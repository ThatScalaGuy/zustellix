package de.thatscalaguy.zustellix.osci

import munit.FunSuite

import java.math.BigInteger
import java.net.URI
import java.security.cert.X509Certificate
import java.util.Date

class OsciMailboxConfigSpec extends FunSuite {

  private lazy val cert: X509Certificate = {
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

  private def cfg(limit: Long): OsciMailboxConfig =
    OsciMailboxConfig(
      intermedUri        = URI.create("https://intermed.example/osci"),
      intermedCipherCert = cert,
      fetchLimit         = limit
    )

  test("default fetchLimit constructs and is 100") {
    val c = OsciMailboxConfig(URI.create("https://intermed.example/osci"), cert)
    assertEquals(c.fetchLimit, 100L)
  }

  test("fetchLimit = 0 raises OsciError.Config naming fetchLimit") {
    val e = intercept[OsciError.Config](cfg(0))
    assert(e.getMessage.contains("fetchLimit"), e.getMessage)
    assert(e.getMessage.contains("0"), e.getMessage)
  }

  test("a negative fetchLimit raises OsciError.Config") {
    intercept[OsciError.Config](cfg(-1))
  }

  test("copy re-validates") {
    intercept[OsciError.Config](cfg(1).copy(fetchLimit = 0))
  }
}
