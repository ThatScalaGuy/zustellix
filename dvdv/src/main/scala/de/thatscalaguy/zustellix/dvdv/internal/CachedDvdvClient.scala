package de.thatscalaguy.zustellix.dvdv.internal

import cats.Applicative
import cats.effect.{Async, Concurrent, Deferred, Ref, Resource}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import de.thatscalaguy.zustellix.dvdv.{CacheConfig, DvdvClient}
import de.thatscalaguy.zustellix.dvdv.model.*
import io.chrisdavenport.mules.{MemoryCache, TimeSpec}

import java.util.concurrent.CancellationException
import scala.concurrent.duration.FiniteDuration

object CachedDvdvClient {

  /** Wrap `underlying` in per-endpoint memoization per `cfg`.
   *
   *  Returned as a `Resource` because mules' single-immutable-map cache
   *  reclaims an expired entry only when its key is looked up again — maps
   *  keyed by caller-supplied fingerprints and organization keys would
   *  otherwise grow for the process lifetime. A supervised fiber therefore
   *  purges expired entries from all caches every
   *  [[CacheConfig.purgeInterval]]; the fiber is cancelled when the
   *  Resource is released.
   */
  def make[F[_]: Async](underlying: DvdvClient[F], cfg: CacheConfig): Resource[F, DvdvClient[F]] =
    if (!cfg.enabled) Resource.pure(underlying)
    else Resource.eval(mkCaches[F](cfg)).flatMap(cs => fromCaches(underlying, cs, cfg.purgeInterval))

  private[internal] final case class Caches[F[_]](
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
  ) {
    def purgeAll(using Applicative[F]): F[Unit] =
      List(
        categoriesC.purgeExpired,
        intermediariesC.purgeExpired,
        certByFpC.purgeExpired,
        urisByCategoryC.purgeExpired,
        authDescriptionC.purgeExpired,
        authDescriptionsC.purgeExpired,
        categoriesByFpKeyC.purgeExpired,
        serviceDescC.purgeExpired,
        orgsByServiceElementC.purgeExpired,
        verifyCategoryC.purgeExpired
      ).sequence_
  }

