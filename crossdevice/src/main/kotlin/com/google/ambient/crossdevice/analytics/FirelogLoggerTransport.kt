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

package com.google.ambient.crossdevice.analytics

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.google.ambient.crossdevice.logs.proto.ClientLogProtos.DtdiClientLog
import com.google.android.datatransport.Encoding
import com.google.android.datatransport.Event
import com.google.android.datatransport.Transport
import com.google.android.datatransport.cct.CCTDestination
import com.google.android.datatransport.runtime.TransportRuntime
import com.google.android.gms.dtdi.analytics.AnalyticsLogger
import com.google.android.gms.dtdi.analytics.LoggerTransport
import com.google.errorprone.annotations.RestrictedApi

/**
 * Initializes Firelog Transport layer and exposes ability to log using this layer.
 *
 * @hide
 */
@RequiresApi(Build.VERSION_CODES.O)
class FirelogLoggerTransport
constructor(context: Context) : LoggerTransport {

  private val transport = createTransport(context)

  // log is a DtDiClientLog type object in ByteArray form
  override fun send(log: ByteArray) {
    transport.send(Event.ofTelemetry(DtdiClientLog.parseFrom(log)))
  }

  private companion object {

    /** Create a new CCT transport for DTDI. */
    private fun createTransport(context: Context): Transport<DtdiClientLog> {
      TransportRuntime.initialize(context)
      return TransportRuntime.getInstance()
        .newFactory(CCTDestination.INSTANCE)
        .getTransport(
          AnalyticsLogger.DTDI_LOG_SOURCE,
          DtdiClientLog::class.java,
          Encoding.of("proto"),
          DtdiClientLog::toByteArray
        )
    }
  }
}
