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
