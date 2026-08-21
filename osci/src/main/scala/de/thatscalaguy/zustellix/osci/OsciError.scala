package de.thatscalaguy.zustellix.osci

sealed abstract class OsciError(msg: String, cause: Throwable | Null = null)
    extends RuntimeException(msg, cause)

object OsciError {

  final case class UnknownTenant(id: TenantId)
      extends OsciError(s"Unknown tenant: ${id.value}")

  /** `input` is not a well-formed AGS (exactly 8 digits) — raised by the
   *  [[Ags]] smart constructors, never by a lookup.
   */
  final case class InvalidAgs(input: String)
      extends OsciError(s"Invalid AGS '$input': expected exactly 8 digits")

  final case class AgsNotInDvdv(ags: Ags, serviceUri: String)
      extends OsciError(
        s"AGS '${ags.value}' has no service registered for '$serviceUri' in DVDV"
      )

  /** The service description resolved for `ags` carries no cipher
   *  certificate for the `kind` service element (`OSCI_ADDRESSEE` or
   *  `OSCI_INTERMEDIARY`).
   */
  final case class RecipientCertMissing(ags: Ags, kind: String)
      extends OsciError(
        s"DVDV service description for AGS '${ags.value}' has no cipher certificate for '$kind'"
      )

  final case class ServiceElementMissing(ags: Ags, kind: String)
      extends OsciError(
        s"DVDV service description for AGS '${ags.value}' is missing service element of type '$kind'"
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

  /** Received content carries no author signature and the policy is
   *  [[ContentSignaturePolicy.Require]]. `messageId` identifies the affected
   *  message when one is known at that point.
   */
  final case class UnsignedContent(messageId: Option[String] = None)
      extends OsciError("OSCI content is not signed but the policy requires a content signature")

  /** The author's content signature on received content failed verification.
   *  Raised regardless of the configured [[ContentSignaturePolicy]] — a
   *  broken signature is never tolerated.
   */
  final case class InvalidContentSignature(
      messageId: Option[String] = None,
      cause:     Throwable | Null = null
  ) extends OsciError("OSCI content signature verification failed", cause)

  final case class Certificate(cause: Throwable)
      extends OsciError("Certificate / key error", cause)

  /** Raised by [[OsciFacade.fromConfigs]] when building one tenant's client
   *  fails during resource acquisition. The facade is all-or-nothing, so this
   *  fails the whole resource, but it names the offending tenant.
   */
  final case class TenantInitFailed(id: TenantId, cause: Throwable)
      extends OsciError(s"Tenant '${id.value}' failed to initialise: ${cause.getMessage}", cause)

  final case class Config(reason: String)
      extends OsciError(s"Configuration error: $reason")
}
