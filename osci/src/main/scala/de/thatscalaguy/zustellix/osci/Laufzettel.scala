package de.thatscalaguy.zustellix.osci

import java.net.URI
import java.time.Instant

/** `contentSignature` is the verified status of the author's content
 *  signature over `rawXml` — `None` when there was no response content to
 *  check (async `send`, failures).
 */
final case class Laufzettel(
    messageId:    String,
    timestamp:    Instant,
    recipientAgs: String,
    recipientUri: URI,
    status:       String,
    rawXml:       String,
    warnings:     List[OsciFeedback] = Nil,
    contentSignature: Option[ContentSignatureStatus] = None
)
