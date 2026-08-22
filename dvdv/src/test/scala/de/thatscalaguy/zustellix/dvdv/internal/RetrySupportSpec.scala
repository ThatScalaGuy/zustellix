/*
 * Copyright 2026 ThatScalaGuy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.thatscalaguy.zustellix.dvdv.internal

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import de.thatscalaguy.zustellix.dvdv.RetryConfig
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.client.middleware.Retry
import org.http4s.implicits.uri
import org.typelevel.ci.CIString

import java.net.ConnectException
import scala.concurrent.duration.*

class RetrySupportSpec extends CatsEffectSuite {

  // baseDelay = 1.milli keeps the real-clock tests fast
  private val cfg = RetryConfig(baseDelay = 1.milli)

  private def retried(cfg: RetryConfig)(backend: Client[IO]): Client[IO] =
    Retry[IO](RetrySupport.policy[IO](cfg))(backend)

  /** Backend that counts hits and serves `responses` in order, repeating the
   *  last one.
   */
  private def counted(hits: Ref[IO, Int])(responses: IO[Response[IO]]*): Client[IO] =
    Client.fromHttpApp(HttpApp[IO] { _ =>
      hits.getAndUpdate(_ + 1).flatMap(n => responses(math.min(n, responses.size - 1)))
    })

  private val ok          = IO(Response[IO](Status.Ok))
  private val unavailable = IO(Response[IO](Status.ServiceUnavailable))

  private val get  = Request[IO](Method.GET, uri"http://dvdv.test/x")
  private val post = Request[IO](Method.POST, uri"http://dvdv.test/x")

  test("GET 503 then 200: retried, succeeds, backend hit twice") {
    for {
      hits <- Ref.of[IO, Int](0)
      st   <- retried(cfg)(counted(hits)(unavailable, ok)).status(get)
      n    <- hits.get
    } yield {
      assertEquals(st, Status.Ok)
      assertEquals(n, 2)
    }
  }

  test("GET transport error then 200: retried, succeeds, backend hit twice") {
    for {
      hits <- Ref.of[IO, Int](0)
      st   <- retried(cfg)(counted(hits)(IO.raiseError(new ConnectException("refused")), ok)).status(get)
      n    <- hits.get
    } yield {
      assertEquals(st, Status.Ok)
      assertEquals(n, 2)
    }
  }

  test("persistent GET 503 gives up after maxRetries and returns the 503") {
    for {
      hits <- Ref.of[IO, Int](0)
      st   <- retried(cfg.copy(maxRetries = 2))(counted(hits)(unavailable)).status(get)
      n    <- hits.get
    } yield {
      assertEquals(st, Status.ServiceUnavailable)
      assertEquals(n, 3) // initial attempt + 2 retries
    }
  }

  test("POST 503 is never retried") {
    for {
      hits <- Ref.of[IO, Int](0)
      st   <- retried(cfg)(counted(hits)(unavailable)).status(post)
      n    <- hits.get
    } yield {
      assertEquals(st, Status.ServiceUnavailable)
      assertEquals(n, 1)
    }
  }

  test("definitive answers are not retried: GET 404 is returned after one hit") {
    for {
      hits <- Ref.of[IO, Int](0)
      st   <- retried(cfg)(counted(hits)(IO(Response[IO](Status.NotFound)))).status(get)
      n    <- hits.get
    } yield {
      assertEquals(st, Status.NotFound)
      assertEquals(n, 1)
    }
  }

  test("maxRetries = 0 disables retrying") {
    for {
      hits <- Ref.of[IO, Int](0)
      st   <- retried(RetryConfig.disabled)(counted(hits)(unavailable)).status(get)
      n    <- hits.get
    } yield {
      assertEquals(st, Status.ServiceUnavailable)
      assertEquals(n, 1)
    }
  }

  test("a 429 Retry-After larger than the backoff delays the retry by the header") {
    TestControl.executeEmbed {
      for {
        hits <- Ref.of[IO, Int](0)
        tooMany = IO(
                    Response[IO](Status.TooManyRequests)
                      .putHeaders(Header.Raw(CIString("Retry-After"), "2"))
                  )
        t0 <- IO.monotonic
        st <- retried(cfg)(counted(hits)(tooMany, ok)).status(get)
        t1 <- IO.monotonic
        n  <- hits.get
      } yield {
        assertEquals(st, Status.Ok)
        assertEquals(n, 2)
        assert(t1 - t0 >= 2.seconds, s"retried after ${t1 - t0}, expected the header's 2s") // backoff alone is ~1ms
      }
    }
  }

  private val failure: Either[Throwable, Response[IO]] = Right(Response[IO](Status.ServiceUnavailable))

  test("backoff doubles from baseDelay and caps at maxDelay with jitter = 0") {
    val p = RetrySupport.policy[IO](RetryConfig(maxRetries = 10, baseDelay = 100.millis, maxDelay = 1.second, jitter = 0.0))
    assertEquals(p(get, failure, 1), Some(100.millis))
    assertEquals(p(get, failure, 2), Some(200.millis))
    assertEquals(p(get, failure, 3), Some(400.millis))
    assertEquals(p(get, failure, 8), Some(1.second))
    assertEquals(p(get, failure, 11), None)
  }

  test("jitter = 0.5 lands each delay in [capped/2, capped], deterministically") {
    val p = RetrySupport.policy[IO](RetryConfig(maxRetries = 10, baseDelay = 100.millis, maxDelay = 1.second, jitter = 0.5))
    (1 to 6).foreach { attempt =>
      val capped = (100L << (attempt - 1)).millis.min(1.second)
      val first  = p(get, failure, attempt)
      assertEquals(first, p(get, failure, attempt))
      val d = first.getOrElse(fail(s"no delay for attempt $attempt"))
      assert(d >= capped / 2, s"attempt $attempt: $d below ${capped / 2}")
      assert(d <= capped, s"attempt $attempt: $d above $capped")
    }
  }

  test("a pool wait-queue timeout is not retried") {
    val p = RetrySupport.policy[IO](RetryConfig())
    assertEquals(p(get, Left(WaitQueueTimeoutException), 1), None)
  }
}
