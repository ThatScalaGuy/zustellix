package de.thatscalaguy.zustellix.dvdv

import de.thatscalaguy.zustellix.dvdv.model.Problem

sealed abstract class DvdvError(msg: String, cause: Throwable | Null = null) extends RuntimeException(msg, cause)

object DvdvError {
  final case class AuthenticationError(problem: Problem)
      extends DvdvError(s"401 Unauthorized: ${problem.detail.orElse(problem.title).getOrElse("")}")

  final case class ValidationError(problem: Problem)
      extends DvdvError(s"400 Bad Request: ${problem.detail.orElse(problem.title).getOrElse("")}")

  final case class NotFound(problem: Problem)
      extends DvdvError(s"404 Not Found: ${problem.detail.orElse(problem.title).getOrElse("")}")

  final case class Unexpected(status: Int, body: String)
      extends DvdvError(s"Unexpected $status: $body")

  final case class TransportError(cause: Throwable)
      extends DvdvError(s"Transport error: ${cause.getMessage}", cause)
}
