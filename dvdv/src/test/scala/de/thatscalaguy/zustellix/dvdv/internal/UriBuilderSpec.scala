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

package de.thatscalaguy.zustellix.dvdv.internal

import munit.FunSuite
import org.http4s.implicits.uri

class UriBuilderSpec extends FunSuite {

  test("withRequestJson URL-encodes the JSON payload") {
    val base = uri"http://x/extern/standaloneauth/directory/v2"
    val u    = UriBuilder.withRequestJson(base, "findauthoritydescription",
      UriBuilder.jsonObject("category" -> "Meldebehörde", "organizationKey" -> "ags:01999001"))
    val r = u.renderString
    assert(r.startsWith("http://x/extern/standaloneauth/directory/v2/findauthoritydescription?request_json="))
    // umlaut must be percent-encoded
    assert(!r.contains("ö"))
  }
}
