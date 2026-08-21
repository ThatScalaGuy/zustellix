package de.thatscalaguy.zustellix.dvdv

import de.thatscalaguy.zustellix.dvdv.model.Problem
import de.thatscalaguy.zustellix.dvdv.model.RevocationReason

import java.time.Instant

sealed abstract class DvdvError(msg: String, cause: Throwable | Null = null) extends RuntimeException(msg, cause)

object DvdvError {
  final case class Config(msg: String) extends DvdvError(msg)

  final case class AuthenticationError(problem: Problem)
      extends DvdvError(s"401 Unauthorized: ${problem.detail.orElse(problem.title).getOrElse("")}")

  final case class ValidationError(problem: Problem)
      extends DvdvError(s"400 Bad Request: ${problem.detail.orElse(problem.title).getOrElse("")}")

  final case class NotFound(problem: Problem)
      extends DvdvError(s"404 Not Found: ${problem.detail.orElse(problem.title).getOrElse("")}")

  /** A non-2xx status outside the mapped 400/401/404/5xx cases. `problem` is
   *  `Some` only when the body parsed as an RFC 7807 Problem; the raw body is
   *  always kept.
   */
  final case class Unexpected(status: Int, body: String, problem: Option[Problem])
      extends DvdvError(s"Unexpected $status: $body")

  /** A 5xx response. `problem` is `Some` only when the body parsed as an
   *  RFC 7807 Problem; the raw body is always kept.
   */
  final case class ServerError(status: Int, body: String, problem: Option[Problem])
      extends DvdvError(s"Server error $status: $body")

  /** `rawDate` is the wire string verbatim; `date` is its parsed `Instant`,
   *  `None` when the date is absent or unparseable — construction never
   *  throws on a malformed revocation date.
   */
  final case class CertificateRevoked(date: Option[Instant], rawDate: Option[String], reason: Option[RevocationReason])
      extends DvdvError(
        s"Certificate revoked${date.map(_.toString).orElse(rawDate).fold("")(d => s" since $d")}${reason.fold("")(r => s": $r")}"
      )

  /** A 2xx body failed JSON decoding — schema drift between client and
   *  directory, distinct from a `TransportError` (socket/IO failure).
   *  `endpoint` is the DVDV path-segment name of the call that failed.
   */
  final case class DecodingError(endpoint: String, cause: io.circe.Error)
      extends DvdvError(s"Decoding failure at '$endpoint': ${cause.getMessage}", cause)

  final case class TransportError(cause: Throwable)
      extends DvdvError(s"Transport error: ${cause.getMessage}", cause)

  /** A string failed validation as a fingerprint — raised only by
   *  the smart constructors, never by a lookup.
   */
  final case class InvalidFingerprint(input: String)
      extends DvdvError(s"Invalid fingerprint '$input': expected 40 hex characters (colons and whitespace are stripped, case-insensitive)")

  /** A string failed validation as an organization key — raised only
   *  by the smart constructors, never by a lookup.
   */
  final case class InvalidOrganizationKey(input: String)
      extends DvdvError(s"Invalid organization key '$input': expected 6 to 255 characters")

  /** A string failed validation as a category — raised only by the
   *  smart constructors, never by a lookup.
   */
  final case class InvalidCategory(input: String)
      extends DvdvError(s"Invalid category '$input': expected 1 to 255 characters")

  /** A batch endpoint received more than 200 requests (the spec's
   *  `maxItems: 200`) — raised client-side before any HTTP call.
   */
  final case class BatchTooLarge(size: Int)
      extends DvdvError(s"Batch of $size requests exceeds the DVDV limit of 200 items per call — split the batch client-side")

  /** A batch response array's length differed from the request list's,
   *  breaking the positional one-result-per-request contract — raised
   *  instead of returning silently misaligned results.
   */
  final case class BatchSizeMismatch(expected: Int, actual: Int)
      extends DvdvError(s"Batch response holds $actual results for $expected requests — positional alignment lost")
}
