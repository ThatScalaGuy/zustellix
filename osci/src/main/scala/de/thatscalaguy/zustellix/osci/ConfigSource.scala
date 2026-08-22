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

import cats.Applicative
import cats.effect.Sync

import java.nio.file.Path

trait ConfigSource[F[_]] {
  def load: F[Map[TenantId, OsciConfig]]
}

object ConfigSource {

  def static[F[_]: Applicative](configs: Map[TenantId, OsciConfig]): ConfigSource[F] =
    new ConfigSource[F] {
      def load: F[Map[TenantId, OsciConfig]] = Applicative[F].pure(configs)
    }

  def file[F[_]: Sync](path: Path): ConfigSource[F] =
    internal.FileConfigSource[F](path)
}
