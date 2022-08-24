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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.annotation.RequiresApi
import com.google.ambient.crossdevice.Participant
import com.google.ambient.crossdevice.ParticipantImpl
import com.google.android.gms.dtdi.analytics.CorrelationData
import com.google.android.gms.dtdi.core.DtdiClient

@RequiresApi(Build.VERSION_CODES.O)
@SuppressWarnings("GmsCoreFirstPartyApiChecker")
internal class DevicePickerResultContract(private val dtdiClient: DtdiClient) :
  ActivityResultContract<LaunchDevicePickerRequest, Collection<Participant>>() {
  override fun createIntent(context: Context, input: LaunchDevicePickerRequest): Intent {
    val intentSenderRequest = IntentSenderRequest.Builder(input.intentSender).build()
    return Intent(StartIntentSenderForResult.ACTION_INTENT_SENDER_REQUEST)
      .putExtra(StartIntentSenderForResult.EXTRA_INTENT_SENDER_REQUEST, intentSenderRequest)
  }

  override fun parseResult(resultCode: Int, intent: Intent?): Collection<Participant> {
    return if (resultCode == Activity.RESULT_OK) {
      val getDevicesResult =
        DtdiClient.getDevicesResultFromActivityResult(resultCode, checkNotNull(intent))
      val analyticsInfo = getDevicesResult?.analyticsInfo
      if (analyticsInfo == null) {
        Log.e(TAG, "No AnalyticsInfo from device picker")
        return emptyList()
      }
      val correlationData = CorrelationData.createFromAnalyticsInfo(analyticsInfo)
      Log.i(TAG, "Processing ${getDevicesResult.devices.size} participants from device picker")
      getDevicesResult.devices.map {
        ParticipantImpl(it.displayName, it.token, correlationData, dtdiClient)
      }
    } else {
      Log.e(TAG, "Unexpected result $resultCode from device picker")
      emptyList()
    }
  }

  companion object {
    private const val TAG = "DevicePickerResult"
  }
}
