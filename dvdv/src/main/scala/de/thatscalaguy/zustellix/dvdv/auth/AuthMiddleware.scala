package de.thatscalaguy.zustellix.dvdv.auth

import cats.effect.Concurrent
import cats.effect.kernel.Resource
import cats.effect.std.NonEmptyHotswap
import cats.syntax.all.*
import org.http4s.{Header, Response, Status}
import org.http4s.client.Client
import org.typelevel.ci.CIString

object AuthMiddleware {

  /** Wraps a Client so every outgoing request carries `Authorization: EmbeddedBearer <token>`.
   *  On a 401, the previous response is released, the exact token that was sent is invalidated
   *  (stale-aware: a token refreshed concurrently by another fiber is left untouched), and the
   *  request is retried exactly once. The in-flight response is held by a hotswap whose
   *  finalizer lives in the caller's resource scope, so cancellation cannot leak it.
   */
  def apply[F[_]: Concurrent](tokens: TokenManager[F])(underlying: Client[F]): Client[F] =
    Client[F] { req =>
      NonEmptyHotswap.empty[F, Response[F]].flatMap { hotswap =>
        Resource.eval {
          def run(canRetry: Boolean): F[Response[F]] =
            tokens.bearer.flatMap { tok =>
              val authed = req.putHeaders(Header.Raw(CIString("Authorization"), s"EmbeddedBearer $tok"))
              swapIn(hotswap, underlying.run(authed)).flatMap { resp =>
                if (resp.status == Status.Unauthorized && canRetry)
                  // release the 401's pool slot before the retry (which may
                  // itself fetch a token through the same underlying client)
                  hotswap.clear *> tokens.invalidate(tok) *> run(canRetry = false)
                else
                  resp.pure[F]
              }
            }
          run(canRetry = true)
        }
      }
    }

  /** Swaps `next` into the hotswap and hands back the value it acquired. The
   *  previous entry is only released after `next` is acquired, so callers
   *  `clear` first where the old slot must be freed before the next attempt.
   */
  private def swapIn[F[_]: Concurrent, A](
      hotswap: NonEmptyHotswap[F, Option[A]],
      next: Resource[F, A]
  ): F[A] =
    hotswap.swap(next.map(_.some)) *>
      hotswap.getOpt.use(_.liftTo[F](new IllegalStateException("hotswap empty after swap")))
}
