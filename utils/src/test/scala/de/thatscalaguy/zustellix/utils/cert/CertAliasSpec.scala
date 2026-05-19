package de.thatscalaguy.zustellix.utils.cert

import munit.FunSuite

class CertAliasSpec extends FunSuite {

  test("value round-trips") {
    assertEquals(CertAlias("test-alias").value, "test-alias")
  }
}
