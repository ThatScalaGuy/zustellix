package de.thatscalaguy.zustellix.utils.cert

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

import java.io.{ByteArrayOutputStream, FileOutputStream}
import java.math.BigInteger
import java.nio.file.{Files, Path}
import java.security.KeyStore
import java.util.{Date, Properties}
import scala.concurrent.duration.*

/** Security-critical coverage for [[DirectoryCertManager]].
 *
 *  IMPORTANT — PRODUCTION DEFECT FOUND (out of this task's edit scope):
 *  `DirectoryCertManager.pollLoop` (DirectoryCertManager.scala:55-60) builds its
 *  background poll with a recursive `def loop = sleep *> scan.handleErrorWith(..) *> loop`.
 *  `*>` (cats `Apply.productR`) is STRICT in its second argument, and for a
 *  generic `F[_]: Async` there is no `Defer`/by-name suspension, so evaluating
 *  `loop` recurses at VALUE-CONSTRUCTION time and throws `StackOverflowError`
 *  the instant `DirectoryCertManager.resource[F](cfg)` is constructed — before
 *  `.use` ever runs. Because `StackOverflowError` is an `Error` (not `NonFatal`),
 *  cats-effect does not trap it; in a forked test JVM it kills the whole run.
 *  The one-line fix is to make the recursion lazy (`>> loop`, `Async[F].defer(loop)`,
 *  or `Monad[F].foreverM`), but DirectoryCertManager.scala is explicitly NOT in
 *  this task's ownership, so it is left untouched and the four resource-level
 *  behaviours below are `.ignore`d (they activate verbatim once the bug is fixed).
 *
 *  What DOES run here: faithful coverage of the per-entry collaborators the
 *  manager delegates to (in-JVM PKCS12 minting -> `CertLoader.loadPkcs12Bytes`,
 *  garbage rejection, and the `InMemoryCertManager` swap/resolve semantics the
 *  manager swaps its map through). These exercise the cert-handling logic on the
 *  path without tripping the broken poll loop.
 */
class DirectoryCertManagerSpec extends CatsEffectSuite {

  private given LoggerFactory[IO] = NoOpFactory[IO]

