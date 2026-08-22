package de.thatscalaguy.zustellix.osci.internal

import de.thatscalaguy.zustellix.osci.{
  ContentSignaturePolicy,
  ContentSignatureStatus,
  DrainFailure,
  OsciError,
  OsciFeedback,
  OsciMessage
}

import de.osci.osci12.OSCIException
import de.osci.osci12.common.{DialogHandler, OSCIErrorException, SoapServerException}
import de.osci.osci12.messageparts.{Content, ContentContainer, EncryptedDataOSCI, Timestamp}
import de.osci.osci12.messagetypes.{ExitDialog, InitDialog}
import de.osci.osci12.roles.{Addressee, Originator, Role}

import java.security.GeneralSecurityException
import java.time.{Instant, OffsetDateTime}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/** Helpers shared by the OSCI bridges (outbound and mailbox). Feedback and
 *  content handling of osci-bibliothek messages and the explicit-dialog
 *  lifecycle live here so they can be unit-tested without a gateway.
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

  /** The message id a FetchDelivery response is answering for. Null means the
   *  response carried no MessageId element — fall back to the requested id. A
   *  non-null id different from the requested one means the intermediary
   *  answered for the wrong delivery and raises
   *  [[OsciError.MessageIdMismatch]] — silently substituting the requested id
   *  would let the caller acknowledge the wrong message. An empty returned id
   *  is deliberately a mismatch, not an absence — a present-but-empty
   *  MessageId element is itself anomalous.
   */
  def confirmMessageId(requested: String, returned: String): String = {
    if returned != null && returned != requested then
      throw OsciError.MessageIdMismatch(requested, returned)
    requested
  }

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
   *  Each encrypted entry is tried independently: an entry that fails to
   *  decrypt (e.g. it carries no EncryptedKey for our role) does not stop a
   *  later entry from being tried. If encrypted entries exist and none
   *  decrypts, the first decrypt failure is rethrown — via [[toOsciError]]
   *  at the bridges' catch sites — so a genuinely wrong key is not masked
   *  as an absent message (`NoSuchMessage`). The container the returned xml
   *  came from is checked via [[verifyContentSignature]] — the dialog
   *  default only verifies the intermediary envelope signature, not the
   *  author's content signature.
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
        val enc       = Option(encrypted).map(_.toList).getOrElse(Nil)
        val attempts  = enc.map(e => Try(Option(e.decrypt(decryptWith))))
        val decrypted = attempts.collect { case Success(Some(cc)) => cc }
        if enc.nonEmpty && decrypted.isEmpty then
          attempts.collectFirst { case Failure(e) => e }.foreach(e => throw e)
        firstContent(decrypted)
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

  /** Explicit-dialog lifecycle shared by the bridges: `body` runs between an
   *  `InitDialog` and a best-effort `ExitDialog`. The `InitDialog` response's
   *  feedback is run through [[checkFeedback]] before the body — a `9xxx`
   *  feedback (dialog refused, certificate rejected) raises
   *  [[OsciError.OsciResponse]] carrying `messageId` when the caller already
   *  holds one, and neither the body nor `ExitDialog` runs since no dialog
   *  was opened. The exit is attempted exactly when the init succeeded (sent
   *  and not `9xxx`-refused) — a failed delivery (send error, 9xxx feedback)
   *  must not leave the dialog open at the intermediary. A NonFatal exit
   *  failure is swallowed so the body's outcome (result or exception) wins.
   */
  def withExplicitDialog[A](dialog: DialogHandler, messageId: Option[String] = None)(
      body: => A
  ): A =
    withExplicitDialog(
      () => new InitDialog(dialog).send().getFeedback,
      () => { new ExitDialog(dialog).send(); () },
      messageId
    )(body)

  /** Thunk seam for the overload above so the lifecycle is unit-testable
   *  without a gateway (a real InitDialog/ExitDialog send needs a parseable
   *  intermediary response). `init` returns the feedback rows of the init
   *  response, which are checked here via [[checkFeedback]]. (No default for
   *  `messageId` — Scala allows defaults on only one overloaded variant.)
   */
  def withExplicitDialog[A](init: () => Array[Array[String]], exit: () => Unit)(body: => A): A =
    withExplicitDialog(init, exit, None)(body)

  def withExplicitDialog[A](
      init: () => Array[Array[String]],
      exit: () => Unit,
      messageId: Option[String]
  )(body: => A): A = {
    checkFeedback(init(), messageId)
    try body
    finally {
      try exit()
      catch case NonFatal(_) => () // best-effort cleanup
    }
  }

  /** The fetch loop of a mailbox drain: walks `ids` (at most `max`) in
   *  listing order, fetching each via `fetchOne`. A fetch that throws stops
   *  the loop — the messages fetched before it are kept (they are already
   *  acknowledged at the intermediary) and the failure is mapped via
   *  [[toOsciError]] into the returned [[DrainFailure]]; later ids are never
   *  touched. Lives here as the unit-testable seam for the drain's
   *  sequencing — a success-path wire fake is impossible (challenge echo +
   *  envelope encryption, see `OsciBibBridgeWireSpec`).
   */
  def drainSequence(
      ids:      List[String],
      max:      Int,
      fetchOne: String => OsciMessage
  ): (List[OsciMessage], Option[DrainFailure]) = {
    val fetched                       = List.newBuilder[OsciMessage]
    var failure: Option[DrainFailure] = None
    val remaining                     = ids.take(max).iterator
    while (failure.isEmpty && remaining.hasNext) {
      val id = remaining.next()
      try fetched += fetchOne(id)
      catch case e: Exception => failure = Some(DrainFailure(id, toOsciError(e)))
    }
    (fetched.result(), failure)
  }

  def parseTimestamp(ts: Timestamp): Option[Instant] =
    Option(ts).flatMap(t => parseInstant(t.getTimeStamp))

  /** OSCI timestamps are xsd:dateTime strings, usually with an offset. */
  def parseInstant(s: String): Option[Instant] =
    Option(s).flatMap { str =>
      Try(OffsetDateTime.parse(str).toInstant).orElse(Try(Instant.parse(str))).toOption
    }

  /** Shared exception mapping for all osci-bibliothek blocking bodies.
   *
   *  `OSCIErrorException` / `SoapServerException` are the SOAP-fault shape of
   *  an error-class (`9xxx`) intermediary response; their `getErrorCode`
   *  surfaces as [[OsciError.OsciResponse]] so the same failure is typed
   *  identically whether it arrives as feedback rows (see [[checkFeedback]])
   *  or as a SOAP fault (`messageId` stays `None` — no id is in scope at the
   *  catch sites). `IllegalArgumentException` / `IllegalStateException` are
   *  caller errors against the library API (e.g.
   *  `FetchProcessCard.setQuantityLimit` rejecting a non-positive limit) and
   *  map to [[OsciError.Config]]. `SoapClientException` deliberately stays
   *  [[OsciError.OsciTransport]] — the library also raises it for locally
   *  detected malformed responses, so its code is not a reliable
   *  intermediary verdict.
   */
  def toOsciError(e: Exception): OsciError =
    e match {
      case e: OsciError                => e
      case e: OSCIErrorException       => OsciError.OsciResponse(codeOf(e), detailOf(e))
      case e: SoapServerException      => OsciError.OsciResponse(codeOf(e), detailOf(e))
      case e: OSCIException            => OsciError.OsciTransport(e)
      case e: java.io.IOException      => OsciError.OsciTransport(e)
      case e: GeneralSecurityException => OsciError.Certificate(e)
      case e: IllegalArgumentException => OsciError.Config(reasonOf(e))
      case e: IllegalStateException    => OsciError.Config(reasonOf(e))
      case e                           => OsciError.OsciTransport(e)
    }

  // The no-arg OSCIException ctor leaves a literal "null" code; the subclass
  // ctors always set one, but stay null-safe anyway.
  private def codeOf(e: OSCIException): String =
    Option(e.getErrorCode).getOrElse("")

  // getMessage is the SOAP faultstring when the fault carried one;
  // getLocalizedMessage is the library's bundle text keyed by the code (it
  // catches lookup failures internally and may return null).
  private def detailOf(e: OSCIException): String =
    Option(e.getMessage).orElse(Option(e.getLocalizedMessage)).getOrElse("")

  private def reasonOf(e: Exception): String =
    Option(e.getMessage).getOrElse(e.toString)
}
