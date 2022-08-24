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
import androidx.annotation.IntDef
import androidx.annotation.RequiresApi
import com.google.ambient.crossdevice.CrossDeviceException
import com.google.ambient.crossdevice.Participant

/**
 * Exception for connections requests. [Result] or [ListenableFuture]
 * [com.google.common.util.concurrent.ListenableFuture] returned from [Participant.openConnection]
 * [com.google.ambient.crossdevice.Participant.openConnection] or [Participant.acceptConnection]
 * [com.google.ambient.crossdevice.Participant.acceptConnection] may fail with this exception.
 */
@RequiresApi(Build.VERSION_CODES.O)
class ConnectionsException(
  override val errorCode: @ConnectionsErrorCode Int,
  message: String? = null,
  cause: Throwable? = null,
) : CrossDeviceException(message, cause) {
  @Retention(AnnotationRetention.SOURCE)
  @IntDef(TIMEOUT, INTERNAL_ERROR, CONNECTION_CLOSED)
  @Target(AnnotationTarget.TYPE)
  annotation class ConnectionsErrorCode

  companion object {
    /** Error code indicating the attempt to open a connection timed out. */
    const val TIMEOUT = 1
    /** Error code indicating when an unidentified internal error occurs. */
    const val INTERNAL_ERROR = 2
    /** Error code indicating the connection has been closed. */
    const val CONNECTION_CLOSED = 3
  }
}
