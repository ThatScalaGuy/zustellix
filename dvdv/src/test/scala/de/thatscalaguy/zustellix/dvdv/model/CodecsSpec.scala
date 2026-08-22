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

import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

import scala.io.Source

class CodecsSpec extends FunSuite {

  private def fixture(name: String): String = {
    val src = Source.fromInputStream(getClass.getResourceAsStream("/" + name), "UTF-8")
    try src.mkString
    finally src.close()
  }

  test("Problem round-trips") {
    val p = Problem(
      `type`    = Some("problems/type/ENTITY_NOT_FOUND"),
      title     = Some("Entität nicht gefunden"),
      status    = Some(404),
      detail    = Some("nope"),
      instance  = Some("problems/instance/DirectoryEntityNotFound")
    )
    val js = p.asJson
    assertEquals(decode[Problem](js.noSpaces), Right(p))
  }

  test("VerificationResult decodes") {
    val js = """{"verifyCategory":true}"""
    assertEquals(decode[VerificationResult](js), Right(VerificationResult(true)))
  }

  test("ServiceElementType decodes string enums") {
    assertEquals(decode[ServiceElementType](""""OSCI_ADDRESSEE""""), Right(ServiceElementType.OSCI_ADDRESSEE))
  }

  test("ServiceElementType decodes unknown constants to Other") {
    assertEquals(decode[ServiceElementType](""""SOME_FUTURE_TYPE""""), Right(ServiceElementType.Other("SOME_FUTURE_TYPE")))
  }

  test("ParameterType decodes unknown constants to Other") {
    assertEquals(decode[ParameterType](""""SOME_FUTURE_TYPE""""), Right(ParameterType.Other("SOME_FUTURE_TYPE")))
  }

  test("ServiceSpecificationType decodes unknown constants to Other") {
    assertEquals(decode[ServiceSpecificationType](""""SOME_FUTURE_TYPE""""), Right(ServiceSpecificationType.Other("SOME_FUTURE_TYPE")))
  }

  test("RevocationReason decodes unknown constants to Other") {
    assertEquals(decode[RevocationReason](""""SOME_FUTURE_REASON""""), Right(RevocationReason.Other("SOME_FUTURE_REASON")))
  }

  test("enum encoders render Other as the raw wire string and known cases as their name") {
    assertEquals((RevocationReason.Other("SOME_FUTURE_REASON"): RevocationReason).asJson.noSpaces, """"SOME_FUTURE_REASON"""")
    assertEquals((RevocationReason.KEY_COMPROMISE: RevocationReason).asJson.noSpaces, """"KEY_COMPROMISE"""")
  }

  test("Certificate decodes with an unknown revocationReason") {
    val js = """
      {
        "fingerprint": "deadbeef",
        "revocationDate": "2026-01-01T00:00:00Z",
        "revocationReason": "SOME_FUTURE_REASON"
      }
    """
    val parsed = decode[Certificate](js)
    assert(parsed.isRight, s"failed: $parsed")
    val cert = parsed.toOption.get
    assertEquals(cert.revocationDate, Some("2026-01-01T00:00:00Z"))
    assertEquals(cert.revocationReason, Some(RevocationReason.Other("SOME_FUTURE_REASON")))
  }

  test("Request encodes only set fields and decodes back") {
    val r = Request(fingerPrint = Some("abc"), category = Some("Meldebehörde"))
    val js = r.asJson
    assertEquals(decode[Request](js.noSpaces), Right(r))
  }

  test("OrganizationDescription decodes the spec example shape") {
    val js = """
      {
        "organization": {
          "id": 14077,
          "nameDe": "Stadt Flensburg",
          "category": "Passbehörde",
          "organizationKeys": ["psb:01001000_00"]
        },
        "representatives": []
      }
    """
    val parsed = decode[OrganizationDescription](js)
    assert(parsed.isRight, s"failed: $parsed")
    val od = parsed.toOption.get
    assertEquals(od.organization.map(_.nameDe), Some("Stadt Flensburg"))
    assertEquals(od.organization.map(_.organizationKeys), Some(List("psb:01001000_00")))
  }

  test("ServiceVersion decodes string or object") {
    assertEquals(decode[ServiceVersion](""""v2.15.0""""), Right(ServiceVersion(raw = Some("v2.15.0"))))
    val obj = """{"version":"v2.15.0","buildnumber":"42","schemaversion":"1"}"""
    assertEquals(decode[ServiceVersion](obj),
      Right(ServiceVersion(version = Some("v2.15.0"), buildnumber = Some("42"), schemaversion = Some("1"))))
  }

  test("AccessTokenResponse decodes snake_case") {
    val js = """{"access_token":"tok","expires_in":86400,"token_type":"Bearer"}"""
    val r = decode[AccessTokenResponse](js)
    assert(r.isRight)
    assertEquals(r.toOption.get.access_token, "tok")
    assertEquals(r.toOption.get.expires_in, Some(86400L))
  }

  // dvdv-api.yaml's AccessToken schema is snake_case (access_token/expires_in/
  // token_type) while the spec's response example shows camelCase (accessToken/
  // expiresIn/tokenType); the codec deliberately follows the schema — the OIDC
  // wire format real servers emit.
  test("AccessTokenResponse decodes the real token fixture with the schema's snake_case fields") {
    val parsed = decode[AccessTokenResponse](fixture("AccessToken.json"))
    assert(parsed.isRight, s"failed: $parsed")
    val tok = parsed.toOption.get
    assert(tok.access_token.startsWith("eyJ"), tok.access_token)
    assertEquals(tok.expires_in, Some(86400L))
    assertEquals(tok.refresh_expires_in, Some(0L))
    assertEquals(tok.token_type, Some("Bearer"))
    assertEquals(tok.`not-before-policy`, Some(0))
    assertEquals(tok.scope, Some("email profile dvdv2-kernsystem-application-client-scope"))
  }

  test("LightweightOrganization decodes the real fixture (category null)") {
    val parsed = decode[LightweightOrganization](fixture("OrganizationLightweight.json"))
    assert(parsed.isRight, s"failed: $parsed")
    val org = parsed.toOption.get
    assertEquals(org.category, None)
    assertEquals(org.id, Some(4711L))
    assertEquals(org.organizationKeys, List("foo:1234", "bar:4321"))
  }

  test("Service decodes the real fixture (null spec type/uri and serviceElementId)") {
    val parsed = decode[Service](fixture("ServiceDescription.json"))
    assert(parsed.isRight, s"failed: $parsed")
    val svc = parsed.toOption.get
    assertEquals(svc.serviceSpecificationType, None)
    assertEquals(svc.serviceSpecificationUri, None)
    assertEquals(svc.serviceElements.get.head.serviceElementId, None)
  }

  test("Service decodes the custom fixture (null document, MANUAL spec type)") {
    val parsed = decode[Service](fixture("ServiceDescription-mit-Custom.json"))
    assert(parsed.isRight, s"failed: $parsed")
    val svc = parsed.toOption.get
    assertEquals(svc.serviceSpecificationDocument, None)
    assertEquals(svc.serviceSpecificationType, Some(ServiceSpecificationType.MANUAL))
  }

  test("OrganizationDescription decodes the real fixture (org id/category null)") {
    val parsed = decode[OrganizationDescription](fixture("OrganizationDescription.json"))
    assert(parsed.isRight, s"failed: $parsed")
    val od = parsed.toOption.get
    assertEquals(od.organization.flatMap(_.id), None)
    assertEquals(od.organization.flatMap(_.category), None)
  }

  // The batch fixtures pin the client's deviations from the published OpenAPI
  // schema (issue #25): dvdv-api.yaml declares a single object as the 200
  // response of four of the six batch endpoints, while the client decodes a
  // positionally aligned JSON array — with a null per miss, mirroring the
  // single-call 204/404 miss semantics (the spec does not specify a batch miss
  // encoding). A client regenerated against the schema's declared types fails
  // these tests loudly. batchFindCategories and batchVerifyCategory match the
  // schema and are pinned for completeness.
  test("batchFindAuthorityDescription response decodes as a positional array of nullable OrganizationDescription") {
    val parsed = decode[List[Option[OrganizationDescription]]](fixture("batchFindAuthorityDescription.json"))
    assert(parsed.isRight, s"failed: $parsed")
    val results = parsed.toOption.get
    assertEquals(results.size, 2)
    assertEquals(results.head.flatMap(_.organization).map(_.nameDe), Some("der-orga-name"))
    assertEquals(results(1), None)
  }

  test("batchFindCategories response decodes as an array of category-name arrays") {
    assertEquals(
      decode[List[List[String]]](fixture("batchFindCategories.json")),
      Right(List(List("Aufnahmeeinrichtung", "Meldebehörde"), Nil))
    )
  }

  test("batchFindOrganizationsByServiceElement response decodes as an array of LightweightOrganization arrays") {
    val parsed = decode[List[List[LightweightOrganization]]](fixture("batchFindOrganizationsByServiceElement.json"))
    assert(parsed.isRight, s"failed: $parsed")
    val results = parsed.toOption.get
    assertEquals(results.size, 2)
    assertEquals(results.head.head.id, Some(4711L))
    assertEquals(results.head.head.organizationKeys, List("foo:1234", "bar:4321"))
    assertEquals(results(1), Nil)
  }

  test("batchFindServiceDescription response decodes as a positional array of nullable Service") {
    val parsed = decode[List[Option[Service]]](fixture("batchFindServiceDescription.json"))
    assert(parsed.isRight, s"failed: $parsed")
    val results = parsed.toOption.get
    assertEquals(results.size, 2)
    assertEquals(results.head.flatMap(_.nameDe), Some("test-servicename"))
    assertEquals(results(1), None)
  }

  test("batchFindServiceSpecificationUrisByCategory response decodes as an array of URI-string arrays") {
    val parsed = decode[List[List[String]]](fixture("batchFindServiceSpecificationUrisByCategory.json"))
    assert(parsed.isRight, s"failed: $parsed")
    val results = parsed.toOption.get
    assertEquals(
      results.head,
      List(
        "http://www.osci.de/xauslaender1170/xauslaender1170ASYLBAMFAE.wsdl",
        "http://www.osci.de/xauslaender1180/xauslaender1180ASYLBAMFAE.wsdl",
        "http://www.osci.de/xinneres/quittung/2/xinneresquittungv2.wsdl",
        "http://www.osci.de/xinneres/quittung/3/xinneresquittungv3.wsdl"
      )
    )
    assertEquals(results(1), Nil)
  }

  test("batchVerifyCategory response decodes as an array of VerificationResult") {
    assertEquals(
      decode[List[VerificationResult]](fixture("batchVerifyCategory.json")),
      Right(List(VerificationResult(true), VerificationResult(false)))
    )
  }
}
