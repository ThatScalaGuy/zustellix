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
import de.thatscalaguy.zustellix.osci.OsciError
import de.thatscalaguy.zustellix.utils.cert.{CertCredential, CertSource}
import munit.CatsEffectSuite

import java.nio.file.{Files, Path, Paths}

/** Which `CertSource` shapes the bridge accepts. osci-bibliothek's
 *  `PKCS12Signer`/`PKCS12Decrypter` take a PKCS12 stream only, so the PEM
 *  variants are converted to an in-memory PKCS12 first — all four shapes
 *  build an Originator.
 */
class OsciBibBridgeCertSpec extends CatsEffectSuite {

  private def resourcePath(name: String): Path =
    Paths.get(getClass.getClassLoader.getResource(name).toURI)

  private def p12Path: Path = resourcePath("test-cert.p12")

  test("Pkcs12 builds an Originator") {
    OsciBibBridge.originator[IO](CertSource.Pkcs12(p12Path, "test")).map(o => assert(o != null))
  }

  test("Pkcs12Bytes builds an Originator with the same certificate as Pkcs12") {
    for {
      bytes     <- IO.blocking(Files.readAllBytes(p12Path))
      fromBytes <- OsciBibBridge.originator[IO](CertSource.Pkcs12Bytes(bytes, "test"))
      fromPath  <- OsciBibBridge.originator[IO](CertSource.Pkcs12(p12Path, "test"))
    } yield assertEquals(fromBytes.getSignatureCertificate, fromPath.getSignatureCertificate)
  }

  test("Pem builds an Originator with the same certificate as Pkcs12") {
    for {
      fromPem  <- OsciBibBridge.originator[IO](
                    CertSource.Pem(resourcePath("test-cert.pem"), resourcePath("test-key.pem"))
                  )
      fromP12  <- OsciBibBridge.originator[IO](CertSource.Pkcs12(p12Path, "test"))
    } yield assertEquals(fromPem.getSignatureCertificate, fromP12.getSignatureCertificate)
  }

  test("PemBytes builds an Originator with the same certificate as Pkcs12") {
    for {
      certBytes <- IO.blocking(Files.readAllBytes(resourcePath("test-cert.pem")))
      keyBytes  <- IO.blocking(Files.readAllBytes(resourcePath("test-key.pem")))
      fromPem   <- OsciBibBridge.originator[IO](CertSource.PemBytes(certBytes, keyBytes))
      fromP12   <- OsciBibBridge.originator[IO](CertSource.Pkcs12(p12Path, "test"))
    } yield assertEquals(fromPem.getSignatureCertificate, fromP12.getSignatureCertificate)
  }

  test("wrong PKCS12 password raises OsciError.Certificate") {
    interceptIO[OsciError.Certificate](
      OsciBibBridge.originator[IO](CertSource.Pkcs12(p12Path, "wrong-password"))
    ).map(e => assert(e.getCause != null, "cause must be preserved"))
  }

  test("missing PKCS12 file raises OsciError.Certificate") {
    interceptIO[OsciError.Certificate](
      OsciBibBridge.originator[IO](CertSource.Pkcs12(Paths.get("/no/such/file.p12"), "test"))
    ).map(e => assert(e.getCause.isInstanceOf[java.nio.file.NoSuchFileException]))
  }

  test("corrupt PKCS12 bytes via CertCredential raise OsciError.Certificate") {
    interceptIO[OsciError.Certificate](
      OsciBibBridge.originator[IO](CertCredential(Array[Byte](1, 2, 3), "test"))
    )
  }

  test("wrong password via CertCredential raises OsciError.Certificate") {
    IO.blocking(Files.readAllBytes(p12Path)).flatMap { bytes =>
      interceptIO[OsciError.Certificate](
        OsciBibBridge.originator[IO](CertCredential(bytes, "wrong-password"))
      )
    }
  }

  test("unparseable PEM bytes fail") {
    interceptIO[IllegalArgumentException](
      OsciBibBridge.originator[IO](CertSource.PemBytes(Array.emptyByteArray, Array.emptyByteArray))
    ).map { e =>
      assert(
        e.getMessage.contains("No certificate found") || e.getMessage.contains("No private key found"),
        e.getMessage
      )
    }
  }
}
