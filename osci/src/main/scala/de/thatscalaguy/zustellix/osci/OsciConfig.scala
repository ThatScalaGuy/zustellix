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

import de.thatscalaguy.zustellix.utils.cert.CertSource

import scala.concurrent.duration.FiniteDuration

/** Outbound OSCI client configuration.
 *
 *  `serviceUri` selects the DVDV service description the recipient routes are
 *  resolved from; `subject` is the OSCI message subject. The defaults match
 *  the XMeld Personensuche profile — XFamilie (or other) profiles override
 *  both.
 *
 *  `connectTimeout` / `readTimeout` bound the default [[OsciHttpTransport]]'s
 *  `HttpURLConnection`s; they are ignored when a custom transport is passed
 *  to `OsciClient.resource`.
 *
 *  `contentSignatures` sets how strictly the author's content signature on
 *  received response content (synchronous `request` answers) is enforced —
 *  see [[ContentSignaturePolicy]].
 *
 *  `capturePayloads` opts in to storing the decrypted response XML of a
 *  `request` on [[Laufzettel.rawXml]]. Off by default: XMeld responses
 *  contain personal data, and every `LaufzettelSink` (DB, queue, log
 *  shipper) would persist it. Enable only when the sink is meant to hold
 *  the payload and handles it accordingly.
 *
 *  `explicitDialog` selects the wire profile per operation. Default
 *  (`false`): `request` is `InitDialog` + `MediateDelivery` + `ExitDialog`
 *  (3 round trips) — no `GetMessageId`, so [[OsciResponse.messageId]] /
 *  [[Laufzettel.messageId]] are empty unless the intermediary volunteers an
 *  id on the response process card, and the intermediary writes no request
 *  process card (the `subject` is not carried on the wire either — OSCI ties
 *  both to the message id); `send` is `GetMessageId` + `StoreDelivery`, both
 *  in implicit dialogs (2 round trips). `true` restores the previous
 *  `GetMessageId` + `InitDialog` + delivery + `ExitDialog` flow (4 round
 *  trips) with an intermediary-issued message id also for `request`.
 */
final case class OsciConfig(
    tenantId: TenantId,
    /** The Originator's signing + decryption cert. Leave empty when building
     *  the client from a [[de.thatscalaguy.zustellix.utils.cert.CertManager]]
     *  + alias, which supplies the cert (and the tenant id) itself.
     */
    certSource: Option[CertSource] = None,
    serviceUri: String = OsciConfig.DefaultXMeldServiceUri,
    subject: String = OsciConfig.DefaultSubject,
    connectTimeout: FiniteDuration = OsciHttpTransport.DefaultConnectTimeout,
    readTimeout: FiniteDuration = OsciHttpTransport.DefaultReadTimeout,
    contentSignatures: ContentSignaturePolicy = ContentSignaturePolicy.Warn,
    capturePayloads: Boolean = false,
    explicitDialog: Boolean = false
)

object OsciConfig {
  val DefaultXMeldServiceUri: String =
    "http://www.osci.de/xmeld2605/xmeld2605Personensuche.wsdl"

  val DefaultSubject: String = "XMeld"
}
