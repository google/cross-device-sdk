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

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.ambient.crossdevice.analytics.FirelogLoggerTransport
import com.google.ambient.crossdevice.logs.proto.ClientLogEnums.SessionAction
import com.google.ambient.crossdevice.logs.proto.ClientLogProtos.Result
import com.google.ambient.crossdevice.logs.proto.ClientLogProtos.SessionsLog
import com.google.ambient.crossdevice.logs.proto.SessionsLogKt.sessionEvent
import com.google.ambient.crossdevice.logs.proto.dtdiClientLog
import com.google.ambient.crossdevice.logs.proto.sessionsLog
import com.google.android.gms.dtdi.analytics.AnalyticsLogger
import com.google.android.gms.dtdi.analytics.ClientInfo
import com.google.android.gms.dtdi.analytics.CorrelationData
import com.google.android.gms.dtdi.analytics.LoggerTransport
import com.google.android.gms.dtdi.analytics.LoggingConfigurationStrategy
import com.google.android.gms.dtdi.analytics.PackageLoggingConfigurationStrategy
import com.google.android.gms.dtdi.core.ApiSurface.Companion.SESSIONS
import com.google.common.io.BaseEncoding
import com.google.protobuf.duration as durationDsl
import java.security.MessageDigest
import kotlin.time.Duration

/**
 * Analytics logging for SessionsLog events.
 *
 * @hide
 */
@RequiresApi(Build.VERSION_CODES.O)
interface SessionsAnalyticsLogger {
  /** Log all of the details of a SessionEvent. */
  fun logSessionEvent(
    sessionAction: SessionAction,
    result: Result,
    sessionId: SessionId,
    applicationSessionTag: ApplicationSessionTag?,
    duration: Duration?,
    correlationData: CorrelationData?,
  )
}

// TODO: Mark class as internal after moving tests to //third_party/cross_device_sdk
/** @hide */
@RequiresApi(Build.VERSION_CODES.O)
class SessionsAnalyticsLoggerImpl(
  context: Context,
  private val clientInfo: ClientInfo? = ClientInfo.fromPackageName(context, context.packageName),
  loggingConfigurationStrategy: LoggingConfigurationStrategy =
    PackageLoggingConfigurationStrategy.fromContext(context, SESSIONS),
  loggerTransport: LoggerTransport = FirelogLoggerTransport(context),
) : SessionsAnalyticsLogger {

  private val logger =
    AnalyticsLogger.create<SessionsLog>(
      context = context,
      logCreator = { event -> dtdiClientLog { sessionsLog = event }.toByteArray() },
      loggingConfigurationStrategy = loggingConfigurationStrategy,
      loggerTransport = loggerTransport,
      userType = AnalyticsLogger.AnalyticsUserType.USER_TYPE_UNKNOWN,
    )

  /** Log all of the details of a SessionEvent. */
  override fun logSessionEvent(
    sessionAction: SessionAction,
    result: Result,
    sessionId: SessionId,
    applicationSessionTag: ApplicationSessionTag?,
    duration: Duration?,
    correlationData: CorrelationData?,
  ) {
    val event = sessionsLog {
      this.sessionId = sessionId.id
      sessionEvent = sessionEvent {
        this.result = result
        this.sessionAction = sessionAction
        duration?.let {
          this.duration =
            it.toComponents { seconds, nanos ->
              durationDsl {
                this.seconds = seconds
                this.nanos = nanos
              }
            }
        }
        applicationSessionTag?.let {
          hashApplicationSessionTag(applicationSessionTag)?.let { hash ->
            applicationSessionTagHash = hash
          }
        }
      }
    }
    logger.log(event, correlationData ?: CorrelationData.createNew(), clientInfo)
  }

  internal companion object {
    private const val TAG = "SessionsAnalyticsLogger"

    internal fun hashApplicationSessionTag(applicationSessionTag: ApplicationSessionTag): String? {
      return try {
        // Get the Base64-encoded SHA-256 hash of the UTF-8 encoded session tag.
        val md = MessageDigest.getInstance("SHA-256")
        md.update(applicationSessionTag.sessionTag.toByteArray(Charsets.UTF_8))
        BaseEncoding.base64Url().omitPadding().encode(md.digest())
      } catch (ex: Exception) {
        // We would expect NoSuchAlgorithmException or some other Exception, but both cases fold
        // into logging the same string so we use this catch-all case.
        Log.w(TAG, "unable to create application session hash", ex)
        // Nothing to return if we can't hash the ApplicationSessionTag.
        null
      }
    }
  }
}
