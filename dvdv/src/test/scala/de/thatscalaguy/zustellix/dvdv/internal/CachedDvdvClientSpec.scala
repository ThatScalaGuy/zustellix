package de.thatscalaguy.zustellix.dvdv.internal

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import de.thatscalaguy.zustellix.dvdv.{CacheConfig, DvdvClient}
import de.thatscalaguy.zustellix.dvdv.model.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

class CachedDvdvClientSpec extends CatsEffectSuite {

  private def countingClient(
      counter: Ref[IO, Int],
      certResult: IO[Option[Certificate]] = IO.pure(Option.empty)
  ): DvdvClient[IO] = new DvdvClient[IO] {
    private def bump[A](a: A): IO[A] = counter.update(_ + 1).as(a)

    def categories = bump(List.empty[DirectoryOrganizationCategoryLevel1DTO])
    def intermediaries = bump(List.empty[SummaryServiceElementDTO])
    def serviceVersion = bump(ServiceVersion(raw = Some("v")))
    def findAuthorityDescription(c: Category, k: OrganizationKey) = bump(Option.empty[OrganizationDescription])
    def findAuthorityDescriptions(k: OrganizationKey) = bump(List.empty[OrganizationDescription])
    def findCategories(fp: Fingerprint, k: OrganizationKey) = bump(List.empty[String])
    def findCertificateByFingerprint(fp: Fingerprint) = counter.update(_ + 1) *> certResult
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

  private val fp = Fingerprint.unsafe("0272c56c9742a62501329a3aa78974f1605c92a2")

  test("categories is cached: two calls hit the underlying client once") {
    val cfg = CacheConfig(categoriesTtl = 1.hour)
    for {
      counter   <- Ref.of[IO, Int](0)
      underlying = countingClient(counter)
      n         <- CachedDvdvClient.make[IO](underlying, cfg).use { cached =>
                     cached.categories *> cached.categories *> counter.get
                   }
    } yield assertEquals(n, 1)
  }

  test("serviceVersion is not cached: two calls hit the underlying client twice") {
    val cfg = CacheConfig()
    for {
      counter   <- Ref.of[IO, Int](0)
      underlying = countingClient(counter)
      n         <- CachedDvdvClient.make[IO](underlying, cfg).use { cached =>
                     cached.serviceVersion *> cached.serviceVersion *> counter.get
                   }
    } yield assertEquals(n, 2)
  }

  test("disabled cache delegates everything") {
    for {
      counter   <- Ref.of[IO, Int](0)
      underlying = countingClient(counter)
      n         <- CachedDvdvClient.make[IO](underlying, CacheConfig.disabled).use { cached =>
                     cached.categories *> cached.categories *> counter.get
                   }
    } yield assertEquals(n, 2)
  }

  test("findAuthorityDescription cache keys on (category, orgKey)") {
    val (a, b)   = (Category.unsafe("a"), Category.unsafe("b"))
    val (k1, k2) = (OrganizationKey.unsafe("ags:00000001"), OrganizationKey.unsafe("ags:00000002"))
    for {
      counter   <- Ref.of[IO, Int](0)
      underlying = countingClient(counter)
      n         <- CachedDvdvClient.make[IO](underlying, CacheConfig()).use { cached =>
                     cached.findAuthorityDescription(a, k1) *>
                       cached.findAuthorityDescription(a, k1) *>
                       cached.findAuthorityDescription(b, k1) *>
                       cached.findAuthorityDescription(a, k2) *>
                       counter.get
                   }
    } yield assertEquals(n, 3) // (a,k1) once, (b,k1) once, (a,k2) once
  }

  test("colon-separated uppercase and plain lowercase fingerprint are ONE cache entry") {
    val colon = Fingerprint.unsafe("02:72:C5:6C:97:42:A6:25:01:32:9A:3A:A7:89:74:F1:60:5C:92:A2")
    val plain = Fingerprint.unsafe("0272c56c9742a62501329a3aa78974f1605c92a2")
    for {
      counter   <- Ref.of[IO, Int](0)
      underlying = countingClient(counter)
      n         <- CachedDvdvClient.make[IO](underlying, CacheConfig()).use { cached =>
                     cached.findCertificateByFingerprint(colon) *>
                       cached.findCertificateByFingerprint(plain) *>
                       counter.get
                   }
    } yield assertEquals(n, 1) // both spellings normalize to the same key
  }

  test("enum and custom findOrganizationsByServiceElement overloads memoize independently") {
    for {
      counter   <- Ref.of[IO, Int](0)
      underlying = countingClient(counter)
      n         <- CachedDvdvClient.make[IO](underlying, CacheConfig()).use { cached =>
                     cached.findOrganizationsByServiceElement(ServiceElementType.OSCI_ADDRESSEE, ParameterType.URI, "v") *>
                       cached.findOrganizationsByServiceElement(ServiceElementType.OSCI_ADDRESSEE, ParameterType.URI, "v") *>
                       cached.findOrganizationsByServiceElement("CUSTOM_TYPE", ParameterType.URI, "v") *>
                       cached.findOrganizationsByServiceElement("CUSTOM_TYPE", ParameterType.URI, "v") *>
                       counter.get
                   }
    } yield assertEquals(n, 2) // one backend call per overload key
  }

  test("expired entries are recomputed after their TTL") {
    val cfg = CacheConfig(findCertificateByFingerprintTtl = 50.millis)
    for {
      counter   <- Ref.of[IO, Int](0)
      underlying = countingClient(counter)
      n         <- CachedDvdvClient.make[IO](underlying, cfg).use { cached =>
                     cached.findCertificateByFingerprint(fp) *>
                       IO.sleep(150.millis) *>
                       cached.findCertificateByFingerprint(fp) *>
                       counter.get
                   }
    } yield assertEquals(n, 2)
  }

  test("a None result is re-fetched after negativeTtl, well before the endpoint TTL") {
    val cfg = CacheConfig(findCertificateByFingerprintTtl = 10.seconds, negativeTtl = 50.millis)
    for {
      counter   <- Ref.of[IO, Int](0)
      underlying = countingClient(counter) // cert lookup returns None
      n         <- CachedDvdvClient.make[IO](underlying, cfg).use { cached =>
                     cached.findCertificateByFingerprint(fp) *>
                       cached.findCertificateByFingerprint(fp) *> // still cached
                       IO.sleep(150.millis) *>
                       cached.findCertificateByFingerprint(fp) *>
                       counter.get
                   }
    } yield assertEquals(n, 2)
  }

  test("a Some result keeps the endpoint TTL past negativeTtl") {
    val cfg  = CacheConfig(findCertificateByFingerprintTtl = 10.seconds, negativeTtl = 50.millis)
    val cert = Certificate(fingerprint = Some(fp.value))
    for {
      counter   <- Ref.of[IO, Int](0)
      underlying = countingClient(counter, certResult = IO.pure(Some(cert)))
      results   <- CachedDvdvClient.make[IO](underlying, cfg).use { cached =>
                     for {
                       first  <- cached.findCertificateByFingerprint(fp)
                       _      <- IO.sleep(150.millis)
                       second <- cached.findCertificateByFingerprint(fp)
                     } yield (first, second)
                   }
      n         <- counter.get
    } yield {
      assertEquals(results, (Some(cert), Some(cert)))
      assertEquals(n, 1)
    }
  }

  test("background fiber purges an expired entry without re-access") {
    val cfg = CacheConfig(findCertificateByFingerprintTtl = 50.millis, purgeInterval = 20.millis)
    for {
      counter   <- Ref.of[IO, Int](0)
      underlying = countingClient(counter)
      deleted   <- Ref.of[IO, Int](0)
      caches0   <- CachedDvdvClient.mkCaches[IO](cfg)
      caches     = caches0.copy(certByFpC = caches0.certByFpC.withOnDelete(_ => deleted.update(_ + 1)))
      d         <- CachedDvdvClient.fromCaches(underlying, caches, cfg).use { cached =>
                     cached.findCertificateByFingerprint(fp) *> IO.sleep(300.millis) *> deleted.get
                   }
      n         <- counter.get
    } yield {
      assert(d >= 1, s"expected the purge fiber to reclaim the expired entry, deleted = $d")
      assertEquals(n, 1) // reclaimed without a second lookup
    }
  }

  test("purge fiber stops when the Resource is released") {
    val cfg = CacheConfig(findCertificateByFingerprintTtl = 100.millis, purgeInterval = 20.millis)
    for {
      counter   <- Ref.of[IO, Int](0)
      underlying = countingClient(counter)
      deleted   <- Ref.of[IO, Int](0)
      caches0   <- CachedDvdvClient.mkCaches[IO](cfg)
      caches     = caches0.copy(certByFpC = caches0.certByFpC.withOnDelete(_ => deleted.update(_ + 1)))
      _         <- CachedDvdvClient.fromCaches(underlying, caches, cfg).use { cached =>
                     cached.findCertificateByFingerprint(fp).void
                   }
      _         <- IO.sleep(300.millis) // entry expires, but the fiber is gone
      d         <- deleted.get
    } yield assertEquals(d, 0)
  }

  test("concurrent misses of the same cold key hit the underlying client once") {
    for {
      counter   <- Ref.of[IO, Int](0)
      gate      <- IO.deferred[Unit]
      underlying = countingClient(counter, certResult = gate.get.as(Option.empty[Certificate]))
      results   <- CachedDvdvClient.make[IO](underlying, CacheConfig()).use { cached =>
                     for {
                       fibers  <- cached.findCertificateByFingerprint(fp).start.replicateA(8)
                       _       <- IO.sleep(100.millis) // winner is in the backend, the rest are parked
                       _       <- gate.complete(())
                       results <- fibers.traverse(_.joinWithNever)
                     } yield results
                   }
      n         <- counter.get
    } yield {
      assertEquals(results, List.fill(8)(Option.empty[Certificate]))
      assertEquals(n, 1)
    }
  }

  test("a failing computation is not cached: the next call retries") {
    val boom = new RuntimeException("boom")
    for {
      counter   <- Ref.of[IO, Int](0)
      // counter is bumped before certResult runs: call 1 fails, call 2 succeeds
      certResult = counter.get.flatMap(n => if (n == 1) IO.raiseError(boom) else IO.pure(Option.empty[Certificate]))
      underlying = countingClient(counter, certResult)
      results   <- CachedDvdvClient.make[IO](underlying, CacheConfig()).use { cached =>
                     for {
                       first  <- cached.findCertificateByFingerprint(fp).attempt
                       second <- cached.findCertificateByFingerprint(fp).attempt
                     } yield (first, second)
                   }
      n         <- counter.get
    } yield {
      assertEquals(results._1, Left(boom))
      assertEquals(results._2, Right(Option.empty[Certificate]))
      assertEquals(n, 2)
    }
  }

  test("waiters see the winner's failure and the key retries afterwards") {
    val boom = new RuntimeException("boom")
    for {
      counter   <- Ref.of[IO, Int](0)
      gate      <- IO.deferred[Unit]
      underlying = countingClient(counter, certResult = gate.get *> IO.raiseError[Option[Certificate]](boom))
      results   <- CachedDvdvClient.make[IO](underlying, CacheConfig()).use { cached =>
                     // attempt inside the fibers: an errored outcome that completes
                     // before join would otherwise be reported as unhandled
                     for {
                       f1 <- cached.findCertificateByFingerprint(fp).attempt.start
                       f2 <- cached.findCertificateByFingerprint(fp).attempt.start
                       _  <- IO.sleep(100.millis)
                       _  <- gate.complete(())
                       r1 <- f1.joinWithNever
                       r2 <- f2.joinWithNever
                       r3 <- cached.findCertificateByFingerprint(fp).attempt
                     } yield (r1, r2, r3)
                   }
      n         <- counter.get
    } yield {
      assertEquals(results._1, Left(boom))
      assertEquals(results._2, Left(boom))
      assertEquals(results._3, Left(boom)) // gate already open: the retry fails directly
      assertEquals(n, 2) // the flight was removed on failure, so the third call recomputed
    }
  }
}
