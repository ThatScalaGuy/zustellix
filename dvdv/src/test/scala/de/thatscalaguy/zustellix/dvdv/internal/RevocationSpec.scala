package de.thatscalaguy.zustellix.dvdv.internal

import cats.effect.IO
import de.thatscalaguy.zustellix.dvdv.{DvdvConfig, DvdvError}
import de.thatscalaguy.zustellix.utils.cert.CertSource
import de.thatscalaguy.zustellix.dvdv.model.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.uri

import java.nio.file.Paths

class RevocationSpec extends CatsEffectSuite {

  private def resourcePath(name: String) =
    Paths.get(getClass.getClassLoader.getResource(name).toURI)

  private def config(ignoreRevocation: Boolean = false) = DvdvConfig(
    baseUri          = uri"http://dvdv.test",
    certSource       = CertSource.Pkcs12(resourcePath("test-cert.p12"), "test"),
    ignoreRevocation = ignoreRevocation
  )

  private def client(routes: HttpRoutes[IO], cfg: DvdvConfig): HttpDvdvClient[IO] =
    HttpDvdvClient[IO](Client.fromHttpApp(routes.orNotFound), cfg)

  private def routesReturning(cert: Certificate): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "findCertificateByFingerprint" :? _ =>
        Ok(cert.asJson)
    }

  private val revokedCert = Certificate(
    fingerprint      = Some("deadbeef"),
    revocationDate   = Some("2026-01-01T00:00:00Z"),
    revocationReason = Some(RevocationReason.KEY_COMPROMISE)
  )

  test("findCertificateByFingerprint raises CertificateRevoked for a revoked cert") {
    val c = client(routesReturning(revokedCert), config())
    c.findCertificateByFingerprint("deadbeef").attempt.map {
      case Left(DvdvError.CertificateRevoked(date, reason)) =>
        assertEquals(date, Some("2026-01-01T00:00:00Z"))
        assertEquals(reason, Some(RevocationReason.KEY_COMPROMISE))
      case other =>
        fail(s"expected CertificateRevoked, got $other")
    }
  }

  test("findCertificateByFingerprint returns the cert when ignoreRevocation = true") {
    val c = client(routesReturning(revokedCert), config(ignoreRevocation = true))
    c.findCertificateByFingerprint("deadbeef").map(r => assertEquals(r, Some(revokedCert)))
  }

  test("findCertificateByFingerprint returns the cert when not revoked") {
    val cert = Certificate(fingerprint = Some("deadbeef"))
    val c    = client(routesReturning(cert), config())
    c.findCertificateByFingerprint("deadbeef").map(r => assertEquals(r, Some(cert)))
  }
}
