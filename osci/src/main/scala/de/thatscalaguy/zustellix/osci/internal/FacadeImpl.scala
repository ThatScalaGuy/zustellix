package de.thatscalaguy.zustellix.osci.internal

import cats.Monad
import cats.syntax.all.*
import de.thatscalaguy.zustellix.osci.{
  OsciFacade,
  OsciReceipt,
  OsciResponse,
  TenantId,
  TenantRegistry
}

private[osci] final class FacadeImpl[F[_]: Monad](registry: TenantRegistry[F])
    extends OsciFacade[F] {

  def request(tenant: TenantId, ags: String, xml: String): F[OsciResponse] =
    registry.lookup(tenant).flatMap(_.request(ags, xml))

  def send(tenant: TenantId, ags: String, xml: String): F[OsciReceipt] =
    registry.lookup(tenant).flatMap(_.send(ags, xml))
}
