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

package de.thatscalaguy.zustellix.dvdv.internal

import de.thatscalaguy.zustellix.dvdv.RetryConfig
import org.http4s.{Method, Request, Response, Status, Uri, WaitQueueTimeoutException}
import org.http4s.client.middleware.RetryPolicy

import scala.concurrent.duration.*
import scala.util.Random

/** Builds the policy the http4s `Retry` middleware runs a [[RetryConfig]]
 *  with. `Retry-After` handling lives in the middleware itself: it sleeps
 *  `max(header delay, policy delay)` — so a `Retry-After` larger than the
 *  backoff wins, uncapped by [[RetryConfig.maxDelay]], bounded overall by
 *  `DvdvConfig.totalDeadline`.
 */
object RetrySupport {

  private val retriableStatuses: Set[Status] =
    Set(
      Status.TooManyRequests,
      Status.InternalServerError,
      Status.BadGateway,
      Status.ServiceUnavailable,
      Status.GatewayTimeout
    )

  /** The middleware calls the policy with `attempts` starting at 1 for the
   *  first attempt's outcome, so `attempts > maxRetries` yields exactly
   *  [[RetryConfig.maxRetries]] retries.
   */
  def policy[F[_]](cfg: RetryConfig): RetryPolicy[F] =
    (req, result, attempts) =>
      if (attempts > cfg.maxRetries || !retriable(req, result)) None
      else Some(delayFor(cfg, req.uri, attempts))

  private def retriable[F[_]](req: Request[F], result: Either[Throwable, Response[F]]): Boolean =
    req.method == Method.GET && (result match {
      case Right(resp) => retriableStatuses(resp.status)
      // Retrying a full connection wait queue makes the congestion worse
      // (matches http4s' defaultRetriable).
      case Left(WaitQueueTimeoutException) => false
      case Left(_)                         => true
    })

  /** `baseDelay * 2^(attempt-1)` capped at `maxDelay`, then jittered
   *  uniformly into `[(1 - jitter) * d, d]` with `jitter` clamped to
   *  `[0, 1]`. The draw is a pure pseudo-random seeded from the request URI
   *  and attempt number — deterministic for testability, at the cost of
   *  identical concurrent requests sharing a jitter value.
   */
  private def delayFor(cfg: RetryConfig, uri: Uri, attempt: Int): FiniteDuration = {
    val capped =
      math.min(cfg.baseDelay.toNanos.toDouble * math.pow(2.0, (attempt - 1).toDouble), cfg.maxDelay.toNanos.toDouble)
    val jitter = math.min(1.0, math.max(0.0, cfg.jitter))
    val seed   = (uri.renderString.hashCode.toLong << 32) ^ attempt.toLong
    val factor = 1.0 - jitter * new Random(seed).nextDouble()
    FiniteDuration((capped * factor).toLong, NANOSECONDS)
  }
}
