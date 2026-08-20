package de.thatscalaguy.zustellix.osci

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import de.thatscalaguy.zustellix.dvdv.DvdvClient
import de.thatscalaguy.zustellix.utils.cert.{CertManager, CertAlias}

import de.osci.osci12.extinterfaces.TransportI

trait OsciClient[F[_]] {

  /** Synchronous request/response (`MediateDelivery`): the recipient answers
   *  within the call, e.g. the XMeld Personensuche.
   */
  def request(ags: String, xml: String): F[String]

  /** Asynchronous send (`StoreDelivery`): stores the message in the
   *  recipient's mailbox at their intermediary and returns a receipt; the
   *  recipient fetches it later. Used by profiles like XFamilie.
   */
  def send(ags: String, xml: String): F[OsciReceipt]
}

object OsciClient {

  /** Build a single-tenant OSCI/XMeld client.
   *
   *  Per `request(ags, xml)`, the bundled `AgsResolver` performs a single DVDV
   *  `findServiceDescription` call on the recipient AGS, and pulls both the
   *  addressee (OSCI_ADDRESSEE) and intermediary (OSCI_INTERMEDIARY) routes
   *  out of the same service description. The DvdvClient's mules cache
   *  memoizes that response for `cacheConfig.findServiceDescriptionTtl`
   *  (default 10 minutes), so repeated sends to the same AGS reuse it.
   *
   *  The given DvdvClient is owned by the caller; this resource does not
   *  close it.
   *
   *  Uses an [[OsciHttpTransport]] with the config's `connectTimeout` /
   *  `readTimeout` on the wire; the `transport` overload swaps it for a
   *  custom one.
   */
  def resource[F[_]: Async](
      config: OsciConfig,
      dvdv:   DvdvClient[F],
      sink:   LaufzettelSink[F]
  ): Resource[F, OsciClient[F]] =
    resource(config, dvdv, sink, defaultTransport(config))

  /** Same as the 3-arg overload, but sends over the given `transport` instead
   *  of the default [[OsciHttpTransport]] (the config's timeouts then do not
   *  apply — the transport owns its own settings).
   */
  def resource[F[_]: Async](
      config:    OsciConfig,
      dvdv:      DvdvClient[F],
      sink:      LaufzettelSink[F],
      transport: TransportI
  ): Resource[F, OsciClient[F]] = {
    val resolver = internal.AgsResolver[F](dvdv, config)
    val certSource = config.certSource.liftTo[F](
      OsciError.Config(
        "OsciConfig.certSource is not set — set it, or use the CertManager/CertAlias overload"
      )
    )
    Resource.eval(certSource).flatMap(internal.OsciBibBridge.resource[F](_, transport)).map {
      bridge =>
        new internal.OsciClientImpl[F](config.tenantId, config.subject, bridge, resolver, sink)
    }
  }

  /** Build an OSCI/XMeld client whose Originator (Autor) signing + decryption
   *  cert is resolved from the shared [[CertManager]] by [[CertAlias]] — the
   *  same cert the matching [[DvdvClient]] uses. The Laufzettel is recorded
   *  under the alias as its tenant id.
   */
  def resource[F[_]: Async](
      config: OsciConfig,
      certs:  CertManager[F],
      alias:  CertAlias,
      dvdv:   DvdvClient[F],
      sink:   LaufzettelSink[F]
  ): Resource[F, OsciClient[F]] =
    resource(config, certs, alias, dvdv, sink, defaultTransport(config))

  /** Same as the CertManager overload, but sends over the given `transport`
   *  instead of the default [[OsciHttpTransport]].
   */
  def resource[F[_]: Async](
      config:    OsciConfig,
      certs:     CertManager[F],
      alias:     CertAlias,
      dvdv:      DvdvClient[F],
      sink:      LaufzettelSink[F],
      transport: TransportI
  ): Resource[F, OsciClient[F]] = {
    val resolver = internal.AgsResolver[F](dvdv, config)
    for {
      cred   <- Resource.eval(certs.resolve(alias))
      bridge <- internal.OsciBibBridge.resource[F](cred, transport)
    } yield new internal.OsciClientImpl[F](
      TenantId(alias.value), config.subject, bridge, resolver, sink
    )
  }

  private def defaultTransport(config: OsciConfig): TransportI =
    new OsciHttpTransport(config.connectTimeout, config.readTimeout)
}
