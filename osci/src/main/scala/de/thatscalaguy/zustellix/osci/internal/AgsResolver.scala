package de.thatscalaguy.zustellix.osci.internal

import cats.effect.Sync
import cats.syntax.all.*
import de.thatscalaguy.zustellix.dvdv.DvdvClient
import de.thatscalaguy.zustellix.dvdv.model.{Certificate as DvdvCert, OrganizationKey, ServiceElementInfo, ServiceElementType}
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
        val orgKey = OrganizationKey.unsafe(s"ags:${ags.value}")
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

        // An element's content-encryption cert is its inline cipher cert; if
        // absent, a standalone CIPHER_CERTIFICATE element carrying the *same*
        // serviceElementDescriptionName is the fallback (matched by name+type,
        // never "first of type"). Applies to OSCI_ADDRESSEE and
        // OSCI_INTERMEDIARY alike.
        def cipherCertFor(e: ServiceElementInfo): Option[DvdvCert] =
          e.cipherCertificate.orElse(
            elems
              .find(c =>
                c.serviceElementType.contains(ServiceElementType.CIPHER_CERTIFICATE) &&
                  c.serviceElementDescriptionName == e.serviceElementDescriptionName
              )
              .flatMap(_.cipherCertificate)
          )

        for {
          addr       <- find(ServiceElementType.OSCI_ADDRESSEE)
          intm       <- find(ServiceElementType.OSCI_INTERMEDIARY)
          addrUri    <- requireUri(ags, "OSCI_ADDRESSEE", addr)
          intUri     <- requireUri(ags, "OSCI_INTERMEDIARY", intm)
          addrCipher <- requireCipherCert(ags, "OSCI_ADDRESSEE", cipherCertFor(addr))
          intCipher  <- requireCipherCert(ags, "OSCI_INTERMEDIARY", cipherCertFor(intm))
          addrSig    <- addr.signatureCertificate.traverse(c =>
                          decodeCert(c).adaptError(t => OsciError.Certificate(t))
                        )
        }
        yield OsciRoute(addrUri, addrCipher, addrSig, intUri, intCipher)
      }

      private def requireUri(ags: Ags, kind: String, e: ServiceElementInfo): F[URI] =
        e.serviceElementUri.map(_.trim).filter(_.nonEmpty) match {
          case Some(s) => parseUri(s)
          case None    => Sync[F].raiseError[URI](OsciError.ServiceElementMissing(ags, kind))
        }

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
