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

package de.thatscalaguy.zustellix.osci.internal

import cats.effect.{Clock, Sync}
import cats.syntax.all.*
import de.thatscalaguy.zustellix.osci.{
  Ags,
  Laufzettel,
  LaufzettelSink,
  LaufzettelStatus,
  OsciClient,
  OsciError,
  OsciReceipt,
  OsciResponse,
  TenantId
}
import org.typelevel.log4cats.LoggerFactory

import java.net.URI

private[osci] final class OsciClientImpl[F[_]: Sync: Clock: LoggerFactory](
    tenantId:  TenantId,
    subject:   String,
    transport: OsciTransport[F],
    resolver:  AgsResolver[F],
    sink:      LaufzettelSink[F],
    capturePayloads: Boolean = false
) extends OsciClient[F] {

  private val log = LoggerFactory[F].getLogger

  def request(ags: Ags, xml: String): F[OsciResponse] =
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
                  status       = LaufzettelStatus.Feedback(result.status),
                  rawXml       = if capturePayloads then result.responseXml else None,
                  warnings     = result.warnings,
                  contentSignature = result.signature
                )
      _      <- recordBestEffort(lz)
    }
    yield OsciResponse(
      xml       = result.responseXml,
      messageId = result.messageId,
      status    = result.status,
      warnings  = result.warnings
    )

  def send(ags: Ags, xml: String): F[OsciReceipt] =
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
                   status       = LaufzettelStatus.Feedback(receipt.status),
                   rawXml       = None, // async: no response payload at store time
                   warnings     = receipt.warnings
                 )
      _       <- recordBestEffort(lz)
    }
    yield receipt

  /** Best-effort record: a sink failure is logged at warn and swallowed so it
   *  never fails the operation nor masks an in-flight delivery error.
   */
  private def recordBestEffort(lz: Laufzettel): F[Unit] =
    sink.record(tenantId, lz)
      .onError { case e =>
        log.warn(e)(
          "LaufzettelSink.record failed — Laufzettel dropped " +
            s"(tenant=${tenantId.value} ags=${lz.recipientAgs.value} " +
            s"messageId=${lz.messageId} status=${lz.status.render})"
        )
      }
      .attempt
      .void

  /** Failed deliveries leave a Laufzettel too, so the audit trail is not
   *  success-only: `status` is `Feedback` with the OSCI code for a `9xxx`
   *  response and `Failed` with the error kind otherwise, `messageId` the id
   *  from `GetMessageId` when one was issued before the failure ("" otherwise),
   *  `rawXml` stays `None`. `recipientUri` is the resolved addressee URI, or
   *  empty when the resolver itself failed. Recording is best-effort — the
   *  original error is re-raised untouched, and a sink failure is logged at
   *  warn and then swallowed like on the success path.
   */
  private def recordFailure(ags: Ags, uri: Option[URI], e: Throwable): F[Unit] =
    Clock[F].realTimeInstant.flatMap { now =>
      val lz = Laufzettel(
        messageId    = failureMessageId(e),
        timestamp    = now,
        recipientAgs = ags,
        recipientUri = uri.getOrElse(URI.create("")),
        status       = failureStatus(e),
        rawXml       = None
      )
      recordBestEffort(lz)
    }

  private def failureStatus(e: Throwable): LaufzettelStatus =
    e match {
      case OsciError.OsciResponse(code, _, _) => LaufzettelStatus.Feedback(code)
      case other                              => LaufzettelStatus.Failed(other.getClass.getSimpleName)
    }

  private def failureMessageId(e: Throwable): String =
    e match {
      case OsciError.OsciResponse(_, _, Some(id))         => id
      case OsciError.UnsignedContent(Some(id))            => id
      case OsciError.InvalidContentSignature(Some(id), _) => id
      case _                                              => ""
    }
}
