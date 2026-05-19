package de.thatscalaguy.zustellix.utils.cert

import cats.effect.Sync

/** The certificate material shared by DVDV and OSCI/XMeld for one tenant.
 *
 *  Raw PKCS12 bytes + password is the lowest common denominator: OSCI's
 *  osci-bibliothek needs a fresh `InputStream` + password to build its
 *  `PKCS12Signer`/`PKCS12Decrypter`, while DVDV derives a [[LoadedCert]]
 *  (private key + X509) from the same bytes to sign its `client_assertion` JWT.
 */
final case class CertCredential(pkcs12: Array[Byte], password: String) {
  def loadedCert[F[_]: Sync]: F[LoadedCert] =
    CertLoader.loadPkcs12Bytes[F](pkcs12, password)
}

sealed abstract class CertManagerError(msg: String) extends RuntimeException(msg)

object CertManagerError {
  final case class UnknownCert(alias: CertAlias)
      extends CertManagerError(s"No certificate for alias ${alias.value}")
}

/** Resolves a certificate by its [[CertAlias]]. Implementations:
 *  [[InMemoryCertManager]] (configured at runtime, hot-swappable) and
 *  [[DirectoryCertManager]] (polls a folder of `<alias>.p12` + passwords).
 */
trait CertManager[F[_]] {
  /** Raises [[CertManagerError.UnknownCert]] in `F` if the alias is not known. */
  def resolve(alias: CertAlias): F[CertCredential]

  /** `resolve` + derive the DVDV [[LoadedCert]] (private key / X509). */
  def loadedCert(alias: CertAlias): F[LoadedCert]

  def knownAliases: F[Set[CertAlias]]
}