  private[internal] def mkCaches[F[_]: Async](cfg: CacheConfig): F[Caches[F]] =
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
    } yield Caches(
      categoriesC, intermediariesC, certByFpC, urisByCategoryC,
      authDescriptionC, authDescriptionsC, categoriesByFpKeyC, serviceDescC,
      orgsByServiceElementC, verifyCategoryC
    )

  // One in-flight map per cache: two caches share the Unit key type, so a
  // single shared map would collide across endpoints.
  private[internal] type InFlight[F[_], K, V] = Ref[F, Map[K, Deferred[F, Either[Throwable, V]]]]

  private[internal] final case class Flights[F[_]](
      categories:           InFlight[F, Unit, List[DirectoryOrganizationCategoryLevel1DTO]],
      intermediaries:       InFlight[F, Unit, List[SummaryServiceElementDTO]],
      certByFp:             InFlight[F, Fingerprint, Option[Certificate]],
      urisByCategory:       InFlight[F, Category, List[String]],
      authDescription:      InFlight[F, (Category, OrganizationKey), Option[OrganizationDescription]],
      authDescriptions:     InFlight[F, OrganizationKey, List[OrganizationDescription]],
      categoriesByFpKey:    InFlight[F, (Fingerprint, OrganizationKey), List[String]],
      serviceDesc:          InFlight[F, (OrganizationKey, String), Option[Service]],
      orgsByServiceElement: InFlight[F, (Either[ServiceElementType, String], ParameterType, String), List[LightweightOrganization]],
      verifyCategory:       InFlight[F, (Fingerprint, Category), VerificationResult]
  )

  private[internal] def mkFlights[F[_]: Concurrent]: F[Flights[F]] =
    for {
      categories           <- mkFlight[F, Unit, List[DirectoryOrganizationCategoryLevel1DTO]]
      intermediaries       <- mkFlight[F, Unit, List[SummaryServiceElementDTO]]
      certByFp             <- mkFlight[F, Fingerprint, Option[Certificate]]
      urisByCategory       <- mkFlight[F, Category, List[String]]
      authDescription      <- mkFlight[F, (Category, OrganizationKey), Option[OrganizationDescription]]
      authDescriptions     <- mkFlight[F, OrganizationKey, List[OrganizationDescription]]
      categoriesByFpKey    <- mkFlight[F, (Fingerprint, OrganizationKey), List[String]]
      serviceDesc          <- mkFlight[F, (OrganizationKey, String), Option[Service]]
      orgsByServiceElement <- mkFlight[F, (Either[ServiceElementType, String], ParameterType, String), List[LightweightOrganization]]
      verifyCategory       <- mkFlight[F, (Fingerprint, Category), VerificationResult]
    } yield Flights(
      categories, intermediaries, certByFp, urisByCategory,
      authDescription, authDescriptions, categoriesByFpKey, serviceDesc,
      orgsByServiceElement, verifyCategory
    )

  private def mkFlight[F[_]: Concurrent, K, V]: F[InFlight[F, K, V]] =
    Ref.of[F, Map[K, Deferred[F, Either[Throwable, V]]]](Map.empty)

  private[internal] def fromCaches[F[_]: Async](
      underlying: DvdvClient[F],
      caches: Caches[F],
      purgeInterval: FiniteDuration
  ): Resource[F, DvdvClient[F]] =
    Resource.eval(mkFlights[F]).flatMap { fl =>
      purgeLoop(purgeInterval, caches.purgeAll).background.as(new Impl[F](underlying, caches, fl))
    }

  // A failed purge pass never kills the loop; the error is dropped because
  // no logger is threaded into the cache layer.
  private def purgeLoop[F[_]: Async](interval: FiniteDuration, purgeAll: F[Unit]): F[Unit] =
    (Async[F].sleep(interval) *> purgeAll.attempt.void).foreverM

  private def mkCache[F[_]: Async, K, V](ttl: FiniteDuration): F[MemoryCache[F, K, V]] =
    MemoryCache.ofSingleImmutableMap[F, K, V](Some(TimeSpec.unsafeFromDuration(ttl)))

  // Single-flight per key: the first miss registers a Deferred and computes,
  // concurrent misses wait on it and rethrow the winner's error. Settle order
  // is load-bearing: complete first, so a failed insert cannot wedge waiters;
  // insert (success only) before dropping the flight, so a concurrent miss
  // finds either the flight or the cached value. Failures are never cached —
  // the entry is removed and the next caller retries.
  private def cached[F[_], K, V](
      c: MemoryCache[F, K, V],
      inFlight: InFlight[F, K, V],
      k: K
  )(compute: F[V])(using F: Concurrent[F]): F[V] =
    c.lookup(k).flatMap {
      case Some(v) => v.pure[F]
      case None =>
        F.uncancelable { poll =>
          F.deferred[Either[Throwable, V]].flatMap { d =>
            def settle(r: Either[Throwable, V]): F[Unit] =
              d.complete(r).void *>
                r.traverse_(v => c.insert(k, v)) *>
                inFlight.update(_ - k)

            inFlight.modify { m =>
              m.get(k) match {
                case Some(running) => (m, poll(running.get).rethrow)
                case None =>
                  val run = poll(compute).attempt
                    .onCancel(settle(Left(new CancellationException("cached computation was canceled"))))
                    .flatTap(settle)
                    .rethrow
                  (m.updated(k, d), run)
              }
            }.flatten
          }
        }
    }

  private final class Impl[F[_]: Concurrent](
      underlying: DvdvClient[F],
      c: Caches[F],
      f: Flights[F]
  ) extends DvdvClient[F] {

    def categories: F[List[DirectoryOrganizationCategoryLevel1DTO]] =
      cached(c.categoriesC, f.categories, ())(underlying.categories)

    def intermediaries: F[List[SummaryServiceElementDTO]] =
      cached(c.intermediariesC, f.intermediaries, ())(underlying.intermediaries)

    def serviceVersion: F[ServiceVersion] =
      underlying.serviceVersion // not cached

    def findAuthorityDescription(category: Category, organizationKey: OrganizationKey): F[Option[OrganizationDescription]] =
      cached(c.authDescriptionC, f.authDescription, (category, organizationKey))(
        underlying.findAuthorityDescription(category, organizationKey)
      )

    def findAuthorityDescriptions(organizationKey: OrganizationKey): F[List[OrganizationDescription]] =
      cached(c.authDescriptionsC, f.authDescriptions, organizationKey)(
        underlying.findAuthorityDescriptions(organizationKey)
      )

    def findCategories(fingerPrint: Fingerprint, organizationKey: OrganizationKey): F[List[String]] =
      cached(c.categoriesByFpKeyC, f.categoriesByFpKey, (fingerPrint, organizationKey))(
        underlying.findCategories(fingerPrint, organizationKey)
      )

    def findCertificateByFingerprint(fingerPrint: Fingerprint): F[Option[Certificate]] =
      cached(c.certByFpC, f.certByFp, fingerPrint)(
        underlying.findCertificateByFingerprint(fingerPrint)
      )

    def findOrganizationsByServiceElement(
        serviceElementType: ServiceElementType,
        parameterType: ParameterType,
        parameterValue: String
    ): F[List[LightweightOrganization]] =
      cached(c.orgsByServiceElementC, f.orgsByServiceElement, (Left(serviceElementType), parameterType, parameterValue))(
        underlying.findOrganizationsByServiceElement(serviceElementType, parameterType, parameterValue)
      )

    def findOrganizationsByServiceElement(
        customServiceElementType: String,
        parameterType: ParameterType,
        parameterValue: String
    ): F[List[LightweightOrganization]] =
      cached(c.orgsByServiceElementC, f.orgsByServiceElement, (Right(customServiceElementType), parameterType, parameterValue))(
        underlying.findOrganizationsByServiceElement(customServiceElementType, parameterType, parameterValue)
      )

    def findServiceDescription(organizationKey: OrganizationKey, serviceSpecificationUri: String): F[Option[Service]] =
      cached(c.serviceDescC, f.serviceDesc, (organizationKey, serviceSpecificationUri))(
        underlying.findServiceDescription(organizationKey, serviceSpecificationUri)
      )

    def findServiceSpecificationUrisByCategory(category: Category): F[List[String]] =
      cached(c.urisByCategoryC, f.urisByCategory, category)(
        underlying.findServiceSpecificationUrisByCategory(category)
      )

    def verifyCategory(fingerPrint: Fingerprint, category: Category): F[VerificationResult] =
      cached(c.verifyCategoryC, f.verifyCategory, (fingerPrint, category))(
        underlying.verifyCategory(fingerPrint, category)
      )

    // Batch endpoints — not cached; delegate.
    def batchFindAuthorityDescription(requests: List[Request]): F[List[Option[OrganizationDescription]]] =
      underlying.batchFindAuthorityDescription(requests)

    def batchFindCategories(requests: List[Request]): F[List[List[String]]] =
      underlying.batchFindCategories(requests)

    def batchFindOrganizationsByServiceElement(requests: List[Request]): F[List[List[LightweightOrganization]]] =
      underlying.batchFindOrganizationsByServiceElement(requests)

    def batchFindServiceDescription(requests: List[Request]): F[List[Option[Service]]] =
      underlying.batchFindServiceDescription(requests)

    def batchFindServiceSpecificationUrisByCategory(requests: List[Request]): F[List[List[String]]] =
      underlying.batchFindServiceSpecificationUrisByCategory(requests)

    def batchVerifyCategory(requests: List[Request]): F[List[VerificationResult]] =
      underlying.batchVerifyCategory(requests)
  }
}
