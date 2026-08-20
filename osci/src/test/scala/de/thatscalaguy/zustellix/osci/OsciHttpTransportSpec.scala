package de.thatscalaguy.zustellix.osci

import cats.effect.IO
import munit.CatsEffectSuite

import java.net.{ServerSocket, SocketTimeoutException, URI}
import scala.concurrent.duration.*

class OsciHttpTransportSpec extends CatsEffectSuite {

  test("openConnection applies both timeouts") {
    val t = new OsciHttpTransport(3.seconds, 45.seconds)
    val c = t.openConnection(new URI("http://localhost:9/osci"))
    assertEquals(c.getConnectTimeout, 3000)
    assertEquals(c.getReadTimeout, 45000)
  }

  test("newInstance carries the timeouts into the per-request clone") {
    // osci-bibliothek clones the transport per request (OSCIRequest calls
    // TransportI.newInstance), so a clone without the timeouts would silently
    // reintroduce the hang.
    val t = new OsciHttpTransport(3.seconds, 45.seconds)
    t.newInstance() match {
      case clone: OsciHttpTransport =>
        assertEquals(clone.connectTimeout, 3.seconds)
        assertEquals(clone.readTimeout, 45.seconds)
      case other => fail(s"expected OsciHttpTransport, got $other")
    }
  }

  test("getResponseStream times out on a silent server instead of blocking forever") {
    IO.blocking {
      val server = new ServerSocket(0)
      try {
        val accepter = new Thread(() => {
          try {
            val s = server.accept()
            Thread.sleep(30000)
            s.close()
          } catch { case _: Throwable => () }
        })
        accepter.setDaemon(true)
        accepter.start()

        val t    = new OsciHttpTransport(5.seconds, 250.millis)
        val body = "<ping/>".getBytes("UTF-8")
        val out =
          t.getConnection(new URI(s"http://127.0.0.1:${server.getLocalPort}/osci"), body.length.toLong)
        out.write(body)
        out.flush()
        intercept[SocketTimeoutException](t.getResponseStream.read())
      } finally server.close()
    }
  }
}
