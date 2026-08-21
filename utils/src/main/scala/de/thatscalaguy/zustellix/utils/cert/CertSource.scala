package de.thatscalaguy.zustellix.utils.cert

import java.nio.file.Path

/** Where a single certificate's material comes from.
 *
 *  The `*Bytes` variants carry the material in memory, for callers that get it
 *  from a secret manager or a mounted secret rather than a readable file. They
 *  hold `Array[Byte]`, so — like [[CertCredential]] — they have reference
 *  `equals`/`hashCode`; compare the loaded certificate's fingerprint instead.
 *
 *  `toString` is redacted on every case: these values carry passwords and end
 *  up in config objects that get logged.
 */
sealed trait CertSource

object CertSource {

  /** A PKCS12 keystore file. It must contain exactly one private-key entry —
   *  loading fails with a descriptive error otherwise — and `password` opens
   *  both the store and the key entry (openssl/keytool convention).
   */
  final case class Pkcs12(path: Path, password: String) extends CertSource {
    override def toString: String = s"Pkcs12($path, <redacted>)"
  }

  /** In-memory PKCS12 keystore bytes; same entry and password rules as
   *  [[Pkcs12]].
   */
  final case class Pkcs12Bytes(bytes: Array[Byte], password: String) extends CertSource {
    override def toString: String = s"Pkcs12Bytes(${bytes.length} bytes, <redacted>)"
  }

  final case class Pem(certPath: Path, keyPath: Path, keyPassword: Option[String] = None) extends CertSource {
    override def toString: String = s"Pem($certPath, $keyPath, <redacted>)"
  }

  final case class PemBytes(cert: Array[Byte], key: Array[Byte], keyPassword: Option[String] = None)
      extends CertSource {
    override def toString: String = s"PemBytes(${cert.length} bytes, ${key.length} bytes, <redacted>)"
  }
}
