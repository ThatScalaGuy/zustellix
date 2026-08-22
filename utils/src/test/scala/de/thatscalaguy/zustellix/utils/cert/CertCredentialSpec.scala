package de.thatscalaguy.zustellix.utils.cert

import cats.effect.IO
import munit.CatsEffectSuite

import java.io.{ByteArrayInputStream, StringWriter}
import java.math.BigInteger
import java.nio.file.{Files, Paths}
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.{KeyPair, KeyPairGenerator, KeyStore, PrivateKey}
import java.util.Date
import javax.security.auth.x500.X500Principal
import scala.jdk.CollectionConverters.*

class CertCredentialSpec extends CatsEffectSuite {

  private val Secret = "sup3rsecret"

  private def resourcePath(name: String) =
    Paths.get(getClass.getClassLoader.getResource(name).toURI)

  test("toString reports the size and redacts the password and key bytes") {
    val keyBytes = "KEYMATERIALBYTES".getBytes("UTF-8")
    val s        = CertCredential(keyBytes, Secret).toString
    assert(!s.contains(Secret), s)
    assert(s.contains("16 bytes"), s)
    assert(s.contains("<redacted>"), s)
    assert(!s.contains("KEYMATERIALBYTES"), s)
    assert(!s.contains("[B@"), s)
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

  // --- fromPem: PEM material -> PKCS12 with an explicit store password ---

  test("fromPem output opens as a PKCS12 keystore holding the key and full chain") {
    for {
      material <- mintChain()
      certPem  <- IO.blocking(toPem(material.leafCert, material.caCert))
      keyPem   <- IO.blocking(toPem(material.leafKey.getPrivate))
      cred     <- CertCredential.fromPem[IO](certPem.getBytes("UTF-8"), keyPem.getBytes("UTF-8"), None, Secret)
      loaded   <- cred.loadedCert[IO]
      ks       <- IO.blocking {
                    val ks = KeyStore.getInstance("PKCS12")
                    ks.load(new ByteArrayInputStream(cred.pkcs12), Secret.toCharArray)
                    ks
                  }
    } yield {
      assertEquals(cred.password, Secret)
      assertEquals(ks.aliases().asScala.toList, List("key"))
      assert(ks.entryInstanceOf("key", classOf[KeyStore.PrivateKeyEntry]))
      assertEquals(
        ks.getKey("key", Secret.toCharArray).asInstanceOf[RSAPrivateKey].getModulus,
        material.leafKey.getPrivate.asInstanceOf[RSAPrivateKey].getModulus
      )
      val chain = ks.getCertificateChain("key").toList.map(_.asInstanceOf[X509Certificate])
      assertEquals(
        chain.map(_.getSubjectX500Principal),
        List(new X500Principal("CN=chain-leaf"), new X500Principal("CN=chain-ca"))
      )
      assertEquals(loaded.certificate, material.leafCert)
    }
  }

  test("fromPem separates the PEM key password from the PKCS12 store password") {
    for {
      material  <- mintChain()
      certPem   <- IO.blocking(toPem(material.leafCert))
      encKeyPem <- IO.blocking(toEncryptedPkcs8Pem(material.leafKey.getPrivate, "keypw"))
      cred      <- CertCredential.fromPem[IO](
                     certPem.getBytes("UTF-8"), encKeyPem.getBytes("UTF-8"), Some("keypw"), Secret
                   )
      loaded    <- cred.loadedCert[IO]
      _         <- interceptIO[java.io.IOException](IO.blocking {
                     KeyStore.getInstance("PKCS12").load(new ByteArrayInputStream(cred.pkcs12), "keypw".toCharArray)
                   })
    } yield {
      assertEquals(cred.password, Secret)
      assertEquals(loaded.privateKey.getAlgorithm, "RSA")
    }
  }

  test("fromPem with the wrong key password fails") {
    for {
      material  <- mintChain()
      certPem   <- IO.blocking(toPem(material.leafCert))
      encKeyPem <- IO.blocking(toEncryptedPkcs8Pem(material.leafKey.getPrivate, "keypw"))
      result    <- CertCredential.fromPem[IO](
                     certPem.getBytes("UTF-8"), encKeyPem.getBytes("UTF-8"), Some("wrong"), Secret
                   ).attempt
    } yield assert(result.isLeft, result)
  }

  test("fromPem rejects an encrypted key without a keyPassword") {
    for {
      material  <- mintChain()
      certPem   <- IO.blocking(toPem(material.leafCert))
      encKeyPem <- IO.blocking(toEncryptedPkcs8Pem(material.leafKey.getPrivate, "keypw"))
      e         <- interceptIO[IllegalArgumentException](
                     CertCredential.fromPem[IO](certPem.getBytes("UTF-8"), encKeyPem.getBytes("UTF-8"), None, Secret)
                   )
    } yield assert(e.getMessage.contains("encrypted; keyPassword required"), e.getMessage)
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

  private def toEncryptedPkcs8Pem(key: PrivateKey, password: String): String = {
    val encryptor = new org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder(
      org.bouncycastle.openssl.PKCS8Generator.AES_256_CBC
    )
      .setProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
      .setPassword(password.toCharArray)
      .build()
    val sw     = new StringWriter()
    val writer = new org.bouncycastle.util.io.pem.PemWriter(sw)
    try writer.writeObject(new org.bouncycastle.openssl.jcajce.JcaPKCS8Generator(key, encryptor))
    finally writer.close()
    sw.toString
  }
}
