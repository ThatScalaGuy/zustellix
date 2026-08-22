package de.thatscalaguy.zustellix.osci.internal

import cats.effect.IO
import de.thatscalaguy.zustellix.osci.{ContentSignaturePolicy, OsciError, OsciMailboxConfig}
import munit.CatsEffectSuite

import de.osci.osci12.extinterfaces.TransportI
import de.osci.osci12.roles.Originator
import de.osci.osci12.samples.impl.crypto.{PKCS12Decrypter, PKCS12Signer}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream}
import java.math.BigInteger
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.util.Date
import scala.collection.mutable.ListBuffer

/** Wire-level fault-path coverage of both bridges over a fake `TransportI`:
 *  each operation composes, signs and envelope-encrypts a genuine OSCI
 *  request against the fake, which answers with a canned intermediary SOAP
 *  fault (an Envelope without `xsi:schemaLocation` routes to the library's
 *  SOAPFaultBuilder). The fault surfaces as [[OsciError.OsciResponse]] via
 *  `toOsciError`, and the recorder proves the dialog aborted after exactly
 *  one request — a refused `GetMessageId` / `InitDialog` is followed by
 *  neither the delivery message nor an `ExitDialog`.
 *
 *  Success-path wire sequences (and thereby round-trip counts — 3-trip
 *  mediate, 2-trip implicit store, N+3-trip drain) are deliberately not
 *  faked here: the library's `DialogHandler.checkControlBlock` requires the
 *  response to echo the request's per-message random challenge, and the
 *  request is envelope-encrypted — a fake intermediary would have to decrypt
 *  it. Those paths run against a live test intermediary in the gated
 *  [[OsciBibBridgeIT]]; the feedback-row and drain sequencing is
 *  unit-covered via the thunk seams in [[OsciBibSupportSpec]].
 */
class OsciBibBridgeWireSpec extends CatsEffectSuite {

