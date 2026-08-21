package de.thatscalaguy.zustellix.utils.cert

import cats.effect.Sync

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.file.{Files, Path}
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.{KeyStore, MessageDigest, PrivateKey, Security}
import scala.jdk.CollectionConverters.*

/** A private key plus its certificate as loaded from a [[CertSource]].
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
    chain: List[X509Certificate]
)

object CertLoader {

  def load[F[_]: Sync](src: CertSource): F[LoadedCert] = src match {
    case CertSource.Pkcs12(path, password)       => loadPkcs12[F](path, password)
    case CertSource.Pkcs12Bytes(bytes, password) => loadPkcs12Bytes[F](bytes, password)
    case CertSource.Pem(c, k, p)                 => loadPem[F](c, k, p)
    case CertSource.PemBytes(c, k, p)            => loadPemBytes[F](c, k, p)
  }

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

    val alias = ks.aliases().asScala.find(ks.isKeyEntry).getOrElse(
      throw new IllegalArgumentException("No key entry found in PKCS12 keystore")
    )

    val pk   = ks.getKey(alias, password.toCharArray).asInstanceOf[PrivateKey]
    val cert = ks.getCertificate(alias).asInstanceOf[X509Certificate]
    val chain = Option(ks.getCertificateChain(alias))
      .map(_.toList.map(_.asInstanceOf[X509Certificate]))
      .filter(_.nonEmpty)
      .getOrElse(List(cert))
    LoadedCert(pk, cert, sha1Hex(cert.getEncoded), chain)
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
    registerBouncyCastle()

    val cf     = CertificateFactory.getInstance("X.509")
    val certIn = new ByteArrayInputStream(certBytes)
    val certs =
      try cf.generateCertificates(certIn).asScala.toList.map(_.asInstanceOf[X509Certificate])
      finally certIn.close()
    val cert = certs.headOption.getOrElse(
      throw new IllegalArgumentException("No certificate found in PEM input")
    )

    val privateKey = readPemPrivateKey(keyBytes, keyPassword, keyLabel)

    LoadedCert(privateKey, cert, sha1Hex(cert.getEncoded), certs)
  }

  private def registerBouncyCastle(): Unit =
    if (Security.getProvider("BC") == null) {
      val _ = Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
    }

  private def readPemPrivateKey(keyBytes: Array[Byte], keyPassword: Option[String], keyLabel: String): PrivateKey = {
    import org.bouncycastle.openssl.{PEMEncryptedKeyPair, PEMKeyPair, PEMParser}
    import org.bouncycastle.openssl.jcajce.{JcaPEMKeyConverter, JcePEMDecryptorProviderBuilder, JceOpenSSLPKCS8DecryptorProviderBuilder}
    import org.bouncycastle.pkcs.{PKCS8EncryptedPrivateKeyInfo}
    import org.bouncycastle.asn1.pkcs.PrivateKeyInfo

    val reader = new java.io.InputStreamReader(new ByteArrayInputStream(keyBytes))
    val parser = new PEMParser(reader)
    try {
      val obj      = parser.readObject()
      val converter = new JcaPEMKeyConverter().setProvider("BC")
      obj match {
        case kp: PEMKeyPair =>
          converter.getKeyPair(kp).getPrivate
        case enc: PEMEncryptedKeyPair =>
          val pwd = keyPassword.getOrElse(
            throw new IllegalArgumentException(s"PEM private key at $keyLabel is encrypted; keyPassword required")
          )
          val decryptor = new JcePEMDecryptorProviderBuilder().build(pwd.toCharArray)
          converter.getKeyPair(enc.decryptKeyPair(decryptor)).getPrivate
        case enc: PKCS8EncryptedPrivateKeyInfo =>
          val pwd = keyPassword.getOrElse(
            throw new IllegalArgumentException(s"PEM PKCS#8 private key at $keyLabel is encrypted; keyPassword required")
          )
          val decryptor = new JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider("BC").build(pwd.toCharArray)
          converter.getPrivateKey(enc.decryptPrivateKeyInfo(decryptor))
        case info: PrivateKeyInfo =>
          converter.getPrivateKey(info)
        case other =>
          throw new IllegalArgumentException(
            s"Unsupported PEM object at $keyLabel: ${Option(other).map(_.getClass.getName).getOrElse("null")}"
          )
      }
    } finally {
      parser.close()
      reader.close()
    }
  }

  private def sha1Hex(bytes: Array[Byte]): String = {
    val md = MessageDigest.getInstance("SHA-1")
    md.digest(bytes).map(b => f"$b%02x").mkString
  }
}
