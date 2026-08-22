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

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import de.thatscalaguy.zustellix.dvdv.DvdvClient
import de.thatscalaguy.zustellix.utils.cert.{CertManager, CertAlias}
import org.typelevel.log4cats.LoggerFactory

import de.osci.osci12.extinterfaces.TransportI

trait OsciClient[F[_]] {

  /** Synchronous request/response (`MediateDelivery`): the recipient answers
   *  within the call, e.g. the XMeld Personensuche. The returned
   *  [[OsciResponse]] carries the response payload (`None` when the answer
   *  had no extractable content) together with the `messageId` (empty by
   *  default — set [[OsciConfig.explicitDialog]] for an intermediary-issued
   *  id), `status` and `3xxx` warnings.
   */
  def request(ags: Ags, xml: String): F[OsciResponse]

  /** Asynchronous send (`StoreDelivery`): stores the message in the
   *  recipient's mailbox at their intermediary and returns a receipt; the
   *  recipient fetches it later. Used by profiles like XFamilie.
   */
  def send(ags: Ags, xml: String): F[OsciReceipt]
}

object OsciClient {

  /** Build a single-tenant OSCI/XMeld client.
   *
   *  Per `request(ags, xml)`, the bundled `AgsResolver` performs a single DVDV
   *  `findServiceDescription` call on the recipient AGS, and pulls both the
   *  addressee (OSCI_ADDRESSEE) and intermediary (OSCI_INTERMEDIARY) routes
   *  out of the same service description. The DvdvClient's mules cache
   *  memoizes that response for `cacheConfig.findServiceDescriptionTtl`
   *  (default 10 minutes), so repeated sends to the same AGS reuse it.
   *
   *  The given DvdvClient is owned by the caller; this resource does not
   *  close it.
   *
   *  `config.certSource` is loaded once and kept for the lifetime of the
   *  client — use the `CertManager` overload for a cert that can rotate.
   *
   *  Uses an [[OsciHttpTransport]] with the config's `connectTimeout` /
   *  `readTimeout` on the wire; the `transport` overload swaps it for a
   *  custom one.
   *
   *  All constructors need a `LoggerFactory[F]` in scope — a failing
   *  [[LaufzettelSink]] is logged at warn instead of failing the operation
   *  (e.g. log4cats' `Slf4jFactory`, or `NoOpFactory` in tests).
   */
  def resource[F[_]: Async: LoggerFactory](
      config: OsciConfig,
      dvdv:   DvdvClient[F],
      sink:   LaufzettelSink[F]
  ): Resource[F, OsciClient[F]] =
    resource(config, dvdv, sink, defaultTransport(config))

  /** Same as the 3-arg overload, but sends over the given `transport` instead
   *  of the default [[OsciHttpTransport]] (the config's timeouts then do not
   *  apply — the transport owns its own settings).
   */
  def resource[F[_]: Async: LoggerFactory](
      config:    OsciConfig,
      dvdv:      DvdvClient[F],
      sink:      LaufzettelSink[F],
      transport: TransportI
  ): Resource[F, OsciClient[F]] = {
    val resolver = internal.AgsResolver[F](dvdv, config)
    val certSource = config.certSource.liftTo[F](
      OsciError.Config(
        "OsciConfig.certSource is not set — set it, or use the CertManager/CertAlias overload"
      )
    )
    Resource.eval(certSource)
      .flatMap(
        internal.OsciBibBridge
          .resource[F](_, transport, config.contentSignatures, config.explicitDialog)
      )
      .map { bridge =>
        new internal.OsciClientImpl[F](
          config.tenantId, config.subject, bridge, resolver, sink, config.capturePayloads
        )
      }
  }

  /** Build an OSCI/XMeld client whose Originator (Autor) signing + decryption
   *  cert is resolved from the shared [[CertManager]] by [[CertAlias]] — the
   *  same cert the matching [[DvdvClient]] uses. The Laufzettel is recorded
   *  under the alias as its tenant id.
   *
   *  The alias is resolved once at build time to fail fast on a missing cert
   *  or unopenable keystore, and again on every `request` / `send` — so a
   *  cert rotated in the manager (e.g. a hot-reloading `DirectoryCertManager`)
   *  is picked up without rebuilding the client. The built OSCI Originator is
   *  cached and only rebuilt when the credential actually changes.
   */
  def resource[F[_]: Async: LoggerFactory](
      config: OsciConfig,
      certs:  CertManager[F],
      alias:  CertAlias,
      dvdv:   DvdvClient[F],
      sink:   LaufzettelSink[F]
  ): Resource[F, OsciClient[F]] =
    resource(config, certs, alias, dvdv, sink, defaultTransport(config))

  /** Same as the CertManager overload, but sends over the given `transport`
   *  instead of the default [[OsciHttpTransport]].
   */
  def resource[F[_]: Async: LoggerFactory](
      config:    OsciConfig,
      certs:     CertManager[F],
      alias:     CertAlias,
      dvdv:      DvdvClient[F],
      sink:      LaufzettelSink[F],
      transport: TransportI
  ): Resource[F, OsciClient[F]] = {
    val resolver = internal.AgsResolver[F](dvdv, config)
    internal.OsciBibBridge
      .resource[F](certs, alias, transport, config.contentSignatures, config.explicitDialog)
      .map { bridge =>
        new internal.OsciClientImpl[F](
          TenantId(alias.value), config.subject, bridge, resolver, sink, config.capturePayloads
        )
      }
  }

  private def defaultTransport(config: OsciConfig): TransportI =
    new OsciHttpTransport(config.connectTimeout, config.readTimeout)
}
