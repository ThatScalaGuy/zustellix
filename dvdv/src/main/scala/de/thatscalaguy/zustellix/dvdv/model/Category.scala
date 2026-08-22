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

final case class DirectoryOrganizationCategoryLevel4DTO(
    name: String,
    prefix: Option[String] = None
)

object DirectoryOrganizationCategoryLevel4DTO {
  given Codec[DirectoryOrganizationCategoryLevel4DTO] = deriveCodec
}

final case class DirectoryOrganizationCategoryLevel3DTO(
    name: String,
    prefix: Option[String] = None,
    children: Option[List[DirectoryOrganizationCategoryLevel4DTO]] = None
)

object DirectoryOrganizationCategoryLevel3DTO {
  given Codec[DirectoryOrganizationCategoryLevel3DTO] = deriveCodec
}

final case class DirectoryOrganizationCategoryLevel2DTO(
    name: String,
    prefix: Option[String] = None,
    children: Option[List[DirectoryOrganizationCategoryLevel3DTO]] = None
)

object DirectoryOrganizationCategoryLevel2DTO {
  given Codec[DirectoryOrganizationCategoryLevel2DTO] = deriveCodec
}

final case class DirectoryOrganizationCategoryLevel1DTO(
    name: String,
    children: Option[List[DirectoryOrganizationCategoryLevel2DTO]] = None
)

object DirectoryOrganizationCategoryLevel1DTO {
  given Codec[DirectoryOrganizationCategoryLevel1DTO] = deriveCodec
}
