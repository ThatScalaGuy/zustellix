package de.thatscalaguy.zustellix.osci.internal

import cats.effect.{Resource, Sync}
import de.thatscalaguy.zustellix.utils.cert.{CertCredential, CertSource}
import de.thatscalaguy.zustellix.osci.{ContentSignaturePolicy, OsciError, OsciReceipt}

import de.osci.osci12.common.DialogHandler
import de.osci.osci12.extinterfaces.TransportI
import de.osci.osci12.extinterfaces.crypto.{Decrypter, Signer}
import de.osci.osci12.messagetypes.{
  ExitDialog,
  GetMessageId,
  InitDialog,
  MediateDelivery,
  StoreDelivery
}
import de.osci.osci12.roles.{Addressee, Intermed, Originator}
import de.osci.osci12.samples.impl.crypto.{PKCS12Decrypter, PKCS12Signer}

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.Security

private[osci] object OsciBibBridge {

  def resource[F[_]: Sync](
      certSource: CertSource,
      transport:  TransportI,
      contentSignatures: ContentSignaturePolicy
  ): Resource[F, OsciTransport[F]] =
    Resource.eval(originator[F](certSource))
      .map(new OsciBibBridgeImpl[F](_, transport, contentSignatures))

  /** Alias-keyed path: the same PKCS12 the DVDV client uses, supplied by the
   *  shared [[de.thatscalaguy.zustellix.utils.cert.CertManager]] as bytes.
   */
  def resource[F[_]: Sync](
      cred:      CertCredential,
      transport: TransportI,
      contentSignatures: ContentSignaturePolicy
  ): Resource[F, OsciTransport[F]] =
    Resource.eval(originator[F](cred))
      .map(new OsciBibBridgeImpl[F](_, transport, contentSignatures))

  /** Our own OSCI role: signer + decrypter from the tenant's PKCS12. Also
   *  used by the mailbox bridge, where the same role fetches and decrypts
   *  inbound deliveries.
   */
  def originator[F[_]: Sync](certSource: CertSource): F[Originator] =
    build[F](buildSignerDecrypter[F](certSource))

  def originator[F[_]: Sync](cred: CertCredential): F[Originator] =
    build[F](buildSignerDecrypter[F](cred))

  private def build[F[_]: Sync](sd: F[(Signer, Decrypter)]): F[Originator] = {
    import cats.syntax.all.*
    registerBouncyCastle[F] *> sd.map { case (signer, decrypter) =>
      new Originator(signer, decrypter)
    }
  }

  private def registerBouncyCastle[F[_]: Sync]: F[Unit] =
    Sync[F].blocking {
      if Security.getProvider("BC") == null then {
        val _ = Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
      }
    }

  private def buildSignerDecrypter[F[_]: Sync](certSource: CertSource): F[(Signer, Decrypter)] =
    certSource match {
      case CertSource.Pkcs12(path, pwd) =>
        Sync[F].blocking(fromPkcs12Bytes(Files.readAllBytes(path), pwd))
      case CertSource.Pkcs12Bytes(bytes, pwd) =>
        Sync[F].blocking(fromPkcs12Bytes(bytes, pwd))
      case _: CertSource.Pem | _: CertSource.PemBytes =>
        Sync[F].raiseError(OsciError.Config(
          "OSCI bridge requires a PKCS12 CertSource (PEM is not supported in v1)"
        ))
    }

  private def buildSignerDecrypter[F[_]: Sync](cred: CertCredential): F[(Signer, Decrypter)] =
    Sync[F].blocking(fromPkcs12Bytes(cred.pkcs12, cred.password))

  /** osci-bibliothek consumes the stream, so the signer and the decrypter each
   *  get their own over the same bytes.
   */
  private def fromPkcs12Bytes(bytes: Array[Byte], pwd: String): (Signer, Decrypter) = {
    val s = new PKCS12Signer(new ByteArrayInputStream(bytes), pwd)
    val d = new PKCS12Decrypter(new ByteArrayInputStream(bytes), pwd)
    (s, d)
  }
}

private[osci] final class OsciBibBridgeImpl[F[_]: Sync](
    originator: Originator,
    transport: TransportI,
    contentSignatures: ContentSignaturePolicy
) extends OsciTransport[F] {

  import OsciBibSupport.*

  def mediate(route: OsciRoute, subject: String, xml: String): F[OsciRawResult] =
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
        mediate.setSubject(subject)
        mediate.setQualityOfTimeStampCreation(false)
        mediate.setQualityOfTimeStampReception(false)

        mediate.addEncryptedData(signedEncryptedPayload(xml, originator, addressee))

        val rsp = mediate.send()
        checkFeedback(rsp.getFeedback, Option(msgIdResp.getMessageId))

        try new ExitDialog(dialog).send()
        catch case _: Throwable => () // best-effort cleanup

        // The synchronous answer comes back encrypted to our cipher cert
        // (the OSCI roles swap: our Originator becomes the response's
        // Addressee), so extractVerifiedXml decrypts with our own role and
        // then checks the author's content signature.
        val verified = extractVerifiedXml(
          rsp.getContentContainer,
          rsp.getEncryptedData,
          originator,
          contentSignatures,
          Option(msgIdResp.getMessageId)
        )

        OsciRawResult(
          responseXml = verified.map(_._1),
          messageId   = msgIdResp.getMessageId,
          status      = topFeedbackCode(rsp.getFeedback),
          warnings    = feedbackWarnings(rsp.getFeedback),
          signature   = verified.map(_._2)
        )
      }
      catch {
        case e: Exception => throw toOsciError(e)
      }
    }

  def store(route: OsciRoute, subject: String, xml: String): F[OsciReceipt] =
    Sync[F].blocking {
      try {
        val addressee = new Addressee(route.addresseeSig.orNull, route.addresseeCipher)
        val intermed  = new Intermed(null, route.intermedCipher, route.intermedUri)
        val dialog    = new DialogHandler(originator, intermed, transport)

        val msgIdResp = new GetMessageId(dialog).send()
        checkFeedback(msgIdResp.getFeedback)

        new InitDialog(dialog).send()

        val storeDelivery = new StoreDelivery(dialog, addressee, msgIdResp.getMessageId)
        storeDelivery.setSubject(subject)
        storeDelivery.setQualityOfTimeStampCreation(false)
        storeDelivery.setQualityOfTimeStampReception(false)

        storeDelivery.addEncryptedData(signedEncryptedPayload(xml, originator, addressee))

        val rsp = storeDelivery.send()
        checkFeedback(rsp.getFeedback, Option(msgIdResp.getMessageId))

        try new ExitDialog(dialog).send()
        catch case _: Throwable => () // best-effort cleanup

        OsciReceipt(
          messageId = msgIdResp.getMessageId,
          status    = topFeedbackCode(rsp.getFeedback),
          creation  = parseTimestamp(rsp.getTimestampCreation),
          warnings  = feedbackWarnings(rsp.getFeedback)
        )
      }
      catch {
        case e: Exception => throw toOsciError(e)
      }
    }
}
