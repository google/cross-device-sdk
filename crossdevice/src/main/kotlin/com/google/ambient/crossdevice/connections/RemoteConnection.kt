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
import androidx.annotation.RequiresApi
import com.google.ambient.crossdevice.Participant
import com.google.common.util.concurrent.ListenableFuture

/**
 * A connection to a remote device, used for sending and receiving payloads. Multiple connections
 * can exist between the same pair of devices, and they will share the underlying physical
 * connection. Developers can use this connection to send and receive payloads between devices.
 *
 * Implementations are not required to be thread safe.
 */
@RequiresApi(Build.VERSION_CODES.O)
interface RemoteConnection {

  /** The participant this remote connection is connected to. */
  val participant: Participant

  /**
   * Enqueues bytes to be sent across the channel.
   *
   * Bytes are sent in the order they are enqueued, and this function completes when the payload is
   * successfully enqueued. Note that completion of this method does not mean the remote device has
   * received or processed the payload message.
   *
   * @return [Result.success] if the message was sent successfully, or a failed result with
   * [ConnectionsException] if the connection is already closed.
   */
  suspend fun send(bytes: ByteArray): Result<Unit>

  /** Java-compatible version of [send]. */
  fun sendFuture(bytes: ByteArray): ListenableFuture<Void?>

  /**
   * Registers the given connection receiver with this channel.
   *
   * @throws ConnectionsException if the connection is closed.
   */
  fun registerReceiver(receiver: ConnectionReceiver)

  /** Unregisters a given receiver from the remote device. */
  fun unregisterReceiver(receiver: ConnectionReceiver)

  /**
   * Closes the connection, cleaning up any resources allocated for it.
   *
   * If called when the connection is already closed, this method will be a no-op.
   *
   * @param reason An optional reason field telling the remote participant why the connection is
   * closed.
   * @see ConnectionReceiver.onConnectionClosed
   */
  suspend fun close(reason: String? = null)

  /** Java-compatible version of [close]. */
  fun closeFuture(reason: String?): ListenableFuture<Void?>

  /** Java-compatible version of [close]. */
  fun closeFuture() = closeFuture(null) // @JvmOverloads doesn't work on interfaces

  /** The type of the connection. */
  val connectionType: ConnectionType

  /** `true` if this connection is closed. */
  val isClosed: Boolean
}

/** The type of the connection. */
@RequiresApi(Build.VERSION_CODES.O)
enum class ConnectionType {
  /** The connection was opened locally via [Participant.openConnection]. */
  OPENED,

  /** The connection was opened remotely and accepted via [Participant.acceptConnection]. */
  ACCEPTED,
}

/** Receiver callback for a [RemoteConnection]. */
@RequiresApi(Build.VERSION_CODES.O)
interface ConnectionReceiver {
  /** Called when a message is received on the [remoteConnection]. */
  fun onMessageReceived(remoteConnection: RemoteConnection, payload: ByteArray)

  /**
   * Called when the [remoteConnection] is closed.
   *
   * @param remoteConnection The remote connection that is closed.
   * @param error The error causing the connection to be closed, or `null` if the connection was
   * closed normally.
   * @param reason The reason indicated by the remote end of the connection in their call to
   * [RemoteConnection.close].
   */
  fun onConnectionClosed(
    remoteConnection: RemoteConnection,
    error: Throwable? = null,
    reason: String? = null
  )
}
