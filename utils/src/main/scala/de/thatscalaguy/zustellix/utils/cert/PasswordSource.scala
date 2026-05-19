package de.thatscalaguy.zustellix.utils.cert

import cats.effect.Sync
import cats.syntax.all.*

import java.io.FileInputStream
import java.nio.file.Path
import java.util.Properties
import scala.jdk.CollectionConverters.*

trait PasswordSource[F[_]] {
  def passwordFor(alias: CertAlias): F[Option[String]]
}

object PasswordSource {

  /** Reads `<alias>=<password>` entries from a `java.util.Properties` file,
   *  mirroring the existing `FileConfigSource` convention. Read eagerly at
   *  construction; [[DirectoryCertManager]] reconstructs it per scan so
   *  password rotations are picked up.
   */
  def propertiesFile[F[_]: Sync](file: Path): F[PasswordSource[F]] =
    Sync[F].blocking {
      val props = new Properties()
      val in    = new FileInputStream(file.toFile)
      try props.load(in)
      finally in.close()
      props.stringPropertyNames().asScala.iterator
        .map(k => k -> props.getProperty(k))
        .toMap
    }.map { byAlias =>
      new PasswordSource[F] {
        def passwordFor(alias: CertAlias): F[Option[String]] = byAlias.get(alias.value).pure[F]
      }
    }
}
