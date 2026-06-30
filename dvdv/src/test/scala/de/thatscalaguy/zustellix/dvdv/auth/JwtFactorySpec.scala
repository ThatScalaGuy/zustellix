package de.thatscalaguy.zustellix.dvdv.auth

import cats.effect.IO
import de.thatscalaguy.zustellix.dvdv.DvdvConfig
import de.thatscalaguy.zustellix.utils.cert.{CertLoader, CertSource}
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.implicits.uri
import pdi.jwt.algorithms.JwtAsymmetricAlgorithm
import pdi.jwt.{Jwt, JwtAlgorithm}

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.file.Paths
import java.security.spec.ECGenParameterSpec
import java.security.{KeyPairGenerator, KeyStore, Security}
import scala.concurrent.duration.*

class JwtFactorySpec extends CatsEffectSuite {

  private def resourcePath(name: String) =
    Paths.get(getClass.getClassLoader.getResource(name).toURI)

  private val cfg = DvdvConfig(
    baseUri    = uri"https://dvdv.example",
    certSource = CertSource.Pkcs12(resourcePath("test-cert.p12"), "test"),
    jwtLifetime = 60.seconds
  )

  test("produces a JWT verifiable with the cert's public key and has sub=fp:<fingerprint>") {
    for {
      loaded <- CertLoader.load[IO](cfg.certSource)
      token  <- JwtFactory.make[IO](cfg, loaded)
      decoded = Jwt.decode(token, loaded.certificate.getPublicKey, Seq(JwtAlgorithm.RS256))
    } yield {
      assert(decoded.isSuccess, s"JWT verification failed: $decoded")
      val claim   = decoded.get
      val payload = parse(claim.content).toOption.flatMap(_.asObject).get
      val expectedSub = s"fp:${loaded.fingerprintSha1Hex}"
      assertEquals(payload("sub").flatMap(_.asString), Some(expectedSub))
      assertEquals(payload("iss").flatMap(_.asString), Some(expectedSub))
      assertEquals(payload("aud").flatMap(_.asString), Some(cfg.tokenUri.renderString))
      assert(claim.issuedAt.isDefined)
      assert(claim.notBefore.isDefined)
      assertEquals(claim.notBefore, claim.issuedAt)
      assert(claim.expiration.isDefined)
      assertEquals(claim.expiration.get - claim.issuedAt.get, 60L)
      assert(claim.jwtId.isDefined)
    }
  }

  // In-JVM EC PKCS12 keystore for the given curve + cert signature algorithm. No binary fixtures shipped.
  private def ecPkcs12(curve: String, sigAlg: String): Array[Byte] = {
    Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
    val kpg = KeyPairGenerator.getInstance("EC")
    kpg.initialize(new ECGenParameterSpec(curve))
    val kp    = kpg.generateKeyPair()
    val name  = new javax.security.auth.x500.X500Principal("CN=Test")
    val now   = new java.util.Date()
    val later = new java.util.Date(now.getTime + 86400000L)
    val builder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
      name, BigInteger.ONE, now, later, name, kp.getPublic
    )
    val signer = new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder(sigAlg).build(kp.getPrivate)
    val holder = builder.build(signer)
    val cert   = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(holder)

    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    ks.setKeyEntry("a", kp.getPrivate, "pw".toCharArray, Array(cert))
    val baos = new ByteArrayOutputStream()
    ks.store(baos, "pw".toCharArray)
    baos.toByteArray
  }

  private def assertEcSignsWith(curve: String, sigAlg: String, expected: JwtAsymmetricAlgorithm): IO[Unit] =
    for {
      bytes  <- IO(ecPkcs12(curve, sigAlg))
      loaded <- CertLoader.loadPkcs12Bytes[IO](bytes, "pw")
      token  <- JwtFactory.make[IO](cfg, loaded)
    } yield {
      val decoded = Jwt.decode(token, loaded.certificate.getPublicKey, Seq(expected))
      assert(decoded.isSuccess, s"$curve JWT verification with $expected failed: $decoded")
    }

  test("secp256r1 EC key signs with ES256") {
    assertEcSignsWith("secp256r1", "SHA256withECDSA", JwtAlgorithm.ES256)
  }

  test("secp384r1 EC key signs with ES384") {
    assertEcSignsWith("secp384r1", "SHA384withECDSA", JwtAlgorithm.ES384)
  }

  test("secp521r1 EC key signs with ES512") {
    assertEcSignsWith("secp521r1", "SHA512withECDSA", JwtAlgorithm.ES512)
  }
}
