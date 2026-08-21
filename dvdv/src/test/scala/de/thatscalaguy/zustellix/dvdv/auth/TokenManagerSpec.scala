package de.thatscalaguy.zustellix.dvdv.auth

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import de.thatscalaguy.zustellix.dvdv.{DvdvConfig, DvdvError, TestCerts}
import de.thatscalaguy.zustellix.dvdv.internal.FailoverClient
import de.thatscalaguy.zustellix.utils.cert.{CertLoader, CertSource, LoadedCert}
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.uri
import pdi.jwt.{Jwt, JwtAlgorithm, JwtCirce, JwtOptions}

import java.nio.file.Paths
import scala.concurrent.duration.*

class TokenManagerSpec extends CatsEffectSuite {

  private def resourcePath(name: String) =
    Paths.get(getClass.getClassLoader.getResource(name).toURI)

  private val config = DvdvConfig(
    baseUri    = uri"https://dvdv.example",
    certSource = Some(CertSource.Pkcs12(resourcePath("test-cert.p12"), "test"))
  )

  private val loaded: IO[LoadedCert] = CertLoader.load[IO](config.certSource.get)

  /** A manager wired like [[de.thatscalaguy.zustellix.dvdv.DvdvClient]] does it
   *  for a healthy primary: the token endpoint derived from the base URI.
   */
  private def mkManager(
      client: Client[IO],
      cfg: DvdvConfig = config,
      resolveCert: IO[LoadedCert] = loaded
  ): IO[TokenManager[IO]] =
    TokenManager.make[IO](client, cfg, resolveCert, IO.pure(cfg.tokenUriFor(cfg.baseUri)))

  /** Records every captured POST form so assertions can inspect it. */
  private final case class Recorder(count: Ref[IO, Int], lastForm: Ref[IO, Option[UrlForm]])
  private def recorder: IO[Recorder] =
    (Ref.of[IO, Int](0), Ref.of[IO, Option[UrlForm]](None)).mapN(Recorder.apply)

  /** Token endpoint at `extern/standaloneauth/token`; `respond` decides the
   *  reply per call, after the POST has been counted and its form captured.
   */
  private def tokenClient(rec: Recorder)(respond: Int => IO[Response[IO]]): Client[IO] = {
    val routes = HttpRoutes.of[IO] {
      case req @ POST -> Root / "extern" / "standaloneauth" / "token" =>
        for {
          form <- req.as[UrlForm]
          _    <- rec.lastForm.set(Some(form))
          n    <- rec.count.updateAndGet(_ + 1)
          resp <- respond(n)
        } yield resp
    }
    Client.fromHttpApp(routes.orNotFound)
  }

  private def accessTokenJson(token: String, expiresIn: Long): Json =
    Json.obj(
      "access_token" -> Json.fromString(token),
      "expires_in"   -> Json.fromLong(expiresIn),
      "token_type"   -> Json.fromString("Bearer")
    )

