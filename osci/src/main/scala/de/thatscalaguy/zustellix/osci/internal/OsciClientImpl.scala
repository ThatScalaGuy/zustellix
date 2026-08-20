package de.thatscalaguy.zustellix.osci.internal

import cats.effect.{Clock, Sync}
import cats.syntax.all.*
import de.thatscalaguy.zustellix.osci.{
  Laufzettel,
  LaufzettelSink,
  OsciClient,
  OsciError,
  OsciReceipt,
  TenantId
}

import java.net.URI

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
                  .onError { case e => recordFailure(ags, None, e) }
      result <- transport.mediate(route, subject, xml)
                  .onError { case e => recordFailure(ags, Some(route.addresseeUri), e) }
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
                   .onError { case e => recordFailure(ags, None, e) }
      receipt <- transport.store(route, subject, xml)
                   .onError { case e => recordFailure(ags, Some(route.addresseeUri), e) }
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

  /** Failed deliveries leave a Laufzettel too, so the audit trail is not
   *  success-only: `status` carries the OSCI feedback code for a `9xxx`
   *  response and the error kind otherwise, `messageId` the id from
   *  `GetMessageId` when one was issued before the failure ("" otherwise),
   *  `rawXml` stays empty. `recipientUri` is the resolved addressee URI, or
   *  empty when the resolver itself failed. Recording is best-effort — the
   *  original error is re-raised untouched, and a sink failure is swallowed
   *  like on the success path.
   */
  private def recordFailure(ags: String, uri: Option[URI], e: Throwable): F[Unit] =
    Clock[F].realTimeInstant.flatMap { now =>
      val lz = Laufzettel(
        messageId    = failureMessageId(e),
        timestamp    = now,
        recipientAgs = ags,
        recipientUri = uri.getOrElse(URI.create("")),
        status       = failureStatus(e),
        rawXml       = ""
      )
      sink.record(tenantId, lz).attempt.void
    }

  private def failureStatus(e: Throwable): String =
    e match {
      case OsciError.OsciResponse(code, _, _) => code
      case other                              => other.getClass.getSimpleName
    }

  private def failureMessageId(e: Throwable): String =
    e match {
      case OsciError.OsciResponse(_, _, Some(id)) => id
      case _                                      => ""
    }
}
