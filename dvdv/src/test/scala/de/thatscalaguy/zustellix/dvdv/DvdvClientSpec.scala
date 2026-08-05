package de.thatscalaguy.zustellix.dvdv

import cats.effect.IO
import de.thatscalaguy.zustellix.utils.cert.CertSource
import munit.CatsEffectSuite
import org.http4s.HttpApp
import org.http4s.client.Client
import org.http4s.implicits.uri

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
}
