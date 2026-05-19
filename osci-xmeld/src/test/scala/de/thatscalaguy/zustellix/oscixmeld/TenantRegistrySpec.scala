package de.thatscalaguy.zustellix.oscixmeld

import cats.effect.IO
import munit.CatsEffectSuite

class TenantRegistrySpec extends CatsEffectSuite {

  private def fakeClient(tag: String): OSCIXMeld[IO] = new OSCIXMeld[IO] {
    def send(ags: String, xml: String): IO[String] = IO.pure(s"$tag:$ags")
  }

  test("inMemory.lookup returns the registered client") {
    val alice = fakeClient("alice")
    val reg   = TenantRegistry.inMemory[IO](Map(TenantId("alice") -> alice))
    reg.lookup(TenantId("alice")).flatMap(_.send("01", "x")).assertEquals("alice:01")
  }

  test("inMemory.lookup raises UnknownTenant on miss") {
    val reg = TenantRegistry.inMemory[IO](Map.empty)
    reg.lookup(TenantId("missing"))
      .attempt
      .map {
        case Left(OSCIXMeldError.UnknownTenant(id)) => assertEquals(id, TenantId("missing"))
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
