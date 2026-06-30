package de.thatscalaguy.zustellix.dvdv.auth

import cats.effect.Sync
import cats.syntax.all.*
import de.thatscalaguy.zustellix.dvdv.DvdvConfig
import de.thatscalaguy.zustellix.dvdv.DvdvError
import de.thatscalaguy.zustellix.utils.cert.LoadedCert
import pdi.jwt.algorithms.JwtAsymmetricAlgorithm
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}

import java.security.PrivateKey
import java.security.interfaces.{ECPrivateKey, RSAPrivateKey}
import java.time.Instant
import java.util.UUID

object JwtFactory {

  def make[F[_]: Sync](config: DvdvConfig, loaded: LoadedCert): F[String] =
    for {
      now <- Sync[F].delay(Instant.now())
      alg <- Sync[F].fromEither(algorithmFor(loaded.privateKey))
    } yield {
      val sub = s"fp:${loaded.fingerprintSha1Hex}"
      val claim = JwtClaim(
        issuer    = Some(config.issuer.getOrElse(sub)),
        subject   = Some(sub),
        audience  = Some(Set(config.tokenUri.renderString)),
        issuedAt  = Some(now.getEpochSecond),
        notBefore = Some(now.getEpochSecond),
        expiration = Some(now.plusSeconds(config.jwtLifetime.toSeconds).getEpochSecond),
        jwtId     = Some(UUID.randomUUID().toString)
      )
      Jwt.encode(claim, loaded.privateKey, alg)
    }

  private def algorithmFor(key: PrivateKey): Either[DvdvError, JwtAsymmetricAlgorithm] =
    key match {
      case _: RSAPrivateKey => Right(JwtAlgorithm.RS256)
      case ec: ECPrivateKey =>
        ec.getParams.getCurve.getField.getFieldSize match {
          case 224 | 256 => Right(JwtAlgorithm.ES256)
          case 384       => Right(JwtAlgorithm.ES384)
          case 512 | 521 => Right(JwtAlgorithm.ES512)
          case size      => Left(unsupported(s"EC key with field size $size"))
        }
      case other => Left(unsupported(other.getAlgorithm))
    }

  private def unsupported(detail: String): DvdvError =
    DvdvError.TransportError(new IllegalArgumentException(s"Unsupported signing key: $detail"))
}
