package de.thatscalaguy.zustellix.dvdv.internal

import cats.effect.Concurrent
import cats.syntax.all.*
import de.thatscalaguy.zustellix.dvdv.DvdvError
import de.thatscalaguy.zustellix.dvdv.model.Problem
import io.circe.Decoder
import io.circe.parser.decode
import org.http4s.{Response, Status}
import org.http4s.headers.`Retry-After`
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.*

object ResponseDecoder {

  // Per dvdv-api.yaml, this header on a 204 signals that an INVALID matching
  // service exists (RFC-2047-encoded message) — the only way to distinguish
  // "no service" from "service exists but invalid".
  private val WarningHeader = CIString("dvdv-warning-msg")

  def required[F[_]: Concurrent, A: Decoder](endpoint: String, resp: Response[F]): F[A] =
    resp.status.code match {
      case s if s >= 200 && s < 300 => decodeBody[F, A](endpoint, resp)
      case _                        => raiseError[F, A](resp)
    }

  def optional[F[_]: Concurrent, A: Decoder](endpoint: String, resp: Response[F], log: Logger[F]): F[Option[A]] =
    resp.status match {
      case Status.NoContent =>
        logNoContentWarning(endpoint, resp, log).as(None)
      case Status.NotFound =>
        // For findCertificateByFingerprint, 404 = not found (return None).
        // For other endpoints we surface NotFound — caller decides via .optional vs .required.
        Concurrent[F].pure(None)
      case s if s.code >= 200 && s.code < 300 =>
        resp.bodyText.compile.string.flatMap { body =>
          if (body.isBlank) Concurrent[F].pure(None)
          else decodeString[F, A](endpoint, body).map(Some(_))
        }
      case _ =>
        raiseError[F, Option[A]](resp)
    }

  private def logNoContentWarning[F[_]: Concurrent](endpoint: String, resp: Response[F], log: Logger[F]): F[Unit] =
    resp.headers.get(WarningHeader).traverse_ { hs =>
      log.warn(s"$endpoint returned 204 with $WarningHeader: ${hs.head.value}")
    }

  private def decodeBody[F[_]: Concurrent, A: Decoder](endpoint: String, resp: Response[F]): F[A] =
    resp.bodyText.compile.string.flatMap(decodeString[F, A](endpoint, _))

  private def decodeString[F[_]: Concurrent, A: Decoder](endpoint: String, s: String): F[A] =
    Concurrent[F].fromEither(
      decode[A](s).left.map(e => DvdvError.DecodingError(endpoint, e))
    )

  private def raiseError[F[_]: Concurrent, A](resp: Response[F]): F[A] =
    resp.bodyText.compile.string.flatMap { body =>
      val problemOpt = decode[Problem](body).toOption
      val problem    = problemOpt.getOrElse(Problem(detail = Some(body)))
      val err: DvdvError = resp.status match {
        case Status.BadRequest      => DvdvError.ValidationError(problem)
        case Status.Unauthorized    => DvdvError.AuthenticationError(problem)
        case Status.NotFound        => DvdvError.NotFound(problem)
        case Status.TooManyRequests => DvdvError.RateLimited(retryAfterOf(resp), body, problemOpt)
        case s if s.code >= 500     => DvdvError.ServerError(s.code, body, problemOpt)
        case s                      => DvdvError.Unexpected(s.code, body, problemOpt)
      }
      Concurrent[F].raiseError[A](err)
    }

  // Delta-seconds form only: the HTTP-date form needs a clock — kept out of
  // scope here, the retry layer already honored it upstream.
  private def retryAfterOf[F[_]](resp: Response[F]): Option[FiniteDuration] =
    resp.headers.get[`Retry-After`].flatMap(_.retry match {
      case Right(secs) => Some(secs.seconds)
      case Left(_)     => None
    })
}
