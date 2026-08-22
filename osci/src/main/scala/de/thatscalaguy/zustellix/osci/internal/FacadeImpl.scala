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

import cats.Monad
import cats.syntax.all.*
import de.thatscalaguy.zustellix.osci.{
  Ags,
  OsciFacade,
  OsciMailbox,
  OsciReceipt,
  OsciResponse,
  TenantId,
  TenantRegistry
}

private[osci] final class FacadeImpl[F[_]: Monad](registry: TenantRegistry[F])
    extends OsciFacade[F] {

  def request(tenant: TenantId, ags: Ags, xml: String): F[OsciResponse] =
    registry.lookup(tenant).flatMap(_.request(ags, xml))

  def send(tenant: TenantId, ags: Ags, xml: String): F[OsciReceipt] =
    registry.lookup(tenant).flatMap(_.send(ags, xml))

  def tenants: F[Set[TenantId]] = registry.list

  def mailbox(tenant: TenantId): F[OsciMailbox[F]] =
    registry.mailbox(tenant)
}
