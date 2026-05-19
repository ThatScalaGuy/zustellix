package de.thatscalaguy.zustellix.dvdv.model

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class Request(
    fingerPrint: Option[String] = None,
    category: Option[String] = None,
    organizationKey: Option[String] = None,
    serviceSpecificationUri: Option[String] = None,
    serviceElementType: Option[ServiceElementType] = None,
    customServiceElementType: Option[String] = None,
    parameterType: Option[ParameterType] = None,
    parameterValue: Option[String] = None
)

object Request {
  given Codec[Request] = deriveCodec
}