  java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())

  /** A PKCS12 keystore (one self-signed RSA-1024 key entry under `alias`),
   *  serialised to bytes — the exact shape `CertLoader.loadPkcs12Bytes` reads.
   */
  private def mintP12(alias: String, password: String): Array[Byte] = {
    val kpg = java.security.KeyPairGenerator.getInstance("RSA")
    kpg.initialize(1024)
    val kp    = kpg.generateKeyPair()
    val name  = new javax.security.auth.x500.X500Principal(s"CN=$alias")
    val now   = new Date()
    val later = new Date(now.getTime + 86400000L)
    val builder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
      name, BigInteger.valueOf(System.nanoTime()), now, later, name, kp.getPublic
    )
    val signer = new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate)
    val holder = builder.build(signer)
    val cert   = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(holder)

    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    ks.setKeyEntry(alias, kp.getPrivate, password.toCharArray, Array(cert))
    val out = new ByteArrayOutputStream()
    ks.store(out, password.toCharArray)
    out.toByteArray
  }

  private def writeP12(dir: Path, alias: String, password: String): Unit = {
    val _ = Files.write(dir.resolve(s"$alias.p12"), mintP12(alias, password))
  }

  private def writePasswords(file: Path, entries: (String, String)*): Unit = {
    val props = new Properties()
    entries.foreach { case (a, p) => props.setProperty(a, p) }
    val out = new FileOutputStream(file.toFile)
    try props.store(out, null)
    finally out.close()
  }

  private def tempDir: IO[Path] = IO.blocking(Files.createTempDirectory("dircertmgr"))

  private def cfg(dir: Path, interval: FiniteDuration = 30.seconds) =
    DirectoryCertManagerConfig(dir = dir, interval = interval)

  // ---------------------------------------------------------------------------
  // ACTIVE coverage: the per-entry cert logic the manager relies on.
  // ---------------------------------------------------------------------------

  test("a minted <alias>.p12 round-trips through CertLoader.loadPkcs12Bytes") {
    val bytes = mintP12("alice", "pw-a")
    CertLoader.loadPkcs12Bytes[IO](bytes, "pw-a").map { lc =>
      assert(lc.privateKey != null)
      assert(lc.certificate != null)
      assert(lc.fingerprintSha1Hex.nonEmpty)
    }
  }

  test("a corrupt .p12 byte blob is rejected by CertLoader (so the scan can skip it)") {
    CertLoader.loadPkcs12Bytes[IO]("not a real keystore".getBytes, "pw").attempt.map { r =>
      assert(r.isLeft, "garbage PKCS12 must fail to load")
    }
  }

  test("InMemoryCertManager swap/resolve: the manager's whole-map swap surfaces new aliases and rotated creds") {
    for {
      mgr     <- InMemoryCertManager.make[IO](Map.empty[CertAlias, CertCredential])
      a0      <- mgr.resolve(CertAlias("alice")).attempt
      _       <- mgr.swap(Map(CertAlias("alice") -> CertCredential(mintP12("alice", "pw-a"), "pw-a")))
      a1      <- mgr.resolve(CertAlias("alice"))
      _       <- mgr.swap(Map(
                   CertAlias("alice") -> CertCredential(mintP12("alice", "pw-a2"), "pw-a2"),
                   CertAlias("carol") -> CertCredential(mintP12("carol", "pw-c"),  "pw-c")
                 ))
      a2      <- mgr.resolve(CertAlias("alice"))
      carol   <- mgr.resolve(CertAlias("carol"))
      aliases <- mgr.knownAliases
    } yield {
      assert(a0.isLeft, "unknown alias must not resolve before the first swap")
      assertEquals(a1.password, "pw-a")
      assertEquals(a2.password, "pw-a2")                 // rotated credential swapped in
      assertEquals(carol.password, "pw-c")               // new alias appears
      assert(!a1.pkcs12.sameElements(a2.pkcs12), "rotated keystore bytes must change")
      assertEquals(aliases, Set(CertAlias("alice"), CertAlias("carol")))
    }
  }

  // ---------------------------------------------------------------------------
  // BLOCKED on the production defect above: these are the four resource-level
  // behaviours the brief asks for. They are written verbatim and `.ignore`d so
  // they pass as soon as `DirectoryCertManager.pollLoop` suspends its recursion.
  // Running any of them today crashes the forked JVM with StackOverflowError at
  // `DirectoryCertManager.resource[IO](...)` construction.
  // ---------------------------------------------------------------------------

  test("resolve succeeds immediately after the Resource is ready (first scan completed)".ignore) {
    for {
      dir <- tempDir
      _   <- IO.blocking {
               writeP12(dir, "alice", "pw-a")
               writeP12(dir, "bob", "pw-b")
               writePasswords(dir.resolve("passwords.properties"), "alice" -> "pw-a", "bob" -> "pw-b")
             }
      out <- DirectoryCertManager.resource[IO](cfg(dir)).use { mgr =>
               for {
                 cred    <- mgr.resolve(CertAlias("alice"))
                 aliases <- mgr.knownAliases
               } yield (cred, aliases)
             }
    } yield {
      val (cred, aliases) = out
      assertEquals(cred.password, "pw-a")
      assertEquals(aliases, Set(CertAlias("alice"), CertAlias("bob")))
    }
  }

  test("a corrupt .p12 is skipped while the other aliases still resolve".ignore) {
    for {
      dir <- tempDir
      _   <- IO.blocking {
               writeP12(dir, "good", "pw-g")
               Files.write(dir.resolve("bad.p12"), "not a real keystore".getBytes)
               writePasswords(dir.resolve("passwords.properties"), "good" -> "pw-g", "bad" -> "pw-x")
             }
      out <- DirectoryCertManager.resource[IO](cfg(dir)).use { mgr =>
               for {
                 good    <- mgr.resolve(CertAlias("good")).attempt
                 bad     <- mgr.resolve(CertAlias("bad")).attempt
                 aliases <- mgr.knownAliases
               } yield (good, bad, aliases)
             }
    } yield {
      val (good, bad, aliases) = out
      assert(good.isRight, s"good should resolve, got $good")
      assert(bad.isLeft, "corrupt 'bad' must not resolve")
      assertEquals(aliases, Set(CertAlias("good")))
    }
  }

  test("hot reload: a new alias appears and a rotated credential changes after one interval".ignore) {
    val interval = 150.millis
    for {
      dir <- tempDir
      _   <- IO.blocking {
               writeP12(dir, "alice", "pw-a")
               writePasswords(dir.resolve("passwords.properties"), "alice" -> "pw-a")
             }
      out <- DirectoryCertManager.resource[IO](cfg(dir, interval)).use { mgr =>
               for {
                 before <- mgr.resolve(CertAlias("alice"))
                 _      <- IO.blocking {
                             writeP12(dir, "carol", "pw-c")
                             writeP12(dir, "alice", "pw-a2")
                             writePasswords(
                               dir.resolve("passwords.properties"),
                               "alice" -> "pw-a2",
                               "carol" -> "pw-c"
                             )
                           }
                 _      <- IO.sleep(interval * 4)
                 carol  <- mgr.resolve(CertAlias("carol"))
                 after  <- mgr.resolve(CertAlias("alice"))
               } yield (before, after, carol)
             }
    } yield {
      val (before, after, carol) = out
      assertEquals(carol.password, "pw-c")
      assertEquals(after.password, "pw-a2")
      assert(!before.pkcs12.sameElements(after.pkcs12), "rotated keystore bytes must change")
    }
  }

  test("a transient bad scan does not kill the poll loop; a later good scan still updates".ignore) {
    val interval = 150.millis
    val pwds     = (d: Path) => d.resolve("passwords.properties")
    for {
      dir <- tempDir
      _   <- IO.blocking {
               writeP12(dir, "alice", "pw-a")
               writePasswords(pwds(dir), "alice" -> "pw-a")
             }
      out <- DirectoryCertManager.resource[IO](cfg(dir, interval)).use { mgr =>
               for {
                 _    <- mgr.resolve(CertAlias("alice"))
                 _    <- IO.blocking(Files.delete(pwds(dir)))     // break the next scan
                 _    <- IO.sleep(interval * 3)
                 _    <- IO.blocking {
                           writeP12(dir, "dave", "pw-d")
                           writePasswords(pwds(dir), "alice" -> "pw-a", "dave" -> "pw-d")
                         }
                 _    <- IO.sleep(interval * 4)
                 dave <- mgr.resolve(CertAlias("dave")).attempt   // proves loop survived
               } yield dave
             }
    } yield assert(out.isRight, s"poll loop should have recovered and picked up 'dave', got $out")
  }
}
