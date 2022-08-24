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
import com.google.ambient.crossdevice.connections.ConnectionsException
import com.google.ambient.crossdevice.connections.RemoteConnection
import com.google.common.util.concurrent.ListenableFuture

/**
 * A connection to a remote device that is participating in a Session. This is a wrapper around
 * [RemoteConnection] updated to be specific to [SessionParticipant].
 */
@RequiresApi(Build.VERSION_CODES.O)
interface SessionRemoteConnection {
  /** The participant this this is a connection to. */
  val participant: SessionParticipant

  /** See [RemoteConnection.send] */
  suspend fun send(bytes: ByteArray): Result<Unit>

  /** Java-compatible version of [send]. */
  fun sendFuture(bytes: ByteArray): ListenableFuture<Void?>

  /**
   * Registers the given [SessionConnectionReceiver] with this channel.
   *
   * @throws ConnectionsException if the connection is closed.
   */
  fun registerReceiver(receiver: SessionConnectionReceiver)

  /** Unregisters a given receiver from the remote device. */
  fun unregisterReceiver(receiver: SessionConnectionReceiver)
}

/** Receiver callback for a [SessionRemoteConnection]. */
@RequiresApi(Build.VERSION_CODES.O)
interface SessionConnectionReceiver {
  /** Called when a message is received on the registered channel. */
  fun onMessageReceived(participant: SessionParticipant, payload: ByteArray)
}
