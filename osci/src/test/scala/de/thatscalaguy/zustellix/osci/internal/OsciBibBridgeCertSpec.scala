package de.thatscalaguy.zustellix.osci.internal

import cats.effect.IO
import de.thatscalaguy.zustellix.utils.cert.CertSource
import munit.CatsEffectSuite

import java.nio.file.{Files, Path, Paths}

/** Which `CertSource` shapes the bridge accepts. osci-bibliothek's
 *  `PKCS12Signer`/`PKCS12Decrypter` take a PKCS12 stream only, so the PEM
 *  variants are converted to an in-memory PKCS12 first — all four shapes
 *  build an Originator.
 */
class OsciBibBridgeCertSpec extends CatsEffectSuite {

  private def resourcePath(name: String): Path =
    Paths.get(getClass.getClassLoader.getResource(name).toURI)

  private def p12Path: Path = resourcePath("test-cert.p12")

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

  test("Pem builds an Originator with the same certificate as Pkcs12") {
    for {
      fromPem  <- OsciBibBridge.originator[IO](
                    CertSource.Pem(resourcePath("test-cert.pem"), resourcePath("test-key.pem"))
                  )
      fromP12  <- OsciBibBridge.originator[IO](CertSource.Pkcs12(p12Path, "test"))
    } yield assertEquals(fromPem.getSignatureCertificate, fromP12.getSignatureCertificate)
  }

  test("PemBytes builds an Originator with the same certificate as Pkcs12") {
    for {
      certBytes <- IO.blocking(Files.readAllBytes(resourcePath("test-cert.pem")))
      keyBytes  <- IO.blocking(Files.readAllBytes(resourcePath("test-key.pem")))
      fromPem   <- OsciBibBridge.originator[IO](CertSource.PemBytes(certBytes, keyBytes))
      fromP12   <- OsciBibBridge.originator[IO](CertSource.Pkcs12(p12Path, "test"))
    } yield assertEquals(fromPem.getSignatureCertificate, fromP12.getSignatureCertificate)
  }

  test("unparseable PEM bytes fail") {
    interceptIO[IllegalArgumentException](
      OsciBibBridge.originator[IO](CertSource.PemBytes(Array.emptyByteArray, Array.emptyByteArray))
    ).map { e =>
      assert(
        e.getMessage.contains("No certificate found") || e.getMessage.contains("No private key found"),
        e.getMessage
      )
    }
  }
}
