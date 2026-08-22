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

package de.thatscalaguy.zustellix.utils.cert

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.file.{Files, Paths}

class InMemoryCertManagerSpec extends CatsEffectSuite {

  private def p12Bytes: Array[Byte] =
    Files.readAllBytes(Paths.get(getClass.getClassLoader.getResource("test-cert.p12").toURI))

  private val alias = CertAlias("test-alias")
  private val cred  = CertCredential(p12Bytes, "test")

  test("resolve returns the configured credential") {
    for {
      mgr <- InMemoryCertManager.make[IO](Map(alias -> cred))
      got <- mgr.resolve(alias)
    } yield assertEquals(got.password, "test")
  }

  test("resolve raises UnknownCert for an unknown alias") {
    for {
      mgr <- InMemoryCertManager.make[IO](Map(alias -> cred))
      e   <- mgr.resolve(CertAlias("missing-alias")).attempt
    } yield e match {
      case Left(CertManagerError.UnknownCert(bad)) => assertEquals(bad, CertAlias("missing-alias"))
      case other                                   => fail(s"expected UnknownCert, got $other")
    }
  }

  test("knownAliases lists the configured aliases") {
    for {
      mgr <- InMemoryCertManager.make[IO](Map(alias -> cred))
      ks  <- mgr.knownAliases
    } yield assertEquals(ks, Set(alias))
  }

  test("loadedCert derives an RSA private key from the same bytes") {
    for {
      mgr <- InMemoryCertManager.make[IO](Map(alias -> cred))
      lc  <- mgr.loadedCert(alias)
    } yield assertEquals(lc.privateKey.getAlgorithm, "RSA")
  }

  test("loadedCert failure surfaces as LoadFailed with alias and cause") {
    val broken = CertAlias("broken")
    for {
      mgr <- InMemoryCertManager.make[IO](Map(broken -> CertCredential("garbage".getBytes, "pw")))
      e   <- mgr.loadedCert(broken).attempt
    } yield e match {
      case Left(CertManagerError.LoadFailed(a, cause)) =>
        assertEquals(a, broken)
        assert(cause != null, "LoadFailed must carry the underlying cause")
      case other => fail(s"expected LoadFailed, got $other")
    }
  }

  test("CertManagerError carries no stack trace") {
    assert(CertManagerError.UnknownCert(alias).getStackTrace.isEmpty)
  }

  test("swap hot-replaces the active map") {
    val alias2 = CertAlias("other-alias")
    for {
      mgr <- InMemoryCertManager.make[IO](Map(alias -> cred))
      _   <- mgr.swap(Map(alias2 -> cred))
      ks  <- mgr.knownAliases
      old <- mgr.resolve(alias).attempt
    } yield {
      assertEquals(ks, Set(alias2))
      assert(old.isLeft, "old alias should no longer resolve after swap")
    }
  }
}
