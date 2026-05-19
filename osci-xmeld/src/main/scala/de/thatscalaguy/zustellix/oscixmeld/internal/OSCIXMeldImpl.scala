package de.thatscalaguy.zustellix.oscixmeld.internal

import cats.effect.{Clock, Sync}
import cats.syntax.all.*
import de.thatscalaguy.zustellix.oscixmeld.{Laufzettel, LaufzettelSink, OSCIXMeld, TenantId}

private[oscixmeld] final class OSCIXMeldImpl[F[_]: Sync: Clock](
    tenantId:  TenantId,
    transport: OsciTransport[F],
    resolver:  AgsResolver[F],
    sink:      LaufzettelSink[F]
) extends OSCIXMeld[F] {

  def send(ags: String, xml: String): F[String] =
    for {
      route  <- resolver.resolve(ags)
      result <- transport.transmit(route, xml)
      now    <- Clock[F].realTimeInstant
      lz      = Laufzettel(
                  messageId    = result.messageId,
                  timestamp    = now,
                  recipientAgs = ags,
                  recipientUri = route.addresseeUri,
                  status       = result.status,
                  rawXml       = result.responseXml
                )
      _      <- sink.record(tenantId, lz).attempt.void
    }
    yield result.responseXml
}
