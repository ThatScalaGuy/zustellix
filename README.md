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
SHA-1 fingerprint hex). The DVDV/OSCI clients call this for you — you rarely
touch it directly.

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
| `OsciClient.request(ags, xml)` | `MediateDelivery` | synchronous request/response (e.g. XMeld Personensuche) |
| `OsciClient.send(ags, xml)`    | `StoreDelivery`   | asynchronous: stored in the recipient's mailbox, returns an `OsciReceipt` |
| `OsciMailbox.pending` / `fetch`| `FetchProcessCard` / `FetchDelivery` | asynchronous receive + ack from your own mailbox (e.g. XFamilie) |

Every outbound operation:

1. calls `dvdv.findServiceDescription("ags:<ags>", serviceUri)` **once** per
   call (memoized by the DVDV mules cache);
2. pulls **both** the addressee (`OSCI_ADDRESSEE`) and intermediary
   (`OSCI_INTERMEDIARY`) routes out of that single service description —
   neither is configured statically;
3. signs the content with the Originator cert, end-to-end encrypts it for the
   addressee (the intermediary stays blind to personal data), and transmits it
   via osci-bibliothek;
4. records a `Laufzettel` to the configured sink (best-effort — a sink
   failure never fails the operation).

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
      osci.request(ags = "01001000", xml = "<xmeld>...</xmeld>").flatMap(IO.println)
    }
```

The given `DvdvClient` is owned by the caller — the `OsciClient` resource does
not close it.

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
  osci.send(ags = "01001000", xml = "<xfamilie>...</xfamilie>").flatMap { receipt =>
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
    waiting <- mailbox.pending                    // un-fetched deliveries, process cards only
    _       <- waiting.traverse_ { p =>
                 mailbox.fetch(p.messageId).flatMap { msg =>
                   IO.println(s"${msg.messageId} [${msg.subject}]: ${msg.xml}")
                 }
               }
  yield ()
}
```

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
  facade.request(TenantId("kiel"), ags = "01002000", xml = "<xmeld>...</xmeld>")
}
```

Properties-file format for `ConfigSource.file`:

```properties
tenant.flensburg.cert.type     = pkcs12
tenant.flensburg.cert.path     = /secrets/flensburg.p12
tenant.flensburg.cert.password = s3cret
tenant.flensburg.serviceUri    = http://www.osci.de/xmeld2605/xmeld2605Personensuche.wsdl
tenant.flensburg.subject       = XMeld

tenant.kiel.cert.type     = pem
tenant.kiel.cert.path     = /secrets/kiel-cert.pem
tenant.kiel.cert.keyPath  = /secrets/kiel-key.pem
tenant.kiel.cert.password = optional-key-password
```

(`serviceUri` and `subject` are optional and default to the XMeld
Personensuche WSDL and `XMeld`.)

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
yield osci).use(_.request("01001000", "<xmeld>...</xmeld>"))

// the mailbox takes the same alias:
OsciMailbox.resource[IO](mailboxConfig, certManager, alias)
```

### Laufzettel

Each `request` / `send` produces a `Laufzettel(messageId, timestamp,
recipientAgs, recipientUri, status, rawXml)` handed to a `LaufzettelSink[F]`
(for `send`, `rawXml` is empty — there is no response payload at store time):

```scala
LaufzettelSink.console[IO]   // prints a one-line summary
LaufzettelSink.noop[IO]      // discards

// or your own — persist it, ship it to a queue, etc.
val toDb: LaufzettelSink[IO] = new LaufzettelSink[IO]:
  def record(tenant: TenantId, l: Laufzettel): IO[Unit] = repo.insert(tenant, l)
```

### Error model

All failures are an `OsciError` (a `RuntimeException`):

| Error                  | When |
|------------------------|------|
| `UnknownTenant`        | facade dispatched to a tenant with no registered client |
| `AgsNotInDvdv`         | DVDV has no service registered for the AGS + service URI |
| `RecipientCertMissing` | the service description has no cipher certificate |
| `ServiceElementMissing`| the `OSCI_ADDRESSEE` / `OSCI_INTERMEDIARY` element is absent |
| `OsciTransport`        | osci-bibliothek transport / IO failure |
| `OsciResponse`         | OSCI returned a non-`0` feedback code |
| `NoSuchMessage`        | `fetch(messageId)` found no content for that id |
| `Certificate`          | cert / key decoding failure |
| `Config`               | bad configuration (invalid URI, unknown cert type, …) |

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
