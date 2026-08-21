package de.thatscalaguy.zustellix.osci.internal

import cats.effect.Sync
import cats.syntax.all.*
import de.thatscalaguy.zustellix.dvdv.DvdvClient
import de.thatscalaguy.zustellix.dvdv.model.{Certificate as DvdvCert, ServiceElementInfo, ServiceElementType}
import de.thatscalaguy.zustellix.osci.{Ags, OsciConfig, OsciError}

import java.io.ByteArrayInputStream
import java.net.URI
import java.security.cert.{CertificateFactory, X509Certificate}
import java.util.Base64

trait AgsResolver[F[_]] {
  def resolve(ags: Ags): F[OsciRoute]
}

object AgsResolver {

  def apply[F[_]: Sync](dvdv: DvdvClient[F], config: OsciConfig): AgsResolver[F] =
    new AgsResolver[F] {

      def resolve(ags: Ags): F[OsciRoute] = {
        val orgKey = s"ags:${ags.value}"
        dvdv.findServiceDescription(orgKey, config.serviceUri).flatMap {
          case None =>
            Sync[F].raiseError(OsciError.AgsNotInDvdv(ags, config.serviceUri))
          case Some(svc) =>
            buildRoute(ags, svc.serviceElements.getOrElse(Nil))
        }
      }

      private def buildRoute(ags: Ags, elems: List[ServiceElementInfo]): F[OsciRoute] = {
        def find(t: ServiceElementType): F[ServiceElementInfo] =
          elems.find(_.serviceElementType.contains(t)) match {
            case Some(e) => Sync[F].pure(e)
            case None    => Sync[F].raiseError(OsciError.ServiceElementMissing(ags, t.toString))
          }

        // The addressee's content-encryption cert is the inline cipher cert on
        // the OSCI_ADDRESSEE element; if absent, a standalone CIPHER_CERTIFICATE
        // element carrying the *same* serviceElementDescriptionName is the
        // fallback (matched by name+type, never "first of type").
        def addresseeCipherCert(addr: ServiceElementInfo): Option[DvdvCert] =
          addr.cipherCertificate.orElse(
            elems
              .find(e =>
                e.serviceElementType.contains(ServiceElementType.CIPHER_CERTIFICATE) &&
                  e.serviceElementDescriptionName == addr.serviceElementDescriptionName
              )
              .flatMap(_.cipherCertificate)
          )

        for {
          addr       <- find(ServiceElementType.OSCI_ADDRESSEE)
          intm       <- find(ServiceElementType.OSCI_INTERMEDIARY)
          addrUri    <- parseUri(addr.serviceElementUri.getOrElse(""))
          intUri     <- parseUri(intm.serviceElementUri.getOrElse(""))
          addrCipher <- requireCipherCert(ags, "OSCI_ADDRESSEE", addresseeCipherCert(addr))
          intCipher  <- requireCipher(ags, "OSCI_INTERMEDIARY", intm)
          addrSig    <- addr.signatureCertificate.traverse(c =>
                          decodeCert(c).adaptError(t => OsciError.Certificate(t))
                        )
        }
        yield OsciRoute(addrUri, addrCipher, addrSig, intUri, intCipher)
      }

      private def requireCipher(ags: Ags, kind: String, e: ServiceElementInfo): F[X509Certificate] =
        requireCipherCert(ags, kind, e.cipherCertificate)

      private def requireCipherCert(ags: Ags, kind: String, c: Option[DvdvCert]): F[X509Certificate] =
        c match {
          case Some(cert) => decodeCert(cert).adaptError(t => OsciError.Certificate(t))
          case None       => Sync[F].raiseError[X509Certificate](
                               OsciError.RecipientCertMissing(ags, kind)
                             )
        }

      private def parseUri(s: String): F[URI] =
        Sync[F].delay(URI.create(s)).adaptError {
          case e: IllegalArgumentException => OsciError.Config(s"Invalid URI '$s': ${e.getMessage}")
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
