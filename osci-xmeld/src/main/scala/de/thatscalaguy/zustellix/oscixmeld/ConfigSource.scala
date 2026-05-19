package de.thatscalaguy.zustellix.oscixmeld

import cats.Applicative
import cats.effect.Sync

import java.nio.file.Path

trait ConfigSource[F[_]] {
  def load: F[Map[TenantId, OSCIXMeldConfig]]
}

object ConfigSource {

  def static[F[_]: Applicative](configs: Map[TenantId, OSCIXMeldConfig]): ConfigSource[F] =
    new ConfigSource[F] {
      def load: F[Map[TenantId, OSCIXMeldConfig]] = Applicative[F].pure(configs)
    }

  def file[F[_]: Sync](path: Path): ConfigSource[F] =
    internal.FileConfigSource[F](path)
}
