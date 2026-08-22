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
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

import java.nio.file.Paths
import java.time.Instant

class RevocationSpec extends CatsEffectSuite {

  private given LoggerFactory[IO] = NoOpFactory[IO]

  private def resourcePath(name: String) =
    Paths.get(getClass.getClassLoader.getResource(name).toURI)

  private def config(ignoreRevocation: Boolean = false) = DvdvConfig(
    baseUri          = uri"http://dvdv.test",
    certSource       = Some(CertSource.Pkcs12(resourcePath("test-cert.p12"), "test")),
    ignoreRevocation = ignoreRevocation
  )

  private def client(routes: HttpRoutes[IO], cfg: DvdvConfig): HttpDvdvClient[IO] =
    HttpDvdvClient[IO](Client.fromHttpApp(routes.orNotFound), cfg)

  private def routesReturning(cert: Certificate): HttpRoutes[IO] =
    routesReturningJson(cert.asJson)

  private def routesReturningJson(body: io.circe.Json): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "findCertificateByFingerprint" :? _ =>
        Ok(body)
    }

  // Raw JSON so the wire genuinely carries a constant this client's enum
  // does not know.
  private val unknownReasonBody = io.circe.Json.obj(
    "fingerprint"      -> io.circe.Json.fromString("deadbeef"),
    "revocationDate"   -> io.circe.Json.fromString("2026-01-01T00:00:00Z"),
    "revocationReason" -> io.circe.Json.fromString("SOME_FUTURE_REASON")
  )

  private val TestFp = Fingerprint.unsafe("0272c56c9742a62501329a3aa78974f1605c92a2")

  private val revokedCert = Certificate(
    fingerprint      = Some("deadbeef"),
    revocationDate   = Some("2026-01-01T00:00:00Z"),
    revocationReason = Some(RevocationReason.KEY_COMPROMISE)
  )

  test("findCertificateByFingerprint raises CertificateRevoked for a revoked cert") {
    val c = client(routesReturning(revokedCert), config())
    c.findCertificateByFingerprint(TestFp).attempt.map {
      case Left(DvdvError.CertificateRevoked(date, rawDate, reason)) =>
        assertEquals(date, Some(Instant.parse("2026-01-01T00:00:00Z")))
        assertEquals(rawDate, Some("2026-01-01T00:00:00Z"))
        assertEquals(reason, Some(RevocationReason.KEY_COMPROMISE))
      case other =>
        fail(s"expected CertificateRevoked, got $other")
    }
  }

  test("CertificateRevoked keeps an unparseable revocation date as rawDate") {
    val cert = Certificate(
      fingerprint      = Some("deadbeef"),
      revocationDate   = Some("not-a-date"),
      revocationReason = Some(RevocationReason.UNSPECIFIED)
    )
    val c = client(routesReturning(cert), config())
    c.findCertificateByFingerprint(TestFp).attempt.map {
      case Left(DvdvError.CertificateRevoked(date, rawDate, reason)) =>
        assertEquals(date, None)
        assertEquals(rawDate, Some("not-a-date"))
        assertEquals(reason, Some(RevocationReason.UNSPECIFIED))
      case other =>
        fail(s"expected CertificateRevoked, got $other")
    }
  }

  test("raises CertificateRevoked for a cert revoked with an unknown reason") {
    val c = client(routesReturningJson(unknownReasonBody), config())
    c.findCertificateByFingerprint(TestFp).attempt.map {
      case Left(DvdvError.CertificateRevoked(date, rawDate, reason)) =>
        assertEquals(date, Some(Instant.parse("2026-01-01T00:00:00Z")))
        assertEquals(rawDate, Some("2026-01-01T00:00:00Z"))
        assertEquals(reason, Some(RevocationReason.Other("SOME_FUTURE_REASON")))
      case other =>
        fail(s"expected CertificateRevoked, got $other")
    }
  }

  test("raises CertificateRevoked when only revocationReason is set") {
    val cert = Certificate(
      fingerprint      = Some("deadbeef"),
      revocationReason = Some(RevocationReason.CERTIFICATE_HOLD)
    )
    val c = client(routesReturning(cert), config())
    c.findCertificateByFingerprint(TestFp).attempt.map {
      case Left(DvdvError.CertificateRevoked(date, rawDate, reason)) =>
        assertEquals(date, None)
        assertEquals(rawDate, None)
        assertEquals(reason, Some(RevocationReason.CERTIFICATE_HOLD))
      case other =>
        fail(s"expected CertificateRevoked, got $other")
    }
  }

  test("returns the cert when ignoreRevocation = true even with an unknown reason") {
    val c = client(routesReturningJson(unknownReasonBody), config(ignoreRevocation = true))
    c.findCertificateByFingerprint(TestFp).map { r =>
      assertEquals(r.flatMap(_.revocationReason), Some(RevocationReason.Other("SOME_FUTURE_REASON")))
    }
  }

  test("findCertificateByFingerprint returns the cert when ignoreRevocation = true") {
    val c = client(routesReturning(revokedCert), config(ignoreRevocation = true))
    c.findCertificateByFingerprint(TestFp).map(r => assertEquals(r, Some(revokedCert)))
  }

  test("findCertificateByFingerprint returns the cert when not revoked") {
    val cert = Certificate(fingerprint = Some("deadbeef"))
    val c    = client(routesReturning(cert), config())
    c.findCertificateByFingerprint(TestFp).map(r => assertEquals(r, Some(cert)))
  }
}
