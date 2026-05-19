package de.thatscalaguy.zustellix.oscixmeld.internal

import cats.Monad
import cats.syntax.all.*
import de.thatscalaguy.zustellix.oscixmeld.{OSCIXMeldFacade, TenantId, TenantRegistry}

private[oscixmeld] final class FacadeImpl[F[_]: Monad](registry: TenantRegistry[F])
    extends OSCIXMeldFacade[F] {

  def send(tenant: TenantId, ags: String, xml: String): F[String] =
    registry.lookup(tenant).flatMap(_.send(ags, xml))
}
