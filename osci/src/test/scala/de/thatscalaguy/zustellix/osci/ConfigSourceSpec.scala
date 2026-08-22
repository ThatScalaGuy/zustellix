package de.thatscalaguy.zustellix.osci

import cats.effect.IO
import de.thatscalaguy.zustellix.utils.cert.CertSource
import munit.CatsEffectSuite

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*

class ConfigSourceSpec extends CatsEffectSuite {

  test("static returns the supplied map") {
    val cfg = OsciConfig(
      tenantId   = TenantId("alice"),
      certSource = Some(CertSource.Pkcs12(Paths.get("k.p12"), "pw"))
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
        |tenant.alice.subject       = XFamilie
        |""".stripMargin

    val tmp = Files.createTempFile("osci-cfg-", ".properties")
    Files.writeString(tmp, props)
    tmp.toFile.deleteOnExit()

    ConfigSource.file[IO](tmp).load.map { m =>
      val c = m(TenantId("alice"))
      assertEquals(c.tenantId, TenantId("alice"))
      assertEquals(c.serviceUri, "http://example/wsdl")
      assertEquals(c.subject, "XFamilie")
      c.certSource match {
        case Some(CertSource.Pkcs12(p, pw)) =>
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
        case Some(CertSource.Pem(certP, keyP, pw)) =>
          assertEquals(certP.toString.replace('\\', '/'), "/keys/bob.crt")
          assertEquals(keyP.toString.replace('\\', '/'), "/keys/bob.key")
          assertEquals(pw, None)
        case other => fail(s"expected Pem, got $other")
      }
    }
  }

  test("file defaults serviceUri and subject when absent") {
    val props =
      """tenant.alice.cert.type     = pkcs12
        |tenant.alice.cert.path     = /keys/alice.p12
        |tenant.alice.cert.password = pw
        |""".stripMargin

    val tmp = Files.createTempFile("osci-cfg-", ".properties")
    Files.writeString(tmp, props)
    tmp.toFile.deleteOnExit()

    ConfigSource.file[IO](tmp).load.map { m =>
      val c = m(TenantId("alice"))
      assertEquals(c.serviceUri, OsciConfig.DefaultXMeldServiceUri)
      assertEquals(c.subject, OsciConfig.DefaultSubject)
    }
  }

  test("file parses optional timeouts and defaults them when absent") {
    val props =
      """tenant.alice.cert.type        = pkcs12
        |tenant.alice.cert.path        = /keys/alice.p12
        |tenant.alice.cert.password    = pw
        |tenant.alice.connectTimeoutMs = 5000
        |tenant.alice.readTimeoutMs    = 30000
        |tenant.bob.cert.type          = pkcs12
        |tenant.bob.cert.path          = /keys/bob.p12
        |tenant.bob.cert.password      = pw
        |""".stripMargin

    val tmp = Files.createTempFile("osci-cfg-", ".properties")
    Files.writeString(tmp, props)
    tmp.toFile.deleteOnExit()

    ConfigSource.file[IO](tmp).load.map { m =>
      val alice = m(TenantId("alice"))
      assertEquals(alice.connectTimeout, 5.seconds)
      assertEquals(alice.readTimeout, 30.seconds)
      val bob = m(TenantId("bob"))
      assertEquals(bob.connectTimeout, OsciHttpTransport.DefaultConnectTimeout)
      assertEquals(bob.readTimeout, OsciHttpTransport.DefaultReadTimeout)
    }
  }

  test("file raises Config error on a non-numeric timeout") {
    val props =
      """tenant.alice.cert.type        = pkcs12
        |tenant.alice.cert.path        = /keys/alice.p12
        |tenant.alice.cert.password    = pw
        |tenant.alice.connectTimeoutMs = fast
        |""".stripMargin

    val tmp = Files.createTempFile("osci-cfg-", ".properties")
    Files.writeString(tmp, props)
    tmp.toFile.deleteOnExit()

    ConfigSource.file[IO](tmp).load.attempt.map {
      case Left(e: OsciError.Config) =>
        assert(e.getMessage.contains("connectTimeoutMs"), e.getMessage)
      case other => fail(s"expected Config error, got $other")
    }
  }

  test("file parses optional contentSignatures and defaults it to Warn when absent") {
    val props =
      """tenant.alice.cert.type         = pkcs12
        |tenant.alice.cert.path         = /keys/alice.p12
        |tenant.alice.cert.password     = pw
        |tenant.alice.contentSignatures = require
        |tenant.bob.cert.type           = pkcs12
        |tenant.bob.cert.path           = /keys/bob.p12
        |tenant.bob.cert.password       = pw
        |""".stripMargin

    val tmp = Files.createTempFile("osci-cfg-", ".properties")
    Files.writeString(tmp, props)
    tmp.toFile.deleteOnExit()

    ConfigSource.file[IO](tmp).load.map { m =>
      assertEquals(m(TenantId("alice")).contentSignatures, ContentSignaturePolicy.Require)
      assertEquals(m(TenantId("bob")).contentSignatures, ContentSignaturePolicy.Warn)
    }
  }

  test("file raises Config error on an unknown contentSignatures value") {
    val props =
      """tenant.alice.cert.type         = pkcs12
        |tenant.alice.cert.path         = /keys/alice.p12
        |tenant.alice.cert.password     = pw
        |tenant.alice.contentSignatures = strict
        |""".stripMargin

    val tmp = Files.createTempFile("osci-cfg-", ".properties")
    Files.writeString(tmp, props)
    tmp.toFile.deleteOnExit()

    ConfigSource.file[IO](tmp).load.attempt.map {
      case Left(e: OsciError.Config) =>
        assert(e.getMessage.contains("contentSignatures"), e.getMessage)
      case other => fail(s"expected Config error, got $other")
    }
  }

  test("file parses optional capturePayloads and defaults it to false when absent") {
    val props =
      """tenant.alice.cert.type       = pkcs12
        |tenant.alice.cert.path       = /keys/alice.p12
        |tenant.alice.cert.password   = pw
        |tenant.alice.capturePayloads = true
        |tenant.bob.cert.type         = pkcs12
        |tenant.bob.cert.path         = /keys/bob.p12
        |tenant.bob.cert.password     = pw
        |""".stripMargin

    val tmp = Files.createTempFile("osci-cfg-", ".properties")
    Files.writeString(tmp, props)
    tmp.toFile.deleteOnExit()

    ConfigSource.file[IO](tmp).load.map { m =>
      assertEquals(m(TenantId("alice")).capturePayloads, true)
      assertEquals(m(TenantId("bob")).capturePayloads, false)
    }
  }

  test("file parses optional explicitDialog and defaults it to false when absent") {
    val props =
      """tenant.alice.cert.type      = pkcs12
        |tenant.alice.cert.path      = /keys/alice.p12
        |tenant.alice.cert.password  = pw
        |tenant.alice.explicitDialog = true
        |tenant.bob.cert.type        = pkcs12
        |tenant.bob.cert.path        = /keys/bob.p12
        |tenant.bob.cert.password    = pw
        |""".stripMargin

    val tmp = Files.createTempFile("osci-cfg-", ".properties")
    Files.writeString(tmp, props)
    tmp.toFile.deleteOnExit()

    ConfigSource.file[IO](tmp).load.map { m =>
      assertEquals(m(TenantId("alice")).explicitDialog, true)
      assertEquals(m(TenantId("bob")).explicitDialog, false)
    }
  }

  test("file raises Config error on an unknown capturePayloads value") {
    val props =
      """tenant.alice.cert.type       = pkcs12
        |tenant.alice.cert.path       = /keys/alice.p12
        |tenant.alice.cert.password   = pw
        |tenant.alice.capturePayloads = yes
        |""".stripMargin

    val tmp = Files.createTempFile("osci-cfg-", ".properties")
    Files.writeString(tmp, props)
    tmp.toFile.deleteOnExit()

    ConfigSource.file[IO](tmp).load.attempt.map {
      case Left(e: OsciError.Config) =>
        assert(e.getMessage.contains("capturePayloads"), e.getMessage)
      case other => fail(s"expected Config error, got $other")
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
      case Left(e: OsciError.Config) =>
        assert(e.getMessage.contains("cert.path"))
      case other => fail(s"expected Config error, got $other")
    }
  }
}
