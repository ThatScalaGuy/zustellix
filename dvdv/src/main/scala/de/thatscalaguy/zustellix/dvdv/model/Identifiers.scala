package de.thatscalaguy.zustellix.dvdv.model

import de.thatscalaguy.zustellix.dvdv.DvdvError

/** SHA-1 certificate fingerprint — 40 lowercase hex characters on the wire.
 *
 *  The smart constructors normalize on the way in: colons and whitespace are
 *  stripped and the result is lowercased, so the colon-separated uppercase
 *  form (`"02:72:C5:..."`) and the plain lowercase form construct the SAME
 *  value. Downstream that means one cache entry and one backend call for
 *  both spellings.
 */
opaque type Fingerprint = String

object Fingerprint {

  /** Normalizes `s` (strips `:` and whitespace, lowercases) and validates
   *  the result as exactly 40 hex characters.
   */
  def from(s: String): Either[DvdvError.InvalidFingerprint, Fingerprint] = {
    val n = s.filterNot(c => c == ':' || c.isWhitespace).toLowerCase
    if (n.length == 40 && n.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) Right(n)
    else Left(DvdvError.InvalidFingerprint(s))
  }

  /** Like [[from]], but throws the [[DvdvError.InvalidFingerprint]] — for
   *  literals and other places where a `Left` cannot occur or cannot be
   *  handled.
   */
  def unsafe(s: String): Fingerprint = from(s).fold(throw _, identity)

  extension (f: Fingerprint) def value: String = f
}

/** Organization key ("Behördenschlüsselname"), e.g. `"ags:01999001"` —
 *  6 to 255 characters per the DVDV2 spec. Leading/trailing whitespace is
 *  trimmed; case is preserved (case rules of the key schemes are not
 *  specified).
 */
opaque type OrganizationKey = String

object OrganizationKey {

  /** Trims `s` and validates the result as 6 to 255 characters. */
  def from(s: String): Either[DvdvError.InvalidOrganizationKey, OrganizationKey] = {
    val n = s.trim
    if (n.length >= 6 && n.length <= 255) Right(n)
    else Left(DvdvError.InvalidOrganizationKey(s))
  }

  /** Like [[from]], but throws the [[DvdvError.InvalidOrganizationKey]] —
   *  for literals and other places where a `Left` cannot occur or cannot be
   *  handled.
   */
  def unsafe(s: String): OrganizationKey = from(s).fold(throw _, identity)

  extension (k: OrganizationKey) def value: String = k
}

/** Authority category ("Behördenkategoriebezeichnung"), e.g.
 *  `"Meldebehörde"` — 1 to 255 characters per the DVDV2 spec.
 *  Leading/trailing whitespace is trimmed; case and non-ASCII characters
 *  are preserved.
 */
opaque type Category = String

object Category {

  /** Trims `s` and validates the result as 1 to 255 characters. */
  def from(s: String): Either[DvdvError.InvalidCategory, Category] = {
    val n = s.trim
    if (n.nonEmpty && n.length <= 255) Right(n)
    else Left(DvdvError.InvalidCategory(s))
  }

  /** Like [[from]], but throws the [[DvdvError.InvalidCategory]] — for
   *  literals and other places where a `Left` cannot occur or cannot be
   *  handled.
   */
  def unsafe(s: String): Category = from(s).fold(throw _, identity)

  extension (c: Category) def value: String = c
}
