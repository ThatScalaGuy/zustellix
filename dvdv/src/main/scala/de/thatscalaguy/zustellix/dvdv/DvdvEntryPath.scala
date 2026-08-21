package de.thatscalaguy.zustellix.dvdv

/** The DVDV2 directory entry path — the path prefix under which the directory
 *  endpoints are reachable, and the authentication scheme that goes with it.
 */
enum DvdvEntryPath {

  /** `extern/standaloneauth/directory` — standalone authentication with a
   *  token issued by the DVDV server itself (POST to
   *  `<server>/extern/standaloneauth/token`). The default; the only entry
   *  path for which this library wires token acquisition, so a signing cert
   *  is required.
   */
  case StandaloneAuth

  /** `intern/directory` — data-center-internal access, defined by the spec as
   *  unauthenticated. No token manager and no signing cert are wired.
   */
  case InternDirectory

  /** `extern/bundesmasterauth/directory` — authentication against the IAM of
   *  the Bundesmaster operator. The DVDV2 spec does not define that token
   *  flow, so this library wires NO authentication for it: callers must
   *  supply IAM auth themselves, e.g. via a pre-authenticated http4s
   *  `Client` passed to `DvdvClient.fromClient`.
   */
  case BundesmasterAuth

  /** The path segments this entry path prepends to directory requests. */
  private[dvdv] def segments: List[String] = this match {
    case StandaloneAuth   => List("extern", "standaloneauth", "directory")
    case InternDirectory  => List("intern", "directory")
    case BundesmasterAuth => List("extern", "bundesmasterauth", "directory")
  }

  /** Whether the standalone token flow (and thus the signing cert) is wired. */
  private[dvdv] def usesStandaloneToken: Boolean = this == StandaloneAuth
}
