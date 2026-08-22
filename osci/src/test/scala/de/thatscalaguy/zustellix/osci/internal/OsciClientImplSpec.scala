package de.thatscalaguy.zustellix.osci.internal

import cats.effect.{IO, Ref}
import de.thatscalaguy.zustellix.osci.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory
import org.typelevel.log4cats.testing.TestingLoggerFactory

import java.net.URI
import java.security.cert.X509Certificate

class OsciClientImplSpec extends CatsEffectSuite {

  private given LoggerFactory[IO] = NoOpFactory[IO]

  private val TestAgs = Ags.unsafe("01001000")
  private val NopeAgs = Ags.unsafe("99999999")

  // The impl never inspects the certs; they're threaded through to the
  // transport. Null references are sufficient for these tests.
  private val Route = OsciRoute(
    addresseeUri    = URI.create("https://example/osci"),
    addresseeCipher = null.asInstanceOf[X509Certificate],
    addresseeSig    = None,
    intermedUri     = URI.create("https://intermed/osci"),
    intermedCipher  = null.asInstanceOf[X509Certificate]
  )

  private def fixedResolver(route: OsciRoute): AgsResolver[IO] = new AgsResolver[IO] {
    def resolve(ags: Ags): IO[OsciRoute] = IO.pure(route)
  }

  private def failingResolver(err: Throwable): AgsResolver[IO] = new AgsResolver[IO] {
    def resolve(ags: Ags): IO[OsciRoute] = IO.raiseError(err)
  }

  private def fixedTransport(out: OsciRawResult): OsciTransport[IO] = new OsciTransport[IO] {
    def mediate(route: OsciRoute, subject: String, xml: String): IO[OsciRawResult] = IO.pure(out)
    def store(route: OsciRoute, subject: String, xml: String): IO[OsciReceipt] =
      IO.raiseError(new AssertionError("store not expected"))
  }

  private def fixedStoreTransport(out: OsciReceipt): OsciTransport[IO] = new OsciTransport[IO] {
    def mediate(route: OsciRoute, subject: String, xml: String): IO[OsciRawResult] =
      IO.raiseError(new AssertionError("mediate not expected"))
    def store(route: OsciRoute, subject: String, xml: String): IO[OsciReceipt] = IO.pure(out)
  }

  private def failingTransport(err: Throwable): OsciTransport[IO] = new OsciTransport[IO] {
    def mediate(route: OsciRoute, subject: String, xml: String): IO[OsciRawResult] = IO.raiseError(err)
    def store(route: OsciRoute, subject: String, xml: String): IO[OsciReceipt] = IO.raiseError(err)
  }

  private def recordingSink(ref: Ref[IO, Vector[Laufzettel]]): LaufzettelSink[IO] =
    new LaufzettelSink[IO] {
      def record(tenant: TenantId, l: Laufzettel): IO[Unit] = ref.update(_ :+ l)
    }

  private val failingSink: LaufzettelSink[IO] = new LaufzettelSink[IO] {
    def record(tenant: TenantId, l: Laufzettel): IO[Unit] =
      IO.raiseError(new RuntimeException("sink down"))
  }

  /** An impl over the given `LoggerFactory` (the method-local given shadows
   *  the class-level `NoOpFactory`), so tests can observe the warn logs.
   */
  private def mkImpl(
      transport: OsciTransport[IO],
      resolver: AgsResolver[IO],
      sink: LaufzettelSink[IO],
      lf: LoggerFactory[IO]
  ): OsciClientImpl[IO] = {
    given LoggerFactory[IO] = lf
    new OsciClientImpl[IO](TenantId("alice"), "XMeld", transport, resolver, sink)
  }

  private def warns(lf: TestingLoggerFactory[IO]): IO[Vector[TestingLoggerFactory.Warn]] =
    lf.logged.map(_.collect { case w: TestingLoggerFactory.Warn => w })

