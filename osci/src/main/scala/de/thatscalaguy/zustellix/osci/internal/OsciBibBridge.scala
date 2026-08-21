package de.thatscalaguy.zustellix.osci.internal

import cats.effect.{Resource, Sync}
import cats.syntax.all.*
import de.thatscalaguy.zustellix.utils.cert.{CertCredential, CertSource}
import de.thatscalaguy.zustellix.osci.{ContentSignaturePolicy, OsciError, OsciReceipt}

import de.osci.osci12.common.DialogHandler
import de.osci.osci12.extinterfaces.TransportI
import de.osci.osci12.extinterfaces.crypto.{Decrypter, Signer}
import de.osci.osci12.messagetypes.{GetMessageId, MediateDelivery, StoreDelivery}
import de.osci.osci12.roles.{Addressee, Intermed, Originator}
import de.osci.osci12.samples.impl.crypto.{PKCS12Decrypter, PKCS12Signer}

import java.io.{ByteArrayInputStream, IOException}
import java.security.{GeneralSecurityException, Security}

private[osci] object OsciBibBridge {

  def resource[F[_]: Sync](
      certSource: CertSource,
      transport:  TransportI,
      contentSignatures: ContentSignaturePolicy
  ): Resource[F, OsciTransport[F]] =
    Resource.eval(originator[F](certSource))
      .map(new OsciBibBridgeImpl[F](_, transport, contentSignatures))

  /** Alias-keyed path: the same PKCS12 the DVDV client uses, supplied by the
   *  shared [[de.thatscalaguy.zustellix.utils.cert.CertManager]] as bytes
   *  (already in the PKCS12 shape — no conversion needed).
   */
  def resource[F[_]: Sync](
      cred:      CertCredential,
      transport: TransportI,
      contentSignatures: ContentSignaturePolicy
  ): Resource[F, OsciTransport[F]] =
    Resource.eval(originator[F](cred))
      .map(new OsciBibBridgeImpl[F](_, transport, contentSignatures))

  /** Our own OSCI role: signer + decrypter from the tenant's PKCS12.
   *  osci-bibliothek's `PKCS12Signer`/`PKCS12Decrypter` consume PKCS12
   *  streams only, so PEM sources are converted to an in-memory PKCS12
   *  first. Also used by the mailbox bridge, where the same role fetches
   *  and decrypts inbound deliveries. Keystore failures (wrong password,
   *  unreadable file, keystore errors) are raised as [[OsciError.Certificate]].
   */
  def originator[F[_]: Sync](certSource: CertSource): F[Originator] =
    build[F](buildSignerDecrypter[F](certSource))

  def originator[F[_]: Sync](cred: CertCredential): F[Originator] =
    build[F](buildSignerDecrypter[F](cred))

  private def build[F[_]: Sync](sd: F[(Signer, Decrypter)]): F[Originator] =
    registerBouncyCastle[F] *> sd.map { case (signer, decrypter) =>
      new Originator(signer, decrypter)
    }

  private def registerBouncyCastle[F[_]: Sync]: F[Unit] =
    Sync[F].blocking {
      if Security.getProvider("BC") == null then {
        val _ = Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
      }
    }

  private def buildSignerDecrypter[F[_]: Sync](certSource: CertSource): F[(Signer, Decrypter)] =
    CertCredential
      .fromSource[F](certSource)
      .flatMap { cred =>
        Sync[F].blocking(fromPkcs12Bytes(cred.pkcs12, cred.password))
      }
      .adaptError {
        case e: GeneralSecurityException => OsciError.Certificate(e)
        case e: IOException              => OsciError.Certificate(e)
      }

  private def buildSignerDecrypter[F[_]: Sync](cred: CertCredential): F[(Signer, Decrypter)] =
    Sync[F].blocking(fromPkcs12Bytes(cred.pkcs12, cred.password)).adaptError {
      case e: GeneralSecurityException => OsciError.Certificate(e)
      case e: IOException              => OsciError.Certificate(e)
    }

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

        val rsp = withExplicitDialog(dialog) {
          val mediate = new MediateDelivery(dialog, addressee, route.addresseeUri.toString)
          mediate.setMessageId(msgIdResp.getMessageId)
          mediate.setSubject(subject)
          mediate.setQualityOfTimeStampCreation(false)
          mediate.setQualityOfTimeStampReception(false)

          mediate.addEncryptedData(signedEncryptedPayload(xml, originator, addressee))

          val r = mediate.send()
          checkFeedback(r.getFeedback, Option(msgIdResp.getMessageId))
          r
        }

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

        val rsp = withExplicitDialog(dialog) {
          val storeDelivery = new StoreDelivery(dialog, addressee, msgIdResp.getMessageId)
          storeDelivery.setSubject(subject)
          storeDelivery.setQualityOfTimeStampCreation(false)
          storeDelivery.setQualityOfTimeStampReception(false)

          storeDelivery.addEncryptedData(signedEncryptedPayload(xml, originator, addressee))

          val r = storeDelivery.send()
          checkFeedback(r.getFeedback, Option(msgIdResp.getMessageId))
          r
        }

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
