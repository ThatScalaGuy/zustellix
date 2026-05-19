package de.thatscalaguy.zustellix.oscixmeld

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
