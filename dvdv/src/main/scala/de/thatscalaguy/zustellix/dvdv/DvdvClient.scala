package de.thatscalaguy.zustellix.dvdv

import cats.effect.{Async, Resource}
import de.thatscalaguy.zustellix.dvdv.auth.{AuthMiddleware, TokenManager}
import de.thatscalaguy.zustellix.utils.cert.{CertLoader, CertManager, CertAlias, LoadedCert}
import de.thatscalaguy.zustellix.dvdv.internal.{CachedDvdvClient, FailoverClient, HttpDvdvClient}
import de.thatscalaguy.zustellix.dvdv.model.*
import fs2.io.net.Network
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.client.Client

/** Tagless-final algebra for the DVDV2 v2 directory API
 *  (entry path `extern/standaloneauth/directory`).
 *
 *  The configured client certificate is used exclusively to sign the
 *  `client_assertion` JWT (RS256). It is NOT installed as a TLS client
 *  certificate — the DVDV2 protocol verifies cert possession via the
 *  signed JWT, not via mTLS.
 */
trait DvdvClient[F[_]] {

  // 3 plain GETs
  def categories: F[List[DirectoryOrganizationCategoryLevel1DTO]]
  def intermediaries: F[List[SummaryServiceElementDTO]]
  def serviceVersion: F[ServiceVersion]

  // 8 query-style GETs (request_json=...)
  def findAuthorityDescription(category: String, organizationKey: String): F[Option[OrganizationDescription]]
  def findAuthorityDescriptions(organizationKey: String): F[List[OrganizationDescription]]
  def findCategories(fingerPrint: String, organizationKey: String): F[List[String]]
  def findCertificateByFingerprint(fingerPrint: String): F[Option[Certificate]]
  def findOrganizationsByServiceElement(
      serviceElementType: ServiceElementType,
      parameterType: ParameterType,
      parameterValue: String
  ): F[List[LightweightOrganization]]
  def findServiceDescription(organizationKey: String, serviceSpecificationUri: String): F[Option[Service]]
  def findServiceSpecificationUrisByCategory(category: String): F[List[String]]
  def verifyCategory(fingerPrint: String, category: String): F[VerificationResult]

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
      loaded <- Resource.eval(CertLoader.load[F](config.certSource))
      http   <- EmberClientBuilder.default[F].withTimeout(config.requestTimeout).build
      client <- assemble[F](config, http, loaded)
    } yield client

  /** Build a DvdvClient whose signing cert is resolved from the shared
   *  [[CertManager]] by [[CertAlias]] (the cert signs the `client_assertion`
   *  JWT, so a client is scoped to one alias).
   */
  def resource[F[_]: Async: Network](
      config: DvdvConfig,
      certs:  CertManager[F],
      alias:  CertAlias
  ): Resource[F, DvdvClient[F]] =
    for {
      loaded <- Resource.eval(certs.loadedCert(alias))
      http   <- EmberClientBuilder.default[F].withTimeout(config.requestTimeout).build
      client <- assemble[F](config, http, loaded)
    } yield client

  /** Build a DvdvClient over a caller-provided http4s Client.
   *  Useful for testing or when the caller wants to control the HTTP backend.
   */
  def fromClient[F[_]: Async](config: DvdvConfig, http: Client[F]): Resource[F, DvdvClient[F]] =
    Resource.eval(CertLoader.load[F](config.certSource)).flatMap(assemble[F](config, http, _))

  /** [[fromClient]] with the signing cert resolved by [[CertAlias]]. */
  def fromClient[F[_]: Async](
      config: DvdvConfig,
      http:   Client[F],
      certs:  CertManager[F],
      alias:  CertAlias
  ): Resource[F, DvdvClient[F]] =
    Resource.eval(certs.loadedCert(alias)).flatMap(assemble[F](config, http, _))

  private def assemble[F[_]: Async](
      config: DvdvConfig,
      http:   Client[F],
      loaded: LoadedCert
  ): Resource[F, DvdvClient[F]] =
    for {
      failover <- Resource.eval(FailoverClient.make[F](config.servers, config.recoverAfter)).map(_(http))
      tokenMgr <- Resource.eval(TokenManager.make[F](failover, config, loaded))
      authed    = AuthMiddleware(tokenMgr)(failover)
      raw       = HttpDvdvClient[F](authed, config)
      cached   <- Resource.eval(CachedDvdvClient.make[F](raw, config.cacheConfig))
    } yield cached
}
