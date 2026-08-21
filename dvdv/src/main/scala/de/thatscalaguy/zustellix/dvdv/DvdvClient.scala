package de.thatscalaguy.zustellix.dvdv

import cats.effect.{Async, Resource, Sync}
import cats.effect.syntax.all.*
import cats.syntax.functor.*
import de.thatscalaguy.zustellix.dvdv.auth.{AuthMiddleware, TokenManager}
import de.thatscalaguy.zustellix.utils.cert.{CertLoader, CertManager, CertAlias, LoadedCert}
import de.thatscalaguy.zustellix.dvdv.internal.{CachedDvdvClient, FailoverClient, HttpDvdvClient}
import de.thatscalaguy.zustellix.dvdv.model.*
import fs2.io.net.Network
import org.http4s.Response
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.client.Client
import org.typelevel.log4cats.LoggerFactory

import java.util.concurrent.TimeoutException

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

  // 6 batch POSTs.
  //
  // All batch methods raise [[DvdvError.BatchTooLarge]] before any HTTP call
  // when given more than 200 requests (the spec's `maxItems: 200`). A typed
  // error was chosen over transparent chunking because chunking multiplies
  // authenticated wire calls invisibly and cannot preserve atomicity if a
  // later chunk fails. A response array whose length differs from the input
  // list raises [[DvdvError.BatchSizeMismatch]] instead of returning
  // silently misaligned results.

  /** Batch variant of [[findAuthorityDescription]]. Results are positionally
   *  aligned with the input `requests` list. A per-item miss is assumed to be
   *  encoded as a positional JSON null — mirroring the 204/404 miss semantics
   *  of the single-call variant; the OpenAPI spec does not specify the batch
   *  miss encoding — and decodes to `None` at that index. Raises
   *  [[DvdvError.BatchTooLarge]] for more than 200 requests.
   */
  def batchFindAuthorityDescription(requests: List[Request]): F[List[Option[OrganizationDescription]]]
  def batchFindCategories(requests: List[Request]): F[List[List[String]]]
  def batchFindOrganizationsByServiceElement(requests: List[Request]): F[List[List[LightweightOrganization]]]

  /** Batch variant of [[findServiceDescription]]. Results are positionally
   *  aligned with the input `requests` list. A per-item miss is assumed to be
   *  encoded as a positional JSON null — mirroring the 204/404 miss semantics
   *  of the single-call variant; the OpenAPI spec does not specify the batch
   *  miss encoding — and decodes to `None` at that index. Raises
   *  [[DvdvError.BatchTooLarge]] for more than 200 requests.
   */
  def batchFindServiceDescription(requests: List[Request]): F[List[Option[Service]]]
  def batchFindServiceSpecificationUrisByCategory(requests: List[Request]): F[List[List[String]]]
  def batchVerifyCategory(requests: List[Request]): F[List[VerificationResult]]
}

object DvdvClient {

  /** Build a fully-wired DvdvClient for a single tenant.
   *  Token + caches are scoped to this resource — each tenant gets its own.
   *
   *  All constructors need a `LoggerFactory[F]` in scope for the auth layer's
   *  warnings — e.g. log4cats' `Slf4jFactory`, or `NoOpFactory` in tests.
   */
  def resource[F[_]: Async: Network: LoggerFactory](config: DvdvConfig): Resource[F, DvdvClient[F]] =
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
  def resource[F[_]: Async: Network: LoggerFactory](
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
   *
   *  [[DvdvConfig.requestTimeout]] is applied per request attempt around the
   *  provided client, mirroring the Ember overloads — callers whose client
   *  already carries its own timeout should set `requestTimeout` accordingly.
   */
  def fromClient[F[_]: Async: LoggerFactory](config: DvdvConfig, http: Client[F]): Resource[F, DvdvClient[F]] =
    Resource
      .eval(configuredResolve[F](config))
      .flatMap(resolve => assemble[F](config, applyRequestTimeout(config, http), resolve))

  /** [[fromClient]] with the signing cert resolved by [[CertAlias]]. Like the
   *  [[resource]] overload, the cert is re-resolved on every token refresh so
   *  rotations in the [[CertManager]] take effect without a rebuild.
   *  [[DvdvConfig.requestTimeout]] is applied per request attempt around the
   *  provided client.
   */
  def fromClient[F[_]: Async: LoggerFactory](
      config: DvdvConfig,
      http:   Client[F],
      certs:  CertManager[F],
      alias:  CertAlias
  ): Resource[F, DvdvClient[F]] =
    Resource
      .eval(certs.loadedCert(alias))
      .flatMap(_ => assemble[F](config, applyRequestTimeout(config, http), certs.loadedCert(alias)))

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

  /** Applies [[DvdvConfig.requestTimeout]] to each request attempt of a
   *  caller-provided client, mirroring the Ember overloads' `withTimeout`.
   *  http4s ships no client-side Timeout middleware, so this is hand-rolled:
   *  the timeout bounds acquisition of the response (like Ember's request
   *  timeout) and raises `java.util.concurrent.TimeoutException`. It sits
   *  beneath the failover middleware, so every failover attempt gets its own
   *  timeout budget.
   */
  private def applyRequestTimeout[F[_]: Async](config: DvdvConfig, http: Client[F]): Client[F] =
    Client[F] { req =>
      http
        .run(req)
        .timeoutTo(
          config.requestTimeout,
          Resource.raiseError[F, Response[F], Throwable](
            new TimeoutException(s"DVDV request timed out after ${config.requestTimeout}: ${req.method} ${req.uri}")
          )
        )
    }

  private def assemble[F[_]: Async: LoggerFactory](
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
      cached   <- CachedDvdvClient.make[F](raw, config.cacheConfig)
    } yield cached
}
