package de.thatscalaguy.zustellix.utils.cert

import cats.effect.IO
import munit.CatsEffectSuite

import java.io.{ByteArrayOutputStream, StringWriter}
import java.math.BigInteger
import java.nio.file.{Files, Paths}
import java.security.cert.X509Certificate
import java.security.{KeyPair, KeyPairGenerator, KeyStore}
import java.util.Date
import javax.security.auth.x500.X500Principal

class CertLoaderSpec extends CatsEffectSuite {

  java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())

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

  test("CertSource.Pkcs12Bytes yields the same fingerprint as CertSource.Pkcs12") {
    for {
      bytes     <- IO.blocking(Files.readAllBytes(resourcePath("test-cert.p12")))
      fromBytes <- CertLoader.load[IO](CertSource.Pkcs12Bytes(bytes, "test"))
      fromPath  <- CertLoader.load[IO](CertSource.Pkcs12(resourcePath("test-cert.p12"), "test"))
    } yield assertEquals(fromBytes.fingerprintSha1Hex, fromPath.fingerprintSha1Hex)
  }

  test("CertSource.PemBytes yields the same fingerprint as CertSource.Pem") {
    for {
      certBytes <- IO.blocking(Files.readAllBytes(resourcePath("test-cert.pem")))
      keyBytes  <- IO.blocking(Files.readAllBytes(resourcePath("test-key.pem")))
      fromBytes <- CertLoader.load[IO](CertSource.PemBytes(certBytes, keyBytes, None))
      fromPath  <- CertLoader.load[IO](
                     CertSource.Pem(resourcePath("test-cert.pem"), resourcePath("test-key.pem"), None)
                   )
    } yield {
      assertEquals(fromBytes.fingerprintSha1Hex, fromPath.fingerprintSha1Hex)
      assertEquals(fromBytes.privateKey.getAlgorithm, "RSA")
    }
  }

  // --- Chain material minted in-test with BouncyCastle (no checked-in fixtures) ---

  private case class ChainMaterial(leafKey: KeyPair, leafCert: X509Certificate, caCert: X509Certificate)

  private def rsaKeyPair(): KeyPair = {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    kpg.generateKeyPair()
  }

  private def mintCert(
      subject: String,
      subjectKey: KeyPair,
      issuer: String,
      issuerKey: KeyPair
  ): X509Certificate = {
    val now   = new Date()
    val later = new Date(now.getTime + 86400000L)
    val builder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
      new X500Principal(s"CN=$issuer"),
      BigInteger.valueOf(System.nanoTime()),
      now,
      later,
      new X500Principal(s"CN=$subject"),
      subjectKey.getPublic
    )
    val signer =
      new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(issuerKey.getPrivate)
    new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(builder.build(signer))
  }

  private def mintChain(): IO[ChainMaterial] = IO.blocking {
    val caKey    = rsaKeyPair()
    val caCert   = mintCert("chain-ca", caKey, "chain-ca", caKey)
    val leafKey  = rsaKeyPair()
    val leafCert = mintCert("chain-leaf", leafKey, "chain-ca", caKey)
    ChainMaterial(leafKey, leafCert, caCert)
  }

  private def toP12(material: ChainMaterial, password: String): IO[Array[Byte]] = IO.blocking {
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    ks.setKeyEntry(
      "chain-leaf",
      material.leafKey.getPrivate,
      password.toCharArray,
      Array(material.leafCert, material.caCert)
    )
    val out = new ByteArrayOutputStream()
    ks.store(out, password.toCharArray)
    out.toByteArray
  }

  private def toPem(objects: AnyRef*): String = {
    val sw     = new StringWriter()
    val writer = new org.bouncycastle.openssl.jcajce.JcaPEMWriter(sw)
    try objects.foreach(writer.writeObject)
    finally writer.close()
    sw.toString
  }

  test("PKCS12 with a CA chain returns the full chain leaf-first") {
    for {
      material <- mintChain()
      bytes    <- toP12(material, "pw")
      loaded   <- CertLoader.loadPkcs12Bytes[IO](bytes, "pw")
    } yield {
      assertEquals(loaded.chain.length, 2)
      assertEquals(loaded.chain.head, loaded.certificate)
      assertEquals(loaded.chain(1).getSubjectX500Principal, material.caCert.getSubjectX500Principal)
      assertEquals(loaded.certificate.getSubjectX500Principal, material.leafCert.getSubjectX500Principal)
    }
  }

  test("PEM bundle with leaf and CA returns the full chain leaf-first") {
    for {
      material <- mintChain()
      certPem  <- IO.blocking(toPem(material.leafCert, material.caCert))
      keyPem   <- IO.blocking(toPem(material.leafKey.getPrivate))
      loaded   <- CertLoader.load[IO](
                    CertSource.PemBytes(certPem.getBytes("UTF-8"), keyPem.getBytes("UTF-8"), None)
                  )
    } yield {
      assertEquals(loaded.chain.length, 2)
      assertEquals(loaded.chain.head, loaded.certificate)
      assertEquals(loaded.certificate.getSubjectX500Principal, material.leafCert.getSubjectX500Principal)
      assertEquals(loaded.chain(1).getSubjectX500Principal, material.caCert.getSubjectX500Principal)
    }
  }

  test("single-certificate sources yield chain == List(certificate)") {
    for {
      p12 <- CertLoader.load[IO](CertSource.Pkcs12(resourcePath("test-cert.p12"), "test"))
      pem <- CertLoader.load[IO](CertSource.Pem(resourcePath("test-cert.pem"), resourcePath("test-key.pem"), None))
    } yield {
      assertEquals(p12.chain, List(p12.certificate))
      assertEquals(pem.chain, List(pem.certificate))
    }
  }
}
