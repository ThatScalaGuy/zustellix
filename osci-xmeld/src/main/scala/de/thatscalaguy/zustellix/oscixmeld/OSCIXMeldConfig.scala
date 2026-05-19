package de.thatscalaguy.zustellix.oscixmeld

import de.thatscalaguy.zustellix.utils.cert.CertSource

import scala.concurrent.duration.*

final case class OSCIXMeldConfig(
    tenantId: TenantId,
    certSource: CertSource,
    serviceUri: String = OSCIXMeldConfig.DefaultXMeldServiceUri,
    category: String = "Meldebehörde",
    requestTimeout: FiniteDuration = 60.seconds
)

object OSCIXMeldConfig {
  val DefaultXMeldServiceUri: String =
    "http://www.osci.de/xmeld2605/xmeld2605Personensuche.wsdl"
}
