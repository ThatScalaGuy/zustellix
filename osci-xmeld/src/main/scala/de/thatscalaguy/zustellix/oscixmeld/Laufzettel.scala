package de.thatscalaguy.zustellix.oscixmeld

import java.net.URI
import java.time.Instant

final case class Laufzettel(
    messageId:    String,
    timestamp:    Instant,
    recipientAgs: String,
    recipientUri: URI,
    status:       String,
    rawXml:       String
)
