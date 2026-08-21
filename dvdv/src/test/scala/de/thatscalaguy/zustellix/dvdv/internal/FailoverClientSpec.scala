package de.thatscalaguy.zustellix.dvdv.internal

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.effect.kernel.Resource
import cats.syntax.all.*
import de.thatscalaguy.zustellix.dvdv.DvdvError
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.implicits.uri

import java.net.ConnectException
import scala.concurrent.duration.*

class FailoverClientSpec extends CatsEffectSuite {

  private val primary   = uri"http://primary"
  private val secondary = uri"http://secondary"

  private def hostOf(req: Request[IO]): String =
    req.uri.authority.map(_.host.value).getOrElse("?")

  /** Backend that dispatches per target host and records every host it serves. */
  private def routed(hits: Ref[IO, List[String]])(behavior: String => IO[Response[IO]]): Client[IO] =
    Client.fromHttpApp(HttpApp[IO] { req =>
      val host = hostOf(req)
      hits.update(_ :+ host) *> behavior(host)
    })

  /** Like [[routed]], but records every full URI it serves, keyed by host. */
  private def routedUris(hits: Ref[IO, List[(String, String)]])(behavior: String => IO[Response[IO]]): Client[IO] =
    Client.fromHttpApp(HttpApp[IO] { req =>
      val host = hostOf(req)
      hits.update(_ :+ (host -> req.uri.renderString)) *> behavior(host)
    })

  private def make(servers: NonEmptyList[Uri], recoverAfter: FiniteDuration = 180.seconds)(
      underlying: Client[IO]
  ): IO[Client[IO]] =
    FailoverClient.make[IO](servers, recoverAfter).map(_.middleware(underlying))

  private val req = Request[IO](Method.GET, uri"http://ignored/path")

  test("primary 500 -> secondary serves it") {
    val hits = Ref.unsafe[IO, List[String]](Nil)
    val backend = routed(hits) {
      case "primary" => IO(Response[IO](Status.InternalServerError))
      case _         => IO(Response[IO](Status.Ok))
    }
    for {
      c  <- make(NonEmptyList(primary, List(secondary)))(backend)
      st <- c.status(req)
      hs <- hits.get
    } yield {
      assertEquals(st, Status.Ok)
      assertEquals(hs, List("primary", "secondary"))
    }
  }

  test("sticky: after failover the secondary is used directly, primary not retried") {
    val hits = Ref.unsafe[IO, List[String]](Nil)
    val backend = routed(hits) {
      case "primary" => IO(Response[IO](Status.InternalServerError))
      case _         => IO(Response[IO](Status.Ok))
    }
    for {
      c   <- make(NonEmptyList(primary, List(secondary)))(backend)
      _   <- c.status(req)         // fails over: primary, secondary
      _   <- hits.set(Nil)
      st  <- c.status(req)         // should go straight to secondary
      hs  <- hits.get
    } yield {
      assertEquals(st, Status.Ok)
      assertEquals(hs, List("secondary"))
    }
  }

  test("recovery: after the window, primary is retried first and becomes active again") {
    val hits        = Ref.unsafe[IO, List[String]](Nil)
    val primaryDown = Ref.unsafe[IO, Boolean](true)
    val backend = routed(hits) {
      case "primary" => primaryDown.get.map(d => Response[IO](if (d) Status.InternalServerError else Status.Ok))
      case _         => IO(Response[IO](Status.Ok))
    }
    for {
      c  <- make(NonEmptyList(primary, List(secondary)), recoverAfter = 50.millis)(backend)
      _  <- c.status(req)          // fail over to secondary
      _  <- primaryDown.set(false) // primary healthy again
      _  <- IO.sleep(80.millis)    // recover window elapses
      _  <- hits.set(Nil)
      st <- c.status(req)          // retries primary first
      hs <- hits.get
      _  <- hits.set(Nil)
      _  <- c.status(req)          // recovered: straight to primary
      hs2 <- hits.get
    } yield {
      assertEquals(st, Status.Ok)
      assertEquals(hs, List("primary"))   // recovery attempt started at primary
      assertEquals(hs2, List("primary"))  // active server reset to primary
    }
  }

  test("no failover on 401: returned as-is, secondary not hit") {
    val hits = Ref.unsafe[IO, List[String]](Nil)
    val backend = routed(hits) {
      case "primary" => IO(Response[IO](Status.Unauthorized))
      case _         => IO(Response[IO](Status.Ok))
    }
    for {
      c  <- make(NonEmptyList(primary, List(secondary)))(backend)
      st <- c.status(req)
      hs <- hits.get
    } yield {
      assertEquals(st, Status.Unauthorized)
      assertEquals(hs, List("primary"))
      assertEquals(hs.count(_ == "secondary"), 0)
    }
  }

  test("no failover on 404: returned as-is, secondary not hit") {
    val hits = Ref.unsafe[IO, List[String]](Nil)
    val backend = routed(hits) {
      case "primary" => IO(Response[IO](Status.NotFound))
      case _         => IO(Response[IO](Status.Ok))
    }
    for {
      c  <- make(NonEmptyList(primary, List(secondary)))(backend)
      st <- c.status(req)
      hs <- hits.get
    } yield {
      assertEquals(st, Status.NotFound)
      assertEquals(hs.count(_ == "secondary"), 0)
    }
  }

  test("all servers 5xx: final response status is 500") {
    val hits = Ref.unsafe[IO, List[String]](Nil)
    val backend = routed(hits)(_ => IO(Response[IO](Status.InternalServerError)))
    for {
      c  <- make(NonEmptyList(primary, List(secondary)))(backend)
      st <- c.status(req)
      hs <- hits.get
    } yield {
      assertEquals(st, Status.InternalServerError)
      assertEquals(hs, List("primary", "secondary")) // tried both, bounded by server count
    }
  }

