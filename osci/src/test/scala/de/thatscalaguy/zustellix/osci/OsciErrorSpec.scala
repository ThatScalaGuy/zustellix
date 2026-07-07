package de.thatscalaguy.zustellix.osci

import munit.FunSuite

import java.io.IOException

class OsciErrorSpec extends FunSuite {

  test("UnknownTenant message includes the tenant id") {
    val e = OsciError.UnknownTenant(TenantId("alice"))
    assert(e.getMessage.contains("alice"))
  }

  test("AgsNotInDvdv message includes the AGS and service URI") {
    val e = OsciError.AgsNotInDvdv("01001000", "http://example/wsdl")
    assert(e.getMessage.contains("01001000"))
    assert(e.getMessage.contains("http://example/wsdl"))
  }

  test("RecipientCertMissing message includes the AGS") {
    val e = OsciError.RecipientCertMissing("01001000")
    assert(e.getMessage.contains("01001000"))
  }

  test("OsciTransport preserves the cause") {
    val cause = new IOException("boom")
    val e     = OsciError.OsciTransport(cause)
    assertEquals(e.getCause, cause)
  }

  test("OsciResponse message includes code and detail") {
    val e = OsciError.OsciResponse("9999", "nope")
    assert(e.getMessage.contains("9999"))
    assert(e.getMessage.contains("nope"))
  }

  test("NoSuchMessage message includes the message id") {
    val e = OsciError.NoSuchMessage("msg-42")
    assert(e.getMessage.contains("msg-42"))
  }

  test("All variants are OsciError subtypes") {
    val errs: List[OsciError] = List(
      OsciError.UnknownTenant(TenantId("x")),
      OsciError.AgsNotInDvdv("a", "u"),
      OsciError.RecipientCertMissing("a"),
      OsciError.OsciTransport(new IOException("x")),
      OsciError.OsciResponse("c", "d"),
      OsciError.NoSuchMessage("m"),
      OsciError.Certificate(new Exception("c")),
      OsciError.Config("r")
    )
    assert(errs.forall(_.isInstanceOf[RuntimeException]))
  }
}
