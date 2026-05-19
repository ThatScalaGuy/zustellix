package de.thatscalaguy.zustellix.oscixmeld.internal

import java.net.URI
import java.security.cert.X509Certificate

/** Full OSCI/XMeld route for one send, resolved from a single DVDV
 *  `findServiceDescription` call against the recipient AGS:
 *    - addressee = the recipient Meldebehörde (OSCI_ADDRESSEE service element)
 *    - intermediary = the OSCI manager that fronts the recipient
 *      (OSCI_INTERMEDIARY service element, same service description)
 *
 *  Both come from DVDV, so neither needs to be configured statically.
 *  Caching is inherited from `DvdvClient.findServiceDescription` (mules).
 */
final case class XmeldRoute(
    addresseeUri:    URI,
    addresseeCipher: X509Certificate,
    addresseeSig:    Option[X509Certificate],
    intermedUri:     URI,
    intermedCipher:  X509Certificate
)

/** Raw OSCI transmission result, before being mapped into a domain Laufzettel. */
final case class OsciRawResult(
    responseXml: String,
    messageId:   String,
    status:      String,
    raw:         Array[Byte]
)

/** Narrow mockable seam over the Governikus osci-bibliothek Java library.
 *  Implementations:
 *    - [[OsciBibBridge]] — production, drives osci-bibliothek inside
 *      `Sync[F].blocking`.
 *    - Test fakes — anonymous trait impls in test code.
 */
trait OsciTransport[F[_]] {
  def transmit(route: XmeldRoute, xml: String): F[OsciRawResult]
}
