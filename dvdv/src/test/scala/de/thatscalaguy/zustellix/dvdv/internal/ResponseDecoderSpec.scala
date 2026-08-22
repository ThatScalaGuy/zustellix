package de.thatscalaguy.zustellix.dvdv.internal

import cats.effect.IO
import de.thatscalaguy.zustellix.dvdv.DvdvError
import de.thatscalaguy.zustellix.dvdv.model.Problem
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.jsonEncoder
import org.typelevel.ci.CIString
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger

import scala.concurrent.duration.*

class ResponseDecoderSpec extends CatsEffectSuite {

  private val noopLog = NoOpLogger[IO]

  test("200 with a non-decoding body raises DecodingError, not TransportError") {
    val resp = Response[IO](Status.Ok).withEntity("""{"nope": true}""")
    ResponseDecoder.required[IO, List[String]]("version", resp).attempt.map {
      case Left(DvdvError.DecodingError(endpoint, cause)) =>
        // `cause` is statically an io.circe.Error — the field type guarantees it.
        assertEquals(endpoint, "version")
        assert(clue(cause).getMessage.nonEmpty)
      case other => fail(s"expected DecodingError, got $other")
    }
  }

  test("optional: 200 with a non-decoding body raises DecodingError with the endpoint label") {
    val resp = Response[IO](Status.Ok).withEntity("not json at all")
    ResponseDecoder.optional[IO, List[String]]("findservicedescription", resp, noopLog).attempt.map {
      case Left(DvdvError.DecodingError(endpoint, cause)) =>
        assertEquals(endpoint, "findservicedescription")
        assert(clue(cause).getMessage.nonEmpty)
      case other => fail(s"expected DecodingError, got $other")
    }
  }

  test("optional: 200 with Content-Length: 0 returns None") {
    val resp = Response[IO](Status.Ok).withEntity("")
    ResponseDecoder.optional[IO, List[String]]("findservicedescription", resp, noopLog).map { r =>
      assertEquals(r, None)
    }
  }

  test("optional: 200 with an empty body and no Content-Length returns None") {
    val resp = Response[IO](Status.Ok)
    ResponseDecoder.optional[IO, List[String]]("findservicedescription", resp, noopLog).map { r =>
      assertEquals(r, None)
    }
  }

  test("optional: 200 with a whitespace-only body returns None") {
    val resp = Response[IO](Status.Ok).withEntity(" \n")
    ResponseDecoder.optional[IO, List[String]]("findservicedescription", resp, noopLog).map { r =>
      assertEquals(r, None)
    }
  }

  test("optional: 204 returns None") {
    val resp = Response[IO](Status.NoContent)
    ResponseDecoder.optional[IO, List[String]]("findservicedescription", resp, noopLog).map { r =>
      assertEquals(r, None)
    }
  }

  test("optional: 204 with dvdv-warning-msg logs the header value at warn") {
    val encoded = "=?UTF-8?Q?ung=C3=BCltig?="
    val logger  = StructuredTestingLogger.impl[IO]()
    val resp = Response[IO](Status.NoContent)
      .putHeaders(Header.Raw(CIString("dvdv-warning-msg"), encoded))
    for {
      r      <- ResponseDecoder.optional[IO, List[String]]("findservicedescription", resp, logger)
      logged <- logger.logged
    } yield {
      assertEquals(r, None)
      val warns = logged.collect { case w: StructuredTestingLogger.WARN => w.message }
      assert(
        warns.exists(m => m.contains(encoded) && m.contains("findservicedescription")),
        s"expected a warn with the header value and endpoint, got: ${logged.mkString("; ")}"
      )
    }
  }

  test("optional: 204 without dvdv-warning-msg logs nothing") {
    val logger = StructuredTestingLogger.impl[IO]()
    val resp   = Response[IO](Status.NoContent)
    for {
      r      <- ResponseDecoder.optional[IO, List[String]]("findservicedescription", resp, logger)
      logged <- logger.logged
    } yield {
      assertEquals(r, None)
      assert(logged.isEmpty, s"expected no log entries, got: ${logged.mkString("; ")}")
    }
  }

