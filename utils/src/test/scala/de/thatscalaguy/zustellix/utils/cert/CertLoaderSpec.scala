package de.thatscalaguy.zustellix.utils.cert

import cats.effect.IO
import munit.CatsEffectSuite

import java.io.{ByteArrayOutputStream, StringWriter}
import java.math.BigInteger
import java.nio.file.{Files, Paths}
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.{KeyPair, KeyPairGenerator, KeyStore, PrivateKey}
import java.util.Date
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

class CertLoaderSpec extends CatsEffectSuite {

  java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())

  // Pinned SHA-1 fingerprint of the committed test-cert.pem / test-cert.p12
  // fixture. If the fixture is ever regenerated, recompute with:
  //   openssl x509 -fingerprint -sha1 -noout -in test-cert.pem
  // then lowercase and strip the colons (CertLoader.fingerprintHex's format).
  private val FixtureCertSha1 = "6334903b017a707ca88e5f1044d371138926ec2d"

  private def resourcePath(name: String) =
    Paths.get(getClass.getClassLoader.getResource(name).toURI)

  test("PKCS12 loads with the configured password and yields a non-empty fingerprint") {
    CertLoader.load[IO](CertSource.Pkcs12(resourcePath("test-cert.p12"), "test")).map { loaded =>
      assert(loaded.fingerprintSha1Hex.length == 40)
      assert(loaded.fingerprintSha1Hex.matches("^[0-9a-f]+$"))
      assertEquals(loaded.fingerprintSha1Hex, FixtureCertSha1)
      assertEquals(loaded.privateKey.getAlgorithm, "RSA")
    }
  }

  test("PEM yields the same fingerprint as PKCS12 for the same cert") {
    for {
      p12 <- CertLoader.load[IO](CertSource.Pkcs12(resourcePath("test-cert.p12"), "test"))
      pem <- CertLoader.load[IO](CertSource.Pem(resourcePath("test-cert.pem"), resourcePath("test-key.pem"), None))
    } yield {
      assertEquals(pem.fingerprintSha1Hex, p12.fingerprintSha1Hex)
      assertEquals(pem.fingerprintSha256Hex, p12.fingerprintSha256Hex)
    }
  }

  test("SHA-256 fingerprint is 64 lowercase hex chars matching an independently computed digest") {
    CertLoader.load[IO](CertSource.Pkcs12(resourcePath("test-cert.p12"), "test")).map { loaded =>
      val expected = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(loaded.certificate.getEncoded)
        .map(b => f"$b%02x")
        .mkString
      assertEquals(loaded.fingerprintSha256Hex, expected)
      assert(loaded.fingerprintSha256Hex.length == 64)
      assert(loaded.fingerprintSha256Hex.matches("^[0-9a-f]+$"))
      assert(loaded.fingerprintSha256Hex != loaded.fingerprintSha1Hex)
    }
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

  private def ecKeyPair(): KeyPair = {
    val kpg = KeyPairGenerator.getInstance("EC", "BC")
    kpg.initialize(new ECGenParameterSpec("secp256r1"))
    kpg.generateKeyPair()
  }

  private def mintCert(
      subject: String,
      subjectKey: KeyPair,
      issuer: String,
      issuerKey: KeyPair,
      sigAlg: String = "SHA256withRSA"
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
      new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder(sigAlg).build(issuerKey.getPrivate)
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

  private def pemBlock(headerType: String, der: Array[Byte]): String = {
    val sw     = new StringWriter()
    val writer = new org.bouncycastle.util.io.pem.PemWriter(sw)
    try writer.writeObject(new org.bouncycastle.util.io.pem.PemObject(headerType, der))
    finally writer.close()
    sw.toString
  }

  private def pkcs1Pem(key: PrivateKey): String =
    pemBlock(
      "RSA PRIVATE KEY",
      org.bouncycastle.asn1.pkcs.PrivateKeyInfo
        .getInstance(key.getEncoded)
        .parsePrivateKey()
        .toASN1Primitive
        .getEncoded
    )

  private def toDekInfoEncryptedPem(key: PrivateKey, password: String): String = {
    val encryptor = new org.bouncycastle.openssl.jcajce.JcePEMEncryptorBuilder("AES-256-CBC")
      .setProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
      .build(password.toCharArray)
    val sw     = new StringWriter()
    val writer = new org.bouncycastle.openssl.jcajce.JcaPEMWriter(sw)
    try writer.writeObject(key, encryptor)
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

  // --- PEM key inputs whose first block is not the key ---

  test("PEM key preceded by an EC PARAMETERS block loads (openssl ecparam -genkey layout)") {
    for {
      key     <- IO.blocking(ecKeyPair())
      certPem <- IO.blocking(toPem(mintCert("ec-named", key, "ec-named", key, sigAlg = "SHA256withECDSA")))
      keyPem  <- IO.blocking {
                   val params = pemBlock(
                     "EC PARAMETERS",
                     new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.10045.3.1.7").getEncoded
                   )
                   params + toPem(key.getPrivate)
                 }
      loaded  <- CertLoader.load[IO](CertSource.PemBytes(certPem.getBytes("UTF-8"), keyPem.getBytes("UTF-8"), None))
    } yield assert(Set("EC", "ECDSA").contains(loaded.privateKey.getAlgorithm), clues(loaded.privateKey.getAlgorithm))
  }

  test("PEM key preceded by explicit EC PARAMETERS loads") {
    for {
      key     <- IO.blocking(ecKeyPair())
      certPem <- IO.blocking(toPem(mintCert("ec-explicit", key, "ec-explicit", key, sigAlg = "SHA256withECDSA")))
      keyPem  <- IO.blocking {
                   val params = pemBlock(
                     "EC PARAMETERS",
                     org.bouncycastle.asn1.x9.ECNamedCurveTable.getByName("prime256v1").getEncoded
                   )
                   params + toPem(key.getPrivate)
                 }
      loaded  <- CertLoader.load[IO](CertSource.PemBytes(certPem.getBytes("UTF-8"), keyPem.getBytes("UTF-8"), None))
    } yield assert(Set("EC", "ECDSA").contains(loaded.privateKey.getAlgorithm), clues(loaded.privateKey.getAlgorithm))
  }

  test("PEM bundle with the certificate before the key loads the key") {
    for {
      material <- mintChain()
      certPem  <- IO.blocking(toPem(material.leafCert))
      keyPem   <- IO.blocking(toPem(material.leafCert, material.leafKey.getPrivate))
      loaded   <- CertLoader.load[IO](CertSource.PemBytes(certPem.getBytes("UTF-8"), keyPem.getBytes("UTF-8"), None))
    } yield assertEquals(
      loaded.privateKey.asInstanceOf[RSAPrivateKey].getModulus,
      material.leafKey.getPrivate.asInstanceOf[RSAPrivateKey].getModulus
    )
  }

  test("PEM key input containing no key fails with a clear message") {
    for {
      material <- mintChain()
      certPem  <- IO.blocking(toPem(material.leafCert))
      err <- interceptIO[IllegalArgumentException](
               CertLoader.load[IO](CertSource.PemBytes(certPem.getBytes("UTF-8"), certPem.getBytes("UTF-8"), None))
             )
    } yield assert(err.getMessage.contains("No private key found"), clues(err.getMessage))
  }

  // --- PEM private-key encodings: PKCS#1, DEK-Info encrypted, PKCS#8 ---

  test("PKCS#1 'RSA PRIVATE KEY' PEM loads via the PEMKeyPair branch") {
    for {
      material <- mintChain()
      certPem  <- IO.blocking(toPem(material.leafCert))
      keyPem   <- IO.blocking(pkcs1Pem(material.leafKey.getPrivate))
      loaded   <- CertLoader.load[IO](CertSource.PemBytes(certPem.getBytes("UTF-8"), keyPem.getBytes("UTF-8"), None))
    } yield {
      assert(keyPem.startsWith("-----BEGIN RSA PRIVATE KEY-----"), keyPem)
      assertEquals(
        loaded.privateKey.asInstanceOf[RSAPrivateKey].getModulus,
        material.leafKey.getPrivate.asInstanceOf[RSAPrivateKey].getModulus
      )
    }
  }

  test("DEK-Info encrypted PKCS#1 PEM decrypts with the key password") {
    for {
      material <- mintChain()
      certPem  <- IO.blocking(toPem(material.leafCert))
      keyPem   <- IO.blocking(toDekInfoEncryptedPem(material.leafKey.getPrivate, "keypw"))
      loaded   <- CertLoader.load[IO](
                    CertSource.PemBytes(certPem.getBytes("UTF-8"), keyPem.getBytes("UTF-8"), Some("keypw"))
                  )
    } yield {
      assert(keyPem.contains("DEK-Info"), keyPem)
      assertEquals(
        loaded.privateKey.asInstanceOf[RSAPrivateKey].getModulus,
        material.leafKey.getPrivate.asInstanceOf[RSAPrivateKey].getModulus
      )
    }
  }

  test("DEK-Info encrypted PEM without a keyPassword fails with the non-PKCS#8 encrypted-key message") {
    for {
      material <- mintChain()
      certPem  <- IO.blocking(toPem(material.leafCert))
      keyPem   <- IO.blocking(toDekInfoEncryptedPem(material.leafKey.getPrivate, "keypw"))
      err <- interceptIO[IllegalArgumentException](
               CertLoader.load[IO](CertSource.PemBytes(certPem.getBytes("UTF-8"), keyPem.getBytes("UTF-8"), None))
             )
    } yield {
      assert(err.getMessage.contains("is encrypted; keyPassword required"), clues(err.getMessage))
      assert(!err.getMessage.contains("PKCS#8"), clues(err.getMessage))
    }
  }

  test("EC plain PKCS#8 'PRIVATE KEY' PEM loads a non-RSA key") {
    for {
      key     <- IO.blocking(ecKeyPair())
      certPem <- IO.blocking(toPem(mintCert("ec-pkcs8", key, "ec-pkcs8", key, sigAlg = "SHA256withECDSA")))
      keyPem  <- IO.blocking(pemBlock("PRIVATE KEY", key.getPrivate.getEncoded))
      loaded  <- CertLoader.load[IO](CertSource.PemBytes(certPem.getBytes("UTF-8"), keyPem.getBytes("UTF-8"), None))
    } yield assert(Set("EC", "ECDSA").contains(loaded.privateKey.getAlgorithm), clues(loaded.privateKey.getAlgorithm))
  }

  // --- Alias selection on multi-entry / non-private-key keystores ---

  private def buildP12(password: String)(populate: KeyStore => Unit): IO[Array[Byte]] = IO.blocking {
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    populate(ks)
    val out = new ByteArrayOutputStream()
    ks.store(out, password.toCharArray)
    out.toByteArray
  }

  private def secretKeyEntry: KeyStore.SecretKeyEntry =
    new KeyStore.SecretKeyEntry(new SecretKeySpec(Array.fill[Byte](16)(1), "AES"))

  test("PKCS12 with multiple private key entries fails with a message naming all aliases") {
    for {
      p12 <- buildP12("pw") { ks =>
               val keyA  = rsaKeyPair()
               val keyB  = rsaKeyPair()
               val certA = mintCert("multi-a", keyA, "multi-a", keyA)
               val certB = mintCert("multi-b", keyB, "multi-b", keyB)
               ks.setKeyEntry("alias-b", keyB.getPrivate, "pw".toCharArray, Array(certB))
               ks.setKeyEntry("alias-a", keyA.getPrivate, "pw".toCharArray, Array(certA))
             }
      err <- interceptIO[IllegalArgumentException](CertLoader.loadPkcs12Bytes[IO](p12, "pw"))
    } yield assert(err.getMessage.contains("alias-a, alias-b"), clues(err.getMessage))
  }

  test("SecretKeyEntry entries are ignored when selecting the key entry") {
    for {
      p12 <- buildP12("pw") { ks =>
               val key  = rsaKeyPair()
               val cert = mintCert("secret-mixed", key, "secret-mixed", key)
               ks.setEntry("aaa-secret", secretKeyEntry, new KeyStore.PasswordProtection("pw".toCharArray))
               ks.setKeyEntry("zzz-key", key.getPrivate, "pw".toCharArray, Array(cert))
             }
      loaded <- CertLoader.loadPkcs12Bytes[IO](p12, "pw")
    } yield {
      assertEquals(loaded.privateKey.getAlgorithm, "RSA")
      assertEquals(loaded.certificate.getSubjectX500Principal, new X500Principal("CN=secret-mixed"))
    }
  }

  test("PKCS12 containing only a secret key entry fails with the clear no-private-key-entry message") {
    for {
      p12 <- buildP12("pw") { ks =>
               ks.setEntry("only-secret", secretKeyEntry, new KeyStore.PasswordProtection("pw".toCharArray))
             }
      err <- interceptIO[IllegalArgumentException](CertLoader.loadPkcs12Bytes[IO](p12, "pw"))
    } yield assert(err.getMessage.contains("No private key entry"), clues(err.getMessage))
  }

  test("PKCS12 containing only trusted certificate entries fails with the same clear message") {
    for {
      p12 <- buildP12("pw") { ks =>
               val key  = rsaKeyPair()
               val cert = mintCert("trusted-only", key, "trusted-only", key)
               ks.setCertificateEntry("trusted-only", cert)
             }
      err <- interceptIO[IllegalArgumentException](CertLoader.loadPkcs12Bytes[IO](p12, "pw"))
    } yield assert(err.getMessage.contains("No private key entry"), clues(err.getMessage))
  }
}
