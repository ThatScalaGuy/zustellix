package de.thatscalaguy.zustellix.dvdv

import munit.FunSuite
import org.http4s.implicits.uri

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

  test("tokenUriFor is entry-path-independent") {
    val cfg = DvdvConfig(baseUri = uri"http://x", entryPath = DvdvEntryPath.InternDirectory)
    assertEquals(cfg.tokenUriFor(uri"http://x").renderString, "http://x/extern/standaloneauth/token")
    val pinned = cfg.copy(tokenEndpoint = Some(uri"http://token.example/t"))
    assertEquals(pinned.tokenUriFor(uri"http://x").renderString, "http://token.example/t")
  }
}
