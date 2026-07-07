package de.thatscalaguy.zustellix.osci.internal

import cats.effect.{Resource, Sync}
import de.thatscalaguy.zustellix.utils.cert.{CertCredential, CertSource}
import de.thatscalaguy.zustellix.osci.{OsciError, OsciReceipt}

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
import de.osci.osci12.samples.impl.HttpTransport
import de.osci.osci12.samples.impl.crypto.{PKCS12Decrypter, PKCS12Signer}

import java.io.{ByteArrayInputStream, FileInputStream, InputStream}
import java.nio.file.Path
import java.security.Security

private[osci] object OsciBibBridge {

  def resource[F[_]: Sync](certSource: CertSource): Resource[F, OsciTransport[F]] =
    Resource.eval(originator[F](certSource)).map(new OsciBibBridgeImpl[F](_))

  /** Alias-keyed path: the same PKCS12 the DVDV client uses, supplied by the
   *  shared [[de.thatscalaguy.zustellix.utils.cert.CertManager]] as bytes.
   */
  def resource[F[_]: Sync](cred: CertCredential): Resource[F, OsciTransport[F]] =
    Resource.eval(originator[F](cred)).map(new OsciBibBridgeImpl[F](_))

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
        Sync[F].blocking {
          val s = openAndUse(path)(in => new PKCS12Signer(in, pwd))
          val d = openAndUse(path)(in => new PKCS12Decrypter(in, pwd))
          (s, d)
        }
      case _: CertSource.Pem =>
        Sync[F].raiseError(OsciError.Config(
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

private[osci] final class OsciBibBridgeImpl[F[_]: Sync](
    originator: Originator,
    transport: TransportI = new HttpTransport()
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
        checkFeedback(rsp.getFeedback)

        try new ExitDialog(dialog).send()
        catch case _: Throwable => () // best-effort cleanup

        OsciRawResult(
          // The synchronous answer comes back encrypted to our cipher cert
          // (the OSCI roles swap: our Originator becomes the response's
          // Addressee), so extractXml decrypts with our own role.
          responseXml = extractXml(rsp.getContentContainer, rsp.getEncryptedData, originator)
            .getOrElse(""),
          messageId = msgIdResp.getMessageId,
          status    = topFeedbackCode(rsp.getFeedback)
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
        checkFeedback(rsp.getFeedback)

        try new ExitDialog(dialog).send()
        catch case _: Throwable => () // best-effort cleanup

        OsciReceipt(
          messageId = msgIdResp.getMessageId,
          status    = topFeedbackCode(rsp.getFeedback),
          creation  = parseTimestamp(rsp.getTimestampCreation)
        )
      }
      catch {
        case e: Exception => throw toOsciError(e)
      }
    }
}
