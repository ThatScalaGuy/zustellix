package de.thatscalaguy.zustellix.dvdv.model

import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.semiauto.deriveCodec

final case class VerificationResult(verifyCategory: Boolean)

object VerificationResult {
  given Codec[VerificationResult] = deriveCodec
}

final case class SummaryServiceElementDTO(
    id: Long,
    orgaProvId: Option[Long] = None,
    provider: Option[Boolean] = None,
    orgaProvName: Option[String] = None,
    orgaProvAddress: Option[String] = None,
    selName: Option[String] = None,
    selDescription: Option[String] = None,
    uri: Option[String] = None
)

object SummaryServiceElementDTO {
  given Codec[SummaryServiceElementDTO] = deriveCodec
}

final case class AccessTokenResponse(
    access_token: String,
    expires_in: Option[Long] = None,
    refresh_expires_in: Option[Long] = None,
    refresh_token: Option[String] = None,
    token_type: Option[String] = None,
    id_token: Option[String] = None,
    `not-before-policy`: Option[Int] = None,
    session_state: Option[String] = None,
    scope: Option[String] = None
)

object AccessTokenResponse {
  given Codec[AccessTokenResponse] = deriveCodec
}

final case class ServiceVersion(
    version: Option[String] = None,
    buildnumber: Option[String] = None,
    schemaversion: Option[String] = None,
    raw: Option[String] = None
)

object ServiceVersion {
  // The endpoint is documented to return a JSON string with keys version/buildnumber/schemaversion,
  // but the schema in the YAML claims plain string. Accept either shape.
  private val objectCodec: Codec[ServiceVersion] = deriveCodec
  given Codec[ServiceVersion] = Codec.from(
    Decoder.instance { c =>
      c.as[String] match {
        case Right(s) => Right(ServiceVersion(raw = Some(s)))
        case Left(_)  => objectCodec(c)
      }
    },
    Encoder.instance(objectCodec(_))
  )
}
