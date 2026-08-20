package de.thatscalaguy.zustellix.osci

import de.thatscalaguy.zustellix.utils.cert.CertSource

import scala.concurrent.duration.FiniteDuration

/** Outbound OSCI client configuration.
 *
 *  `serviceUri` selects the DVDV service description the recipient routes are
 *  resolved from; `subject` is the OSCI message subject. The defaults match
 *  the XMeld Personensuche profile — XFamilie (or other) profiles override
 *  both.
 *
 *  `connectTimeout` / `readTimeout` bound the default [[OsciHttpTransport]]'s
 *  `HttpURLConnection`s; they are ignored when a custom transport is passed
 *  to `OsciClient.resource`.
 */
final case class OsciConfig(
    tenantId: TenantId,
    /** The Originator's signing + decryption cert. Leave empty when building
     *  the client from a [[de.thatscalaguy.zustellix.utils.cert.CertManager]]
     *  + alias, which supplies the cert (and the tenant id) itself.
     */
    certSource: Option[CertSource] = None,
    serviceUri: String = OsciConfig.DefaultXMeldServiceUri,
    subject: String = OsciConfig.DefaultSubject,
    connectTimeout: FiniteDuration = OsciHttpTransport.DefaultConnectTimeout,
    readTimeout: FiniteDuration = OsciHttpTransport.DefaultReadTimeout
)

object OsciConfig {
  val DefaultXMeldServiceUri: String =
    "http://www.osci.de/xmeld2605/xmeld2605Personensuche.wsdl"

  val DefaultSubject: String = "XMeld"
}
