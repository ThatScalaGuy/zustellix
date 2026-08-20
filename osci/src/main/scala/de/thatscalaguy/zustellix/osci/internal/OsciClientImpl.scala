package de.thatscalaguy.zustellix.osci.internal

import cats.effect.{Clock, Sync}
import cats.syntax.all.*
import de.thatscalaguy.zustellix.osci.{Laufzettel, LaufzettelSink, OsciClient, OsciReceipt, TenantId}

private[osci] final class OsciClientImpl[F[_]: Sync: Clock](
    tenantId:  TenantId,
    subject:   String,
    transport: OsciTransport[F],
    resolver:  AgsResolver[F],
    sink:      LaufzettelSink[F]
) extends OsciClient[F] {

  def request(ags: String, xml: String): F[String] =
    for {
      route  <- resolver.resolve(ags)
      result <- transport.mediate(route, subject, xml)
      now    <- Clock[F].realTimeInstant
      lz      = Laufzettel(
                  messageId    = result.messageId,
                  timestamp    = now,
                  recipientAgs = ags,
                  recipientUri = route.addresseeUri,
                  status       = result.status,
                  rawXml       = result.responseXml,
                  warnings     = result.warnings
                )
      _      <- sink.record(tenantId, lz).attempt.void
    }
    yield result.responseXml

  def send(ags: String, xml: String): F[OsciReceipt] =
    for {
      route   <- resolver.resolve(ags)
      receipt <- transport.store(route, subject, xml)
      now     <- Clock[F].realTimeInstant
      lz       = Laufzettel(
                   messageId    = receipt.messageId,
                   timestamp    = now,
                   recipientAgs = ags,
                   recipientUri = route.addresseeUri,
                   status       = receipt.status,
                   rawXml       = "", // async: no response payload at store time
                   warnings     = receipt.warnings
                 )
      _       <- sink.record(tenantId, lz).attempt.void
    }
    yield receipt
}
