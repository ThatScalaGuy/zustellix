package de.thatscalaguy.zustellix.oscixmeld

import cats.effect.IO
import munit.CatsEffectSuite

import java.net.URI
import java.time.Instant

class LaufzettelSinkSpec extends CatsEffectSuite {

  private val sampleLz = Laufzettel(
    messageId    = "msg-1",
    timestamp    = Instant.parse("2026-05-13T12:00:00Z"),
    recipientAgs = "01001000",
    recipientUri = URI.create("https://example/osci"),
    status       = "OK",
    rawXml       = "<x/>"
  )

  test("noop sink completes with Unit") {
    LaufzettelSink.noop[IO].record(TenantId("alice"), sampleLz).assertEquals(())
  }

  test("console sink completes with Unit (output not captured)") {
    LaufzettelSink.console[IO].record(TenantId("alice"), sampleLz).assertEquals(())
  }
}
