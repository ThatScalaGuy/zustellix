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