  test("happy path returns the response and records a Laufzettel") {
    val raw = OsciRawResult(Some("<resp/>"), "msg-1", "OK")
    Ref.of[IO, Vector[Laufzettel]](Vector.empty).flatMap { ref =>
      val impl = new OsciClientImpl[IO](
        TenantId("alice"),
        "XMeld",
        fixedTransport(raw),
        fixedResolver(Route),
        recordingSink(ref)
      )
      for {
        out  <- impl.request(TestAgs, "<req/>")
        seen <- ref.get
      }
      yield {
        assertEquals(out, OsciResponse(Some("<resp/>"), "msg-1", "OK"))
        assertEquals(seen.size, 1)
        assertEquals(seen.head.messageId, "msg-1")
        assertEquals(seen.head.recipientAgs, TestAgs)
        assertEquals(seen.head.status, LaufzettelStatus.Feedback("OK"))
        assertEquals(seen.head.rawXml, None)
      }
    }
  }

  test("request: capturePayloads = true records the response payload on the Laufzettel") {
    val raw = OsciRawResult(Some("<resp/>"), "msg-1", "OK")
    Ref.of[IO, Vector[Laufzettel]](Vector.empty).flatMap { ref =>
      val impl = new OsciClientImpl[IO](
        TenantId("alice"),
        "XMeld",
        fixedTransport(raw),
        fixedResolver(Route),
        recordingSink(ref),
        capturePayloads = true
      )
      for {
        _    <- impl.request(TestAgs, "<req/>")
        seen <- ref.get
      }
      yield assertEquals(seen.head.rawXml, Some("<resp/>"))
    }
  }

  test("request: capturePayloads = true with no extractable content records rawXml = None") {
    val raw = OsciRawResult(None, "msg-1", "0800")
    Ref.of[IO, Vector[Laufzettel]](Vector.empty).flatMap { ref =>
      val impl = new OsciClientImpl[IO](
        TenantId("alice"),
        "XMeld",
        fixedTransport(raw),
        fixedResolver(Route),
        recordingSink(ref),
        capturePayloads = true
      )
      for {
        _    <- impl.request(TestAgs, "<req/>")
        seen <- ref.get
      }
      yield assertEquals(seen.head.rawXml, None)
    }
  }

  test("request: a response without extractable content yields xml = None") {
    val raw = OsciRawResult(None, "msg-1", "0800")
    Ref.of[IO, Vector[Laufzettel]](Vector.empty).flatMap { ref =>
      val impl = new OsciClientImpl[IO](
        TenantId("alice"),
        "XMeld",
        fixedTransport(raw),
        fixedResolver(Route),
        recordingSink(ref)
      )
      for {
        out  <- impl.request(TestAgs, "<req/>")
        seen <- ref.get
      }
      yield {
        assertEquals(out, OsciResponse(None, "msg-1", "0800"))
        assertEquals(seen.head.rawXml, None)
      }
    }
  }

  test("feedback warnings from the transport land on the response and the Laufzettel") {
    val warning = OsciFeedback("3802", "Signatur des Empfängers fehlt")
    val raw     = OsciRawResult(Some("<resp/>"), "msg-1", "3802", List(warning))
    Ref.of[IO, Vector[Laufzettel]](Vector.empty).flatMap { ref =>
      val impl = new OsciClientImpl[IO](
        TenantId("alice"),
        "XMeld",
        fixedTransport(raw),
        fixedResolver(Route),
        recordingSink(ref)
      )
      for {
        out  <- impl.request(TestAgs, "<req/>")
        seen <- ref.get
      }
      yield {
        assertEquals(out, OsciResponse(Some("<resp/>"), "msg-1", "3802", List(warning)))
        assertEquals(seen.head.status, LaufzettelStatus.Feedback("3802"))
        assertEquals(seen.head.warnings, List(warning))
      }
    }
  }

