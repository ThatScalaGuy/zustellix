package de.thatscalaguy.zustellix.oscixmeld

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import de.thatscalaguy.zustellix.dvdv.DvdvClient

trait OSCIXMeldFacade[F[_]] {
  def send(tenant: TenantId, ags: String, xml: String): F[String]
}

object OSCIXMeldFacade {

  /** Build a multi-tenant facade. One [[OSCIXMeld]] is constructed per
   *  tenant config. `dvdvFor` returns the DvdvClient to use for a given
   *  tenant; the caller owns those clients' lifetimes.
   */
  def fromConfigs[F[_]: Async](
      src:    ConfigSource[F],
      dvdvFor: TenantId => DvdvClient[F],
      sink:   LaufzettelSink[F]
  ): Resource[F, OSCIXMeldFacade[F]] =
    for {
      cfgs  <- Resource.eval(src.load)
      pairs <- cfgs.toList.traverse { case (id, c) =>
                 OSCIXMeld.resource[F](c, dvdvFor(id), sink).map(id -> _)
               }
      registry = TenantRegistry.inMemory[F](pairs.toMap)
    }
    yield new internal.FacadeImpl[F](registry)

  def fromRegistry[F[_]: Async](registry: TenantRegistry[F]): OSCIXMeldFacade[F] =
    new internal.FacadeImpl[F](registry)
}
