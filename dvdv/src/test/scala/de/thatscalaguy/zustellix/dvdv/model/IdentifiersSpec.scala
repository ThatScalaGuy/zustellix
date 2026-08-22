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

package de.thatscalaguy.zustellix.dvdv.model

import de.thatscalaguy.zustellix.dvdv.DvdvError
import munit.FunSuite

class IdentifiersSpec extends FunSuite {

  private val PlainFp = "0272c56c9742a62501329a3aa78974f1605c92a2"
  private val ColonFp = "02:72:C5:6C:97:42:A6:25:01:32:9A:3A:A7:89:74:F1:60:5C:92:A2"

  test("Fingerprint.from accepts the plain lowercase 40-hex form") {
    assertEquals(Fingerprint.from(PlainFp).map(_.value), Right(PlainFp))
  }

  test("Fingerprint.from normalizes the colon-separated uppercase form to the plain lowercase form") {
    assertEquals(Fingerprint.from(ColonFp).map(_.value), Right(PlainFp))
    assertEquals(Fingerprint.from(ColonFp), Fingerprint.from(PlainFp))
  }

  test("Fingerprint.from strips whitespace") {
    assertEquals(Fingerprint.from(s"  $PlainFp\n").map(_.value), Right(PlainFp))
    assertEquals(Fingerprint.from(PlainFp.grouped(4).mkString(" ")).map(_.value), Right(PlainFp))
  }

  test("Fingerprint.from rejects the wrong length") {
    assert(Fingerprint.from("").isLeft)
    assert(Fingerprint.from(PlainFp.drop(1)).isLeft)
    assert(Fingerprint.from(PlainFp + "0").isLeft)
    // 32-hex MD5-length colon form is too short even after normalization
    assert(Fingerprint.from("11:51:43:a1:b5:fc:8b:b7:0a:3a:a9:b1:0f:66:73:22").isLeft)
  }

  test("Fingerprint.from rejects non-hex characters") {
    assert(Fingerprint.from(PlainFp.dropRight(1) + "g").isLeft)
    assert(Fingerprint.from(PlainFp.dropRight(1) + "-").isLeft)
  }

  test("Fingerprint.from reports the ORIGINAL input as InvalidFingerprint") {
    Fingerprint.from("nope") match {
      case Left(e: DvdvError.InvalidFingerprint) => assertEquals(e.input, "nope")
      case other                                 => fail(s"unexpected: $other")
    }
  }

  test("Fingerprint.unsafe returns the value for valid input and throws InvalidFingerprint otherwise") {
    assertEquals(Fingerprint.unsafe(ColonFp).value, PlainFp)
    intercept[DvdvError.InvalidFingerprint](Fingerprint.unsafe("deadbeef"))
  }

  test("OrganizationKey.from trims and accepts 6 to 255 characters, preserving case") {
    assertEquals(OrganizationKey.from("ags:01999001").map(_.value), Right("ags:01999001"))
    assertEquals(OrganizationKey.from("  ags:01999001 ").map(_.value), Right("ags:01999001"))
    assertEquals(OrganizationKey.from("AGS:XY").map(_.value), Right("AGS:XY"))
    assertEquals(OrganizationKey.from("a" * 255).map(_.value), Right("a" * 255))
  }

  test("OrganizationKey.from rejects too-short and too-long input, reporting it as InvalidOrganizationKey") {
    assert(OrganizationKey.from("").isLeft)
    assert(OrganizationKey.from("a" * 5).isLeft)
    assert(OrganizationKey.from("a" * 256).isLeft)
    OrganizationKey.from("short") match {
      case Left(e: DvdvError.InvalidOrganizationKey) => assertEquals(e.input, "short")
      case other                                     => fail(s"unexpected: $other")
    }
  }

  test("OrganizationKey.unsafe returns the value for valid input and throws InvalidOrganizationKey otherwise") {
    assertEquals(OrganizationKey.unsafe("ags:01999001").value, "ags:01999001")
    intercept[DvdvError.InvalidOrganizationKey](OrganizationKey.unsafe("nope"))
  }

  test("Category.from trims and accepts 1 to 255 characters, preserving case and umlauts") {
    assertEquals(Category.from("Meldebehörde").map(_.value), Right("Meldebehörde"))
    assertEquals(Category.from("  Meldebehörde ").map(_.value), Right("Meldebehörde"))
    assertEquals(Category.from("x" * 255).map(_.value), Right("x" * 255))
  }

  test("Category.from rejects blank and too-long input, reporting it as InvalidCategory") {
    assert(Category.from("").isLeft)
    assert(Category.from("   ").isLeft)
    assert(Category.from("x" * 256).isLeft)
    Category.from("") match {
      case Left(e: DvdvError.InvalidCategory) => assertEquals(e.input, "")
      case other                              => fail(s"unexpected: $other")
    }
  }

  test("Category.unsafe returns the value for valid input and throws InvalidCategory otherwise") {
    assertEquals(Category.unsafe("Behörde").value, "Behörde")
    intercept[DvdvError.InvalidCategory](Category.unsafe(" "))
  }
}
