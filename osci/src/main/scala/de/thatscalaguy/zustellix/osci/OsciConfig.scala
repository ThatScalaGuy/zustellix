package de.thatscalaguy.zustellix.osci

import de.thatscalaguy.zustellix.utils.cert.CertSource

/** Outbound OSCI client configuration.
 *
 *  `serviceUri` selects the DVDV service description the recipient routes are
 *  resolved from; `subject` is the OSCI message subject. The defaults match
 *  the XMeld Personensuche profile — XFamilie (or other) profiles override
 *  both.
 */
final case class OsciConfig(
    tenantId: TenantId,
    certSource: CertSource,
    serviceUri: String = OsciConfig.DefaultXMeldServiceUri,
    subject: String = OsciConfig.DefaultSubject
)

object OsciConfig {
  val DefaultXMeldServiceUri: String =
    "http://www.osci.de/xmeld2605/xmeld2605Personensuche.wsdl"

  val DefaultSubject: String = "XMeld"
}
