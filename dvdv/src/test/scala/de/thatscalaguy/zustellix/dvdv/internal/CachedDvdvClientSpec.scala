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
    def findAuthorityDescription(c: String, k: String) = bump(Option.empty[OrganizationDescription])
    def findAuthorityDescriptions(k: String) = bump(List.empty[OrganizationDescription])
    def findCategories(fp: String, k: String) = bump(List.empty[String])
    def findCertificateByFingerprint(fp: String) = bump(Option.empty[Certificate])
    def findOrganizationsByServiceElement(s: ServiceElementType, p: ParameterType, v: String) =
      bump(List.empty[LightweightOrganization])
    def findServiceDescription(k: String, u: String) = bump(Option.empty[Service])
    def findServiceSpecificationUrisByCategory(c: String) = bump(List.empty[String])
    def verifyCategory(fp: String, c: String) = bump(VerificationResult(true))
    def batchFindAuthorityDescription(r: List[Request]) = bump(List.empty[OrganizationDescription])
    def batchFindCategories(r: List[Request]) = bump(List.empty[List[String]])
    def batchFindOrganizationsByServiceElement(r: List[Request]) = bump(List.empty[List[LightweightOrganization]])
    def batchFindServiceDescription(r: List[Request]) = bump(List.empty[Service])
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
    for {
      counter   <- Ref.of[IO, Int](0)
      underlying = countingClient(counter)
      cached    <- CachedDvdvClient.make[IO](underlying, CacheConfig())
      _         <- cached.findAuthorityDescription("a", "k1")
      _         <- cached.findAuthorityDescription("a", "k1")
      _         <- cached.findAuthorityDescription("b", "k1")
      _         <- cached.findAuthorityDescription("a", "k2")
      n         <- counter.get
    } yield assertEquals(n, 3) // (a,k1) once, (b,k1) once, (a,k2) once
  }
}