  test("happy path: posts the jwt-bearer form and returns the access token") {
    for {
      rec <- recorder
      tm  <- mkManager(tokenClient(rec)(_ => Ok(accessTokenJson("tok-123", 3600))))
      tok  <- tm.bearer
      n    <- rec.count.get
      form <- rec.lastForm.get.map(_.get)
    } yield {
      assertEquals(tok, "tok-123")
      assertEquals(n, 1)
      assertEquals(form.values.get("grant_type").flatMap(_.headOption), Some("client_credentials"))
      assertEquals(
        form.values.get("client_assertion_type").flatMap(_.headOption),
        Some("urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
      )
      assert(
        form.values.get("client_assertion").flatMap(_.headOption).exists(_.nonEmpty),
        "client_assertion must be a non-empty JWT"
      )
    }
  }

  test("401 from the token endpoint surfaces as DvdvError.AuthenticationError") {
    val problem = Json.obj(
      "title"  -> Json.fromString("invalid_client"),
      "detail" -> Json.fromString("bad assertion"),
      "status" -> Json.fromInt(401)
    )
    for {
      rec <- recorder
      tm  <- mkManager(tokenClient(rec)(_ => IO(Response[IO](Status.Unauthorized).withEntity(problem))))
      res <- tm.bearer.attempt
    } yield res match {
      case Left(DvdvError.AuthenticationError(p)) =>
        assertEquals(p.detail, Some("bad assertion"))
      case other => fail(s"expected AuthenticationError, got: $other")
    }
  }

  test("caching: two bearer calls within the skew window trigger exactly one POST") {
    for {
      rec <- recorder
      tm  <- mkManager(tokenClient(rec)(_ => Ok(accessTokenJson("cached", 3600))))
      a <- tm.bearer
      b <- tm.bearer
      n <- rec.count.get
    } yield {
      assertEquals(a, "cached")
      assertEquals(b, "cached")
      assertEquals(n, 1)
    }
  }

  test("invalidate forces the next bearer to re-fetch (a second POST)") {
    for {
      rec <- recorder
      // each acquisition returns a distinct token so the refresh is observable
      tm  <- mkManager(tokenClient(rec)(n => Ok(accessTokenJson(s"tok-$n", 3600))))
      first <- tm.bearer
      _     <- tm.invalidate(first)
      again <- tm.bearer
      n     <- rec.count.get
    } yield {
      assertEquals(first, "tok-1")
      assertEquals(again, "tok-2")
      assertEquals(n, 2)
    }
  }

  test("invalidate with a non-matching token keeps the cached token (no re-fetch)") {
    for {
      rec <- recorder
      tm  <- mkManager(tokenClient(rec)(n => Ok(accessTokenJson(s"tok-$n", 3600))))
      first <- tm.bearer
      _     <- tm.invalidate("not-the-cached-token")
      again <- tm.bearer
      n     <- rec.count.get
    } yield {
      assertEquals(first, "tok-1")
      assertEquals(again, "tok-1")
      assertEquals(n, 1)
    }
  }

  test("stale invalidation after a refresh is a no-op (no second re-fetch)") {
    for {
      rec <- recorder
      tm  <- mkManager(tokenClient(rec)(n => Ok(accessTokenJson(s"tok-$n", 3600))))
      first <- tm.bearer
      _     <- tm.invalidate(first)
      fresh <- tm.bearer
      _     <- tm.invalidate(first) // a second fiber's late 401 carrying the OLD token
      again <- tm.bearer
      n     <- rec.count.get
    } yield {
      assertEquals(first, "tok-1")
      assertEquals(fresh, "tok-2")
      assertEquals(again, "tok-2")
      assertEquals(n, 2)
    }
  }

  test("stampede: concurrent 401s carrying the old token trigger exactly one refresh") {
    val N = 32
    for {
      rec <- recorder
      tm  <- mkManager(tokenClient(rec)(n => Ok(accessTokenJson(s"tok-$n", 3600))))
      old  <- tm.bearer // tok-1, POST 1
      toks <- (tm.invalidate(old) *> tm.bearer).parReplicateA(N)
      n    <- rec.count.get
    } yield {
      assertEquals(old, "tok-1")
      assertEquals(toks.toSet, Set("tok-2"))
      assertEquals(n, 2)
    }
  }

  test("concurrency: parallel bearers from a cold cache trigger exactly one POST") {
    val N = 32
    for {
      rec <- recorder
      tm  <- mkManager(tokenClient(rec)(_ => Ok(accessTokenJson("once", 3600))))
      toks <- tm.bearer.parReplicateA(N)
      n    <- rec.count.get
    } yield {
      assertEquals(toks.toSet, Set("once"))
      assertEquals(n, 1)
    }
  }

  private def lastAssertion(rec: Recorder): IO[String] =
    rec.lastForm.get.map(_.flatMap(_.values.get("client_assertion")).flatMap(_.headOption).get)

  test("the cert is resolved once per token acquisition, not at construction") {
    for {
      rec      <- recorder
      resolves <- Ref.of[IO, Int](0)
      tm       <- mkManager(
                    tokenClient(rec)(n => Ok(accessTokenJson(s"tok-$n", 3600))),
                    resolveCert = resolves.update(_ + 1) *> loaded
                  )
      n0 <- resolves.get
      t1 <- tm.bearer
      _  <- tm.bearer // still cached — no new resolution
      n1 <- resolves.get
      _  <- tm.invalidate(t1)
      _  <- tm.bearer
      n2 <- resolves.get
    } yield {
      assertEquals(n0, 0)
      assertEquals(n1, 1)
      assertEquals(n2, 2)
    }
  }

  test("rotation: each token refresh signs the client_assertion with the freshly resolved cert") {
    for {
      rec     <- recorder
      old     <- loaded
      rotated <- TestCerts.mintLoadedCert("rotated")
      current <- Ref.of[IO, LoadedCert](old)
      tm      <- mkManager(
                   tokenClient(rec)(n => Ok(accessTokenJson(s"tok-$n", 3600))),
                   resolveCert = current.get
                 )
      t1 <- tm.bearer
      a1 <- lastAssertion(rec)
      _  <- current.set(rotated) // the rotation
      _  <- tm.invalidate(t1)    // e.g. the AuthMiddleware 401 path
      _  <- tm.bearer
      a2 <- lastAssertion(rec)
    } yield {
      def subject(jwt: String): Option[String] =
        JwtCirce.decode(jwt, JwtOptions(signature = false)).toOption.flatMap(_.subject)
      assertEquals(subject(a1), Some(s"fp:${old.fingerprintSha1Hex}"))
      assertEquals(subject(a2), Some(s"fp:${rotated.fingerprintSha1Hex}"))
      assert(
        Jwt.isValid(a2, rotated.certificate.getPublicKey, Seq(JwtAlgorithm.RS256)),
        "assertion after rotation must verify against the rotated key"
      )
      assert(
        !Jwt.isValid(a2, old.certificate.getPublicKey, Seq(JwtAlgorithm.RS256)),
        "assertion after rotation must no longer verify against the old key"
      )
    }
  }

  private def audOf(jwt: String): Option[Set[String]] =
    JwtCirce.decode(jwt, JwtOptions(signature = false)).toOption.flatMap(_.audience)

  test("token POST goes to tokenEndpoint when overridden, and aud defaults to it") {
    val customEp = uri"https://auth.example/custom/token"
    val cfg      = config.copy(tokenEndpoint = Some(customEp))
    for {
      seen <- Ref.of[IO, List[Uri]](Nil)
      form <- Ref.of[IO, Option[UrlForm]](None)
      client = Client.fromHttpApp(
                 HttpRoutes
                   .of[IO] { case req @ POST -> Root / "custom" / "token" =>
                     for {
                       _    <- seen.update(_ :+ req.uri)
                       f    <- req.as[UrlForm]
                       _    <- form.set(Some(f))
                       resp <- Ok(accessTokenJson("tok-custom", 3600))
                     } yield resp
                   }
                   .orNotFound
               )
      tm   <- mkManager(client, cfg)
      tok  <- tm.bearer
      uris <- seen.get
      a    <- form.get.map(_.flatMap(_.values.get("client_assertion")).flatMap(_.headOption).get)
    } yield {
      assertEquals(tok, "tok-custom")
      assertEquals(uris, List(customEp))
      assertEquals(audOf(a), Some(Set(customEp.renderString)))
    }
  }

  /** Backend for the failover tests: the primary answers 500, the secondary
   *  serves the token and records every client_assertion it receives.
   */
  private def failingPrimaryBackend(asserts: Ref[IO, List[String]]): Client[IO] =
    Client.fromHttpApp(HttpApp[IO] { req =>
      req.uri.authority.map(_.host.value) match {
        case Some("secondary") =>
          for {
            f    <- req.as[UrlForm]
            _    <- asserts.update(_ :+ f.values.get("client_assertion").flatMap(_.headOption).get)
            resp <- Ok(accessTokenJson("tok-failover", 3600))
          } yield resp
        case _ => IO(Response[IO](Status.InternalServerError))
      }
    })

  test("failover: aud follows the failed-over endpoint by default (one-refresh lag)") {
    val cfg = config.copy(
      baseUri         = uri"https://primary",
      failoverServers = List(uri"https://secondary")
    )
    for {
      asserts <- Ref.of[IO, List[String]](Nil)
      h       <- FailoverClient.make[IO](cfg.servers, 180.seconds)
      tm      <- TokenManager.make[IO](
                   h.middleware(failingPrimaryBackend(asserts)),
                   cfg,
                   loaded,
                   h.activeServer.map(cfg.tokenUriFor)
                 )
      t1 <- tm.bearer // POST fails over to the secondary mid-request
      _  <- tm.invalidate(t1)
      _  <- tm.bearer // refresh minted after the sticky switch
      as <- asserts.get
    } yield {
      assertEquals(as.size, 2)
      // The first assertion is minted before the failover happens, so it still
      // carries the primary's endpoint as aud — the documented one-refresh lag.
      assertEquals(audOf(as(0)), Some(Set(cfg.tokenUriFor(uri"https://primary").renderString)))
      // The next refresh converges on the endpoint actually contacted.
      assertEquals(audOf(as(1)), Some(Set(cfg.tokenUriFor(uri"https://secondary").renderString)))
    }
  }

  test("jwtAudience pins aud across failover") {
    val cfg = config.copy(
      baseUri         = uri"https://primary",
      failoverServers = List(uri"https://secondary"),
      jwtAudience     = Some("urn:pinned")
    )
    for {
      asserts <- Ref.of[IO, List[String]](Nil)
      h       <- FailoverClient.make[IO](cfg.servers, 180.seconds)
      tm      <- TokenManager.make[IO](
                   h.middleware(failingPrimaryBackend(asserts)),
                   cfg,
                   loaded,
                   h.activeServer.map(cfg.tokenUriFor)
                 )
      t1 <- tm.bearer
      _  <- tm.invalidate(t1)
      _  <- tm.bearer
      as <- asserts.get
    } yield {
      assertEquals(as.size, 2)
      assertEquals(audOf(as(0)), Some(Set("urn:pinned")))
      assertEquals(audOf(as(1)), Some(Set("urn:pinned")))
    }
  }
}
