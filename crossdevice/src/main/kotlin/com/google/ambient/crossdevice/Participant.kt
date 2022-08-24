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
import androidx.annotation.RequiresApi
import com.google.ambient.crossdevice.connections.ConnectionsException
import com.google.ambient.crossdevice.connections.RemoteConnection
import com.google.common.util.concurrent.ListenableFuture
import com.google.errorprone.annotations.RestrictedApi

/**
 * An opaque handle that represents a participant in a cross device experience. This participant is
 * expected to be passed back to other Cross device SDK components to identify the desired
 * participant.
 *
 * Participants are not stable across experiences. If the user starts another experience by
 * selecting the same receiving device, the resulting [Participant] instance will still be
 * different.
 *
 * This class is not thread safe.
 */
@RequiresApi(Build.VERSION_CODES.O)
interface Participant {
  /**
   * A user-friendly name of the selected device. This is the same name that the user saw in
   * the device picker, for example, "Ryan's Pixel 6".
   */
  val displayName: CharSequence

  /**
   * The list of connections connected to this participant. This list includes both connections
   * opened and accepted.
   */
  val connections: List<RemoteConnection>

  /**
   * Returns a remote channel to send and receive messages.
   *
   * This function completes as soon as the connection channel is created, even if there are no
   * listeners registered for this connection on the receiving device yet. Messages can be sent as
   * soon as the connection is created, but they may be queued until a listener is registered.
   *
   * It is expected that this call will fail for a fraction of cases due to circumstances outside of
   * the application and system's control, such as poor signal strength or wireless interference.
   * Therefore, this method returns a [Result] and callers should always check and handle the
   * failure cases.
   *
   * If the connection is not created successfully within an implementation-defined timeout
   * (typically on the order of tens of seconds), the result will fail with the code
   * [ConnectionsException.TIMEOUT].
   *
   * Callers should close the connection after using it by calling either [RemoteConnection.close]
   * or [Participant.close].
   *
   * @param channelName A string identifying the channel. This string must be the same on this
   * device and the remote device from which this `participant` was provided for the messages to be
   * received successfully. The tuple (callingApp, participant, channelName) must be unique - trying
   * to create a channel with the same name, from the same application, to the same participant
   * device will result in an [IllegalArgumentException].
   */
  suspend fun openConnection(
    channelName: String,
  ): Result<RemoteConnection> = openConnection(channelName, "app")

  /** @hide */
  suspend fun openConnection(
    channelName: String,
    namespace: String,
  ): Result<RemoteConnection>

  /** Java-compatible version of [openConnection]. */
  fun openConnectionFuture(
    channelName: String,
  ): ListenableFuture<RemoteConnection>

  /**
   * Accepts an incoming connection from a remote device.
   *
   * This function completes as soon as the connection channel is created, even if there are no
   * listeners registered for this connection on the receiving device yet. Messages can be sent as
   * soon as the connection is created, but they may be queued until a listener is registered.
   *
   * It is expected that this call will fail for a fraction of cases due to circumstances outside of
   * the application and system's control, such as poor signal strength or wireless interference.
   * Therefore, this method returns a [Result] and callers should always check and handle the
   * failure cases.
   *
   * If the connection is not created successfully within an implementation-defined timeout
   * (typically on the order of tens of seconds), the result will fail with the code
   * [ConnectionsException.TIMEOUT].
   *
   * Callers should close the connection after using it by calling either [RemoteConnection.close]
   * or [Participant.close].
   *
   * @param channelName A string identifying the channel. This string must be the same on this
   * device and the remote device indicated by `participant` for the messages to be received
   * successfully. The tuple (callingApp, participant, channelName) must be unique - trying to
   * create a channel with the same name, from the same application, to the same remote device will
   * result in an [IllegalArgumentException].
   */
  suspend fun acceptConnection(
    channelName: String,
  ): Result<RemoteConnection> = acceptConnection(channelName, "app")

  /** @hide */
  suspend fun acceptConnection(
    channelName: String,
    namespace: String,
  ): Result<RemoteConnection>

  /** Java-compatible version of [acceptConnection]. */
  fun acceptConnectionFuture(
    channelName: String,
  ): ListenableFuture<RemoteConnection>

  /**
   * Closes this participant and all the associated [RemoteConnection]s. This participant should not
   * be used after calling this method.
   */
  suspend fun close()

  /** Java-compatible version of [close]. */
  fun closeFuture(): ListenableFuture<Void?>
}
