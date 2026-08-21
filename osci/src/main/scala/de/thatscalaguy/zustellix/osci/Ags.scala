package de.thatscalaguy.zustellix.osci

/** Amtlicher Gemeindeschlüssel (AGS) — the 8-digit official municipality key
 *  that addresses the recipient authority in DVDV lookups.
 *
 *  Values only exist via the smart constructors, so an `Ags` is always
 *  exactly 8 ASCII digits — a malformed key fails fast at the API boundary
 *  instead of surfacing later as a DVDV miss.
 */
opaque type Ags = String

object Ags {

  /** Validates `s` as exactly 8 ASCII digits (leading zeros are
   *  significant, e.g. `"01001000"`).
   */
  def from(s: String): Either[OsciError.InvalidAgs, Ags] =
    if (s.length == 8 && s.forall(c => c >= '0' && c <= '9')) Right(s)
    else Left(OsciError.InvalidAgs(s))

  /** Like [[from]], but throws the [[OsciError.InvalidAgs]] — for literals
   *  and other places where a `Left` cannot occur or cannot be handled.
   */
  def unsafe(s: String): Ags = from(s).fold(throw _, identity)

  extension (a: Ags) def value: String = a
}
