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
 * Describes methods that are common to both originating and receiving sides of a Session transfer.
 */
@RequiresApi(Build.VERSION_CODES.O)
interface TransferrableSession {
  /** [SessionId] this [TransferrableSession] belongs to. */
  val sessionId: SessionId

  /**
   * Gets communication channel to send initialization messages back and forth between the
   * originating and receiving device.
   *
   * @return The [SessionRemoteConnection] relevant to the transfer.
   *
   * @throws SessionException if this session has already completed.
   */
  @Throws(SessionException::class) fun getStartupRemoteConnection(): SessionRemoteConnection

  /**
   * Cancels the transfer. After this call, all methods will throw [SessionException] with
   * [HANDLE_INVALIDATED][SessionException.HANDLE_INVALIDATED].
   *
   * @throws SessionException if the transfer cannot be cancelled for any reason.
   */
  @Throws(SessionException::class) suspend fun cancelTransfer()

  /** Java-compatible version of [cancelTransfer]. */
  fun cancelTransferFuture(): ListenableFuture<Unit>
}

@RequiresApi(Build.VERSION_CODES.O)
internal class TransferrableSessionImpl(
  private val session: TransferrableSessionInterface,
  private val handleId: String
) : TransferrableSession {
  override val sessionId = session.sessionId
  private val futureScope = MainScope()

  override fun getStartupRemoteConnection(): SessionRemoteConnection =
    session.getDefaultApplicationRemoteConnectionForTransferrable(handleId)

  override suspend fun cancelTransfer() {
    return session.cancelTransferForHandle(
      SessionCancellationReason.TRANSFER_CANCELLED_BY_APP,
      handleId
    )
  }

  override fun cancelTransferFuture(): ListenableFuture<Unit> =
    futureScope.future { cancelTransfer() }
}
