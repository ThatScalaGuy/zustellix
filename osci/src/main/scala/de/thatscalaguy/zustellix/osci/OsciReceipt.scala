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
