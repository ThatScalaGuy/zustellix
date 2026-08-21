package de.thatscalaguy.zustellix.osci.internal

import cats.effect.Sync
import cats.syntax.all.*
import de.thatscalaguy.zustellix.osci.{
  OsciError,
  OsciMailbox,
  OsciMailboxConfig,
  OsciMessage,
  PendingDelivery,
  PendingPage
}

import de.osci.osci12.common.DialogHandler
import de.osci.osci12.extinterfaces.TransportI
import de.osci.osci12.messagetypes.{FetchDelivery, FetchProcessCard, OSCIMessage}
import de.osci.osci12.roles.{Intermed, Originator}

/** osci-bibliothek-backed mailbox. The same Originator role that signs the
 *  fetch dialog carries our Decrypter for the inbound payloads (they are
 *  encrypted to our cipher cert). `resolveOriginator` is evaluated at the
 *  start of every operation, so a rotated credential signs and decrypts the
 *  next fetch; pass a pure value for a cert that is fixed for the lifetime
 *  of the mailbox.
 */
private[osci] final class OsciMailboxBridgeImpl[F[_]: Sync](
    resolveOriginator: F[Originator],
    config:            OsciMailboxConfig,
    transport:         TransportI
) extends OsciMailbox[F] {

  import OsciBibSupport.*

  def pending: F[PendingPage] =
    resolveOriginator.flatMap { originator =>
      Sync[F].blocking {
        try withDialog(originator) { dialog =>
          val fpc = new FetchProcessCard(dialog)
          fpc.setRoleForSelection(OSCIMessage.SELECT_ADDRESSEE)
          fpc.setSelectNoReceptionOnly(true)
          fpc.setQuantityLimit(config.fetchLimit)

          val rsp = fpc.send()
          checkFeedback(rsp.getFeedback)

          val deliveries =
            Option(rsp.getProcessCardBundles).map(_.toList).getOrElse(Nil).map { pc =>
              PendingDelivery(
                messageId = pc.getMessageId,
                subject   = Option(pc.getSubject),
                creation  = parseTimestamp(pc.getCreation)
              )
            }
          PendingPage(deliveries, feedbackWarnings(rsp.getFeedback))
        }
        catch {
          case e: Exception => throw toOsciError(e)
        }
      }
    }

  def fetch(messageId: String): F[OsciMessage] =
    resolveOriginator.flatMap { originator =>
      Sync[F].blocking {
        try withDialog(originator) { dialog =>
          val fd = new FetchDelivery(dialog)
          fd.setSelectionMode(OSCIMessage.SELECT_BY_MESSAGE_ID)
          fd.setSelectionRule(messageId)

          val rsp = fd.send()
          checkFeedback(rsp.getFeedback)
          val confirmedId = confirmMessageId(messageId, rsp.getMessageId)

          val (xml, signature) = extractVerifiedXml(
            rsp.getContentContainer,
            rsp.getEncryptedData,
            originator,
            config.contentSignatures,
            Some(messageId)
          ).getOrElse(throw OsciError.NoSuchMessage(messageId))

          OsciMessage(
            messageId = confirmedId,
            subject   = Option(rsp.getSubject),
            xml       = xml,
            creation  = parseTimestamp(rsp.getTimestampCreation),
            reception = Option(rsp.getProcessCardBundle).flatMap(pc => parseTimestamp(pc.getReception)),
            signature = signature,
            warnings  = feedbackWarnings(rsp.getFeedback)
          )
        }
        catch {
          case e: Exception => throw toOsciError(e)
        }
      }
    }

  /** Fetch message types require an explicit dialog (the library enforces a
   *  ConversationID): InitDialog first, ExitDialog best-effort afterwards —
   *  see [[OsciBibSupport.withExplicitDialog]]. The InitDialog response's
   *  feedback is checked there: a `9xxx` dialog refusal raises
   *  [[OsciError.OsciResponse]] before FetchProcessCard/FetchDelivery run.
   */
  private def withDialog[A](originator: Originator)(f: DialogHandler => A): A = {
    val intermed = new Intermed(null, config.intermedCipherCert, config.intermedUri)
    val dialog   = new DialogHandler(originator, intermed, transport)
    withExplicitDialog(dialog)(f(dialog))
  }
}
