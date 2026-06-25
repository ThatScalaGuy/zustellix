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

  private def make(servers: NonEmptyList[Uri], recoverAfter: FiniteDuration = 180.seconds)(
      underlying: Client[IO]
  ): IO[Client[IO]] =
    FailoverClient.make[IO](servers, recoverAfter).map(_(underlying))

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
      err <- c.run(req).use(ResponseDecoder.required[IO, String](_)).attempt
    } yield err match {
      case Left(DvdvError.ServerError(status, body)) =>
        assertEquals(status, 503)
        assertEquals(body, "boom")
      case other => fail(s"expected ServerError, got $other")
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
