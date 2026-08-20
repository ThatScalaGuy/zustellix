package de.thatscalaguy.zustellix.osci

import java.time.Instant

/** Receipt for an asynchronous `send` (StoreDelivery): the message was stored
 *  in the recipient's mailbox at their intermediary; delivery to the
 *  recipient happens when they fetch it.
 *
 *  @param messageId the OSCI message id issued by the intermediary — the
 *                   handle for any later process-card inquiry
 *  @param status    top OSCI feedback code (e.g. "0800")
 *  @param creation  intermediary's creation timestamp from the process card
 *  @param warnings  warning-class (`3xxx`) feedback entries — the request was
 *                   executed, but the intermediary flagged something (e.g.
 *                   a certificate validity warning)
 */
final case class OsciReceipt(
    messageId: String,
    status:    String,
    creation:  Option[Instant],
    warnings:  List[OsciFeedback] = Nil
)
