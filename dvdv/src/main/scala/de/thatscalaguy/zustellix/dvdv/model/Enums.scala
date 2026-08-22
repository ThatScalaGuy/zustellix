package de.thatscalaguy.zustellix.dvdv.model

import io.circe.{Codec, Decoder, Encoder}

/** Server constants unknown to this client decode to `Other` carrying the
 *  verbatim wire string, so a new server-side value never fails the whole
 *  response decode.
 */
enum ServiceElementType {
  case OSCI_INTERMEDIARY, OSCI_ADDRESSEE, CIPHER_CERTIFICATE, SIGNATURE_CERTIFICATE, WEBSERVER, TEXT
  case Other(raw: String)
}

object ServiceElementType {
  private val known: Map[String, ServiceElementType] =
    List(OSCI_INTERMEDIARY, OSCI_ADDRESSEE, CIPHER_CERTIFICATE, SIGNATURE_CERTIFICATE, WEBSERVER, TEXT)
      .map(v => v.toString -> v).toMap

  given Codec[ServiceElementType] =
    Codec.from(
      Decoder.decodeString.map(s => known.getOrElse(s, Other(s))),
      Encoder.encodeString.contramap {
        case Other(raw) => raw
        case v          => v.toString
      }
    )
}

/** Server constants unknown to this client decode to `Other` carrying the
 *  verbatim wire string, so a new server-side value never fails the whole
 *  response decode.
 */
enum ParameterType {
  case CIPHER_CERTIFICATE, SIGNATURE_CERTIFICATE, URI
  case Other(raw: String)
}

object ParameterType {
  private val known: Map[String, ParameterType] =
    List(CIPHER_CERTIFICATE, SIGNATURE_CERTIFICATE, URI)
      .map(v => v.toString -> v).toMap

  given Codec[ParameterType] =
    Codec.from(
      Decoder.decodeString.map(s => known.getOrElse(s, Other(s))),
      Encoder.encodeString.contramap {
        case Other(raw) => raw
        case v          => v.toString
      }
    )
}

/** Server constants unknown to this client decode to `Other` carrying the
 *  verbatim wire string, so a new server-side value never fails the whole
 *  response decode.
 */
enum ServiceSpecificationType {
  case WSDL_OSCI, MANUAL
  case Other(raw: String)
}

object ServiceSpecificationType {
  private val known: Map[String, ServiceSpecificationType] =
    List(WSDL_OSCI, MANUAL)
      .map(v => v.toString -> v).toMap

  given Codec[ServiceSpecificationType] =
    Codec.from(
      Decoder.decodeString.map(s => known.getOrElse(s, Other(s))),
      Encoder.encodeString.contramap {
        case Other(raw) => raw
        case v          => v.toString
      }
    )
}

/** Server constants unknown to this client decode to `Other` carrying the
 *  verbatim wire string, so a certificate revoked with a new reason still
 *  decodes and the revocation check stays fail-closed.
 */
enum RevocationReason {
  case UNSPECIFIED, KEY_COMPROMISE, CA_COMPROMISE, AFFILIATION_CHANGED, SUPERSEDED,
    CESSATION_OF_OPERATION, CERTIFICATE_HOLD, UNUSED, REMOVE_FROM_CRL, PRIVILEGE_WITHDRAWN, AA_COMPROMISE
  case Other(raw: String)
}

object RevocationReason {
  private val known: Map[String, RevocationReason] =
    List(UNSPECIFIED, KEY_COMPROMISE, CA_COMPROMISE, AFFILIATION_CHANGED, SUPERSEDED,
      CESSATION_OF_OPERATION, CERTIFICATE_HOLD, UNUSED, REMOVE_FROM_CRL, PRIVILEGE_WITHDRAWN, AA_COMPROMISE)
      .map(v => v.toString -> v).toMap

  given Codec[RevocationReason] =
    Codec.from(
      Decoder.decodeString.map(s => known.getOrElse(s, Other(s))),
      Encoder.encodeString.contramap {
        case Other(raw) => raw
        case v          => v.toString
      }
    )
}
