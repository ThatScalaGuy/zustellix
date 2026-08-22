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

package de.thatscalaguy.zustellix.dvdv

import munit.FunSuite
import org.http4s.implicits.uri

import scala.concurrent.duration.*

class DvdvConfigSpec extends FunSuite {

  test("entryPath defaults to StandaloneAuth and directoryBase is unchanged") {
    val cfg = DvdvConfig(baseUri = uri"http://x")
    assertEquals(cfg.entryPath, DvdvEntryPath.StandaloneAuth)
    assertEquals(cfg.directoryBase.renderString, "http://x/extern/standaloneauth/directory/v2")
  }

  test("InternDirectory derives intern/directory/v2") {
    val cfg = DvdvConfig(baseUri = uri"http://x", entryPath = DvdvEntryPath.InternDirectory)
    assertEquals(cfg.directoryBase.renderString, "http://x/intern/directory/v2")
  }

  test("BundesmasterAuth derives extern/bundesmasterauth/directory/v2") {
    val cfg = DvdvConfig(baseUri = uri"http://x", entryPath = DvdvEntryPath.BundesmasterAuth)
    assertEquals(cfg.directoryBase.renderString, "http://x/extern/bundesmasterauth/directory/v2")
  }

  test("defaultTokenTtl defaults to 5 minutes, independent of jwtLifetime") {
    val cfg = DvdvConfig(baseUri = uri"http://x")
    assertEquals(cfg.defaultTokenTtl, 5.minutes)
    assertEquals(cfg.copy(jwtLifetime = 1.second).defaultTokenTtl, 5.minutes)
  }

  test("retries default on with a 5-minute total deadline; RetryConfig.disabled turns them off") {
    val cfg = DvdvConfig(baseUri = uri"http://x")
    assertEquals(cfg.retryConfig, RetryConfig())
    assertEquals(cfg.retryConfig.maxRetries, 3)
    assertEquals(cfg.totalDeadline, Some(5.minutes))
    assertEquals(RetryConfig.disabled.maxRetries, 0)
  }

  test("tokenUriFor is entry-path-independent") {
    val cfg = DvdvConfig(baseUri = uri"http://x", entryPath = DvdvEntryPath.InternDirectory)
    assertEquals(cfg.tokenUriFor(uri"http://x").renderString, "http://x/extern/standaloneauth/token")
    val pinned = cfg.copy(tokenEndpoint = Some(uri"http://token.example/t"))
    assertEquals(pinned.tokenUriFor(uri"http://x").renderString, "http://token.example/t")
  }
}
