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

package de.thatscalaguy.zustellix.osci

import cats.effect.IO
import munit.CatsEffectSuite

import java.net.URI
import java.time.Instant

class LaufzettelSinkSpec extends CatsEffectSuite {

  private val sampleLz = Laufzettel(
    messageId    = "msg-1",
    timestamp    = Instant.parse("2026-05-13T12:00:00Z"),
    recipientAgs = Ags.unsafe("01001000"),
    recipientUri = URI.create("https://example/osci"),
    status       = LaufzettelStatus.Feedback("0800"),
    rawXml       = Some("<x/>")
  )

  test("noop sink completes with Unit") {
    LaufzettelSink.noop[IO].record(TenantId("alice"), sampleLz).assertEquals(())
  }

  test("console sink completes with Unit (output not captured)") {
    LaufzettelSink.console[IO].record(TenantId("alice"), sampleLz).assertEquals(())
  }
}
