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

import cats.effect.Concurrent
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import de.thatscalaguy.zustellix.dvdv.{DvdvClient, DvdvConfig, DvdvError}
import de.thatscalaguy.zustellix.dvdv.model.*
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.{Method, Request as HttpRequest}
import org.http4s.client.Client
import org.typelevel.log4cats.LoggerFactory

final class HttpDvdvClient[F[_]: Concurrent: LoggerFactory](
    http: Client[F],
    config: DvdvConfig
) extends DvdvClient[F] {

  private val base = config.directoryBase
  private val log  = LoggerFactory[F].getLogger
  import UriBuilder.{endpoint, withRequestJson, jsonObject}

  // --- 3 plain GETs ---
  def categories: F[List[DirectoryOrganizationCategoryLevel1DTO]] =
    http.run(HttpRequest[F](Method.GET, endpoint(base, "categories")))
      .use(ResponseDecoder.required[F, List[DirectoryOrganizationCategoryLevel1DTO]]("categories", _))

  def intermediaries: F[List[SummaryServiceElementDTO]] =
    http.run(HttpRequest[F](Method.GET, endpoint(base, "intermediaries")))
      .use(ResponseDecoder.required[F, List[SummaryServiceElementDTO]]("intermediaries", _))

  def serviceVersion: F[ServiceVersion] =
    http.run(HttpRequest[F](Method.GET, endpoint(base, "version")))
      .use(ResponseDecoder.required[F, ServiceVersion]("version", _))

  // --- 8 query-style GETs ---
  def findAuthorityDescription(category: Category, organizationKey: OrganizationKey): F[Option[OrganizationDescription]] = {
    val uri = withRequestJson(base, "findauthoritydescription",
      jsonObject("category" -> category.value, "organizationKey" -> organizationKey.value))
    http.run(HttpRequest[F](Method.GET, uri))
      .use(ResponseDecoder.optional[F, OrganizationDescription]("findauthoritydescription", _, log))
  }

  def findAuthorityDescriptions(organizationKey: OrganizationKey): F[List[OrganizationDescription]] = {
    val uri = withRequestJson(base, "findauthoritydescriptions",
      jsonObject("organizationKey" -> organizationKey.value))
    http.run(HttpRequest[F](Method.GET, uri))
      .use(ResponseDecoder.required[F, List[OrganizationDescription]]("findauthoritydescriptions", _))
  }

  def findCategories(fingerPrint: Fingerprint, organizationKey: OrganizationKey): F[List[String]] = {
    val uri = withRequestJson(base, "findcategories",
      jsonObject("fingerPrint" -> fingerPrint.value, "organizationKey" -> organizationKey.value))
    http.run(HttpRequest[F](Method.GET, uri))
      .use(ResponseDecoder.required[F, List[String]]("findcategories", _))
  }

  def findCertificateByFingerprint(fingerPrint: Fingerprint): F[Option[Certificate]] = {
    val uri = withRequestJson(base, "findCertificateByFingerprint",
      jsonObject("fingerPrint" -> fingerPrint.value))
    http.run(HttpRequest[F](Method.GET, uri))
      .use(ResponseDecoder.optional[F, Certificate]("findCertificateByFingerprint", _, log))
      .flatTap(_.traverse_(Revocation.check[F](_, config.ignoreRevocation)))
  }

  def findOrganizationsByServiceElement(
      serviceElementType: ServiceElementType,
      parameterType: ParameterType,
      parameterValue: String
  ): F[List[LightweightOrganization]] = {
    val uri = withRequestJson(base, "findOrganizationsByServiceElement",
      jsonObject(
        "serviceElementType" -> serviceElementType.toString,
        "parameterType"      -> parameterType.toString,
        "parameterValue"     -> parameterValue
      ))
    http.run(HttpRequest[F](Method.GET, uri))
      .use(ResponseDecoder.required[F, List[LightweightOrganization]]("findOrganizationsByServiceElement", _))
  }

  def findOrganizationsByServiceElement(
      customServiceElementType: String,
      parameterType: ParameterType,
      parameterValue: String
  ): F[List[LightweightOrganization]] = {
    val uri = withRequestJson(base, "findOrganizationsByServiceElement",
      jsonObject(
        "customServiceElementType" -> customServiceElementType,
        "parameterType"            -> parameterType.toString,
        "parameterValue"           -> parameterValue
      ))
    http.run(HttpRequest[F](Method.GET, uri))
      .use(ResponseDecoder.required[F, List[LightweightOrganization]]("findOrganizationsByServiceElement", _))
  }

  def findServiceDescription(organizationKey: OrganizationKey, serviceSpecificationUri: String): F[Option[Service]] = {
    val uri = withRequestJson(base, "findservicedescription",
      jsonObject(
        "organizationKey"         -> organizationKey.value,
        "serviceSpecificationUri" -> serviceSpecificationUri
      ))
    http.run(HttpRequest[F](Method.GET, uri))
      .use(ResponseDecoder.optional[F, Service]("findservicedescription", _, log))
  }

  def findServiceSpecificationUrisByCategory(category: Category): F[List[String]] = {
    val uri = withRequestJson(base, "findServiceSpecificationUrisByCategory",
      jsonObject("category" -> category.value))
    http.run(HttpRequest[F](Method.GET, uri))
      .use(ResponseDecoder.required[F, List[String]]("findServiceSpecificationUrisByCategory", _))
  }

  def verifyCategory(fingerPrint: Fingerprint, category: Category): F[VerificationResult] = {
    val uri = withRequestJson(base, "verifycategory",
      jsonObject("fingerPrint" -> fingerPrint.value, "category" -> category.value))
    http.run(HttpRequest[F](Method.GET, uri))
      .use(ResponseDecoder.required[F, VerificationResult]("verifycategory", _))
  }

  // --- 6 batch POSTs ---
  def batchFindAuthorityDescription(requests: List[Request]): F[List[Option[OrganizationDescription]]] =
    batchPost[Option[OrganizationDescription]]("findauthoritydescription", requests)

  def batchFindCategories(requests: List[Request]): F[List[List[String]]] =
    batchPost[List[String]]("findcategories", requests)

  def batchFindOrganizationsByServiceElement(requests: List[Request]): F[List[List[LightweightOrganization]]] =
    batchPost[List[LightweightOrganization]]("findOrganizationsByServiceElement", requests)

  def batchFindServiceDescription(requests: List[Request]): F[List[Option[Service]]] =
    batchPost[Option[Service]]("findservicedescription", requests)

  def batchFindServiceSpecificationUrisByCategory(requests: List[Request]): F[List[List[String]]] =
    batchPost[List[String]]("findServiceSpecificationUrisByCategory", requests)

  def batchVerifyCategory(requests: List[Request]): F[List[VerificationResult]] =
    batchPost[VerificationResult]("verifycategory", requests)

  private def batchPost[B: io.circe.Decoder](name: String, requests: List[Request]): F[List[B]] =
    if (requests.sizeIs > HttpDvdvClient.MaxBatchItems)
      Concurrent[F].raiseError(DvdvError.BatchTooLarge(requests.size))
    else {
      val uri = base / "batch" / name
      val req = HttpRequest[F](Method.POST, uri).withEntity(requests.asJson)
      http.run(req).use(ResponseDecoder.required[F, List[B]](s"batch/$name", _)).flatMap { results =>
        if (results.sizeIs == requests.size) Concurrent[F].pure(results)
        else Concurrent[F].raiseError(DvdvError.BatchSizeMismatch(requests.size, results.size))
      }
    }
}

object HttpDvdvClient {

  /** The spec's `maxItems: 200` on every batch request body — enforced
   *  client-side so oversized batches fail before any HTTP call.
   */
  private val MaxBatchItems = 200

  def apply[F[_]: Concurrent: LoggerFactory](http: Client[F], config: DvdvConfig): HttpDvdvClient[F] =
    new HttpDvdvClient[F](http, config)
}
