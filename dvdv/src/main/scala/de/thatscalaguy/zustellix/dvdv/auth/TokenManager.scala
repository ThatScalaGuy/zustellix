package de.thatscalaguy.zustellix.dvdv.auth

import cats.effect.{Async, Clock, Ref}
import cats.effect.std.Mutex
import cats.syntax.all.*
import de.thatscalaguy.zustellix.utils.cert.LoadedCert
import de.thatscalaguy.zustellix.dvdv.model.AccessTokenResponse
import de.thatscalaguy.zustellix.dvdv.{DvdvConfig, DvdvError}
import de.thatscalaguy.zustellix.dvdv.model.Problem
import io.circe.parser.decode
import org.http4s.{Method, Request, Status, UrlForm}
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.http4s.MediaType

import java.time.Instant

trait TokenManager[F[_]] {
  def bearer: F[String]
  def invalidate: F[Unit]
}

object TokenManager {

  private final case class CachedToken(value: String, notAfter: Instant)

  def make[F[_]: Async](
      client: Client[F],
      config: DvdvConfig,
      loaded: LoadedCert
  ): F[TokenManager[F]] =
    for {
      ref   <- Ref.of[F, Option[CachedToken]](None)
      mutex <- Mutex[F]
    } yield new Impl[F](client, config, loaded, ref, mutex)

  private final class Impl[F[_]: Async](
      client: Client[F],
      config: DvdvConfig,
      loaded: LoadedCert,
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

    def invalidate: F[Unit] =
      state.set(None)

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
        jwt <- JwtFactory.make[F](config, loaded)
        form = UrlForm(
                 "grant_type"            -> "client_credentials",
                 "client_assertion_type" -> "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
                 "client_assertion"      -> jwt
               )
        req  = Request[F](Method.POST, config.tokenUri)
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
                         Async[F].raiseError[AccessTokenResponse](DvdvError.Unexpected(other.code, body))
                       }
                   }
                 }
      } yield {
        val ttl = token.expires_in.getOrElse(config.jwtLifetime.toSeconds)
        CachedToken(token.access_token, now.plusSeconds(ttl))
      }
  }
}
