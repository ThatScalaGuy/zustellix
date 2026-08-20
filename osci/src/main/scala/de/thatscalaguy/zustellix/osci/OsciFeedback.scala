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
