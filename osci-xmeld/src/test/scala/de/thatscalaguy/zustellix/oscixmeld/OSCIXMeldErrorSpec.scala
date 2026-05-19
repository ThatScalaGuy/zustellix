package de.thatscalaguy.zustellix.oscixmeld

import munit.FunSuite

import java.io.IOException

class OSCIXMeldErrorSpec extends FunSuite {

  test("UnknownTenant message includes the tenant id") {
    val e = OSCIXMeldError.UnknownTenant(TenantId("alice"))
    assert(e.getMessage.contains("alice"))
  }

  test("AgsNotInDvdv message includes the AGS and service URI") {
    val e = OSCIXMeldError.AgsNotInDvdv("01001000", "http://example/wsdl")
    assert(e.getMessage.contains("01001000"))
    assert(e.getMessage.contains("http://example/wsdl"))
  }

  test("RecipientCertMissing message includes the AGS") {
    val e = OSCIXMeldError.RecipientCertMissing("01001000")
    assert(e.getMessage.contains("01001000"))
  }

  test("OsciTransport preserves the cause") {
    val cause = new IOException("boom")
    val e     = OSCIXMeldError.OsciTransport(cause)
    assertEquals(e.getCause, cause)
  }

  test("OsciResponse message includes code and detail") {
    val e = OSCIXMeldError.OsciResponse("9999", "nope")
    assert(e.getMessage.contains("9999"))
    assert(e.getMessage.contains("nope"))
  }

  test("All variants are OSCIXMeldError subtypes") {
    val errs: List[OSCIXMeldError] = List(
      OSCIXMeldError.UnknownTenant(TenantId("x")),
      OSCIXMeldError.AgsNotInDvdv("a", "u"),
      OSCIXMeldError.RecipientCertMissing("a"),
      OSCIXMeldError.OsciTransport(new IOException("x")),
      OSCIXMeldError.OsciResponse("c", "d"),
      OSCIXMeldError.Certificate(new Exception("c")),
      OSCIXMeldError.Config("r")
    )
    assert(errs.forall(_.isInstanceOf[RuntimeException]))
  }
}
