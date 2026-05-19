package de.thatscalaguy.zustellix.utils.cert

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.file.{Files, Paths}

class CertLoaderSpec extends CatsEffectSuite {

  // Set by openssl during fixture generation.
  // Verify with: openssl x509 -fingerprint -sha1 -noout -in test-cert.pem
  // The exact value depends on the generated cert. We assert PKCS12 and PEM yield the *same* fingerprint.

  private def resourcePath(name: String) =
    Paths.get(getClass.getClassLoader.getResource(name).toURI)

  test("PKCS12 loads with the configured password and yields a non-empty fingerprint") {
    CertLoader.load[IO](CertSource.Pkcs12(resourcePath("test-cert.p12"), "test")).map { loaded =>
      assert(loaded.fingerprintSha1Hex.length == 40)
      assert(loaded.fingerprintSha1Hex.matches("^[0-9a-f]+$"))
      assertEquals(loaded.privateKey.getAlgorithm, "RSA")
    }
  }

  test("PEM yields the same fingerprint as PKCS12 for the same cert") {
    for {
      p12 <- CertLoader.load[IO](CertSource.Pkcs12(resourcePath("test-cert.p12"), "test"))
      pem <- CertLoader.load[IO](CertSource.Pem(resourcePath("test-cert.pem"), resourcePath("test-key.pem"), None))
    } yield assertEquals(pem.fingerprintSha1Hex, p12.fingerprintSha1Hex)
  }

  test("loadPkcs12Bytes yields the same fingerprint as the path-based load") {
    for {
      bytes <- IO.blocking(Files.readAllBytes(resourcePath("test-cert.p12")))
      fromBytes <- CertLoader.loadPkcs12Bytes[IO](bytes, "test")
      fromPath  <- CertLoader.load[IO](CertSource.Pkcs12(resourcePath("test-cert.p12"), "test"))
    } yield {
      assertEquals(fromBytes.fingerprintSha1Hex, fromPath.fingerprintSha1Hex)
      assertEquals(fromBytes.privateKey.getAlgorithm, "RSA")
    }
  }
}
