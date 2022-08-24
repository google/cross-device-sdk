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
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.ambient.crossdevice.connections.ConnectionReceiver
import com.google.ambient.crossdevice.connections.RemoteConnection
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.guava.future

private const val TAG = "SessionRemoteConnection"

/** @hide */
@RequiresApi(Build.VERSION_CODES.O)
internal class SessionRemoteConnectionImpl(
  private val remoteConnection: RemoteConnection,
  private val sessionParticipantImpl: SessionParticipantImpl,
  override val participant: SessionParticipant = sessionParticipantImpl
) : SessionRemoteConnection {
  private val scope = MainScope()
  private val connectionReceiversBySessionConnectionReceiver =
    mutableMapOf<SessionConnectionReceiver, ConnectionReceiverImpl>()
  override suspend fun send(bytes: ByteArray): Result<Unit> = remoteConnection.send(bytes)

  override fun sendFuture(bytes: ByteArray): ListenableFuture<Void?> =
    scope.future {
      send(bytes)
      null
    }

  override fun registerReceiver(receiver: SessionConnectionReceiver) {
    remoteConnection.registerReceiver(
      ConnectionReceiverImpl(receiver, sessionParticipantImpl).also {
        connectionReceiversBySessionConnectionReceiver[receiver] = it
      }
    )
  }

  override fun unregisterReceiver(receiver: SessionConnectionReceiver) {
    connectionReceiversBySessionConnectionReceiver[receiver]?.let {
      connectionReceiversBySessionConnectionReceiver.remove(receiver)?.let {
        remoteConnection.unregisterReceiver(it)
      }

      return
    }
    Log.w(TAG, "Cannot unregister receiver. It hasn't been registered.")
  }
}

@RequiresApi(Build.VERSION_CODES.O)
internal class ConnectionReceiverImpl(
  private val sessionConnectionReceiver: SessionConnectionReceiver,
  private val sessionParticipant: SessionParticipantImpl
) : ConnectionReceiver {
  override fun onMessageReceived(remoteConnection: RemoteConnection, payload: ByteArray) {
    if (sessionParticipant.participant != remoteConnection.participant) {
      Log.e(
        TAG,
        "Participant mis-match ${sessionParticipant.participant}, ${remoteConnection.participant}"
      )
      // We received a message from a participant other than the one this ConnectionReceiver is
      // set up to handle.
      throw SessionException(SessionException.INTERNAL_ERROR)
    }
    sessionConnectionReceiver.onMessageReceived(sessionParticipant, payload)
  }

  override fun onConnectionClosed(
    remoteConnection: RemoteConnection,
    error: Throwable?,
    reason: String?,
  ) {
    // TODO: Notify SessionsManager of connections closing in order to do cleanup once
    // we convert Sessions internal channels to be callback-based.
  }
}
