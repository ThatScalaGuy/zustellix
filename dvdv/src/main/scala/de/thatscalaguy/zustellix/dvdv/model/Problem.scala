package de.thatscalaguy.zustellix.dvdv.model

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class Problem(
    `type`: Option[String] = None,
    title: Option[String] = None,
    status: Option[Int] = None,
    detail: Option[String] = None,
    instance: Option[String] = None
)

object Problem {
  given Codec[Problem] = deriveCodec
}
