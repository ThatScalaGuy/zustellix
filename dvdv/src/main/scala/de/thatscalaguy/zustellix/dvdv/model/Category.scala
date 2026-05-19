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
