package de.thatscalaguy.zustellix.osci

import java.time.Instant

/** A delivery waiting in the mailbox, listed from its process card — content
 *  is fetched separately via [[OsciMailbox.fetch]].
 */
final case class PendingDelivery(
    messageId: String,
    subject:   Option[String],
    creation:  Option[Instant]
)

/** A fetched delivery. `reception` is the intermediary's reception entry on
 *  the process card — the acknowledgement mark (see [[OsciMailbox]]).
 */
final case class OsciMessage(
    messageId: String,
    subject:   Option[String],
    xml:       String,
    creation:  Option[Instant],
    reception: Option[Instant]
)
