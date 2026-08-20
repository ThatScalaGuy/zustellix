package de.thatscalaguy.zustellix.osci.internal

import de.thatscalaguy.zustellix.osci.{ContentSignatureStatus, OsciFeedback, OsciReceipt}

import java.net.URI
import java.security.cert.X509Certificate

/** Full OSCI route for one outbound message, resolved from a single DVDV
 *  `findServiceDescription` call against the recipient AGS:
 *    - addressee = the recipient authority (OSCI_ADDRESSEE service element)
 *    - intermediary = the OSCI manager that fronts the recipient
 *      (OSCI_INTERMEDIARY service element, same service description)
 *
 *  Both come from DVDV, so neither needs to be configured statically.
 *  Caching is inherited from `DvdvClient.findServiceDescription` (mules).
 */
final case class OsciRoute(
    addresseeUri:    URI,
    addresseeCipher: X509Certificate,
    addresseeSig:    Option[X509Certificate],
    intermedUri:     URI,
    intermedCipher:  X509Certificate
)

/** Raw OSCI transmission result, before being mapped into a domain Laufzettel.
 *  `signature` is the verified content-signature status of `responseXml`
 *  (`None` when the response carried no content to check).
 */
final case class OsciRawResult(
    responseXml: String,
    messageId:   String,
    status:      String,
    warnings:    List[OsciFeedback] = Nil,
    signature:   Option[ContentSignatureStatus] = None
)

/** Narrow mockable seam over the Governikus osci-bibliothek Java library for
 *  the outbound (active) message types. Implementations:
 *    - [[OsciBibBridge]] — production, drives osci-bibliothek inside
 *      `Sync[F].blocking`.
 *    - Test fakes — anonymous trait impls in test code.
 */
trait OsciTransport[F[_]] {

  /** Synchronous request/response (`MediateDelivery`). */
  def mediate(route: OsciRoute, subject: String, xml: String): F[OsciRawResult]

  /** Asynchronous send into the recipient's mailbox (`StoreDelivery`). */
  def store(route: OsciRoute, subject: String, xml: String): F[OsciReceipt]
}
