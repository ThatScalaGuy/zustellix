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
