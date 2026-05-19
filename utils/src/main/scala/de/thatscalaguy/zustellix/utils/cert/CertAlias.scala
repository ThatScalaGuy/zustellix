package de.thatscalaguy.zustellix.utils.cert

/** Identifies a certificate within a [[CertManager]]. The alias is also the
 *  keystore file name (`<alias>.p12`) and the key under which its password is
 *  looked up in the password-properties file.
 */
opaque type CertAlias = String

object CertAlias {
  def apply(s: String): CertAlias = s

  extension (a: CertAlias) {
    def value: String = a
  }
}
