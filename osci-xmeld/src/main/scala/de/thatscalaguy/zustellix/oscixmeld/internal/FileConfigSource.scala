package de.thatscalaguy.zustellix.oscixmeld.internal

import cats.effect.Sync
import de.thatscalaguy.zustellix.utils.cert.CertSource
import de.thatscalaguy.zustellix.oscixmeld.*

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
 *   tenant.<id>.category               = <category>          (optional)
 *   tenant.<id>.requestTimeoutSeconds  = <int>               (optional)
 * }}}
 *
 *  The intermediary is no longer configured here — it is resolved per send
 *  from the recipient's DVDV service description (OSCI_INTERMEDIARY element).
 */
private[oscixmeld] final class FileConfigSource[F[_]: Sync](path: Path) extends ConfigSource[F] {

  def load: F[Map[TenantId, OSCIXMeldConfig]] =
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

  private def parseTenant(id: String, kv: Map[String, String]): OSCIXMeldConfig = {
    def req(k: String): String =
      kv.getOrElse(k, throw OSCIXMeldError.Config(s"tenant.$id.$k is missing"))

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
        throw OSCIXMeldError.Config(s"tenant.$id.cert.type must be 'pkcs12' or 'pem', got '$other'")
    }

    val timeout: FiniteDuration =
      kv.get("requestTimeoutSeconds")
        .map(s =>
          try s.trim.toInt.seconds
          catch case _: NumberFormatException =>
            throw OSCIXMeldError.Config(s"tenant.$id.requestTimeoutSeconds is not an integer: '$s'")
        )
        .getOrElse(60.seconds)

    OSCIXMeldConfig(
      tenantId       = TenantId(id),
      certSource     = certSource,
      serviceUri     = kv.getOrElse("serviceUri", OSCIXMeldConfig.DefaultXMeldServiceUri),
      category       = kv.getOrElse("category", "Meldebehörde"),
      requestTimeout = timeout
    )
  }
}
