package de.thatscalaguy.zustellix.osci.internal

import cats.effect.IO
import munit.CatsEffectSuite

/** Integration test scaffold for the OSCI bridges. All tests are skipped
 *  unless the respective environment variables are set — they need a real
 *  (test) intermediary.
 *
 *  Outbound (mediate): `OSCI_IT_GATEWAY`.
 *  Mailbox: `OSCI_IT_MAILBOX_URI`, `OSCI_IT_MAILBOX_CERT` (DER/PEM of the
 *  intermediary's cipher cert), `OSCI_IT_P12`, `OSCI_IT_P12_PW`.
 */
class OsciBibBridgeIT extends CatsEffectSuite {

  private def gatewaySet = sys.env.contains("OSCI_IT_GATEWAY")
  private def mailboxSet =
    List("OSCI_IT_MAILBOX_URI", "OSCI_IT_MAILBOX_CERT", "OSCI_IT_P12", "OSCI_IT_P12_PW")
      .forall(sys.env.contains)

  override def munitIgnore: Boolean = !gatewaySet && !mailboxSet

  test("mediate reaches the configured gateway".ignore) {
    // TODO: build an Originator from OSCI_IT_P12, construct an OsciRoute
    // pointing at sys.env("OSCI_IT_GATEWAY"), call transport.mediate(), and
    // assert a non-empty response XML.
    IO.unit
  }

  test("store → pending lists it → fetch returns the payload → pending no longer lists it".ignore) {
    // TODO (the ack property, self-send loopback against the IT mailbox):
    //  1. store() a message addressed to our own mailbox
    //  2. OsciMailbox.pending must list the receipt's messageId
    //  3. fetch(messageId) must return the sent payload (decrypted)
    //  4. pending must no longer list the messageId — the reception entry
    //     recorded at fetch time IS the acknowledgement
    IO.unit
  }

  test("re-fetch by id after reception still returns the delivery".ignore) {
    // TODO: documents the at-least-once recovery contract: whether a
    // delivery can be re-fetched by message id after its reception entry
    // exists is intermediary-policy-dependent; assert it against the IT
    // intermediary so the README claim is backed by evidence.
    IO.unit
  }
}
