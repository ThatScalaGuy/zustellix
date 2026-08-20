package de.thatscalaguy.zustellix.osci.internal

import de.thatscalaguy.zustellix.osci.{OsciError, OsciFeedback}
import munit.FunSuite

import de.osci.osci12.messageparts.{Content, ContentContainer, EncryptedDataOSCI, Timestamp}
import de.osci.osci12.roles.Originator

import java.math.BigInteger
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date

/** Offline coverage for the pure feedback/extraction helpers shared by the
 *  OSCI bridges. The full wire sequences (MediateDelivery, StoreDelivery,
 *  FetchProcessCard/FetchDelivery) need a library-parseable OSCI response
 *  from a gateway and stay in the gated `OsciBibBridgeIT`.
 *
 *  An `Originator` is built from a self-signed in-JVM cert (the
 *  `(X509Certificate, X509Certificate)` ctor — no Signer/Decrypter needed),
 *  exactly as `AgsResolverSpec` mints certs.
 */
class OsciBibSupportSpec extends FunSuite {

  import OsciBibSupport.*

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

  private lazy val role = new Originator(cert, cert)

  // OSCI feedback rows are [lang, code, text]. OSCI 1.2 code classes:
  // "0xxx" = success, "3xxx" = warning (request executed), "9xxx" = error.

  test("checkFeedback: a \"9...\" error code in row 0 raises OsciResponse(code, detail)") {
    val fb = Array(Array("de", "9000", "boom"))
    interceptMessage[OsciError.OsciResponse]("OSCI response error [9000]: boom") {
      checkFeedback(fb)
    }
  }

  test("checkFeedback attaches the messageId to the raised OsciResponse") {
    val e = intercept[OsciError.OsciResponse] {
      checkFeedback(Array(Array("de", "9000", "boom")), Some("msg-1"))
    }
    assertEquals(e.code, "9000")
    assertEquals(e.messageId, Some("msg-1"))
  }

  test("checkFeedback without a messageId raises OsciResponse with messageId None") {
    val e = intercept[OsciError.OsciResponse] {
      checkFeedback(Array(Array("de", "9000", "boom")))
    }
    assertEquals(e.messageId, None)
  }

  test("checkFeedback: a \"0...\" success code in row 0 does not raise") {
    checkFeedback(Array(Array("de", "0800", "ok"))) // no exception
  }

  test("checkFeedback: a \"3...\" warning code does not raise (request was executed)") {
    // 3802 = recipient signature over acceptance/processing response missing.
    checkFeedback(Array(Array("en", "3802", "Signature of the recipient ... is missing")))
  }

  test("checkFeedback: an unknown code class is treated as an error") {
    interceptMessage[OsciError.OsciResponse]("OSCI response error [X999]: strange") {
      checkFeedback(Array(Array("de", "X999", "strange")))
    }
  }

  test("checkFeedback: null / empty feedback is tolerated") {
    checkFeedback(null)
    checkFeedback(Array.empty[Array[String]])
  }

  test("topFeedbackCode reads the code from row 0") {
    assertEquals(topFeedbackCode(Array(Array("de", "0800", "ok"))), "0800")
    assertEquals(topFeedbackCode(Array(Array("de", "9000", "boom"))), "9000")
  }

  test("topFeedbackCode: null / empty feedback yields \"\"") {
    assertEquals(topFeedbackCode(null), "")
    assertEquals(topFeedbackCode(Array.empty[Array[String]]), "")
  }

  test("checkFeedback scans ALL rows: an error in a LATER row raises") {
    val fb = Array(
      Array("de", "0800", "envelope ok"),
      Array("de", "9000", "inner error in a later row")
    )
    interceptMessage[OsciError.OsciResponse]("OSCI response error [9000]: inner error in a later row") {
      checkFeedback(fb)
    }
  }

