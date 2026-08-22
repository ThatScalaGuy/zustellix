package de.thatscalaguy.zustellix.osci.internal

import cats.effect.IO
import cats.syntax.all.*
import de.thatscalaguy.zustellix.osci.{
  ContentSignaturePolicy,
  ContentSignatureStatus,
  OsciHttpTransport,
  OsciMailbox,
  OsciMailboxConfig
}
import de.thatscalaguy.zustellix.utils.cert.CertSource
import munit.CatsEffectSuite

import java.io.FileInputStream
import java.net.URI
import java.nio.file.Paths
import java.security.cert.{CertificateFactory, X509Certificate}
import java.util.UUID
import scala.concurrent.duration.*

/** Integration tests for the OSCI bridges against a real (test) intermediary.
 *  Each test is gated individually via `assume` on its environment variables
 *  and reports as skipped when they are absent — no variables, no network.
 *
 *  Outbound (mediate): `OSCI_IT_GATEWAY` (OSCI endpoint of the intermediary),
 *  `OSCI_IT_GATEWAY_CERT` (DER/PEM of its cipher cert — the request envelope
 *  is encrypted to it), `OSCI_IT_P12`, `OSCI_IT_P12_PW` (our Originator's
 *  PKCS12). Optionally `OSCI_IT_ADDRESSEE_URI` / `OSCI_IT_ADDRESSEE_CERT`
 *  select a real addressee; without them the message is self-addressed to
 *  our own cipher cert at the gateway (loopback).
 *
 *  Mailbox: `OSCI_IT_MAILBOX_URI`, `OSCI_IT_MAILBOX_CERT` (DER/PEM of the
 *  intermediary's cipher cert), `OSCI_IT_P12`, `OSCI_IT_P12_PW`.
 */
class OsciBibBridgeIT extends CatsEffectSuite {

  override def munitIOTimeout: Duration = 5.minutes

  private val mediateVars =
    List("OSCI_IT_GATEWAY", "OSCI_IT_GATEWAY_CERT", "OSCI_IT_P12", "OSCI_IT_P12_PW")
  private val mailboxVars =
    List("OSCI_IT_MAILBOX_URI", "OSCI_IT_MAILBOX_CERT", "OSCI_IT_P12", "OSCI_IT_P12_PW")

  private def mediateReady = mediateVars.forall(sys.env.contains)
  private def mailboxReady = mailboxVars.forall(sys.env.contains)

  private def env(key: String): String = sys.env(key)

  private def certSource: CertSource =
    CertSource.Pkcs12(Paths.get(env("OSCI_IT_P12")), env("OSCI_IT_P12_PW"))

  private def transport =
    new OsciHttpTransport(
      OsciHttpTransport.DefaultConnectTimeout,
      OsciHttpTransport.DefaultReadTimeout
    )

  /** CertificateFactory reads both DER and PEM, as in `AgsResolver`. */
  private def loadX509(path: String): X509Certificate = {
    val in = new FileInputStream(path)
    try
      CertificateFactory
        .getInstance("X.509")
        .generateCertificate(in)
        .asInstanceOf[X509Certificate]
    finally in.close()
  }

  private def ownCipherCert: IO[X509Certificate] =
    OsciBibBridge.originator[IO](certSource).map(_.getCipherCertificate)

  private def mailboxConfig: OsciMailboxConfig =
    OsciMailboxConfig(
      intermedUri        = URI.create(env("OSCI_IT_MAILBOX_URI")),
      intermedCipherCert = loadX509(env("OSCI_IT_MAILBOX_CERT"))
    )

  /** Self-addressed route through the mailbox intermediary: store() delivers
   *  into our own mailbox, so pending/fetch can pick the message up again.
   */
  private def loopbackRoute(cfg: OsciMailboxConfig, ownCipher: X509Certificate): OsciRoute =
    OsciRoute(
      addresseeUri    = cfg.intermedUri,
      addresseeCipher = ownCipher,
      addresseeSig    = None,
      intermedUri     = cfg.intermedUri,
      intermedCipher  = cfg.intermedCipherCert
    )

