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

package de.thatscalaguy.zustellix.utils.cert

import cats.effect.Sync

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.{KeyStore, MessageDigest, PrivateKey}
import scala.jdk.CollectionConverters.*

/** A private key plus its certificate as loaded from a [[CertSource]].
 *
 *  `fingerprintSha1Hex` and `fingerprintSha256Hex` are lowercase hex digests
 *  over `certificate.getEncoded`.
 *
 *  `chain` follows JSSE `getCertificateChain` semantics: the leaf certificate
 *  is the head (`chain.head == certificate`), followed by any intermediate/CA
 *  certificates the source provides. Single-certificate sources yield
 *  `chain == List(certificate)`.
 */
final case class LoadedCert(
    privateKey: PrivateKey,
    certificate: X509Certificate,
    fingerprintSha1Hex: String,
    fingerprintSha256Hex: String,
    chain: List[X509Certificate]
)

object CertLoader {

  def load[F[_]: Sync](src: CertSource): F[LoadedCert] = src match {
    case CertSource.Pkcs12(path, password)       => loadPkcs12[F](path, password)
    case CertSource.Pkcs12Bytes(bytes, password) => loadPkcs12Bytes[F](bytes, password)
    case CertSource.Pem(c, k, p)                 => loadPem[F](c, k, p)
    case CertSource.PemBytes(c, k, p)            => loadPemBytes[F](c, k, p)
  }

  /** Loads a PKCS12 keystore that contains exactly one private-key entry with a
   *  certificate; fails with [[IllegalArgumentException]] when none or several
   *  exist. `password` opens both the store and the key entry — the layout
   *  produced by `openssl pkcs12 -export` and Java keytool; keystores whose
   *  entry password differs from the store password are not supported.
   */
  def loadPkcs12Bytes[F[_]: Sync](bytes: Array[Byte], password: String): F[LoadedCert] =
    Sync[F].blocking(fromKeyStoreStream(new ByteArrayInputStream(bytes), password))

  private def loadPkcs12[F[_]: Sync](path: Path, password: String): F[LoadedCert] =
    Sync[F].blocking {
      val in = Files.newInputStream(path)
      try fromKeyStoreStream(in, password)
      finally in.close()
    }

  private def fromKeyStoreStream(in: InputStream, password: String): LoadedCert = {
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(in, password.toCharArray)

    // PrivateKeyEntry (never a SecretKeyEntry or trusted cert) with a stable
    // choice: enumeration order is unspecified, so require exactly one match.
    val keyAliases = ks
      .aliases()
      .asScala
      .toList
      .filter(a => ks.entryInstanceOf(a, classOf[KeyStore.PrivateKeyEntry]))
      .sorted
    val alias = keyAliases match {
      case Nil =>
        throw new IllegalArgumentException("No private key entry with a certificate found in PKCS12 keystore")
      case single :: Nil => single
      case many =>
        throw new IllegalArgumentException(
          s"Multiple private key entries found in PKCS12 keystore (${many.mkString(", ")}); expected exactly one"
        )
    }

    val pk = ks.getKey(alias, password.toCharArray).asInstanceOf[PrivateKey]
    val cert = Option(ks.getCertificate(alias))
      .getOrElse(throw new IllegalArgumentException(s"PKCS12 key entry '$alias' has no certificate"))
      .asInstanceOf[X509Certificate]
    val chain = Option(ks.getCertificateChain(alias))
      .map(_.toList.map(_.asInstanceOf[X509Certificate]))
      .filter(_.nonEmpty)
      .getOrElse(List(cert))
    val encoded = cert.getEncoded
    LoadedCert(pk, cert, fingerprintHex("SHA-1", encoded), fingerprintHex("SHA-256", encoded), chain)
  }

  private def loadPem[F[_]: Sync](certPath: Path, keyPath: Path, keyPassword: Option[String]): F[LoadedCert] =
    Sync[F].blocking {
      fromPemBytes(Files.readAllBytes(certPath), Files.readAllBytes(keyPath), keyPassword, keyPath.toString)
    }

  private def loadPemBytes[F[_]: Sync](cert: Array[Byte], key: Array[Byte], keyPassword: Option[String]): F[LoadedCert] =
    Sync[F].blocking(fromPemBytes(cert, key, keyPassword, "<in-memory PEM key>"))

