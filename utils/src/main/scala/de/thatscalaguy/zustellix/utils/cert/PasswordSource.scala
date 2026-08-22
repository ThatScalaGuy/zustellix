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
