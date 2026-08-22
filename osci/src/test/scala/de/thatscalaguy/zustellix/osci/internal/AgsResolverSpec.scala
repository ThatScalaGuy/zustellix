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

package de.thatscalaguy.zustellix.osci.internal

import cats.effect.IO
import de.thatscalaguy.zustellix.dvdv.DvdvClient
import de.thatscalaguy.zustellix.utils.cert.CertSource
import de.thatscalaguy.zustellix.dvdv.model.*
import de.thatscalaguy.zustellix.osci.*
import munit.CatsEffectSuite

import java.math.BigInteger
import java.nio.file.Paths
import java.util.Base64

class AgsResolverSpec extends CatsEffectSuite {

  private val TestAgs = Ags.unsafe("01001000")

  private val Cfg = OsciConfig(
    tenantId   = TenantId("t"),
    certSource = Some(CertSource.Pkcs12(Paths.get("k.p12"), "pw"))
  )

  // Self-signed test cert (RSA-1024, valid for one day). `cn` makes each cert distinct
  // so tests can assert *which* key the resolver picked.
  private def mintCertB64(cn: String): String = {
    java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
    val kpg   = java.security.KeyPairGenerator.getInstance("RSA")
    kpg.initialize(1024)
    val kp    = kpg.generateKeyPair()
    val name  = new javax.security.auth.x500.X500Principal(s"CN=$cn")
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

  // Two distinct certs, generated lazily once per JVM.
  private lazy val testCertB64: String  = mintCertB64("Test")
  private lazy val otherCertB64: String = mintCertB64("Other")

  // The expected DER bytes of a base64 cert, for `sameElements` identity checks.
  private def certBytes(b64: String): Array[Byte] = Base64.getDecoder.decode(b64)

  private def stubDvdv(handler: (String, String) => IO[Option[Service]]): DvdvClient[IO] =
    new DvdvClient[IO] {
      def findServiceDescription(organizationKey: OrganizationKey, serviceSpecificationUri: String): IO[Option[Service]] =
        handler(organizationKey.value, serviceSpecificationUri)

      // Unused by the resolver
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

  private def element(
      kind:      ServiceElementType,
      uri:       Option[String],
      cipherB64: Option[String],
      name:      Option[String] = None
  ): ServiceElementInfo =
    ServiceElementInfo(
      serviceElementType            = Some(kind),
      serviceElementUri             = uri,
      cipherCertificate             = cipherB64.map(b => Certificate(content = Some(b))),
      serviceElementDescriptionName = name,
      serviceElementId              = Some(7L),
      providerId                    = Some(9L)
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

  test("resolve returns OsciRoute with addressee + intermediary from the same service description") {
    val dvdv = stubDvdv {
      case ("ags:01001000", "http://www.osci.de/xmeld2605/xmeld2605Personensuche.wsdl") =>
        IO.pure(Some(serviceWithElements(List(
          element(ServiceElementType.OSCI_ADDRESSEE,    Some("https://recipient/osci"), Some(testCertB64)),
          element(ServiceElementType.OSCI_INTERMEDIARY, Some("https://intermed/osci"),  Some(testCertB64))
        ))))
      case other => IO.raiseError(new AssertionError(s"unexpected: $other"))
    }
    AgsResolver[IO](dvdv, Cfg).resolve(TestAgs).map { route =>
      assertEquals(route.addresseeUri.toString, "https://recipient/osci")
      assertEquals(route.intermedUri.toString,  "https://intermed/osci")
      assert(route.addresseeCipher != null)
      assert(route.intermedCipher  != null)
      assertEquals(route.addresseeSig, None)
    }
  }

  test("resolve raises AgsNotInDvdv when DVDV returns None") {
    val unknown = Ags.unsafe("99999999")
    val dvdv    = stubDvdv((_, _) => IO.pure(None))
    AgsResolver[IO](dvdv, Cfg).resolve(unknown).attempt.map {
      case Left(OsciError.AgsNotInDvdv(ags, _)) => assertEquals(ags, unknown)
      case other                                     => fail(s"unexpected: $other")
    }
  }

  test("resolve raises ServiceElementMissing when OSCI_INTERMEDIARY is absent") {
    val dvdv = stubDvdv((_, _) => IO.pure(Some(serviceWithElements(List(
      element(ServiceElementType.OSCI_ADDRESSEE, Some("https://recipient/osci"), Some(testCertB64))
    )))))
    AgsResolver[IO](dvdv, Cfg).resolve(TestAgs).attempt.map {
      case Left(OsciError.ServiceElementMissing(TestAgs, "OSCI_INTERMEDIARY")) => ()
      case other                                                                    => fail(s"unexpected: $other")
    }
  }

  test("resolve raises RecipientCertMissing when an element has no cipher cert") {
    val dvdv = stubDvdv((_, _) => IO.pure(Some(serviceWithElements(List(
      element(ServiceElementType.OSCI_ADDRESSEE,    Some("https://recipient/osci"), None),
      element(ServiceElementType.OSCI_INTERMEDIARY, Some("https://intermed/osci"),  Some(testCertB64))
    )))))
    AgsResolver[IO](dvdv, Cfg).resolve(TestAgs).attempt.map {
      case Left(e: OsciError.RecipientCertMissing) =>
        assertEquals(e.ags, TestAgs)
        assertEquals(e.kind, "OSCI_ADDRESSEE")
      case other => fail(s"unexpected: $other")
    }
  }

  test("addressee inline cipher wins over a foreign standalone CIPHER_CERTIFICATE element") {
    val dvdv = stubDvdv((_, _) => IO.pure(Some(serviceWithElements(List(
      element(ServiceElementType.OSCI_ADDRESSEE,     Some("https://recipient/osci"), Some(testCertB64),  name = Some("addr")),
      element(ServiceElementType.OSCI_INTERMEDIARY,  Some("https://intermed/osci"),  Some(testCertB64),  name = Some("intm")),
      element(ServiceElementType.CIPHER_CERTIFICATE, Some("https://other/cipher"),   Some(otherCertB64), name = Some("intm"))
    )))))
    AgsResolver[IO](dvdv, Cfg).resolve(TestAgs).map { route =>
      assert(route.addresseeCipher.getEncoded.sameElements(certBytes(testCertB64)),
             "addressee cipher must be the inline cert, not the foreign standalone one")
    }
  }

  test("addressee falls back to a standalone CIPHER_CERTIFICATE with a matching name") {
    val dvdv = stubDvdv((_, _) => IO.pure(Some(serviceWithElements(List(
      element(ServiceElementType.OSCI_ADDRESSEE,     Some("https://recipient/osci"), None,               name = Some("addr")),
      element(ServiceElementType.OSCI_INTERMEDIARY,  Some("https://intermed/osci"),  Some(testCertB64),  name = Some("intm")),
      element(ServiceElementType.CIPHER_CERTIFICATE, Some("https://addr/cipher"),    Some(otherCertB64), name = Some("addr"))
    )))))
    AgsResolver[IO](dvdv, Cfg).resolve(TestAgs).map { route =>
      assert(route.addresseeCipher.getEncoded.sameElements(certBytes(otherCertB64)),
             "addressee cipher must be the matching standalone cert")
    }
  }

  test("addressee raises RecipientCertMissing when the only standalone cipher has a non-matching name") {
    val dvdv = stubDvdv((_, _) => IO.pure(Some(serviceWithElements(List(
      element(ServiceElementType.OSCI_ADDRESSEE,     Some("https://recipient/osci"), None,               name = Some("addr")),
      element(ServiceElementType.OSCI_INTERMEDIARY,  Some("https://intermed/osci"),  Some(testCertB64),  name = Some("intm")),
      element(ServiceElementType.CIPHER_CERTIFICATE, Some("https://other/cipher"),   Some(otherCertB64), name = Some("intm"))
    )))))
    AgsResolver[IO](dvdv, Cfg).resolve(TestAgs).attempt.map {
      case Left(e: OsciError.RecipientCertMissing) =>
        assertEquals(e.ags, TestAgs)
        assertEquals(e.kind, "OSCI_ADDRESSEE")
      case other => fail(s"unexpected: $other")
    }
  }

  test("resolve raises ServiceElementMissing when the addressee has no serviceElementUri") {
    val dvdv = stubDvdv((_, _) => IO.pure(Some(serviceWithElements(List(
      element(ServiceElementType.OSCI_ADDRESSEE,    None,                           Some(testCertB64)),
      element(ServiceElementType.OSCI_INTERMEDIARY, Some("https://intermed/osci"),  Some(testCertB64))
    )))))
    AgsResolver[IO](dvdv, Cfg).resolve(TestAgs).attempt.map {
      case Left(OsciError.ServiceElementMissing(TestAgs, "OSCI_ADDRESSEE")) => ()
      case other                                                                 => fail(s"unexpected: $other")
    }
  }

  test("resolve raises ServiceElementMissing when the intermediary URI is blank") {
    val dvdv = stubDvdv((_, _) => IO.pure(Some(serviceWithElements(List(
      element(ServiceElementType.OSCI_ADDRESSEE,    Some("https://recipient/osci"), Some(testCertB64)),
      element(ServiceElementType.OSCI_INTERMEDIARY, Some("   "),                    Some(testCertB64))
    )))))
    AgsResolver[IO](dvdv, Cfg).resolve(TestAgs).attempt.map {
      case Left(OsciError.ServiceElementMissing(TestAgs, "OSCI_INTERMEDIARY")) => ()
      case other                                                                    => fail(s"unexpected: $other")
    }
  }

  test("intermediary inline cipher wins over a standalone CIPHER_CERTIFICATE element") {
    val dvdv = stubDvdv((_, _) => IO.pure(Some(serviceWithElements(List(
      element(ServiceElementType.OSCI_ADDRESSEE,     Some("https://recipient/osci"), Some(testCertB64),  name = Some("addr")),
      element(ServiceElementType.OSCI_INTERMEDIARY,  Some("https://intermed/osci"),  Some(testCertB64),  name = Some("intm")),
      element(ServiceElementType.CIPHER_CERTIFICATE, Some("https://other/cipher"),   Some(otherCertB64), name = Some("intm"))
    )))))
    AgsResolver[IO](dvdv, Cfg).resolve(TestAgs).map { route =>
      assert(route.intermedCipher.getEncoded.sameElements(certBytes(testCertB64)),
             "intermediary cipher must be the inline cert, not the standalone one")
    }
  }

  test("intermediary falls back to a standalone CIPHER_CERTIFICATE with a matching name") {
    val dvdv = stubDvdv((_, _) => IO.pure(Some(serviceWithElements(List(
      element(ServiceElementType.OSCI_ADDRESSEE,     Some("https://recipient/osci"), Some(testCertB64),  name = Some("addr")),
      element(ServiceElementType.OSCI_INTERMEDIARY,  Some("https://intermed/osci"),  None,               name = Some("intm")),
      element(ServiceElementType.CIPHER_CERTIFICATE, Some("https://intm/cipher"),    Some(otherCertB64), name = Some("intm"))
    )))))
    AgsResolver[IO](dvdv, Cfg).resolve(TestAgs).map { route =>
      assert(route.intermedCipher.getEncoded.sameElements(certBytes(otherCertB64)),
             "intermediary cipher must be the matching standalone cert")
    }
  }

  test("intermediary raises RecipientCertMissing when the only standalone cipher has a non-matching name") {
    val dvdv = stubDvdv((_, _) => IO.pure(Some(serviceWithElements(List(
      element(ServiceElementType.OSCI_ADDRESSEE,     Some("https://recipient/osci"), Some(testCertB64),  name = Some("addr")),
      element(ServiceElementType.OSCI_INTERMEDIARY,  Some("https://intermed/osci"),  None,               name = Some("intm")),
      element(ServiceElementType.CIPHER_CERTIFICATE, Some("https://other/cipher"),   Some(otherCertB64), name = Some("addr"))
    )))))
    AgsResolver[IO](dvdv, Cfg).resolve(TestAgs).attempt.map {
      case Left(e: OsciError.RecipientCertMissing) =>
        assertEquals(e.ags, TestAgs)
        assertEquals(e.kind, "OSCI_INTERMEDIARY")
      case other => fail(s"unexpected: $other")
    }
  }
}