  private def fromPemBytes(
      certBytes: Array[Byte],
      keyBytes: Array[Byte],
      keyPassword: Option[String],
      keyLabel: String
  ): LoadedCert = {
    val cf     = CertificateFactory.getInstance("X.509")
    val certIn = new ByteArrayInputStream(certBytes)
    val certs =
      try cf.generateCertificates(certIn).asScala.toList.map(_.asInstanceOf[X509Certificate])
      finally certIn.close()
    val cert = certs.headOption.getOrElse(
      throw new IllegalArgumentException("No certificate found in PEM input")
    )

    val privateKey = readPemPrivateKey(keyBytes, keyPassword, keyLabel)

    val encoded = cert.getEncoded
    LoadedCert(privateKey, cert, fingerprintHex("SHA-1", encoded), fingerprintHex("SHA-256", encoded), certs)
  }

  /** Local BouncyCastle instance passed to the PEM converters directly — no
   *  JVM-global `Security.addProvider` registration.
   */
  private lazy val bcProvider = new org.bouncycastle.jce.provider.BouncyCastleProvider()

  private def readPemPrivateKey(keyBytes: Array[Byte], keyPassword: Option[String], keyLabel: String): PrivateKey = {
    import org.bouncycastle.openssl.{PEMEncryptedKeyPair, PEMKeyPair, PEMParser}
    import org.bouncycastle.openssl.jcajce.{JcaPEMKeyConverter, JcePEMDecryptorProviderBuilder, JceOpenSSLPKCS8DecryptorProviderBuilder}
    import org.bouncycastle.pkcs.{PKCS8EncryptedPrivateKeyInfo}
    import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
    import org.bouncycastle.asn1.ASN1ObjectIdentifier
    import org.bouncycastle.asn1.x9.X9ECParameters
    import org.bouncycastle.cert.X509CertificateHolder

    val reader = new java.io.InputStreamReader(new ByteArrayInputStream(keyBytes), StandardCharsets.UTF_8)
    val parser = new PEMParser(reader)
    try {
      val converter = new JcaPEMKeyConverter().setProvider(bcProvider)
      // Non-key blocks before the key are skipped: `openssl ecparam -genkey`
      // emits an EC PARAMETERS block ahead of the key, and combined PEM bundles
      // may place certificates first.
      @annotation.tailrec
      def nextKey(): PrivateKey = parser.readObject() match {
        case null =>
          throw new IllegalArgumentException(s"No private key found in PEM input at $keyLabel")
        case kp: PEMKeyPair =>
          converter.getKeyPair(kp).getPrivate
        case enc: PEMEncryptedKeyPair =>
          val pwd = keyPassword.getOrElse(
            throw new IllegalArgumentException(s"PEM private key at $keyLabel is encrypted; keyPassword required")
          )
          val decryptor = new JcePEMDecryptorProviderBuilder().setProvider(bcProvider).build(pwd.toCharArray)
          converter.getKeyPair(enc.decryptKeyPair(decryptor)).getPrivate
        case enc: PKCS8EncryptedPrivateKeyInfo =>
          val pwd = keyPassword.getOrElse(
            throw new IllegalArgumentException(s"PEM PKCS#8 private key at $keyLabel is encrypted; keyPassword required")
          )
          val decryptor = new JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider(bcProvider).build(pwd.toCharArray)
          converter.getPrivateKey(enc.decryptPrivateKeyInfo(decryptor))
        case info: PrivateKeyInfo =>
          converter.getPrivateKey(info)
        case _: ASN1ObjectIdentifier | _: X9ECParameters | _: X509CertificateHolder =>
          nextKey()
        case other =>
          throw new IllegalArgumentException(
            s"Unsupported PEM object at $keyLabel: ${other.getClass.getName}"
          )
      }
      nextKey()
    } finally {
      parser.close()
      reader.close()
    }
  }

  private def fingerprintHex(algorithm: String, bytes: Array[Byte]): String =
    MessageDigest.getInstance(algorithm).digest(bytes).map(b => f"$b%02x").mkString
}
