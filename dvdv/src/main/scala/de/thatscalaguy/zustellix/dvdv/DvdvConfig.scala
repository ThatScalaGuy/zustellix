package de.thatscalaguy.zustellix.dvdv

import cats.data.NonEmptyList
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
    ignoreRevocation: Boolean = false,
    failoverServers: List[Uri] = Nil,
    recoverAfter: FiniteDuration = 180.seconds,
    cacheConfig: CacheConfig = CacheConfig()
) {
  val entryPath: String = "extern/standaloneauth/directory"

  /** All servers, index 0 = primary ([[baseUri]]), then the failover servers. */
  def servers: NonEmptyList[Uri] = NonEmptyList(baseUri, failoverServers)

  def tokenUri: Uri =
    audience.getOrElse(baseUri / "extern" / "standaloneauth" / "token")

  def directoryBase: Uri =
    baseUri / "extern" / "standaloneauth" / "directory" / "v2"
}
