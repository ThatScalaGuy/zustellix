package de.thatscalaguy.zustellix.osci

import cats.effect.{Async, Resource}
import cats.syntax.all.*
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
   *  `fetchLimit` entries. The page carries the response's `3xxx` warnings;
   *  [[PendingPage.truncated]] tells whether the listing was cut off at
   *  `fetchLimit` (feedback code `3800` / `3801`) — more deliveries are then
   *  waiting than the page lists.
   */
  def pending: F[PendingPage]

  /** Fetches one delivery by message id (`FetchDelivery`) and decrypts its
   *  content with our own cipher cert. Raises [[OsciError.NoSuchMessage]]
   *  when the response carries no content for the id, and
   *  [[OsciError.MessageIdMismatch]] when the response names a different
   *  message id than requested — the intermediary answered for the wrong
   *  delivery. `3xxx` warnings on the response are surfaced via
   *  [[OsciMessage.warnings]].
   */
  def fetch(messageId: String): F[OsciMessage]

  /** Lists and fetches up to `maxMessages` pending deliveries in ONE explicit
   *  dialog: `InitDialog` + `FetchProcessCard` + one `FetchDelivery` per
   *  message + `ExitDialog` — N+3 round trips where separate [[pending]] +
   *  [[fetch]] calls cost 3 + 3N. Lists at most `min(fetchLimit, maxMessages)`
   *  process cards; each fetch acknowledges like [[fetch]] does.
   *
   *  Raises like [[pending]] when the listing itself fails (an `InitDialog`
   *  refusal, a `FetchProcessCard` error — `ExitDialog` is still sent when
   *  the dialog was opened) and when `maxMessages` is not positive
   *  ([[OsciError.Config]]). A failing fetch does NOT raise: the messages
   *  fetched before it are already acknowledged, so it stops the drain and is
   *  returned on [[MailboxDrain.failure]] beside them.
   *
   *  Note the at-least-once caveat: drain cannot persist ids between listing
   *  and fetching, so the strict crash-safe pattern remains
   *  [[pending]] → persist ids → [[fetch]] (see above).
   */
  def drain(maxMessages: Int): F[MailboxDrain]
}

object OsciMailbox {

  /** Fetches over an [[OsciHttpTransport]] with the config's
   *  `connectTimeout` / `readTimeout`; the `transport` overload swaps it for
   *  a custom one. The `certSource` is loaded once and kept for the lifetime
   *  of the mailbox — use the `CertManager` overload for a cert that can
   *  rotate.
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
      new internal.OsciMailboxBridgeImpl[F](originator.pure[F], config, transport)
    }

  /** Mailbox whose signing + decryption cert is resolved from the shared
   *  [[de.thatscalaguy.zustellix.utils.cert.CertManager]] by alias — the same
   *  cert the matching `DvdvClient` / [[OsciClient]] use.
   *
   *  The alias is resolved once at build time to fail fast on a missing cert
   *  or unopenable keystore, and again on every [[OsciMailbox.pending]] /
   *  [[OsciMailbox.fetch]] / [[OsciMailbox.drain]] — so a cert rotated in
   *  the manager (e.g. a hot-reloading `DirectoryCertManager`) is picked up
   *  without rebuilding the mailbox, and deliveries encrypted to the
   *  rotated cipher cert stay
   *  decryptable. The built OSCI Originator is cached and only rebuilt when
   *  the credential actually changes.
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
      resolve <- Resource.eval(internal.OsciBibBridge.managedOriginator[F](certs, alias))
      _       <- Resource.eval(resolve)
    } yield new internal.OsciMailboxBridgeImpl[F](resolve, config, transport)

  private def defaultTransport(config: OsciMailboxConfig): TransportI =
    new OsciHttpTransport(config.connectTimeout, config.readTimeout)
}
