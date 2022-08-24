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

/**
 * Describes a Session that is in the process of being transferred to another device. The
 * originating side of the Session transfer will use this class to initialize the application.
 */
@RequiresApi(Build.VERSION_CODES.O)
interface OriginatingSession : TransferrableSession {
  /**
   * Gets communication channel to send initialization messages back and forth between the
   * originating and receiving device.
   *
   * @return The [SessionRemoteConnection] relevant to the transfer.
   *
   * @throws SessionException if this session has already completed. This method will also throw an
   * exception if it is called prior to the Session being ready for initialization. Applications
   * will notify that they are ready for initialization in
   * [OriginatingSessionStateCallback.onConnected].
   *
   * @throws SessionException if this session has already completed.
   */
  @Throws(SessionException::class)
  override fun getStartupRemoteConnection(): SessionRemoteConnection
}

@RequiresApi(Build.VERSION_CODES.O)
internal class OriginatingSessionImpl(session: TransferrableSessionInterface, handleId: String) :
  OriginatingSession {
  override val sessionId = session.sessionId
  private val transferrableSession = TransferrableSessionImpl(session, handleId)

  override fun getStartupRemoteConnection(): SessionRemoteConnection =
    transferrableSession.getStartupRemoteConnection()

  override suspend fun cancelTransfer() = transferrableSession.cancelTransfer()

  override fun cancelTransferFuture(): ListenableFuture<Unit> =
    transferrableSession.cancelTransferFuture()
}
