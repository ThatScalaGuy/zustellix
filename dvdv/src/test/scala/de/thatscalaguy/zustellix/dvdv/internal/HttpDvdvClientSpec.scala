package de.thatscalaguy.zustellix.dvdv.internal

import cats.effect.{IO, Ref}
import de.thatscalaguy.zustellix.dvdv.DvdvConfig
import de.thatscalaguy.zustellix.utils.cert.CertSource
import de.thatscalaguy.zustellix.dvdv.model.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.{Request as _, *}
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.uri

import java.nio.file.Paths

class HttpDvdvClientSpec extends CatsEffectSuite {

  private def resourcePath(name: String) =
    Paths.get(getClass.getClassLoader.getResource(name).toURI)

  private val config = DvdvConfig(
    baseUri    = uri"http://dvdv.test",
    certSource = CertSource.Pkcs12(resourcePath("test-cert.p12"), "test")
  )

  private def client(routes: HttpRoutes[IO]): HttpDvdvClient[IO] =
    HttpDvdvClient[IO](Client.fromHttpApp(routes.orNotFound), config)

  test("findAuthorityDescription decodes 200 and 204") {
    val routes = HttpRoutes.of[IO] {
      case GET -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "findauthoritydescription" :? RequestJsonQ(json) =>
        if (json.contains("none")) NoContent()
        else Ok(OrganizationDescription(organization = Some(Organization(
          id = Some(1L), nameDe = "X", category = Some("Y"), organizationKeys = List("k"))
        )).asJson)
    }
    val c = client(routes)
    for {
      hit <- c.findAuthorityDescription("Cat", "key-1")
      miss <- c.findAuthorityDescription("Cat", "none")
    } yield {
      assert(hit.isDefined)
      assertEquals(miss, None)
    }
  }

  test("findCertificateByFingerprint maps 404 to None") {
    val routes = HttpRoutes.of[IO] {
      case GET -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "findCertificateByFingerprint" :? _ =>
        NotFound()
    }
    client(routes).findCertificateByFingerprint("deadbeef").map(r => assertEquals(r, None))
  }

  test("verifyCategory returns true") {
    val routes = HttpRoutes.of[IO] {
      case GET -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "verifycategory" :? _ =>
        Ok(VerificationResult(true).asJson)
    }
    client(routes).verifyCategory("fp", "cat").map(r => assertEquals(r, VerificationResult(true)))
  }

  test("batchVerifyCategory posts a JSON array and decodes a list") {
    val seen = Ref.unsafe[IO, Option[List[Request]]](None)
    val routes = HttpRoutes.of[IO] {
      case req @ POST -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "batch" / "verifycategory" =>
        req.as[List[Request]].flatMap(rs => seen.set(Some(rs))) *>
          Ok(List(VerificationResult(true), VerificationResult(false)).asJson)
    }
    val c = client(routes)
    val input = List(Request(fingerPrint = Some("fp1"), category = Some("c1")))
    for {
      out <- c.batchVerifyCategory(input)
      seenIn <- seen.get
    } yield {
      assertEquals(out.map(_.verifyCategory), List(true, false))
      assertEquals(seenIn, Some(input))
    }
  }

  test("findServiceSpecificationUrisByCategory decodes a JSON string array") {
    val routes = HttpRoutes.of[IO] {
      case GET -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "findServiceSpecificationUrisByCategory" :? RequestJsonQ(_) =>
        Ok(List("u1", "u2").asJson)
    }
    client(routes).findServiceSpecificationUrisByCategory("cat").map { r =>
      assertEquals(r, List("u1", "u2"))
    }
  }

  test("findOrganizationsByServiceElement decodes a JSON array of organizations") {
    val routes = HttpRoutes.of[IO] {
      case GET -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "findOrganizationsByServiceElement" :? RequestJsonQ(_) =>
        Ok(List(LightweightOrganization(id = Some(1L))).asJson)
    }
    client(routes)
      .findOrganizationsByServiceElement(ServiceElementType.OSCI_ADDRESSEE, ParameterType.URI, "01001000")
      .map(r => assertEquals(r.size, 1))
  }

  test("batchFindAuthorityDescription decodes a JSON array of OrganizationDescription") {
    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "batch" / "findauthoritydescription" =>
        Ok(List(OrganizationDescription(), OrganizationDescription()).asJson)
    }
    client(routes).batchFindAuthorityDescription(List(Request())).map(r => assertEquals(r.size, 2))
  }

  test("batchFindOrganizationsByServiceElement decodes an array of arrays of organizations") {
    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "batch" / "findOrganizationsByServiceElement" =>
        Ok(List(List(LightweightOrganization(id = Some(1L))), List.empty[LightweightOrganization]).asJson)
    }
    client(routes).batchFindOrganizationsByServiceElement(List(Request())).map { r =>
      assertEquals(r.size, 2)
      assertEquals(r.head.size, 1)
    }
  }

  test("batchFindServiceDescription decodes a JSON array of Service") {
    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "batch" / "findservicedescription" =>
        Ok(List(Service(id = Some(1L))).asJson)
    }
    client(routes).batchFindServiceDescription(List(Request())).map(r => assertEquals(r.size, 1))
  }

  test("batchFindServiceSpecificationUrisByCategory decodes an array of arrays of strings") {
    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "batch" / "findServiceSpecificationUrisByCategory" =>
        Ok(List(List("a"), List("b", "c")).asJson)
    }
    client(routes).batchFindServiceSpecificationUrisByCategory(List(Request())).map { r =>
      assertEquals(r, List(List("a"), List("b", "c")))
    }
  }

  // Matcher for ?request_json=<value>
  private object RequestJsonQ extends QueryParamDecoderMatcher[String]("request_json")
}
