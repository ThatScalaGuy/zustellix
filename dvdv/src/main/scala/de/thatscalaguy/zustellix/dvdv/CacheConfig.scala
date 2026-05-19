package de.thatscalaguy.zustellix.dvdv

import scala.concurrent.duration.*

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
    enabled: Boolean = true
)

object CacheConfig {
  val disabled: CacheConfig = CacheConfig(enabled = false)
}
