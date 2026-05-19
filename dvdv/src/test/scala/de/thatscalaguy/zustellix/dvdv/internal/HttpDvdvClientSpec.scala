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
          id = 1, nameDe = "X", category = "Y", organizationKeys = List("k"))
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

  // Matcher for ?request_json=<value>
  private object RequestJsonQ extends QueryParamDecoderMatcher[String]("request_json")
}
