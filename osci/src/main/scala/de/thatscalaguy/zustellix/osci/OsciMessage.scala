/*
 * Copyright 2026 ThatScalaGuy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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

/** The fetch that stopped an [[OsciMailbox.drain]]: the delivery `messageId`
 *  and the [[OsciError]] its fetch raised. Whether the intermediary already
 *  acknowledged that delivery depends on where the fetch failed: a failure
 *  after the reception entry was recorded (e.g. content decryption or
 *  signature verification) means the message will not be listed as pending
 *  again — surface or persist the failure instead of relying on a re-listing.
 */
final case class DrainFailure(messageId: String, error: OsciError)

/** Result of one [[OsciMailbox.drain]]: the pending `page` that was listed
 *  and the `messages` fetched from it, in listing (oldest-first) order.
 *
 *  Every returned message is acknowledged at the intermediary (the fetch is
 *  the ack — see [[OsciMailbox]]), which is why a mid-drain failure yields a
 *  partial result instead of raising: discarding the already-fetched
 *  messages would lose acknowledged deliveries. A `failure` stops the drain —
 *  deliveries never fetched stay pending for the next drain, while the
 *  failed one may already be acknowledged (see [[DrainFailure]]).
 */
final case class MailboxDrain(
    page:     PendingPage,
    messages: List[OsciMessage],
    failure:  Option[DrainFailure] = None
) {

  /** True when the drain fetched everything that was waiting: no fetch
   *  failed, the listing was not cut off at the limit, and every listed
   *  delivery was fetched.
   */
  def complete: Boolean =
    failure.isEmpty && !page.truncated && messages.size == page.deliveries.size
}
