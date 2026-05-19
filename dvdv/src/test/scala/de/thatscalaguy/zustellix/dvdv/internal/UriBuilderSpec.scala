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
