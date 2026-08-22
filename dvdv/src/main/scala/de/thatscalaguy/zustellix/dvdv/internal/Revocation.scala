/*
 * Copyright 2026 ThatScalaGuy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.thatscalaguy.zustellix.dvdv.internal

import de.thatscalaguy.zustellix.dvdv.DvdvError
import de.thatscalaguy.zustellix.dvdv.model.Certificate

object Revocation {

  def check[F[_]](cert: Certificate, ignore: Boolean)(using F: cats.ApplicativeThrow[F]): F[Unit] =
    if (!ignore && (cert.revocationDate.isDefined || cert.revocationReason.isDefined)) {
      // Parse the wire date defensively: an unparseable string must not throw
      // while constructing the error — date = None, rawDate keeps the string.
      // A reason-only response (no date) still fails closed as revoked.
      val parsed = cert.revocationDate.flatMap(s => scala.util.Try(java.time.Instant.parse(s)).toOption)
      F.raiseError(DvdvError.CertificateRevoked(parsed, cert.revocationDate, cert.revocationReason))
    } else F.unit
}
