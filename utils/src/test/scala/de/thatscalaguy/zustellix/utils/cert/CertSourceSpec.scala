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

package de.thatscalaguy.zustellix.utils.cert

import munit.FunSuite

import java.nio.file.Paths

class CertSourceSpec extends FunSuite {

  private val Secret = "sup3rsecret"

  test("Pkcs12 toString keeps the path but redacts the password") {
    val s = CertSource.Pkcs12(Paths.get("/secrets/client.p12"), Secret).toString
    assert(!s.contains(Secret), s)
    assert(s.contains("/secrets/client.p12"), s)
    assert(s.contains("<redacted>"), s)
  }

  test("Pkcs12Bytes toString reports the size and redacts the password") {
    val s = CertSource.Pkcs12Bytes(Array.fill[Byte](7)(1), Secret).toString
    assert(!s.contains(Secret), s)
    assert(s.contains("7 bytes"), s)
  }

  test("Pem toString keeps both paths but redacts the key password") {
    val s = CertSource.Pem(Paths.get("/secrets/c.pem"), Paths.get("/secrets/k.pem"), Some(Secret)).toString
    assert(!s.contains(Secret), s)
    assert(s.contains("/secrets/c.pem") && s.contains("/secrets/k.pem"), s)
  }

  test("PemBytes toString reports both sizes and redacts the key password") {
    val s = CertSource.PemBytes(Array.fill[Byte](3)(1), Array.fill[Byte](5)(1), Some(Secret)).toString
    assert(!s.contains(Secret), s)
    assert(s.contains("3 bytes") && s.contains("5 bytes"), s)
  }
}
