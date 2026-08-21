package de.thatscalaguy.zustellix.osci.internal

import cats.effect.IO
import de.thatscalaguy.zustellix.osci.{ContentSignaturePolicy, OsciError, OsciHttpTransport}
import de.thatscalaguy.zustellix.utils.cert.{
  CertAlias,
  CertCredential,
  CertManagerError,
  InMemoryCertManager
}
import de.osci.osci12.roles.Originator
import munit.CatsEffectSuite

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.URI
import java.nio.file.{Files, Paths}
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import scala.concurrent.duration.*

/** Certificate rotation semantics of the alias-keyed bridge path: the alias
 *  is resolved in the CertManager on every operation, the built Originator is
 *  cached on credential identity, and the CertManager `resource` overload
 *  still fails fast at acquisition.
 */
class OsciBibBridgeRotationSpec extends CatsEffectSuite {

  java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())

  private def p12Bytes: Array[Byte] =
    Files.readAllBytes(Paths.get(getClass.getClassLoader.getResource("test-cert.p12").toURI))

  private val alias     = CertAlias("test-alias")
  private val transport = new OsciHttpTransport(1.second, 1.second) // never contacted

  /** A PKCS12 keystore (one self-signed RSA-1024 key entry) serialised to
   *  bytes, plus its certificate — a rotated credential minted in-JVM.
   */
  private def mintP12(cn: String, password: String): (Array[Byte], X509Certificate) = {
    val kpg = java.security.KeyPairGenerator.getInstance("RSA")
    kpg.initialize(1024)
    val kp    = kpg.generateKeyPair()
    val name  = new javax.security.auth.x500.X500Principal(s"CN=$cn")
    val now   = new Date()
    val later = new Date(now.getTime + 86400000L)
    val builder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
      name, BigInteger.valueOf(System.nanoTime()), now, later, name, kp.getPublic
    )
    val signer = new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate)
    val holder = builder.build(signer)
    val cert   = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(holder)

    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    ks.setKeyEntry(cn, kp.getPrivate, password.toCharArray, Array(cert))
    val out = new ByteArrayOutputStream()
    ks.store(out, password.toCharArray)
    (out.toByteArray, cert)
  }

  test("managedOriginator reuses the cached Originator while the credential is unchanged") {
    for {
      certs   <- InMemoryCertManager.make[IO](Map(alias -> CertCredential(p12Bytes, "test")))
      resolve <- OsciBibBridge.managedOriginator[IO](certs, alias)
      o1      <- resolve
      o2      <- resolve
    } yield assert(o1 eq o2, "unchanged credential must keep the same Originator instance")
  }

  test("managedOriginator rebuilds the Originator when the credential rotates") {
    val (rotatedBytes, rotatedCert) = mintP12("rotated", "pw-r")
    for {
      certs   <- InMemoryCertManager.make[IO](Map(alias -> CertCredential(p12Bytes, "test")))
      resolve <- OsciBibBridge.managedOriginator[IO](certs, alias)
      before  <- resolve
      _       <- certs.swap(Map(alias -> CertCredential(rotatedBytes, "pw-r")))
      after   <- resolve
    } yield {
      assert(!(before eq after), "rotated credential must produce a new Originator")
      assertEquals(after.getSignatureCertificate, rotatedCert)
      assertNotEquals(after.getSignatureCertificate, before.getSignatureCertificate)
    }
  }

  test("an alias dropped from the manager raises UnknownCert on the next evaluation") {
    for {
      certs   <- InMemoryCertManager.make[IO](Map(alias -> CertCredential(p12Bytes, "test")))
      resolve <- OsciBibBridge.managedOriginator[IO](certs, alias)
      _       <- resolve
      _       <- certs.swap(Map.empty)
      e       <- interceptIO[CertManagerError.UnknownCert](resolve)
    } yield assertEquals(e.alias, alias)
  }

  test("a rotation to corrupt bytes fails that evaluation and keeps the cached Originator") {
    val good = CertCredential(p12Bytes, "test")
    for {
      certs   <- InMemoryCertManager.make[IO](Map(alias -> good))
      resolve <- OsciBibBridge.managedOriginator[IO](certs, alias)
      before  <- resolve
      _       <- certs.swap(Map(alias -> CertCredential(Array[Byte](1, 2, 3), "pw")))
      _       <- interceptIO[OsciError.Certificate](resolve)
      _       <- certs.swap(Map(alias -> good))
      after   <- resolve
    } yield assert(before eq after, "the cache must survive a failed rebuild")
  }

  test("resource(certs, alias) fails at acquisition for an unknown alias") {
    for {
      certs <- InMemoryCertManager.make[IO](Map.empty[CertAlias, CertCredential])
      e     <- interceptIO[CertManagerError.UnknownCert](
                 OsciBibBridge.resource[IO](certs, alias, transport, ContentSignaturePolicy.Warn).use_
               )
    } yield assertEquals(e.alias, alias)
  }

  test("resource(certs, alias) fails at acquisition for an unopenable keystore") {
    for {
      certs <- InMemoryCertManager.make[IO](Map(alias -> CertCredential(Array[Byte](1, 2, 3), "pw")))
      _     <- interceptIO[OsciError.Certificate](
                 OsciBibBridge.resource[IO](certs, alias, transport, ContentSignaturePolicy.Warn).use_
               )
    } yield ()
  }

  test("OsciBibBridgeImpl evaluates resolveOriginator on every operation") {
    val (_, cert) = mintP12("route", "pw")
    val route = OsciRoute(
      addresseeUri    = URI.create("https://addressee.example/osci"),
      addresseeCipher = cert,
      addresseeSig    = None,
      intermedUri     = URI.create("https://intermed.example/osci"),
      intermedCipher  = cert
    )
    val boom = new RuntimeException("resolve marker")
    for {
      counter <- IO.ref(0)
      bridge   = new OsciBibBridgeImpl[IO](
                   counter.update(_ + 1) *> IO.raiseError[Originator](boom),
                   transport,
                   ContentSignaturePolicy.Warn
                 )
      r1      <- bridge.mediate(route, "subject", "<xml/>").attempt
      r2      <- bridge.store(route, "subject", "<xml/>").attempt
      n       <- counter.get
    } yield {
      assertEquals(r1, Left(boom))
      assertEquals(r2, Left(boom))
      assertEquals(n, 2)
    }
  }
}
