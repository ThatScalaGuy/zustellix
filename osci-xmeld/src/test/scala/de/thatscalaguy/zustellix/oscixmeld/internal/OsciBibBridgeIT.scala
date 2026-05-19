package de.thatscalaguy.zustellix.oscixmeld.internal

import cats.effect.IO
import munit.CatsEffectSuite

/** Integration test scaffold for OsciBibBridge. Skipped unless
 *  `OSCI_IT_GATEWAY` is set in the environment AND the bridge has been
 *  wired to the osci-bibliothek library. Until then, this test is a
 *  placeholder so the gate is wired up.
 */
class OsciBibBridgeIT extends CatsEffectSuite {

  override def munitIgnore: Boolean = sys.env.get("OSCI_IT_GATEWAY").isEmpty

  test("transmit reaches the configured gateway".ignore) {
    // TODO: once OsciBibBridge is implemented, build a LoadedCert from a
    // test p12 (placed at src/test/resources/test-osci.p12), construct an
    // OSCIXMeldConfig, build a fake RecipientRoute pointing at
    // sys.env("OSCI_IT_GATEWAY"), call transport.transmit(), and assert a
    // non-empty response XML.
    IO.unit
  }
}
