package de.thatscalaguy.zustellix.utils.cert

import cats.effect.Sync

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
