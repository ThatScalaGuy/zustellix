package de.thatscalaguy.zustellix.osci

sealed abstract class OsciError(msg: String, cause: Throwable | Null = null)
    extends RuntimeException(msg, cause)

object OsciError {

  final case class UnknownTenant(id: TenantId)
      extends OsciError(s"Unknown tenant: ${id.value}")

  final case class AgsNotInDvdv(ags: String, serviceUri: String)
      extends OsciError(
        s"AGS '$ags' has no service registered for '$serviceUri' in DVDV"
      )

  final case class RecipientCertMissing(ags: String)
      extends OsciError(
        s"DVDV service description for AGS '$ags' has no cipher certificate"
      )

  final case class ServiceElementMissing(ags: String, kind: String)
      extends OsciError(
        s"DVDV service description for AGS '$ags' is missing service element of type '$kind'"
      )

  final case class OsciTransport(cause: Throwable)
      extends OsciError("OSCI transport failure", cause)

  /** OSCI returned an error-class (`9xxx`) feedback code. `messageId` is the
   *  intermediary-issued message id when one was already assigned before the
   *  failure (i.e. `GetMessageId` succeeded and the delivery itself failed);
   *  `None` when the failure happened before an id existed.
   */
  final case class OsciResponse(
      code:      String,
      detail:    String,
      messageId: Option[String] = None
  ) extends OsciError(s"OSCI response error [$code]: $detail")

  final case class NoSuchMessage(messageId: String)
      extends OsciError(s"No delivery found for messageId '$messageId'")

  final case class Certificate(cause: Throwable)
      extends OsciError("Certificate / key error", cause)

  final case class Config(reason: String)
      extends OsciError(s"Configuration error: $reason")
}
