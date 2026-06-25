package de.thatscalaguy.zustellix.oscixmeld.internal

import cats.effect.{Resource, Sync}
import de.thatscalaguy.zustellix.utils.cert.{CertCredential, CertSource}
import de.thatscalaguy.zustellix.oscixmeld.{OSCIXMeldConfig, OSCIXMeldError}

import de.osci.osci12.OSCIException
import de.osci.osci12.common.DialogHandler
import de.osci.osci12.extinterfaces.TransportI
import de.osci.osci12.extinterfaces.crypto.{Decrypter, Signer}
import de.osci.osci12.messageparts.{Content, ContentContainer, EncryptedDataOSCI}
import de.osci.osci12.messagetypes.{
  ExitDialog,
  GetMessageId,
  InitDialog,
  MediateDelivery,
  ResponseToMediateDelivery
}
import de.osci.osci12.roles.{Addressee, Intermed, Originator}
import de.osci.osci12.samples.impl.HttpTransport
import de.osci.osci12.samples.impl.crypto.{PKCS12Decrypter, PKCS12Signer}

import java.io.{ByteArrayInputStream, FileInputStream, InputStream}
import java.nio.file.Path
import java.security.{GeneralSecurityException, Security}

private[oscixmeld] object OsciBibBridge {

  def resource[F[_]: Sync](config: OSCIXMeldConfig): Resource[F, OsciTransport[F]] =
    build[F](buildSignerDecrypter[F](config))

  /** Alias-keyed path: the same PKCS12 the DVDV client uses, supplied by the
   *  shared [[de.thatscalaguy.zustellix.utils.cert.CertManager]] as bytes.
   */
  def resource[F[_]: Sync](cred: CertCredential): Resource[F, OsciTransport[F]] =
    build[F](buildSignerDecrypter[F](cred))

  private def build[F[_]: Sync](sd: F[(Signer, Decrypter)]): Resource[F, OsciTransport[F]] =
    for {
      _           <- Resource.eval(registerBouncyCastle[F])
      signerDecr  <- Resource.eval(sd)
      (signer, decrypter) = signerDecr
      originator   = new Originator(signer, decrypter)
    }
    yield new OsciBibBridgeImpl[F](originator)

  private def registerBouncyCastle[F[_]: Sync]: F[Unit] =
    Sync[F].blocking {
      if Security.getProvider("BC") == null then {
        val _ = Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
      }
    }

  private def buildSignerDecrypter[F[_]: Sync](config: OSCIXMeldConfig): F[(Signer, Decrypter)] =
    config.certSource match {
      case CertSource.Pkcs12(path, pwd) =>
        Sync[F].blocking {
          val s = openAndUse(path)(in => new PKCS12Signer(in, pwd))
          val d = openAndUse(path)(in => new PKCS12Decrypter(in, pwd))
          (s, d)
        }
      case _: CertSource.Pem =>
        Sync[F].raiseError(OSCIXMeldError.Config(
          "OSCI bridge requires CertSource.Pkcs12 (PEM is not supported in v1)"
        ))
    }

  private def buildSignerDecrypter[F[_]: Sync](cred: CertCredential): F[(Signer, Decrypter)] =
    Sync[F].blocking {
      val s = new PKCS12Signer(new ByteArrayInputStream(cred.pkcs12), cred.password)
      val d = new PKCS12Decrypter(new ByteArrayInputStream(cred.pkcs12), cred.password)
      (s, d)
    }

  private def openAndUse[A](p: Path)(f: InputStream => A): A = {
    val in = new FileInputStream(p.toFile)
    try f(in)
    finally in.close()
  }
}

