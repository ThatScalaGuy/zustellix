package de.thatscalaguy.zustellix.osci.internal

import cats.effect.{Ref, Resource, Sync}
import cats.syntax.all.*
import de.thatscalaguy.zustellix.utils.cert.{CertAlias, CertCredential, CertManager, CertSource}
import de.thatscalaguy.zustellix.osci.{ContentSignaturePolicy, OsciError, OsciReceipt}

import de.osci.osci12.common.DialogHandler
import de.osci.osci12.extinterfaces.TransportI
import de.osci.osci12.extinterfaces.crypto.{Decrypter, Signer}
import de.osci.osci12.messagetypes.{GetMessageId, MediateDelivery, ResponseToStoreDelivery, StoreDelivery}
import de.osci.osci12.roles.{Addressee, Intermed, Originator}
import de.osci.osci12.samples.impl.crypto.{PKCS12Decrypter, PKCS12Signer}

import java.io.{ByteArrayInputStream, IOException}
import java.security.{GeneralSecurityException, Security}

private[osci] object OsciBibBridge {

  /** Both `resource` overloads deliberately attach no finalizer:
   *  osci-bibliothek holds no long-lived connections — every operation opens
   *  its own dialog over `transport` — so there is nothing to release.
   */
  def resource[F[_]: Sync](
      certSource: CertSource,
      transport:  TransportI,
      contentSignatures: ContentSignaturePolicy,
      explicitDialog: Boolean = false
  ): Resource[F, OsciTransport[F]] =
    Resource.eval(originator[F](certSource))
      .map(o => new OsciBibBridgeImpl[F](o.pure[F], transport, contentSignatures, explicitDialog))

  /** Alias-keyed path: the same PKCS12 the DVDV client uses, supplied by the
   *  shared [[de.thatscalaguy.zustellix.utils.cert.CertManager]] as bytes
   *  (already in the PKCS12 shape — no conversion needed). The alias is
   *  resolved eagerly once at acquisition (unknown alias / unopenable
   *  keystore fail the Resource) and then via [[managedOriginator]] on every
   *  operation, so rotations in the manager reach OSCI without a rebuild.
   */
  def resource[F[_]: Sync](
      certs:     CertManager[F],
      alias:     CertAlias,
      transport: TransportI,
      contentSignatures: ContentSignaturePolicy,
      // No default here — Scala allows default arguments on only one
      // overloaded variant (the CertSource one carries it).
      explicitDialog: Boolean
  ): Resource[F, OsciTransport[F]] =
    Resource.eval(managedOriginator[F](certs, alias)).flatMap { resolve =>
      Resource.eval(resolve)
        .as(new OsciBibBridgeImpl[F](resolve, transport, contentSignatures, explicitDialog))
    }

  /** An effect that resolves the alias in the [[de.thatscalaguy.zustellix.utils.cert.CertManager]]
   *  on every evaluation and yields the tenant's Originator, rebuilding it
   *  only when the credential actually changed (`DirectoryCertManager` keeps
   *  the [[CertCredential]] instance stable while the underlying file is
   *  unchanged, so the common case is a cheap reference check). A failed
   *  rebuild surfaces as [[OsciError.Certificate]] (or
   *  `CertManagerError.UnknownCert` for a dropped alias) from that evaluation
   *  and keeps the previously cached Originator for when the credential
   *  heals.
   */
  def managedOriginator[F[_]: Sync](
      certs: CertManager[F],
      alias: CertAlias
  ): F[F[Originator]] =
    Ref.of[F, Option[(CertCredential, Originator)]](None).map { cache =>
      certs.resolve(alias).flatMap { cred =>
        cache.get.flatMap {
          case Some((cached, orig)) if sameCredential(cached, cred) => orig.pure[F]
          case _ => originator[F](cred).flatTap(o => cache.set(Some((cred, o))))
        }
      }
    }

  private def sameCredential(a: CertCredential, b: CertCredential): Boolean =
    a.password == b.password &&
      ((a.pkcs12 eq b.pkcs12) || java.util.Arrays.equals(a.pkcs12, b.pkcs12))

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

/** `resolveOriginator` is evaluated at the start of every operation, so a
 *  rotated credential signs/decrypts the next message; pass a pure value for
 *  a cert that is fixed for the lifetime of the bridge.
 *
 *  `explicitDialog` selects the wire profile (see [[de.thatscalaguy.zustellix.osci.OsciConfig]]):
 *  the default cheap paths skip the round trips the protocol does not
 *  require, `true` restores the previous `GetMessageId` + `InitDialog` +
 *  delivery + `ExitDialog` flow for both operations.
 */
private[osci] final class OsciBibBridgeImpl[F[_]: Sync](
    resolveOriginator: F[Originator],
    transport: TransportI,
    contentSignatures: ContentSignaturePolicy,
    explicitDialog: Boolean = false
) extends OsciTransport[F] {

  import OsciBibSupport.*

  def mediate(route: OsciRoute, subject: String, xml: String): F[OsciRawResult] =
    resolveOriginator.flatMap { originator =>
      Sync[F].blocking {
        try {
          val addressee = new Addressee(route.addresseeSig.orNull, route.addresseeCipher)
          val intermed  = new Intermed(null, route.intermedCipher, route.intermedUri)
          val dialog    = new DialogHandler(originator, intermed, transport)

          // osci-bibliothek 2.5.1 cannot send a MediateDelivery in an
          // implicit dialog: its compose() hard-requires ControlBlock
          // Response/ConversationID/SequenceNumber (only InitDialog
          // establishes them), and its constructor lacks the
          // !isExplicitDialog -> resetControlBlock() handling that
          // StoreDelivery/GetMessageId have. The MessageId element IS
          // optional there, so the cheap path keeps the dialog frame and
          // drops only the GetMessageId round trip.
          val msgId: Option[String] =
            if explicitDialog then {
              val r = new GetMessageId(dialog).send()
              checkFeedback(r.getFeedback)
              Some(r.getMessageId)
            }
            else None

          val rsp = withExplicitDialog(dialog, msgId) {
            val mediate = new MediateDelivery(dialog, addressee, route.addresseeUri.toString)
            msgId.foreach(mediate.setMessageId)
            // Without a message id the library omits the Subject and the
            // QoTS headers from the wire too (no process card is written).
            mediate.setSubject(subject)
            mediate.setQualityOfTimeStampCreation(false)
            mediate.setQualityOfTimeStampReception(false)

            mediate.addEncryptedData(signedEncryptedPayload(xml, originator, addressee))

            val r = mediate.send()
            checkFeedback(r.getFeedback, msgId)
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
            msgId
          )

          OsciRawResult(
            responseXml = verified.map(_._1),
            messageId   = msgId.orElse(Option(rsp.getMessageIdRequest)).getOrElse(""),
            status      = topFeedbackCode(rsp.getFeedback),
            warnings    = feedbackWarnings(rsp.getFeedback),
            signature   = verified.map(_._2)
          )
        }
        catch {
          case e: Exception => throw toOsciError(e)
        }
      }
    }

  def store(route: OsciRoute, subject: String, xml: String): F[OsciReceipt] =
    resolveOriginator.flatMap { originator =>
      Sync[F].blocking {
        try {
          val addressee = new Addressee(route.addresseeSig.orNull, route.addresseeCipher)
          val intermed  = new Intermed(null, route.intermedCipher, route.intermedUri)
          val dialog    = new DialogHandler(originator, intermed, transport)

          val msgIdResp = new GetMessageId(dialog).send()
          checkFeedback(msgIdResp.getFeedback)
          val msgId = msgIdResp.getMessageId

          val rsp =
            if explicitDialog then
              withExplicitDialog(dialog, Some(msgId)) {
                sendStore(new StoreDelivery(dialog, addressee, msgId), subject, xml, originator, addressee, msgId)
              }
            else {
              // Implicit-dialog StoreDelivery (its constructor resets the
              // control block for non-explicit dialogs). A FRESH
              // DialogHandler keeps prevChallenge null, so the request
              // carries no stale Response from the closed GetMessageId
              // exchange. No InitDialog was sent, so there is no dialog to
              // exit.
              val storeDialog = new DialogHandler(originator, intermed, transport)
              sendStore(new StoreDelivery(storeDialog, addressee, msgId), subject, xml, originator, addressee, msgId)
            }

          OsciReceipt(
            messageId = msgId,
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

  private def sendStore(
      storeDelivery: StoreDelivery,
      subject:       String,
      xml:           String,
      originator:    Originator,
      addressee:     Addressee,
      msgId:         String
  ): ResponseToStoreDelivery = {
    storeDelivery.setSubject(subject)
    storeDelivery.setQualityOfTimeStampCreation(false)
    storeDelivery.setQualityOfTimeStampReception(false)

    storeDelivery.addEncryptedData(signedEncryptedPayload(xml, originator, addressee))

    val r = storeDelivery.send()
    checkFeedback(r.getFeedback, Some(msgId))
    r
  }
}
