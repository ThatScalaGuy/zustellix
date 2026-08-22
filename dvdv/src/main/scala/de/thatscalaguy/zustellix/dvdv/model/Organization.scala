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

final case class Organization(
    id: Option[Long] = None,
    validFrom: Option[String] = None,
    validTo: Option[String] = None,
    nameDe: String,
    nameEn: Option[String] = None,
    descriptionDe: Option[String] = None,
    descriptionEn: Option[String] = None,
    postalAddress: Option[String] = None,
    locationStateId: Option[Long] = None,
    dvdv1Id: Option[String] = None,
    category: Option[String] = None,
    organizationKeys: List[String],
    clientCertificates: Option[List[Certificate]] = None,
    services: Option[List[ServiceBase]] = None
)

object Organization {
  given Codec[Organization] = deriveCodec
}

final case class LightweightOrganization(
    id: Option[Long] = None,
    validFrom: Option[String] = None,
    validTo: Option[String] = None,
    nameDe: Option[String] = None,
    nameEn: Option[String] = None,
    descriptionDe: Option[String] = None,
    descriptionEn: Option[String] = None,
    postalAddress: Option[String] = None,
    locationStateId: Option[Long] = None,
    dvdv1Id: Option[String] = None,
    category: Option[String] = None,
    organizationKeys: List[String] = Nil
)

object LightweightOrganization {
  given Codec[LightweightOrganization] = deriveCodec
}

final case class OrganizationDescription(
    organization: Option[Organization] = None,
    representatives: Option[List[Organization]] = None
)

object OrganizationDescription {
  given Codec[OrganizationDescription] = deriveCodec
}
