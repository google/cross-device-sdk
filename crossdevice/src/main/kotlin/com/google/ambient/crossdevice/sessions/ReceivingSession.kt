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
 * Describes a Session that is in the process of being transferred to another device. The receiver
 * of the Session will use this class to initialize the application.
 */
@RequiresApi(Build.VERSION_CODES.O)
interface ReceivingSession : TransferrableSession {
  /**
   * Called when the receiving device is initialized and ready to run the Session. After this call,
   * all methods will throw [SessionException] with [HANDLE_INVALIDATED]
   * [SessionException.HANDLE_INVALIDATED].
   *
   * @return The [SessionId] that was received.
   *
   * @throws SessionException if the transfer cannot be completed for any reason:
   * - Session transfer is cancelled already (by either originating or receiving device)
   * - Handle is invalidated
   * - Internal error
   */
  @Throws(SessionException::class) suspend fun onComplete(): SessionId

  /** Java-compatible version of [onComplete]. */
  fun onCompleteFuture(): ListenableFuture<SessionId>
}

@RequiresApi(Build.VERSION_CODES.O)
internal class ReceivingSessionImpl(
  private val session: TransferrableSessionInterface,
  private val handleId: String
) : ReceivingSession {
  override val sessionId = session.sessionId
  private val transferrableSession = TransferrableSessionImpl(session, handleId)
  private val futureScope = MainScope()

  override fun getStartupRemoteConnection(): SessionRemoteConnection =
    transferrableSession.getStartupRemoteConnection()

  override suspend fun cancelTransfer() = transferrableSession.cancelTransfer()

  override fun cancelTransferFuture(): ListenableFuture<Unit> =
    transferrableSession.cancelTransferFuture()

  override suspend fun onComplete(): SessionId {
    return session.completeTransfer(handleId)
  }

  override fun onCompleteFuture(): ListenableFuture<SessionId> = futureScope.future { onComplete() }
}
