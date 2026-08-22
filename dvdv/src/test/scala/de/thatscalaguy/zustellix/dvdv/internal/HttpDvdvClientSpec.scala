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

import cats.effect.{IO, Ref}
import de.thatscalaguy.zustellix.dvdv.{DvdvConfig, DvdvError}
import de.thatscalaguy.zustellix.utils.cert.CertSource
import de.thatscalaguy.zustellix.dvdv.model.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.{Request as _, *}
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.uri
import org.typelevel.ci.CIString
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory
import org.typelevel.log4cats.testing.TestingLoggerFactory

import java.nio.file.Paths

class HttpDvdvClientSpec extends CatsEffectSuite {

  private def resourcePath(name: String) =
    Paths.get(getClass.getClassLoader.getResource(name).toURI)

  private val config = DvdvConfig(
    baseUri    = uri"http://dvdv.test",
    certSource = Some(CertSource.Pkcs12(resourcePath("test-cert.p12"), "test"))
  )

  private def client(
      routes: HttpRoutes[IO],
      lf: LoggerFactory[IO] = NoOpFactory[IO]
  ): HttpDvdvClient[IO] = {
    given LoggerFactory[IO] = lf
    HttpDvdvClient[IO](Client.fromHttpApp(routes.orNotFound), config)
  }

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
      hit <- c.findAuthorityDescription(Category.unsafe("Cat"), OrganizationKey.unsafe("key-01"))
      miss <- c.findAuthorityDescription(Category.unsafe("none"), OrganizationKey.unsafe("key-01"))
    } yield {
      assert(hit.isDefined)
      assertEquals(miss, None)
    }
  }

  test("findServiceDescription warns with the 204 dvdv-warning-msg header value") {
    val encoded = "=?UTF-8?Q?ung=C3=BCltig?="
    val routes = HttpRoutes.of[IO] {
      case GET -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "findservicedescription" :? _ =>
        NoContent().map(_.putHeaders(Header.Raw(CIString("dvdv-warning-msg"), encoded)))
    }
    for {
      lf    <- TestingLoggerFactory.ref[IO]()
      r     <- client(routes, lf).findServiceDescription(OrganizationKey.unsafe("ags:00000001"), "spec-uri")
      warns <- lf.logged.map(_.collect { case w: TestingLoggerFactory.Warn => w.message })
    } yield {
      assertEquals(r, None)
      assert(warns.exists(_.contains(encoded)), warns.mkString("; "))
    }
  }

  test("findCertificateByFingerprint maps 404 to None") {
    val routes = HttpRoutes.of[IO] {
      case GET -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "findCertificateByFingerprint" :? _ =>
        NotFound()
    }
    client(routes)
      .findCertificateByFingerprint(Fingerprint.unsafe("0272c56c9742a62501329a3aa78974f1605c92a2"))
      .map(r => assertEquals(r, None))
  }

  test("findCertificateByFingerprint maps an empty 200 to None") {
    val routes = HttpRoutes.of[IO] {
      case GET -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "findCertificateByFingerprint" :? _ =>
        Ok()
    }
    client(routes)
      .findCertificateByFingerprint(Fingerprint.unsafe("0272c56c9742a62501329a3aa78974f1605c92a2"))
      .map(r => assertEquals(r, None))
  }

  test("findCertificateByFingerprint sends the normalized fingerprint in request_json") {
    val seen = Ref.unsafe[IO, Option[String]](None)
    val routes = HttpRoutes.of[IO] {
      case GET -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "findCertificateByFingerprint" :? RequestJsonQ(json) =>
        seen.set(Some(json)) *> NotFound()
    }
    val colonUpper = "02:72:C5:6C:97:42:A6:25:01:32:9A:3A:A7:89:74:F1:60:5C:92:A2"
    for {
      _    <- client(routes).findCertificateByFingerprint(Fingerprint.unsafe(colonUpper))
      json <- seen.get.map(_.getOrElse(fail("request_json not captured")))
    } yield {
      assert(json.contains("0272c56c9742a62501329a3aa78974f1605c92a2"))
      assert(!json.contains(":7"), s"colons must be stripped: $json")
      assert(!json.contains("C5"), s"hex must be lowercased: $json")
    }
  }

  test("verifyCategory returns true") {
    val routes = HttpRoutes.of[IO] {
      case GET -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "verifycategory" :? _ =>
        Ok(VerificationResult(true).asJson)
    }
    client(routes)
      .verifyCategory(
        Fingerprint.unsafe("0272c56c9742a62501329a3aa78974f1605c92a2"),
        Category.unsafe("cat")
      )
      .map(r => assertEquals(r, VerificationResult(true)))
  }

  test("batchVerifyCategory posts a JSON array and decodes a list") {
    val seen = Ref.unsafe[IO, Option[List[Request]]](None)
    val routes = HttpRoutes.of[IO] {
      case req @ POST -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "batch" / "verifycategory" =>
        req.as[List[Request]].flatMap(rs => seen.set(Some(rs))) *>
          Ok(List(VerificationResult(true), VerificationResult(false)).asJson)
    }
    val c = client(routes)
    val input = List(
      Request(fingerPrint = Some("fp1"), category = Some("c1")),
      Request(fingerPrint = Some("fp2"), category = Some("c2"))
    )
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
    client(routes).findServiceSpecificationUrisByCategory(Category.unsafe("cat")).map { r =>
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

  test("findOrganizationsByServiceElement with customServiceElementType sends the right request_json") {
    val seen = Ref.unsafe[IO, Option[String]](None)
    val routes = HttpRoutes.of[IO] {
      case GET -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "findOrganizationsByServiceElement" :? RequestJsonQ(json) =>
        seen.set(Some(json)) *> Ok(List(LightweightOrganization(id = Some(1L))).asJson)
    }
    for {
      out  <- client(routes).findOrganizationsByServiceElement("MY_TYPE", ParameterType.URI, "01001000")
      json <- seen.get.map(_.getOrElse(fail("request_json not captured")))
      body <- IO.fromEither(io.circe.parser.parse(json))
    } yield {
      assertEquals(out.size, 1)
      val obj = body.asObject.getOrElse(fail(s"not a JSON object: $json"))
      assertEquals(obj("customServiceElementType").flatMap(_.asString), Some("MY_TYPE"))
      assertEquals(obj("parameterType").flatMap(_.asString), Some("URI"))
      assertEquals(obj("parameterValue").flatMap(_.asString), Some("01001000"))
      assertEquals(obj("serviceElementType"), None)
    }
  }

  test("batchFindAuthorityDescription decodes a JSON array of OrganizationDescription") {
    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "batch" / "findauthoritydescription" =>
        Ok(List(OrganizationDescription(), OrganizationDescription()).asJson)
    }
    client(routes).batchFindAuthorityDescription(List(Request(), Request())).map { r =>
      assertEquals(r, List(Some(OrganizationDescription()), Some(OrganizationDescription())))
    }
  }

  test("batchFindAuthorityDescription decodes a positional null as None") {
    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "batch" / "findauthoritydescription" =>
        Ok(List(Some(OrganizationDescription()), None, Some(OrganizationDescription())).asJson)
    }
    val input = List(Request(), Request(), Request())
    client(routes).batchFindAuthorityDescription(input).map { r =>
      assertEquals(r, List(Some(OrganizationDescription()), None, Some(OrganizationDescription())))
    }
  }

  test("batchFindServiceDescription decodes a positional null as None") {
    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "batch" / "findservicedescription" =>
        Ok(List(Some(Service(id = Some(1L))), None).asJson)
    }
    client(routes).batchFindServiceDescription(List(Request(), Request())).map { r =>
      assertEquals(r, List(Some(Service(id = Some(1L))), None))
    }
  }

  test("batch endpoints accept an empty batch") {
    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "batch" / "findauthoritydescription" =>
        Ok(List.empty[Option[OrganizationDescription]].asJson)
    }
    client(routes).batchFindAuthorityDescription(Nil).map(r => assertEquals(r, Nil))
  }

  test("batch endpoints raise BatchSizeMismatch when the response length differs") {
    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "batch" / "findauthoritydescription" =>
        Ok(List(Some(OrganizationDescription())).asJson)
    }
    interceptIO[DvdvError.BatchSizeMismatch](
      client(routes).batchFindAuthorityDescription(List(Request(), Request()))
    ).map { e =>
      assertEquals(e.expected, 2)
      assertEquals(e.actual, 1)
    }
  }

  test("batch endpoints reject more than 200 requests before any HTTP call") {
    val calls = Ref.unsafe[IO, Int](0)
    val routes = HttpRoutes.of[IO] {
      case req @ POST -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "batch" / "verifycategory" =>
        calls.update(_ + 1) *>
          req.as[List[Request]].flatMap(rs => Ok(List.fill(rs.size)(VerificationResult(true)).asJson))
    }
    val c = client(routes)
    for {
      _ <- interceptIO[DvdvError.BatchTooLarge](c.batchVerifyCategory(List.fill(201)(Request())))
      n <- calls.get
      _ <- c.batchVerifyCategory(List.fill(200)(Request())) // exactly 200 is allowed
      m <- calls.get
    } yield {
      assertEquals(n, 0)
      assertEquals(m, 1)
    }
  }

  test("batchFindOrganizationsByServiceElement decodes an array of arrays of organizations") {
    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "batch" / "findOrganizationsByServiceElement" =>
        Ok(List(List(LightweightOrganization(id = Some(1L))), List.empty[LightweightOrganization]).asJson)
    }
    client(routes).batchFindOrganizationsByServiceElement(List(Request(), Request())).map { r =>
      assertEquals(r.size, 2)
      assertEquals(r.head.size, 1)
    }
  }

  test("batchFindServiceDescription decodes a JSON array of Service") {
    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "batch" / "findservicedescription" =>
        Ok(List(Service(id = Some(1L))).asJson)
    }
    client(routes).batchFindServiceDescription(List(Request())).map { r =>
      assertEquals(r, List(Some(Service(id = Some(1L)))))
    }
  }

  test("batchFindServiceSpecificationUrisByCategory decodes an array of arrays of strings") {
    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "extern" / "standaloneauth" / "directory" / "v2" / "batch" / "findServiceSpecificationUrisByCategory" =>
        Ok(List(List("a"), List("b", "c")).asJson)
    }
    client(routes).batchFindServiceSpecificationUrisByCategory(List(Request(), Request())).map { r =>
      assertEquals(r, List(List("a"), List("b", "c")))
    }
  }

  // Matcher for ?request_json=<value>
  private object RequestJsonQ extends QueryParamDecoderMatcher[String]("request_json")
}
