package de.thatscalaguy.zustellix.dvdv.auth

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import de.thatscalaguy.zustellix.dvdv.{DvdvConfig, DvdvError}
import de.thatscalaguy.zustellix.utils.cert.{CertLoader, CertSource, LoadedCert}
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.uri

import java.nio.file.Paths

class TokenManagerSpec extends CatsEffectSuite {

  private def resourcePath(name: String) =
    Paths.get(getClass.getClassLoader.getResource(name).toURI)

  private val config = DvdvConfig(
    baseUri    = uri"https://dvdv.example",
    certSource = CertSource.Pkcs12(resourcePath("test-cert.p12"), "test")
  )

  private val loaded: IO[LoadedCert] = CertLoader.load[IO](config.certSource)

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
      l   <- loaded
      tm  <- TokenManager.make[IO](
               tokenClient(rec)(_ => Ok(accessTokenJson("tok-123", 3600))),
               config,
               l
             )
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
      l   <- loaded
      tm  <- TokenManager.make[IO](
               tokenClient(rec)(_ => IO(Response[IO](Status.Unauthorized).withEntity(problem))),
               config,
               l
             )
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
      l   <- loaded
      tm  <- TokenManager.make[IO](
               tokenClient(rec)(_ => Ok(accessTokenJson("cached", 3600))),
               config,
               l
             )
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
      l   <- loaded
      tm  <- TokenManager.make[IO](
               // each acquisition returns a distinct token so the refresh is observable
               tokenClient(rec)(n => Ok(accessTokenJson(s"tok-$n", 3600))),
               config,
               l
             )
      first <- tm.bearer
      _     <- tm.invalidate
      again <- tm.bearer
      n     <- rec.count.get
    } yield {
      assertEquals(first, "tok-1")
      assertEquals(again, "tok-2")
      assertEquals(n, 2)
    }
  }

  test("concurrency: parallel bearers from a cold cache trigger exactly one POST") {
    val N = 32
    for {
      rec <- recorder
      l   <- loaded
      tm  <- TokenManager.make[IO](
               tokenClient(rec)(_ => Ok(accessTokenJson("once", 3600))),
               config,
               l
             )
      toks <- tm.bearer.parReplicateA(N)
      n    <- rec.count.get
    } yield {
      assertEquals(toks.toSet, Set("once"))
      assertEquals(n, 1)
    }
  }
}
