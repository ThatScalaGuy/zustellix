package de.thatscalaguy.zustellix.oscixmeld

sealed abstract class OSCIXMeldError(msg: String, cause: Throwable | Null = null)
    extends RuntimeException(msg, cause)

object OSCIXMeldError {

  final case class UnknownTenant(id: TenantId)
      extends OSCIXMeldError(s"Unknown tenant: ${id.value}")

  final case class AgsNotInDvdv(ags: String, serviceUri: String)
      extends OSCIXMeldError(
        s"AGS '$ags' has no service registered for '$serviceUri' in DVDV"
      )

  final case class RecipientCertMissing(ags: String)
      extends OSCIXMeldError(
        s"DVDV service description for AGS '$ags' has no cipher certificate"
      )

  final case class ServiceElementMissing(ags: String, kind: String)
      extends OSCIXMeldError(
        s"DVDV service description for AGS '$ags' is missing service element of type '$kind'"
      )

  final case class OsciTransport(cause: Throwable)
      extends OSCIXMeldError("OSCI transport failure", cause)

  final case class OsciResponse(code: String, detail: String)
      extends OSCIXMeldError(s"OSCI response error [$code]: $detail")

  final case class Certificate(cause: Throwable)
      extends OSCIXMeldError("Certificate / key error", cause)

  final case class Config(reason: String)
      extends OSCIXMeldError(s"Configuration error: $reason")
}
