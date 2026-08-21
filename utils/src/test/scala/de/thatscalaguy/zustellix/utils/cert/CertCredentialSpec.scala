package de.thatscalaguy.zustellix.utils.cert

import munit.FunSuite

class CertCredentialSpec extends FunSuite {

  private val Secret = "sup3rsecret"

  test("toString reports the size and redacts the password") {
    val s = CertCredential(Array.fill[Byte](7)(1), Secret).toString
    assert(!s.contains(Secret), s)
    assert(s.contains("7 bytes"), s)
    assert(s.contains("<redacted>"), s)
  }
}
