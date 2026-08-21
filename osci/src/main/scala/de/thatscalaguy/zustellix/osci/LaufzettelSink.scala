package de.thatscalaguy.zustellix.osci

import cats.effect.Sync

trait LaufzettelSink[F[_]] {
  def record(tenant: TenantId, l: Laufzettel): F[Unit]
}

object LaufzettelSink {

  def console[F[_]: Sync]: LaufzettelSink[F] =
    new LaufzettelSink[F] {
      def record(tenant: TenantId, l: Laufzettel): F[Unit] =
        Sync[F].delay {
          val warnings =
            if l.warnings.isEmpty then ""
            else l.warnings.map(_.code).mkString(" warnings=", ",", "")
          println(
            s"[Laufzettel tenant=${tenant.value} ags=${l.recipientAgs.value} " +
              s"messageId=${l.messageId} status=${l.status.render}$warnings " +
              s"uri=${l.recipientUri} at=${l.timestamp}]"
          )
        }
    }

  def noop[F[_]: Sync]: LaufzettelSink[F] =
    new LaufzettelSink[F] {
      def record(tenant: TenantId, l: Laufzettel): F[Unit] = Sync[F].unit
    }
}
