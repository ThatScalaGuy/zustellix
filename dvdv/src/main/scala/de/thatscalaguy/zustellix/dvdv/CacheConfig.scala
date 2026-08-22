package de.thatscalaguy.zustellix.dvdv

import scala.concurrent.duration.*

/** Per-endpoint TTLs for the [[DvdvClient]] response caches.
 *
 *  `purgeInterval` is the interval at which a background fiber, scoped to
 *  the client `Resource`, purges expired entries from all caches — bounding
 *  memory between accesses, since an expired entry is otherwise only
 *  reclaimed when its key is looked up again.
 */
final case class CacheConfig(
    categoriesTtl: FiniteDuration = 1.hour,
    intermediariesTtl: FiniteDuration = 1.hour,
    findCertificateByFingerprintTtl: FiniteDuration = 1.hour,
    findServiceSpecificationUrisByCategoryTtl: FiniteDuration = 1.hour,
    findAuthorityDescriptionTtl: FiniteDuration = 10.minutes,
    findAuthorityDescriptionsTtl: FiniteDuration = 10.minutes,
    findCategoriesTtl: FiniteDuration = 10.minutes,
    findServiceDescriptionTtl: FiniteDuration = 10.minutes,
    findOrganizationsByServiceElementTtl: FiniteDuration = 10.minutes,
    verifyCategoryTtl: FiniteDuration = 5.minutes,
    purgeInterval: FiniteDuration = 1.minute,
    enabled: Boolean = true
)

object CacheConfig {
  val disabled: CacheConfig = CacheConfig(enabled = false)
}
