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

/** Identifies a certificate within a [[CertManager]]. The alias is also the
 *  keystore file name (`<alias>.p12`) and the key under which its password is
 *  looked up in the password-properties file.
 */
opaque type CertAlias = String

object CertAlias {
  def apply(s: String): CertAlias = s

  extension (a: CertAlias) {
    def value: String = a
  }
}
