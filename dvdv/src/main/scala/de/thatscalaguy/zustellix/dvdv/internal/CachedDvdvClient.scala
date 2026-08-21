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
        certByFpC                            <- mkCache[F, Fingerprint, Option[Certificate]](cfg.findCertificateByFingerprintTtl)
        urisByCategoryC                      <- mkCache[F, Category, List[String]](cfg.findServiceSpecificationUrisByCategoryTtl)
        authDescriptionC                     <- mkCache[F, (Category, OrganizationKey), Option[OrganizationDescription]](cfg.findAuthorityDescriptionTtl)
        authDescriptionsC                    <- mkCache[F, OrganizationKey, List[OrganizationDescription]](cfg.findAuthorityDescriptionsTtl)
        categoriesByFpKeyC                   <- mkCache[F, (Fingerprint, OrganizationKey), List[String]](cfg.findCategoriesTtl)
        serviceDescC                         <- mkCache[F, (OrganizationKey, String), Option[Service]](cfg.findServiceDescriptionTtl)
        orgsByServiceElementC                <- mkCache[F, (Either[ServiceElementType, String], ParameterType, String), List[LightweightOrganization]](cfg.findOrganizationsByServiceElementTtl)
        verifyCategoryC                      <- mkCache[F, (Fingerprint, Category), VerificationResult](cfg.verifyCategoryTtl)
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
      certByFpC:             MemoryCache[F, Fingerprint, Option[Certificate]],
      urisByCategoryC:       MemoryCache[F, Category, List[String]],
      authDescriptionC:      MemoryCache[F, (Category, OrganizationKey), Option[OrganizationDescription]],
      authDescriptionsC:     MemoryCache[F, OrganizationKey, List[OrganizationDescription]],
      categoriesByFpKeyC:    MemoryCache[F, (Fingerprint, OrganizationKey), List[String]],
      serviceDescC:          MemoryCache[F, (OrganizationKey, String), Option[Service]],
      orgsByServiceElementC: MemoryCache[F, (Either[ServiceElementType, String], ParameterType, String), List[LightweightOrganization]],
      verifyCategoryC:       MemoryCache[F, (Fingerprint, Category), VerificationResult]
  ) extends DvdvClient[F] {

    def categories: F[List[DirectoryOrganizationCategoryLevel1DTO]] =
      cached(categoriesC, ())(underlying.categories)

    def intermediaries: F[List[SummaryServiceElementDTO]] =
      cached(intermediariesC, ())(underlying.intermediaries)

    def serviceVersion: F[ServiceVersion] =
      underlying.serviceVersion // not cached

    def findAuthorityDescription(category: Category, organizationKey: OrganizationKey): F[Option[OrganizationDescription]] =
      cached(authDescriptionC, (category, organizationKey))(
        underlying.findAuthorityDescription(category, organizationKey)
      )

    def findAuthorityDescriptions(organizationKey: OrganizationKey): F[List[OrganizationDescription]] =
      cached(authDescriptionsC, organizationKey)(
        underlying.findAuthorityDescriptions(organizationKey)
      )

    def findCategories(fingerPrint: Fingerprint, organizationKey: OrganizationKey): F[List[String]] =
      cached(categoriesByFpKeyC, (fingerPrint, organizationKey))(
        underlying.findCategories(fingerPrint, organizationKey)
      )

    def findCertificateByFingerprint(fingerPrint: Fingerprint): F[Option[Certificate]] =
      cached(certByFpC, fingerPrint)(
        underlying.findCertificateByFingerprint(fingerPrint)
      )

    def findOrganizationsByServiceElement(
        serviceElementType: ServiceElementType,
        parameterType: ParameterType,
        parameterValue: String
    ): F[List[LightweightOrganization]] =
      cached(orgsByServiceElementC, (Left(serviceElementType), parameterType, parameterValue))(
        underlying.findOrganizationsByServiceElement(serviceElementType, parameterType, parameterValue)
      )

    def findOrganizationsByServiceElement(
        customServiceElementType: String,
        parameterType: ParameterType,
        parameterValue: String
    ): F[List[LightweightOrganization]] =
      cached(orgsByServiceElementC, (Right(customServiceElementType), parameterType, parameterValue))(
        underlying.findOrganizationsByServiceElement(customServiceElementType, parameterType, parameterValue)
      )

    def findServiceDescription(organizationKey: OrganizationKey, serviceSpecificationUri: String): F[Option[Service]] =
      cached(serviceDescC, (organizationKey, serviceSpecificationUri))(
        underlying.findServiceDescription(organizationKey, serviceSpecificationUri)
      )

    def findServiceSpecificationUrisByCategory(category: Category): F[List[String]] =
      cached(urisByCategoryC, category)(
        underlying.findServiceSpecificationUrisByCategory(category)
      )

    def verifyCategory(fingerPrint: Fingerprint, category: Category): F[VerificationResult] =
      cached(verifyCategoryC, (fingerPrint, category))(
        underlying.verifyCategory(fingerPrint, category)
      )

    // Batch endpoints — not cached; delegate.
    def batchFindAuthorityDescription(requests: List[Request]): F[List[OrganizationDescription]] =
      underlying.batchFindAuthorityDescription(requests)

    def batchFindCategories(requests: List[Request]): F[List[List[String]]] =
      underlying.batchFindCategories(requests)

    def batchFindOrganizationsByServiceElement(requests: List[Request]): F[List[List[LightweightOrganization]]] =
      underlying.batchFindOrganizationsByServiceElement(requests)

    def batchFindServiceDescription(requests: List[Request]): F[List[Service]] =
      underlying.batchFindServiceDescription(requests)

    def batchFindServiceSpecificationUrisByCategory(requests: List[Request]): F[List[List[String]]] =
      underlying.batchFindServiceSpecificationUrisByCategory(requests)

    def batchVerifyCategory(requests: List[Request]): F[List[VerificationResult]] =
      underlying.batchVerifyCategory(requests)
  }
}
