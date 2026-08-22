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

package de.thatscalaguy.zustellix.osci.internal

import de.thatscalaguy.zustellix.osci.{
  ContentSignaturePolicy,
  ContentSignatureStatus,
  DrainFailure,
  OsciError,
  OsciFeedback,
  OsciMessage
}
import munit.FunSuite

import de.osci.osci12.OSCIException
import de.osci.osci12.common.OSCIExceptionCodes.OSCIErrorCodes
import de.osci.osci12.common.{OSCIErrorException, SoapClientException, SoapServerException}
import de.osci.osci12.messageparts.{Content, ContentContainer, EncryptedDataOSCI, Timestamp}
import de.osci.osci12.roles.Originator
import de.osci.osci12.samples.impl.crypto.{PKCS12Decrypter, PKCS12Signer}

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

  /** A signing-capable role over the test PKCS12 (same fixture the bridges
   *  use in production) — needed to actually sign / decrypt containers.
   */
  private lazy val signingRole: Originator = {
    java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
    def p12 = getClass.getClassLoader.getResourceAsStream("test-cert.p12")
    new Originator(new PKCS12Signer(p12, "test"), new PKCS12Decrypter(p12, "test"))
  }


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

  // confirmMessageId guards the mailbox fetch: the FetchDelivery response
  // must answer for the requested message id. The parser leaves the field
  // null when the response carries no MessageId element — only that absence
  // falls back to the requested id.

  test("confirmMessageId falls back to the requested id when the response carries none (null)") {
    assertEquals(confirmMessageId("msg-1", null), "msg-1")
  }

  test("confirmMessageId confirms a matching response id") {
    assertEquals(confirmMessageId("msg-1", "msg-1"), "msg-1")
  }

  test("confirmMessageId: a different response id raises MessageIdMismatch carrying both ids") {
    val e = interceptMessage[OsciError.MessageIdMismatch](
      "FetchDelivery for messageId 'msg-1' returned a delivery with messageId 'msg-2'"
    ) {
      confirmMessageId("msg-1", "msg-2")
    }
    assertEquals(e.requested, "msg-1")
    assertEquals(e.returned, "msg-2")
  }

  test("confirmMessageId treats an empty response id as a mismatch, not an absence") {
    intercept[OsciError.MessageIdMismatch] {
      confirmMessageId("msg-1", "")
    }
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

  test("feedbackWarnings prefers the German row of a code even when it is not first") {
    val fb = Array(
      Array("en", "3802", "Signature of the recipient is missing"),
      Array("de", "3802", "Signatur des Empfängers fehlt"),
      Array("en", "3500", "Certificate not valid in time")
    )
    assertEquals(
      feedbackWarnings(fb),
      List(
        OsciFeedback("3802", "Signatur des Empfängers fehlt"),
        OsciFeedback("3500", "Certificate not valid in time")
      )
    )
  }

  test("feedbackWarnings keeps a code's first row when the preferred language is absent") {
    val fb = Array(
      Array("en", "3802", "Signature of the recipient is missing"),
      Array("fr", "3802", "La signature du destinataire est absente")
    )
    assertEquals(
      feedbackWarnings(fb),
      List(OsciFeedback("3802", "Signature of the recipient is missing"))
    )
  }

  test("feedbackWarnings honours a caller-supplied preferred language") {
    val fb = Array(
      Array("de", "3802", "Signatur des Empfängers fehlt"),
      Array("en", "3802", "Signature of the recipient is missing")
    )
    assertEquals(
      feedbackWarnings(fb, preferredLang = "en"),
      List(OsciFeedback("3802", "Signature of the recipient is missing"))
    )
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

  test("extractVerifiedXml prefers a plaintext container and never touches the encrypted entries") {
    val cc = new ContentContainer()
    cc.addContent(new Content("<xml>plain</xml>"))
    // A decrypt attempt on this bogus entry would throw — proving it is not touched.
    val out = extractVerifiedXml(
      Array(cc), Array[EncryptedDataOSCI](new EncryptedDataOSCI(new ContentContainer())),
      role, ContentSignaturePolicy.Warn, None
    )
    assertEquals(out, Some(("<xml>plain</xml>", ContentSignatureStatus.Unsigned)))
  }

  test("extractVerifiedXml tolerates null arrays and yields None") {
    assertEquals(extractVerifiedXml(null, null, role, ContentSignaturePolicy.Require, None), None)
  }

  test("extractVerifiedXml yields None when nothing carries content") {
    assertEquals(
      extractVerifiedXml(
        Array(new ContentContainer()), Array.empty[EncryptedDataOSCI], role,
        ContentSignaturePolicy.Require, None
      ),
      None
    )
  }

  test("extractVerifiedXml: a signed container verifies as Valid under Require") {
    val cc = new ContentContainer()
    cc.addContent(new Content("<xml>signed</xml>"))
    cc.sign(signingRole)
    val out = extractVerifiedXml(
      Array(cc), Array.empty[EncryptedDataOSCI], signingRole, ContentSignaturePolicy.Require, None
    )
    assertEquals(out, Some(("<xml>signed</xml>", ContentSignatureStatus.Valid)))
  }

  test("extractVerifiedXml: unsigned content under Require raises UnsignedContent with the messageId") {
    val cc = new ContentContainer()
    cc.addContent(new Content("<xml>plain</xml>"))
    val e = intercept[OsciError.UnsignedContent] {
      extractVerifiedXml(
        Array(cc), Array.empty[EncryptedDataOSCI], role,
        ContentSignaturePolicy.Require, Some("msg-7")
      )
    }
    assertEquals(e.messageId, Some("msg-7"))
  }

  test("extractVerifiedXml verifies the signature of a decrypted container") {
    // decrypt is stubbed to return the signed container — what matters here
    // is that the encrypted branch runs the same verification. The real
    // decrypt of a serialized entry is covered by the roundtrip test below.
    val signed = new ContentContainer()
    signed.addContent(new Content("<xml>e2e</xml>"))
    signed.sign(signingRole)
    val enc = new EncryptedDataOSCI(new ContentContainer()) {
      override def decrypt(role: de.osci.osci12.roles.Role): ContentContainer = signed
    }
    val out = extractVerifiedXml(
      null, Array[EncryptedDataOSCI](enc), signingRole, ContentSignaturePolicy.Require, None
    )
    assertEquals(out, Some(("<xml>e2e</xml>", ContentSignatureStatus.Valid)))
  }

  /** Serialize an encrypted entry and SAX-reparse it through the library's
   *  public `EncryptedDataBuilder` — the shape a received message's entries
   *  arrive in (the encrypted keys only land in the `EncryptedData` on
   *  serialization). `writeXML(out, false)` emits the namespace declarations
   *  the SOAP envelope would otherwise supply; the parse-side constructor
   *  resolves the `EncryptedKey`'s RetrievalMethod ref-id against the
   *  carrier message's roles, so `decryptWith` is registered there.
   */
  private def reparse(enc: EncryptedDataOSCI, decryptWith: Originator): EncryptedDataOSCI = {
    val out = new java.io.ByteArrayOutputStream()
    enc.writeXML(out, false)
    val encData =
      de.osci.osci12.encryption.EncryptedDataBuilder.createFromXmlBytes(out.toByteArray)
    val msg = new de.osci.osci12.messagetypes.OSCIMessage() {}
    msg.addRole(decryptWith)
    new EncryptedDataOSCI(encData, msg)
  }

  test("extractVerifiedXml decrypts a real EncryptedDataOSCI encrypted for test-cert.p12") {
    // The full cycle the mailbox performs on a fetched delivery: the sender
    // signs and encrypts, the entry crosses the wire serialized, and the
    // receiver decrypts with its own role and verifies the author's content
    // signature on the decrypted container.
    val container = new ContentContainer()
    container.addContent(new Content("<xml>e2e</xml>"))
    container.sign(signingRole)
    val enc = new EncryptedDataOSCI(container)
    enc.encrypt(signingRole)
    val parsed = reparse(enc, signingRole)
    val out = extractVerifiedXml(
      null, Array[EncryptedDataOSCI](parsed), signingRole, ContentSignaturePolicy.Require, None
    )
    assertEquals(out, Some(("<xml>e2e</xml>", ContentSignatureStatus.Valid)))
  }

  test("extractVerifiedXml skips an undecryptable entry and decrypts a later one") {
    val signed = new ContentContainer()
    signed.addContent(new Content("<xml>later</xml>"))
    signed.sign(signingRole)
    // A locally built entry carries no EncryptedKey for our role, so its
    // decrypt throws — the genuine library failure mode.
    val bad = new EncryptedDataOSCI(new ContentContainer())
    val good = new EncryptedDataOSCI(new ContentContainer()) {
      override def decrypt(role: de.osci.osci12.roles.Role): ContentContainer = signed
    }
    val out = extractVerifiedXml(
      null, Array[EncryptedDataOSCI](bad, good), signingRole, ContentSignaturePolicy.Require, None
    )
    assertEquals(out, Some(("<xml>later</xml>", ContentSignatureStatus.Valid)))
  }

  test("extractVerifiedXml: when every encrypted entry fails to decrypt, the first failure is raised") {
    val bad1 = new EncryptedDataOSCI(new ContentContainer()) {
      override def decrypt(role: de.osci.osci12.roles.Role): ContentContainer =
        throw new IllegalArgumentException("first")
    }
    val bad2 = new EncryptedDataOSCI(new ContentContainer()) {
      override def decrypt(role: de.osci.osci12.roles.Role): ContentContainer =
        throw new IllegalArgumentException("second")
    }
    val e = intercept[IllegalArgumentException] {
      extractVerifiedXml(
        null, Array[EncryptedDataOSCI](bad1, bad2), role, ContentSignaturePolicy.Warn, None
      )
    }
    assertEquals(e.getMessage, "first")
  }

  test("extractVerifiedXml: a failing entry beside a decryptable-but-empty one yields None, not a raise") {
    val bad = new EncryptedDataOSCI(new ContentContainer())
    val empty = new EncryptedDataOSCI(new ContentContainer()) {
      override def decrypt(role: de.osci.osci12.roles.Role): ContentContainer =
        new ContentContainer()
    }
    assertEquals(
      extractVerifiedXml(
        null, Array[EncryptedDataOSCI](bad, empty), role, ContentSignaturePolicy.Warn, None
      ),
      None
    )
  }

  test("extractVerifiedXml: decrypt returning null is tolerated and yields None") {
    val nullEntry = new EncryptedDataOSCI(new ContentContainer()) {
      override def decrypt(role: de.osci.osci12.roles.Role): ContentContainer = null
    }
    assertEquals(
      extractVerifiedXml(
        null, Array[EncryptedDataOSCI](nullEntry), role, ContentSignaturePolicy.Warn, None
      ),
      None
    )
  }

  test("extractVerifiedXml: a corrupted signature raises InvalidContentSignature") {
    val cc = new ContentContainer()
    cc.addContent(new Content("<xml>original</xml>"))
    cc.sign(signingRole)
    val sig = cc.getSignatures()(0)
    sig.signatureValue(0) = (sig.signatureValue(0) ^ 0xff).toByte
    val e = intercept[OsciError.InvalidContentSignature] {
      extractVerifiedXml(
        Array(cc), Array.empty[EncryptedDataOSCI], signingRole,
        ContentSignaturePolicy.Warn, Some("msg-8")
      )
    }
    assertEquals(e.messageId, Some("msg-8"))
  }

  test("verifyContentSignature: unsigned + Warn yields Unsigned, signed yields Valid") {
    val unsigned = new ContentContainer()
    unsigned.addContent(new Content("<a/>"))
    assertEquals(
      verifyContentSignature(unsigned, ContentSignaturePolicy.Warn, None),
      ContentSignatureStatus.Unsigned
    )

    val signed = new ContentContainer()
    signed.addContent(new Content("<a/>"))
    signed.sign(signingRole)
    assertEquals(
      verifyContentSignature(signed, ContentSignaturePolicy.Require, None),
      ContentSignatureStatus.Valid
    )
  }

  // withExplicitDialog is the explicit-dialog lifecycle of the bridges:
  // InitDialog (whose response feedback is checked — a 9xxx refusal aborts
  // before the body), body, best-effort ExitDialog. The thunk overload is
  // the offline seam — a real InitDialog/ExitDialog send needs a parseable
  // intermediary response and stays in the gated `OsciBibBridgeIT`.

  test("withExplicitDialog runs init, body, exit in order and returns the body's result") {
    val calls = scala.collection.mutable.ListBuffer.empty[String]
    val out = withExplicitDialog(
      () => { calls += "init"; Array(Array("de", "0801", "dialog ok")) },
      () => { calls += "exit"; () }
    ) {
      calls += "body"
      42
    }
    assertEquals(out, 42)
    assertEquals(calls.toList, List("init", "body", "exit"))
  }

  test("withExplicitDialog checks the InitDialog response feedback: a 9xxx refusal raises OsciResponse and neither body nor exit runs") {
    // 9802 = no explicit dialog — the intermediary refused to open one.
    val calls = scala.collection.mutable.ListBuffer.empty[String]
    val e = intercept[OsciError.OsciResponse] {
      withExplicitDialog(
        () => Array(Array("de", "9802", "dialog refused")),
        () => { calls += "exit"; () }
      ) {
        calls += "body"
      }
    }
    assertEquals(e.code, "9802")
    assertEquals(e.messageId, None)
    assertEquals(calls.toList, Nil)
  }

  test("withExplicitDialog attaches the caller's messageId to an init refusal") {
    val e = intercept[OsciError.OsciResponse] {
      withExplicitDialog(
        () => Array(Array("de", "9802", "dialog refused")),
        () => (),
        Some("msg-1")
      )(42)
    }
    assertEquals(e.code, "9802")
    assertEquals(e.messageId, Some("msg-1"))
  }

  test("withExplicitDialog: warning-class (3xxx) init feedback does not abort the dialog") {
    val calls = scala.collection.mutable.ListBuffer.empty[String]
    val out = withExplicitDialog(
      () => Array(Array("de", "3802", "Signatur des Empfängers fehlt")),
      () => { calls += "exit"; () }
    )(42)
    assertEquals(out, 42)
    assertEquals(calls.toList, List("exit"))
  }

  test("withExplicitDialog still sends exit when the body raises, and the body's exception propagates") {
    val calls = scala.collection.mutable.ListBuffer.empty[String]
    val e = intercept[OsciError.OsciResponse] {
      withExplicitDialog(() => null, () => { calls += "exit"; () }) {
        checkFeedback(Array(Array("de", "9000", "boom")), Some("msg-1"))
      }
    }
    assertEquals(e.code, "9000")
    assertEquals(e.messageId, Some("msg-1"))
    assertEquals(calls.toList, List("exit"))
  }

  test("withExplicitDialog: a NonFatal exit failure is swallowed (best-effort cleanup)") {
    val out = withExplicitDialog(() => null, () => throw new RuntimeException("exit boom"))(42)
    assertEquals(out, 42)
  }

  test("withExplicitDialog: an exit failure does not mask the body's exception") {
    val e = intercept[OsciError.OsciResponse] {
      withExplicitDialog(() => null, () => throw new RuntimeException("exit boom")) {
        throw OsciError.OsciResponse("9000", "delivery rejected", Some("msg-1"))
      }
    }
    assertEquals(e.code, "9000")
  }

  test("withExplicitDialog: a failing init propagates and exit is not attempted") {
    val calls = scala.collection.mutable.ListBuffer.empty[String]
    intercept[RuntimeException] {
      withExplicitDialog(() => throw new RuntimeException("init boom"), () => { calls += "exit"; () }) {
        calls += "body"
      }
    }
    assertEquals(calls.toList, Nil)
  }

  test("withExplicitDialog: a fatal exit exception is not swallowed") {
    // munit's intercept itself only catches NonFatal, so catch by hand.
    val propagated =
      try {
        withExplicitDialog(() => null, () => throw new InterruptedException())(42)
        false
      }
      catch case _: InterruptedException => true
    assert(propagated, "expected the fatal exit exception to propagate")
  }

  // drainSequence is the fetch loop of OsciMailbox.drain — the offline seam
  // for its sequencing, since a success-path wire fake is impossible (see
  // OsciBibBridgeWireSpec). withExplicitDialog around it mirrors the bridge's
  // one-dialog-per-batch structure.

  private def message(id: String): OsciMessage =
    OsciMessage(id, None, s"<xml>$id</xml>", None, None, ContentSignatureStatus.Unsigned)

  test("drainSequence fetches min(N, max) ids in listing order and keeps the order in the result") {
    val fetched = scala.collection.mutable.ListBuffer.empty[String]
    val (msgs, failure) =
      drainSequence(List("a", "b", "c"), 2, { id => fetched += id; message(id) })
    assertEquals(fetched.toList, List("a", "b"))
    assertEquals(msgs.map(_.messageId), List("a", "b"))
    assertEquals(failure, None)

    val (all, none) = drainSequence(List("a", "b"), 10, message)
    assertEquals(all.map(_.messageId), List("a", "b"))
    assertEquals(none, None)
  }

  test("drainSequence: empty ids yield no messages and no failure") {
    assertEquals(drainSequence(Nil, 10, message), (Nil, None))
  }

  test("drainSequence stops at the first failing fetch, keeps prior messages and never touches later ids") {
    val fetched = scala.collection.mutable.ListBuffer.empty[String]
    val (msgs, failure) = drainSequence(
      List("a", "b", "c"),
      10,
      { id =>
        fetched += id
        if id == "b" then throw OsciError.NoSuchMessage(id) else message(id)
      }
    )
    assertEquals(fetched.toList, List("a", "b"))
    assertEquals(msgs.map(_.messageId), List("a"))
    assertEquals(failure, Some(DrainFailure("b", OsciError.NoSuchMessage("b"))))
  }

  test("drainSequence maps a non-OsciError fetch failure through toOsciError") {
    val io = new java.io.IOException("connection reset")
    val (msgs, failure) =
      drainSequence(List("a"), 10, _ => throw io)
    assertEquals(msgs, Nil)
    failure match {
      case Some(DrainFailure("a", e: OsciError.OsciTransport)) => assert(e.getCause eq io)
      case other => fail(s"expected an OsciTransport DrainFailure, got $other")
    }
  }

  test("drainSequence inside withExplicitDialog: init once, fetches in order, exit once — also after a failed fetch") {
    def run(failOn: Option[String]): List[String] = {
      val calls = scala.collection.mutable.ListBuffer.empty[String]
      val out = withExplicitDialog(
        () => { calls += "init"; Array(Array("de", "0801", "dialog ok")) },
        () => { calls += "exit"; () }
      ) {
        drainSequence(
          List("a", "b", "c"),
          2,
          { id =>
            calls += s"fetch:$id"
            if failOn.contains(id) then throw OsciError.NoSuchMessage(id) else message(id)
          }
        )
      }
      val (msgs, failure) = out
      failOn match {
        case None =>
          assertEquals(msgs.map(_.messageId), List("a", "b"))
          assertEquals(failure, None)
        case Some(id) =>
          assertEquals(failure.map(_.messageId), Some(id))
      }
      calls.toList
    }
    assertEquals(run(None), List("init", "fetch:a", "fetch:b", "exit"))
    // The batch failure is part of the result, not an exception — the dialog
    // still exits normally.
    assertEquals(run(Some("a")), List("init", "fetch:a", "exit"))
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

  // toOsciError maps every exception escaping an osci-bibliothek blocking
  // body. SOAP faults (OSCIErrorException / SoapServerException) carry the
  // same 9xxx codes as feedback rows and must surface them as OsciResponse.

  test("toOsciError: OSCIErrorException surfaces its OSCI error code as OsciResponse") {
    // NoExplicitDialog carries OSCI code 9802.
    toOsciError(new OSCIErrorException(OSCIErrorCodes.NoExplicitDialog)) match {
      case e: OsciError.OsciResponse =>
        assertEquals(e.code, "9802")
        assertEquals(e.messageId, None)
      case other => fail(s"expected OsciResponse, got $other")
    }
  }

  test("toOsciError: SoapServerException carries code and faultstring into OsciResponse") {
    // InternalErrorSupplier carries OSCI code 9811.
    val fault = new SoapServerException(OSCIErrorCodes.InternalErrorSupplier, "Interner Fehler des Intermediaers")
    toOsciError(fault) match {
      case e: OsciError.OsciResponse =>
        assertEquals(e.code, "9811")
        assertEquals(e.detail, "Interner Fehler des Intermediaers")
        assertEquals(e.messageId, None)
      case other => fail(s"expected OsciResponse, got $other")
    }
  }

  test("toOsciError: other OSCIExceptions stay OsciTransport with the cause preserved") {
    // SoapClientException is deliberately NOT an OsciResponse — the library
    // also raises it for locally detected malformed responses.
    val client = new SoapClientException(OSCIErrorCodes.SignatureInvalid, "kaputt")
    toOsciError(client) match {
      case e: OsciError.OsciTransport => assert(e.getCause eq client)
      case other                      => fail(s"expected OsciTransport, got $other")
    }
    val bare = new OSCIException("9601")
    toOsciError(bare) match {
      case e: OsciError.OsciTransport => assert(e.getCause eq bare)
      case other                      => fail(s"expected OsciTransport, got $other")
    }
  }

  test("toOsciError: IllegalArgumentException maps to Config with the message as reason") {
    toOsciError(new IllegalArgumentException("invalid firstargument: 0")) match {
      case e: OsciError.Config => assertEquals(e.reason, "invalid firstargument: 0")
      case other               => fail(s"expected Config, got $other")
    }
  }

  test("toOsciError: IllegalStateException maps to Config") {
    toOsciError(new IllegalStateException("dialog already closed")) match {
      case e: OsciError.Config => assertEquals(e.reason, "dialog already closed")
      case other               => fail(s"expected Config, got $other")
    }
  }

  test("toOsciError: a message-less IllegalArgumentException still yields a non-empty Config reason") {
    toOsciError(new IllegalArgumentException()) match {
      case e: OsciError.Config => assert(e.reason.nonEmpty)
      case other               => fail(s"expected Config, got $other")
    }
  }

  test("toOsciError passes an OsciError through unchanged") {
    val noSuch = OsciError.NoSuchMessage("m")
    assert(toOsciError(noSuch) eq noSuch)
    // In particular an OsciResponse raised by checkFeedback keeps its messageId.
    val rsp = OsciError.OsciResponse("9000", "boom", Some("msg-1"))
    assert(toOsciError(rsp) eq rsp)
    // And a MessageIdMismatch raised by confirmMessageId is not re-wrapped.
    val mm = OsciError.MessageIdMismatch("a", "b")
    assert(toOsciError(mm) eq mm)
  }

  test("toOsciError: IOException stays OsciTransport, GeneralSecurityException maps to Certificate") {
    val io = new java.io.IOException("connection reset")
    toOsciError(io) match {
      case e: OsciError.OsciTransport => assert(e.getCause eq io)
      case other                      => fail(s"expected OsciTransport, got $other")
    }
    val gse = new java.security.GeneralSecurityException("bad key")
    toOsciError(gse) match {
      case e: OsciError.Certificate => assert(e.getCause eq gse)
      case other                    => fail(s"expected Certificate, got $other")
    }
  }
}
