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
import android.content.IntentSender
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.RequiresApi
import com.google.ambient.crossdevice.wakeup.StartComponentRequest
import com.google.android.gms.dtdi.Dtdi
import com.google.android.gms.dtdi.analytics.CorrelationData
import com.google.android.gms.dtdi.core.WakeUpRequest
import com.google.common.util.concurrent.ListenableFuture
import com.google.errorprone.annotations.RestrictedApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.tasks.await

internal class LaunchDevicePickerRequest(val intentSender: IntentSender)

/**
 * A launcher to start a device discovery and show a dialog chooser to display available devices.
 */
@RequiresApi(Build.VERSION_CODES.O)
interface DevicePickerLauncher {
  // TODO: Add an option |allowMultipleDeviceSelection| to allow selecting multiple
  // devices.
  /**
   * Launches a dialog chooser for available devices.
   *
   * @param deviceFilters Only devices that pass all filters will be shown to the user. Note: There
   * are currently no [DeviceFilters][DeviceFilter] supported in this version, so callers must pass
   * in an empty list.
   * @param startComponentRequest A request indicating how the caller wants the Android component to
   * be started on the receiving device.
   */
  suspend fun launchDevicePicker(
    deviceFilters: List<DeviceFilter>,
    startComponentRequest: StartComponentRequest,
  )

  /** Java-compatible version of [launchDevicePicker]. */
  fun launchDevicePickerFuture(
    deviceFilters: List<DeviceFilter>,
    startComponentRequest: StartComponentRequest,
  ): ListenableFuture<Void?>

  /**
   * Launch a dialog chooser for available devices with the parameter `parentCorrelationData` for
   * internal customers of discovery (i.e. Sessions).
   *
   * @param deviceFilters only devices that pass all filters will be shown to the user.
   * @param parentCorrelationData the correlation data of the caller.
   */
  // TODO: Limit the visibility of this method to limit the visibility of the concept
  // of parentCorrelationData.
  suspend fun launchDevicePicker(
    deviceFilters: List<DeviceFilter>,
    startComponentRequest: StartComponentRequest,
    parentCorrelationData: CorrelationData?
  )
}

@RequiresApi(Build.VERSION_CODES.O)
@SuppressWarnings("GmsCoreFirstPartyApiChecker")
internal class DevicePickerLauncherImpl(
  private val context: Context,
  private val launcher: ActivityResultLauncher<LaunchDevicePickerRequest>,
) : DevicePickerLauncher {
  override suspend fun launchDevicePicker(
    deviceFilters: List<DeviceFilter>,
    startComponentRequest: StartComponentRequest
  ) {
    launchDevicePicker(deviceFilters, startComponentRequest, null)
  }

  override suspend fun launchDevicePicker(
    deviceFilters: List<DeviceFilter>,
    startComponentRequest: StartComponentRequest,
    parentCorrelationData: CorrelationData?
  ) {
    val intentSender =
      Dtdi.getClient(context)
        .createDevicePickerIntent(
          deviceFilters = deviceFilters.map { it.toGmsCoreDeviceFilter() },
          allowMultipleDeviceSelection = false,
          wakeupRequest =
            WakeUpRequest(
              startComponentRequest.action,
              startComponentRequest.extras,
              startComponentRequest.reason,
            ),
          parentCorrelationData = parentCorrelationData,
        )
        .await()
    launcher.launch(LaunchDevicePickerRequest(intentSender))
  }

  override fun launchDevicePickerFuture(
    deviceFilters: List<DeviceFilter>,
    startComponentRequest: StartComponentRequest,
  ): ListenableFuture<Void?> =
    MainScope().future {
      launchDevicePicker(deviceFilters, startComponentRequest)
      null
    }
}
