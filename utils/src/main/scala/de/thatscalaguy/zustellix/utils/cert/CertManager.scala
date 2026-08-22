package de.thatscalaguy.zustellix.utils.cert

import cats.effect.Sync
import cats.syntax.all.*

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import scala.util.control.NoStackTrace

/** The certificate material shared by DVDV and OSCI/XMeld for one tenant.
 *
 *  Raw PKCS12 bytes + password is the lowest common denominator: OSCI's
 *  osci-bibliothek needs a fresh `InputStream` + password to build its
 *  `PKCS12Signer`/`PKCS12Decrypter`, while DVDV derives a [[LoadedCert]]
 *  (private key + X509) from the same bytes to sign its `client_assertion` JWT.
 *
 *  `toString` is redacted: the value carries a password and is the one most
 *  likely to end up in a log line.
 */
final case class CertCredential(pkcs12: Array[Byte], password: String) {
  def loadedCert[F[_]: Sync]: F[LoadedCert] =
    CertLoader.loadPkcs12Bytes[F](pkcs12, password)

  override def toString: String = s"CertCredential(${pkcs12.length} bytes, <redacted>)"
}

object CertCredential {

  /** Normalises any [[CertSource]] to the PKCS12-bytes + password shape both
   *  DVDV and OSCI consume. PKCS12 sources pass through (the file variant is
   *  read into memory); the PEM variants are parsed via [[CertLoader]] and
   *  repacked into an in-memory PKCS12 keystore — nothing touches the disk.
   *
   *  For PEM sources the keystore password is the source's `keyPassword` when
   *  set, otherwise a random one; the returned credential carries it either
   *  way, so callers never need to know.
   */
  def fromSource[F[_]: Sync](src: CertSource): F[CertCredential] = src match {
    case CertSource.Pkcs12(path, password) =>
      Sync[F].blocking(CertCredential(Files.readAllBytes(path), password))
    case CertSource.Pkcs12Bytes(bytes, password) =>
      CertCredential(bytes, password).pure[F]
    case CertSource.Pem(_, _, keyPassword) =>
      fromPem[F](src, keyPassword)
    case CertSource.PemBytes(_, _, keyPassword) =>
      fromPem[F](src, keyPassword)
  }

  private def fromPem[F[_]: Sync](src: CertSource, keyPassword: Option[String]): F[CertCredential] = {
    val password = keyPassword.getOrElse(UUID.randomUUID().toString)
    CertLoader.load[F](src).flatMap { loaded =>
      Sync[F].blocking(CertCredential(packPkcs12(loaded, password), password))
    }
  }

  /** One key entry ("key") whose password equals the store password — the
   *  layout [[CertLoader.loadPkcs12Bytes]] requires. The chain goes in
   *  leaf-first, as [[LoadedCert]] carries it.
   */
  private def packPkcs12(loaded: LoadedCert, password: String): Array[Byte] = {
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    ks.setKeyEntry(
      "key",
      loaded.privateKey,
      password.toCharArray,
      loaded.chain.toArray[java.security.cert.Certificate]
    )
    val out = new ByteArrayOutputStream()
    ks.store(out, password.toCharArray)
    out.toByteArray
  }
}

sealed abstract class CertManagerError(msg: String, cause: Option[Throwable] = None)
    extends RuntimeException(msg, cause.orNull)
    with NoStackTrace

object CertManagerError {
  final case class UnknownCert(alias: CertAlias)
      extends CertManagerError(s"No certificate for alias ${alias.value}")

  final case class LoadFailed(alias: CertAlias, cause: Throwable)
      extends CertManagerError(s"Failed to load certificate for alias ${alias.value}", Some(cause))
}

/** Resolves a certificate by its [[CertAlias]]. Implementations:
 *  [[InMemoryCertManager]] (configured at runtime, hot-swappable) and
 *  [[DirectoryCertManager]] (polls a folder of `<alias>.p12` + passwords).
 */
trait CertManager[F[_]] {
  /** Raises [[CertManagerError.UnknownCert]] in `F` if the alias is not known. */
  def resolve(alias: CertAlias): F[CertCredential]

  /** `resolve` + derive the DVDV [[LoadedCert]] (private key / X509).
   *
   *  Raises [[CertManagerError.UnknownCert]] for an unknown alias and
   *  [[CertManagerError.LoadFailed]] when the credential's PKCS12 fails to
   *  parse.
   */
  def loadedCert(alias: CertAlias): F[LoadedCert]

  def knownAliases: F[Set[CertAlias]]
}
