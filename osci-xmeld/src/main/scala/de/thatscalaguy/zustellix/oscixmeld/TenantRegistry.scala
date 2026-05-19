package de.thatscalaguy.zustellix.oscixmeld

import cats.ApplicativeThrow
import cats.syntax.all.*

trait TenantRegistry[F[_]] {
  /** Returns the [[OSCIXMeld]] for the given tenant, or raises
   *  [[OSCIXMeldError.UnknownTenant]] in F.
   */
  def lookup(tenant: TenantId): F[OSCIXMeld[F]]

  def list: F[Set[TenantId]]
}

object TenantRegistry {

  def inMemory[F[_]: ApplicativeThrow](
      entries: Map[TenantId, OSCIXMeld[F]]
  ): TenantRegistry[F] =
    new TenantRegistry[F] {
      def lookup(tenant: TenantId): F[OSCIXMeld[F]] =
        entries.get(tenant) match {
          case Some(c) => c.pure[F]
          case None    => ApplicativeThrow[F].raiseError(OSCIXMeldError.UnknownTenant(tenant))
        }

      def list: F[Set[TenantId]] = entries.keySet.pure[F]
    }
}
