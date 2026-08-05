package de.thatscalaguy.zustellix.osci.internal

import cats.effect.IO
import de.thatscalaguy.zustellix.osci.OsciError
import de.thatscalaguy.zustellix.utils.cert.CertSource
import munit.CatsEffectSuite

import java.nio.file.{Files, Path, Paths}

/** Which `CertSource` shapes the bridge accepts. osci-bibliothek's
 *  `PKCS12Signer`/`PKCS12Decrypter` take a PKCS12 stream only, so both PEM
 *  variants are rejected up front rather than deep inside a send.
 */
class OsciBibBridgeCertSpec extends CatsEffectSuite {

  private def p12Path: Path =
    Paths.get(getClass.getClassLoader.getResource("test-cert.p12").toURI)

  test("Pkcs12 builds an Originator") {
    OsciBibBridge.originator[IO](CertSource.Pkcs12(p12Path, "test")).map(o => assert(o != null))
  }

  test("Pkcs12Bytes builds an Originator with the same certificate as Pkcs12") {
    for {
      bytes     <- IO.blocking(Files.readAllBytes(p12Path))
      fromBytes <- OsciBibBridge.originator[IO](CertSource.Pkcs12Bytes(bytes, "test"))
      fromPath  <- OsciBibBridge.originator[IO](CertSource.Pkcs12(p12Path, "test"))
    } yield assertEquals(fromBytes.getSignatureCertificate, fromPath.getSignatureCertificate)
  }

  test("Pem is rejected with a Config error") {
    OsciBibBridge
      .originator[IO](CertSource.Pem(Paths.get("c.pem"), Paths.get("k.pem")))
      .attempt
      .map {
        case Left(e: OsciError.Config) => assert(e.getMessage.contains("PKCS12"), e.getMessage)
        case other                     => fail(s"expected OsciError.Config, got $other")
      }
  }

  test("PemBytes is rejected with a Config error") {
    OsciBibBridge
      .originator[IO](CertSource.PemBytes(Array.emptyByteArray, Array.emptyByteArray))
      .attempt
      .map {
        case Left(e: OsciError.Config) => assert(e.getMessage.contains("PKCS12"), e.getMessage)
        case other                     => fail(s"expected OsciError.Config, got $other")
      }
  }
}