  test("the content-signature status from the transport lands on the Laufzettel") {
    val raw = OsciRawResult(
      Some("<resp/>"), "msg-1", "0800", Nil, Some(ContentSignatureStatus.Unsigned)
    )
    Ref.of[IO, Vector[Laufzettel]](Vector.empty).flatMap { ref =>
      val impl = new OsciClientImpl[IO](
        TenantId("alice"),
        "XMeld",
        fixedTransport(raw),
        fixedResolver(Route),
        recordingSink(ref)
      )
      for {
        _    <- impl.request(TestAgs, "<req/>")
        seen <- ref.get
      }
      yield assertEquals(seen.head.contentSignature, Some(ContentSignatureStatus.Unsigned))
    }
  }

  test("request: an UnsignedContent failure records a failure Laufzettel with its messageId") {
    val err = OsciError.UnsignedContent(Some("msg-u"))
    Ref.of[IO, Vector[Laufzettel]](Vector.empty).flatMap { ref =>
      val impl = new OsciClientImpl[IO](
        TenantId("alice"),
        "XMeld",
        failingTransport(err),
        fixedResolver(Route),
        recordingSink(ref)
      )
      for {
        out  <- impl.request(TestAgs, "<req/>").attempt
        seen <- ref.get
      }
      yield {
        assertEquals(out, Left(err))
        assertEquals(seen.head.messageId, "msg-u")
        assertEquals(seen.head.status, LaufzettelStatus.Failed("UnsignedContent"))
        assertEquals(seen.head.contentSignature, None)
      }
    }
  }

  test("request: a 9xxx OsciResponse still raises but records a failure Laufzettel") {
    val err = OsciError.OsciResponse("9000", "boom", Some("msg-9"))
    Ref.of[IO, Vector[Laufzettel]](Vector.empty).flatMap { ref =>
      val impl = new OsciClientImpl[IO](
        TenantId("alice"),
        "XMeld",
        failingTransport(err),
        fixedResolver(Route),
        recordingSink(ref)
      )
      for {
        out  <- impl.request(TestAgs, "<req/>").attempt
        seen <- ref.get
      }
      yield {
        assertEquals(out, Left(err))
        assertEquals(seen.size, 1)
        assertEquals(seen.head.messageId, "msg-9")
        assertEquals(seen.head.recipientAgs, TestAgs)
        assertEquals(seen.head.recipientUri, Route.addresseeUri)
        assertEquals(seen.head.status, LaufzettelStatus.Feedback("9000"))
        assertEquals(seen.head.rawXml, None)
        assertEquals(seen.head.warnings, Nil)
      }
    }
  }

  test("request: a transport failure records a failure Laufzettel with the error kind as status") {
    val err = OsciError.OsciTransport(new java.io.IOException("net"))
    Ref.of[IO, Vector[Laufzettel]](Vector.empty).flatMap { ref =>
      val impl = new OsciClientImpl[IO](
        TenantId("alice"),
        "XMeld",
        failingTransport(err),
        fixedResolver(Route),
        recordingSink(ref)
      )
      for {
        out  <- impl.request(TestAgs, "<req/>").attempt
        seen <- ref.get
      }
      yield {
        assertEquals(out, Left(err))
        assertEquals(seen.size, 1)
        assertEquals(seen.head.messageId, "")
        assertEquals(seen.head.status, LaufzettelStatus.Failed("OsciTransport"))
        assertEquals(seen.head.rawXml, None)
      }
    }
  }

  test("request: a resolver failure records a failure Laufzettel without a recipient URI") {
    val err = OsciError.AgsNotInDvdv(NopeAgs, "u")
    Ref.of[IO, Vector[Laufzettel]](Vector.empty).flatMap { ref =>
      val impl = new OsciClientImpl[IO](
        TenantId("alice"),
        "XMeld",
        fixedTransport(OsciRawResult(Some("<x/>"), "m", "OK")),
        failingResolver(err),
        recordingSink(ref)
      )
      for {
        out  <- impl.request(NopeAgs, "<x/>").attempt
        seen <- ref.get
      }
      yield {
        assertEquals(out, Left(err))
        assertEquals(seen.size, 1)
        assertEquals(seen.head.recipientAgs, NopeAgs)
        assertEquals(seen.head.recipientUri, URI.create(""))
        assertEquals(seen.head.status, LaufzettelStatus.Failed("AgsNotInDvdv"))
        assertEquals(seen.head.messageId, "")
      }
    }
  }

