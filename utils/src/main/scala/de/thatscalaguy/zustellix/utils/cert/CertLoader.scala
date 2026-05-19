package de.thatscalaguy.zustellix.utils.cert

import cats.effect.Sync

import java.io.{ByteArrayInputStream, FileInputStream, InputStream}
import java.nio.file.{Files, Path}
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.{KeyStore, MessageDigest, PrivateKey, Security}
import scala.jdk.CollectionConverters.*

final case class LoadedCert(
    privateKey: PrivateKey,
    certificate: X509Certificate,
    fingerprintSha1Hex: String
)

object CertLoader {

  def load[F[_]: Sync](src: CertSource): F[LoadedCert] = src match {
    case CertSource.Pkcs12(path, password) => loadPkcs12[F](path, password)
    case CertSource.Pem(c, k, p)           => loadPem[F](c, k, p)
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
    LoadedCert(pk, cert, sha1Hex(cert.getEncoded))
  }

  private def loadPem[F[_]: Sync](certPath: Path, keyPath: Path, keyPassword: Option[String]): F[LoadedCert] =
    Sync[F].blocking {
      registerBouncyCastle()

      val cf = CertificateFactory.getInstance("X.509")
      val certIn = new FileInputStream(certPath.toFile)
      val cert =
        try cf.generateCertificate(certIn).asInstanceOf[X509Certificate]
        finally certIn.close()

      val privateKey = readPemPrivateKey(keyPath, keyPassword)

      LoadedCert(privateKey, cert, sha1Hex(cert.getEncoded))
    }

  private def registerBouncyCastle(): Unit =
    if (Security.getProvider("BC") == null) {
      val _ = Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
    }

  private def readPemPrivateKey(keyPath: Path, keyPassword: Option[String]): PrivateKey = {
    import org.bouncycastle.openssl.{PEMEncryptedKeyPair, PEMKeyPair, PEMParser}
    import org.bouncycastle.openssl.jcajce.{JcaPEMKeyConverter, JcePEMDecryptorProviderBuilder, JceOpenSSLPKCS8DecryptorProviderBuilder}
    import org.bouncycastle.pkcs.{PKCS8EncryptedPrivateKeyInfo}
    import org.bouncycastle.asn1.pkcs.PrivateKeyInfo

    val reader = new java.io.InputStreamReader(Files.newInputStream(keyPath))
    val parser = new PEMParser(reader)
    try {
      val obj      = parser.readObject()
      val converter = new JcaPEMKeyConverter().setProvider("BC")
      obj match {
        case kp: PEMKeyPair =>
          converter.getKeyPair(kp).getPrivate
        case enc: PEMEncryptedKeyPair =>
          val pwd = keyPassword.getOrElse(
            throw new IllegalArgumentException(s"PEM private key at $keyPath is encrypted; keyPassword required")
          )
          val decryptor = new JcePEMDecryptorProviderBuilder().build(pwd.toCharArray)
          converter.getKeyPair(enc.decryptKeyPair(decryptor)).getPrivate
        case enc: PKCS8EncryptedPrivateKeyInfo =>
          val pwd = keyPassword.getOrElse(
            throw new IllegalArgumentException(s"PEM PKCS#8 private key at $keyPath is encrypted; keyPassword required")
          )
          val decryptor = new JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider("BC").build(pwd.toCharArray)
          converter.getPrivateKey(enc.decryptPrivateKeyInfo(decryptor))
        case info: PrivateKeyInfo =>
          converter.getPrivateKey(info)
        case other =>
          throw new IllegalArgumentException(
            s"Unsupported PEM object at $keyPath: ${Option(other).map(_.getClass.getName).getOrElse("null")}"
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
