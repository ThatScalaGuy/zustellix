package de.thatscalaguy.zustellix.dvdv.internal

import cats.data.NonEmptyList
import cats.effect.{Async, Clock, Ref}
import cats.effect.kernel.Resource
import cats.effect.std.NonEmptyHotswap
import cats.syntax.all.*
import org.http4s.{Response, Uri}
import org.http4s.client.Client

import scala.concurrent.duration.FiniteDuration

/** Sticky multi-server failover middleware, mirroring the DVDV2 reference
 *  (`DVDV2RestManager`). `servers` index 0 is the primary; the rest are
 *  failover targets tried in order. Each entry is a per-server base URI that
 *  may carry its own path prefix (operators serve behind different prefixes,
 *  e.g. `https://backup/dvdv2-backend`): a request addressed under one
 *  server's base has that base's path stripped and the target server's path
 *  prepended when it is routed, so paths survive failover and recovery.
 *  Requests not addressed under any configured base are routed with their
 *  path unchanged.
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

  /** The failover middleware plus a view of its routing state.
   *
   *  `activeServer` is the sticky active server new requests are routed to
   *  first (index 0 = the primary), as its base URI including any path prefix.
   *  It lets callers derive per-server values — e.g. the token endpoint a POST
   *  will be addressed at — that follow failover and recovery.
   */
  final case class Handle[F[_]](
      middleware: Client[F] => Client[F],
      activeServer: F[Uri]
  )

  def make[F[_]: Async](
      servers: NonEmptyList[Uri],
      recoverAfter: FiniteDuration
  ): F[Handle[F]] =
    Ref.of[F, State](State(0, None)).map { state =>
      Handle(
        underlying => build(servers, recoverAfter, state)(underlying),
        state.get.map(s => servers.toList(s.activeIndex))
      )
    }

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
      // The base path the request was addressed under: the longest path-prefix
      // match among the configured servers. All servers are candidates, not
      // just the primary — token POSTs are addressed at the ACTIVE server's
      // base, so after a failover a recovery attempt must strip the backup's
      // prefix. Requests addressed at no configured server strip nothing.
      val sourceBase: Uri.Path = serverList
        .filter(s => s.scheme == req.uri.scheme && s.authority == req.uri.authority && req.uri.path.startsWith(s.path))
        .map(_.path)
        .maxByOption(_.segments.size)
        .getOrElse(Uri.Path.empty)

      // The in-flight attempt's response lives in a hotswap whose finalizer is
      // part of the caller's resource scope, so cancellation cannot leak it.
      NonEmptyHotswap.empty[F, Either[Throwable, Response[F]]].flatMap { hotswap =>
        Resource.eval {
          Clock[F].monotonic.flatMap { now =>
            // Decide once per request whether recovery is due, advancing the
            // recover deadline as a side effect (matches shallAttemptRecover).
            state.modify { s =>
              val due = s.activeIndex != 0 && s.nextRecoverAt.forall(now >= _)
              val next = if (due) s.copy(nextRecoverAt = Some(now + recoverAfter)) else s
              (next, (due, s.activeIndex))
            }.flatMap { case (attemptRecover, activeIndex) =>
              // Try the candidate at loop position `i`; recurse to the next on a
              // 5xx or transport failure. The last attempt's outcome is final.
              def go(i: Int): F[Response[F]] = {
                val serverIndex = pickServerIndex(i, activeIndex, attemptRecover)
                val srv         = serverList(serverIndex)
                val routed      = req.withUri(rebase(req.uri, sourceBase, srv))
                val isLast      = i == noOfServers - 1

                // Release the prior attempt's response before allocating the
                // next, or a small connection pool can deadlock.
                hotswap.clear *> swapIn(hotswap, underlying.run(routed).attempt).flatMap {
                  case Right(resp) if resp.status.code >= 500 =>
                    if (isLast) markFailedOver(state, serverIndex, now + recoverAfter).as(resp)
                    // drain the discarded 5xx body (best-effort) so the pooled
                    // connection can be reused instead of killed
                    else resp.body.compile.drain.attempt *> go(i + 1)
                  case Right(resp) =>
                    markAnswered(state, serverIndex, now + recoverAfter).as(resp)
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
  }

  /** Swaps `next` into the hotswap and hands back the value it acquired. The
   *  previous entry is only released after `next` is acquired, so callers
   *  `clear` first where the old slot must be freed before the next attempt.
   */
  private def swapIn[F[_]: Async, A](
      hotswap: NonEmptyHotswap[F, Option[A]],
      next: Resource[F, A]
  ): F[A] =
    hotswap.swap(next.map(_.some)) *>
      hotswap.getOpt.use(_.liftTo[F](new IllegalStateException("hotswap empty after swap")))

  /** Re-address `reqUri` at the `target` server: swap in the target's scheme
   *  and authority, strip the base path the request was addressed under
   *  (`sourceBase`) and prepend the target's own path prefix. Query and
   *  fragment ride along untouched.
   */
  private def rebase(reqUri: Uri, sourceBase: Uri.Path, target: Uri): Uri = {
    val rel  = reqUri.path.segments.drop(sourceBase.segments.size)
    val segs = target.path.segments ++ rel
    val newPath =
      if (segs.isEmpty && reqUri.path.isEmpty) reqUri.path
      else
        Uri.Path(
          segs,
          absolute = true,
          endsWithSlash = if (rel.nonEmpty) reqUri.path.endsWithSlash else target.path.endsWithSlash
        )
    reqUri.copy(scheme = target.scheme, authority = target.authority, path = newPath)
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
