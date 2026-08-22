package de.thatscalaguy.zustellix.dvdv.internal

import cats.effect.IO
import de.thatscalaguy.zustellix.dvdv.DvdvError
import de.thatscalaguy.zustellix.dvdv.model.Problem
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.jsonEncoder

class ResponseDecoderSpec extends CatsEffectSuite {

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
    ResponseDecoder.optional[IO, List[String]]("findservicedescription", resp).attempt.map {
      case Left(DvdvError.DecodingError(endpoint, cause)) =>
        assertEquals(endpoint, "findservicedescription")
        assert(clue(cause).getMessage.nonEmpty)
      case other => fail(s"expected DecodingError, got $other")
    }
  }

  test("optional: 200 with Content-Length: 0 returns None") {
    val resp = Response[IO](Status.Ok).withEntity("")
    ResponseDecoder.optional[IO, List[String]]("findservicedescription", resp).map { r =>
      assertEquals(r, None)
    }
  }

  test("optional: 200 with an empty body and no Content-Length returns None") {
    val resp = Response[IO](Status.Ok)
    ResponseDecoder.optional[IO, List[String]]("findservicedescription", resp).map { r =>
      assertEquals(r, None)
    }
  }

  test("optional: 200 with a whitespace-only body returns None") {
    val resp = Response[IO](Status.Ok).withEntity(" \n")
    ResponseDecoder.optional[IO, List[String]]("findservicedescription", resp).map { r =>
      assertEquals(r, None)
    }
  }

  test("optional: 204 returns None") {
    val resp = Response[IO](Status.NoContent)
    ResponseDecoder.optional[IO, List[String]]("findservicedescription", resp).map { r =>
      assertEquals(r, None)
    }
  }

  test("optional: 404 returns None") {
    val resp = Response[IO](Status.NotFound)
    ResponseDecoder.optional[IO, List[String]]("findCertificateByFingerprint", resp).map { r =>
      assertEquals(r, None)
    }
  }

  test("optional: 200 with a decodable body returns Some") {
    val resp = Response[IO](Status.Ok).withEntity("""["a","b"]""")
    ResponseDecoder.optional[IO, List[String]]("findservicedescription", resp).map { r =>
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
