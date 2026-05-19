package de.thatscalaguy.zustellix.oscixmeld.internal

import cats.effect.Sync
import cats.syntax.all.*
import de.thatscalaguy.zustellix.dvdv.DvdvClient
import de.thatscalaguy.zustellix.dvdv.model.{Certificate as DvdvCert, ServiceElementInfo, ServiceElementType}
import de.thatscalaguy.zustellix.oscixmeld.{OSCIXMeldConfig, OSCIXMeldError}

import java.io.ByteArrayInputStream
import java.net.URI
import java.security.cert.{CertificateFactory, X509Certificate}
import java.util.Base64

trait AgsResolver[F[_]] {
  def resolve(ags: String): F[XmeldRoute]
}

object AgsResolver {

  def apply[F[_]: Sync](dvdv: DvdvClient[F], config: OSCIXMeldConfig): AgsResolver[F] =
    new AgsResolver[F] {

      def resolve(ags: String): F[XmeldRoute] = {
        val orgKey = s"ags:$ags"
        dvdv.findServiceDescription(orgKey, config.serviceUri).flatMap {
          case None =>
            Sync[F].raiseError(OSCIXMeldError.AgsNotInDvdv(ags, config.serviceUri))
          case Some(svc) =>
            buildRoute(ags, svc.serviceElements.getOrElse(Nil))
        }
      }

      private def buildRoute(ags: String, elems: List[ServiceElementInfo]): F[XmeldRoute] = {
        def find(t: ServiceElementType): F[ServiceElementInfo] =
          elems.find(_.serviceElementType.contains(t)) match {
            case Some(e) => Sync[F].pure(e)
            case None    => Sync[F].raiseError(OSCIXMeldError.ServiceElementMissing(ags, t.toString))
          }

        // The addressee's content-encryption cert is published as a dedicated
        // CIPHER_CERTIFICATE service element when present; the cipher cert on
        // the OSCI_ADDRESSEE element is the fallback.
        val cipherElem: Option[ServiceElementInfo] =
          elems.find(_.serviceElementType.contains(ServiceElementType.CIPHER_CERTIFICATE))

        for {
          addr       <- find(ServiceElementType.OSCI_ADDRESSEE)
          intm       <- find(ServiceElementType.OSCI_INTERMEDIARY)
          addrUri    <- parseUri(addr.serviceElementUri.getOrElse(""))
          intUri     <- parseUri(intm.serviceElementUri.getOrElse(""))
          addrCipher <- requireCipher(ags, "OSCI_ADDRESSEE", cipherElem.getOrElse(addr))
          intCipher  <- requireCipher(ags, "OSCI_INTERMEDIARY", intm)
          addrSig    <- addr.signatureCertificate.traverse(c =>
                          decodeCert(c).adaptError(t => OSCIXMeldError.Certificate(t))
                        )
        }
        yield XmeldRoute(addrUri, addrCipher, addrSig, intUri, intCipher)
      }

      private def requireCipher(ags: String, kind: String, e: ServiceElementInfo): F[X509Certificate] =
        e.cipherCertificate match {
          case Some(c) => decodeCert(c).adaptError(t => OSCIXMeldError.Certificate(t))
          case None    => Sync[F].raiseError[X509Certificate](
                            OSCIXMeldError.RecipientCertMissing(s"$ags ($kind)")
                          )
        }

      private def parseUri(s: String): F[URI] =
        Sync[F].delay(URI.create(s)).adaptError {
          case e: IllegalArgumentException => OSCIXMeldError.Config(s"Invalid URI '$s': ${e.getMessage}")
        }

      private def decodeCert(c: DvdvCert): F[X509Certificate] =
        Sync[F].blocking {
          val b64 = c.content.getOrElse(
            throw new IllegalArgumentException("DVDV certificate has no `content` field")
          )
          val der = Base64.getDecoder.decode(b64)
          val cf  = CertificateFactory.getInstance("X.509")
          cf.generateCertificate(new ByteArrayInputStream(der)).asInstanceOf[X509Certificate]
        }
    }
}
