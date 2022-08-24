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

package com.google.ambient.crossdevice

import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.ambient.crossdevice.connections.ConnectionReceiver
import com.google.ambient.crossdevice.connections.ConnectionType
import com.google.ambient.crossdevice.connections.RemoteConnection
import com.google.ambient.crossdevice.connections.RemoteConnectionImpl
import com.google.android.gms.dtdi.analytics.CorrelationData
import com.google.android.gms.dtdi.core.DtdiClient
import com.google.common.util.concurrent.ListenableFuture
import com.google.errorprone.annotations.RestrictedApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** @hide */
@RequiresApi(Build.VERSION_CODES.O)
class ParticipantImpl
constructor(
  override val displayName: CharSequence,
  val token: IBinder,
  /** CorrelationData used for logging analytics. This data is only used internally. */
  val correlationData: CorrelationData,
  private val client: DtdiClient
) : Participant {
  private val futureScope = MainScope()
  private val openedConnections = mutableListOf<RemoteConnection>()
  private val acceptedConnections = mutableListOf<RemoteConnection>()
  private var closed = false

  override val connections: List<RemoteConnection>
    get() = openedConnections + acceptedConnections

  override suspend fun openConnection(
    channelName: String,
    namespace: String,
  ): Result<RemoteConnection> =
    try {
      check(!closed) { "Cannot open connection when participant is closed." }
      val connection =
        RemoteConnectionImpl(client, this, channelName, namespace, ConnectionType.OPENED)
      connection.addPayloadReceiver()
      connection.registerReceiver(
        object : ConnectionReceiver {
          override fun onMessageReceived(remoteConnection: RemoteConnection, payload: ByteArray) {}

          override fun onConnectionClosed(
            remoteConnection: RemoteConnection,
            error: Throwable?,
            reason: String?,
          ) {
            openedConnections.remove(remoteConnection)
          }
        }
      )
      openedConnections.add(connection)
      Result.success(connection)
    } catch (e: IllegalArgumentException) {
      Result.failure(e)
    }

  /** Java-compatible version of [openConnection]. */
  override fun openConnectionFuture(
    channelName: String,
  ): ListenableFuture<RemoteConnection> =
    futureScope.future { openConnection(channelName).getOrThrow() }

  override suspend fun acceptConnection(
    channelName: String,
    namespace: String
  ): Result<RemoteConnection> =
    try {
      check(!closed) { "Cannot accept connection when participant is closed." }
      val connection =
        RemoteConnectionImpl(client, this, channelName, namespace, ConnectionType.ACCEPTED)
      connection.addPayloadReceiver()
      connection.registerReceiver(
        object : ConnectionReceiver {
          override fun onMessageReceived(remoteConnection: RemoteConnection, payload: ByteArray) {}

          override fun onConnectionClosed(
            remoteConnection: RemoteConnection,
            error: Throwable?,
            reason: String?,
          ) {
            acceptedConnections.remove(remoteConnection)
          }
        }
      )
      acceptedConnections.add(connection)
      Result.success(connection)
    } catch (e: IllegalArgumentException) {
      Result.failure(e)
    }

  override fun acceptConnectionFuture(channelName: String): ListenableFuture<RemoteConnection> =
    futureScope.future { acceptConnection(channelName).getOrThrow() }

  @SuppressWarnings("GmsCoreFirstPartyApiChecker")
  override suspend fun close() {
    if (closed) {
      Log.w(TAG, "Participant already closed. Ignoring close()")
      return
    }
    coroutineScope {
      closed = true
      openedConnections.forEach { launch { it.close() } }
      acceptedConnections.forEach { launch { it.close() } }
    }
    client.closeDevice(token).await()
  }

  protected fun finalize() {
    if (!closed) {
      futureScope.launch { close() }
    }
  }

  override fun closeFuture(): ListenableFuture<Void?> =
    futureScope.future {
      close()
      null
    }

  override fun toString(): String {
    return "Participant(displayName=$displayName)"
  }

  companion object {
    private const val TAG = "D2diParticipant"
  }
}
