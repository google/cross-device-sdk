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

package com.google.ambient.crossdevice.connections

import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.ambient.crossdevice.ParticipantImpl
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.dtdi.core.ChannelInfo
import com.google.android.gms.dtdi.core.DtdiClient
import com.google.android.gms.dtdi.core.OnPayloadReceivedCallback
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.jvm.Throws
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.tasks.await

/** @hide */
@RequiresApi(Build.VERSION_CODES.O)
class RemoteConnectionImpl(
  private val client: DtdiClient,
  override val participant: ParticipantImpl,
  channelName: String,
  namespace: String,
  override val connectionType: ConnectionType,
) : RemoteConnection {
  private val scope = MainScope()
  private val channelName = "$namespace:$channelName"
  private val connectionReceivers = CopyOnWriteArraySet<ConnectionReceiver>()
  private var closed = false
  override val isClosed
    get() = closed

  private val bufferedMessages = mutableListOf<ByteArray>()

  @SuppressWarnings("GmsCoreFirstPartyApiChecker")
  override suspend fun send(bytes: ByteArray): Result<Unit> {
    try {
      val channelInfo = ChannelInfo(channelName, connectionType.toChannelType())
      client.sendPayload(channelInfo, participant.token, bytes).await()
    } catch (e: ApiException) {
      return when (e.statusCode) {
        CommonStatusCodes.DEVELOPER_ERROR ->
          Result.failure(
            ConnectionsException(ConnectionsException.CONNECTION_CLOSED, e.localizedMessage)
          )
        CommonStatusCodes.TIMEOUT ->
          Result.failure(ConnectionsException(ConnectionsException.TIMEOUT, e.localizedMessage))
        else ->
          Result.failure(
            ConnectionsException(ConnectionsException.INTERNAL_ERROR, e.localizedMessage)
          )
      }
    }
    return Result.success(Unit)
  }

  override fun registerReceiver(receiver: ConnectionReceiver) {
    if (closed) {
      throw ConnectionsException(ConnectionsException.CONNECTION_CLOSED)
    }
    // Send buffered messages.
    for (message in bufferedMessages) {
      receiver.onMessageReceived(this@RemoteConnectionImpl, message)
    }

    connectionReceivers.add(receiver)
  }

  override fun unregisterReceiver(receiver: ConnectionReceiver) {
    connectionReceivers.remove(receiver)
  }

  @SuppressWarnings("GmsCoreFirstPartyApiChecker")
  override suspend fun close(reason: String?) {
    if (closed) {
      Log.w(TAG, "Closing a closed connection.")
      return
    }
    closed = true
    val channelInfo = ChannelInfo(channelName, connectionType.toChannelType())
    client.closeConnection(channelInfo, participant.token, reason).await()
    connectionReceivers.clear()
  }

  override fun closeFuture(reason: String?): ListenableFuture<Void?> =
    scope.future {
      close(reason)
      null
    }

  override fun sendFuture(bytes: ByteArray): ListenableFuture<Void?> =
    scope.future {
      send(bytes).getOrThrow()
      null
    }

  @SuppressWarnings("GmsCoreFirstPartyApiChecker")
  @Throws(IllegalArgumentException::class, ConnectionsException::class)
  internal suspend fun addPayloadReceiver() {
    try {
      val channelInfo = ChannelInfo(channelName, connectionType.toChannelType())
      client
        .registerPayloadReceiver(
          channelInfo,
          participant.token,
          object : OnPayloadReceivedCallback {
            override fun onPayloadReceived(
              channelName: String,
              token: IBinder,
              bytes: ByteArray,
            ) {
              bufferedMessages.add(bytes)
              connectionReceivers.forEach { it.onMessageReceived(this@RemoteConnectionImpl, bytes) }
            }

            override fun onConnectionClosed(reason: String?) {
              closed = true
              connectionReceivers.forEach {
                it.onConnectionClosed(this@RemoteConnectionImpl, reason = reason)
              }
            }
          }
        )
        .await()
    } catch (e: ApiException) {
      when (e.statusCode) {
        CommonStatusCodes.DEVELOPER_ERROR ->
          throw IllegalArgumentException("Channel $channelName has been registered already.")
        else -> throw ConnectionsException(ConnectionsException.INTERNAL_ERROR, e.localizedMessage)
      }
    }
  }

  companion object {
    private const val TAG = "RemoteConnectionImpl"

    private fun ConnectionType.toChannelType() =
      when (this) {
        ConnectionType.OPENED -> ChannelInfo.OPENED_CHANNEL
        ConnectionType.ACCEPTED -> ChannelInfo.ACCEPTED_CHANNEL
      }
  }
}
