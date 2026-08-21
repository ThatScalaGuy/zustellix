package de.thatscalaguy.zustellix.osci

import munit.FunSuite

class LaufzettelStatusSpec extends FunSuite {

  test("0xxx and 3xxx feedback counts as delivered") {
    assert(LaufzettelStatus.Feedback("0800").delivered)
    assert(LaufzettelStatus.Feedback("3802").delivered)
  }

  test("9xxx feedback and Failed records are not delivered") {
    assert(!LaufzettelStatus.Feedback("9000").delivered)
    assert(!LaufzettelStatus.Failed("OsciTransport").delivered)
  }

  test("render yields the plain code or error kind") {
    assertEquals(LaufzettelStatus.Feedback("0800").render, "0800")
    assertEquals(LaufzettelStatus.Failed("AgsNotInDvdv").render, "AgsNotInDvdv")
  }
}
