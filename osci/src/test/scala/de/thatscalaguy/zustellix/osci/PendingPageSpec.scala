package de.thatscalaguy.zustellix.osci

import munit.FunSuite

/** The truncation contract of a `pending` listing: only the intermediary's
 *  3800/3801 feedback distinguishes "mailbox fully listed" from "listing cut
 *  off at fetchLimit" — `deliveries.size == fetchLimit` is ambiguous.
 */
class PendingPageSpec extends FunSuite {

  test("truncated is false without warnings") {
    assertEquals(PendingPage(Nil).truncated, false)
  }

  test("truncated is true on a 3800 warning") {
    val page = PendingPage(Nil, List(OsciFeedback("3800", "Auswahl unvollständig")))
    assertEquals(page.truncated, true)
  }

  test("truncated is true on a 3801 warning") {
    val page = PendingPage(Nil, List(OsciFeedback("3801", "Auswahl unvollständig")))
    assertEquals(page.truncated, true)
  }

  test("truncated is false on unrelated warnings (e.g. 3802)") {
    val page = PendingPage(
      Nil,
      List(OsciFeedback("3802", "Signatur des Empfängers fehlt"))
    )
    assertEquals(page.truncated, false)
  }

  test("truncated finds the truncation code among other warnings") {
    val page = PendingPage(
      Nil,
      List(
        OsciFeedback("3802", "Signatur des Empfängers fehlt"),
        OsciFeedback("3800", "Auswahl unvollständig")
      )
    )
    assertEquals(page.truncated, true)
  }

  test("warnings default to Nil") {
    assertEquals(PendingPage(Nil).warnings, Nil)
  }
}