  test("mediate reaches the configured gateway") {
    assume(mediateReady, s"set ${mediateVars.mkString(", ")} to run")
    val gatewayUri = URI.create(env("OSCI_IT_GATEWAY"))
    for {
      ownCipher <- ownCipherCert
      route = OsciRoute(
                addresseeUri    = sys.env.get("OSCI_IT_ADDRESSEE_URI")
                                    .map(URI.create).getOrElse(gatewayUri),
                addresseeCipher = sys.env.get("OSCI_IT_ADDRESSEE_CERT")
                                    .map(loadX509).getOrElse(ownCipher),
                addresseeSig    = None,
                intermedUri     = gatewayUri,
                intermedCipher  = loadX509(env("OSCI_IT_GATEWAY_CERT"))
              )
      // A typed OsciError (e.g. an InitDialog refusal surfacing as
      // OsciError.OsciResponse) fails the test with the informative error;
      // the refusal-typing contract itself is unit-covered in
      // OsciBibBridgeWireSpec / OsciBibSupportSpec.
      result <- OsciBibBridge
                  .resource[IO](certSource, transport, ContentSignaturePolicy.Warn)
                  .use(_.mediate(route, "zustellix-it", s"<xml>it-${UUID.randomUUID()}</xml>"))
    } yield {
      assert(result.messageId.nonEmpty, s"expected a messageId, got $result")
      assert(result.status.startsWith("0"), s"expected a success status, got $result")
      assert(result.responseXml.exists(_.nonEmpty), s"expected response content, got $result")
    }
  }

  test("store → pending lists it → fetch returns the payload → pending no longer lists it") {
    assume(mailboxReady, s"set ${mailboxVars.mkString(", ")} to run")
    val payload = s"<xml>it-${UUID.randomUUID()}</xml>"
    val cfg     = mailboxConfig
    (
      OsciBibBridge.resource[IO](certSource, transport, ContentSignaturePolicy.Warn),
      OsciMailbox.resource[IO](cfg, certSource, transport)
    ).tupled.use { case (bridge, mailbox) =>
      for {
        ownCipher <- ownCipherCert
        receipt   <- bridge.store(loopbackRoute(cfg, ownCipher), "zustellix-it", payload)
        _ = assert(receipt.messageId.nonEmpty, s"expected a messageId, got $receipt")
        _ = assert(receipt.status.startsWith("0"), s"expected a success status, got $receipt")
        page <- mailbox.pending
        _ = assert(
              page.deliveries.exists(_.messageId == receipt.messageId),
              s"'${receipt.messageId}' not pending (truncated=${page.truncated}, " +
                s"listed=${page.deliveries.map(_.messageId)})"
            )
        msg <- mailbox.fetch(receipt.messageId)
        _ = assertEquals(msg.messageId, receipt.messageId)
        _ = assertEquals(msg.xml, payload)
        _ = assertEquals(msg.signature, ContentSignatureStatus.Valid)
        // The reception entry recorded at fetch time IS the acknowledgement.
        after <- mailbox.pending
        _ = assert(
              !after.deliveries.exists(_.messageId == receipt.messageId),
              s"'${receipt.messageId}' still pending after fetch"
            )
      } yield ()
    }
  }

  test("re-fetch by id after reception still returns the delivery") {
    // Backs the README's at-least-once claim: deliveries remain fetchable by
    // message id after their reception entry exists (intermediary retention
    // permitting), so a crashed consumer can re-fetch unprocessed ids.
    assume(mailboxReady, s"set ${mailboxVars.mkString(", ")} to run")
    val payload = s"<xml>it-${UUID.randomUUID()}</xml>"
    val cfg     = mailboxConfig
    (
      OsciBibBridge.resource[IO](certSource, transport, ContentSignaturePolicy.Warn),
      OsciMailbox.resource[IO](cfg, certSource, transport)
    ).tupled.use { case (bridge, mailbox) =>
      for {
        ownCipher <- ownCipherCert
        receipt   <- bridge.store(loopbackRoute(cfg, ownCipher), "zustellix-it", payload)
        first     <- mailbox.fetch(receipt.messageId)
        again     <- mailbox.fetch(receipt.messageId)
      } yield {
        assertEquals(first.xml, payload)
        assertEquals(again.messageId, receipt.messageId)
        assertEquals(again.xml, payload)
      }
    }
  }
}
