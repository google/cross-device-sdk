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
 * When a Session becomes shared with one or more devices, this interface represents a secondary
 * device (a device that has joined an existing Session).
 */
@RequiresApi(Build.VERSION_CODES.O)
interface SecondarySession {

  /** [SessionId] this [SecondarySession] belongs to. */
  val sessionId: SessionId

  /**
   * Gets communication channel to send messages to the primary of the Sessions.
   *
   * @throws SessionException if the [SecondarySession] object is no long valid.
   */
  @Throws(SessionException::class) fun getDefaultRemoteConnection(): SessionRemoteConnection

  /**
   * Destroys the [SecondarySession].
   *
   * When a [SecondarySession] is destroyed, the connection with the Primary is terminated and this
   * participant will leave the underlying Session.
   *
   * @throws SessionException if the [SecondarySession] object is no long valid.
   */
  @Throws(SessionException::class) suspend fun destroySecondarySession()

  /** Java-compatible version of [destroySecondarySession]. */
  fun destroySecondarySessionFuture(): ListenableFuture<Void?>
}

@RequiresApi(Build.VERSION_CODES.O)
internal class SecondarySessionImpl(
  private val session: SecondarySessionInterface,
  private val handleId: String
) : SecondarySession {
  override val sessionId = session.sessionId
  private val futureScope = MainScope()

  override fun getDefaultRemoteConnection(): SessionRemoteConnection =
    session.getDefaultApplicationRemoteConnectionForSecondary(handleId)

  override suspend fun destroySecondarySession() {
    session.cancelShareForHandle(SessionCancellationReason.SHARE_CANCELLED_BY_APP, handleId)
  }

  override fun destroySecondarySessionFuture(): ListenableFuture<Void?> =
    futureScope.future {
      destroySecondarySession()
      null
    }
}
