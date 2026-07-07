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

  test("happy path returns response xml and records a Laufzettel") {
    val raw = OsciRawResult("<resp/>", "msg-1", "OK")
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
        assertEquals(out, "<resp/>")
        assertEquals(seen.size, 1)
        assertEquals(seen.head.messageId, "msg-1")
        assertEquals(seen.head.recipientAgs, Ags)
        assertEquals(seen.head.status, "OK")
      }
    }
  }

  test("AgsNotInDvdv from resolver bubbles up") {
    val impl = new OsciClientImpl[IO](
      TenantId("alice"),
      "XMeld",
      fixedTransport(OsciRawResult("<x/>", "m", "OK")),
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
    val raw  = OsciRawResult("<resp/>", "m", "OK")
    val impl = new OsciClientImpl[IO](
      TenantId("alice"),
      "XMeld",
      fixedTransport(raw),
      fixedResolver(Route),
      failingSink
    )
    impl.request(Ags, "<req/>").assertEquals("<resp/>")
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
