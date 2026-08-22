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
    /** How far ahead of token expiry a refresh is triggered. The refresh point
     *  is clamped to at least half the token TTL, so a skew >= TTL cannot
     *  force a token POST on every request.
     */
    tokenRefreshSkew: FiniteDuration = 30.seconds,
    /** Token lifetime assumed when the token response carries no `expires_in`.
     *  Real DVDV tokens are typically day-valid, but a token without a stated
     *  lifetime is deliberately trusted only briefly — an expired guess
     *  self-heals cheaply via the 401 → invalidate → retry path.
     */
    defaultTokenTtl: FiniteDuration = 5.minutes,
    /** Bounds each HTTP request attempt (acquisition of the response). Applies
     *  to all constructors: the Ember builders via `withTimeout`, and
     *  `fromClient` via a timeout wrapper around the provided client. With
     *  [[failoverServers]] configured, each per-server attempt gets its own
     *  budget.
     */
    requestTimeout: FiniteDuration = 30.seconds,
    /** Outermost bound on one logical directory call: covers all failover
     *  passes, retries, backoff/`Retry-After` sleeps and token fetches, and
     *  raises `java.util.concurrent.TimeoutException` when exceeded. Separate
     *  from the per-attempt [[requestTimeout]]. `None` disables the bound.
     */
    totalDeadline: Option[FiniteDuration] = Some(5.minutes),
    /** Retry policy for transient failures (429/transient 5xx/transport
     *  errors on idempotent GETs); each retry re-enters failover from the
     *  top. See [[RetryConfig]]; disable via [[RetryConfig.disabled]].
     */
    retryConfig: RetryConfig = RetryConfig(),
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
