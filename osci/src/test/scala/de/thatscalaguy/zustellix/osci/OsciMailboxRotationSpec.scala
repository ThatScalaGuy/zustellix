package de.thatscalaguy.zustellix.osci

import cats.effect.IO
import de.thatscalaguy.zustellix.utils.cert.{
  CertAlias,
  CertCredential,
  CertManagerError,
  InMemoryCertManager
}
import de.osci.osci12.roles.Originator
import munit.CatsEffectSuite

import java.math.BigInteger
import java.net.URI
import java.nio.file.{Files, Paths}
import java.security.cert.X509Certificate
import java.util.Date
import scala.concurrent.duration.*

/** Certificate rotation semantics of the CertManager-backed mailbox: the
 *  alias fails fast at acquisition and the Originator is resolved again on
 *  every `pending` / `fetch` / `drain` (one drain = one resolution for the
 *  whole batch).
 */
class OsciMailboxRotationSpec extends CatsEffectSuite {

  private lazy val intermedCert: X509Certificate = {
    java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
    val kpg = java.security.KeyPairGenerator.getInstance("RSA")
    kpg.initialize(1024)
    val kp    = kpg.generateKeyPair()
    val name  = new javax.security.auth.x500.X500Principal("CN=Test")
    val now   = new Date()
    val later = new Date(now.getTime + 86400000L)
    val builder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
      name, BigInteger.ONE, now, later, name, kp.getPublic
    )
    val signer = new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate)
    val holder = builder.build(signer)
    new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(holder)
  }

  private def p12Bytes: Array[Byte] =
    Files.readAllBytes(Paths.get(getClass.getClassLoader.getResource("test-cert.p12").toURI))

  private val alias     = CertAlias("test-alias")
  private val transport = new OsciHttpTransport(1.second, 1.second) // never contacted

  private def config: OsciMailboxConfig =
    OsciMailboxConfig(
      intermedUri        = URI.create("https://intermed.example/osci"),
      intermedCipherCert = intermedCert
    )

  test("resource(config, certs, alias) fails at acquisition for an unknown alias") {
    for {
      certs <- InMemoryCertManager.make[IO](Map.empty[CertAlias, CertCredential])
      e     <- interceptIO[CertManagerError.UnknownCert](
                 OsciMailbox.resource[IO](config, certs, alias, transport).use_
               )
    } yield assertEquals(e.alias, alias)
  }

  test("resource(config, certs, alias) acquires and releases for a known alias") {
    for {
      certs <- InMemoryCertManager.make[IO](Map(alias -> CertCredential(p12Bytes, "test")))
      _     <- OsciMailbox.resource[IO](config, certs, alias, transport)
                 .use(mailbox => IO(assert(mailbox != null)))
    } yield ()
  }

  test("OsciMailboxBridgeImpl evaluates resolveOriginator on every operation") {
    val boom = new RuntimeException("resolve marker")
    for {
      counter <- IO.ref(0)
      bridge   = new internal.OsciMailboxBridgeImpl[IO](
                   counter.update(_ + 1) *> IO.raiseError[Originator](boom),
                   config,
                   transport
                 )
      r1      <- bridge.pending.attempt
      r2      <- bridge.fetch("some-id").attempt
      r3      <- bridge.drain(1).attempt
      n       <- counter.get
    } yield {
      assertEquals(r1, Left(boom))
      assertEquals(r2, Left(boom))
      assertEquals(r3, Left(boom))
      assertEquals(n, 3)
    }
  }

  test("drain rejects a non-positive maxMessages without evaluating the resolver") {
    for {
      counter <- IO.ref(0)
      bridge   = new internal.OsciMailboxBridgeImpl[IO](
                   counter.update(_ + 1) *> IO.raiseError[Originator](new RuntimeException("resolve")),
                   config,
                   transport
                 )
      e       <- interceptIO[OsciError.Config](bridge.drain(0))
      n       <- counter.get
    } yield {
      assert(e.reason.contains("maxMessages"))
      assertEquals(n, 0)
    }
  }
}
