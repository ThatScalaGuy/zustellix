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

  private final case class CachedToken(value: String, notAfter: Instant)

  /** `resolve` is evaluated on every token acquisition (once per refresh, cheap),
   *  so a rotated signing cert — e.g. from a hot-reloading `CertManager` — is
   *  picked up without rebuilding the client. Pass a pure/memoized value for a
   *  cert that is fixed for the lifetime of the manager.
   *
   *  `tokenEndpoint` is likewise evaluated once per token acquisition, so a
   *  failed-over active server flows into both the wire target of the POST and
   *  the `aud` claim of the `client_assertion` on the next refresh.
   */
  def make[F[_]: Async](
      client: Client[F],
      config: DvdvConfig,
      resolve: F[LoadedCert],
      tokenEndpoint: F[Uri]
  ): F[TokenManager[F]] =
    for {
      ref   <- Ref.of[F, Option[CachedToken]](None)
      mutex <- Mutex[F]
    } yield new Impl[F](client, config, resolve, tokenEndpoint, ref, mutex)

  private final class Impl[F[_]: Async](
      client: Client[F],
      config: DvdvConfig,
      resolve: F[LoadedCert],
      tokenEndpoint: F[Uri],
      state: Ref[F, Option[CachedToken]],
      mutex: Mutex[F]
  ) extends TokenManager[F] {

    private val skew = config.tokenRefreshSkew

    def bearer: F[String] =
      Clock[F].realTimeInstant.flatMap { now =>
        state.get.flatMap {
          case Some(t) if t.notAfter.minusSeconds(skew.toSeconds).isAfter(now) =>
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
            case Some(t) if t.notAfter.minusSeconds(skew.toSeconds).isAfter(now) =>
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
                           decode[AccessTokenResponse](body).left.map(e => DvdvError.TransportError(e))
                         )
                       }
                     case Status.Unauthorized =>
                       resp.bodyText.compile.string.flatMap { body =>
                         val p = decode[Problem](body).getOrElse(Problem(detail = Some(body)))
                         Async[F].raiseError[AccessTokenResponse](DvdvError.AuthenticationError(p))
                       }
                     case other =>
                       resp.bodyText.compile.string.flatMap { body =>
                         val err =
                           if (other.code >= 500) DvdvError.ServerError(other.code, body)
                           else DvdvError.Unexpected(other.code, body)
                         Async[F].raiseError[AccessTokenResponse](err)
                       }
                   }
                 }
      } yield {
        val ttl = token.expires_in.getOrElse(config.jwtLifetime.toSeconds)
        CachedToken(token.access_token, now.plusSeconds(ttl))
      }
  }
}
