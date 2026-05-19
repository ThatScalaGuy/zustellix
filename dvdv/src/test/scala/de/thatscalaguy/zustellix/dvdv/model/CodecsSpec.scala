package de.thatscalaguy.zustellix.dvdv.model

import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

class CodecsSpec extends FunSuite {

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
}
