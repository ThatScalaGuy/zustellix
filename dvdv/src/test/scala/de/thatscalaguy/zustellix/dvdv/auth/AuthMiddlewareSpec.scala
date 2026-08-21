package de.thatscalaguy.zustellix.dvdv.auth

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.uri

class AuthMiddlewareSpec extends CatsEffectSuite {

  /** Hand-rolled [[TokenManager]]: hands out `tokenFor(invalidations)` so a
   *  refresh after `invalidate` yields a different token, and records every
   *  stale token passed to `invalidate`.
   */
  private final class StubTokenManager(
      invalidations: Ref[IO, Int],
      staleSeen: Ref[IO, List[String]],
      tokenFor: Int => String
  ) extends TokenManager[IO] {
    def bearer: IO[String] = invalidations.get.map(tokenFor)
    def invalidate(stale: String): IO[Unit] =
      staleSeen.update(_ :+ stale) *> invalidations.update(_ + 1)
  }

  private def authHeader(req: Request[IO]): Option[String] =
    req.headers.get(org.typelevel.ci.CIString("Authorization")).map(_.head.value)

  test("outgoing request carries Authorization: EmbeddedBearer <token>") {
    val seen = Ref.unsafe[IO, List[String]](Nil)
    val backend = Client.fromHttpApp(HttpRoutes.of[IO] { case req =>
      IO(authHeader(req)).flatMap(h => seen.update(h.toList ++ _)) *> Ok()
    }.orNotFound)

    for {
      inv   <- Ref.of[IO, Int](0)
      stale <- Ref.of[IO, List[String]](Nil)
      tm     = new StubTokenManager(inv, stale, _ => "tok-A")
      c      = AuthMiddleware(tm)(backend)
      st    <- c.status(Request[IO](Method.GET, uri"http://dvdv.test/x"))
      hs    <- seen.get
    } yield {
      assertEquals(st, Status.Ok)
      assertEquals(hs, List("EmbeddedBearer tok-A"))
    }
  }

  test("401 then 200: middleware invalidates once and retries once with a re-fetched token") {
    val hits  = Ref.unsafe[IO, Int](0)
    val auths = Ref.unsafe[IO, List[String]](Nil)
    val backend = Client.fromHttpApp(HttpRoutes.of[IO] { case req =>
      for {
        _ <- auths.update(_ ++ authHeader(req).toList)
        n <- hits.updateAndGet(_ + 1)
        r <- if (n == 1) IO(Response[IO](Status.Unauthorized)) else Ok()
      } yield r
    }.orNotFound)

    for {
      inv   <- Ref.of[IO, Int](0)
      stale <- Ref.of[IO, List[String]](Nil)
      // before invalidate -> tok-0; after one invalidate -> tok-1
      tm     = new StubTokenManager(inv, stale, i => s"tok-$i")
      c      = AuthMiddleware(tm)(backend)
      st    <- c.status(Request[IO](Method.GET, uri"http://dvdv.test/x"))
      n     <- hits.get
      ic    <- inv.get
      ss    <- stale.get
      as    <- auths.get
    } yield {
      assertEquals(st, Status.Ok)            // final response is 200
      assertEquals(n, 2)                     // backend saw exactly 2 requests
      assertEquals(ic, 1)                    // invalidated exactly once
      assertEquals(ss, List("tok-0"))        // ...passing exactly the token it sent
      assertEquals(as, List("EmbeddedBearer tok-0", "EmbeddedBearer tok-1"))
    }
  }

  test("401 twice: the second 401 propagates (no infinite retry)") {
    val hits = Ref.unsafe[IO, Int](0)
    val backend = Client.fromHttpApp(HttpRoutes.of[IO] { case _ =>
      hits.update(_ + 1) *> IO(Response[IO](Status.Unauthorized))
    }.orNotFound)

    for {
      inv   <- Ref.of[IO, Int](0)
      stale <- Ref.of[IO, List[String]](Nil)
      tm     = new StubTokenManager(inv, stale, i => s"tok-$i")
      c      = AuthMiddleware(tm)(backend)
      st    <- c.status(Request[IO](Method.GET, uri"http://dvdv.test/x"))
      n     <- hits.get
      ic    <- inv.get
    } yield {
      assertEquals(st, Status.Unauthorized)  // second 401 propagates
      assertEquals(n, 2)                     // tried exactly twice
      assertEquals(ic, 1)                    // invalidated once, then gave up
    }
  }

  test("401 racing a token rotation does not wipe the newer token") {
    // Stale-aware manager: `invalidate` refreshes only when the stale token is
    // still current, mirroring the real compare-and-clear semantics.
    final class RotatingTokenManager(
        current: Ref[IO, String],
        staleSeen: Ref[IO, List[String]]
    ) extends TokenManager[IO] {
      def bearer: IO[String] = current.get
      def invalidate(stale: String): IO[Unit] =
        staleSeen.update(_ :+ stale) *>
          current.update(c => if (c == stale) "tok-refreshed" else c)
    }

    val auths   = Ref.unsafe[IO, List[String]](Nil)
    val current = Ref.unsafe[IO, String]("tok-A")
    val backend = Client.fromHttpApp(HttpRoutes.of[IO] { case req =>
      for {
        as <- auths.updateAndGet(_ ++ authHeader(req).toList)
        // the first request 401s, but another fiber rotates the token first
        r  <- if (as.sizeIs == 1) current.set("tok-B") *> IO(Response[IO](Status.Unauthorized))
              else Ok()
      } yield r
    }.orNotFound)

    for {
      stale <- Ref.of[IO, List[String]](Nil)
      tm     = new RotatingTokenManager(current, stale)
      c      = AuthMiddleware(tm)(backend)
      st    <- c.status(Request[IO](Method.GET, uri"http://dvdv.test/x"))
      as    <- auths.get
      ss    <- stale.get
      fin   <- current.get
    } yield {
      assertEquals(st, Status.Ok)
      assertEquals(as, List("EmbeddedBearer tok-A", "EmbeddedBearer tok-B"))
      assertEquals(ss, List("tok-A"))  // the middleware invalidated the token it sent
      assertEquals(fin, "tok-B")       // the concurrently rotated token survived
    }
  }
}
