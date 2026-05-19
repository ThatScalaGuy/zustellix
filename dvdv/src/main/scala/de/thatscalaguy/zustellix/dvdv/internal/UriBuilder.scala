package de.thatscalaguy.zustellix.dvdv.internal

import io.circe.Json
import org.http4s.Uri

object UriBuilder {

  def endpoint(base: Uri, name: String): Uri =
    base / name

  def withRequestJson(base: Uri, name: String, payload: Json): Uri =
    base / name +? ("request_json" -> payload.noSpaces)

  def jsonObject(fields: (String, String)*): Json =
    Json.fromFields(fields.map { case (k, v) => k -> Json.fromString(v) })
}
