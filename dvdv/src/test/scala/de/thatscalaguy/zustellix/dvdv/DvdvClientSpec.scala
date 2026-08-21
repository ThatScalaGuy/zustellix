package de.thatscalaguy.zustellix.dvdv

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import de.thatscalaguy.zustellix.utils.cert.{
  CertAlias,
  CertCredential,
  CertLoader,
  CertManagerError,
  CertSource,
  InMemoryCertManager
}
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.uri
import org.typelevel.ci.CIString
import pdi.jwt.{JwtCirce, JwtOptions}

import java.nio.file.Paths

class DvdvClientSpec extends CatsEffectSuite {

  private def resourcePath(name: String) =
    Paths.get(getClass.getClassLoader.getResource(name).toURI)

  private val http: Client[IO] = Client.fromHttpApp(HttpApp.notFound[IO])

  test("fromClient raises Config when certSource is not set") {
    DvdvClient
      .fromClient[IO](DvdvConfig(baseUri = uri"http://dvdv.test"), http)
      .use_
      .attempt
      .map {
        case Left(e: DvdvError.Config) => assert(e.getMessage.contains("certSource"), e.getMessage)
        case other                     => fail(s"expected DvdvError.Config, got $other")
      }
  }

  test("fromClient accepts a certSource carried as bytes") {
    for {
      bytes <- IO.blocking(java.nio.file.Files.readAllBytes(resourcePath("test-cert.p12")))
      cfg = DvdvConfig(
              baseUri    = uri"http://dvdv.test",
              certSource = Some(CertSource.Pkcs12Bytes(bytes, "test"))
            )
      _ <- DvdvClient.fromClient[IO](cfg, http).use_
    } yield ()
  }

  private def unauthenticatedEntryPathTest(
      entryPath: DvdvEntryPath,
      versionSegments: List[String]
  ): IO[Unit] =
    for {
      authHeaders <- Ref.of[IO, List[Option[String]]](Nil)
      tokenPosts  <- Ref.of[IO, Int](0)
      routes = HttpRoutes.of[IO] {
                 case req @ GET -> path if path.segments.map(_.decoded()).toList == versionSegments =>
                   authHeaders.update(_ :+ req.headers.get(CIString("Authorization")).map(_.head.value)) *>
                     Ok(Json.fromString("v1"))
                 case POST -> Root / "extern" / "standaloneauth" / "token" =>
                   tokenPosts.update(_ + 1) *> Ok(Json.obj())
               }
      cfg = DvdvConfig(baseUri = uri"http://dvdv.test", entryPath = entryPath) // no certSource
      v     <- DvdvClient.fromClient[IO](cfg, Client.fromHttpApp(routes.orNotFound)).use(_.serviceVersion)
      auths <- authHeaders.get
      posts <- tokenPosts.get
    } yield {
      assertEquals(v.raw, Some("v1"))
      assertEquals(auths, List(None)) // exactly one request, no Authorization header
      assertEquals(posts, 0)          // no token POST
    }

  test("fromClient with InternDirectory needs no cert and sends unauthenticated requests") {
    unauthenticatedEntryPathTest(
      DvdvEntryPath.InternDirectory,
      List("intern", "directory", "v2", "version")
    )
  }

  test("fromClient with BundesmasterAuth targets extern/bundesmasterauth/directory and wires no auth") {
    unauthenticatedEntryPathTest(
      DvdvEntryPath.BundesmasterAuth,
      List("extern", "bundesmasterauth", "directory", "v2", "version")
    )
  }

