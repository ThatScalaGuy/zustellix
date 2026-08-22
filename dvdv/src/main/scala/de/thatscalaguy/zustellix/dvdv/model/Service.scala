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

final case class ServiceBase(
    id: Option[Long] = None,
    validFrom: Option[String] = None,
    validTo: Option[String] = None,
    nameDe: Option[String] = None,
    nameEn: Option[String] = None,
    descriptionDe: Option[String] = None,
    descriptionEn: Option[String] = None,
    dvdv1Uuid: Option[String] = None,
    serviceDescriptionName: Option[String] = None,
    serviceSpecificationType: Option[ServiceSpecificationType] = None,
    serviceSpecificationUri: Option[String] = None
)

object ServiceBase {
  given Codec[ServiceBase] = deriveCodec
}

final case class ServiceElementInfo(
    serviceElementName: Option[String] = None,
    serviceElementDescription: Option[String] = None,
    serviceElementDescriptionName: Option[String] = None,
    serviceElementType: Option[ServiceElementType] = None,
    customServiceElementType: Option[String] = None,
    serviceElementText: Option[String] = None,
    serviceElementUri: Option[String] = None,
    cipherCertificate: Option[Certificate] = None,
    signatureCertificate: Option[Certificate] = None,
    required: Option[Boolean] = None,
    serviceElementId: Option[Long] = None,
    providerId: Option[Long] = None,
    providerNameDe: Option[String] = None,
    providerNameEn: Option[String] = None
)

object ServiceElementInfo {
  given Codec[ServiceElementInfo] = deriveCodec
}

final case class Service(
    id: Option[Long] = None,
    validFrom: Option[String] = None,
    validTo: Option[String] = None,
    nameDe: Option[String] = None,
    nameEn: Option[String] = None,
    descriptionDe: Option[String] = None,
    descriptionEn: Option[String] = None,
    dvdv1Uuid: Option[String] = None,
    serviceDescriptionName: Option[String] = None,
    serviceSpecificationType: Option[ServiceSpecificationType] = None,
    serviceSpecificationUri: Option[String] = None,
    serviceSpecificationDocument: Option[String] = None,
    organizationNameDe: Option[String] = None,
    organizationNameEn: Option[String] = None,
    locationStateId: Option[Long] = None,
    serviceElements: Option[List[ServiceElementInfo]] = None
)

object Service {
  given Codec[Service] = deriveCodec
}