  test("request: a sink failure while recording a failure does not mask the original error") {
    val err  = OsciError.OsciTransport(new java.io.IOException("net"))
    val impl = new OsciClientImpl[IO](
      TenantId("alice"),
      "XMeld",
      failingTransport(err),
      fixedResolver(Route),
      failingSink
    )
    impl.request(TestAgs, "<x/>").attempt.map {
      case Left(e: OsciError.OsciTransport) => assertEquals(e.getCause.getMessage, "net")
      case other                            => fail(s"unexpected: $other")
    }
  }

  test("AgsNotInDvdv from resolver bubbles up") {
    val impl = new OsciClientImpl[IO](
      TenantId("alice"),
      "XMeld",
      fixedTransport(OsciRawResult(Some("<x/>"), "m", "OK")),
      failingResolver(OsciError.AgsNotInDvdv(NopeAgs, "u")),
      LaufzettelSink.noop[IO]
    )
    impl.request(NopeAgs, "<x/>").attempt.map {
      case Left(OsciError.AgsNotInDvdv(NopeAgs, "u")) => ()
      case other                                           => fail(s"unexpected: $other")
    }
  }

  test("OsciTransport from transport bubbles up") {
    val err  = OsciError.OsciTransport(new java.io.IOException("net"))
    val impl = new OsciClientImpl[IO](
      TenantId("alice"),
      "XMeld",
      failingTransport(err),
      fixedResolver(Route),
      LaufzettelSink.noop[IO]
    )
    impl.request(TestAgs, "<x/>").attempt.map {
      case Left(e: OsciError.OsciTransport) => assertEquals(e.getCause.getMessage, "net")
      case other                                 => fail(s"unexpected: $other")
    }
  }

  test("sink failure does not fail request") {
    val raw  = OsciRawResult(Some("<resp/>"), "m", "OK")
    val impl = new OsciClientImpl[IO](
      TenantId("alice"),
      "XMeld",
      fixedTransport(raw),
      fixedResolver(Route),
      failingSink
    )
    impl.request(TestAgs, "<req/>").assertEquals(OsciResponse(Some("<resp/>"), "m", "OK"))
  }

  test("send happy path returns the receipt and records a Laufzettel without payload") {
    val receipt = OsciReceipt("msg-2", "0800", None)
    Ref.of[IO, Vector[Laufzettel]](Vector.empty).flatMap { ref =>
      val impl = new OsciClientImpl[IO](
        TenantId("alice"),
        "XFamilie",
        fixedStoreTransport(receipt),
        fixedResolver(Route),
        recordingSink(ref)
      )
      for {
        out  <- impl.send(TestAgs, "<req/>")
        seen <- ref.get
      }
      yield {
        assertEquals(out, receipt)
        assertEquals(seen.size, 1)
        assertEquals(seen.head.messageId, "msg-2")
        assertEquals(seen.head.recipientAgs, TestAgs)
        assertEquals(seen.head.status, LaufzettelStatus.Feedback("0800"))
        assertEquals(seen.head.rawXml, None)
      }
    }
  }

  test("send: transport failure bubbles up") {
    val err  = OsciError.OsciTransport(new java.io.IOException("net"))
    val impl = new OsciClientImpl[IO](
      TenantId("alice"),
      "XFamilie",
      failingTransport(err),
      fixedResolver(Route),
      LaufzettelSink.noop[IO]
    )
    impl.send(TestAgs, "<x/>").attempt.map {
      case Left(e: OsciError.OsciTransport) => assertEquals(e.getCause.getMessage, "net")
      case other                            => fail(s"unexpected: $other")
    }
  }

