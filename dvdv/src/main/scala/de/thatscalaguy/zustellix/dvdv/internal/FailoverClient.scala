package de.thatscalaguy.zustellix.dvdv.internal

import cats.data.NonEmptyList
import cats.effect.{Async, Clock, Ref}
import cats.effect.kernel.Resource
import cats.syntax.all.*
import org.http4s.{Response, Uri}
import org.http4s.client.Client

import scala.concurrent.duration.FiniteDuration

/** Sticky multi-server failover middleware, mirroring the DVDV2 reference
 *  (`DVDV2RestManager`). `servers` index 0 is the primary; the rest are
 *  failover targets tried in order.
 *
 *  Failover triggers on a response status `>= 500` or a connection/transport
 *  exception from the underlying client. A 2xx/3xx/4xx (including 401/404) is a
 *  definitive answer from that server and is returned as-is.
 *
 *  Once failed over, the active server is sticky. After `recoverAfter` elapses,
 *  the next request restarts from the primary; if the primary answers, the
 *  active server resets to index 0.
 */
object FailoverClient {

  private final case class State(activeIndex: Int, nextRecoverAt: Option[FiniteDuration])

  def make[F[_]: Async](
      servers: NonEmptyList[Uri],
      recoverAfter: FiniteDuration
  ): F[Client[F] => Client[F]] =
    Ref.of[F, State](State(0, None)).map(state => underlying => build(servers, recoverAfter, state)(underlying))

  /** Permutation that puts the active server first, then the remaining servers
   *  in their normal order. During a recovery attempt the natural order is used.
   */
  private def pickServerIndex(i: Int, activeIndex: Int, attemptRecover: Boolean): Int =
    if (attemptRecover) i
    else if (i == 0) activeIndex
    else if (i <= activeIndex) i - 1
    else i

  private def build[F[_]: Async](
      servers: NonEmptyList[Uri],
      recoverAfter: FiniteDuration,
      state: Ref[F, State]
  )(underlying: Client[F]): Client[F] = {
    val serverList = servers.toList
    val noOfServers = serverList.size

    Client[F] { req =>
      Resource.suspend {
        Clock[F].realTime.flatMap { now =>
          // Decide once per request whether recovery is due, advancing the
          // recover deadline as a side effect (matches shallAttemptRecover).
          state.modify { s =>
            val due = s.activeIndex != 0 && s.nextRecoverAt.forall(now >= _)
            val next = if (due) s.copy(nextRecoverAt = Some(now + recoverAfter)) else s
            (next, (due, s.activeIndex))
          }.flatMap { case (attemptRecover, activeIndex) =>
            // Try the candidate at loop position `i`; recurse to the next on a
            // 5xx or transport failure. The last attempt's outcome is final.
            def go(i: Int): F[Resource[F, Response[F]]] = {
              val serverIndex = pickServerIndex(i, activeIndex, attemptRecover)
              val srv         = serverList(serverIndex)
              val routed      = req.withUri(req.uri.copy(scheme = srv.scheme, authority = srv.authority))
              val isLast      = i == noOfServers - 1

              underlying.run(routed).allocated.attempt.flatMap {
                case Right((resp, release)) if resp.status.code >= 500 =>
                  if (isLast)
                    markFailedOver(state, serverIndex, now + recoverAfter)
                      .as(Resource.make(Async[F].pure(resp))(_ => release))
                  else release *> go(i + 1)
                case Right((resp, release)) =>
                  markAnswered(state, serverIndex, now + recoverAfter)
                    .as(Resource.make(Async[F].pure(resp))(_ => release))
                case Left(err) =>
                  if (isLast) markFailedOver(state, serverIndex, now + recoverAfter) *> Async[F].raiseError(err)
                  else go(i + 1)
              }
            }

            go(0)
          }
        }
      }
    }
  }

  /** A server answered. If it is the primary, reset to it and clear the recover
   *  timer (recovered). If it is a different non-primary server than the current
   *  active one, switch and arm the recover timer. If it is already the active
   *  server, leave the state (and timer) untouched.
   */
  private def markAnswered[F[_]](
      state: Ref[F, FailoverClient.State],
      serverIndex: Int,
      nextRecoverAt: FiniteDuration
  ): F[Unit] =
    state.update { s =>
      if (serverIndex == 0) State(0, None)
      else if (serverIndex == s.activeIndex) s
      else State(serverIndex, Some(nextRecoverAt))
    }

  /** All servers exhausted: stick to the last one tried and arm the recover timer. */
  private def markFailedOver[F[_]](
      state: Ref[F, FailoverClient.State],
      serverIndex: Int,
      nextRecoverAt: FiniteDuration
  ): F[Unit] =
    state.set(State(serverIndex, Some(nextRecoverAt)))
}
