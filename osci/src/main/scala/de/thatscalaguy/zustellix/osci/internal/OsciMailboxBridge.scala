package de.thatscalaguy.zustellix.osci.internal

import cats.effect.Sync
import de.thatscalaguy.zustellix.osci.{
  OsciError,
  OsciMailbox,
  OsciMailboxConfig,
  OsciMessage,
  PendingDelivery
}

import de.osci.osci12.common.DialogHandler
import de.osci.osci12.extinterfaces.TransportI
import de.osci.osci12.messagetypes.{
  ExitDialog,
  FetchDelivery,
  FetchProcessCard,
  InitDialog,
  OSCIMessage
}
import de.osci.osci12.roles.{Intermed, Originator}

/** osci-bibliothek-backed mailbox. The same Originator role that signs the
 *  fetch dialog carries our Decrypter for the inbound payloads (they are
 *  encrypted to our cipher cert).
 */
private[osci] final class OsciMailboxBridgeImpl[F[_]: Sync](
    originator: Originator,
    config:     OsciMailboxConfig,
    transport:  TransportI
) extends OsciMailbox[F] {

  import OsciBibSupport.*

  def pending: F[List[PendingDelivery]] =
    Sync[F].blocking {
      try withDialog { dialog =>
        val fpc = new FetchProcessCard(dialog)
        fpc.setRoleForSelection(OSCIMessage.SELECT_ADDRESSEE)
        fpc.setSelectNoReceptionOnly(true)
        fpc.setQuantityLimit(config.fetchLimit)

        val rsp = fpc.send()
        checkFeedback(rsp.getFeedback)

        Option(rsp.getProcessCardBundles).map(_.toList).getOrElse(Nil).map { pc =>
          PendingDelivery(
            messageId = pc.getMessageId,
            subject   = Option(pc.getSubject),
            creation  = parseTimestamp(pc.getCreation)
          )
        }
      }
      catch {
        case e: Exception => throw toOsciError(e)
      }
    }

  def fetch(messageId: String): F[OsciMessage] =
    Sync[F].blocking {
      try withDialog { dialog =>
        val fd = new FetchDelivery(dialog)
        fd.setSelectionMode(OSCIMessage.SELECT_BY_MESSAGE_ID)
        fd.setSelectionRule(messageId)

        val rsp = fd.send()
        checkFeedback(rsp.getFeedback)

        val (xml, signature) = extractVerifiedXml(
          rsp.getContentContainer,
          rsp.getEncryptedData,
          originator,
          config.contentSignatures,
          Some(messageId)
        ).getOrElse(throw OsciError.NoSuchMessage(messageId))

        OsciMessage(
          messageId = Option(rsp.getMessageId).getOrElse(messageId),
          subject   = Option(rsp.getSubject),
          xml       = xml,
          creation  = parseTimestamp(rsp.getTimestampCreation),
          reception = Option(rsp.getProcessCardBundle).flatMap(pc => parseTimestamp(pc.getReception)),
          signature = signature
        )
      }
      catch {
        case e: Exception => throw toOsciError(e)
      }
    }

  /** Fetch message types require an explicit dialog (the library enforces a
   *  ConversationID): InitDialog first, ExitDialog best-effort afterwards.
   */
  private def withDialog[A](f: DialogHandler => A): A = {
    val intermed = new Intermed(null, config.intermedCipherCert, config.intermedUri)
    val dialog   = new DialogHandler(originator, intermed, transport)
    new InitDialog(dialog).send()
    try f(dialog)
    finally {
      try new ExitDialog(dialog).send()
      catch case _: Throwable => () // best-effort cleanup
    }
  }
}
