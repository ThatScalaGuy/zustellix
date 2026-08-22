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

/** One OSCI feedback entry (Rückmeldung) of the warning class.
 *
 *  OSCI-Transport 1.2 classifies feedback codes by their first digit:
 *  `0xxx` = success, `3xxx` = warning (the request WAS executed), `9xxx` =
 *  error (the request was not executed). Warnings — e.g. `3802` "Signatur
 *  des Empfängers über die Annahme- bzw. Bearbeitungsantwort fehlt" — do not
 *  fail the call; they are surfaced here so callers can log or act on them.
 *
 *  @param code four-digit OSCI feedback code (e.g. "3802")
 *  @param text human-readable text as sent by the intermediary
 */
final case class OsciFeedback(code: String, text: String)