private[oscixmeld] final class OsciBibBridgeImpl[F[_]: Sync](
    originator: Originator,
    transport: TransportI = new HttpTransport()
) extends OsciTransport[F] {

  def transmit(route: XmeldRoute, xml: String): F[OsciRawResult] =
    Sync[F].blocking {
      try {
        val addressee = new Addressee(route.addresseeSig.orNull, route.addresseeCipher)
        val intermed  = new Intermed(null, route.intermedCipher, route.intermedUri)
        val dialog    = new DialogHandler(originator, intermed, transport)

        val msgIdResp = new GetMessageId(dialog).send()
        checkFeedback(msgIdResp.getFeedback)

        new InitDialog(dialog).send()

        val mediate = new MediateDelivery(dialog, addressee, route.addresseeUri.toString)
        mediate.setMessageId(msgIdResp.getMessageId)
        mediate.setSubject("XMeld")
        mediate.setQualityOfTimeStampCreation(false)
        mediate.setQualityOfTimeStampReception(false)

        // XMeld/Meldewesen profile: the Inhaltsdaten must be signed by the
        // Autor (Originator) and end-to-end encrypted for the Addressee, so
        // the intermediary stays blind to the personal data. The
        // DialogHandler's message-level signature/encryption only protects
        // the envelope towards the intermediary — it is not sufficient.
        val container = new ContentContainer()
        container.addContent(new Content(xml))
        container.sign(originator)

        val encrypted = new EncryptedDataOSCI(container)
        encrypted.encrypt(addressee)
        mediate.addEncryptedData(encrypted)

        val rsp = mediate.send()
        checkFeedback(rsp.getFeedback)

        try new ExitDialog(dialog).send()
        catch case _: Throwable => () // best-effort cleanup

        OsciRawResult(
          responseXml = extractResponseXml(rsp),
          messageId   = msgIdResp.getMessageId,
          status      = topFeedbackCode(rsp.getFeedback),
          raw         = Array.emptyByteArray
        )
      }
      catch {
        case e: OSCIXMeldError              => throw e
        case e: OSCIException               => throw OSCIXMeldError.OsciTransport(e)
        case e: java.io.IOException         => throw OSCIXMeldError.OsciTransport(e)
        case e: GeneralSecurityException    => throw OSCIXMeldError.Certificate(e)
        case e: Exception                   => throw OSCIXMeldError.OsciTransport(e)
      }
    }

  private[internal] def checkFeedback(fb: Array[Array[String]]): Unit =
    Option(fb).filter(_.nonEmpty).map(_(0)) match {
      case Some(row) if row.length >= 2 && row(1) != null && !row(1).startsWith("0") =>
        val detail = if row.length >= 3 then Option(row(2)).getOrElse("") else ""
        throw OSCIXMeldError.OsciResponse(row(1), detail)
      case _ => ()
    }

  private[internal] def topFeedbackCode(fb: Array[Array[String]]): String =
    Option(fb).filter(_.nonEmpty).flatMap(_.headOption).flatMap { r =>
      if r.length >= 2 then Option(r(1)) else None
    }.getOrElse("")

  /** The synchronous Personensuche answer is returned encrypted to our
   *  cipher cert (the OSCI roles swap: our Originator becomes the response's
   *  Addressee). Plaintext `getContentContainer` is empty in that case, so we
   *  decrypt the `EncryptedDataOSCI` entries with our decrypter (carried by
   *  `originator`). Plaintext containers are still tried first as a fallback.
   */
  private def extractResponseXml(rsp: ResponseToMediateDelivery): String = {
    val plain = Option(rsp.getContentContainer).map(_.toList).getOrElse(Nil)
    firstContentData(plain).orElse {
      val encrypted = Option(rsp.getEncryptedData).map(_.toList).getOrElse(Nil)
      val decrypted = encrypted.flatMap(enc => Option(enc.decrypt(originator)))
      firstContentData(decrypted)
    }.getOrElse("")
  }

  private[internal] def firstContentData(ccs: List[ContentContainer]): Option[String] =
    ccs.iterator
      .flatMap(cc => Option(cc.getContents).map(_.toList).getOrElse(Nil))
      .map(_.getContentData)
      .find(s => s != null && s.nonEmpty)
}
