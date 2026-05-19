package de.thatscalaguy.zustellix.oscixmeld

import cats.effect.IO
import de.thatscalaguy.zustellix.utils.cert.CertSource
import munit.CatsEffectSuite

import java.nio.file.{Files, Paths}

class ConfigSourceSpec extends CatsEffectSuite {

  test("static returns the supplied map") {
    val cfg = OSCIXMeldConfig(
      tenantId   = TenantId("alice"),
      certSource = CertSource.Pkcs12(Paths.get("k.p12"), "pw")
    )
    ConfigSource.static[IO](Map(TenantId("alice") -> cfg)).load.assertEquals(
      Map(TenantId("alice") -> cfg)
    )
  }

  test("file parses tenants with pkcs12 cert") {
    val props =
      """tenant.alice.cert.type     = pkcs12
        |tenant.alice.cert.path     = C:/keys/alice.p12
        |tenant.alice.cert.password = secret
        |tenant.alice.serviceUri    = http://example/wsdl
        |tenant.alice.requestTimeoutSeconds = 30
        |""".stripMargin

    val tmp = Files.createTempFile("osci-cfg-", ".properties")
    Files.writeString(tmp, props)
    tmp.toFile.deleteOnExit()

    ConfigSource.file[IO](tmp).load.map { m =>
      val c = m(TenantId("alice"))
      assertEquals(c.tenantId, TenantId("alice"))
      assertEquals(c.serviceUri, "http://example/wsdl")
      assertEquals(c.requestTimeout.toSeconds, 30L)
      c.certSource match {
        case CertSource.Pkcs12(p, pw) =>
          assertEquals(p.toString.replace('\\', '/'), "C:/keys/alice.p12")
          assertEquals(pw, "secret")
        case other => fail(s"expected Pkcs12, got $other")
      }
    }
  }

  test("file parses tenants with pem cert") {
    val props =
      """tenant.bob.cert.type    = pem
        |tenant.bob.cert.path    = /keys/bob.crt
        |tenant.bob.cert.keyPath = /keys/bob.key
        |""".stripMargin

    val tmp = Files.createTempFile("osci-cfg-", ".properties")
    Files.writeString(tmp, props)
    tmp.toFile.deleteOnExit()

    ConfigSource.file[IO](tmp).load.map { m =>
      val c = m(TenantId("bob"))
      c.certSource match {
        case CertSource.Pem(certP, keyP, pw) =>
          assertEquals(certP.toString.replace('\\', '/'), "/keys/bob.crt")
          assertEquals(keyP.toString.replace('\\', '/'), "/keys/bob.key")
          assertEquals(pw, None)
        case other => fail(s"expected Pem, got $other")
      }
    }
  }

  test("file raises Config error on missing required cert.path") {
    val props =
      """tenant.broken.cert.type     = pkcs12
        |tenant.broken.cert.password = pw
        |""".stripMargin
    val tmp = Files.createTempFile("osci-cfg-", ".properties")
    Files.writeString(tmp, props)
    tmp.toFile.deleteOnExit()

    ConfigSource.file[IO](tmp).load.attempt.map {
      case Left(e: OSCIXMeldError.Config) =>
        assert(e.getMessage.contains("cert.path"))
      case other => fail(s"expected Config error, got $other")
    }
  }
}
