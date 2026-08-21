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
