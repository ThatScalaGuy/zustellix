package de.thatscalaguy.zustellix.osci

import cats.effect.{IO, Resource}
import de.thatscalaguy.zustellix.dvdv.DvdvClient
import de.thatscalaguy.zustellix.dvdv.model.*
import munit.CatsEffectSuite

import java.nio.file.{Files, Path, Paths}

/** Boots `OsciFacade.fromConfigs` from a properties file. Boot only touches
 *  the tenant certs — DVDV is consulted per send, never during acquisition —
 *  so the stub client can leave every method unimplemented.
 */
class OsciFacadeSpec extends CatsEffectSuite {

  private def resourcePath(name: String): Path =
    Paths.get(getClass.getClassLoader.getResource(name).toURI)

  private val stubDvdv: DvdvClient[IO] =
    new DvdvClient[IO] {
      def findServiceDescription(organizationKey: OrganizationKey, serviceSpecificationUri: String) = ???
      def categories                                                       = ???
      def intermediaries                                                   = ???
      def serviceVersion                                                   = ???
      def findAuthorityDescription(c: Category, o: OrganizationKey)        = ???
      def findAuthorityDescriptions(o: OrganizationKey)                    = ???
      def findCategories(f: Fingerprint, o: OrganizationKey)               = ???
      def findCertificateByFingerprint(f: Fingerprint)                     = ???
      def findOrganizationsByServiceElement(s: ServiceElementType, p: ParameterType, v: String) = ???
      def findOrganizationsByServiceElement(c: String, p: ParameterType, v: String)            = ???
      def findServiceSpecificationUrisByCategory(c: Category)              = ???
      def verifyCategory(f: Fingerprint, c: Category)                      = ???
      def batchFindAuthorityDescription(rs: List[Request])                 = ???
      def batchFindCategories(rs: List[Request])                           = ???
      def batchFindOrganizationsByServiceElement(rs: List[Request])        = ???
      def batchFindServiceDescription(rs: List[Request])                   = ???
      def batchFindServiceSpecificationUrisByCategory(rs: List[Request])   = ???
      def batchVerifyCategory(rs: List[Request])                           = ???
    }

  private def facadeFor(props: String): Resource[IO, OsciFacade[IO]] = {
    val tmp = Files.createTempFile("osci-facade-", ".properties")
    Files.writeString(tmp, props)
    tmp.toFile.deleteOnExit()
    OsciFacade.fromConfigs[IO](ConfigSource.file[IO](tmp), _ => stubDvdv, LaufzettelSink.noop[IO])
  }

  test("fromConfigs boots a facade mixing pkcs12 and pem tenants") {
    val props =
      s"""tenant.alice.cert.type     = pkcs12
         |tenant.alice.cert.path     = ${resourcePath("test-cert.p12")}
         |tenant.alice.cert.password = test
         |tenant.bob.cert.type       = pem
         |tenant.bob.cert.path       = ${resourcePath("test-cert.pem")}
         |tenant.bob.cert.keyPath    = ${resourcePath("test-key.pem")}
         |""".stripMargin
    facadeFor(props).use(_ => IO.unit)
  }

  test("a tenant whose cert cannot be loaded fails the whole facade with TenantInitFailed") {
    val props =
      s"""tenant.alice.cert.type      = pkcs12
         |tenant.alice.cert.path      = ${resourcePath("test-cert.p12")}
         |tenant.alice.cert.password  = test
         |tenant.broken.cert.type     = pkcs12
         |tenant.broken.cert.path     = /nonexistent/broken.p12
         |tenant.broken.cert.password = pw
         |""".stripMargin
    facadeFor(props).use(_ => IO.unit).attempt.map {
      case Left(e: OsciError.TenantInitFailed) =>
        assertEquals(e.id, TenantId("broken"))
        assert(e.getMessage.contains("broken"), e.getMessage)
      case other => fail(s"expected TenantInitFailed, got $other")
    }
  }
}
