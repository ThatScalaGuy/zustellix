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
import org.typelevel.log4cats.LoggerFactory

trait OsciFacade[F[_]] {
  def request(tenant: TenantId, ags: Ags, xml: String): F[OsciResponse]
  def send(tenant: TenantId, ags: Ags, xml: String): F[OsciReceipt]

  /** Every tenant the underlying registry knows ([[TenantRegistry.list]]). */
  def tenants: F[Set[TenantId]]

  /** The tenant's [[OsciMailbox]] — run `pending` / `fetch` / `drain` against
   *  it. Raises [[OsciError.UnknownTenant]] when the registry holds no
   *  mailbox for the tenant.
   */
  def mailbox(tenant: TenantId): F[OsciMailbox[F]]
}

object OsciFacade {

  /** Build a multi-tenant facade. One [[OsciClient]] is constructed per
   *  tenant config. `dvdvFor` returns the DvdvClient to use for a given
   *  tenant; the caller owns those clients' lifetimes.
   *
   *  Boot is all-or-nothing: every tenant client is built eagerly when the
   *  resource is acquired, and a tenant whose client cannot be built (e.g.
   *  its certificate fails to load) fails the whole resource with
   *  [[OsciError.TenantInitFailed]] naming that tenant — there is no
   *  partial boot.
   *
   *  Needs a `LoggerFactory[F]` in scope, like `OsciClient.resource` — a
   *  failing [[LaufzettelSink]] is logged at warn instead of failing the
   *  operation.
   *
   *  The built registry registers no mailboxes ([[ConfigSource]] carries
   *  only client configs), so [[OsciFacade.mailbox]] raises
   *  [[OsciError.UnknownTenant]] — to dispatch mailboxes, build via
   *  [[fromRegistry]] with `TenantRegistry.inMemory(clients, mailboxes)`.
   */
  def fromConfigs[F[_]: Async: LoggerFactory](
      src:    ConfigSource[F],
      dvdvFor: TenantId => DvdvClient[F],
      sink:   LaufzettelSink[F]
  ): Resource[F, OsciFacade[F]] =
    for {
      cfgs  <- Resource.eval(src.load)
      pairs <- cfgs.toList.traverse { case (id, c) =>
                 OsciClient.resource[F](c, dvdvFor(id), sink)
                   .adaptError { case e => OsciError.TenantInitFailed(id, e) }
                   .map(id -> _)
               }
      registry = TenantRegistry.inMemory[F](pairs.toMap)
    }
    yield new internal.FacadeImpl[F](registry)

  def fromRegistry[F[_]: Async](registry: TenantRegistry[F]): OsciFacade[F] =
    new internal.FacadeImpl[F](registry)
}
