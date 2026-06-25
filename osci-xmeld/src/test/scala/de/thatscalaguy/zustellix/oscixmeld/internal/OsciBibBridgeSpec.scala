package de.thatscalaguy.zustellix.oscixmeld.internal

import cats.effect.IO
import de.thatscalaguy.zustellix.oscixmeld.OSCIXMeldError
import munit.CatsEffectSuite

import de.osci.osci12.messageparts.{Content, ContentContainer}
import de.osci.osci12.roles.Originator

import java.math.BigInteger
import java.security.cert.X509Certificate
import java.util.Date

/** Offline coverage for the pure feedback/extraction helpers of
 *  [[OsciBibBridgeImpl]]. The full GetMessageId -> InitDialog ->
 *  MediateDelivery -> ExitDialog send sequence needs a library-parseable OSCI
 *  response from a gateway and stays in the gated `OsciBibBridgeIT`. Here we
 *  drive the helpers directly (made `private[internal]`).
 *
 *  An `Originator` is built from a self-signed in-JVM cert (the
 *  `(X509Certificate, X509Certificate)` ctor — no Signer/Decrypter needed),
 *  exactly as `AgsResolverSpec` mints certs.
 */
class OsciBibBridgeSpec extends CatsEffectSuite {

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

  private def bridge: OsciBibBridgeImpl[IO] =
    new OsciBibBridgeImpl[IO](new Originator(cert, cert))

  // OSCI feedback rows are [lang, code, text]; code "0..." means success.

  test("checkFeedback: a non-\"0\" code in row 0 raises OsciResponse(code, detail)") {
    val fb = Array(Array("de", "9000", "boom"))
    interceptMessage[OSCIXMeldError.OsciResponse]("OSCI response error [9000]: boom") {
      bridge.checkFeedback(fb)
    }
  }

  test("checkFeedback: a \"0...\" success code in row 0 does not raise") {
    bridge.checkFeedback(Array(Array("de", "0800", "ok"))) // no exception
  }

  test("checkFeedback: null / empty feedback is tolerated") {
    bridge.checkFeedback(null)
    bridge.checkFeedback(Array.empty[Array[String]])
  }

  test("topFeedbackCode reads the code from row 0") {
    assertEquals(bridge.topFeedbackCode(Array(Array("de", "0800", "ok"))), "0800")
    assertEquals(bridge.topFeedbackCode(Array(Array("de", "9000", "boom"))), "9000")
  }

  test("topFeedbackCode: null / empty feedback yields \"\"") {
    assertEquals(bridge.topFeedbackCode(null), "")
    assertEquals(bridge.topFeedbackCode(Array.empty[Array[String]]), "")
  }

  // RESIDUAL GAP (documented, not ideal): checkFeedback and topFeedbackCode
  // only ever inspect row 0. An error code sitting in a LATER feedback row is
  // NOT surfaced today. We assert the CURRENT behaviour so a future fix that
  // scans all rows will flip these expectations on purpose.
  test("checkFeedback ignores an error in a LATER row (row 0 only — current behaviour)") {
    val fb = Array(
      Array("de", "0800", "envelope ok"),
      Array("de", "9000", "inner error in a later row")
    )
    bridge.checkFeedback(fb) // does NOT raise, because only row 0 is read
  }

  test("topFeedbackCode returns row-0 code even when a later row carries an error (current behaviour)") {
    val fb = Array(
      Array("de", "0800", "envelope ok"),
      Array("de", "9000", "inner error in a later row")
    )
    assertEquals(bridge.topFeedbackCode(fb), "0800")
  }

  test("firstContentData returns the first non-empty content payload") {
    val cc = new ContentContainer()
    cc.addContent(new Content("<xml>hello</xml>"))
    assertEquals(bridge.firstContentData(List(cc)), Some("<xml>hello</xml>"))
  }

  test("firstContentData skips containers with no usable content and finds a later one") {
    val empty   = new ContentContainer()
    val payload = new ContentContainer()
    payload.addContent(new Content("<xml>found</xml>"))
    assertEquals(bridge.firstContentData(List(empty, payload)), Some("<xml>found</xml>"))
  }

  test("firstContentData returns None when no container carries content") {
    assertEquals(bridge.firstContentData(Nil), None)
    assertEquals(bridge.firstContentData(List(new ContentContainer())), None)
  }
}
