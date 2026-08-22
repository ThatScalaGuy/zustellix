package de.thatscalaguy.zustellix.osci

/** Result of a synchronous `request` (MediateDelivery): the recipient's
 *  answer plus the transmission metadata the intermediary reported.
 *
 *  @param xml       the recipient's response payload, decrypted and (per the
 *                   configured [[ContentSignaturePolicy]]) signature-checked —
 *                   `None` when the response carried no extractable content
 *  @param messageId the OSCI message id — the handle for any later
 *                   process-card inquiry. Empty under the default wire
 *                   profile, which skips the `GetMessageId` round trip; set
 *                   [[OsciConfig.explicitDialog]] for an intermediary-issued
 *                   id
 *  @param status    top OSCI feedback code (e.g. "0800")
 *  @param warnings  warning-class (`3xxx`) feedback entries — the request was
 *                   executed, but the intermediary flagged something (e.g.
 *                   `3802` "Signatur des Empfängers über die Annahme- bzw.
 *                   Bearbeitungsantwort fehlt")
 */
final case class OsciResponse(
    xml:       Option[String],
    messageId: String,
    status:    String,
    warnings:  List[OsciFeedback] = Nil
)