  java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())

  /** Records every request (target URI, raw bytes) and serves `response` for
   *  each of them. osci-bibliothek clones the transport per request via
   *  `newInstance()`; the clones share the recorder.
   */
  private final class FakeTransport(
      recorded: ListBuffer[(URI, Array[Byte])],
      response: String
  ) extends TransportI {

    private var target:  URI                   = null
    private var request: ByteArrayOutputStream = null

    override def getVendor: String  = "Test"
    override def getVersion: String = "0"

    override def newInstance(): TransportI = new FakeTransport(recorded, response)

    override def isOnline(uri: URI): Boolean = true

    override def getContentLength: Long =
      throw new UnsupportedOperationException("getContentLength is not implemented")

    override def getConnection(uri: URI, contentLength: Long): OutputStream = {
      target  = uri
      request = new ByteArrayOutputStream()
      request
    }

    override def getResponseStream: InputStream = {
      recorded += ((target, request.toByteArray))
      new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8))
    }
  }

  /** An intermediary SOAP fault in the exact MIME framing the library's own
   *  `SOAPFault.writeXML` emits. No `xsi:schemaLocation` on the Envelope, so
   *  the parser routes it to SOAPFaultBuilder; `faultcode` `soap:Server`
   *  raises SoapServerException carrying `code` and the faultstring.
   */
  private def fault(code: String, faultstring: String): String = {
    val envelope =
      "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
        "xmlns:osci=\"http://www.osci.de/2002/04/osci\"><soap:Body><soap:Fault>" +
        s"<faultcode>soap:Server</faultcode><faultstring>$faultstring</faultstring>" +
        s"<detail><osci:Code>$code</osci:Code></detail></soap:Fault></soap:Body></soap:Envelope>"
    "\r\nMIME-Version: 1.0\r\nContent-Type: Multipart/Related; boundary=MIME_boundary; type=text/xml\r\n" +
      "\r\n--MIME_boundary\r\nContent-Type: text/xml; charset=UTF-8\r\n" +
      "Content-Transfer-Encoding: 8bit\r\nContent-ID: <osci@message>\r\n\r\n" +
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n\r\n" + envelope +
      "\r\n--MIME_boundary--\r\n"
  }

  /** Signing + decrypting Originator over the test PKCS12, as in
   *  `OsciBibSupportSpec` — the dialog signs every request with it.
   */
  private def originator: Originator = {
    def p12 = getClass.getClassLoader.getResourceAsStream("test-cert.p12")
    new Originator(new PKCS12Signer(p12, "test"), new PKCS12Decrypter(p12, "test"))
  }

  /** Self-signed in-JVM RSA cert for the intermediary — the request envelope
   *  is encrypted to it before it hits the transport.
   */
  private lazy val intermedCert: X509Certificate = {
    val kpg = java.security.KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val kp    = kpg.generateKeyPair()
    val name  = new javax.security.auth.x500.X500Principal("CN=intermed")
    val now   = new Date()
    val later = new Date(now.getTime + 86400000L)
    val builder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
      name, BigInteger.ONE, now, later, name, kp.getPublic
    )
    val signer = new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate)
    val holder = builder.build(signer)
    new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(holder)
  }

  private def route: OsciRoute =
    OsciRoute(
      addresseeUri    = URI.create("https://addressee.example/osci"),
      addresseeCipher = intermedCert,
      addresseeSig    = None,
      intermedUri     = URI.create("https://intermed.example/osci"),
      intermedCipher  = intermedCert
    )

  private def mailboxConfig: OsciMailboxConfig =
    OsciMailboxConfig(
      intermedUri        = URI.create("https://intermed.example/osci"),
      intermedCipherCert = intermedCert
    )

  private def assertSingleRequest(recorded: ListBuffer[(URI, Array[Byte])], intermedUri: URI): Unit = {
    assertEquals(recorded.size, 1, "expected exactly one request on the wire")
    val (target, bytes) = recorded.head
    assertEquals(target, intermedUri)
    val head = new String(bytes.take(17), StandardCharsets.UTF_8)
    assertEquals(head, "MIME-Version: 1.0", "expected a composed MIME request")
  }

  // 9811 = InternalErrorSupplier, 9802 = NoExplicitDialog — both known to
  // OSCIErrorCodes.fromErrorCode, so the parsed fault keeps its code.

  test("mediate (default): a SOAP fault on InitDialog raises OsciResponse after exactly one request") {
    val recorded  = ListBuffer.empty[(URI, Array[Byte])]
    val transport = new FakeTransport(recorded, fault("9811", "Interner Fehler des Intermediaers"))
    val bridge    = new OsciBibBridgeImpl[IO](IO(originator), transport, ContentSignaturePolicy.Warn)
    interceptIO[OsciError.OsciResponse](bridge.mediate(route, "subject", "<xml/>")).map { e =>
      assertEquals(e.code, "9811")
      assertEquals(e.detail, "Interner Fehler des Intermediaers")
      assertEquals(e.messageId, None)
      // The default profile sends no GetMessageId — the first (and only)
      // request is InitDialog; refused, so neither MediateDelivery nor
      // ExitDialog followed.
      assertSingleRequest(recorded, route.intermedUri)
    }
  }

  test("mediate (explicitDialog): a SOAP fault on GetMessageId raises OsciResponse after exactly one request") {
    val recorded  = ListBuffer.empty[(URI, Array[Byte])]
    val transport = new FakeTransport(recorded, fault("9811", "Interner Fehler des Intermediaers"))
    val bridge    = new OsciBibBridgeImpl[IO](
      IO(originator), transport, ContentSignaturePolicy.Warn, explicitDialog = true
    )
    interceptIO[OsciError.OsciResponse](bridge.mediate(route, "subject", "<xml/>")).map { e =>
      assertEquals(e.code, "9811")
      assertEquals(e.messageId, None)
      // GetMessageId (first again in this mode) was refused, so neither
      // InitDialog, MediateDelivery nor ExitDialog followed.
      assertSingleRequest(recorded, route.intermedUri)
    }
  }

  test("store: a SOAP fault on GetMessageId raises OsciResponse after exactly one request") {
    val recorded  = ListBuffer.empty[(URI, Array[Byte])]
    val transport = new FakeTransport(recorded, fault("9811", "Interner Fehler des Intermediaers"))
    val bridge    = new OsciBibBridgeImpl[IO](IO(originator), transport, ContentSignaturePolicy.Warn)
    interceptIO[OsciError.OsciResponse](bridge.store(route, "subject", "<xml/>")).map { e =>
      assertEquals(e.code, "9811")
      assertEquals(e.messageId, None)
      // Both wire profiles open with GetMessageId; refused, so no
      // StoreDelivery followed — implicit or not — and no ExitDialog either.
      assertSingleRequest(recorded, route.intermedUri)
    }
  }

  test("pending: an InitDialog refusal raises OsciResponse and no ExitDialog is sent") {
    val recorded  = ListBuffer.empty[(URI, Array[Byte])]
    val transport = new FakeTransport(recorded, fault("9802", "Kein expliziter Dialog"))
    val mailbox   = new OsciMailboxBridgeImpl[IO](IO(originator), mailboxConfig, transport)
    interceptIO[OsciError.OsciResponse](mailbox.pending).map { e =>
      assertEquals(e.code, "9802")
      assertEquals(e.detail, "Kein expliziter Dialog")
      // The refusal aborted the explicit dialog before FetchProcessCard, and
      // no dialog was opened — so no ExitDialog either.
      assertSingleRequest(recorded, mailboxConfig.intermedUri)
    }
  }

  test("fetch: an InitDialog refusal raises OsciResponse and no ExitDialog is sent") {
    val recorded  = ListBuffer.empty[(URI, Array[Byte])]
    val transport = new FakeTransport(recorded, fault("9802", "Kein expliziter Dialog"))
    val mailbox   = new OsciMailboxBridgeImpl[IO](IO(originator), mailboxConfig, transport)
    interceptIO[OsciError.OsciResponse](mailbox.fetch("some-id")).map { e =>
      assertEquals(e.code, "9802")
      // FetchDelivery was never sent.
      assertSingleRequest(recorded, mailboxConfig.intermedUri)
    }
  }

  test("drain: an InitDialog refusal raises OsciResponse and no ExitDialog is sent") {
    val recorded  = ListBuffer.empty[(URI, Array[Byte])]
    val transport = new FakeTransport(recorded, fault("9802", "Kein expliziter Dialog"))
    val mailbox   = new OsciMailboxBridgeImpl[IO](IO(originator), mailboxConfig, transport)
    interceptIO[OsciError.OsciResponse](mailbox.drain(10)).map { e =>
      assertEquals(e.code, "9802")
      // The one dialog of the whole batch was refused before
      // FetchProcessCard — nothing else followed, no ExitDialog either.
      assertSingleRequest(recorded, mailboxConfig.intermedUri)
    }
  }
}
