package de.thatscalaguy.zustellix.osci

import cats.effect.{Async, Resource}
import de.thatscalaguy.zustellix.utils.cert.{CertAlias, CertManager, CertSource}

import de.osci.osci12.extinterfaces.TransportI

/** Our own mailbox at an OSCI intermediary — the asynchronous, passive
 *  recipient leg (e.g. XFamilie).
 *
 *  Acknowledgement semantics (OSCI 1.2 has no separate ack message): a
 *  successful [[fetch]] makes the intermediary record a reception entry on
 *  the message's process card, which removes it from [[pending]] — the fetch
 *  IS the acknowledgement, so a message is never listed as pending twice.
 *
 *  The crash window between fetch and processing is the caller's to close:
 *  persist the `messageId`s from [[pending]] before fetching, and re-[[fetch]]
 *  by id after a crash (deliveries remain stored at the intermediary after
 *  reception, subject to its retention policy) — that yields at-least-once
 *  processing on top of this API.
 */
trait OsciMailbox[F[_]] {

  /** Deliveries addressed to this mailbox that nobody has fetched yet
   *  (`FetchProcessCard` with `selectNoReceptionOnly`), oldest first, at most
   *  `fetchLimit` entries.
   */
  def pending: F[List[PendingDelivery]]

  /** Fetches one delivery by message id (`FetchDelivery`) and decrypts its
   *  content with our own cipher cert. Raises [[OsciError.NoSuchMessage]]
   *  when the response carries no content for the id.
   */
  def fetch(messageId: String): F[OsciMessage]
}

object OsciMailbox {

  /** Fetches over an [[OsciHttpTransport]] with the config's
   *  `connectTimeout` / `readTimeout`; the `transport` overload swaps it for
   *  a custom one.
   */
  def resource[F[_]: Async](
      config:     OsciMailboxConfig,
      certSource: CertSource
  ): Resource[F, OsciMailbox[F]] =
    resource(config, certSource, defaultTransport(config))

  /** Same as the CertSource overload, but fetches over the given `transport`
   *  instead of the default [[OsciHttpTransport]] (the config's timeouts then
   *  do not apply — the transport owns its own settings).
   */
  def resource[F[_]: Async](
      config:     OsciMailboxConfig,
      certSource: CertSource,
      transport:  TransportI
  ): Resource[F, OsciMailbox[F]] =
    Resource.eval(internal.OsciBibBridge.originator[F](certSource)).map { originator =>
      new internal.OsciMailboxBridgeImpl[F](originator, config, transport)
    }

  /** Mailbox whose signing + decryption cert is resolved from the shared
   *  [[de.thatscalaguy.zustellix.utils.cert.CertManager]] by alias — the same
   *  cert the matching `DvdvClient` / [[OsciClient]] use.
   */
  def resource[F[_]: Async](
      config: OsciMailboxConfig,
      certs:  CertManager[F],
      alias:  CertAlias
  ): Resource[F, OsciMailbox[F]] =
    resource(config, certs, alias, defaultTransport(config))

  /** Same as the CertManager overload, but fetches over the given
   *  `transport` instead of the default [[OsciHttpTransport]].
   */
  def resource[F[_]: Async](
      config:    OsciMailboxConfig,
      certs:     CertManager[F],
      alias:     CertAlias,
      transport: TransportI
  ): Resource[F, OsciMailbox[F]] =
    for {
      cred       <- Resource.eval(certs.resolve(alias))
      originator <- Resource.eval(internal.OsciBibBridge.originator[F](cred))
    } yield new internal.OsciMailboxBridgeImpl[F](originator, config, transport)

  private def defaultTransport(config: OsciMailboxConfig): TransportI =
    new OsciHttpTransport(config.connectTimeout, config.readTimeout)
}
