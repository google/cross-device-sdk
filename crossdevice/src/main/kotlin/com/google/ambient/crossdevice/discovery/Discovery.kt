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

package com.google.ambient.crossdevice.discovery

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import android.util.Log
import androidx.activity.result.ActivityResultCaller
import androidx.annotation.RequiresApi
import com.google.ambient.crossdevice.Participant
import com.google.ambient.crossdevice.ParticipantImpl
import com.google.android.gms.dtdi.Dtdi as GmsDtdi
import com.google.android.gms.dtdi.analytics.CorrelationData
import com.google.android.gms.dtdi.core.AnalyticsInfo
import com.google.android.gms.dtdi.core.DtdiClient
import com.google.android.gms.dtdi.core.SelectedDevice

/** Entry point for discovering devices. */
@RequiresApi(Build.VERSION_CODES.O)
interface Discovery {
  /**
   * Gets the participant from an incoming [Intent]. Used to begin the process of accepting a remote
   * connection with a [Participant]. Returns null if the [Intent] is not valid for getting a
   * [Participant].
   */
  fun getParticipantFromIntent(intent: Intent): Participant?

  /**
   * Registers a callback for discovery.
   *
   * @param caller The calling activity or fragment.
   * @param callback The callback to be called when devices are selected by a user.
   * @return The launcher to use to show a device picker UI to the user.
   */
  fun registerForResult(
    caller: ActivityResultCaller,
    callback: OnDevicePickerResultListener,
  ): DevicePickerLauncher

  /** Interface to receive the result from the device picker, for use with [registerForResult]. */
  fun interface OnDevicePickerResultListener {
    /**
     * Called when the user has selected the devices to connect to, and the receiving component has
     * been successfully started.
     */
    fun onDevicePickerResult(participants: Collection<Participant>)
  }

  companion object {
    internal const val ORIGIN_DEVICE = "com.google.android.gms.dtdi.extra.ORIGIN_DEVICE"
    internal const val ANALYTICS_INFO = "com.google.android.gms.dtdi.extra.ANALYTICS_INFO"

    /** Creates an instance of [Discovery]. */
    @JvmStatic
    fun create(context: Context): Discovery = DiscoveryImpl(context, GmsDtdi.getClient(context))
  }
}

private const val TAG = "CrossDeviceDiscovery"

/**
 * Not intended for external usage. Please use [Discovery.create].
 *
 * @hide
 */
// TODO: Move Impl classes into internal packages with visibility/strict deps.
@RequiresApi(Build.VERSION_CODES.O)
class DiscoveryImpl(private val context: Context, private val dtdiClient: DtdiClient) : Discovery {
  @SuppressWarnings("GmsCoreFirstPartyApiChecker")
  override fun getParticipantFromIntent(intent: Intent): Participant? {
    val device =
      intent.getParcelable<SelectedDevice>(Discovery.ORIGIN_DEVICE)
        ?: run {
          Log.w(TAG, "Unable to get participant token from intent")
          return null
        }
    val analyticsInfo =
      intent.getParcelable<AnalyticsInfo>(Discovery.ANALYTICS_INFO)
        ?: run {
          Log.w(TAG, "Unable to get analytics info from intent")
          return null
        }
    return ParticipantImpl(
      device.displayName,
      device.token,
      CorrelationData.createFromAnalyticsInfo(analyticsInfo),
      dtdiClient,
    )
  }

  private inline fun <reified T : Parcelable> Intent.getParcelable(key: String): T? {
    // TODO: Use the non-deprecated Bundle.getParcelable(String, Class) instead on T or
    //   above when b/232589966 is fixed.
    @Suppress("DEPRECATION") return extras?.getParcelable(key)
  }

  override fun registerForResult(
    caller: ActivityResultCaller,
    callback: Discovery.OnDevicePickerResultListener,
  ): DevicePickerLauncher {
    return DevicePickerLauncherImpl(
      context = context,
      launcher =
        caller.registerForActivityResult(DevicePickerResultContract(dtdiClient)) {
          callback.onDevicePickerResult(it)
        }
    )
  }
}
