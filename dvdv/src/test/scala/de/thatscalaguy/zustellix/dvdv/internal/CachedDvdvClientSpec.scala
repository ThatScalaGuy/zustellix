package de.thatscalaguy.zustellix.dvdv.internal

import cats.effect.{IO, Ref}
import de.thatscalaguy.zustellix.dvdv.{CacheConfig, DvdvClient}
import de.thatscalaguy.zustellix.dvdv.model.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

class CachedDvdvClientSpec extends CatsEffectSuite {

  private def countingClient(counter: Ref[IO, Int]): DvdvClient[IO] = new DvdvClient[IO] {
    private def bump[A](a: A): IO[A] = counter.update(_ + 1).as(a)

    def categories = bump(List.empty[DirectoryOrganizationCategoryLevel1DTO])
    def intermediaries = bump(List.empty[SummaryServiceElementDTO])
    def serviceVersion = bump(ServiceVersion(raw = Some("v")))
    def findAuthorityDescription(c: Category, k: OrganizationKey) = bump(Option.empty[OrganizationDescription])
    def findAuthorityDescriptions(k: OrganizationKey) = bump(List.empty[OrganizationDescription])
    def findCategories(fp: Fingerprint, k: OrganizationKey) = bump(List.empty[String])
    def findCertificateByFingerprint(fp: Fingerprint) = bump(Option.empty[Certificate])
    def findOrganizationsByServiceElement(s: ServiceElementType, p: ParameterType, v: String) =
      bump(List.empty[LightweightOrganization])
    def findOrganizationsByServiceElement(c: String, p: ParameterType, v: String) =
      bump(List.empty[LightweightOrganization])
    def findServiceDescription(k: OrganizationKey, u: String) = bump(Option.empty[Service])
    def findServiceSpecificationUrisByCategory(c: Category) = bump(List.empty[String])
    def verifyCategory(fp: Fingerprint, c: Category) = bump(VerificationResult(true))
    def batchFindAuthorityDescription(r: List[Request]) = bump(List.empty[Option[OrganizationDescription]])
    def batchFindCategories(r: List[Request]) = bump(List.empty[List[String]])
    def batchFindOrganizationsByServiceElement(r: List[Request]) = bump(List.empty[List[LightweightOrganization]])
    def batchFindServiceDescription(r: List[Request]) = bump(List.empty[Option[Service]])
    def batchFindServiceSpecificationUrisByCategory(r: List[Request]) = bump(List.empty[List[String]])
    def batchVerifyCategory(r: List[Request]) = bump(List.empty[VerificationResult])
  }

  test("categories is cached: two calls hit the underlying client once") {
    val cfg = CacheConfig(categoriesTtl = 1.hour)
    for {
      counter   <- Ref.of[IO, Int](0)
      underlying = countingClient(counter)
      cached    <- CachedDvdvClient.make[IO](underlying, cfg)
      _         <- cached.categories
      _         <- cached.categories
      n         <- counter.get
    } yield assertEquals(n, 1)
  }

  test("serviceVersion is not cached: two calls hit the underlying client twice") {
    val cfg = CacheConfig()
    for {
      counter   <- Ref.of[IO, Int](0)
      underlying = countingClient(counter)
      cached    <- CachedDvdvClient.make[IO](underlying, cfg)
      _         <- cached.serviceVersion
      _         <- cached.serviceVersion
      n         <- counter.get
    } yield assertEquals(n, 2)
  }

  test("disabled cache delegates everything") {
    for {
      counter   <- Ref.of[IO, Int](0)
      underlying = countingClient(counter)
      cached    <- CachedDvdvClient.make[IO](underlying, CacheConfig.disabled)
      _         <- cached.categories
      _         <- cached.categories
      n         <- counter.get
    } yield assertEquals(n, 2)
  }

  test("findAuthorityDescription cache keys on (category, orgKey)") {
    val (a, b)   = (Category.unsafe("a"), Category.unsafe("b"))
    val (k1, k2) = (OrganizationKey.unsafe("ags:00000001"), OrganizationKey.unsafe("ags:00000002"))
    for {
      counter   <- Ref.of[IO, Int](0)
      underlying = countingClient(counter)
      cached    <- CachedDvdvClient.make[IO](underlying, CacheConfig())
      _         <- cached.findAuthorityDescription(a, k1)
      _         <- cached.findAuthorityDescription(a, k1)
      _         <- cached.findAuthorityDescription(b, k1)
      _         <- cached.findAuthorityDescription(a, k2)
      n         <- counter.get
    } yield assertEquals(n, 3) // (a,k1) once, (b,k1) once, (a,k2) once
  }

  test("colon-separated uppercase and plain lowercase fingerprint are ONE cache entry") {
    val colon = Fingerprint.unsafe("02:72:C5:6C:97:42:A6:25:01:32:9A:3A:A7:89:74:F1:60:5C:92:A2")
    val plain = Fingerprint.unsafe("0272c56c9742a62501329a3aa78974f1605c92a2")
    for {
      counter   <- Ref.of[IO, Int](0)
      underlying = countingClient(counter)
      cached    <- CachedDvdvClient.make[IO](underlying, CacheConfig())
      _         <- cached.findCertificateByFingerprint(colon)
      _         <- cached.findCertificateByFingerprint(plain)
      n         <- counter.get
    } yield assertEquals(n, 1) // both spellings normalize to the same key
  }

  test("enum and custom findOrganizationsByServiceElement overloads memoize independently") {
    for {
      counter   <- Ref.of[IO, Int](0)
      underlying = countingClient(counter)
      cached    <- CachedDvdvClient.make[IO](underlying, CacheConfig())
      _         <- cached.findOrganizationsByServiceElement(ServiceElementType.OSCI_ADDRESSEE, ParameterType.URI, "v")
      _         <- cached.findOrganizationsByServiceElement(ServiceElementType.OSCI_ADDRESSEE, ParameterType.URI, "v")
      _         <- cached.findOrganizationsByServiceElement("CUSTOM_TYPE", ParameterType.URI, "v")
      _         <- cached.findOrganizationsByServiceElement("CUSTOM_TYPE", ParameterType.URI, "v")
      n         <- counter.get
    } yield assertEquals(n, 2) // one backend call per overload key
  }
}
