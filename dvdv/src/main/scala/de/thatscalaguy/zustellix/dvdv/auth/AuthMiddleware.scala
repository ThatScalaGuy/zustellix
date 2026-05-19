package de.thatscalaguy.zustellix.dvdv.auth

import cats.effect.MonadCancelThrow
import cats.effect.kernel.Resource
import cats.syntax.all.*
import org.http4s.{Header, Response, Status}
import org.http4s.client.Client
import org.typelevel.ci.CIString

object AuthMiddleware {

  /** Wraps a Client so every outgoing request carries `Authorization: EmbeddedBearer <token>`.
   *  On a 401, the previous response is released, the token is invalidated, and the request
   *  is retried exactly once.
   */
  def apply[F[_]: MonadCancelThrow](tokens: TokenManager[F])(underlying: Client[F]): Client[F] =
    Client[F] { req =>
      Resource.suspend {
        def run(canRetry: Boolean): F[Resource[F, Response[F]]] =
          tokens.bearer.flatMap { tok =>
            val authed = req.putHeaders(Header.Raw(CIString("Authorization"), s"EmbeddedBearer $tok"))
            underlying.run(authed).allocated.flatMap { case (resp, release) =>
              if (resp.status == Status.Unauthorized && canRetry)
                release *> tokens.invalidate *> run(canRetry = false)
              else
                MonadCancelThrow[F].pure(Resource.make(MonadCancelThrow[F].pure(resp))(_ => release))
            }
          }
        run(canRetry = true)
      }
    }
}
