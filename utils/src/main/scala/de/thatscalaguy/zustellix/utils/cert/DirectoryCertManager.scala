package de.thatscalaguy.zustellix.utils.cert

import cats.effect.{Async, Resource}
import cats.effect.std.Supervisor
import cats.syntax.all.*
import org.typelevel.log4cats.LoggerFactory

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** @param dir           folder scanned for `<alias>.p12` keystores
 *  @param interval       poll period (rebuilds the map every `interval`)
 *  @param passwordsFile  `java.util.Properties` of `<alias>=<password>`
 */
final case class DirectoryCertManagerConfig(
    dir: Path,
    interval: FiniteDuration = 30.seconds,
    passwordsFile: Path = null
) {
  val passwords: Path =
    if passwordsFile == null then dir.resolve("passwords.properties") else passwordsFile
}

/** Polls [[DirectoryCertManagerConfig.dir]] every `interval`, rebuilding the
 *  alias -> credential map. Semantics:
 *
 *   - The first scan completes before the `Resource` is ready, so `resolve`
 *     never races an empty map (and a misconfigured dir fails fast).
 *   - One unreadable/corrupt `<alias>.p12` is logged and skipped; the other
 *     entries still swap in (atomic whole-map swap, no per-entry merge).
 *   - An entry that fails to parse is dropped until it parses again — the
 *     active map always reflects current disk truth (never serves a stale
 *     or rotated-away cert).
 *   - The background poll loop is run by a `Supervisor` tied to the
 *     `Resource` scope, and a failed scan never kills the loop.
 */
object DirectoryCertManager {

  def resource[F[_]: Async: LoggerFactory](
      cfg: DirectoryCertManagerConfig
  ): Resource[F, CertManager[F]] =
    for {
      mgr <- Resource.eval(InMemoryCertManager.make[F](Map.empty[CertAlias, CertCredential]))
      _   <- Resource.eval(scanOnce[F](cfg, mgr))
      sup <- Supervisor[F]
      _   <- Resource.eval(sup.supervise(pollLoop[F](cfg, mgr)).void)
    } yield mgr

  private def pollLoop[F[_]: Async: LoggerFactory](
      cfg: DirectoryCertManagerConfig,
      mgr: InMemoryCertManager.Swappable[F]
  ): F[Unit] = {
    val log = LoggerFactory[F].getLogger
    def loop: F[Unit] =
      Async[F].sleep(cfg.interval) *>
        scanOnce[F](cfg, mgr).handleErrorWith { e =>
          log.warn(e)("cert directory scan failed; keeping previous certificates")
        } >> loop
    loop
  }

  private def scanOnce[F[_]: Async: LoggerFactory](
      cfg: DirectoryCertManagerConfig,
      mgr: InMemoryCertManager.Swappable[F]
  ): F[Unit] = {
    val log = LoggerFactory[F].getLogger
    for {
      pwds  <- PasswordSource.propertiesFile[F](cfg.passwords)
      files <- listP12[F](cfg.dir)
      pairs <- files.traverseFilter { p =>
                 loadEntry[F](p, pwds).attempt.flatMap {
                   case Right(entry) => entry.some.pure[F]
                   case Left(err) =>
                     log
                       .warn(err)(s"skipping cert file $p")
                       .as(Option.empty[(CertAlias, CertCredential)])
                 }
               }
      _ <- mgr.swap(pairs.toMap)
    } yield ()
  }

  private def listP12[F[_]: Async](dir: Path): F[List[Path]] =
    Async[F].blocking {
      val s = Files.list(dir)
      try
        s.iterator().asScala
          .filter(_.getFileName.toString.endsWith(".p12"))
          .toList
      finally s.close()
    }

  private def loadEntry[F[_]: Async](
      p: Path,
      pwds: PasswordSource[F]
  ): F[(CertAlias, CertCredential)] = {
    val alias = CertAlias(p.getFileName.toString.dropRight(".p12".length))
    for {
      pwOpt <- pwds.passwordFor(alias)
      pw <- pwOpt.liftTo[F](
              new IllegalArgumentException(
                s"no password for alias ${alias.value} in passwords file"
              )
            )
      bytes <- Async[F].blocking(Files.readAllBytes(p))
      _     <- CertLoader.loadPkcs12Bytes[F](bytes, pw) // validate it parses
    } yield alias -> CertCredential(bytes, pw)
  }
}
