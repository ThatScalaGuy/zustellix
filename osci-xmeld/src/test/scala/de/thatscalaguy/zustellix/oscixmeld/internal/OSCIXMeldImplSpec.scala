package de.thatscalaguy.zustellix.oscixmeld.internal

import cats.effect.{IO, Ref}
import de.thatscalaguy.zustellix.oscixmeld.*
import munit.CatsEffectSuite

import java.net.URI
import java.security.cert.X509Certificate

class OSCIXMeldImplSpec extends CatsEffectSuite {

  private val Ags = "01001000"

  // The impl never inspects the certs; they're threaded through to the
  // transport. Null references are sufficient for these tests.
  private val Route = XmeldRoute(
    addresseeUri    = URI.create("https://example/osci"),
    addresseeCipher = null.asInstanceOf[X509Certificate],
    addresseeSig    = None,
    intermedUri     = URI.create("https://intermed/osci"),
    intermedCipher  = null.asInstanceOf[X509Certificate]
  )

  private def fixedResolver(route: XmeldRoute): AgsResolver[IO] = new AgsResolver[IO] {
    def resolve(ags: String): IO[XmeldRoute] = IO.pure(route)
  }

  private def failingResolver(err: Throwable): AgsResolver[IO] = new AgsResolver[IO] {
    def resolve(ags: String): IO[XmeldRoute] = IO.raiseError(err)
  }

  private def fixedTransport(out: OsciRawResult): OsciTransport[IO] = new OsciTransport[IO] {
    def transmit(route: XmeldRoute, xml: String): IO[OsciRawResult] = IO.pure(out)
  }

  private def failingTransport(err: Throwable): OsciTransport[IO] = new OsciTransport[IO] {
    def transmit(route: XmeldRoute, xml: String): IO[OsciRawResult] = IO.raiseError(err)
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
    val raw = OsciRawResult("<resp/>", "msg-1", "OK", Array.emptyByteArray)
    Ref.of[IO, Vector[Laufzettel]](Vector.empty).flatMap { ref =>
      val impl = new OSCIXMeldImpl[IO](
        TenantId("alice"),
        fixedTransport(raw),
        fixedResolver(Route),
        recordingSink(ref)
      )
      for {
        out  <- impl.send(Ags, "<req/>")
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
    val impl = new OSCIXMeldImpl[IO](
      TenantId("alice"),
      fixedTransport(OsciRawResult("<x/>", "m", "OK", Array.emptyByteArray)),
      failingResolver(OSCIXMeldError.AgsNotInDvdv("nope", "u")),
      LaufzettelSink.noop[IO]
    )
    impl.send("nope", "<x/>").attempt.map {
      case Left(OSCIXMeldError.AgsNotInDvdv("nope", "u")) => ()
      case other                                          => fail(s"unexpected: $other")
    }
  }

  test("OsciTransport from transport bubbles up") {
    val err  = OSCIXMeldError.OsciTransport(new java.io.IOException("net"))
    val impl = new OSCIXMeldImpl[IO](
      TenantId("alice"),
      failingTransport(err),
      fixedResolver(Route),
      LaufzettelSink.noop[IO]
    )
    impl.send(Ags, "<x/>").attempt.map {
      case Left(e: OSCIXMeldError.OsciTransport) => assertEquals(e.getCause.getMessage, "net")
      case other                                 => fail(s"unexpected: $other")
    }
  }

  test("sink failure does not fail send") {
    val raw  = OsciRawResult("<resp/>", "m", "OK", Array.emptyByteArray)
    val impl = new OSCIXMeldImpl[IO](
      TenantId("alice"),
      fixedTransport(raw),
      fixedResolver(Route),
      failingSink
    )
    impl.send(Ags, "<req/>").assertEquals("<resp/>")
  }
}
