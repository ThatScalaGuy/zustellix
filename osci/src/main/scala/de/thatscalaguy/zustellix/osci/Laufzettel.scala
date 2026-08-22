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

import java.net.URI
import java.time.Instant

/** `rawXml` is the decrypted response payload of a synchronous `request` —
 *  captured only when `OsciConfig.capturePayloads` is enabled, `None`
 *  otherwise (and always for async `send` and failures, which have no
 *  response payload). The payload can contain personal data, which is why
 *  capture is opt-in — see the README's Laufzettel section.
 *
 *  `contentSignature` is the verified status of the author's content
 *  signature over the response content — `None` when there was no response
 *  content to check (async `send`, failures). It is filled independently of
 *  `rawXml`: the signature is verified even when the payload is not captured.
 */
final case class Laufzettel(
    messageId:    String,
    timestamp:    Instant,
    recipientAgs: Ags,
    recipientUri: URI,
    status:       LaufzettelStatus,
    rawXml:       Option[String] = None,
    warnings:     List[OsciFeedback] = Nil,
    contentSignature: Option[ContentSignatureStatus] = None
)
