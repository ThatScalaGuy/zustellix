package de.thatscalaguy.zustellix.dvdv.auth

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.uri

class AuthMiddlewareSpec extends CatsEffectSuite {

  /** Hand-rolled [[TokenManager]]: hands out `tokenFor(invalidations)` so a
   *  refresh after `invalidate` yields a different token, and counts the
   *  `invalidate` calls.
   */
  private final class StubTokenManager(
      invalidations: Ref[IO, Int],
      tokenFor: Int => String
  ) extends TokenManager[IO] {
    def bearer: IO[String]   = invalidations.get.map(tokenFor)
    def invalidate: IO[Unit] = invalidations.update(_ + 1)
  }

  private def authHeader(req: Request[IO]): Option[String] =
    req.headers.get(org.typelevel.ci.CIString("Authorization")).map(_.head.value)

  test("outgoing request carries Authorization: EmbeddedBearer <token>") {
    val seen = Ref.unsafe[IO, List[String]](Nil)
    val backend = Client.fromHttpApp(HttpRoutes.of[IO] { case req =>
      IO(authHeader(req)).flatMap(h => seen.update(h.toList ++ _)) *> Ok()
    }.orNotFound)

    for {
      inv <- Ref.of[IO, Int](0)
      tm   = new StubTokenManager(inv, _ => "tok-A")
      c    = AuthMiddleware(tm)(backend)
      st  <- c.status(Request[IO](Method.GET, uri"http://dvdv.test/x"))
      hs  <- seen.get
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
      inv <- Ref.of[IO, Int](0)
      // before invalidate -> tok-0; after one invalidate -> tok-1
      tm   = new StubTokenManager(inv, i => s"tok-$i")
      c    = AuthMiddleware(tm)(backend)
      st  <- c.status(Request[IO](Method.GET, uri"http://dvdv.test/x"))
      n   <- hits.get
      ic  <- inv.get
      as  <- auths.get
    } yield {
      assertEquals(st, Status.Ok)            // final response is 200
      assertEquals(n, 2)                     // backend saw exactly 2 requests
      assertEquals(ic, 1)                    // invalidated exactly once
      assertEquals(as, List("EmbeddedBearer tok-0", "EmbeddedBearer tok-1"))
    }
  }

  test("401 twice: the second 401 propagates (no infinite retry)") {
    val hits = Ref.unsafe[IO, Int](0)
    val backend = Client.fromHttpApp(HttpRoutes.of[IO] { case _ =>
      hits.update(_ + 1) *> IO(Response[IO](Status.Unauthorized))
    }.orNotFound)

    for {
      inv <- Ref.of[IO, Int](0)
      tm   = new StubTokenManager(inv, i => s"tok-$i")
      c    = AuthMiddleware(tm)(backend)
      st  <- c.status(Request[IO](Method.GET, uri"http://dvdv.test/x"))
      n   <- hits.get
      ic  <- inv.get
    } yield {
      assertEquals(st, Status.Unauthorized)  // second 401 propagates
      assertEquals(n, 2)                     // tried exactly twice
      assertEquals(ic, 1)                    // invalidated once, then gave up
    }
  }
}
