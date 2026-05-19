package de.thatscalaguy.zustellix.dvdv

import de.thatscalaguy.zustellix.utils.cert.CertSource
import org.http4s.Uri

import scala.concurrent.duration.*

final case class DvdvConfig(
    baseUri: Uri,
    certSource: CertSource,
    issuer: Option[String] = None,
    audience: Option[Uri] = None,
    jwtLifetime: FiniteDuration = 60.seconds,
    tokenRefreshSkew: FiniteDuration = 30.seconds,
    requestTimeout: FiniteDuration = 30.seconds,
    cacheConfig: CacheConfig = CacheConfig()
) {
  val entryPath: String = "extern/standaloneauth/directory"

  def tokenUri: Uri =
    audience.getOrElse(baseUri / "extern" / "standaloneauth" / "token")

  def directoryBase: Uri =
    baseUri / "extern" / "standaloneauth" / "directory" / "v2"
}
