package de.thatscalaguy.zustellix.dvdv.auth

import cats.effect.IO
import de.thatscalaguy.zustellix.dvdv.DvdvConfig
import de.thatscalaguy.zustellix.utils.cert.{CertLoader, CertSource}
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.implicits.uri
import pdi.jwt.{Jwt, JwtAlgorithm}

import java.nio.file.Paths
import scala.concurrent.duration.*

class JwtFactorySpec extends CatsEffectSuite {

  private def resourcePath(name: String) =
    Paths.get(getClass.getClassLoader.getResource(name).toURI)

  private val cfg = DvdvConfig(
    baseUri    = uri"https://dvdv.example",
    certSource = CertSource.Pkcs12(resourcePath("test-cert.p12"), "test"),
    jwtLifetime = 60.seconds
  )

  test("produces a JWT verifiable with the cert's public key and has sub=fp:<fingerprint>") {
    for {
      loaded <- CertLoader.load[IO](cfg.certSource)
      token  <- JwtFactory.make[IO](cfg, loaded)
      decoded = Jwt.decode(token, loaded.certificate.getPublicKey, Seq(JwtAlgorithm.RS256))
    } yield {
      assert(decoded.isSuccess, s"JWT verification failed: $decoded")
      val claim   = decoded.get
      val payload = parse(claim.content).toOption.flatMap(_.asObject).get
      val expectedSub = s"fp:${loaded.fingerprintSha1Hex}"
      assertEquals(payload("sub").flatMap(_.asString), Some(expectedSub))
      assertEquals(payload("iss").flatMap(_.asString), Some(expectedSub))
      assertEquals(payload("aud").flatMap(_.asString), Some(cfg.tokenUri.renderString))
      assert(claim.issuedAt.isDefined)
      assert(claim.expiration.isDefined)
      assertEquals(claim.expiration.get - claim.issuedAt.get, 60L)
      assert(claim.jwtId.isDefined)
    }
  }
}
