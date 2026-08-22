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

import cats.effect.IO
import de.thatscalaguy.zustellix.utils.cert.{CertLoader, LoadedCert}

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyStore
import java.util.Date

/** Mints throw-away self-signed RSA PKCS12 keystores, so rotation tests can
 *  produce a second signing cert distinct from the checked-in `test-cert.p12`.
 */
object TestCerts {

  java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())

  val password = "pw"

  def mintP12(cn: String): IO[Array[Byte]] = IO.blocking {
    val kpg = java.security.KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val kp    = kpg.generateKeyPair()
    val name  = new javax.security.auth.x500.X500Principal(s"CN=$cn")
    val now   = new Date()
    val later = new Date(now.getTime + 86400000L)
    val builder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
      name, BigInteger.valueOf(System.nanoTime()), now, later, name, kp.getPublic
    )
    val signer = new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate)
    val cert   = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(builder.build(signer))

    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    ks.setKeyEntry(cn, kp.getPrivate, password.toCharArray, Array(cert))
    val out = new ByteArrayOutputStream()
    ks.store(out, password.toCharArray)
    out.toByteArray
  }

  def mintLoadedCert(cn: String): IO[LoadedCert] =
    mintP12(cn).flatMap(CertLoader.loadPkcs12Bytes[IO](_, password))
}
