/*
 * Copyright 2026 ThatScalaGuy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.thatscalaguy.zustellix.dvdv

import scala.concurrent.duration.*

/** Per-endpoint TTLs for the [[DvdvClient]] response caches.
 *
 *  `negativeTtl` caps how long a `None` result of the Option-returning
 *  lookups (`findAuthorityDescription`, `findCertificateByFingerprint`,
 *  `findServiceDescription`) is served from cache — effectively
 *  `min(negativeTtl, endpoint TTL)`, so a miss is never cached longer than
 *  a hit and a newly onboarded authority or certificate becomes visible
 *  without waiting out the full endpoint TTL. Must be positive, like every
 *  other TTL here.
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
    negativeTtl: FiniteDuration = 5.minutes,
    purgeInterval: FiniteDuration = 1.minute,
    enabled: Boolean = true
)

object CacheConfig {
  val disabled: CacheConfig = CacheConfig(enabled = false)
}
