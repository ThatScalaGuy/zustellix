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

package de.thatscalaguy.zustellix.utils.cert

import cats.effect.{Ref, Sync}
import cats.syntax.all.*

/** Runtime-configured, hot-swappable [[CertManager]]. The caller supplies the
 *  full `Map[CertAlias, CertCredential]`; [[InMemoryCertManager.Swappable.swap]]
 *  atomically replaces it (used by the runtime config and by
 *  [[DirectoryCertManager]]).
 */
object InMemoryCertManager {

  /** Mix-in so the active map can be replaced without widening the read-only
   *  [[CertManager]] surface for normal consumers.
   */
  trait Swappable[F[_]] {
    def swap(next: Map[CertAlias, CertCredential]): F[Unit]
  }

  def make[F[_]: Sync](
      initial: Map[CertAlias, CertCredential]
  ): F[CertManager[F] & Swappable[F]] =
    Ref.of[F, Map[CertAlias, CertCredential]](initial).map(new Impl[F](_))

  private final class Impl[F[_]: Sync](
      ref: Ref[F, Map[CertAlias, CertCredential]]
  ) extends CertManager[F]
      with Swappable[F] {

    def resolve(alias: CertAlias): F[CertCredential] =
      ref.get.flatMap { m =>
        m.get(alias) match {
          case Some(c) => c.pure[F]
          case None    => Sync[F].raiseError(CertManagerError.UnknownCert(alias))
        }
      }

    def loadedCert(alias: CertAlias): F[LoadedCert] =
      resolve(alias).flatMap(
        _.loadedCert[F].adaptError { case e => CertManagerError.LoadFailed(alias, e) }
      )

    def knownAliases: F[Set[CertAlias]] = ref.get.map(_.keySet)

    def swap(next: Map[CertAlias, CertCredential]): F[Unit] = ref.set(next)
  }
}
