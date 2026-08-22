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

package de.thatscalaguy.zustellix.osci

import cats.effect.{IO, Ref, Resource}
import de.thatscalaguy.zustellix.dvdv.DvdvClient
import de.thatscalaguy.zustellix.dvdv.model.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

import java.nio.file.{Files, Path, Paths}

/** Boots `OsciFacade.fromConfigs` from a properties file. Boot only touches
 *  the tenant certs — DVDV is consulted per send, never during acquisition —
 *  so the stub client can leave every method unimplemented. Dispatch through
 *  the built facade is covered separately over `fromRegistry` with recording
 *  clients.
 */
class OsciFacadeSpec extends CatsEffectSuite {

  private given LoggerFactory[IO] = NoOpFactory[IO]

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

  // Dispatch semantics of the facade over the registry (FacadeImpl via the
  // public fromRegistry): the tenant id selects the client, ags/xml pass
  // through unchanged, and the client's result / failure comes back as-is.

  private def recordingClient(
      tenant: TenantId,
      calls:  Ref[IO, List[(TenantId, Ags, String)]]
  ): OsciClient[IO] =
    new OsciClient[IO] {
      def request(ags: Ags, xml: String): IO[OsciResponse] =
        calls.update(_ :+ (tenant, ags, xml)).as(
          OsciResponse(Some(s"<rsp>${tenant.value}</rsp>"), s"msg-${tenant.value}", "0800")
        )
      def send(ags: Ags, xml: String): IO[OsciReceipt] =
        calls.update(_ :+ (tenant, ags, xml)).as(
          OsciReceipt(s"msg-${tenant.value}", "0800", None)
        )
    }

  private def dispatchFacade: IO[(OsciFacade[IO], Ref[IO, List[(TenantId, Ags, String)]])] =
    IO.ref(List.empty[(TenantId, Ags, String)]).map { calls =>
      val registry = TenantRegistry.inMemory[IO](
        Map(
          TenantId("alice") -> recordingClient(TenantId("alice"), calls),
          TenantId("bob")   -> recordingClient(TenantId("bob"), calls)
        )
      )
      (OsciFacade.fromRegistry[IO](registry), calls)
    }

  private val ags = Ags.unsafe("01001000")

  test("request routes to the named tenant's client and passes ags/xml through") {
    for {
      fc            <- dispatchFacade
      (facade, calls) = fc
      rsp           <- facade.request(TenantId("bob"), ags, "<xml>q</xml>")
      recorded      <- calls.get
    } yield {
      assertEquals(rsp, OsciResponse(Some("<rsp>bob</rsp>"), "msg-bob", "0800"))
      assertEquals(recorded, List((TenantId("bob"), ags, "<xml>q</xml>")))
    }
  }

  test("send routes to the named tenant's client and returns its receipt") {
    for {
      fc            <- dispatchFacade
      (facade, calls) = fc
      receipt       <- facade.send(TenantId("alice"), ags, "<xml>s</xml>")
      recorded      <- calls.get
    } yield {
      assertEquals(receipt, OsciReceipt("msg-alice", "0800", None))
      assertEquals(recorded, List((TenantId("alice"), ags, "<xml>s</xml>")))
    }
  }

  test("an unknown tenant raises UnknownTenant carrying the id, and no client is touched") {
    for {
      fc            <- dispatchFacade
      (facade, calls) = fc
      e1            <- interceptIO[OsciError.UnknownTenant](facade.request(TenantId("nobody"), ags, "<xml/>"))
      e2            <- interceptIO[OsciError.UnknownTenant](facade.send(TenantId("nobody"), ags, "<xml/>"))
      recorded      <- calls.get
    } yield {
      assertEquals(e1.id, TenantId("nobody"))
      assertEquals(e2.id, TenantId("nobody"))
      assertEquals(recorded, Nil)
    }
  }

  test("a client failure propagates unwrapped through the facade") {
    val boom = OsciError.AgsNotInDvdv(ags, "http://example/wsdl")
    val failing = new OsciClient[IO] {
      def request(ags: Ags, xml: String): IO[OsciResponse] = IO.raiseError(boom)
      def send(ags: Ags, xml: String): IO[OsciReceipt]     = IO.raiseError(boom)
    }
    val facade = OsciFacade.fromRegistry[IO](
      TenantRegistry.inMemory[IO](Map(TenantId("alice") -> failing))
    )
    for {
      r1 <- facade.request(TenantId("alice"), ags, "<xml/>").attempt
      r2 <- facade.send(TenantId("alice"), ags, "<xml/>").attempt
    } yield {
      assertEquals(r1, Left(boom))
      assertEquals(r2, Left(boom))
    }
  }

  test("tenants lists the registry's tenant ids") {
    for {
      fc          <- dispatchFacade
      (facade, _)  = fc
      ids         <- facade.tenants
    } yield assertEquals(ids, Set(TenantId("alice"), TenantId("bob")))
  }

  // Mailbox dispatch: facade.mailbox selects the tenant's mailbox, so its
  // whole algebra — pending/fetch/drain — is reachable per tenant.

  private def recordingMailbox(
      tenant: TenantId,
      calls:  Ref[IO, List[(TenantId, Int)]]
  ): OsciMailbox[IO] =
    new OsciMailbox[IO] {
      def pending: IO[PendingPage] = IO.pure(PendingPage(Nil))
      def fetch(messageId: String): IO[OsciMessage] =
        IO.pure(
          OsciMessage(messageId, None, s"<xml>${tenant.value}</xml>", None, None, ContentSignatureStatus.Valid)
        )
      def drain(maxMessages: Int): IO[MailboxDrain] =
        calls.update(_ :+ (tenant, maxMessages)).as(
          MailboxDrain(PendingPage(List(PendingDelivery(s"pending-${tenant.value}", None, None))), Nil)
        )
    }

  private def mailboxFacade: IO[(OsciFacade[IO], Ref[IO, List[(TenantId, Int)]])] =
    IO.ref(List.empty[(TenantId, Int)]).map { calls =>
      val registry = TenantRegistry.inMemory[IO](
        Map.empty[TenantId, OsciClient[IO]],
        Map(
          TenantId("alice") -> recordingMailbox(TenantId("alice"), calls),
          TenantId("bob")   -> recordingMailbox(TenantId("bob"), calls)
        )
      )
      (OsciFacade.fromRegistry[IO](registry), calls)
    }

  test("mailbox routes to the named tenant's mailbox and drain is reachable through it") {
    for {
      fc            <- mailboxFacade
      (facade, calls) = fc
      drained       <- facade.mailbox(TenantId("bob")).flatMap(_.drain(3))
      recorded      <- calls.get
    } yield {
      assertEquals(drained.page.deliveries.map(_.messageId), List("pending-bob"))
      assertEquals(recorded, List((TenantId("bob"), 3)))
    }
  }

  test("mailbox for a tenant without a registered mailbox raises UnknownTenant") {
    for {
      fc            <- mailboxFacade
      (facade, calls) = fc
      e             <- interceptIO[OsciError.UnknownTenant](facade.mailbox(TenantId("nobody")))
      recorded      <- calls.get
    } yield {
      assertEquals(e.id, TenantId("nobody"))
      assertEquals(recorded, Nil)
    }
  }
}
