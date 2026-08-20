package de.thatscalaguy.zustellix.osci.internal

import de.thatscalaguy.zustellix.osci.{
  ContentSignaturePolicy,
  ContentSignatureStatus,
  OsciError,
  OsciFeedback
}

import de.osci.osci12.OSCIException
import de.osci.osci12.messageparts.{Content, ContentContainer, EncryptedDataOSCI, Timestamp}
import de.osci.osci12.roles.{Addressee, Originator, Role}

import java.security.GeneralSecurityException
import java.time.{Instant, OffsetDateTime}
import scala.util.Try

/** Pure helpers shared by the OSCI bridges (outbound and mailbox). All
 *  feedback and content handling of osci-bibliothek messages lives here so it
 *  can be unit-tested without a gateway.
 */
private[osci] object OsciBibSupport {

  // OSCI feedback rows are [lang, code, text]. OSCI 1.2 classifies codes by
  // their first digit: "0" = success, "3" = warning (the request WAS
  // executed — e.g. 3802 "recipient signature over the acceptance/processing
  // response missing"), "9" = error (the request was not executed). Warnings
  // must not fail the call; they are surfaced via feedbackWarnings. All rows
  // are scanned — an error in a later row (e.g. behind a per-language
  // duplicate of row 0) fails too. `messageId` (when already issued at this
  // point of the dialog) rides along on the raised OsciResponse so callers
  // can still correlate the failed delivery.
  def checkFeedback(fb: Array[Array[String]], messageId: Option[String] = None): Unit =
    feedbackRows(fb).foreach { row =>
      if row.length >= 2 && row(1) != null
        && !row(1).startsWith("0") && !row(1).startsWith("3")
      then {
        val detail = if row.length >= 3 then Option(row(2)).getOrElse("") else ""
        throw OsciError.OsciResponse(row(1), detail, messageId)
      }
    }

  /** Warning-class (`3xxx`) feedback entries, deduplicated by code — the
   *  intermediary usually repeats the same code once per requested language.
   */
  def feedbackWarnings(fb: Array[Array[String]]): List[OsciFeedback] =
    feedbackRows(fb)
      .collect {
        case row if row.length >= 2 && row(1) != null && row(1).startsWith("3") =>
          val text = if row.length >= 3 then Option(row(2)).getOrElse("") else ""
          OsciFeedback(row(1), text)
      }
      .distinctBy(_.code)

  def topFeedbackCode(fb: Array[Array[String]]): String =
    feedbackRows(fb).headOption.flatMap { r =>
      if r.length >= 2 then Option(r(1)) else None
    }.getOrElse("")

  private def feedbackRows(fb: Array[Array[String]]): List[Array[String]] =
    Option(fb).map(_.toList).getOrElse(Nil).filter(_ != null)

  def firstContentData(ccs: List[ContentContainer]): Option[String] =
    firstContent(ccs).map(_._2)

  /** The first non-empty content payload, together with the container that
   *  carries it — the container is what the content signature covers.
   */
  private def firstContent(ccs: List[ContentContainer]): Option[(ContentContainer, String)] =
    ccs.iterator
      .flatMap { cc =>
        Option(cc.getContents).map(_.toList).getOrElse(Nil).map(c => (cc, c.getContentData))
      }
      .find { case (_, s) => s != null && s.nonEmpty }

  /** Content data of a received message, with its content signature verified:
   *  plaintext containers are tried first, then the `EncryptedDataOSCI`
   *  entries are decrypted with `decryptWith` (the role carrying our own
   *  Decrypter — payloads addressed to us are encrypted to our cipher cert).
   *  The container the returned xml came from is checked via
   *  [[verifyContentSignature]] — the dialog default only verifies the
   *  intermediary envelope signature, not the author's content signature.
   */
  def extractVerifiedXml(
      plain:       Array[ContentContainer],
      encrypted:   Array[EncryptedDataOSCI],
      decryptWith: Role,
      policy:      ContentSignaturePolicy,
      messageId:   Option[String]
  ): Option[(String, ContentSignatureStatus)] =
    firstContent(Option(plain).map(_.toList).getOrElse(Nil))
      .orElse {
        val enc = Option(encrypted).map(_.toList).getOrElse(Nil)
        firstContent(enc.flatMap(e => Option(e.decrypt(decryptWith))))
      }
      .map { case (cc, xml) => (xml, verifyContentSignature(cc, policy, messageId)) }

  /** `ContentContainer.checkAllSignatures()` over the container the returned
   *  content came from. An invalid (or uncheckable) signature always raises;
   *  the policy only decides whether *unsigned* content raises or is
   *  surfaced as [[ContentSignatureStatus.Unsigned]].
   */
  def verifyContentSignature(
      cc:        ContentContainer,
      policy:    ContentSignaturePolicy,
      messageId: Option[String]
  ): ContentSignatureStatus =
    if Option(cc.getSigners).forall(_.isEmpty) then
      policy match {
        case ContentSignaturePolicy.Require => throw OsciError.UnsignedContent(messageId)
        case ContentSignaturePolicy.Warn    => ContentSignatureStatus.Unsigned
      }
    else {
      val ok =
        try cc.checkAllSignatures()
        catch case e: Exception => throw OsciError.InvalidContentSignature(messageId, e)
      if !ok then throw OsciError.InvalidContentSignature(messageId)
      ContentSignatureStatus.Valid
    }

  /** OSCI content-data profile (XMeld, XFamilie, ...): the Inhaltsdaten must
   *  be signed by the Autor (Originator) and end-to-end encrypted for the
   *  Addressee, so the intermediary stays blind to the personal data. The
   *  DialogHandler's message-level signature/encryption only protects the
   *  envelope towards the intermediary — it is not sufficient.
   */
  def signedEncryptedPayload(
      xml:        String,
      signer:     Originator,
      encryptFor: Addressee
  ): EncryptedDataOSCI = {
    val container = new ContentContainer()
    container.addContent(new Content(xml))
    container.sign(signer)
    val encrypted = new EncryptedDataOSCI(container)
    encrypted.encrypt(encryptFor)
    encrypted
  }

  def parseTimestamp(ts: Timestamp): Option[Instant] =
    Option(ts).flatMap(t => parseInstant(t.getTimeStamp))

  /** OSCI timestamps are xsd:dateTime strings, usually with an offset. */
  def parseInstant(s: String): Option[Instant] =
    Option(s).flatMap { str =>
      Try(OffsetDateTime.parse(str).toInstant).orElse(Try(Instant.parse(str))).toOption
    }

  /** Shared exception mapping for all osci-bibliothek blocking bodies. */
  def toOsciError(e: Exception): Exception =
    e match {
      case e: OsciError                => e
      case e: OSCIException            => OsciError.OsciTransport(e)
      case e: java.io.IOException      => OsciError.OsciTransport(e)
      case e: GeneralSecurityException => OsciError.Certificate(e)
      case e                           => OsciError.OsciTransport(e)
    }
}
