package de.thatscalaguy.zustellix.osci

import cats.effect.IO
import munit.CatsEffectSuite

class TenantRegistrySpec extends CatsEffectSuite {

  private def fakeClient(tag: String): OsciClient[IO] = new OsciClient[IO] {
    def request(ags: String, xml: String): IO[OsciResponse] =
      IO.pure(OsciResponse(Some(s"$tag:$ags"), s"$tag-msg", "0800"))
    def send(ags: String, xml: String): IO[OsciReceipt] =
      IO.pure(OsciReceipt(s"$tag-msg", "0800", None))
  }

  test("inMemory.lookup returns the registered client") {
    val alice = fakeClient("alice")
    val reg   = TenantRegistry.inMemory[IO](Map(TenantId("alice") -> alice))
    reg.lookup(TenantId("alice"))
      .flatMap(_.request("01", "x"))
      .map(_.xml)
      .assertEquals(Some("alice:01"))
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
}
