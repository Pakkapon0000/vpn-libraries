/*
 * Copyright (C) 2022 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.libraries.privacy.ppn.krypton;

// LINT.IfChange
/** Interface for Krypton to use when fetching an oauth token and for device verification. */
public interface OAuthTokenProvider {
  /**
   * Returns a fresh oauth token. This method is called by C++ code, so it returns an empty string
   * on failure.
   *
   * @return the token as a String, or an empty String if there was a failure.
   */
  String getOAuthToken();

  /**
   * Returns a serialized version of AndroidAttestationData, or null on error.
   *
   * <p>AndroidAttestationData contains the integrity token generated by Play Integrity API and also
   * the Hardware Attested IDs, if feasible, to validate the device type.
   */
  byte[] getAttestationData(String nonce);

  /** Clears the given OAuth token from any cache that may have provided it. */
  void clearOAuthToken(String token);
}
// LINT.ThenChange(//depot/google3/privacy/net/krypton/jni/jni_cache.cc)
