package de.thatscalaguy.zustellix.osci.internal

import cats.effect.{IO, Ref}
import de.thatscalaguy.zustellix.osci.*
import munit.CatsEffectSuite

import java.net.URI
import java.security.cert.X509Certificate

class OsciClientImplSpec extends CatsEffectSuite {

  private val Ags = "01001000"

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
    def resolve(ags: String): IO[OsciRoute] = IO.pure(route)
  }

  private def failingResolver(err: Throwable): AgsResolver[IO] = new AgsResolver[IO] {
    def resolve(ags: String): IO[OsciRoute] = IO.raiseError(err)
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
        out  <- impl.request(Ags, "<req/>")
        seen <- ref.get
      }
      yield {
        assertEquals(out, OsciResponse(Some("<resp/>"), "msg-1", "OK"))
        assertEquals(seen.size, 1)
        assertEquals(seen.head.messageId, "msg-1")
        assertEquals(seen.head.recipientAgs, Ags)
        assertEquals(seen.head.status, "OK")
      }
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
        out  <- impl.request(Ags, "<req/>")
        seen <- ref.get
      }
      yield {
        assertEquals(out, OsciResponse(None, "msg-1", "0800"))
        assertEquals(seen.head.rawXml, "")
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
        out  <- impl.request(Ags, "<req/>")
        seen <- ref.get
      }
      yield {
        assertEquals(out, OsciResponse(Some("<resp/>"), "msg-1", "3802", List(warning)))
        assertEquals(seen.head.status, "3802")
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
        _    <- impl.request(Ags, "<req/>")
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
        out  <- impl.request(Ags, "<req/>").attempt
        seen <- ref.get
      }
      yield {
        assertEquals(out, Left(err))
        assertEquals(seen.head.messageId, "msg-u")
        assertEquals(seen.head.status, "UnsignedContent")
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
        out  <- impl.request(Ags, "<req/>").attempt
        seen <- ref.get
      }
      yield {
        assertEquals(out, Left(err))
        assertEquals(seen.size, 1)
        assertEquals(seen.head.messageId, "msg-9")
        assertEquals(seen.head.recipientAgs, Ags)
        assertEquals(seen.head.recipientUri, Route.addresseeUri)
        assertEquals(seen.head.status, "9000")
        assertEquals(seen.head.rawXml, "")
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
        out  <- impl.request(Ags, "<req/>").attempt
        seen <- ref.get
      }
      yield {
        assertEquals(out, Left(err))
        assertEquals(seen.size, 1)
        assertEquals(seen.head.messageId, "")
        assertEquals(seen.head.status, "OsciTransport")
        assertEquals(seen.head.rawXml, "")
      }
    }
  }

  test("request: a resolver failure records a failure Laufzettel without a recipient URI") {
    val err = OsciError.AgsNotInDvdv("nope", "u")
    Ref.of[IO, Vector[Laufzettel]](Vector.empty).flatMap { ref =>
      val impl = new OsciClientImpl[IO](
        TenantId("alice"),
        "XMeld",
        fixedTransport(OsciRawResult(Some("<x/>"), "m", "OK")),
        failingResolver(err),
        recordingSink(ref)
      )
      for {
        out  <- impl.request("nope", "<x/>").attempt
        seen <- ref.get
      }
      yield {
        assertEquals(out, Left(err))
        assertEquals(seen.size, 1)
        assertEquals(seen.head.recipientAgs, "nope")
        assertEquals(seen.head.recipientUri, URI.create(""))
        assertEquals(seen.head.status, "AgsNotInDvdv")
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
    impl.request(Ags, "<x/>").attempt.map {
      case Left(e: OsciError.OsciTransport) => assertEquals(e.getCause.getMessage, "net")
      case other                            => fail(s"unexpected: $other")
    }
  }

  test("AgsNotInDvdv from resolver bubbles up") {
    val impl = new OsciClientImpl[IO](
      TenantId("alice"),
      "XMeld",
      fixedTransport(OsciRawResult(Some("<x/>"), "m", "OK")),
      failingResolver(OsciError.AgsNotInDvdv("nope", "u")),
      LaufzettelSink.noop[IO]
    )
    impl.request("nope", "<x/>").attempt.map {
      case Left(OsciError.AgsNotInDvdv("nope", "u")) => ()
      case other                                          => fail(s"unexpected: $other")
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
    impl.request(Ags, "<x/>").attempt.map {
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
    impl.request(Ags, "<req/>").assertEquals(OsciResponse(Some("<resp/>"), "m", "OK"))
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
        out  <- impl.send(Ags, "<req/>")
        seen <- ref.get
      }
      yield {
        assertEquals(out, receipt)
        assertEquals(seen.size, 1)
        assertEquals(seen.head.messageId, "msg-2")
        assertEquals(seen.head.recipientAgs, Ags)
        assertEquals(seen.head.status, "0800")
        assertEquals(seen.head.rawXml, "")
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
    impl.send(Ags, "<x/>").attempt.map {
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
        out  <- impl.send(Ags, "<req/>").attempt
        seen <- ref.get
      }
      yield {
        assertEquals(out, Left(err))
        assertEquals(seen.size, 1)
        assertEquals(seen.head.messageId, "msg-3")
        assertEquals(seen.head.recipientAgs, Ags)
        assertEquals(seen.head.recipientUri, Route.addresseeUri)
        assertEquals(seen.head.status, "9802")
        assertEquals(seen.head.rawXml, "")
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
    impl.send(Ags, "<req/>").assertEquals(receipt)
  }
}