  test("optional: 404 returns None") {
    val resp = Response[IO](Status.NotFound)
    ResponseDecoder.optional[IO, List[String]]("findCertificateByFingerprint", resp, noopLog).map { r =>
      assertEquals(r, None)
    }
  }

  test("optional: 200 with a decodable body returns Some") {
    val resp = Response[IO](Status.Ok).withEntity("""["a","b"]""")
    ResponseDecoder.optional[IO, List[String]]("findservicedescription", resp, noopLog).map { r =>
      assertEquals(r, Some(List("a", "b")))
    }
  }

  test("required: 200 with an empty body raises DecodingError") {
    val resp = Response[IO](Status.Ok)
    ResponseDecoder.required[IO, List[String]]("version", resp).attempt.map {
      case Left(DvdvError.DecodingError(endpoint, cause)) =>
        assertEquals(endpoint, "version")
        assert(clue(cause).getMessage.nonEmpty)
      case other => fail(s"expected DecodingError, got $other")
    }
  }

  test("5xx with a Problem body carries Some(problem) and the raw body") {
    val problem = Problem(title = Some("down"), status = Some(503))
    val raw     = problem.asJson.noSpaces
    val resp    = Response[IO](Status.ServiceUnavailable).withEntity(problem.asJson)
    ResponseDecoder.required[IO, List[String]]("categories", resp).attempt.map {
      case Left(DvdvError.ServerError(status, body, Some(p))) =>
        assertEquals(status, 503)
        assertEquals(body, raw)
        assertEquals(p.title, Some("down"))
      case other => fail(s"expected ServerError with Some(problem), got $other")
    }
  }

  test("5xx with a garbage body carries problem = None") {
    val resp = Response[IO](Status.ServiceUnavailable).withEntity("boom")
    ResponseDecoder.required[IO, List[String]]("categories", resp).attempt.map {
      case Left(DvdvError.ServerError(status, body, problem)) =>
        assertEquals(status, 503)
        assertEquals(body, "boom")
        assertEquals(problem, None)
      case other => fail(s"expected ServerError, got $other")
    }
  }

  test("429 with a delta-seconds Retry-After maps to RateLimited") {
    val problem = Problem(title = Some("slow down"), status = Some(429))
    val resp = Response[IO](Status.TooManyRequests)
      .withEntity(problem.asJson)
      .putHeaders(Header.Raw(CIString("Retry-After"), "7"))
    ResponseDecoder.required[IO, List[String]]("categories", resp).attempt.map {
      case Left(DvdvError.RateLimited(retryAfter, _, Some(p))) =>
        assertEquals(retryAfter, Some(7.seconds))
        assertEquals(p.title, Some("slow down"))
      case other => fail(s"expected RateLimited with Some(problem), got $other")
    }
  }

  test("429 without Retry-After carries retryAfter = None and the raw body") {
    val resp = Response[IO](Status.TooManyRequests).withEntity("busy")
    ResponseDecoder.required[IO, List[String]]("categories", resp).attempt.map {
      case Left(DvdvError.RateLimited(retryAfter, body, problem)) =>
        assertEquals(retryAfter, None)
        assertEquals(body, "busy")
        assertEquals(problem, None)
      case other => fail(s"expected RateLimited, got $other")
    }
  }

  test("unexpected status with a Problem body carries Some(problem)") {
    val problem = Problem(title = Some("teapot"), status = Some(418))
    val resp    = Response[IO](Status.ImATeapot).withEntity(problem.asJson)
    ResponseDecoder.required[IO, List[String]]("verifycategory", resp).attempt.map {
      case Left(DvdvError.Unexpected(status, _, Some(p))) =>
        assertEquals(status, 418)
        assertEquals(p.title, Some("teapot"))
      case other => fail(s"expected Unexpected with Some(problem), got $other")
    }
  }

  test("unexpected status with a garbage body carries problem = None") {
    val resp = Response[IO](Status.ImATeapot).withEntity("short and stout")
    ResponseDecoder.required[IO, List[String]]("verifycategory", resp).attempt.map {
      case Left(DvdvError.Unexpected(status, body, problem)) =>
        assertEquals(status, 418)
        assertEquals(body, "short and stout")
        assertEquals(problem, None)
      case other => fail(s"expected Unexpected, got $other")
    }
  }
}
