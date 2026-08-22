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

package de.thatscalaguy.zustellix.dvdv.model

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class Certificate(
    content: Option[String] = None,
    fingerprint: Option[String] = None,
    serial: Option[String] = None,
    serialHex: Option[String] = None,
    emailSubject: Option[String] = None,
    algorithm: Option[String] = None,
    nameIssuer: Option[String] = None,
    nameSubject: Option[String] = None,
    organizationIssuer: Option[String] = None,
    organizationSubject: Option[String] = None,
    ouIssuer: Option[String] = None,
    ouSubject: Option[String] = None,
    validFrom: Option[String] = None,
    validTo: Option[String] = None,
    x509KeyUsage: Option[Int] = None,
    revocationDate: Option[String] = None,
    revocationReason: Option[RevocationReason] = None
)

object Certificate {
  given Codec[Certificate] = deriveCodec
}
