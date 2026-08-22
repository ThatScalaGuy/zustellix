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

import cats.effect.IO
import munit.CatsEffectSuite

class TenantRegistrySpec extends CatsEffectSuite {

  private def fakeClient(tag: String): OsciClient[IO] = new OsciClient[IO] {
    def request(ags: Ags, xml: String): IO[OsciResponse] =
      IO.pure(OsciResponse(Some(s"$tag:${ags.value}"), s"$tag-msg", "0800"))
    def send(ags: Ags, xml: String): IO[OsciReceipt] =
      IO.pure(OsciReceipt(s"$tag-msg", "0800", None))
  }

  test("inMemory.lookup returns the registered client") {
    val alice = fakeClient("alice")
    val reg   = TenantRegistry.inMemory[IO](Map(TenantId("alice") -> alice))
    reg.lookup(TenantId("alice"))
      .flatMap(_.request(Ags.unsafe("01001000"), "x"))
      .map(_.xml)
      .assertEquals(Some("alice:01001000"))
  }

  test("inMemory.lookup raises UnknownTenant on miss") {
    val reg = TenantRegistry.inMemory[IO](Map.empty)
    reg.lookup(TenantId("missing"))
      .attempt
      .map {
        case Left(OsciError.UnknownTenant(id)) => assertEquals(id, TenantId("missing"))
        case other                                  => fail(s"unexpected: $other")
      }
  }

  test("inMemory.list returns the configured ids") {
    val reg = TenantRegistry.inMemory[IO](
      Map(TenantId("a") -> fakeClient("a"), TenantId("b") -> fakeClient("b"))
    )
    reg.list.assertEquals(Set(TenantId("a"), TenantId("b")))
  }

  private def fakeMailbox(tag: String): OsciMailbox[IO] = new OsciMailbox[IO] {
    def pending: IO[PendingPage] = IO.pure(PendingPage(Nil))
    def fetch(messageId: String): IO[OsciMessage] =
      IO.pure(OsciMessage(s"$tag-$messageId", None, s"<xml>$tag</xml>", None, None, ContentSignatureStatus.Valid))
    def drain(maxMessages: Int): IO[MailboxDrain] =
      IO.pure(MailboxDrain(PendingPage(Nil), Nil))
  }

  test("inMemory.mailbox returns the registered mailbox") {
    val reg = TenantRegistry.inMemory[IO](
      Map.empty[TenantId, OsciClient[IO]],
      Map(TenantId("a") -> fakeMailbox("a"))
    )
    reg.mailbox(TenantId("a"))
      .flatMap(_.fetch("m1"))
      .map(_.messageId)
      .assertEquals("a-m1")
  }

  test("inMemory.mailbox raises UnknownTenant for a tenant that has a client but no mailbox") {
    val reg = TenantRegistry.inMemory[IO](
      Map(TenantId("a") -> fakeClient("a")),
      Map(TenantId("b") -> fakeMailbox("b"))
    )
    reg.mailbox(TenantId("a"))
      .attempt
      .map {
        case Left(OsciError.UnknownTenant(id)) => assertEquals(id, TenantId("a"))
        case other                                  => fail(s"unexpected: $other")
      }
  }

  test("inMemory.list includes mailbox-only tenants") {
    val reg = TenantRegistry.inMemory[IO](
      Map(TenantId("a") -> fakeClient("a")),
      Map(TenantId("a") -> fakeMailbox("a"), TenantId("b") -> fakeMailbox("b"))
    )
    reg.list.assertEquals(Set(TenantId("a"), TenantId("b")))
  }
}
