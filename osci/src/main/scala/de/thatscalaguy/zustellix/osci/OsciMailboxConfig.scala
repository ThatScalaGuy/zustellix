package de.thatscalaguy.zustellix.osci

import java.net.URI
import java.security.cert.X509Certificate
import scala.concurrent.duration.FiniteDuration

/** Static configuration of our own mailbox at an OSCI intermediary (the
 *  asynchronous, passive-recipient leg — e.g. XFamilie). Unlike outbound
 *  routes, the own mailbox is not resolved from DVDV.
 *
 *  @param intermedUri        OSCI endpoint of the intermediary hosting the mailbox
 *  @param intermedCipherCert the intermediary's cipher certificate (encrypts
 *                            the envelope towards the intermediary)
 *  @param fetchLimit         maximum number of process cards one `pending`
 *                            call lists (`FetchProcessCard.setQuantityLimit`)
 *  @param connectTimeout     HTTP connect timeout of the default
 *                            [[OsciHttpTransport]]; ignored when a custom
 *                            transport is passed to `OsciMailbox.resource`
 *  @param readTimeout        HTTP read timeout of the default
 *                            [[OsciHttpTransport]]; ignored when a custom
 *                            transport is passed to `OsciMailbox.resource`
 *  @param contentSignatures  how strictly the author's content signature on
 *                            fetched deliveries is enforced (see
 *                            [[ContentSignaturePolicy]])
 */
final case class OsciMailboxConfig(
    intermedUri:        URI,
    intermedCipherCert: X509Certificate,
    fetchLimit:         Long = 100,
    connectTimeout:     FiniteDuration = OsciHttpTransport.DefaultConnectTimeout,
    readTimeout:        FiniteDuration = OsciHttpTransport.DefaultReadTimeout,
    contentSignatures:  ContentSignaturePolicy = ContentSignaturePolicy.Warn
)
