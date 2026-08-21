# zustellix

A typed, **tagless-final Scala 3** toolkit for the German public-administration
messaging stack:

- **`dvdv`** — a client for the [**DVDV2 v2 öffentliche API**](https://www.dataport.de/)
  (Deutsches Verwaltungsdiensteverzeichnis), scoped to the
  `extern/standaloneauth/directory` entry path. Look up authorities,
  categories, certificates and service descriptions.
- **`osci`** — speaks **OSCI** (Governikus osci-bibliothek) in both shapes:
  synchronous request/response (XMeld Personensuche against a Meldebehörde,
  routes resolved automatically from DVDV per recipient AGS) and asynchronous
  messaging (XFamilie-style: store into a recipient's mailbox, fetch + ack
  from your own mailbox at an intermediary).
- **`utils`** — the certificate plumbing both of the above share: load
  PKCS12/PEM material, or resolve it by alias from an in-memory map or a
  hot-reloaded directory.

Built on Scala 3 · Cats Effect 3 · http4s 0.23 (Ember) · circe · mules ·
BouncyCastle · osci-bibliothek.

Design principles:

- **Tagless final** — every algebra is `F[_]`; run it with `IO`,
  `Resource[F, _]`, or your own effect.
- **Per-tenant isolation** — one client per cert/config, with its own token
  cache and response caches. Nothing is shared by accident.
- **Caches mandatory** — every cacheable DVDV endpoint is backed by
  [mules](https://github.com/davenverse/mules) with sensible default TTLs
  (overridable, or disable entirely for tests).
- **Both cert formats** — PKCS12 (`.p12`) or PEM (`cert.pem` + `key.pem`).
- **Standalone auth** — JWT `client_credentials` flow, `EmbeddedBearer`
  token header, automatic single retry on `401`.

---

## Modules at a glance

| Module  | sbt name | Depends on      | Purpose |
|---------|----------|-----------------|---------|
| `utils` | `utils`  | —               | `CertSource` / `CertLoader` / `CertManager` — shared certificate material |
| `dvdv`  | `dvdv`   | `utils`         | Tagless-final DVDV2 v2 directory client (JWT auth + mules caching) |
| `osci`  | `osci`   | `dvdv`, `utils` | OSCI client: sync request (XMeld), async send + mailbox (XFamilie); DVDV-driven routing; single- and multi-tenant |

Dependency direction:

```
osci ──▶ dvdv ──▶ utils
  └────────────────▶ utils
```

---

## Install

```scala
// build.sbt — pick the module you need (transitive deps are pulled in)
libraryDependencies += "de.thatscalaguy" %% "zustellix-osci"  % "0.2.0"
// or just the directory client:
libraryDependencies += "de.thatscalaguy" %% "zustellix-dvdv"  % "0.2.0"
// or only the cert utilities:
libraryDependencies += "de.thatscalaguy" %% "zustellix-utils" % "0.2.0"
```

> **Migrating from 0.1.x:** `zustellix-osci-xmeld` is frozen at 0.1.1 and
> replaced by `zustellix-osci`. Package
> `de.thatscalaguy.zustellix.oscixmeld` → `de.thatscalaguy.zustellix.osci`;
> `OSCIXMeld.send` → `OsciClient.request`; `OSCIXMeldConfig` → `OsciConfig`
> (the unused `category` / `requestTimeout` fields are gone);
> `OSCIXMeldFacade` → `OsciFacade`; `OSCIXMeldError` → `OsciError`.

> **Migrating to 0.4.x:** feedback handling is now OSCI-1.2-conformant —
> `3xxx` codes are warnings, not errors (see
> [OSCI feedback codes](#osci-feedback-codes)). When upgrading:
>
> - `OsciReceipt` and `Laufzettel` gained a
>   `warnings: List[OsciFeedback] = Nil` field. Construction and field
>   access compile unchanged; pattern matches that destructure every field
>   need the new one, and codecs/schemas derived from the case-class shape
>   (circe, doobie, a DB table mirroring `Laufzettel`) must add it.
> - `Laufzettel.rawXml` is now `Option[String]` and **empty by default**. The
>   decrypted response XML of a `request` contains personal data, so it is no
>   longer handed to the sink unless payload capture is explicitly enabled
>   with `OsciConfig.capturePayloads = true` (properties key
>   `tenant.<id>.capturePayloads`) — see [Laufzettel](#laufzettel). With
>   capture on, a response without extractable content is `None` (instead of
>   the former `""`); for `send` and failure records it is always `None`.
>   Codecs/schemas derived from the case-class shape (circe, doobie, a DB
>   table mirroring `Laufzettel`) must make the column nullable, and sinks
>   that relied on the payload must opt in.
> - `OsciClient.request` / `OsciFacade.request` now return `F[OsciResponse]`
>   instead of `F[String]`: the response payload moved to
>   `OsciResponse.xml: Option[String]` — `None` (instead of the former `""`)
>   when the answer had no extractable content — and the intermediary's
>   `messageId`, `status` and `3xxx` `warnings` are now visible on the
>   synchronous path too. Callers that only want the payload use
>   `rsp.xml.getOrElse("")`.
> - `request` / `send` no longer raise `OsciError.OsciResponse` for `3xxx`
>   feedback (e.g. `3802` "Signatur des Empfängers über die Annahme- bzw.
>   Bearbeitungsantwort fehlt") — the call succeeds and the codes land in
>   `warnings`. Move alerting/retry logic keyed on that exception to
>   inspecting `warnings`; a remaining `OsciResponse` is a real `9xxx`
>   failure. `Laufzettel.status` can now carry a `3xxx` code, so
>   "starts with `0` = success" checks must accept `0xxx` **and** `3xxx`
>   as delivered — `LaufzettelStatus.delivered` does exactly that.
> - All feedback rows are scanned now: a `9xxx` error behind a per-language
>   duplicate row raises where it previously slipped through, and the
>   mailbox's `pending` / `fetch` no longer abort on `3800` / `3801`
>   ("more available than the fetch limit").
> - `OsciMailbox.pending` now returns `F[PendingPage]` instead of
>   `F[List[PendingDelivery]]`: the deliveries moved to
>   `PendingPage.deliveries`, and the response's `3xxx` `warnings` ride
>   along — `PendingPage.truncated` (derived from `3800` / `3801`) finally
>   tells "mailbox fully listed" apart from "listing cut off at
>   `fetchLimit`", which `size == fetchLimit` alone could not.
> - `OsciMessage` gained a `warnings: List[OsciFeedback] = Nil` field for
>   the `3xxx` feedback of the fetch response. Construction and field access
>   compile unchanged; pattern matches that destructure every field need the
>   new one.
> - `OsciError.OsciResponse` gained a `messageId: Option[String] = None`
>   field. Construction compiles unchanged; pattern matches that destructure
>   every field need the new one.
> - Failed deliveries now record a `Laufzettel` too (see
>   [Laufzettel](#laufzettel)). Sinks that treated every record as a
>   delivered message must check `status`: `0xxx` / `3xxx` is delivered,
>   anything else (a `9xxx` code or an error kind like `OsciTransport`) is a
>   failure record with `rawXml = None`.
> - The AGS is now the opaque type `Ags` instead of a plain `String` — in
>   `OsciClient.request` / `send`, `OsciFacade.request` / `send`,
>   `Laufzettel.recipientAgs` and the `ags` field of `AgsNotInDvdv`,
>   `RecipientCertMissing` and `ServiceElementMissing`. Build one with
>   `Ags.from(s)` (`Either[OsciError.InvalidAgs, Ags]`, exactly 8 digits) or
>   `Ags.unsafe(s)` (throws); read the raw string back with `.value`.
> - `OsciError.RecipientCertMissing` gained a `kind` field naming the
>   affected service element (`OSCI_ADDRESSEE` / `OSCI_INTERMEDIARY`) — it
>   was previously appended to the `ags` string.
> - `Laufzettel.status` is now a `LaufzettelStatus` instead of a `String`:
>   `Feedback(code)` carries an OSCI feedback code, `Failed(kind)` an error
>   kind when no code exists. `status.delivered` replaces "starts with `0`
>   or `3`" checks, and `status.render` yields the previous plain string for
>   logs and DB columns.

---

## `utils` — certificates

Everything starts with a certificate. DVDV uses it to sign the
`client_assertion` JWT (RS256, **not** mTLS); OSCI uses it as the Originator's
signing + decryption key. Both consume the same material through `utils`.

### A single certificate: `CertSource`

```scala
import de.thatscalaguy.zustellix.utils.cert.CertSource
import java.nio.file.Paths

// PKCS12
CertSource.Pkcs12(
  path     = Paths.get("/secrets/client.p12"),
  password = "changeit"
)

// PEM (cert + key in separate files)
CertSource.Pem(
  certPath    = Paths.get("/secrets/client-cert.pem"),
  keyPath     = Paths.get("/secrets/client-key.pem"),
  keyPassword = None            // Some("...") for encrypted PKCS#8 / RSA keys
)
```

When the material arrives from a secret manager rather than a readable file,
use the in-memory variants — same loading, no temp file:

```scala
CertSource.Pkcs12Bytes(bytes = p12Bytes, password = "changeit")

CertSource.PemBytes(
  cert        = certBytes,
  key         = keyBytes,
  keyPassword = None
)
```

`CertLoader.load[F]` turns any of them into a `LoadedCert` (private key, X509,
SHA-1 fingerprint hex, and the leaf-first certificate chain the source
provides). The DVDV/OSCI clients call this for you — you rarely touch it
directly.

`toString` is redacted on every case, so a `CertSource` (or a config holding
one) can be logged without leaking its password.

### Many certificates by alias: `CertManager`

For multi-tenant deployments, resolve credentials by a `CertAlias` instead of
hard-coding paths. A `CertManager[F]` returns a `CertCredential` (raw PKCS12
bytes + password) that **both** DVDV and OSCI can consume for the same tenant.

On this path the cert comes from the manager, so `certSource` is left unset on
`DvdvConfig` / `OsciConfig` — it defaults to `None`.

**In memory, hot-swappable:**

```scala
import cats.effect.IO
import de.thatscalaguy.zustellix.utils.cert.*

for {
  mgr  <- InMemoryCertManager.make[IO](Map(
            CertAlias("flensburg") -> CertCredential(p12Bytes, "secret")
          ))
  cred <- mgr.resolve(CertAlias("flensburg"))     // raises UnknownCert if absent
  // later, atomically replace the whole map (e.g. on config reload):
  _    <- mgr.swap(Map(CertAlias("kiel") -> CertCredential(otherBytes, "s2")))
} yield ()
```

**Backed by a directory, polled and hot-reloaded:**

```scala
import cats.effect.IO
import de.thatscalaguy.zustellix.utils.cert.*
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.log4cats.LoggerFactory
import java.nio.file.Paths
import scala.concurrent.duration.*

given LoggerFactory[IO] = Slf4jFactory.create[IO]

val cfg = DirectoryCertManagerConfig(
  dir      = Paths.get("/secrets/certs"),  // scanned for <alias>.p12
  interval = 30.seconds                    // rebuilt every interval
  // passwordsFile defaults to <dir>/passwords.properties (alias=password)
)

DirectoryCertManager.resource[IO](cfg).use { certs =>
  certs.knownAliases.flatMap(IO.println)
}
```

The first scan completes before the `Resource` is ready (a misconfigured
directory fails fast). A corrupt `<alias>.p12` is logged and skipped — the
rest still swap in. The active map always reflects current disk truth, so a
rotated-away cert is never served stale.

`zustellix-utils` depends only on `log4cats-core`, so to use `Slf4jFactory`
as shown above, add `org.typelevel::log4cats-slf4j` plus an SLF4J backend
(e.g. `logback-classic`) to your own build — or provide any other
`LoggerFactory[F]` implementation.

---

## `dvdv` — DVDV2 directory client

### Quick start

```scala
import cats.effect.{IO, IOApp}
import de.thatscalaguy.zustellix.dvdv.*
import de.thatscalaguy.zustellix.utils.cert.CertSource
import org.http4s.implicits.uri

import java.nio.file.Paths

object Demo extends IOApp.Simple:

  val config = DvdvConfig(
    baseUri    = uri"https://your-dvdv-betreiber.example",
    certSource = Some(CertSource.Pkcs12(
      path     = Paths.get("/secrets/my-client.p12"),
      password = sys.env("MY_CLIENT_P12_PASSWORD")
    ))
  )

  def run: IO[Unit] =
    DvdvClient.resource[IO](config).use { dvdv =>
      for
        cats  <- dvdv.categories
        org   <- dvdv.findAuthorityDescription("Meldebehörde", "ags:01999001")
        check <- dvdv.verifyCategory("0272c56c9742a62501329a3aa78974f1605c92a2", "Meldebehörde")
        _     <- IO.println(s"Got ${cats.size} top-level categories")
        _     <- IO.println(s"Organization: ${org.flatMap(_.organization).map(_.nameDe)}")
        _     <- IO.println(s"Category verification: ${check.verifyCategory}")
      yield ()
    }
```

The first call drives the full JWT → token → endpoint flow. The token is
cached and refreshed ahead of expiry; cacheable responses are memoized.

### Constructors

```scala
// Ember-backed, single tenant from config.certSource (needs Async + Network).
// Raises DvdvError.Config if config.certSource is None:
DvdvClient.resource[IO](config)

// Ember-backed, signing cert resolved from a shared CertManager by alias:
DvdvClient.resource[IO](config, certManager, CertAlias("flensburg"))

// Bring your own http4s Client (tests, non-Ember backends; needs Async):
DvdvClient.fromClient[IO](config, myClient)
DvdvClient.fromClient[IO](config, myClient, certManager, CertAlias("kiel"))
```

On the `CertManager` overloads the alias is resolved once at build time (so an
unknown alias fails fast) and then again on every token refresh — a cert
rotated in the manager (e.g. by `DirectoryCertManager`) signs the next
`client_assertion` without a restart. The `certSource` constructors load the
cert once and keep it for the lifetime of the client.

### Configuration

```scala
import de.thatscalaguy.zustellix.dvdv.{CacheConfig, DvdvConfig}
import scala.concurrent.duration.*

val config = DvdvConfig(
  baseUri          = uri"https://your-dvdv-betreiber.example",
  certSource       = Some(CertSource.Pkcs12(p12Path, password)),
                                      // omit entirely when using CertManager + CertAlias

  issuer           = None,            // JWT iss; defaults to "fp:<sha1-fingerprint>"
  audience         = None,            // token URI; defaults to baseUri/extern/standaloneauth/token
  jwtLifetime      = 60.seconds,      // client_assertion lifetime
  tokenRefreshSkew = 30.seconds,      // refresh this far ahead of expiry
  requestTimeout   = 30.seconds,

  cacheConfig = CacheConfig(
    categoriesTtl               = 2.hours,     // override any subset
    findAuthorityDescriptionTtl = 15.minutes,
    verifyCategoryTtl           = 1.minute
  )
)

// Disable caching entirely (useful in tests):
DvdvConfig(baseUri = ???, certSource = Some(???), cacheConfig = CacheConfig.disabled)
```

Default TTLs:

| Endpoint                                                            | Default TTL |
|---------------------------------------------------------------------|-------------|
| `categories`, `intermediaries`                                      | 1 hour      |
| `findCertificateByFingerprint`                                      | 1 hour      |
| `findServiceSpecificationUrisByCategory`                            | 1 hour      |
| `findAuthorityDescription(s)`, `findCategories`                     | 10 minutes  |
| `findServiceDescription`, `findOrganizationsByServiceElement`       | 10 minutes  |
| `verifyCategory`                                                    | 5 minutes   |
| `serviceVersion`, all `batch*` POSTs                                | not cached  |

### API examples

```scala
// Category tree
dvdv.categories.flatMap { tree =>
  IO {
    tree.foreach { l1 =>
      println(l1.name)
      l1.children.toList.flatten.foreach(l2 => println(s"  ${l2.name}"))
    }
  }
}

// Look up an organization (Option: 204 No Content → None)
import de.thatscalaguy.zustellix.dvdv.model.*
val org: IO[Option[OrganizationDescription]] =
  dvdv.findAuthorityDescription(
    category        = "Meldebehörde",
    organizationKey = "ags:01999001"
  )

// Certificate by fingerprint
dvdv.findCertificateByFingerprint("0272c56c9742a62501329a3aa78974f1605c92a2")
  .map(_.flatMap(_.nameSubject))               // Some("GRP: Stadt Flensburg XhD-T") | None

// Organizations by service element
dvdv.findOrganizationsByServiceElement(
  serviceElementType = ServiceElementType.OSCI_ADDRESSEE,
  parameterType      = ParameterType.CIPHER_CERTIFICATE,
  parameterValue     = "80157bbb3934cb651fb4df94a98773fba0b02b03"
)

// Verify a fingerprint belongs to a category
dvdv.verifyCategory(
  fingerPrint = "11:51:43:a1:b5:fc:8b:b7:0a:3a:a9:b1:0f:66:73:22",
  category    = "Behörde"
).map(_.verifyCategory)                        // Boolean

// Batch lookup
val batch = List(
  Request(category = Some("Meldebehörde"), organizationKey = Some("ags:01001000")),
  Request(category = Some("Meldebehörde"), organizationKey = Some("ags:02000000"))
)
dvdv.batchFindAuthorityDescription(batch)
```

### Error handling

Every non-success response raises a typed `DvdvError` (a `RuntimeException`):

```scala
import de.thatscalaguy.zustellix.dvdv.DvdvError

dvdv.findAuthorityDescription("Meldebehörde", "ags:irrtum").attempt.flatMap {
  case Right(Some(org))                         => IO.println(org)
  case Right(None)                              => IO.println("no match (204)")
  case Left(DvdvError.NotFound(p))              => IO.println(s"404: ${p.detail}")
  case Left(DvdvError.ValidationError(p))       => IO.println(s"400: ${p.detail}")
  case Left(DvdvError.AuthenticationError(p))   => IO.println(s"401: ${p.detail}")
  case Left(DvdvError.Unexpected(status, body)) => IO.println(s"$status: $body")
  case Left(DvdvError.TransportError(cause))    => IO.println(s"transport: $cause")
}
```

On `401` the auth middleware releases the response, invalidates the cached
token, and retries the request **exactly once** before propagating the error.

### Algebra

```scala
trait DvdvClient[F[_]]:
  def categories:     F[List[DirectoryOrganizationCategoryLevel1DTO]]
  def intermediaries: F[List[SummaryServiceElementDTO]]
  def serviceVersion: F[ServiceVersion]

  def findAuthorityDescription(category: String, organizationKey: String): F[Option[OrganizationDescription]]
  def findAuthorityDescriptions(organizationKey: String): F[List[OrganizationDescription]]
  def findCategories(fingerPrint: String, organizationKey: String): F[List[String]]
  def findCertificateByFingerprint(fingerPrint: String): F[Option[Certificate]]
  def findOrganizationsByServiceElement(set: ServiceElementType, pt: ParameterType, pv: String): F[OrganizationDescription]
  def findServiceDescription(organizationKey: String, serviceSpecificationUri: String): F[Option[Service]]
  def findServiceSpecificationUrisByCategory(category: String): F[List[ServiceBase]]
  def verifyCategory(fingerPrint: String, category: String): F[VerificationResult]

  def batchFindAuthorityDescription(requests: List[Request]): F[OrganizationDescription]
  def batchFindCategories(requests: List[Request]): F[List[List[String]]]
  def batchFindOrganizationsByServiceElement(requests: List[Request]): F[OrganizationDescription]
  def batchFindServiceDescription(requests: List[Request]): F[Service]
  def batchFindServiceSpecificationUrisByCategory(requests: List[Request]): F[Request]
  def batchVerifyCategory(requests: List[Request]): F[List[VerificationResult]]
```

---

## `osci` — OSCI messaging (sync + async)

`OsciClient` covers the outbound directions; `OsciMailbox` covers the inbound
one:

| Operation | OSCI message type | Shape |
|-----------|-------------------|-------|
| `OsciClient.request(ags, xml)` | `MediateDelivery` | synchronous request/response (e.g. XMeld Personensuche), returns an `OsciResponse` |
| `OsciClient.send(ags, xml)`    | `StoreDelivery`   | asynchronous: stored in the recipient's mailbox, returns an `OsciReceipt` |
| `OsciMailbox.pending` / `fetch`| `FetchProcessCard` / `FetchDelivery` | asynchronous receive + ack from your own mailbox (e.g. XFamilie) |

Recipients are addressed by their `Ags` (amtlicher Gemeindeschlüssel) — an
opaque type that only admits well-formed keys: `Ags.from("01001000")`
validates (exactly 8 digits, `Either[OsciError.InvalidAgs, Ags]`),
`Ags.unsafe(...)` throws on bad input, `.value` reads the raw string back.
A typo fails at the call site instead of surfacing as a DVDV miss.

Every outbound operation:

1. calls `dvdv.findServiceDescription("ags:<ags>", serviceUri)` **once** per
   call (memoized by the DVDV mules cache);
2. pulls **both** the addressee (`OSCI_ADDRESSEE`) and intermediary
   (`OSCI_INTERMEDIARY`) routes out of that single service description —
   neither is configured statically;
3. signs the content with the Originator cert, end-to-end encrypts it for the
   addressee (the intermediary stays blind to personal data), and transmits it
   via osci-bibliothek;
4. records a `Laufzettel` to the configured sink — on failure too, so the
   audit trail is not success-only (best-effort — a sink failure never fails
   the operation).

> The OSCI bridge requires a PKCS12 `CertSource` (`Pkcs12` or `Pkcs12Bytes`) —
> neither PEM variant is supported here.

### Single tenant (sync XMeld)

```scala
import cats.effect.{IO, IOApp}
import de.thatscalaguy.zustellix.dvdv.*
import de.thatscalaguy.zustellix.osci.*
import de.thatscalaguy.zustellix.utils.cert.CertSource
import org.http4s.implicits.uri
import java.nio.file.Paths

object SendDemo extends IOApp.Simple:

  val cert = CertSource.Pkcs12(Paths.get("/secrets/flensburg.p12"), sys.env("P12_PW"))

  val dvdvConfig = DvdvConfig(
    baseUri    = uri"https://your-dvdv-betreiber.example",
    certSource = Some(cert)
  )
  val osciConfig = OsciConfig(
    tenantId   = TenantId("flensburg"),
    certSource = Some(cert)      // same PKCS12: DVDV signs the JWT, OSCI signs/decrypts
    // serviceUri + subject default to the XMeld Personensuche profile
  )

  def run: IO[Unit] =
    (for
      dvdv <- DvdvClient.resource[IO](dvdvConfig)
      osci <- OsciClient.resource[IO](osciConfig, dvdv, LaufzettelSink.console[IO])
    yield osci).use { osci =>
      osci.request(ags = Ags.unsafe("01001000"), xml = "<xmeld>...</xmeld>").flatMap { rsp =>
        IO.println(s"[${rsp.status}] ${rsp.xml.getOrElse("<no content>")}")
      }
    }
```

The given `DvdvClient` is owned by the caller — the `OsciClient` resource does
not close it.

`request` returns an `OsciResponse(xml, messageId, status, warnings)`:

- `xml: Option[String]` — the recipient's decrypted (and per policy
  signature-checked) response payload; `None` when the answer carried no
  extractable content;
- `messageId: String` — the intermediary-issued message id, the handle for
  any later process-card inquiry;
- `status: String` — the top OSCI feedback code (e.g. `"0800"`);
- `warnings: List[OsciFeedback]` — warning-class (`3xxx`) feedback, e.g.
  `3802` (see [OSCI feedback codes](#osci-feedback-codes)).

### Asynchronous send (StoreDelivery)

For profiles whose recipients answer asynchronously (e.g. XFamilie), `send`
stores the message in the recipient's mailbox at their intermediary and
returns immediately with a receipt:

```scala
val xfamConfig = OsciConfig(
  tenantId   = TenantId("flensburg"),
  certSource = Some(cert),
  serviceUri = "urn:xfamilie:...",   // the profile's DVDV service URI
  subject    = "XFamilie"
)

OsciClient.resource[IO](xfamConfig, dvdv, LaufzettelSink.console[IO]).use { osci =>
  osci.send(ags = Ags.unsafe("01001000"), xml = "<xfamilie>...</xfamilie>").flatMap { receipt =>
    IO.println(s"stored as ${receipt.messageId} (status ${receipt.status})")
  }
}
```

### Receiving: `OsciMailbox` (fetch + ack)

The inbound leg of asynchronous profiles: other parties store messages into
**your** mailbox at **your** intermediary, and you poll it. The mailbox is
configured statically (it is yours — it is not resolved from DVDV):

```scala
import cats.syntax.all.*
import de.thatscalaguy.zustellix.osci.*
import java.io.FileInputStream
import java.net.URI
import java.security.cert.{CertificateFactory, X509Certificate}

// The intermediary's cipher cert, e.g. from a DER/PEM file:
val intermedCert: X509Certificate =
  val in = new FileInputStream("/secrets/intermed.crt")
  try CertificateFactory.getInstance("X.509")
    .generateCertificate(in).asInstanceOf[X509Certificate]
  finally in.close()

val mailboxConfig = OsciMailboxConfig(
  intermedUri        = URI.create("https://intermed.example/osci-manager"),
  intermedCipherCert = intermedCert
  // fetchLimit = 100
)

OsciMailbox.resource[IO](mailboxConfig, cert).use { mailbox =>
  for
    page <- mailbox.pending                       // un-fetched deliveries, process cards only
    _    <- page.deliveries.traverse_ { p =>
              mailbox.fetch(p.messageId).flatMap { msg =>
                IO.println(s"${msg.messageId} [${msg.subject}]: ${msg.xml}")
              }
            }
    _    <- IO.println("more waiting, poll again").whenA(page.truncated)
  yield ()
}
```

A `pending` listing is capped at `fetchLimit`. When the mailbox holds more,
the intermediary flags the response with feedback code `3800` / `3801` —
surfaced as `PendingPage.truncated` (the raw entries are in
`PendingPage.warnings`). Since fetching acknowledges (see below), fetch the
listed deliveries and call `pending` again for the next page; the count of a
page alone cannot tell a full listing from a cut-off one.

**Acknowledgement semantics.** OSCI 1.2 has no separate ack message — a
successful `fetch` makes the intermediary record a *reception* entry on the
message's process card, which removes it from `pending`. The fetch **is** the
acknowledgement: an un-acked (never fetched) message keeps showing up in
`pending`, a fetched one never does.

**At-least-once processing** is the caller's to build, and the API is shaped
for it: persist the `messageId`s from `pending` *before* fetching, and after a
crash re-`fetch` the unprocessed ids directly — deliveries remain stored at
the intermediary after reception (subject to its retention policy).

One mailbox can serve several profiles; filter on `PendingDelivery.subject`
client-side if you need to split them.

### Content signature verification

Received content — synchronous `request` answers and mailbox `fetch`
deliveries — is checked for the author's content signature
(Inhaltsdatensignatur) after decryption. The dialog-level checks of
osci-bibliothek only cover the intermediary envelope signature; the content
signature is what actually proves the author of the payload.

An **invalid** signature always raises `OsciError.InvalidContentSignature`,
regardless of configuration. What happens for content that carries **no**
signature at all is configurable per client/mailbox:

```scala
val osciConfig = OsciConfig(
  tenantId          = TenantId("flensburg"),
  certSource        = Some(cert),
  contentSignatures = ContentSignaturePolicy.Require   // default: Warn
)

val mailboxConfig = OsciMailboxConfig(
  intermedUri        = URI.create("https://intermed.example/osci-manager"),
  intermedCipherCert = intermedCert,
  contentSignatures  = ContentSignaturePolicy.Require  // default: Warn
)
```

- `ContentSignaturePolicy.Warn` (default): unsigned content is accepted and
  surfaced as `ContentSignatureStatus.Unsigned` — on `OsciMessage.signature`
  for mailbox fetches and on `Laufzettel.contentSignature` for `request`
  responses. Some gateways answer unsigned (the condition feedback code
  `3802` warns about), which is why this is the default.
- `ContentSignaturePolicy.Require`: unsigned content raises
  `OsciError.UnsignedContent`.

Fully verified content yields `ContentSignatureStatus.Valid`. The check
verifies the signatures cryptographically against the certificates embedded
in the message; certificate chain/trust validation stays with the caller.

### HTTP timeouts and custom transport

All wire operations go through an `OsciHttpTransport` — a drop-in for
osci-bibliothek's sample `HttpTransport` that additionally sets a connect and
a read timeout on every `HttpURLConnection`, so a stalled intermediary
surfaces as an `OsciError.OsciTransport` instead of blocking the calling thread
forever (the bridges run inside `Sync[F].blocking`, which cannot be
cancelled once started). Both timeouts are configurable per client/mailbox
and default to 10 s connect / 120 s read:

```scala
import scala.concurrent.duration.*

val osciConfig = OsciConfig(
  tenantId       = TenantId("flensburg"),
  certSource     = Some(cert),
  connectTimeout = 5.seconds,
  readTimeout    = 60.seconds
)

val mailboxConfig = OsciMailboxConfig(
  intermedUri        = URI.create("https://intermed.example/osci-manager"),
  intermedCipherCert = intermedCert,
  connectTimeout     = 5.seconds,
  readTimeout        = 60.seconds
)
```

Note that `readTimeout` bounds each blocking read, not the whole exchange —
size it for the slowest expected synchronous round trip (`request` waits for
the addressee's answer within the call).

Both `OsciClient.resource` and `OsciMailbox.resource` also accept your own
`de.osci.osci12.extinterfaces.TransportI` as a trailing argument (proxies,
custom TLS, instrumentation, …); the config timeouts then do not apply — the
transport owns its own settings:

```scala
val myTransport: de.osci.osci12.extinterfaces.TransportI = ...

OsciClient.resource[IO](osciConfig, dvdv, LaufzettelSink.console[IO], myTransport)
OsciMailbox.resource[IO](mailboxConfig, cert, myTransport)
```

### Multi-tenant facade

`OsciFacade` dispatches `request` / `send` by tenant. Build it from a
`ConfigSource` (one `OsciClient` per tenant config), supplying the right
`DvdvClient` per tenant:

```scala
import de.thatscalaguy.zustellix.osci.*

val configs = Map(
  TenantId("flensburg") -> OsciConfig(TenantId("flensburg"), flensburgCert),
  TenantId("kiel")      -> OsciConfig(TenantId("kiel"),      kielCert)
)

val src: ConfigSource[IO] = ConfigSource.static[IO](configs)
// or load from a java.util.Properties file:
val srcFromFile = ConfigSource.file[IO](Paths.get("/etc/zustellix/tenants.properties"))

def dvdvFor(t: TenantId): DvdvClient[IO] = clientsByTenant(t)   // caller owns these

OsciFacade.fromConfigs[IO](src, dvdvFor, LaufzettelSink.console[IO]).use { facade =>
  facade.request(TenantId("kiel"), ags = Ags.unsafe("01002000"), xml = "<xmeld>...</xmeld>")
}
```

Properties-file format for `ConfigSource.file`:

```properties
tenant.flensburg.cert.type        = pkcs12
tenant.flensburg.cert.path        = /secrets/flensburg.p12
tenant.flensburg.cert.password    = s3cret
tenant.flensburg.serviceUri       = http://www.osci.de/xmeld2605/xmeld2605Personensuche.wsdl
tenant.flensburg.subject          = XMeld
tenant.flensburg.connectTimeoutMs = 5000
tenant.flensburg.readTimeoutMs    = 60000
tenant.flensburg.contentSignatures = require
tenant.flensburg.capturePayloads   = true

tenant.kiel.cert.type     = pem
tenant.kiel.cert.path     = /secrets/kiel-cert.pem
tenant.kiel.cert.keyPath  = /secrets/kiel-key.pem
tenant.kiel.cert.password = optional-key-password
```

(`serviceUri` and `subject` are optional and default to the XMeld
Personensuche WSDL and `XMeld`; `connectTimeoutMs` / `readTimeoutMs` are
optional and default to 10 s / 120 s; `contentSignatures` is optional —
`require` or `warn`, default `warn`; `capturePayloads` is optional —
`true` stores the decrypted response XML on `Laufzettel.rawXml`, default
`false`, see [Laufzettel](#laufzettel).)

Multi-tenant mailboxes are simply multiple `OsciMailbox.resource` calls — one
per tenant cert/intermediary.

### Shared certificates by alias

When DVDV and OSCI should use the *same* tenant cert from a `CertManager`,
build both against an alias — the `Laufzettel` is then recorded under that
alias as the tenant id:

```scala
val alias = CertAlias("flensburg")

(for
  dvdv <- DvdvClient.resource[IO](dvdvConfig, certManager, alias)
  osci <- OsciClient.resource[IO](osciConfig, certManager, alias, dvdv, LaufzettelSink.console[IO])
yield osci).use(_.request(Ags.unsafe("01001000"), "<xmeld>...</xmeld>"))

// the mailbox takes the same alias:
OsciMailbox.resource[IO](mailboxConfig, certManager, alias)
```

### Laufzettel

Each `request` / `send` produces a `Laufzettel(messageId, timestamp,
recipientAgs, recipientUri, status, rawXml, warnings, contentSignature)`
handed to a `LaufzettelSink[F]`:

```scala
LaufzettelSink.console[IO]   // prints a one-line summary
LaufzettelSink.noop[IO]      // discards

// or your own — persist it, ship it to a queue, etc.
val toDb: LaufzettelSink[IO] = new LaufzettelSink[IO]:
  def record(tenant: TenantId, l: Laufzettel): IO[Unit] = repo.insert(tenant, l)
```

`status` is a `LaufzettelStatus`: `Feedback(code)` when OSCI reported a
feedback code (`0xxx` / `3xxx` / `9xxx`), `Failed(kind)` when the delivery
failed before one existed. `status.delivered` is `true` for `0xxx` / `3xxx`
(the request was executed), and `status.render` gives the plain string —
the code or the error kind — for logs and DB columns.

**`rawXml` is `None` by default.** The decrypted response XML of a `request`
(e.g. an XMeld Personensuche answer) contains personal data, and whatever
the sink writes to — a DB, a queue, a log shipper — would persist it with
every record. Set `OsciConfig.capturePayloads = true` (properties key
`tenant.<id>.capturePayloads`) to store it on the Laufzettel; do that only
when the sink is meant to hold the payload and your data-protection rules
(retention, access, deletion) cover it. The caller always gets the payload
via `OsciResponse.xml` — capture only affects what the sink sees.

For `send` there is no response payload at store time, so `rawXml` and
`contentSignature` stay `None` regardless of the flag. The content signature
of a `request` response is verified independently of capture —
`contentSignature` is filled even when `rawXml` is not.

Failed deliveries are recorded too, so the sink sees the complete audit
trail, not just successes. For a failure Laufzettel:

- `status` is `Feedback` with the `9xxx` code for an OSCI error response
  (`OsciError.OsciResponse`) and `Failed` with the error kind otherwise
  (e.g. `OsciTransport`, `AgsNotInDvdv`);
- `messageId` is the id issued by `GetMessageId` when the delivery got that
  far, `""` otherwise;
- `rawXml` is `None`, `warnings` is `Nil`;
- `recipientUri` is empty when the resolver itself failed (no route exists).

Recording stays best-effort in both directions: a sink failure never fails
the operation, and a failure record never replaces or swallows the raised
`OsciError`.

### Error model

All failures are an `OsciError` (a `RuntimeException`):

| Error                  | When |
|------------------------|------|
| `UnknownTenant`        | facade dispatched to a tenant with no registered client |
| `InvalidAgs`           | the given string is not a well-formed 8-digit AGS (raised by the `Ags` smart constructors) |
| `AgsNotInDvdv`         | DVDV has no service registered for the AGS + service URI |
| `RecipientCertMissing` | the service description has no cipher certificate for the element in `kind` |
| `ServiceElementMissing`| the `OSCI_ADDRESSEE` / `OSCI_INTERMEDIARY` element is absent |
| `OsciTransport`        | osci-bibliothek transport / IO failure |
| `OsciResponse`         | OSCI returned an error (`9xxx`) feedback code; carries the `messageId` when one was already issued |
| `NoSuchMessage`        | `fetch(messageId)` found no content for that id |
| `UnsignedContent`      | received content carries no content signature and `contentSignatures = Require` |
| `InvalidContentSignature` | the content signature on received content failed verification (raised regardless of policy) |
| `Certificate`          | cert / key decoding failure |
| `Config`               | bad configuration (invalid URI, unknown cert type, …) |

#### OSCI feedback codes

OSCI-Transport 1.2 classifies feedback (Rückmeldungen) by the first digit of
the four-digit code:

| Class  | Meaning | Handling |
|--------|---------|----------|
| `0xxx` | success | normal result |
| `3xxx` | warning — the request **was** executed | tolerated; surfaced as `OsciFeedback(code, text)` in `OsciResponse.warnings`, `OsciReceipt.warnings`, `Laufzettel.warnings`, `PendingPage.warnings` and `OsciMessage.warnings` |
| `9xxx` | error — the request was not executed | raised as `OsciError.OsciResponse` (with the intermediary's `messageId` when one was already issued) |

All feedback rows are inspected, so an error behind a per-language duplicate
row fails too. A common warning is `3802` ("Signatur des Empfängers über die
Annahme- bzw. Bearbeitungsantwort fehlt"): the recipient's gateway answered
without signing its response. The response payload is still delivered — only
the cryptographic proof over the recipient's answer is missing, which the
`warnings` list lets you log or escalate per your own policy. The author's
signature over the content itself is verified separately — see
[Content signature verification](#content-signature-verification).

---

## A note on the signing certificate

The configured client certificate is used **only** to prove possession:

- **DVDV** signs the `client_assertion` JWT with it (RS256). It is *not*
  installed as a TLS client certificate — DVDV2 verifies possession via the
  signed JWT, not mTLS. Server TLS is verified against the JVM's default
  truststore.
- **OSCI** uses it as the Originator's signing key and content decryption key
  (osci-bibliothek `PKCS12Signer` / `PKCS12Decrypter`).

The same PKCS12 therefore serves both, which is why the `CertManager`
alias-keyed constructors wire one credential into both clients.

---

## Build & test

```bash
sbt clean compile
sbt test                 # all modules
sbt dvdv/test            # one module
```

`Test / fork := true` is set per module. Test fixtures
(`src/test/resources/test-cert.p12`, `test-cert.pem`, `test-key.pem`) are
generated with:

```bash
openssl req -x509 -newkey rsa:2048 -keyout test-key.pem -out test-cert.pem \
  -days 3650 -nodes -subj "/CN=zustellix-test"
openssl pkcs12 -export -inkey test-key.pem -in test-cert.pem \
  -out test-cert.p12 -password pass:test
```

---

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
