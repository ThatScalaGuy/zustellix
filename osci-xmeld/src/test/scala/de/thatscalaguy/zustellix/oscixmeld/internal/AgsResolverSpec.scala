package de.thatscalaguy.zustellix.oscixmeld.internal

import cats.effect.IO
import de.thatscalaguy.zustellix.dvdv.DvdvClient
import de.thatscalaguy.zustellix.utils.cert.CertSource
import de.thatscalaguy.zustellix.dvdv.model.*
import de.thatscalaguy.zustellix.oscixmeld.*
import munit.CatsEffectSuite

import java.math.BigInteger
import java.nio.file.Paths
import java.util.Base64

class AgsResolverSpec extends CatsEffectSuite {

  private val Cfg = OSCIXMeldConfig(
    tenantId   = TenantId("t"),
    certSource = CertSource.Pkcs12(Paths.get("k.p12"), "pw")
  )

  // Self-signed test cert (RSA-1024, valid for one day). Generated lazily once per JVM.
  private lazy val testCertB64: String = {
    java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
    val kpg   = java.security.KeyPairGenerator.getInstance("RSA")
    kpg.initialize(1024)
    val kp    = kpg.generateKeyPair()
    val name  = new javax.security.auth.x500.X500Principal("CN=Test")
    val now   = new java.util.Date()
    val later = new java.util.Date(now.getTime + 86400000L)
    val builder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
      name, BigInteger.ONE, now, later, name, kp.getPublic
    )
    val signer = new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate)
    val holder = builder.build(signer)
    val cert   = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(holder)
    Base64.getEncoder.encodeToString(cert.getEncoded)
  }

  private def stubDvdv(handler: (String, String) => IO[Option[Service]]): DvdvClient[IO] =
    new DvdvClient[IO] {
      def findServiceDescription(organizationKey: String, serviceSpecificationUri: String): IO[Option[Service]] =
        handler(organizationKey, serviceSpecificationUri)

      // Unused by the resolver
      def categories                                                       = ???
      def intermediaries                                                   = ???
      def serviceVersion                                                   = ???
      def findAuthorityDescription(c: String, o: String)                   = ???
      def findAuthorityDescriptions(o: String)                             = ???
      def findCategories(f: String, o: String)                             = ???
      def findCertificateByFingerprint(f: String)                          = ???
      def findOrganizationsByServiceElement(s: ServiceElementType, p: ParameterType, v: String) = ???
      def findServiceSpecificationUrisByCategory(c: String)                = ???
      def verifyCategory(f: String, c: String)                             = ???
      def batchFindAuthorityDescription(rs: List[Request])                 = ???
      def batchFindCategories(rs: List[Request])                           = ???
      def batchFindOrganizationsByServiceElement(rs: List[Request])        = ???
      def batchFindServiceDescription(rs: List[Request])                   = ???
      def batchFindServiceSpecificationUrisByCategory(rs: List[Request])   = ???
      def batchVerifyCategory(rs: List[Request])                           = ???
    }

  private def element(
      kind:      ServiceElementType,
      uri:       String,
      cipherB64: Option[String]
  ): ServiceElementInfo =
    ServiceElementInfo(
      serviceElementType = Some(kind),
      serviceElementUri  = Some(uri),
      cipherCertificate  = cipherB64.map(b => Certificate(content = Some(b))),
      serviceElementId   = Some(7L),
      providerId         = Some(9L)
    )

  private def serviceWithElements(elems: List[ServiceElementInfo]): Service =
    Service(
      id                            = Some(1L),
      serviceDescriptionName        = Some("x"),
      serviceSpecificationType      = Some(ServiceSpecificationType.WSDL_OSCI),
      serviceSpecificationUri       = Some("u"),
      serviceSpecificationDocument  = Some(""),
      serviceElements               = Some(elems)
    )

  test("resolve returns XmeldRoute with addressee + intermediary from the same service description") {
    val dvdv = stubDvdv {
      case ("ags:01001000", "http://www.osci.de/xmeld2605/xmeld2605Personensuche.wsdl") =>
        IO.pure(Some(serviceWithElements(List(
          element(ServiceElementType.OSCI_ADDRESSEE,    "https://recipient/osci", Some(testCertB64)),
          element(ServiceElementType.OSCI_INTERMEDIARY, "https://intermed/osci",  Some(testCertB64))
        ))))
      case other => IO.raiseError(new AssertionError(s"unexpected: $other"))
    }
    AgsResolver[IO](dvdv, Cfg).resolve("01001000").map { route =>
      assertEquals(route.addresseeUri.toString, "https://recipient/osci")
      assertEquals(route.intermedUri.toString,  "https://intermed/osci")
      assert(route.addresseeCipher != null)
      assert(route.intermedCipher  != null)
      assertEquals(route.addresseeSig, None)
    }
  }

  test("resolve raises AgsNotInDvdv when DVDV returns None") {
    val dvdv = stubDvdv((_, _) => IO.pure(None))
    AgsResolver[IO](dvdv, Cfg).resolve("nope").attempt.map {
      case Left(OSCIXMeldError.AgsNotInDvdv("nope", _)) => ()
      case other                                        => fail(s"unexpected: $other")
    }
  }

  test("resolve raises ServiceElementMissing when OSCI_INTERMEDIARY is absent") {
    val dvdv = stubDvdv((_, _) => IO.pure(Some(serviceWithElements(List(
      element(ServiceElementType.OSCI_ADDRESSEE, "https://recipient/osci", Some(testCertB64))
    )))))
    AgsResolver[IO](dvdv, Cfg).resolve("01001000").attempt.map {
      case Left(OSCIXMeldError.ServiceElementMissing("01001000", "OSCI_INTERMEDIARY")) => ()
      case other                                                                       => fail(s"unexpected: $other")
    }
  }

  test("resolve raises RecipientCertMissing when an element has no cipher cert") {
    val dvdv = stubDvdv((_, _) => IO.pure(Some(serviceWithElements(List(
      element(ServiceElementType.OSCI_ADDRESSEE,    "https://recipient/osci", None),
      element(ServiceElementType.OSCI_INTERMEDIARY, "https://intermed/osci",  Some(testCertB64))
    )))))
    AgsResolver[IO](dvdv, Cfg).resolve("01001000").attempt.map {
      case Left(e: OSCIXMeldError.RecipientCertMissing) =>
        assert(e.ags.startsWith("01001000"))
      case other => fail(s"unexpected: $other")
    }
  }
}
