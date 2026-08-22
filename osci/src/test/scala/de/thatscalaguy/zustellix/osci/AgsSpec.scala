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
