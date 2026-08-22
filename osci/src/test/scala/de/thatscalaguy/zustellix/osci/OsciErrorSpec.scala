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

import java.io.IOException

class OsciErrorSpec extends FunSuite {

  test("UnknownTenant message includes the tenant id") {
    val e = OsciError.UnknownTenant(TenantId("alice"))
    assert(e.getMessage.contains("alice"))
  }

  test("InvalidAgs message includes the rejected input") {
    val e = OsciError.InvalidAgs("123")
    assert(e.getMessage.contains("123"))
    assert(e.getMessage.contains("8 digits"))
  }

  test("AgsNotInDvdv message includes the AGS and service URI") {
    val e = OsciError.AgsNotInDvdv(Ags.unsafe("01001000"), "http://example/wsdl")
    assert(e.getMessage.contains("01001000"))
    assert(e.getMessage.contains("http://example/wsdl"))
  }

  test("RecipientCertMissing message includes the AGS and the element kind") {
    val e = OsciError.RecipientCertMissing(Ags.unsafe("01001000"), "OSCI_ADDRESSEE")
    assert(e.getMessage.contains("01001000"))
    assert(e.getMessage.contains("OSCI_ADDRESSEE"))
    assertEquals(e.ags, Ags.unsafe("01001000"))
    assertEquals(e.kind, "OSCI_ADDRESSEE")
  }

  test("ServiceElementMissing message includes the AGS and the element kind") {
    val e = OsciError.ServiceElementMissing(Ags.unsafe("01001000"), "OSCI_INTERMEDIARY")
    assert(e.getMessage.contains("01001000"))
    assert(e.getMessage.contains("OSCI_INTERMEDIARY"))
    assertEquals(e.ags, Ags.unsafe("01001000"))
    assertEquals(e.kind, "OSCI_INTERMEDIARY")
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

  test("OsciResponse carries the messageId when one was already issued") {
    assertEquals(OsciError.OsciResponse("9000", "boom").messageId, None)
    assertEquals(
      OsciError.OsciResponse("9000", "boom", Some("msg-1")).messageId,
      Some("msg-1")
    )
  }

  test("NoSuchMessage message includes the message id") {
    val e = OsciError.NoSuchMessage("msg-42")
    assert(e.getMessage.contains("msg-42"))
  }

  test("UnsignedContent carries the messageId when one is known") {
    assertEquals(OsciError.UnsignedContent().messageId, None)
    assertEquals(OsciError.UnsignedContent(Some("msg-1")).messageId, Some("msg-1"))
  }

  test("InvalidContentSignature preserves the cause") {
    val cause = new Exception("digest mismatch")
    val e     = OsciError.InvalidContentSignature(Some("msg-1"), cause)
    assertEquals(e.getCause, cause)
    assertEquals(e.messageId, Some("msg-1"))
  }

  test("All variants are OsciError subtypes") {
    val errs: List[OsciError] = List(
      OsciError.UnknownTenant(TenantId("x")),
      OsciError.InvalidAgs("x"),
      OsciError.AgsNotInDvdv(Ags.unsafe("01001000"), "u"),
      OsciError.RecipientCertMissing(Ags.unsafe("01001000"), "OSCI_ADDRESSEE"),
      OsciError.OsciTransport(new IOException("x")),
      OsciError.OsciResponse("c", "d"),
      OsciError.NoSuchMessage("m"),
      OsciError.UnsignedContent(),
      OsciError.InvalidContentSignature(),
      OsciError.Certificate(new Exception("c")),
      OsciError.Config("r")
    )
    assert(errs.forall(_.isInstanceOf[RuntimeException]))
  }
}
