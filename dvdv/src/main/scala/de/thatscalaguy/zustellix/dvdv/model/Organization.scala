package de.thatscalaguy.zustellix.dvdv.model

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class Organization(
    id: Option[Long] = None,
    validFrom: Option[String] = None,
    validTo: Option[String] = None,
    nameDe: String,
    nameEn: Option[String] = None,
    descriptionDe: Option[String] = None,
    descriptionEn: Option[String] = None,
    postalAddress: Option[String] = None,
    locationStateId: Option[Long] = None,
    dvdv1Id: Option[String] = None,
    category: Option[String] = None,
    organizationKeys: List[String],
    clientCertificates: Option[List[Certificate]] = None,
    services: Option[List[ServiceBase]] = None
)

object Organization {
  given Codec[Organization] = deriveCodec
}

final case class LightweightOrganization(
    id: Option[Long] = None,
    validFrom: Option[String] = None,
    validTo: Option[String] = None,
    nameDe: Option[String] = None,
    nameEn: Option[String] = None,
    descriptionDe: Option[String] = None,
    descriptionEn: Option[String] = None,
    postalAddress: Option[String] = None,
    locationStateId: Option[Long] = None,
    dvdv1Id: Option[String] = None,
    category: Option[String] = None,
    organizationKeys: List[String] = Nil
)

object LightweightOrganization {
  given Codec[LightweightOrganization] = deriveCodec
}

final case class OrganizationDescription(
    organization: Option[Organization] = None,
    representatives: Option[List[Organization]] = None
)

object OrganizationDescription {
  given Codec[OrganizationDescription] = deriveCodec
}
