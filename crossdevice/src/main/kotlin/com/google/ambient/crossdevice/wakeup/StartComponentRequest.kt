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

package com.google.ambient.crossdevice.wakeup

import android.os.Build
import androidx.annotation.RequiresApi
import com.google.android.gms.dtdi.core.Extra
import com.google.android.gms.dtdi.core.ExtraType
import kotlin.collections.mutableListOf

/**
 * A request to wake up a specified component on another device, and optionally deliver additional
 * information to it. Currently the only supported component type is Android Activities.
 */
@RequiresApi(Build.VERSION_CODES.O)
class StartComponentRequest
private constructor(
  internal val action: String,
  internal val reason: String,
  internal val extras: List<Extra>,
) {

  fun toBuilder() = Builder(this)

  class Builder() {
    @set:JvmSynthetic // Prefer the method that returns Builder below to allow chaining in Java
    var action: String? = null
    @set:JvmSynthetic // Prefer the method that returns Builder below to allow chaining in Java
    var reason: String? = null
    private val extras = mutableListOf<Extra>()

    internal constructor(startComponentRequest: StartComponentRequest) : this() {
      action = startComponentRequest.action
      reason = startComponentRequest.reason
      extras.addAll(startComponentRequest.extras)
    }

    /**
     * (Required) Sets the intent action used to determine the target of the wakeup request.
     *
     * This intent action is used to resolve the target component (only Activity targets are
     * currently supported) on the receiving device.
     */
    fun setAction(action: String) = apply { this.action = action }

    /** (Required) Sets the user-visible reason for this request. */
    fun setReason(reason: String) = apply { this.reason = reason }

    @SuppressWarnings("GmsCoreFirstPartyApiChecker")
    /** Adds an extra string to the wakeup request that will be provided to the targeted app. */
    fun addExtra(key: String, value: String): Builder {
      extras.add(Extra(key, type = ExtraType.TYPE_STRING, stringExtra = value))
      return this
    }

    @SuppressWarnings("GmsCoreFirstPartyApiChecker")
    /** Adds an extra boolean to the wakeup request that will be provided to the targeted app. */
    fun addExtra(key: String, value: Boolean): Builder {
      extras.add(Extra(key, type = ExtraType.TYPE_BOOLEAN, booleanExtra = value))
      return this
    }

    @SuppressWarnings("GmsCoreFirstPartyApiChecker")
    /** Adds an extra int to the wakeup request that will be provided to the targeted app. */
    fun addExtra(key: String, value: Int): Builder {
      extras.add(Extra(key, type = ExtraType.TYPE_INT, intExtra = value))
      return this
    }

    @SuppressWarnings("GmsCoreFirstPartyApiChecker")
    /** Adds an extra ByteArray to the wakeup request that will be provided to the targeted app. */
    fun addExtra(key: String, value: ByteArray): Builder {
      extras.add(Extra(key, type = ExtraType.TYPE_BYTE_ARRAY, byteArrayExtra = value))
      return this
    }

    /**
     * Builds the [StartComponentRequest] from this builder.
     *
     * @throws IllegalArgumentException if required parameters are not specified.
     */
    @Throws(IllegalArgumentException::class)
    fun build(): StartComponentRequest {
      val action: String = requireNotNull(action) { "No action specified for request" }
      val reason: String = requireNotNull(reason) { "No reason specified for request" }
      return StartComponentRequest(action, reason, extras)
    }
  }
}

/** Convenience function for building a [StartComponentRequest]. */
@JvmSynthetic
@RequiresApi(Build.VERSION_CODES.O)
inline fun startComponentRequest(block: StartComponentRequest.Builder.() -> Unit) =
  StartComponentRequest.Builder().apply(block).build()
