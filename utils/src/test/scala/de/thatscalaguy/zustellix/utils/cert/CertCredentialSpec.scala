package de.thatscalaguy.zustellix.utils.cert

import cats.effect.IO
import munit.CatsEffectSuite

import java.io.StringWriter
import java.math.BigInteger
import java.nio.file.{Files, Paths}
import java.security.cert.X509Certificate
import java.security.{KeyPair, KeyPairGenerator}
import java.util.Date
import javax.security.auth.x500.X500Principal

class CertCredentialSpec extends CatsEffectSuite {

  private val Secret = "sup3rsecret"

  private def resourcePath(name: String) =
    Paths.get(getClass.getClassLoader.getResource(name).toURI)

  test("toString reports the size and redacts the password") {
    val s = CertCredential(Array.fill[Byte](7)(1), Secret).toString
    assert(!s.contains(Secret), s)
    assert(s.contains("7 bytes"), s)
    assert(s.contains("<redacted>"), s)
  }

  // --- fromSource: CertSource -> in-memory PKCS12 credential ---

  test("fromSource(Pkcs12Bytes) passes the bytes and password through") {
    for {
      bytes  <- IO.blocking(Files.readAllBytes(resourcePath("test-cert.p12")))
      cred   <- CertCredential.fromSource[IO](CertSource.Pkcs12Bytes(bytes, "test"))
      loaded <- cred.loadedCert[IO]
      direct <- CertLoader.load[IO](CertSource.Pkcs12Bytes(bytes, "test"))
    } yield {
      assertEquals(cred.password, "test")
      assertEquals(loaded.fingerprintSha256Hex, direct.fingerprintSha256Hex)
    }
  }

  test("fromSource(Pkcs12) reads the file and preserves the password") {
    val src = CertSource.Pkcs12(resourcePath("test-cert.p12"), "test")
    for {
      cred   <- CertCredential.fromSource[IO](src)
      loaded <- cred.loadedCert[IO]
      direct <- CertLoader.load[IO](src)
    } yield {
      assertEquals(cred.password, "test")
      assertEquals(loaded.fingerprintSha256Hex, direct.fingerprintSha256Hex)
    }
  }

  test("fromSource(Pem) repacks cert + key into a loadable in-memory PKCS12") {
    val src = CertSource.Pem(resourcePath("test-cert.pem"), resourcePath("test-key.pem"), None)
    for {
      cred   <- CertCredential.fromSource[IO](src)
      loaded <- cred.loadedCert[IO]
      direct <- CertLoader.load[IO](src)
    } yield {
      assertEquals(loaded.fingerprintSha256Hex, direct.fingerprintSha256Hex)
      assertEquals(loaded.privateKey.getAlgorithm, "RSA")
    }
  }

  test("fromSource(PemBytes) yields the same certificate as fromSource(Pem)") {
    for {
      certBytes <- IO.blocking(Files.readAllBytes(resourcePath("test-cert.pem")))
      keyBytes  <- IO.blocking(Files.readAllBytes(resourcePath("test-key.pem")))
      fromBytes <- CertCredential.fromSource[IO](CertSource.PemBytes(certBytes, keyBytes, None))
      fromPath  <- CertCredential.fromSource[IO](
                     CertSource.Pem(resourcePath("test-cert.pem"), resourcePath("test-key.pem"), None)
                   )
      lb <- fromBytes.loadedCert[IO]
      lp <- fromPath.loadedCert[IO]
    } yield assertEquals(lb.fingerprintSha256Hex, lp.fingerprintSha256Hex)
  }

  test("fromSource(PemBytes) with a leaf + CA bundle keeps the full chain leaf-first") {
    for {
      material <- mintChain()
      certPem  <- IO.blocking(toPem(material.leafCert, material.caCert))
      keyPem   <- IO.blocking(toPem(material.leafKey.getPrivate))
      cred     <- CertCredential.fromSource[IO](
                    CertSource.PemBytes(certPem.getBytes("UTF-8"), keyPem.getBytes("UTF-8"), None)
                  )
      loaded   <- cred.loadedCert[IO]
    } yield {
      assertEquals(loaded.chain.length, 2)
      assertEquals(loaded.chain.head, loaded.certificate)
      assertEquals(loaded.certificate.getSubjectX500Principal, new X500Principal("CN=chain-leaf"))
      assertEquals(loaded.chain(1).getSubjectX500Principal, new X500Principal("CN=chain-ca"))
    }
  }

  test("PEM keyPassword becomes the credential password; without one a fresh password is generated") {
    for {
      certBytes <- IO.blocking(Files.readAllBytes(resourcePath("test-cert.pem")))
      keyBytes  <- IO.blocking(Files.readAllBytes(resourcePath("test-key.pem")))
      withPw    <- CertCredential.fromSource[IO](CertSource.PemBytes(certBytes, keyBytes, Some(Secret)))
      without   <- CertCredential.fromSource[IO](CertSource.PemBytes(certBytes, keyBytes, None))
      loaded    <- without.loadedCert[IO]
    } yield {
      assertEquals(withPw.password, Secret)
      assert(without.password.nonEmpty)
      assertEquals(loaded.privateKey.getAlgorithm, "RSA")
    }
  }

  test("fromSource fails on unparseable PEM bytes with CertLoader's error") {
    interceptIO[IllegalArgumentException](
      CertCredential.fromSource[IO](
        CertSource.PemBytes(Array.emptyByteArray, Array.emptyByteArray, None)
      )
    ).map(e => assert(e.getMessage.contains("No certificate found"), e.getMessage))
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

  private def toPem(objects: AnyRef*): String = {
    val sw     = new StringWriter()
    val writer = new org.bouncycastle.openssl.jcajce.JcaPEMWriter(sw)
    try objects.foreach(writer.writeObject)
    finally writer.close()
    sw.toString
  }
}
