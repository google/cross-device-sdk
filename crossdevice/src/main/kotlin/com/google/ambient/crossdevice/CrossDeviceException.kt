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

/** Base exception type for Cross device APIs. */
@RequiresApi(Build.VERSION_CODES.O)
abstract class CrossDeviceException(
  message: String? = null,
  cause: Throwable? = null,
) : RuntimeException(message, cause) {
  /**
   * The error code associated with this exception. Refer to the concrete implementations for the
   * possible error codes and their meanings.
   *
   * @see com.google.ambient.crossdevice.connections.ConnectionsException
   * @see com.google.ambient.crossdevice.sessions.SessionException
   */
  abstract val errorCode: Int
  override val message: String?
    get() = super.message ?: "${this::class.simpleName} code=$errorCode"
}