  test("send: a failed store records a failure Laufzettel") {
    val err = OsciError.OsciResponse("9802", "signature invalid", Some("msg-3"))
    Ref.of[IO, Vector[Laufzettel]](Vector.empty).flatMap { ref =>
      val impl = new OsciClientImpl[IO](
        TenantId("alice"),
        "XFamilie",
        failingTransport(err),
        fixedResolver(Route),
        recordingSink(ref)
      )
      for {
        out  <- impl.send(TestAgs, "<req/>").attempt
        seen <- ref.get
      }
      yield {
        assertEquals(out, Left(err))
        assertEquals(seen.size, 1)
        assertEquals(seen.head.messageId, "msg-3")
        assertEquals(seen.head.recipientAgs, TestAgs)
        assertEquals(seen.head.recipientUri, Route.addresseeUri)
        assertEquals(seen.head.status, LaufzettelStatus.Feedback("9802"))
        assertEquals(seen.head.rawXml, None)
      }
    }
  }

  test("send: sink failure does not fail send") {
    val receipt = OsciReceipt("m", "0800", None)
    val impl = new OsciClientImpl[IO](
      TenantId("alice"),
      "XFamilie",
      fixedStoreTransport(receipt),
      fixedResolver(Route),
      failingSink
    )
    impl.send(TestAgs, "<req/>").assertEquals(receipt)
  }

  test("request: a sink failure is logged at warn and does not fail the request") {
    val raw = OsciRawResult(Some("<resp/>"), "m", "OK")
    for {
      lf  <- TestingLoggerFactory.ref[IO]()
      impl = mkImpl(fixedTransport(raw), fixedResolver(Route), failingSink, lf)
      out <- impl.request(TestAgs, "<req/>")
      ws  <- warns(lf)
    }
    yield {
      assertEquals(out, OsciResponse(Some("<resp/>"), "m", "OK"))
      assertEquals(ws.size, 1)
      assert(ws.head.message.contains("tenant=alice"), ws.head.message)
      assert(ws.head.message.contains("messageId=m"), ws.head.message)
      assertEquals(ws.head.throwOpt.map(_.getMessage), Some("sink down"))
    }
  }

  test("send: a sink failure is logged at warn and does not fail the send") {
    val receipt = OsciReceipt("m", "0800", None)
    for {
      lf  <- TestingLoggerFactory.ref[IO]()
      impl = mkImpl(fixedStoreTransport(receipt), fixedResolver(Route), failingSink, lf)
      out <- impl.send(TestAgs, "<req/>")
      ws  <- warns(lf)
    }
    yield {
      assertEquals(out, receipt)
      assertEquals(ws.size, 1)
      assertEquals(ws.head.throwOpt.map(_.getMessage), Some("sink down"))
    }
  }

  test("request: a sink failure while recording a failure Laufzettel is logged and does not mask the original error") {
    val err = OsciError.OsciTransport(new java.io.IOException("net"))
    for {
      lf  <- TestingLoggerFactory.ref[IO]()
      impl = mkImpl(failingTransport(err), fixedResolver(Route), failingSink, lf)
      out <- impl.request(TestAgs, "<x/>").attempt
      ws  <- warns(lf)
    }
    yield {
      assertEquals(out, Left(err))
      assertEquals(ws.size, 1)
      assertEquals(ws.head.throwOpt.map(_.getMessage), Some("sink down"))
    }
  }

  test("a successful sink logs no warnings") {
    val raw = OsciRawResult(Some("<resp/>"), "m", "OK")
    for {
      lf  <- TestingLoggerFactory.ref[IO]()
      ref <- Ref.of[IO, Vector[Laufzettel]](Vector.empty)
      impl = mkImpl(fixedTransport(raw), fixedResolver(Route), recordingSink(ref), lf)
      _   <- impl.request(TestAgs, "<req/>")
      ws  <- warns(lf)
    }
    yield assertEquals(ws, Vector.empty)
  }
}
