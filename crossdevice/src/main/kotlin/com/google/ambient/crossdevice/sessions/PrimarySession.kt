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
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.guava.future

/**
 * When a Session becomes shared with one or more devices, this interface represents the Primary
 * device (the Session owner).
 */
@RequiresApi(Build.VERSION_CODES.O)
interface PrimarySession {

  /** The [SessionId] this [PrimarySession] belongs to. */
  val sessionId: SessionId

  /**
   * Enqueues `bytes` to be sent to all connected secondaries on their associated
   * [SessionRemoteConnection].
   *
   * @param bytes The bytes to be sent across the [SessionRemoteConnection].
   * @throws SessionException if this [PrimarySession] is no longer valid.
   */
  @Throws(SessionException::class) suspend fun broadcastToSecondaries(bytes: ByteArray)

  /** Java-compatible version of [broadcastToSecondaries]. */
  @Throws(SessionException::class)
  fun broadcastToSecondariesFuture(bytes: ByteArray): ListenableFuture<Void?>

  /**
   * Returns a list containing the [SessionRemoteConnection] instances for all secondaries related
   * to this session.
   *
   * @throws SessionException if this [PrimarySession] is no longer valid.
   */
  @Throws(SessionException::class)
  fun getSecondaryRemoteConnections(): List<SessionRemoteConnection>

  /**
   * Returns the [SessionRemoteConnection] for `secondary`.
   *
   * @throws SessionException if this [PrimarySession] is no longer valid or the `secondary` is not
   * connected.
   */
  @Throws(SessionException::class)
  fun getSecondaryRemoteConnectionForParticipant(
    secondary: SessionParticipant
  ): SessionRemoteConnection

  /**
   * Ends the shared experience that this PrimarySession is currently leading. Once
   * `destroyPrimarySessionAndStopSharing` is called, all other methods will throw
   * [SessionException] when called.
   *
   * On the secondary side, each secondary will be notified with
   * [SecondarySessionStateCallback.onSecondarySessionCleanup].
   *
   * @throws SessionException This [PrimarySession] is no longer valid.
   */
  @Throws(SessionException::class) suspend fun destroyPrimarySessionAndStopSharing()

  /** Java-compatible version of [destroyPrimarySessionAndStopSharing]. */
  @Throws(SessionException::class)
  fun destroyPrimarySessionAndStopSharingFuture(): ListenableFuture<Void?>

  /**
   * Removes the given participant from this session. On the secondary side, the secondary will be
   * notified with [SecondarySessionStateCallback.onSecondarySessionCleanup].
   *
   * @param secondary The participant to remove.
   * @throws SessionException This [PrimarySession] is no longer valid.
   */
  @Throws(SessionException::class)
  suspend fun removeSecondaryFromSession(secondary: SessionParticipant)

  /** Java-compatible version of [removeSecondaryFromSession]. */
  @Throws(SessionException::class)
  fun removeSecondaryFromSessionFuture(secondary: SessionParticipant): ListenableFuture<Void?>

  /**
   * Enables new participants to find and join the session. This [PrimarySession] is notified of new
   * participants joining the session via [PrimarySessionStateCallback.onParticipantJoined]. New
   * participants are disabled by default.
   *
   * @throws SessionException This [PrimarySession] is no longer valid.
   */
  @Throws(SessionException::class) fun enableSessionDiscovery()

  /**
   * Disallows new participants to find and join the session.
   *
   * @throws SessionException This [PrimarySession] is no longer valid.
   */
  @Throws(SessionException::class) fun disableSessionDiscovery()

  /**
   * Checks whether session discovery is currently enabled.
   *
   * @throws SessionException This [PrimarySession] is no longer valid.
   */
  @Throws(SessionException::class) fun isSessionDiscoveryEnabled(): Boolean
}

@RequiresApi(Build.VERSION_CODES.O)
internal class PrimarySessionImpl(
  private val session: PrimarySessionInterface,
  private val handleId: String
) : PrimarySession {
  override val sessionId = session.sessionId
  private val futureScope = MainScope()

  override suspend fun broadcastToSecondaries(bytes: ByteArray) {
    for (connection in getSecondaryRemoteConnections()) {
      connection.send(bytes)
    }
  }

  override fun broadcastToSecondariesFuture(bytes: ByteArray): ListenableFuture<Void?> =
    futureScope.future {
      broadcastToSecondaries(bytes)
      null
    }

  override fun getSecondaryRemoteConnections(): List<SessionRemoteConnection> {
    return session.getSecondaryApplicationConnections(handleId).values.toList()
  }

  override fun getSecondaryRemoteConnectionForParticipant(
    secondary: SessionParticipant
  ): SessionRemoteConnection {
    val connectionMap = session.getSecondaryApplicationConnections(handleId)
    return connectionMap[secondary] ?: throw SessionException(SessionException.INVALID_PARTICIPANT)
  }

  override suspend fun destroyPrimarySessionAndStopSharing() {
    session.cancelShareForHandle(SessionCancellationReason.SHARE_CANCELLED_BY_APP, handleId)
  }

  override fun destroyPrimarySessionAndStopSharingFuture(): ListenableFuture<Void?> =
    futureScope.future {
      destroyPrimarySessionAndStopSharing()
      null
    }

  override suspend fun removeSecondaryFromSession(secondary: SessionParticipant) {
    session.removeParticipantFromSession(secondary, handleId)
  }

  override fun removeSecondaryFromSessionFuture(
    secondary: SessionParticipant
  ): ListenableFuture<Void?> =
    futureScope.future {
      removeSecondaryFromSession(secondary)
      null
    }

  override fun enableSessionDiscovery() {
    session.enableSessionDiscovery(handleId)
  }

  override fun disableSessionDiscovery() {
    session.disableSessionDiscovery(handleId)
  }

  override fun isSessionDiscoveryEnabled(): Boolean {
    return session.isSessionDiscoveryEnabled(handleId)
  }
}
