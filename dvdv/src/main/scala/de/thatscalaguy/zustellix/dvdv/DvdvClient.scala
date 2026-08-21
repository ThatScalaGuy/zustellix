package de.thatscalaguy.zustellix.dvdv

import cats.effect.{Async, Resource, Sync}
import cats.syntax.functor.*
import de.thatscalaguy.zustellix.dvdv.auth.{AuthMiddleware, TokenManager}
import de.thatscalaguy.zustellix.utils.cert.{CertLoader, CertManager, CertAlias, LoadedCert}
import de.thatscalaguy.zustellix.dvdv.internal.{CachedDvdvClient, FailoverClient, HttpDvdvClient}
import de.thatscalaguy.zustellix.dvdv.model.*
import fs2.io.net.Network
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.client.Client

/** Tagless-final algebra for the DVDV2 v2 directory API. The entry path is
 *  configurable via [[DvdvConfig.entryPath]] (default
 *  `extern/standaloneauth/directory`, see [[DvdvEntryPath]]).
 *
 *  With [[DvdvEntryPath.StandaloneAuth]], the configured client certificate
 *  is used exclusively to sign the `client_assertion` JWT (RS256). It is NOT
 *  installed as a TLS client certificate — the DVDV2 protocol verifies cert
 *  possession via the signed JWT, not via mTLS. The unauthenticated entry
 *  paths need no cert at all.
 */
trait DvdvClient[F[_]] {

  // 3 plain GETs
  def categories: F[List[DirectoryOrganizationCategoryLevel1DTO]]
  def intermediaries: F[List[SummaryServiceElementDTO]]
  def serviceVersion: F[ServiceVersion]

  // 8 query-style GETs (request_json=...)
  def findAuthorityDescription(category: Category, organizationKey: OrganizationKey): F[Option[OrganizationDescription]]
  def findAuthorityDescriptions(organizationKey: OrganizationKey): F[List[OrganizationDescription]]
  def findCategories(fingerPrint: Fingerprint, organizationKey: OrganizationKey): F[List[String]]
  def findCertificateByFingerprint(fingerPrint: Fingerprint): F[Option[Certificate]]
  def findOrganizationsByServiceElement(
      serviceElementType: ServiceElementType,
      parameterType: ParameterType,
      parameterValue: String
  ): F[List[LightweightOrganization]]

  /** Like the `ServiceElementType` overload, but for operator-configured
   *  service-element types: sends the spec's `customServiceElementType`
   *  request field instead of `serviceElementType`.
   */
  def findOrganizationsByServiceElement(
      customServiceElementType: String,
      parameterType: ParameterType,
      parameterValue: String
  ): F[List[LightweightOrganization]]
  def findServiceDescription(organizationKey: OrganizationKey, serviceSpecificationUri: String): F[Option[Service]]
  def findServiceSpecificationUrisByCategory(category: Category): F[List[String]]
  def verifyCategory(fingerPrint: Fingerprint, category: Category): F[VerificationResult]

  // 6 batch POSTs
  def batchFindAuthorityDescription(requests: List[Request]): F[List[OrganizationDescription]]
  def batchFindCategories(requests: List[Request]): F[List[List[String]]]
  def batchFindOrganizationsByServiceElement(requests: List[Request]): F[List[List[LightweightOrganization]]]
  def batchFindServiceDescription(requests: List[Request]): F[List[Service]]
  def batchFindServiceSpecificationUrisByCategory(requests: List[Request]): F[List[List[String]]]
  def batchVerifyCategory(requests: List[Request]): F[List[VerificationResult]]
}

object DvdvClient {

  /** Build a fully-wired DvdvClient for a single tenant.
   *  Token + caches are scoped to this resource — each tenant gets its own.
   */
  def resource[F[_]: Async: Network](config: DvdvConfig): Resource[F, DvdvClient[F]] =
    for {
      resolve <- Resource.eval(configuredResolve[F](config))
      http    <- EmberClientBuilder.default[F].withTimeout(config.requestTimeout).build
      client  <- assemble[F](config, http, resolve)
    } yield client