  test("checkFeedback: a warning alongside a success code still does not raise") {
    val fb = Array(
      Array("de", "0800", "envelope ok"),
      Array("de", "3802", "Signatur des Empfängers fehlt")
    )
    checkFeedback(fb)
  }

  test("topFeedbackCode returns the row-0 code regardless of later rows") {
    val fb = Array(
      Array("de", "0800", "envelope ok"),
      Array("de", "3802", "warning in a later row")
    )
    assertEquals(topFeedbackCode(fb), "0800")
  }

  test("feedbackWarnings collects 3xxx rows and dedups per-language repeats") {
    val fb = Array(
      Array("de", "3802", "Signatur des Empfängers über die Annahme- bzw. Bearbeitungsantwort fehlt"),
      Array("en", "3802", "Signature of the recipient over the acceptance response or processing response is missing"),
      Array("de", "3500", "Zertifikat zeitlich ungültig")
    )
    assertEquals(
      feedbackWarnings(fb),
      List(
        OsciFeedback("3802", "Signatur des Empfängers über die Annahme- bzw. Bearbeitungsantwort fehlt"),
        OsciFeedback("3500", "Zertifikat zeitlich ungültig")
      )
    )
  }

  test("feedbackWarnings ignores success/error rows and tolerates null / empty feedback") {
    assertEquals(feedbackWarnings(Array(Array("de", "0800", "ok"))), Nil)
    assertEquals(feedbackWarnings(null), Nil)
    assertEquals(feedbackWarnings(Array.empty[Array[String]]), Nil)
  }

  test("firstContentData returns the first non-empty content payload") {
    val cc = new ContentContainer()
    cc.addContent(new Content("<xml>hello</xml>"))
    assertEquals(firstContentData(List(cc)), Some("<xml>hello</xml>"))
  }

  test("firstContentData skips containers with no usable content and finds a later one") {
    val empty   = new ContentContainer()
    val payload = new ContentContainer()
    payload.addContent(new Content("<xml>found</xml>"))
    assertEquals(firstContentData(List(empty, payload)), Some("<xml>found</xml>"))
  }

  test("firstContentData returns None when no container carries content") {
    assertEquals(firstContentData(Nil), None)
    assertEquals(firstContentData(List(new ContentContainer())), None)
  }

  test("extractXml prefers a plaintext container and never touches the encrypted entries") {
    val cc = new ContentContainer()
    cc.addContent(new Content("<xml>plain</xml>"))
    // A decrypt attempt on this bogus entry would throw — proving it is not touched.
    val out = extractXml(Array(cc), Array.empty[EncryptedDataOSCI], role)
    assertEquals(out, Some("<xml>plain</xml>"))
  }

  test("extractXml tolerates null arrays and yields None") {
    assertEquals(extractXml(null, null, role), None)
  }

  test("extractXml yields None when nothing carries content") {
    assertEquals(extractXml(Array(new ContentContainer()), Array.empty[EncryptedDataOSCI], role), None)
  }

  test("parseInstant handles the offset xsd:dateTime form") {
    assertEquals(
      parseInstant("2026-05-13T12:00:00+02:00"),
      Some(Instant.parse("2026-05-13T10:00:00Z"))
    )
  }

  test("parseInstant handles the UTC 'Z' form") {
    assertEquals(parseInstant("2026-05-13T12:00:00Z"), Some(Instant.parse("2026-05-13T12:00:00Z")))
  }

  test("parseInstant yields None on garbage and null") {
    assertEquals(parseInstant("not-a-date"), None)
    assertEquals(parseInstant(null), None)
  }

  test("parseTimestamp reads the xsd:dateTime carried by an OSCI Timestamp") {
    val ts = new Timestamp(Timestamp.PROCESS_CARD_CREATION, null, "2026-05-13T12:00:00+02:00")
    assertEquals(parseTimestamp(ts), Some(Instant.parse("2026-05-13T10:00:00Z")))
    assertEquals(parseTimestamp(null), None)
  }
}
