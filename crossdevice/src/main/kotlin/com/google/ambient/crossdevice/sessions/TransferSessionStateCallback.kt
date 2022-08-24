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

/**
 * Clients of Sessions should implement `OriginatingSessionStateCallback` to receive
 * notifications of Session state changes when transferring a Session.
 *
 * Provide your implementation in [Sessions.transferSession].
 */
@RequiresApi(Build.VERSION_CODES.O)
interface OriginatingSessionStateCallback : SessionStateCallback {
  /**
   * Notifies the application that the `OriginatingSession` associated with `sessionId` is ready to
   * begin communication with the receiving side.
   */
  fun onConnected(sessionId: SessionId)

  /** Called when transfer cannot complete. Failure reason described by `exception`. */
  fun onTransferFailure(sessionId: SessionId, exception: SessionException)

  /**
   * Called when a Session has been successfully transferred to another device (and no longer
   * running on this device).
   */
  fun onSessionTransferred(sessionId: SessionId)
}
/**
 * Clients of Sessions should implement `ReceivingSessionStateCallback` to receive
 * notifications of Session state changes when transferring a Session.
 *
 * Provide your implementation in [Sessions.getReceivingSession].
 */
@RequiresApi(Build.VERSION_CODES.O)
interface ReceivingSessionStateCallback : SessionStateCallback {
  /** Called when transfer cannot complete. Failure reason described by `errorCode` */
  fun onTransferFailure(sessionId: SessionId, exception: SessionException)
}
