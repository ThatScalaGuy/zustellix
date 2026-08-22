package de.thatscalaguy.zustellix.osci

import cats.ApplicativeThrow
import cats.syntax.all.*

trait TenantRegistry[F[_]] {
  /** Returns the [[OsciClient]] for the given tenant, or raises
   *  [[OsciError.UnknownTenant]] in F.
   */
  def lookup(tenant: TenantId): F[OsciClient[F]]

  /** Returns the [[OsciMailbox]] for the given tenant, or raises
   *  [[OsciError.UnknownTenant]] in F when no mailbox is registered for it
   *  (a tenant may carry a client but no mailbox).
   */
  def mailbox(tenant: TenantId): F[OsciMailbox[F]]

  /** Every tenant the registry knows — with a client, a mailbox, or both. */
  def list: F[Set[TenantId]]
}

object TenantRegistry {

  /** Client-only registry — no mailboxes, so [[TenantRegistry.mailbox]]
   *  always raises. (An overload instead of a `mailboxes = Map.empty`
   *  default: the polymorphic default getter is instantiated at `Nothing`,
   *  which the invariant `OsciMailbox[F]` rejects at every call site.)
   */
  def inMemory[F[_]: ApplicativeThrow](
      entries: Map[TenantId, OsciClient[F]]
  ): TenantRegistry[F] =
    inMemory(entries, Map.empty)

  def inMemory[F[_]: ApplicativeThrow](
      entries:   Map[TenantId, OsciClient[F]],
      mailboxes: Map[TenantId, OsciMailbox[F]]
  ): TenantRegistry[F] =
    new TenantRegistry[F] {
      def lookup(tenant: TenantId): F[OsciClient[F]] =
        find(entries, tenant)

      def mailbox(tenant: TenantId): F[OsciMailbox[F]] =
        find(mailboxes, tenant)

      def list: F[Set[TenantId]] = (entries.keySet ++ mailboxes.keySet).pure[F]

      private def find[A](m: Map[TenantId, A], tenant: TenantId): F[A] =
        m.get(tenant) match {
          case Some(a) => a.pure[F]
          case None    => ApplicativeThrow[F].raiseError(OsciError.UnknownTenant(tenant))
        }
    }
}
