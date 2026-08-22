/*
 * Copyright 2026 ThatScalaGuy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
