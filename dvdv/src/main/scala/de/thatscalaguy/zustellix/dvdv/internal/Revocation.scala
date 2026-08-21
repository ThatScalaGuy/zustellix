package de.thatscalaguy.zustellix.dvdv.internal

import de.thatscalaguy.zustellix.dvdv.DvdvError
import de.thatscalaguy.zustellix.dvdv.model.Certificate

object Revocation {

  def check[F[_]](cert: Certificate, ignore: Boolean)(using F: cats.ApplicativeThrow[F]): F[Unit] =
    if (!ignore && cert.revocationDate.isDefined) {
      // Parse the wire date defensively: an unparseable string must not throw
      // while constructing the error — date = None, rawDate keeps the string.
      val parsed = cert.revocationDate.flatMap(s => scala.util.Try(java.time.Instant.parse(s)).toOption)
      F.raiseError(DvdvError.CertificateRevoked(parsed, cert.revocationDate, cert.revocationReason))
    } else F.unit
}
