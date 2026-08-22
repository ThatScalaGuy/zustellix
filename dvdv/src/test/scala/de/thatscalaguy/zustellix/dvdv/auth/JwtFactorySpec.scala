/*
 * Copyright 2026 ThatScalaGuy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.thatscalaguy.zustellix.dvdv.auth

import cats.effect.IO
import cats.effect.testkit.TestControl
import de.thatscalaguy.zustellix.dvdv.DvdvConfig
import de.thatscalaguy.zustellix.utils.cert.{CertLoader, CertSource}
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.implicits.uri
import pdi.jwt.algorithms.JwtAsymmetricAlgorithm
import pdi.jwt.{Jwt, JwtAlgorithm, JwtOptions}

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
    certSource = Some(CertSource.Pkcs12(resourcePath("test-cert.p12"), "test")),
    jwtLifetime = 60.seconds
  )

  private val tokenEp = cfg.tokenUriFor(cfg.baseUri)

  test("produces a JWT verifiable with the cert's public key and has sub=fp:<fingerprint>") {
    for {
      loaded <- CertLoader.load[IO](cfg.certSource.get)
      token  <- JwtFactory.make[IO](cfg, loaded, tokenEp)
      decoded = Jwt.decode(token, loaded.certificate.getPublicKey, Seq(JwtAlgorithm.RS256))
    } yield {
      assert(decoded.isSuccess, s"JWT verification failed: $decoded")
      val claim   = decoded.get
      val payload = parse(claim.content).toOption.flatMap(_.asObject).get
      val expectedSub = s"fp:${loaded.fingerprintSha1Hex}"
      assertEquals(payload("sub").flatMap(_.asString), Some(expectedSub))
      assertEquals(payload("iss").flatMap(_.asString), Some(expectedSub))
      assertEquals(payload("aud").flatMap(_.asString), Some(tokenEp.renderString))
      assert(claim.issuedAt.isDefined)
      assert(claim.notBefore.isDefined)
      assertEquals(claim.notBefore, claim.issuedAt)
      assert(claim.expiration.isDefined)
      assertEquals(claim.expiration.get - claim.issuedAt.get, 60L)
      assert(claim.jwtId.isDefined)
    }
  }

  test("iat/nbf/exp come from the effect clock") {
    for {
      // cert loading stays outside the mocked runtime — it does real I/O
      loaded <- CertLoader.load[IO](cfg.certSource.get)
      token  <- TestControl.executeEmbed(IO.sleep(1234.seconds) *> JwtFactory.make[IO](cfg, loaded, tokenEp))
    } yield {
      // exp/nbf validation must stay off: the pinned claims sit at the epoch,
      // far in the past relative to the real clock
      val claim = Jwt.decode(token, JwtOptions(signature = false, expiration = false, notBefore = false)).get
      assertEquals(claim.issuedAt, Some(1234L))
      assertEquals(claim.notBefore, Some(1234L))
      assertEquals(claim.expiration, Some(1294L))
    }
  }

  private def audOf(token: String): Option[String] =
    parse(Jwt.decode(token, JwtOptions(signature = false)).get.content).toOption
      .flatMap(_.asObject)
      .flatMap(_("aud"))
      .flatMap(_.asString)

  test("aud defaults to the token endpoint actually contacted") {
    val backupEp = uri"https://backup.example/extern/standaloneauth/token"
    for {
      loaded <- CertLoader.load[IO](cfg.certSource.get)
      token  <- JwtFactory.make[IO](cfg, loaded, backupEp)
    } yield assertEquals(audOf(token), Some(backupEp.renderString))
  }

  test("jwtAudience pins aud regardless of the contacted endpoint") {
    val pinned   = cfg.copy(jwtAudience = Some("urn:dvdv:pinned"))
    val backupEp = uri"https://backup.example/extern/standaloneauth/token"
    for {
      loaded <- CertLoader.load[IO](pinned.certSource.get)
      token  <- JwtFactory.make[IO](pinned, loaded, backupEp)
    } yield assertEquals(audOf(token), Some("urn:dvdv:pinned"))
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
      token  <- JwtFactory.make[IO](cfg, loaded, tokenEp)
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
