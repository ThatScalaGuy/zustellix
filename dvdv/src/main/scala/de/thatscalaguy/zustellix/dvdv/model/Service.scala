package de.thatscalaguy.zustellix.dvdv.model

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class ServiceBase(
    id: Option[Long] = None,
    validFrom: Option[String] = None,
    validTo: Option[String] = None,
    nameDe: Option[String] = None,
    nameEn: Option[String] = None,
    descriptionDe: Option[String] = None,
    descriptionEn: Option[String] = None,
    dvdv1Uuid: Option[String] = None,
    serviceDescriptionName: Option[String] = None,
    serviceSpecificationType: Option[ServiceSpecificationType] = None,
    serviceSpecificationUri: Option[String] = None
)

object ServiceBase {
  given Codec[ServiceBase] = deriveCodec
}

final case class ServiceElementInfo(
    serviceElementName: Option[String] = None,
    serviceElementDescription: Option[String] = None,
    serviceElementDescriptionName: Option[String] = None,
    serviceElementType: Option[ServiceElementType] = None,
    customServiceElementType: Option[String] = None,
    serviceElementText: Option[String] = None,
    serviceElementUri: Option[String] = None,
    cipherCertificate: Option[Certificate] = None,
    signatureCertificate: Option[Certificate] = None,
    required: Option[Boolean] = None,
    serviceElementId: Option[Long] = None,
    providerId: Option[Long] = None,
    providerNameDe: Option[String] = None,
    providerNameEn: Option[String] = None
)

object ServiceElementInfo {
  given Codec[ServiceElementInfo] = deriveCodec
}

final case class Service(
    id: Option[Long] = None,
    validFrom: Option[String] = None,
    validTo: Option[String] = None,
    nameDe: Option[String] = None,
    nameEn: Option[String] = None,
    descriptionDe: Option[String] = None,
    descriptionEn: Option[String] = None,
    dvdv1Uuid: Option[String] = None,
    serviceDescriptionName: Option[String] = None,
    serviceSpecificationType: Option[ServiceSpecificationType] = None,
    serviceSpecificationUri: Option[String] = None,
    serviceSpecificationDocument: Option[String] = None,
    organizationNameDe: Option[String] = None,
    organizationNameEn: Option[String] = None,
    locationStateId: Option[Long] = None,
    serviceElements: Option[List[ServiceElementInfo]] = None
)

object Service {
  given Codec[Service] = deriveCodec
}
