# zustellix

A typed, **tagless-final Scala 3** toolkit for the German public-administration
messaging stack:

- **`dvdv`** — a client for the [**DVDV2 v2 öffentliche API**](https://www.dataport.de/)
  (Deutsches Verwaltungsdiensteverzeichnis), scoped to the
  `extern/standaloneauth/directory` entry path. Look up authorities,
  categories, certificates and service descriptions.
- **`osci-xmeld`** — sends **XMeld** payloads over **OSCI** (Governikus
  osci-bibliothek) to the right Meldebehörde, with the OSCI addressee and
  intermediary routes resolved automatically from DVDV per recipient AGS.
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

| Module       | sbt name     | Depends on   | Purpose |
|--------------|--------------|--------------|---------|
| `utils`      | `utils`      | —            | `CertSource` / `CertLoader` / `CertManager` — shared certificate material |
| `dvdv`       | `dvdv`       | `utils`      | Tagless-final DVDV2 v2 directory client (JWT auth + mules caching) |
| `osci-xmeld` | `osci-xmeld` | `dvdv`, `utils` | OSCI/XMeld sender; DVDV-driven routing; single- and multi-tenant |

Dependency direction:

```
osci-xmeld ──▶ dvdv ──▶ utils
     └───────────────────▶ utils
```

---

## Install

```scala
// build.sbt — pick the module you need (transitive deps are pulled in)
libraryDependencies += "de.thatscalaguy" %% "osci-xmeld" % "0.1.0-SNAPSHOT"
// or just the directory client:
libraryDependencies += "de.thatscalaguy" %% "dvdv"       % "0.1.0-SNAPSHOT"
// or only the cert utilities:
libraryDependencies += "de.thatscalaguy" %% "utils"      % "0.1.0-SNAPSHOT"
```

Built against:

| Library        | Version  |
|----------------|----------|
| Scala          | 3.3.6    |
| cats-effect    | 3.6.1    |
| http4s         | 0.23.30  |
| circe          | 0.14.13  |
| mules          | 0.7.0    |
| jwt-scala      | 10.0.4   |
| bouncycastle   | 1.79     |
| osci-bibliothek| 2.4.8    |

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

`CertLoader.load[F]` turns either into a `LoadedCert` (private key, X509,
SHA-1 fingerprint hex). The DVDV/OSCI clients call this for you — you rarely
touch it directly.

### Many certificates by alias: `CertManager`

For multi-tenant deployments, resolve credentials by a `CertAlias` instead of
hard-coding paths. A `CertManager[F]` returns a `CertCredential` (raw PKCS12
bytes + password) that **both** DVDV and OSCI can consume for the same tenant.

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
    certSource = CertSource.Pkcs12(
      path     = Paths.get("/secrets/my-client.p12"),
      password = sys.env("MY_CLIENT_P12_PASSWORD")
    )
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
// Ember-backed, single tenant from a CertSource (needs Async + Network):
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
  certSource       = CertSource.Pkcs12(p12Path, password),

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
DvdvConfig(baseUri = ???, certSource = ???, cacheConfig = CacheConfig.disabled)
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

## `osci-xmeld` — sending XMeld over OSCI

`OSCIXMeld.send(ags, xml)` takes a recipient AGS and an XMeld XML payload and
returns the synchronous response XML. It:

1. calls `dvdv.findServiceDescription("ags:<ags>", serviceUri)` **once** per
   send (memoized by the DVDV mules cache);
2. pulls **both** the addressee (`OSCI_ADDRESSEE`) and intermediary
   (`OSCI_INTERMEDIARY`) routes out of that single service description —
   neither is configured statically;
3. signs the content with the Originator cert, end-to-end encrypts it for the
   addressee (the intermediary stays blind to personal data), and transmits it
   via osci-bibliothek;
4. records a `Laufzettel` to the configured sink (best-effort — a sink
   failure never fails the send).

> The OSCI bridge requires `CertSource.Pkcs12` — PEM is not supported here.

### Single tenant

```scala
import cats.effect.{IO, IOApp}
import de.thatscalaguy.zustellix.dvdv.*
import de.thatscalaguy.zustellix.oscixmeld.*
import de.thatscalaguy.zustellix.utils.cert.CertSource
import org.http4s.implicits.uri
import java.nio.file.Paths

object SendDemo extends IOApp.Simple:

  val cert = CertSource.Pkcs12(Paths.get("/secrets/flensburg.p12"), sys.env("P12_PW"))

  val dvdvConfig = DvdvConfig(
    baseUri    = uri"https://your-dvdv-betreiber.example",
    certSource = cert
  )
  val xmeldConfig = OSCIXMeldConfig(
    tenantId   = TenantId("flensburg"),
    certSource = cert            // same PKCS12: DVDV signs the JWT, OSCI signs/decrypts
  )

  def run: IO[Unit] =
    (for
      dvdv  <- DvdvClient.resource[IO](dvdvConfig)
      xmeld <- OSCIXMeld.resource[IO](xmeldConfig, dvdv, LaufzettelSink.console[IO])
    yield xmeld).use { xmeld =>
      xmeld.send(ags = "01001000", xml = "<xmeld>...</xmeld>").flatMap(IO.println)
    }
```

The given `DvdvClient` is owned by the caller — the `OSCIXMeld` resource does
not close it.

### Multi-tenant facade

`OSCIXMeldFacade.send(tenant, ags, xml)` dispatches by tenant. Build it from a
`ConfigSource` (one `OSCIXMeld` per tenant config), supplying the right
`DvdvClient` per tenant:

```scala
import de.thatscalaguy.zustellix.oscixmeld.*

val configs = Map(
  TenantId("flensburg") -> OSCIXMeldConfig(TenantId("flensburg"), flensburgCert),
  TenantId("kiel")      -> OSCIXMeldConfig(TenantId("kiel"),      kielCert)
)

val src: ConfigSource[IO] = ConfigSource.static[IO](configs)
// or load from a java.util.Properties file:
val srcFromFile = ConfigSource.file[IO](Paths.get("/etc/zustellix/tenants.properties"))

def dvdvFor(t: TenantId): DvdvClient[IO] = clientsByTenant(t)   // caller owns these

OSCIXMeldFacade.fromConfigs[IO](src, dvdvFor, LaufzettelSink.console[IO]).use { facade =>
  facade.send(TenantId("kiel"), ags = "01002000", xml = "<xmeld>...</xmeld>")
}
```

Properties-file format for `ConfigSource.file`:

```properties
tenant.flensburg.cert.type             = pkcs12
tenant.flensburg.cert.path             = /secrets/flensburg.p12
tenant.flensburg.cert.password         = s3cret
tenant.flensburg.serviceUri            = http://www.osci.de/xmeld2605/xmeld2605Personensuche.wsdl
tenant.flensburg.category              = Meldebehörde
tenant.flensburg.requestTimeoutSeconds = 60

tenant.kiel.cert.type     = pem
tenant.kiel.cert.path     = /secrets/kiel-cert.pem
tenant.kiel.cert.keyPath  = /secrets/kiel-key.pem
tenant.kiel.cert.password = optional-key-password
```

(`serviceUri`, `category`, `requestTimeoutSeconds` are optional and default to
the XMeld Personensuche WSDL, `Meldebehörde`, and 60s.)

### Shared certificates by alias

When DVDV and OSCI should use the *same* tenant cert from a `CertManager`,
build both against an alias — the `Laufzettel` is then recorded under that
alias as the tenant id:

```scala
val alias = CertAlias("flensburg")

(for
  dvdv  <- DvdvClient.resource[IO](dvdvConfig, certManager, alias)
  xmeld <- OSCIXMeld.resource[IO](xmeldConfig, certManager, alias, dvdv, LaufzettelSink.console[IO])
yield xmeld).use(_.send("01001000", "<xmeld>...</xmeld>"))
```

### Laufzettel

Each send produces a `Laufzettel(messageId, timestamp, recipientAgs,
recipientUri, status, rawXml)` handed to a `LaufzettelSink[F]`:

```scala
LaufzettelSink.console[IO]   // prints a one-line summary
LaufzettelSink.noop[IO]      // discards

// or your own — persist it, ship it to a queue, etc.
val toDb: LaufzettelSink[IO] = new LaufzettelSink[IO]:
  def record(tenant: TenantId, l: Laufzettel): IO[Unit] = repo.insert(tenant, l)
```

### Error model

All failures are an `OSCIXMeldError` (a `RuntimeException`):

| Error                  | When |
|------------------------|------|
| `UnknownTenant`        | facade dispatched to a tenant with no registered client |
| `AgsNotInDvdv`         | DVDV has no service registered for the AGS + service URI |
| `RecipientCertMissing` | the service description has no cipher certificate |
| `ServiceElementMissing`| the `OSCI_ADDRESSEE` / `OSCI_INTERMEDIARY` element is absent |
| `OsciTransport`        | osci-bibliothek transport / IO failure |
| `OsciResponse`         | OSCI returned a non-`0` feedback code |
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
