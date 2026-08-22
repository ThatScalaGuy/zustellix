package de.thatscalaguy.zustellix.osci.internal

import cats.effect.Sync
import de.thatscalaguy.zustellix.utils.cert.CertSource
import de.thatscalaguy.zustellix.osci.*

import java.io.FileInputStream
import java.nio.file.{Path, Paths}
import java.util.Properties
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Loads tenant configurations from a Java properties file with keys of the form
 *
 * {{{
 *   tenant.<id>.cert.type              = pkcs12 | pem
 *   tenant.<id>.cert.path              = <path>             (pkcs12: keystore, pem: cert file)
 *   tenant.<id>.cert.password          = <password>         (pkcs12 required, pem optional)
 *   tenant.<id>.cert.keyPath           = <path>             (pem only)
 *   tenant.<id>.serviceUri             = <wsdl uri>          (optional)
 *   tenant.<id>.subject                = <osci subject>      (optional)
 *   tenant.<id>.connectTimeoutMs       = <millis>            (optional)
 *   tenant.<id>.readTimeoutMs          = <millis>            (optional)
 *   tenant.<id>.contentSignatures      = require | warn      (optional, default warn)
 *   tenant.<id>.capturePayloads        = true | false        (optional, default false)
 *   tenant.<id>.explicitDialog         = true | false        (optional, default false)
 * }}}
 *
 *  The intermediary is no longer configured here — it is resolved per send
 *  from the recipient's DVDV service description (OSCI_INTERMEDIARY element).
 */
private[osci] final class FileConfigSource[F[_]: Sync](path: Path) extends ConfigSource[F] {

  def load: F[Map[TenantId, OsciConfig]] =
    Sync[F].blocking {
      val props = new Properties()
      val in    = new FileInputStream(path.toFile)
      try props.load(in)
      finally in.close()

      val byTenant: Map[String, Map[String, String]] =
        props.stringPropertyNames().asScala.toList.flatMap { key =>
          if key.startsWith("tenant.") then
            key.drop("tenant.".length).split("\\.", 2) match {
              case Array(id, sub) => Some((id, sub, props.getProperty(key)))
              case _              => None
            }
          else None
        }.groupBy(_._1).view.mapValues(_.map { case (_, k, v) => k -> v }.toMap).toMap

      byTenant.map { case (id, kv) =>
        TenantId(id) -> parseTenant(id, kv)
      }
    }

  private def parseTenant(id: String, kv: Map[String, String]): OsciConfig = {
    def req(k: String): String =
      kv.getOrElse(k, throw OsciError.Config(s"tenant.$id.$k is missing"))

    val certSource: CertSource = req("cert.type").trim.toLowerCase match {
      case "pkcs12" =>
        CertSource.Pkcs12(Paths.get(req("cert.path")), req("cert.password"))
      case "pem" =>
        CertSource.Pem(
          Paths.get(req("cert.path")),
          Paths.get(req("cert.keyPath")),
          kv.get("cert.password")
        )
      case other =>
        throw OsciError.Config(s"tenant.$id.cert.type must be 'pkcs12' or 'pem', got '$other'")
    }

    def timeoutMs(k: String, default: FiniteDuration): FiniteDuration =
      kv.get(k).fold(default) { v =>
        v.trim.toLongOption.filter(_ > 0).map(_.millis).getOrElse(
          throw OsciError.Config(
            s"tenant.$id.$k must be a positive number of milliseconds, got '$v'"
          )
        )
      }

    val contentSignatures =
      kv.get("contentSignatures").fold(ContentSignaturePolicy.Warn) { v =>
        v.trim.toLowerCase match {
          case "require" => ContentSignaturePolicy.Require
          case "warn"    => ContentSignaturePolicy.Warn
          case other =>
            throw OsciError.Config(
              s"tenant.$id.contentSignatures must be 'require' or 'warn', got '$other'"
            )
        }
      }

    def bool(k: String): Boolean =
      kv.get(k).fold(false) { v =>
        v.trim.toLowerCase match {
          case "true"  => true
          case "false" => false
          case other =>
            throw OsciError.Config(
              s"tenant.$id.$k must be 'true' or 'false', got '$other'"
            )
        }
      }

    OsciConfig(
      tenantId       = TenantId(id),
      certSource     = Some(certSource),
      serviceUri     = kv.getOrElse("serviceUri", OsciConfig.DefaultXMeldServiceUri),
      subject        = kv.getOrElse("subject", OsciConfig.DefaultSubject),
      connectTimeout = timeoutMs("connectTimeoutMs", OsciHttpTransport.DefaultConnectTimeout),
      readTimeout    = timeoutMs("readTimeoutMs", OsciHttpTransport.DefaultReadTimeout),
      contentSignatures = contentSignatures,
      capturePayloads   = bool("capturePayloads"),
      explicitDialog    = bool("explicitDialog")
    )
  }
}
