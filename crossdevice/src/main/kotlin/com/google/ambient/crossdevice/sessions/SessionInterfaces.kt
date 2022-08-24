/*
 * Copyright 2022 Google LLC
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

package com.google.ambient.crossdevice.sessions

import android.os.Build
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean

/** Interface for Session expected to be used by the `PrimarySession` for share. */
@RequiresApi(Build.VERSION_CODES.O)
internal interface PrimarySessionInterface {
  val sessionId: SessionId
  var sessionDiscoveryEnabled: AtomicBoolean
  var cancelled: AtomicBoolean

  fun enableSessionDiscovery(handleId: String) {
    if (cancelled.get()) throw SessionException(SessionException.SESSION_ACTION_CANCELLED)
    // TODO: When enabled, allow other devices to find and join this session.
    sessionDiscoveryEnabled.set(true)
  }

  fun disableSessionDiscovery(handleId: String) {
    if (cancelled.get()) throw SessionException(SessionException.SESSION_ACTION_CANCELLED)
    sessionDiscoveryEnabled.set(false)
  }

  fun isSessionDiscoveryEnabled(handleId: String): Boolean {
    if (cancelled.get()) throw SessionException(SessionException.SESSION_ACTION_CANCELLED)
    return sessionDiscoveryEnabled.get()
  }

  fun getSecondaryApplicationConnections(
    handleId: String
  ): Map<SessionParticipant, SessionRemoteConnection>
  suspend fun removeParticipantFromSession(participant: SessionParticipant, handleId: String)
  suspend fun cancelShareForHandle(reason: String, handleId: String)
}

/** Interface for Session expected to be used by the `SecondarySession` for share. */
@RequiresApi(Build.VERSION_CODES.O)
internal interface SecondarySessionInterface {
  val sessionId: SessionId
  var cancelled: AtomicBoolean

  fun getDefaultApplicationRemoteConnectionForSecondary(handleId: String): SessionRemoteConnection {
    if (cancelled.get()) throw SessionException(SessionException.SESSION_ACTION_CANCELLED)
    return getDefaultApplicationRemoteConnection(handleId)
  }

  fun getDefaultApplicationRemoteConnection(handleId: String?): SessionRemoteConnection
  suspend fun cancelShareForHandle(reason: String, handleId: String)
}

/** Interface for Session expected to be used by the `TransferrableSessionHandle`. */
@RequiresApi(Build.VERSION_CODES.O)
internal interface TransferrableSessionInterface {
  val sessionId: SessionId
  var cancelled: AtomicBoolean

  fun getDefaultApplicationRemoteConnectionForTransferrable(
    handleId: String
  ): SessionRemoteConnection {
    if (cancelled.get()) throw SessionException(SessionException.SESSION_ACTION_CANCELLED)
    return getDefaultApplicationRemoteConnection(handleId)
  }

  fun getDefaultApplicationRemoteConnection(handleId: String?): SessionRemoteConnection
  suspend fun completeTransfer(handleId: String): SessionId
  suspend fun cancelTransferForHandle(reason: String, handleId: String)
}
