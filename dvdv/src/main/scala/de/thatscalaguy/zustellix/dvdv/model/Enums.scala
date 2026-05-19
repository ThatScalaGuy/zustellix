package de.thatscalaguy.zustellix.dvdv.model

import io.circe.{Codec, Decoder, Encoder}

enum ServiceElementType {
  case OSCI_INTERMEDIARY, OSCI_ADDRESSEE, CIPHER_CERTIFICATE, SIGNATURE_CERTIFICATE, WEBSERVER, TEXT
}

object ServiceElementType {
  given Codec[ServiceElementType] =
    Codec.from(
      Decoder.decodeString.emap(s =>
        scala.util.Try(ServiceElementType.valueOf(s)).toEither.left.map(_ => s"Unknown ServiceElementType: $s")
      ),
      Encoder.encodeString.contramap(_.toString)
    )
}

enum ParameterType {
  case CIPHER_CERTIFICATE, SIGNATURE_CERTIFICATE, URI
}

object ParameterType {
  given Codec[ParameterType] =
    Codec.from(
      Decoder.decodeString.emap(s =>
        scala.util.Try(ParameterType.valueOf(s)).toEither.left.map(_ => s"Unknown ParameterType: $s")
      ),
      Encoder.encodeString.contramap(_.toString)
    )
}

enum ServiceSpecificationType {
  case WSDL_OSCI, MANUAL
}

object ServiceSpecificationType {
  given Codec[ServiceSpecificationType] =
    Codec.from(
      Decoder.decodeString.emap(s =>
        scala.util.Try(ServiceSpecificationType.valueOf(s)).toEither.left.map(_ => s"Unknown ServiceSpecificationType: $s")
      ),
      Encoder.encodeString.contramap(_.toString)
    )
}

enum RevocationReason {
  case UNSPECIFIED, KEY_COMPROMISE, CA_COMPROMISE, AFFILIATION_CHANGED, SUPERSEDED,
    CESSATION_OF_OPERATION, CERTIFICATE_HOLD, UNUSED, REMOVE_FROM_CRL, PRIVILEGE_WITHDRAWN, AA_COMPROMISE
}

object RevocationReason {
  given Codec[RevocationReason] =
    Codec.from(
      Decoder.decodeString.emap(s =>
        scala.util.Try(RevocationReason.valueOf(s)).toEither.left.map(_ => s"Unknown RevocationReason: $s")
      ),
      Encoder.encodeString.contramap(_.toString)
    )
}
