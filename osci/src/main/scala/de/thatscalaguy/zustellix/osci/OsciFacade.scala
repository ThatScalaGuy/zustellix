package de.thatscalaguy.zustellix.osci

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import de.thatscalaguy.zustellix.dvdv.DvdvClient

trait OsciFacade[F[_]] {
  def request(tenant: TenantId, ags: Ags, xml: String): F[OsciResponse]
  def send(tenant: TenantId, ags: Ags, xml: String): F[OsciReceipt]
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
   */
  def fromConfigs[F[_]: Async](
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
