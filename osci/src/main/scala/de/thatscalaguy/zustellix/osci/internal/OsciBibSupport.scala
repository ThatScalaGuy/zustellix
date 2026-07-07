package de.thatscalaguy.zustellix.osci.internal

import de.thatscalaguy.zustellix.osci.OsciError

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

  // OSCI feedback rows are [lang, code, text]; a code starting with "0" means
  // success. Only row 0 is inspected — a documented residual gap, see
  // OsciBibSupportSpec.
  def checkFeedback(fb: Array[Array[String]]): Unit =
    Option(fb).filter(_.nonEmpty).map(_(0)) match {
      case Some(row) if row.length >= 2 && row(1) != null && !row(1).startsWith("0") =>
        val detail = if row.length >= 3 then Option(row(2)).getOrElse("") else ""
        throw OsciError.OsciResponse(row(1), detail)
      case _ => ()
    }

  def topFeedbackCode(fb: Array[Array[String]]): String =
    Option(fb).filter(_.nonEmpty).flatMap(_.headOption).flatMap { r =>
      if r.length >= 2 then Option(r(1)) else None
    }.getOrElse("")

  def firstContentData(ccs: List[ContentContainer]): Option[String] =
    ccs.iterator
      .flatMap(cc => Option(cc.getContents).map(_.toList).getOrElse(Nil))
      .map(_.getContentData)
      .find(s => s != null && s.nonEmpty)

  /** Content data of a received message: plaintext containers are tried
   *  first, then the `EncryptedDataOSCI` entries are decrypted with
   *  `decryptWith` (the role carrying our own Decrypter — payloads addressed
   *  to us are encrypted to our cipher cert).
   */
  def extractXml(
      plain:       Array[ContentContainer],
      encrypted:   Array[EncryptedDataOSCI],
      decryptWith: Role
  ): Option[String] =
    firstContentData(Option(plain).map(_.toList).getOrElse(Nil)).orElse {
      val enc       = Option(encrypted).map(_.toList).getOrElse(Nil)
      val decrypted = enc.flatMap(e => Option(e.decrypt(decryptWith)))
      firstContentData(decrypted)
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
