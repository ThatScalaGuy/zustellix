package de.thatscalaguy.zustellix.osci

import java.net.URI
import java.security.cert.X509Certificate

/** Static configuration of our own mailbox at an OSCI intermediary (the
 *  asynchronous, passive-recipient leg — e.g. XFamilie). Unlike outbound
 *  routes, the own mailbox is not resolved from DVDV.
 *
 *  @param intermedUri        OSCI endpoint of the intermediary hosting the mailbox
 *  @param intermedCipherCert the intermediary's cipher certificate (encrypts
 *                            the envelope towards the intermediary)
 *  @param fetchLimit         maximum number of process cards one `pending`
 *                            call lists (`FetchProcessCard.setQuantityLimit`)
 */
final case class OsciMailboxConfig(
    intermedUri:        URI,
    intermedCipherCert: X509Certificate,
    fetchLimit:         Long = 100
)
