package de.thatscalaguy.zustellix.osci

import cats.ApplicativeThrow
import cats.syntax.all.*

trait TenantRegistry[F[_]] {
  /** Returns the [[OsciClient]] for the given tenant, or raises
   *  [[OsciError.UnknownTenant]] in F.
   */
  def lookup(tenant: TenantId): F[OsciClient[F]]

  def list: F[Set[TenantId]]
}

object TenantRegistry {

  def inMemory[F[_]: ApplicativeThrow](
      entries: Map[TenantId, OsciClient[F]]
  ): TenantRegistry[F] =
    new TenantRegistry[F] {
      def lookup(tenant: TenantId): F[OsciClient[F]] =
        entries.get(tenant) match {
          case Some(c) => c.pure[F]
          case None    => ApplicativeThrow[F].raiseError(OsciError.UnknownTenant(tenant))
        }

      def list: F[Set[TenantId]] = entries.keySet.pure[F]
    }
}
