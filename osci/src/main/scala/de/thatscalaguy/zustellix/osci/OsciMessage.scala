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

/** One [[OsciMailbox.pending]] listing: the un-fetched deliveries plus the
 *  warning-class (`3xxx`) feedback of the response. The warning that matters
 *  here is `3800` / `3801` ("more available than the fetch limit"): it is the
 *  only reliable signal that the listing was cut off at `fetchLimit` —
 *  `deliveries.size == fetchLimit` alone is ambiguous. [[truncated]] derives
 *  it; when it is `true`, fetch (and thereby acknowledge) the listed
 *  deliveries and call `pending` again for the rest.
 */
final case class PendingPage(
    deliveries: List[PendingDelivery],
    warnings:   List[OsciFeedback] = Nil
) {

  /** True when the intermediary flagged the listing as incomplete (feedback
   *  code `3800` or `3801`) — more deliveries are waiting than `fetchLimit`
   *  allowed to list.
   */
  def truncated: Boolean = warnings.exists(w => PendingPage.TruncationCodes(w.code))
}

object PendingPage {

  /** OSCI 1.2 feedback codes reporting a selection cut off at the requested
   *  quantity limit.
   */
  val TruncationCodes: Set[String] = Set("3800", "3801")
}

/** A fetched delivery. `reception` is the intermediary's reception entry on
 *  the process card — the acknowledgement mark (see [[OsciMailbox]]).
 *  `signature` is the verified status of the author's content signature over
 *  `xml`; it is [[ContentSignatureStatus.Unsigned]] only under
 *  [[ContentSignaturePolicy.Warn]] — an invalid signature never yields a
 *  message but raises [[OsciError.InvalidContentSignature]]. `warnings` are
 *  the warning-class (`3xxx`) feedback entries of the fetch response — the
 *  delivery was returned, but the intermediary flagged something.
 */
final case class OsciMessage(
    messageId: String,
    subject:   Option[String],
    xml:       String,
    creation:  Option[Instant],
    reception: Option[Instant],
    signature: ContentSignatureStatus,
    warnings:  List[OsciFeedback] = Nil
)
