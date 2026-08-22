package de.thatscalaguy.zustellix.dvdv

import scala.concurrent.duration.*

/** Retry policy for transient failures, applied outside the failover
 *  middleware — one attempt is one full failover pass, and a retry re-enters
 *  failover (and auth) from the top.
 *
 *  Only idempotent GETs are retried (the batch endpoints are POSTs and are
 *  never retried), on 429, 500, 502, 503, 504 and transport exceptions.
 *  Retry `n` is delayed by `baseDelay * 2^(n-1)` capped at [[maxDelay]], then
 *  jittered uniformly into `[(1 - jitter) * d, d]` with [[jitter]] clamped to
 *  `[0, 1]` (`jitter = 0` disables jitter). The jitter draw is a pure
 *  pseudo-random seeded from the request URI and attempt number —
 *  deterministic for testability, so identical concurrent requests share a
 *  jitter value. A `Retry-After` response header larger than the computed
 *  delay wins (uncapped by [[maxDelay]]); the whole call stays bounded by
 *  [[DvdvConfig.totalDeadline]].
 *
 *  `maxRetries = 0` disables retrying entirely (see [[RetryConfig.disabled]]).
 */
final case class RetryConfig(
    maxRetries: Int = 3,
    baseDelay: FiniteDuration = 500.millis,
    maxDelay: FiniteDuration = 10.seconds,
    jitter: Double = 0.5
)

object RetryConfig {
  val disabled: RetryConfig = RetryConfig(maxRetries = 0)
}