  /** Build a DvdvClient whose signing cert is resolved from the shared
   *  [[CertManager]] by [[CertAlias]] (the cert signs the `client_assertion`
   *  JWT, so a client is scoped to one alias).
   *
   *  The alias is resolved once at build time to fail fast on a missing cert,
   *  and again on every token refresh — so a cert rotated in the manager (e.g.
   *  a hot-reloading `DirectoryCertManager`) is picked up without a restart.
   */
  def resource[F[_]: Async: Network](
      config: DvdvConfig,
      certs:  CertManager[F],
      alias:  CertAlias
  ): Resource[F, DvdvClient[F]] =
    for {
      _      <- Resource.eval(certs.loadedCert(alias))
      http   <- EmberClientBuilder.default[F].withTimeout(config.requestTimeout).build
      client <- assemble[F](config, http, certs.loadedCert(alias))
    } yield client

  /** Build a DvdvClient over a caller-provided http4s Client.
   *  Useful for testing or when the caller wants to control the HTTP backend.
   */
  def fromClient[F[_]: Async](config: DvdvConfig, http: Client[F]): Resource[F, DvdvClient[F]] =
    Resource.eval(configuredResolve[F](config)).flatMap(resolve => assemble[F](config, http, resolve))

  /** [[fromClient]] with the signing cert resolved by [[CertAlias]]. Like the
   *  [[resource]] overload, the cert is re-resolved on every token refresh so
   *  rotations in the [[CertManager]] take effect without a rebuild.
   */
  def fromClient[F[_]: Async](
      config: DvdvConfig,
      http:   Client[F],
      certs:  CertManager[F],
      alias:  CertAlias
  ): Resource[F, DvdvClient[F]] =
    Resource.eval(certs.loadedCert(alias)).flatMap(_ => assemble[F](config, http, certs.loadedCert(alias)))

  /** The cert-resolution effect for [[assemble]], shaped by the entry path.
   *  For [[DvdvEntryPath.StandaloneAuth]] the cert is loaded eagerly (fail
   *  fast on a missing/broken `certSource`) and the resolve step just returns
   *  it. For the unauthenticated entry paths no token manager is built, so
   *  the (never-run) resolve step is deferred and no `certSource` is
   *  required.
   */
  private def configuredResolve[F[_]: Sync](config: DvdvConfig): F[F[LoadedCert]] =
    if (config.entryPath.usesStandaloneToken)
      loadConfigured[F](config).map(loaded => Sync[F].pure(loaded))
    else
      Sync[F].pure(loadConfigured[F](config))

  private def loadConfigured[F[_]: Sync](config: DvdvConfig): F[LoadedCert] =
    config.certSource match {
      case Some(src) => CertLoader.load[F](src)
      case None =>
        Sync[F].raiseError(
          DvdvError.Config(
            "DvdvConfig.certSource is not set — set it, or use the CertManager/CertAlias overload"
          )
        )
    }

  private def assemble[F[_]: Async](
      config:  DvdvConfig,
      http:    Client[F],
      resolve: F[LoadedCert]
  ): Resource[F, DvdvClient[F]] =
    for {
      handle   <- Resource.eval(FailoverClient.make[F](config.servers, config.recoverAfter))
      failover  = handle.middleware(http)
      directory <- if (config.entryPath.usesStandaloneToken) {
                     val tokenEp = handle.activeServer.map(config.tokenUriFor)
                     // An explicit tokenEndpoint is an exact wire target — routing it
                     // through the failover middleware would rewrite its authority to a
                     // directory server, so token POSTs go straight to the underlying
                     // client instead.
                     val tokenHttp = if (config.tokenEndpoint.isDefined) http else failover
                     Resource
                       .eval(TokenManager.make[F](tokenHttp, config, resolve, tokenEp))
                       .map(tokenMgr => AuthMiddleware(tokenMgr)(failover))
                   } else Resource.pure[F, Client[F]](failover)
      raw       = HttpDvdvClient[F](directory, config)
      cached   <- Resource.eval(CachedDvdvClient.make[F](raw, config.cacheConfig))
    } yield cached
}
