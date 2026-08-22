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

/** How strictly the author's content signature (Inhaltsdatensignatur) on
 *  received messages is enforced. The dialog-level checks of osci-bibliothek
 *  only cover the intermediary envelope signature — the content signature
 *  inside the (decrypted) `ContentContainer` is what proves the author, so it
 *  is verified separately after decryption.
 *
 *  An *invalid* signature always raises
 *  [[OsciError.InvalidContentSignature]] — the policy only decides what
 *  happens when content carries no signature at all. Some gateways answer
 *  unsigned (the condition OSCI feedback code `3802` warns about), which is
 *  why [[ContentSignaturePolicy.Warn]] is the default.
 */
enum ContentSignaturePolicy {

  /** Unsigned content raises [[OsciError.UnsignedContent]]. */
  case Require

  /** Unsigned content is accepted and surfaced as
   *  [[ContentSignatureStatus.Unsigned]] (on [[OsciMessage.signature]] and
   *  [[Laufzettel.contentSignature]]).
   */
  case Warn
}

/** Outcome of verifying the content signature of received content. An
 *  invalid signature never appears here — it raises
 *  [[OsciError.InvalidContentSignature]] regardless of policy.
 */
enum ContentSignatureStatus {

  /** The content was signed and every signature verified. */
  case Valid

  /** The content carried no signature (tolerated under
   *  [[ContentSignaturePolicy.Warn]]).
   */
  case Unsigned
}
