package de.thatscalaguy.zustellix.osci

import munit.FunSuite

class AgsSpec extends FunSuite {

  test("from accepts exactly 8 digits, keeping leading zeros") {
    assertEquals(Ags.from("01001000").map(_.value), Right("01001000"))
    assertEquals(Ags.from("99999999").map(_.value), Right("99999999"))
    assertEquals(Ags.from("00000000").map(_.value), Right("00000000"))
  }

  test("from rejects the wrong length") {
    assert(Ags.from("").isLeft)
    assert(Ags.from("0100100").isLeft)
    assert(Ags.from("010010001").isLeft)
  }

  test("from rejects non-digit characters") {
    assert(Ags.from("0100100a").isLeft)
    assert(Ags.from("01 01000").isLeft)
    assert(Ags.from("-1001000").isLeft)
    // Unicode digits are not ASCII digits
    assert(Ags.from("٠١٢٣٤٥٦٧").isLeft)
  }

  test("from reports the rejected input as InvalidAgs") {
    Ags.from("nope") match {
      case Left(e: OsciError.InvalidAgs) => assertEquals(e.input, "nope")
      case other                         => fail(s"unexpected: $other")
    }
  }

  test("unsafe returns the Ags for valid input and throws InvalidAgs otherwise") {
    assertEquals(Ags.unsafe("01001000").value, "01001000")
    intercept[OsciError.InvalidAgs](Ags.unsafe("123"))
  }
}
