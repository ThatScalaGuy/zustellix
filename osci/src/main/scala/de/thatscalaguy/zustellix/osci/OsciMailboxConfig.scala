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
import java.security.cert.X509Certificate
import scala.concurrent.duration.FiniteDuration

/** Static configuration of our own mailbox at an OSCI intermediary (the
 *  asynchronous, passive-recipient leg — e.g. XFamilie). Unlike outbound
 *  routes, the own mailbox is not resolved from DVDV.
 *
 *  @param intermedUri        OSCI endpoint of the intermediary hosting the mailbox
 *  @param intermedCipherCert the intermediary's cipher certificate (encrypts
 *                            the envelope towards the intermediary)
 *  @param fetchLimit         maximum number of process cards one `pending`
 *                            call lists (`FetchProcessCard.setQuantityLimit`);
 *                            must be > 0 — a non-positive value raises
 *                            [[OsciError.Config]] at construction
 *  @param connectTimeout     HTTP connect timeout of the default
 *                            [[OsciHttpTransport]]; ignored when a custom
 *                            transport is passed to `OsciMailbox.resource`
 *  @param readTimeout        HTTP read timeout of the default
 *                            [[OsciHttpTransport]]; ignored when a custom
 *                            transport is passed to `OsciMailbox.resource`
 *  @param contentSignatures  how strictly the author's content signature on
 *                            fetched deliveries is enforced (see
 *                            [[ContentSignaturePolicy]])
 */
final case class OsciMailboxConfig(
    intermedUri:        URI,
    intermedCipherCert: X509Certificate,
    fetchLimit:         Long = 100,
    connectTimeout:     FiniteDuration = OsciHttpTransport.DefaultConnectTimeout,
    readTimeout:        FiniteDuration = OsciHttpTransport.DefaultReadTimeout,
    contentSignatures:  ContentSignaturePolicy = ContentSignaturePolicy.Warn
) {
  if fetchLimit <= 0 then
    throw OsciError.Config(s"OsciMailboxConfig.fetchLimit must be > 0, got $fetchLimit")
}
