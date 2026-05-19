package de.thatscalaguy.zustellix.oscixmeld

import cats.effect.{Async, Resource}
import de.thatscalaguy.zustellix.dvdv.DvdvClient
import de.thatscalaguy.zustellix.utils.cert.{CertManager, CertAlias}

trait OSCIXMeld[F[_]] {
  def send(ags: String, xml: String): F[String]
}

object OSCIXMeld {

  /** Build a single-tenant OSCI/XMeld client.
   *
   *  Per `send(ags, xml)`, the bundled `AgsResolver` performs a single DVDV
   *  `findServiceDescription` call on the recipient AGS, and pulls both the
   *  addressee (OSCI_ADDRESSEE) and intermediary (OSCI_INTERMEDIARY) routes
   *  out of the same service description. The DvdvClient's mules cache
   *  memoizes that response for `cacheConfig.findServiceDescriptionTtl`
   *  (default 10 minutes), so repeated sends to the same AGS reuse it.
   *
   *  The given DvdvClient is owned by the caller; this resource does not
   *  close it.
   */
  def resource[F[_]: Async](
      config: OSCIXMeldConfig,
      dvdv:   DvdvClient[F],
      sink:   LaufzettelSink[F]
  ): Resource[F, OSCIXMeld[F]] = {
    val resolver = internal.AgsResolver[F](dvdv, config)
    internal.OsciBibBridge.resource[F](config).map { transport =>
      new internal.OSCIXMeldImpl[F](config.tenantId, transport, resolver, sink)
    }
  }

  /** Build an OSCI/XMeld client whose Originator (Autor) signing + decryption
   *  cert is resolved from the shared [[CertManager]] by [[CertAlias]] — the
   *  same cert the matching [[DvdvClient]] uses. The Laufzettel is recorded
   *  under the alias as its tenant id.
   */
  def resource[F[_]: Async](
      config: OSCIXMeldConfig,
      certs:  CertManager[F],
      alias:  CertAlias,
      dvdv:   DvdvClient[F],
      sink:   LaufzettelSink[F]
  ): Resource[F, OSCIXMeld[F]] = {
    val resolver = internal.AgsResolver[F](dvdv, config)
    for {
      cred      <- Resource.eval(certs.resolve(alias))
      transport <- internal.OsciBibBridge.resource[F](cred)
    } yield new internal.OSCIXMeldImpl[F](TenantId(alias.value), transport, resolver, sink)
  }
}