  test("failover to a backup behind a path prefix rebases directory and token requests") {
    for {
      bytes <- IO.blocking(java.nio.file.Files.readAllBytes(resourcePath("test-cert.p12")))
      hits  <- Ref.of[IO, List[(String, String)]](Nil)
      backend = HttpApp[IO] { req =>
                  val host = req.uri.authority.map(_.host.value).getOrElse("?")
                  hits.update(_ :+ (host -> req.uri.path.renderString)) *> {
                    if (host == "primary") IO(Response[IO](Status.ServiceUnavailable))
                    else
                      (req.method, req.uri.path.renderString) match {
                        case (Method.POST, "/dvdv2-backend/extern/standaloneauth/token") =>
                          Ok(Json.obj(
                            "access_token" -> Json.fromString("tok"),
                            "expires_in"   -> Json.fromLong(300L),
                            "token_type"   -> Json.fromString("Bearer")
                          ))
                        case (Method.GET, "/dvdv2-backend/extern/standaloneauth/directory/v2/version") =>
                          Ok(Json.fromString("v1"))
                        case _ => NotFound()
                      }
                  }
                }
      cfg = DvdvConfig(
              baseUri         = uri"http://primary",
              certSource      = Some(CertSource.Pkcs12Bytes(bytes, "test")),
              failoverServers = List(uri"http://backup/dvdv2-backend"),
              cacheConfig     = CacheConfig.disabled
            )
      v  <- DvdvClient.fromClient[IO](cfg, Client.fromHttpApp(backend)).use(_.serviceVersion)
      hs <- hits.get
    } yield {
      assertEquals(v.raw, Some("v1"))
      assertEquals(
        hs,
        List(
          // token POST addressed at the then-active primary, failed over and re-based
          "primary" -> "/extern/standaloneauth/token",
          "backup"  -> "/dvdv2-backend/extern/standaloneauth/token",
          // directory GET routed straight to the sticky backup, prefix prepended
          "backup"  -> "/dvdv2-backend/extern/standaloneauth/directory/v2/version"
        )
      )
    }
  }

  test("fromClient with a CertManager fails fast on an unknown alias") {
    for {
      certs <- InMemoryCertManager.make[IO](Map.empty[CertAlias, CertCredential])
      res   <- DvdvClient
                 .fromClient[IO](DvdvConfig(baseUri = uri"http://dvdv.test"), http, certs, CertAlias("nope"))
                 .use_
                 .attempt
    } yield res match {
      case Left(CertManagerError.UnknownCert(a)) => assertEquals(a, CertAlias("nope"))
      case other                                 => fail(s"expected UnknownCert, got $other")
    }
  }

  test("fromClient with a CertManager signs with the rotated cert after a swap") {
    val alias = CertAlias("tenant")
    for {
      oldP12   <- TestCerts.mintP12("old")
      newP12   <- TestCerts.mintP12("new")
      oldFp    <- CertLoader.loadPkcs12Bytes[IO](oldP12, TestCerts.password).map(_.fingerprintSha1Hex)
      newFp    <- CertLoader.loadPkcs12Bytes[IO](newP12, TestCerts.password).map(_.fingerprintSha1Hex)
      subjects <- Ref.of[IO, List[String]](Nil)
      routes = HttpRoutes.of[IO] {
                 case req @ POST -> Root / "extern" / "standaloneauth" / "token" =>
                   for {
                     form <- req.as[UrlForm]
                     jwt   = form.values.get("client_assertion").flatMap(_.headOption).getOrElse("")
                     sub   = JwtCirce.decode(jwt, JwtOptions(signature = false)).toOption.flatMap(_.subject).getOrElse("")
                     _    <- subjects.update(_ :+ sub)
                     resp <- Ok(Json.obj(
                               "access_token" -> Json.fromString("tok"),
                               "expires_in"   -> Json.fromLong(0L), // expire at once → refresh on every call
                               "token_type"   -> Json.fromString("Bearer")
                             ))
                   } yield resp
                 case GET -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "version" =>
                   Ok(Json.fromString("v1"))
               }
      certs <- InMemoryCertManager.make[IO](Map(alias -> CertCredential(oldP12, TestCerts.password)))
      cfg    = DvdvConfig(baseUri = uri"http://dvdv.test")
      _     <- DvdvClient.fromClient[IO](cfg, Client.fromHttpApp(routes.orNotFound), certs, alias).use { dvdv =>
                 dvdv.serviceVersion *>
                   certs.swap(Map(alias -> CertCredential(newP12, TestCerts.password))) *>
                   dvdv.serviceVersion.void
               }
      subs  <- subjects.get
    } yield assertEquals(subs, List(s"fp:$oldFp", s"fp:$newFp"))
  }
}
