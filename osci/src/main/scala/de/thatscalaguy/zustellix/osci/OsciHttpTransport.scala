package de.thatscalaguy.zustellix.osci

import de.osci.osci12.extinterfaces.TransportI

import java.io.{IOException, InputStream, OutputStream}
import java.net.{HttpURLConnection, MalformedURLException, URI, URLConnection}
import scala.concurrent.duration.*

/** HTTP transport for osci-bibliothek with connect and read timeouts.
 *
 *  Functional copy of the library's sample
 *  `de.osci.osci12.samples.impl.HttpTransport`, which opens its
 *  `HttpURLConnection`s without any timeout: a stalled intermediary blocks
 *  the calling thread forever — and the bridges run inside
 *  `Sync[F].blocking`, which cannot be cancelled once started. This variant
 *  applies `connectTimeout` and `readTimeout` to every connection it opens,
 *  so a dead or hanging endpoint surfaces as a `SocketTimeoutException`
 *  (mapped to an [[OsciError.OsciTransport]] by the bridges) instead.
 *
 *  osci-bibliothek clones the transport per request via [[newInstance]]; the
 *  clone carries the same timeouts. Note that `readTimeout` bounds each
 *  blocking read, not the whole exchange — size it for the slowest expected
 *  synchronous round trip (`MediateDelivery` waits for the addressee's
 *  answer within the call).
 */
final class OsciHttpTransport(
    val connectTimeout: FiniteDuration,
    val readTimeout:    FiniteDuration
) extends TransportI {

  private var con: URLConnection = null

  override def getVendor: String  = "ThatScalaGuy"
  override def getVersion: String = "1.0"

  override def newInstance(): TransportI =
    new OsciHttpTransport(connectTimeout, readTimeout)

  override def getResponseStream: InputStream = con.getInputStream

  override def isOnline(uri: URI): Boolean =
    try {
      con = openConnection(uri)
      con.connect()
      true
    } catch {
      case e: MalformedURLException => throw invalidUrl(e)
      case _: IOException           => false
    }

  override def getContentLength: Long =
    throw new UnsupportedOperationException("getContentLength is not implemented")

  override def getConnection(uri: URI, contentLength: Long): OutputStream =
    try {
      val http = openConnection(uri).asInstanceOf[HttpURLConnection]
      http.setInstanceFollowRedirects(false)
      http.setRequestMethod("POST")
      http.setRequestProperty("Content-Type", "text/xml")
      http.setRequestProperty("charset", "utf-8")
      http.setRequestProperty("Content-Length", contentLength.toString)
      http.setUseCaches(false)
      http.setDoOutput(true)
      con = http
      http.getOutputStream
    } catch {
      case e: MalformedURLException => throw invalidUrl(e)
    }

  private[osci] def openConnection(uri: URI): URLConnection = {
    val c = uri.toURL.openConnection()
    c.setConnectTimeout(toMillisInt(connectTimeout))
    c.setReadTimeout(toMillisInt(readTimeout))
    c
  }

  private def toMillisInt(d: FiniteDuration): Int =
    math.min(d.toMillis, Int.MaxValue.toLong).toInt

  private def invalidUrl(e: MalformedURLException): IOException =
    new IOException(s"invalid URL: ${e.getLocalizedMessage}", e)
}

object OsciHttpTransport {
  val DefaultConnectTimeout: FiniteDuration = 10.seconds
  val DefaultReadTimeout:    FiniteDuration = 120.seconds
}
