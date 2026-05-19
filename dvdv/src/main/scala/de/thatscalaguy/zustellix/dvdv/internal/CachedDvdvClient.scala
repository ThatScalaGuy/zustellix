package de.thatscalaguy.zustellix.dvdv.internal

import cats.Monad
import cats.effect.Async
import cats.syntax.all.*
import de.thatscalaguy.zustellix.dvdv.{CacheConfig, DvdvClient}
import de.thatscalaguy.zustellix.dvdv.model.*
import io.chrisdavenport.mules.{MemoryCache, TimeSpec}

import scala.concurrent.duration.FiniteDuration

object CachedDvdvClient {

  def make[F[_]: Async](underlying: DvdvClient[F], cfg: CacheConfig): F[DvdvClient[F]] =
    if (!cfg.enabled) Async[F].pure(underlying)
    else
      for {
        categoriesC                          <- mkCache[F, Unit, List[DirectoryOrganizationCategoryLevel1DTO]](cfg.categoriesTtl)
        intermediariesC                      <- mkCache[F, Unit, List[SummaryServiceElementDTO]](cfg.intermediariesTtl)
        certByFpC                            <- mkCache[F, String, Option[Certificate]](cfg.findCertificateByFingerprintTtl)
        urisByCategoryC                      <- mkCache[F, String, List[ServiceBase]](cfg.findServiceSpecificationUrisByCategoryTtl)
        authDescriptionC                     <- mkCache[F, (String, String), Option[OrganizationDescription]](cfg.findAuthorityDescriptionTtl)
        authDescriptionsC                    <- mkCache[F, String, List[OrganizationDescription]](cfg.findAuthorityDescriptionsTtl)
        categoriesByFpKeyC                   <- mkCache[F, (String, String), List[String]](cfg.findCategoriesTtl)
        serviceDescC                         <- mkCache[F, (String, String), Option[Service]](cfg.findServiceDescriptionTtl)
        orgsByServiceElementC                <- mkCache[F, (ServiceElementType, ParameterType, String), OrganizationDescription](cfg.findOrganizationsByServiceElementTtl)
        verifyCategoryC                      <- mkCache[F, (String, String), VerificationResult](cfg.verifyCategoryTtl)
      } yield new Impl[F](
        underlying,
        categoriesC, intermediariesC, certByFpC, urisByCategoryC,
        authDescriptionC, authDescriptionsC, categoriesByFpKeyC, serviceDescC,
        orgsByServiceElementC, verifyCategoryC
      )

  private def mkCache[F[_]: Async, K, V](ttl: FiniteDuration): F[MemoryCache[F, K, V]] =
    MemoryCache.ofSingleImmutableMap[F, K, V](Some(TimeSpec.unsafeFromDuration(ttl)))

  private def cached[F[_]: Monad, K, V](c: MemoryCache[F, K, V], k: K)(compute: F[V]): F[V] =
    c.lookup(k).flatMap {
      case Some(v) => v.pure[F]
      case None    => compute.flatTap(v => c.insert(k, v))
    }

  private final class Impl[F[_]: Monad](
      underlying: DvdvClient[F],
      categoriesC:           MemoryCache[F, Unit, List[DirectoryOrganizationCategoryLevel1DTO]],
      intermediariesC:       MemoryCache[F, Unit, List[SummaryServiceElementDTO]],
      certByFpC:             MemoryCache[F, String, Option[Certificate]],
      urisByCategoryC:       MemoryCache[F, String, List[ServiceBase]],
      authDescriptionC:      MemoryCache[F, (String, String), Option[OrganizationDescription]],
      authDescriptionsC:     MemoryCache[F, String, List[OrganizationDescription]],
      categoriesByFpKeyC:    MemoryCache[F, (String, String), List[String]],
      serviceDescC:          MemoryCache[F, (String, String), Option[Service]],
      orgsByServiceElementC: MemoryCache[F, (ServiceElementType, ParameterType, String), OrganizationDescription],
      verifyCategoryC:       MemoryCache[F, (String, String), VerificationResult]
  ) extends DvdvClient[F] {

    def categories: F[List[DirectoryOrganizationCategoryLevel1DTO]] =
      cached(categoriesC, ())(underlying.categories)

    def intermediaries: F[List[SummaryServiceElementDTO]] =
      cached(intermediariesC, ())(underlying.intermediaries)

    def serviceVersion: F[ServiceVersion] =
      underlying.serviceVersion // not cached

    def findAuthorityDescription(category: String, organizationKey: String): F[Option[OrganizationDescription]] =
      cached(authDescriptionC, (category, organizationKey))(
        underlying.findAuthorityDescription(category, organizationKey)
      )

    def findAuthorityDescriptions(organizationKey: String): F[List[OrganizationDescription]] =
      cached(authDescriptionsC, organizationKey)(
        underlying.findAuthorityDescriptions(organizationKey)
      )

    def findCategories(fingerPrint: String, organizationKey: String): F[List[String]] =
      cached(categoriesByFpKeyC, (fingerPrint, organizationKey))(
        underlying.findCategories(fingerPrint, organizationKey)
      )

    def findCertificateByFingerprint(fingerPrint: String): F[Option[Certificate]] =
      cached(certByFpC, fingerPrint)(
        underlying.findCertificateByFingerprint(fingerPrint)
      )

    def findOrganizationsByServiceElement(
        serviceElementType: ServiceElementType,
        parameterType: ParameterType,
        parameterValue: String
    ): F[OrganizationDescription] =
      cached(orgsByServiceElementC, (serviceElementType, parameterType, parameterValue))(
        underlying.findOrganizationsByServiceElement(serviceElementType, parameterType, parameterValue)
      )

    def findServiceDescription(organizationKey: String, serviceSpecificationUri: String): F[Option[Service]] =
      cached(serviceDescC, (organizationKey, serviceSpecificationUri))(
        underlying.findServiceDescription(organizationKey, serviceSpecificationUri)
      )

    def findServiceSpecificationUrisByCategory(category: String): F[List[ServiceBase]] =
      cached(urisByCategoryC, category)(
        underlying.findServiceSpecificationUrisByCategory(category)
      )

    def verifyCategory(fingerPrint: String, category: String): F[VerificationResult] =
      cached(verifyCategoryC, (fingerPrint, category))(
        underlying.verifyCategory(fingerPrint, category)
      )

    // Batch endpoints — not cached; delegate.
    def batchFindAuthorityDescription(requests: List[Request]): F[OrganizationDescription] =
      underlying.batchFindAuthorityDescription(requests)

    def batchFindCategories(requests: List[Request]): F[List[List[String]]] =
      underlying.batchFindCategories(requests)

    def batchFindOrganizationsByServiceElement(requests: List[Request]): F[OrganizationDescription] =
      underlying.batchFindOrganizationsByServiceElement(requests)

    def batchFindServiceDescription(requests: List[Request]): F[Service] =
      underlying.batchFindServiceDescription(requests)

    def batchFindServiceSpecificationUrisByCategory(requests: List[Request]): F[Request] =
      underlying.batchFindServiceSpecificationUrisByCategory(requests)

    def batchVerifyCategory(requests: List[Request]): F[List[VerificationResult]] =
      underlying.batchVerifyCategory(requests)
  }
}