  test("all servers 5xx through ResponseDecoder -> DvdvError.ServerError") {
    val hits = Ref.unsafe[IO, List[String]](Nil)
    val backend = routed(hits)(_ => IO(Response[IO](Status.ServiceUnavailable).withEntity("boom")))
    for {
      c   <- make(NonEmptyList(primary, List(secondary)))(backend)
      err <- c.run(req).use(ResponseDecoder.required[IO, String]("test", _)).attempt
    } yield err match {
      case Left(DvdvError.ServerError(status, body, problem)) =>
        assertEquals(status, 503)
        assertEquals(body, "boom")
        assertEquals(problem, None)
      case other => fail(s"expected ServerError, got $other")
    }
  }

  test("activeServer tracks failover and recovery") {
    val hits        = Ref.unsafe[IO, List[String]](Nil)
    val primaryDown = Ref.unsafe[IO, Boolean](true)
    val backend = routed(hits) {
      case "primary" => primaryDown.get.map(d => Response[IO](if (d) Status.InternalServerError else Status.Ok))
      case _         => IO(Response[IO](Status.Ok))
    }
    for {
      h  <- FailoverClient.make[IO](NonEmptyList(primary, List(secondary)), recoverAfter = 50.millis)
      c   = h.middleware(backend)
      a0 <- h.activeServer
      _  <- c.status(req)          // fail over to secondary
      a1 <- h.activeServer
      _  <- primaryDown.set(false) // primary healthy again
      _  <- IO.sleep(80.millis)    // recover window elapses
      _  <- c.status(req)          // recovery attempt succeeds
      a2 <- h.activeServer
    } yield {
      assertEquals(a0, primary)
      assertEquals(a1, secondary)
      assertEquals(a2, primary)
    }
  }

  test("failover to a server with a path prefix prepends the prefix") {
    val hits = Ref.unsafe[IO, List[(String, String)]](Nil)
    val backend = routedUris(hits) {
      case "primary" => IO(Response[IO](Status.InternalServerError))
      case _         => IO(Response[IO](Status.Ok))
    }
    for {
      c  <- make(NonEmptyList(primary, List(uri"http://secondary/dvdv2-backend")))(backend)
      st <- c.status(Request[IO](Method.GET, uri"http://primary/extern/standaloneauth/directory/v2/version?request_json=x"))
      hs <- hits.get
    } yield {
      assertEquals(st, Status.Ok)
      assertEquals(
        hs,
        List(
          "primary"   -> "http://primary/extern/standaloneauth/directory/v2/version?request_json=x",
          "secondary" -> "http://secondary/dvdv2-backend/extern/standaloneauth/directory/v2/version?request_json=x"
        )
      )
    }
  }

  test("primary base path is stripped when failing over to a server without one") {
    val hits = Ref.unsafe[IO, List[(String, String)]](Nil)
    val backend = routedUris(hits) {
      case "primary" => IO(Response[IO](Status.InternalServerError))
      case _         => IO(Response[IO](Status.Ok))
    }
    for {
      c  <- make(NonEmptyList(uri"http://primary/api", List(secondary)))(backend)
      st <- c.status(Request[IO](Method.GET, uri"http://primary/api/v2/version"))
      hs <- hits.get
    } yield {
      assertEquals(st, Status.Ok)
      assertEquals(
        hs,
        List("primary" -> "http://primary/api/v2/version", "secondary" -> "http://secondary/v2/version")
      )
    }
  }

  test("recovery attempt rebases a request addressed at the failed-over server") {
    val hits        = Ref.unsafe[IO, List[(String, String)]](Nil)
    val primaryDown = Ref.unsafe[IO, Boolean](true)
    val backend = routedUris(hits) {
      case "primary" => primaryDown.get.map(d => Response[IO](if (d) Status.InternalServerError else Status.Ok))
      case _         => IO(Response[IO](Status.Ok))
    }
    for {
      c  <- make(NonEmptyList(uri"http://primary/p", List(uri"http://secondary/s")), recoverAfter = 50.millis)(backend)
      _  <- c.status(Request[IO](Method.POST, uri"http://primary/p/token")) // fail over to secondary
      h1 <- hits.get
      _  <- primaryDown.set(false) // primary healthy again
      _  <- IO.sleep(80.millis)    // recover window elapses
      _  <- hits.set(Nil)
      _  <- c.status(Request[IO](Method.POST, uri"http://secondary/s/token")) // addressed at the active server
      h2 <- hits.get
    } yield {
      assertEquals(h1, List("primary" -> "http://primary/p/token", "secondary" -> "http://secondary/s/token"))
      assertEquals(h2, List("primary" -> "http://primary/p/token")) // recovery attempt re-based onto the primary
    }
  }

  test("connection error on primary -> secondary serves it") {
    val hits = Ref.unsafe[IO, List[String]](Nil)
    val backend = Client[IO] { r =>
      val host = hostOf(r)
      Resource.eval(hits.update(_ :+ host)) *> {
        if (host == "primary") Resource.eval(IO.raiseError(new ConnectException("refused")))
        else Resource.pure(Response[IO](Status.Ok))
      }
    }
    for {
      c  <- make(NonEmptyList(primary, List(secondary)))(backend)
      st <- c.status(req)
      hs <- hits.get
    } yield {
      assertEquals(st, Status.Ok)
      assertEquals(hs, List("primary", "secondary"))
    }
  }
}
