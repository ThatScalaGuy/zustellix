package de.thatscalaguy.zustellix.dvdv.auth

import cats.effect.Sync
import cats.syntax.all.*
import de.thatscalaguy.zustellix.dvdv.DvdvConfig
import de.thatscalaguy.zustellix.utils.cert.LoadedCert
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}

import java.time.Instant
import java.util.UUID

object JwtFactory {

  def make[F[_]: Sync](config: DvdvConfig, loaded: LoadedCert): F[String] =
    Sync[F].delay(Instant.now()).map { now =>
      val sub = s"fp:${loaded.fingerprintSha1Hex}"
      val claim = JwtClaim(
        issuer    = Some(config.issuer.getOrElse(sub)),
        subject   = Some(sub),
        audience  = Some(Set(config.tokenUri.renderString)),
        issuedAt  = Some(now.getEpochSecond),
        expiration = Some(now.plusSeconds(config.jwtLifetime.toSeconds).getEpochSecond),
        jwtId     = Some(UUID.randomUUID().toString)
      )
      Jwt.encode(claim, loaded.privateKey, JwtAlgorithm.RS256)
    }
}
