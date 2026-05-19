package de.thatscalaguy.zustellix.dvdv.internal

import cats.effect.Concurrent
import de.thatscalaguy.zustellix.dvdv.{DvdvClient, DvdvConfig}
import de.thatscalaguy.zustellix.dvdv.model.*
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.{Method, Request as HttpRequest}
import org.http4s.client.Client

final class HttpDvdvClient[F[_]: Concurrent](
    http: Client[F],
    config: DvdvConfig
) extends DvdvClient[F] {

  private val base = config.directoryBase
  import UriBuilder.{endpoint, withRequestJson, jsonObject}

  // --- 3 plain GETs ---
  def categories: F[List[DirectoryOrganizationCategoryLevel1DTO]] =
    http.run(HttpRequest[F](Method.GET, endpoint(base, "categories")))
      .use(ResponseDecoder.required[F, List[DirectoryOrganizationCategoryLevel1DTO]](_))

  def intermediaries: F[List[SummaryServiceElementDTO]] =
    http.run(HttpRequest[F](Method.GET, endpoint(base, "intermediaries")))
      .use(ResponseDecoder.required[F, List[SummaryServiceElementDTO]](_))

  def serviceVersion: F[ServiceVersion] =
    http.run(HttpRequest[F](Method.GET, endpoint(base, "version")))
      .use(ResponseDecoder.required[F, ServiceVersion](_))

  // --- 8 query-style GETs ---
  def findAuthorityDescription(category: String, organizationKey: String): F[Option[OrganizationDescription]] = {
    val uri = withRequestJson(base, "findauthoritydescription",
      jsonObject("category" -> category, "organizationKey" -> organizationKey))
    http.run(HttpRequest[F](Method.GET, uri))
      .use(ResponseDecoder.optional[F, OrganizationDescription](_))
  }

  def findAuthorityDescriptions(organizationKey: String): F[List[OrganizationDescription]] = {
    val uri = withRequestJson(base, "findauthoritydescriptions",
      jsonObject("organizationKey" -> organizationKey))
    http.run(HttpRequest[F](Method.GET, uri))
      .use(ResponseDecoder.required[F, List[OrganizationDescription]](_))
  }

  def findCategories(fingerPrint: String, organizationKey: String): F[List[String]] = {
    val uri = withRequestJson(base, "findcategories",
      jsonObject("fingerPrint" -> fingerPrint, "organizationKey" -> organizationKey))
    http.run(HttpRequest[F](Method.GET, uri))
      .use(ResponseDecoder.required[F, List[String]](_))
  }

  def findCertificateByFingerprint(fingerPrint: String): F[Option[Certificate]] = {
    val uri = withRequestJson(base, "findCertificateByFingerprint",
      jsonObject("fingerPrint" -> fingerPrint))
    http.run(HttpRequest[F](Method.GET, uri))
      .use(ResponseDecoder.optional[F, Certificate](_))
  }

  def findOrganizationsByServiceElement(
      serviceElementType: ServiceElementType,
      parameterType: ParameterType,
      parameterValue: String
  ): F[OrganizationDescription] = {
    val uri = withRequestJson(base, "findOrganizationsByServiceElement",
      jsonObject(
        "serviceElementType" -> serviceElementType.toString,
        "parameterType"      -> parameterType.toString,
        "parameterValue"     -> parameterValue
      ))
    http.run(HttpRequest[F](Method.GET, uri))
      .use(ResponseDecoder.required[F, OrganizationDescription](_))
  }

  def findServiceDescription(organizationKey: String, serviceSpecificationUri: String): F[Option[Service]] = {
    val uri = withRequestJson(base, "findservicedescription",
      jsonObject(
        "organizationKey"         -> organizationKey,
        "serviceSpecificationUri" -> serviceSpecificationUri
      ))
    http.run(HttpRequest[F](Method.GET, uri))
      .use(ResponseDecoder.optional[F, Service](_))
  }

  def findServiceSpecificationUrisByCategory(category: String): F[List[ServiceBase]] = {
    val uri = withRequestJson(base, "findServiceSpecificationUrisByCategory",
      jsonObject("category" -> category))
    http.run(HttpRequest[F](Method.GET, uri))
      .use(ResponseDecoder.required[F, List[ServiceBase]](_))
  }

  def verifyCategory(fingerPrint: String, category: String): F[VerificationResult] = {
    val uri = withRequestJson(base, "verifycategory",
      jsonObject("fingerPrint" -> fingerPrint, "category" -> category))
    http.run(HttpRequest[F](Method.GET, uri))
      .use(ResponseDecoder.required[F, VerificationResult](_))
  }

  // --- 6 batch POSTs ---
  def batchFindAuthorityDescription(requests: List[Request]): F[OrganizationDescription] =
    batchPost[OrganizationDescription]("findauthoritydescription", requests)

  def batchFindCategories(requests: List[Request]): F[List[List[String]]] =
    batchPost[List[List[String]]]("findcategories", requests)

  def batchFindOrganizationsByServiceElement(requests: List[Request]): F[OrganizationDescription] =
    batchPost[OrganizationDescription]("findOrganizationsByServiceElement", requests)

  def batchFindServiceDescription(requests: List[Request]): F[Service] =
    batchPost[Service]("findservicedescription", requests)

  def batchFindServiceSpecificationUrisByCategory(requests: List[Request]): F[Request] =
    batchPost[Request]("findServiceSpecificationUrisByCategory", requests)

  def batchVerifyCategory(requests: List[Request]): F[List[VerificationResult]] =
    batchPost[List[VerificationResult]]("verifycategory", requests)

  private def batchPost[A: io.circe.Decoder](name: String, requests: List[Request]): F[A] = {
    val uri = base / "batch" / name
    val req = HttpRequest[F](Method.POST, uri).withEntity(requests.asJson)
    http.run(req).use(ResponseDecoder.required[F, A](_))
  }
}

object HttpDvdvClient {
  def apply[F[_]: Concurrent](http: Client[F], config: DvdvConfig): HttpDvdvClient[F] =
    new HttpDvdvClient[F](http, config)
}
