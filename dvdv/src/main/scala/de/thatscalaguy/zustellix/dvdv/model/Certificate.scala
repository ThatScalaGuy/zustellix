package de.thatscalaguy.zustellix.dvdv.model

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class Certificate(
    content: Option[String] = None,
    fingerprint: Option[String] = None,
    serial: Option[String] = None,
    serialHex: Option[String] = None,
    emailSubject: Option[String] = None,
    algorithm: Option[String] = None,
    nameIssuer: Option[String] = None,
    nameSubject: Option[String] = None,
    organizationIssuer: Option[String] = None,
    organizationSubject: Option[String] = None,
    ouIssuer: Option[String] = None,
    ouSubject: Option[String] = None,
    validFrom: Option[String] = None,
    validTo: Option[String] = None,
    x509KeyUsage: Option[Int] = None,
    revocationDate: Option[String] = None,
    revocationReason: Option[RevocationReason] = None
)

object Certificate {
  given Codec[Certificate] = deriveCodec
}
