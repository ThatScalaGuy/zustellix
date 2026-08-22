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

import cats.effect.IO
import de.thatscalaguy.zustellix.utils.cert.{CertCredential, CertAlias, InMemoryCertManager}
import de.osci.osci12.samples.impl.crypto.{PKCS12Decrypter, PKCS12Signer}
import munit.CatsEffectSuite

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Paths}

/** The same alias credential must drive BOTH the DVDV side (a `LoadedCert` with
 *  an RSA private key for JWT signing) and the OSCI side (osci-bibliothek's
 *  `PKCS12Signer`/`PKCS12Decrypter` from the same bytes).
 */
class CertManagerCrossModuleSpec extends CatsEffectSuite {

  java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())

  private def p12Bytes: Array[Byte] =
    Files.readAllBytes(Paths.get(getClass.getClassLoader.getResource("test-cert.p12").toURI))

  private val alias = CertAlias("test-alias")

  test("one alias credential yields both a DVDV LoadedCert and an OSCI signer/decrypter") {
    for {
      certs <- InMemoryCertManager.make[IO](Map(alias -> CertCredential(p12Bytes, "test")))
      lc    <- certs.loadedCert(alias)                 // DVDV side
      cred  <- certs.resolve(alias)                    // OSCI side
      _     <- IO.blocking {
                 val _ = new PKCS12Signer(new ByteArrayInputStream(cred.pkcs12), cred.password)
                 val _ = new PKCS12Decrypter(new ByteArrayInputStream(cred.pkcs12), cred.password)
               }
    } yield assertEquals(lc.privateKey.getAlgorithm, "RSA")
  }
}
