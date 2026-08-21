package de.thatscalaguy.zustellix.utils.cert

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Supervisor
import cats.syntax.all.*
import org.typelevel.log4cats.LoggerFactory

import java.nio.file.attribute.{BasicFileAttributes, FileTime}
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** @param dir           folder scanned for `<alias>.p12` keystores
 *  @param interval       poll period (rebuilds the map every `interval`)
 *  @param passwordsFile  optional `java.util.Properties` of `<alias>=<password>`;
 *                        defaults to `<dir>/passwords.properties`
 */
final case class DirectoryCertManagerConfig(
    dir: Path,
    interval: FiniteDuration = 30.seconds,
    passwordsFile: Option[Path] = None
) {
  val passwords: Path =
    passwordsFile.getOrElse(dir.resolve("passwords.properties"))
}

/** Polls [[DirectoryCertManagerConfig.dir]] every `interval`, rebuilding the
 *  alias -> credential map. Semantics:
 *
 *   - The first scan completes before the `Resource` is ready, so `resolve`
 *     never races an empty map (and a misconfigured dir fails fast).
 *   - A `<alias>.p12` that has never loaded successfully is logged and
 *     skipped; the other entries still swap in (atomic whole-map swap, no
 *     per-entry merge).
 *   - Once an alias has loaded, a per-file load failure — a keystore read
 *     mid-overwrite during non-atomic rotation, a password entry missing or
 *     not yet updated, corrupt bytes — keeps the previously loaded
 *     credential for that alias, with the failure logged on every scan
 *     until it heals. An alias is dropped only when its `<alias>.p12`
 *     disappears from the directory (picked up at the next scan).
 *   - An unchanged file (same size, same last-modified time, same resolved
 *     password) is not re-read or re-parsed: the previous [[CertCredential]]
 *     instance is carried over, so credential identity stays stable across
 *     scans. A changed password entry forces a reload with the new password
 *     (and if the keystore does not open with it, the previously loaded
 *     credential is retained until it does).
 *   - The background poll loop is run by a `Supervisor` tied to the
 *     `Resource` scope, and a failed scan never kills the loop.
 *
 *  Writers should rotate keystores and the passwords file atomically: write
 *  to a temp file in the same directory, then
 *  `Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)`, so a scan
 *  never observes a half-written file. Plain `cp`/`scp`/configmap-style
 *  sync is not atomic; a torn file is served from the retained previous
 *  credential until the write completes.
 */
object DirectoryCertManager {

  def resource[F[_]: Async: LoggerFactory](
      cfg: DirectoryCertManagerConfig
  ): Resource[F, CertManager[F]] =
    resource[F](cfg, PasswordSource.propertiesFile[F](cfg.passwords))

  /** Like `resource(cfg)` but with a pluggable password source. The
   *  `passwordSource` effect is (re-)evaluated on every scan, so rotating
   *  sources (like the default properties file) are picked up.
   */
  def resource[F[_]: Async: LoggerFactory](
      cfg: DirectoryCertManagerConfig,
      passwordSource: F[PasswordSource[F]]
  ): Resource[F, CertManager[F]] =
    for {
      mgr   <- Resource.eval(InMemoryCertManager.make[F](Map.empty[CertAlias, CertCredential]))
      cache <- Resource.eval(Ref.of[F, Map[Path, CachedEntry]](Map.empty))
      _     <- Resource.eval(scanOnce[F](cfg, passwordSource, mgr, cache))
      sup   <- Supervisor[F]
      _     <- Resource.eval(sup.supervise(pollLoop[F](cfg, passwordSource, mgr, cache)).void)
    } yield mgr

  /** Per-file scan state: an entry is reused (no read/parse, same credential
   *  instance) while size, mtime and resolved password are all unchanged.
   */
  private final case class CachedEntry(
      alias: CertAlias,
      size: Long,
      mtime: FileTime,
      password: String,
      credential: CertCredential
  ) {
    override def toString: String =
      s"CachedEntry($alias, $size bytes, $mtime, <redacted>, $credential)"
  }

  private def pollLoop[F[_]: Async: LoggerFactory](
      cfg: DirectoryCertManagerConfig,
      passwordSource: F[PasswordSource[F]],
      mgr: InMemoryCertManager.Swappable[F],
      cache: Ref[F, Map[Path, CachedEntry]]
  ): F[Unit] = {
    val log = LoggerFactory[F].getLogger
    def loop: F[Unit] =
      Async[F].sleep(cfg.interval) *>
        scanOnce[F](cfg, passwordSource, mgr, cache).handleErrorWith { e =>
          log.warn(e)("cert directory scan failed; keeping previous certificates")
        } >> loop
    loop
  }

  private def scanOnce[F[_]: Async: LoggerFactory](
      cfg: DirectoryCertManagerConfig,
      passwordSource: F[PasswordSource[F]],
      mgr: InMemoryCertManager.Swappable[F],
      cache: Ref[F, Map[Path, CachedEntry]]
  ): F[Unit] = {
    val log = LoggerFactory[F].getLogger
    for {
      pwds  <- passwordSource
      files <- listP12[F](cfg.dir)
      prev  <- cache.get
      entries <- files.traverseFilter { p =>
                   loadEntry[F](p, pwds, prev.get(p)).attempt.flatMap {
                     case Right(entry) => (p -> entry).some.pure[F]
                     case Left(err) =>
                       prev.get(p) match {
                         case Some(cached) =>
                           log
                             .warn(err)(
                               s"failed to reload cert file $p; keeping previously loaded credential for alias ${cached.alias.value}"
                             )
                             .as((p -> cached).some)
                         case None =>
                           log
                             .warn(err)(s"skipping cert file $p")
                             .as(Option.empty[(Path, CachedEntry)])
                       }
                   }
                 }
      _ <- cache.set(entries.toMap)
      _ <- mgr.swap(entries.map { case (_, e) => e.alias -> e.credential }.toMap)
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
      pwds: PasswordSource[F],
      prev: Option[CachedEntry]
  ): F[CachedEntry] = {
    val alias = CertAlias(p.getFileName.toString.dropRight(".p12".length))
    for {
      pwOpt <- pwds.passwordFor(alias)
      pw <- pwOpt.liftTo[F](
              new IllegalArgumentException(
                s"no password for alias ${alias.value} in passwords file"
              )
            )
      attrs <- Async[F].blocking(Files.readAttributes(p, classOf[BasicFileAttributes]))
      entry <- prev match {
                 case Some(cached)
                     if cached.size == attrs.size &&
                       cached.mtime == attrs.lastModifiedTime &&
                       cached.password == pw =>
                   cached.pure[F] // unchanged: keep the same credential instance
                 case _ =>
                   for {
                     bytes <- Async[F].blocking(Files.readAllBytes(p))
                     _     <- CertLoader.loadPkcs12Bytes[F](bytes, pw) // validate it parses
                   } yield CachedEntry(
                     alias,
                     attrs.size,
                     attrs.lastModifiedTime,
                     pw,
                     CertCredential(bytes, pw)
                   )
               }
    } yield entry
  }
}
