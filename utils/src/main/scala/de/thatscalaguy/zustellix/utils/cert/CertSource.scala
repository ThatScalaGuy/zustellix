package de.thatscalaguy.zustellix.utils.cert

import java.nio.file.Path

sealed trait CertSource

object CertSource {
  final case class Pkcs12(path: Path, password: String) extends CertSource
  final case class Pem(certPath: Path, keyPath: Path, keyPassword: Option[String] = None) extends CertSource
}
