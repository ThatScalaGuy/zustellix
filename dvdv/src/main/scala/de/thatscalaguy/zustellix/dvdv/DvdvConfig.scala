package de.thatscalaguy.zustellix.dvdv

import cats.data.NonEmptyList
import de.thatscalaguy.zustellix.utils.cert.CertSource
import org.http4s.Uri

import scala.concurrent.duration.*

final case class DvdvConfig(
    baseUri: Uri,
    /** The directory entry path (and with it the auth scheme) the client
     *  addresses under [[baseUri]]. See [[DvdvEntryPath]].
     */
    entryPath: DvdvEntryPath = DvdvEntryPath.StandaloneAuth,
    /** The signing cert. Leave empty when building the client from a
     *  [[de.thatscalaguy.zustellix.utils.cert.CertManager]] + alias, which
     *  supplies the cert itself.
     */
    certSource: Option[CertSource] = None,
    issuer: Option[String] = None,
    /** Explicit wire target of the token POST. When set, the token request is
     *  sent to exactly this URI, bypassing the failover middleware (an exact
     *  wire target must not have its authority rewritten to a directory
     *  server — which also means such a POST gets no 5xx/transport failover).
     *  When `None`, the endpoint is derived from the currently active server:
     *  `<active server>/extern/standaloneauth/token`, following failover.
     *
     *  Together with [[jwtAudience]] this replaces the former `audience` field,
     *  which steered both the wire target and the JWT `aud` claim at once.
     */
    tokenEndpoint: Option[Uri] = None,
    /** Pins the JWT `aud` claim of the `client_assertion`. When `None`, `aud`
     *  is the token endpoint actually contacted — so after a failover the claim
     *  follows the answering server, matching the per-server addressing of the
     *  DVDV2 failover model. (The reference implementation could not be
     *  consulted on whether `aud` must track the contacted server, so the
     *  default tracks it; deployments that require a fixed `aud` set this
     *  field explicitly.)
     */
    jwtAudience: Option[String] = None,
    jwtLifetime: FiniteDuration = 60.seconds,
    tokenRefreshSkew: FiniteDuration = 30.seconds,
    requestTimeout: FiniteDuration = 30.seconds,
    ignoreRevocation: Boolean = false,
    failoverServers: List[Uri] = Nil,
    recoverAfter: FiniteDuration = 180.seconds,
    cacheConfig: CacheConfig = CacheConfig()
) {
  /** All servers, index 0 = primary ([[baseUri]]), then the failover servers. */
  def servers: NonEmptyList[Uri] = NonEmptyList(baseUri, failoverServers)

  /** The token endpoint for a token POST addressed at `host`:
   *  [[tokenEndpoint]] when set, else `host/extern/standaloneauth/token`.
   *  The spec defines this endpoint for standalone auth only, so it is
   *  consumed only when [[entryPath]] is [[DvdvEntryPath.StandaloneAuth]].
   */
  def tokenUriFor(host: Uri): Uri =
    tokenEndpoint.getOrElse(host / "extern" / "standaloneauth" / "token")

  def directoryBase: Uri =
    entryPath.segments.foldLeft(baseUri)(_ / _) / "v2"
}
