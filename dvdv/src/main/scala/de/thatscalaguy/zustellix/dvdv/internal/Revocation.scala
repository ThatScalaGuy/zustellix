package de.thatscalaguy.zustellix.dvdv.internal

import de.thatscalaguy.zustellix.dvdv.DvdvError
import de.thatscalaguy.zustellix.dvdv.model.Certificate

object Revocation {

  def check[F[_]](cert: Certificate, ignore: Boolean)(using F: cats.ApplicativeThrow[F]): F[Unit] =
    if (!ignore && cert.revocationDate.isDefined)
      F.raiseError(DvdvError.CertificateRevoked(cert.revocationDate, cert.revocationReason))
    else F.unit
}
