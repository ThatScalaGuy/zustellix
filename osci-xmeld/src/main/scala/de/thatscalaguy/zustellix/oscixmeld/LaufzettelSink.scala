package de.thatscalaguy.zustellix.oscixmeld

import cats.effect.Sync

trait LaufzettelSink[F[_]] {
  def record(tenant: TenantId, l: Laufzettel): F[Unit]
}

object LaufzettelSink {

  def console[F[_]: Sync]: LaufzettelSink[F] =
    new LaufzettelSink[F] {
      def record(tenant: TenantId, l: Laufzettel): F[Unit] =
        Sync[F].delay {
          println(
            s"[Laufzettel tenant=${tenant.value} ags=${l.recipientAgs} " +
              s"messageId=${l.messageId} status=${l.status} " +
              s"uri=${l.recipientUri} at=${l.timestamp}]"
          )
        }
    }

  def noop[F[_]: Sync]: LaufzettelSink[F] =
    new LaufzettelSink[F] {
      def record(tenant: TenantId, l: Laufzettel): F[Unit] = Sync[F].unit
    }
}
