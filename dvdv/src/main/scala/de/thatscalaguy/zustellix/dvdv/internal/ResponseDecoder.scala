package de.thatscalaguy.zustellix.dvdv.internal

import cats.effect.Concurrent
import cats.syntax.all.*
import de.thatscalaguy.zustellix.dvdv.DvdvError
import de.thatscalaguy.zustellix.dvdv.model.Problem
import io.circe.Decoder
import io.circe.parser.decode
import org.http4s.{MediaType, Response, Status}
import org.http4s.headers.`Content-Type`

object ResponseDecoder {

  def required[F[_]: Concurrent, A: Decoder](resp: Response[F]): F[A] =
    resp.status.code match {
      case s if s >= 200 && s < 300 => decodeBody[F, A](resp)
      case _                        => raiseError[F, A](resp)
    }

  def optional[F[_]: Concurrent, A: Decoder](resp: Response[F], notFoundIs204: Boolean = true): F[Option[A]] =
    resp.status match {
      case Status.Ok if resp.contentLength.forall(_ > 0L) =>
        decodeBody[F, A](resp).map(Some(_))
      case Status.NoContent if notFoundIs204 =>
        Concurrent[F].pure(None)
      case Status.NotFound =>
        // For findCertificateByFingerprint, 404 = not found (return None).
        // For other endpoints we surface NotFound — caller decides via .optional vs .required.
        Concurrent[F].pure(None)
      case s if s.code >= 200 && s.code < 300 =>
        decodeBody[F, A](resp).map(Some(_))
      case _ =>
        raiseError[F, Option[A]](resp)
    }

  private def decodeBody[F[_]: Concurrent, A: Decoder](resp: Response[F]): F[A] =
    resp.bodyText.compile.string.flatMap { s =>
      Concurrent[F].fromEither(
        decode[A](s).left.map(e => DvdvError.TransportError(e))
      )
    }

  private def raiseError[F[_]: Concurrent, A](resp: Response[F]): F[A] =
    resp.bodyText.compile.string.flatMap { body =>
      val problem = resp.headers.get[`Content-Type`] match {
        case Some(ct) if ct.mediaType == MediaType.application.`problem+json` =>
          decode[Problem](body).toOption.getOrElse(Problem(detail = Some(body)))
        case _ =>
          decode[Problem](body).toOption.getOrElse(Problem(detail = Some(body)))
      }
      val err: DvdvError = resp.status match {
        case Status.BadRequest   => DvdvError.ValidationError(problem)
        case Status.Unauthorized => DvdvError.AuthenticationError(problem)
        case Status.NotFound     => DvdvError.NotFound(problem)
        case s                   => DvdvError.Unexpected(s.code, body)
      }
      Concurrent[F].raiseError[A](err)
    }
}
