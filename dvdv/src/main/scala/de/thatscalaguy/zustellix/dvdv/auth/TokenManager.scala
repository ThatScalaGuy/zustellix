package de.thatscalaguy.zustellix.dvdv.auth

import cats.effect.{Async, Clock, Ref}
import cats.effect.std.Mutex
import cats.syntax.all.*
import de.thatscalaguy.zustellix.utils.cert.LoadedCert
import de.thatscalaguy.zustellix.dvdv.model.AccessTokenResponse
import de.thatscalaguy.zustellix.dvdv.{DvdvConfig, DvdvError}
import de.thatscalaguy.zustellix.dvdv.model.Problem
import io.circe.parser.decode
import org.http4s.{Method, Request, Status, Uri, UrlForm}
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.http4s.MediaType
import org.typelevel.log4cats.LoggerFactory

import java.time.Instant

trait TokenManager[F[_]] {
  def bearer: F[String]

  /** Drops the cached token only if it still equals `stale` — the token that
   *  provoked the 401. A token refreshed concurrently by another fiber does not
   *  match and survives, so parallel 401s cannot stampede the token endpoint.
   */
  def invalidate(stale: String): F[Unit]
}

object TokenManager {

  /** `refreshAt` is the instant from which `bearer` refreshes — computed once
   *  at acquisition, with the refresh skew and its clamp already applied.
   */
  private final case class CachedToken(value: String, refreshAt: Instant)

  /** `resolve` is evaluated on every token acquisition (once per refresh, cheap),
   *  so a rotated signing cert — e.g. from a hot-reloading `CertManager` — is
   *  picked up without rebuilding the client. Pass a pure/memoized value for a
   *  cert that is fixed for the lifetime of the manager.
   *
   *  `tokenEndpoint` is likewise evaluated once per token acquisition, so a
   *  failed-over active server flows into both the wire target of the POST and
   *  the `aud` claim of the `client_assertion` on the next refresh.
   *
   *  A token is refreshed `max(ttl - tokenRefreshSkew, ttl / 2)` after it was
   *  minted, with the TTL taken from the response's `expires_in` and falling
   *  back to `config.defaultTokenTtl` — the clamp keeps a usable cache window
   *  even when the skew exceeds the TTL.
   */
  def make[F[_]: Async: LoggerFactory](
      client: Client[F],
      config: DvdvConfig,
      resolve: F[LoadedCert],
      tokenEndpoint: F[Uri]
  ): F[TokenManager[F]] =
    for {
      ref   <- Ref.of[F, Option[CachedToken]](None)
      mutex <- Mutex[F]
    } yield new Impl[F](client, config, resolve, tokenEndpoint, ref, mutex)

  private final class Impl[F[_]: Async: LoggerFactory](
      client: Client[F],
      config: DvdvConfig,
      resolve: F[LoadedCert],
      tokenEndpoint: F[Uri],
      state: Ref[F, Option[CachedToken]],
      mutex: Mutex[F]
  ) extends TokenManager[F] {

    private val skew = config.tokenRefreshSkew
    private val log  = LoggerFactory[F].getLogger

    def bearer: F[String] =
      Clock[F].realTimeInstant.flatMap { now =>
        state.get.flatMap {
          case Some(t) if t.refreshAt.isAfter(now) =>
            Async[F].pure(t.value)
          case _ =>
            refresh
        }
      }

    def invalidate(stale: String): F[Unit] =
      state.update {
        case Some(t) if t.value == stale => None
        case other                       => other
      }

    private def refresh: F[String] =
      mutex.lock.surround {
        Clock[F].realTimeInstant.flatMap { now =>
          state.get.flatMap {
            case Some(t) if t.refreshAt.isAfter(now) =>
              Async[F].pure(t.value)
            case _ =>
              acquire(now).flatTap(t => state.set(Some(t))).map(_.value)
          }
        }
      }

    private def acquire(now: Instant): F[CachedToken] =
      for {
        loaded   <- resolve
        endpoint <- tokenEndpoint
        jwt      <- JwtFactory.make[F](config, loaded, endpoint)
        form = UrlForm(
                 "grant_type"            -> "client_credentials",
                 "client_assertion_type" -> "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
                 "client_assertion"      -> jwt
               )
        req  = Request[F](Method.POST, endpoint)
                 .withEntity(form)
                 .putHeaders(`Content-Type`(MediaType.application.`x-www-form-urlencoded`))
        token <- client.run(req).use { resp =>
                   resp.status match {
                     case Status.Ok =>
                       resp.bodyText.compile.string.flatMap { body =>
                         Async[F].fromEither(
                           decode[AccessTokenResponse](body).left.map(e => DvdvError.DecodingError("token", e))
                         )
                       }
                     case Status.Unauthorized =>
                       resp.bodyText.compile.string.flatMap { body =>
                         val p = decode[Problem](body).getOrElse(Problem(detail = Some(body)))
                         Async[F].raiseError[AccessTokenResponse](DvdvError.AuthenticationError(p))
                       }
                     case other =>
                       resp.bodyText.compile.string.flatMap { body =>
                         val problemOpt = decode[Problem](body).toOption
                         val err =
                           if (other.code >= 500) DvdvError.ServerError(other.code, body, problemOpt)
                           else DvdvError.Unexpected(other.code, body, problemOpt)
                         Async[F].raiseError[AccessTokenResponse](err)
                       }
                   }
                 }
        cached <- toCached(now, token)
      } yield cached

    /** Computes the refresh point `max(ttl - skew, ttl / 2)` after the mint
     *  instant and warns when the window degenerates: a window of zero means
     *  every request performs a token POST (serialized under the mutex), and
     *  a skew >= TTL means the clamp is carrying the cache.
     */
    private def toCached(now: Instant, token: AccessTokenResponse): F[CachedToken] = {
      val ttl    = token.expires_in.getOrElse(config.defaultTokenTtl.toSeconds)
      val skewS  = skew.toSeconds
      val window = (ttl - skewS).max(ttl / 2)
      val warn =
        if (window <= 0)
          log.warn(
            s"token TTL of ${ttl}s (expires_in, or defaultTokenTtl when absent) leaves no " +
              "refresh window — every request will perform a token POST"
          )
        else if (skewS >= ttl)
          log.warn(
            s"tokenRefreshSkew (${skewS}s) is not below the token TTL (${ttl}s) — " +
              s"refresh point clamped to half the TTL (${window}s)"
          )
        else Async[F].unit
      warn.as(CachedToken(token.access_token, now.plusSeconds(window)))
    }
  }
}
