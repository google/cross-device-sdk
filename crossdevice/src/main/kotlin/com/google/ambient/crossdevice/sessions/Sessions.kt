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
import android.content.Intent
import android.os.Build
import androidx.activity.result.ActivityResultCaller
import androidx.annotation.DoNotInline
import androidx.annotation.IntDef
import androidx.annotation.RequiresApi
import com.google.ambient.crossdevice.CrossDeviceException
import com.google.ambient.crossdevice.discovery.DeviceFilter
import com.google.ambient.crossdevice.discovery.Discovery
import com.google.ambient.crossdevice.sessions.SessionProto.SessionId as SessionIdPb
import com.google.ambient.crossdevice.wakeup.StartComponentRequest
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.guava.future

/** An identifier that uniquely describes an individual Session. */
// Not a Data class because we do not want to support Copy.
@RequiresApi(Build.VERSION_CODES.O)
class SessionId constructor(internal val id: String) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is SessionId) return false
    if (id != other.id) return false
    return true
  }

  override fun hashCode(): Int {
    return id.hashCode()
  }

  override fun toString(): String {
    return "SessionId(id='$id')"
  }

  companion object {
    @JvmStatic
    @DoNotInline
    fun getSessionIdFromIntent(intent: Intent): SessionId? =
      intent.getStringExtra(SessionIntentConstants.EXTRA_SESSION_ID)?.let { SessionId(it) }
  }
}

/** @hide */
@RequiresApi(Build.VERSION_CODES.O)
fun SessionId.toProto(): SessionIdPb = sessionId { id = this@toProto.id }

/** An identifier that allows apps to differentiate between multiple types of sessions. */
@RequiresApi(Build.VERSION_CODES.O) class ApplicationSessionTag(val sessionTag: String)

/**
 * Entry-point for creating and interacting with Sessions. Create a client by calling
 * [Sessions.create].
 */
@RequiresApi(Build.VERSION_CODES.O)
interface Sessions {
  /**
   * Registers an [ActivityResultCaller] to enable Sessions to show available devices via
   * Discovery's device launcher.
   */
  fun registerActivityResultCaller(activityResultCaller: ActivityResultCaller)

  /**
   * Creates a Session which can be be transferred to or shared with another device. Returns the
   * [SessionId] associated with that Session.
   *
   * @param applicationSessionTag A client-defined concept that optionally describes the high level
   * type of experience for a Session within an app.
   */
  fun createSession(
    applicationSessionTag: ApplicationSessionTag? = null,
  ): SessionId

  /**
   * Removes a Session so that it can no longer be discovered by other devices requesting transfer
   * or share. If remove fails, a [SessionException] will be thrown.
   *
   * This will cancel any ongoing requests for transfer/share (
   * [SessionStateCallback.onTransferFailure], [SessionStateCallback.onShareFailure]). This will
   * terminate any active shared sessions (Primary and Secondaries will be cleaned up -
   * [PrimarySessionStateCallback.onPrimarySessionCleanup],
   * [SecondarySessionStateCallback.onSecondarySessionCleanup]).
   */
  @Throws(SessionException::class) suspend fun removeSession(sessionId: SessionId)

  /** Java-compatible version of [removeSession]. */
  fun removeSessionFuture(sessionId: SessionId): ListenableFuture<Void?>

  /**
   * Transfers a Session from the device this function is called on to another.
   *
   * Opens a window that searches for compatible, trusted devices. When the user selects a specific
   * device, the receiving app on that device will receive the request to transfer via
   * [StartComponentRequest] and can begin initialization (see [ReceivingSession]).
   * [StartComponentRequest] must contain an action and a user-visible reason for the request, and
   * can optionally contain extras.
   *
   * If transfer cannot be initiated, a [SessionException] will be thrown.
   *
   * If receiving app is ready to accept the transfer, [OriginatingSessionStateCallback.onConnected]
   * is triggered. Otherwise, [OriginatingSessionStateCallback.onTransferFailure] is triggered.
   */
  @Throws(SessionException::class)
  suspend fun transferSession(
    sessionId: SessionId,
    startComponentRequest: StartComponentRequest,
    deviceFilters: List<DeviceFilter>,
    callback: OriginatingSessionStateCallback
  ): OriginatingSession

  /** Java-compatible version of [transferSession]. */
  fun transferSessionFuture(
    sessionId: SessionId,
    startComponentRequest: StartComponentRequest,
    deviceFilters: List<DeviceFilter>,
    callback: OriginatingSessionStateCallback
  ): ListenableFuture<OriginatingSession>

  /**
   * Receives a [ReceivingSession] to transfer a Session to this device. Should be called
   * from receiving device after app is launched by D2DI with an intent specified by the originating
   * device.
   *
   * App should pass the [intent] it was launched with as a parameter.
   *
   * @throws SessionException if unable to return a `ReceivingSession`.
   */
  @Throws(SessionException::class)
  suspend fun getReceivingSession(
    intent: Intent,
    callback: ReceivingSessionStateCallback,
  ): ReceivingSession

  /** Java-compatible version of [getReceivingSession]. */
  fun getReceivingSessionFuture(
    intent: Intent,
    callback: ReceivingSessionStateCallback,
  ): ListenableFuture<ReceivingSession>

  /**
   * Invites another device to join a Session currently running on the device this function is
   * called on.
   *
   * Opens a dialog that searches for compatible, trusted devices. When the user selects a specific
   * device, the receiving app on that device will receive the request to join the session via
   * [StartComponentRequest] and can begin initialization (see [SecondarySession]).
   * [StartComponentRequest] must contain an action and a user-visible reason for the request, and
   * can optionally contain extras.
   *
   * If the share cannot be initiated, a [SessionException] will be thrown.
   *
   * If the receiving app is ready to accept the share request,
   * [PrimarySessionStateCallback.onParticipantJoined] is triggered. Otherwise,
   * [PrimarySessionStateCallback.onShareFailureWithParticipant] is triggered.
   *
   * Once devices are selected, [PrimarySessionStateCallback.onShareInitiated] will be called to
   * inform the application of the number of potential participants.
   */
  @Throws(SessionException::class)
  suspend fun shareSession(
    sessionId: SessionId,
    startComponentRequest: StartComponentRequest,
    deviceFilters: List<DeviceFilter>,
    callback: PrimarySessionStateCallback
  ): PrimarySession

  /** Java-compatible version of [shareSession]. */
  fun shareSessionFuture(
    sessionId: SessionId,
    startComponentRequest: StartComponentRequest,
    deviceFilters: List<DeviceFilter>,
    callback: PrimarySessionStateCallback
  ): ListenableFuture<PrimarySession>

  /**
   * Receives a [SecondarySession] to join a Session from this device. Should be
   * called from receiving device after app is launched by D2DI with an intent specified by the
   * originating device.
   *
   * App should pass the [intent] it was launched with as a parameter.
   *
   * @throws SessionException if unable to return a `SecondarySession`.
   */
  @Throws(SessionException::class)
  suspend fun getSecondarySession(
    intent: Intent,
    callback: SecondarySessionStateCallback,
  ): SecondarySession

  /** Java-compatible version of [getSecondarySession]. */
  fun getSecondarySessionFuture(
    intent: Intent,
    callback: SecondarySessionStateCallback,
  ): ListenableFuture<SecondarySession>

  companion object {
    /** Creates an instance of [Sessions]. */
    @JvmStatic
    fun create(context: Context): Sessions =
      SessionsImpl(Discovery.create(context), SessionsAnalyticsLoggerImpl(context))
  }
}

/**
 * Defines how two [ApplicationSessionTag] should be matched. Used as a filter when searching for
 * ongoing Sessions.
 */
@RequiresApi(Build.VERSION_CODES.O)
fun interface ApplicationSessionTagMatcher {
  fun matchSessionTag(applicationSessionTag: ApplicationSessionTag?): Boolean
}

/**
 * An [ApplicationSessionTagMatcher] that matches [ApplicationSessionTag] objects when their
 * [ApplicationSessionTag.sessionTag] strings are the same.
 */
@RequiresApi(Build.VERSION_CODES.O)
class ExactSessionTagMatcher(private val applicationSessionTagToMatch: ApplicationSessionTag?) :
  ApplicationSessionTagMatcher {
  override fun matchSessionTag(applicationSessionTag: ApplicationSessionTag?): Boolean =
    applicationSessionTagToMatch?.sessionTag == applicationSessionTag?.sessionTag
}

/** An [ApplicationSessionTagMatcher] that always returns true. */
@RequiresApi(Build.VERSION_CODES.O)
object MatchEverythingSessionTagMatcher : ApplicationSessionTagMatcher {
  override fun matchSessionTag(applicationSessionTag: ApplicationSessionTag?): Boolean = true
}

/**
 * Not intended for external usage. Please use [Sessions.create].
 *
 * @hide
 */
@RequiresApi(Build.VERSION_CODES.O)
class SessionsImpl(
  discovery: Discovery,
  logger: SessionsAnalyticsLogger,
  coroutineScope: CoroutineScope = MainScope()
) : Sessions {
  private val futureScope = coroutineScope
  private val sessionsManager = SessionsManager(discovery, logger, coroutineScope)

  override fun registerActivityResultCaller(activityResultCaller: ActivityResultCaller) {
    sessionsManager.registerActivityResultCaller(activityResultCaller)
  }

  override fun createSession(
    applicationSessionTag: ApplicationSessionTag?,
  ): SessionId = sessionsManager.createSession(applicationSessionTag)

  override suspend fun removeSession(sessionId: SessionId) {
    sessionsManager.removeSession(sessionId)
  }

  override fun removeSessionFuture(sessionId: SessionId): ListenableFuture<Void?> =
    futureScope.future {
      removeSession(sessionId)
      null
    }

  override suspend fun transferSession(
    sessionId: SessionId,
    startComponentRequest: StartComponentRequest,
    deviceFilters: List<DeviceFilter>,
    callback: OriginatingSessionStateCallback
  ): OriginatingSession {
    return sessionsManager.transferSession(
      sessionId,
      startComponentRequest,
      deviceFilters,
      callback
    )
  }

  override fun transferSessionFuture(
    sessionId: SessionId,
    startComponentRequest: StartComponentRequest,
    deviceFilters: List<DeviceFilter>,
    callback: OriginatingSessionStateCallback
  ): ListenableFuture<OriginatingSession> =
    futureScope.future {
      transferSession(sessionId, startComponentRequest, deviceFilters, callback)
    }

  override suspend fun getReceivingSession(
    intent: Intent,
    callback: ReceivingSessionStateCallback,
  ): ReceivingSession {
    return sessionsManager.getReceivingSession(intent, callback)
  }

  override fun getReceivingSessionFuture(
    intent: Intent,
    callback: ReceivingSessionStateCallback,
  ): ListenableFuture<ReceivingSession> =
    futureScope.future { getReceivingSession(intent, callback) }

  override suspend fun shareSession(
    sessionId: SessionId,
    startComponentRequest: StartComponentRequest,
    deviceFilters: List<DeviceFilter>,
    callback: PrimarySessionStateCallback
  ): PrimarySession =
    sessionsManager.shareSession(sessionId, startComponentRequest, deviceFilters, callback)

  override fun shareSessionFuture(
    sessionId: SessionId,
    startComponentRequest: StartComponentRequest,
    deviceFilters: List<DeviceFilter>,
    callback: PrimarySessionStateCallback
  ): ListenableFuture<PrimarySession> =
    futureScope.future { shareSession(sessionId, startComponentRequest, deviceFilters, callback) }

  override suspend fun getSecondarySession(
    intent: Intent,
    callback: SecondarySessionStateCallback,
  ): SecondarySession {
    return sessionsManager.createSecondarySession(intent, callback)
  }

  override fun getSecondarySessionFuture(
    intent: Intent,
    callback: SecondarySessionStateCallback,
  ): ListenableFuture<SecondarySession> =
    futureScope.future { getSecondarySession(intent, callback) }
}

/**
 * Exception for session requests. [Result] or [ListenableFuture] returned from APIs in [Sessions]
 * may fail with this exception.
 */
@RequiresApi(Build.VERSION_CODES.O)
class SessionException(
  override val errorCode: @SessionErrorCode Int,
  message: String? = null,
  cause: Throwable? = null,
) : CrossDeviceException(message, cause) {
  @Retention(AnnotationRetention.SOURCE)
  @IntDef(
    INTERNAL_ERROR,
    INVALID_SESSION_ID,
    OPERATION_NOT_ALLOWED,
    INVALID_PARTICIPANT,
    INTERNAL_CONNECTION_DISCONNECTED,
    CONNECTION_FAILURE,
    UNEXPECTED_STATE,
    SESSION_ACTION_CANCELLED,
    NO_DEVICES_SELECTED,
    HANDLE_INVALIDATED,
    INVALID_START_COMPONENT_REQUEST,
    SESSIONS_NOT_INITIALIZED
  )
  @Target(AnnotationTarget.TYPE)
  annotation class SessionErrorCode

  companion object {
    const val INTERNAL_ERROR = 1
    const val INVALID_SESSION_ID = 2
    const val OPERATION_NOT_ALLOWED = 3
    const val INVALID_PARTICIPANT = 4
    const val INTERNAL_CONNECTION_DISCONNECTED = 5
    const val CONNECTION_FAILURE = 6
    const val UNEXPECTED_STATE = 7
    const val SESSION_ACTION_CANCELLED = 8
    const val NO_DEVICES_SELECTED = 9
    const val HANDLE_INVALIDATED = 10
    const val INVALID_START_COMPONENT_REQUEST = 11
    const val SESSIONS_NOT_INITIALIZED = 12
  }
}

// TODO: Use WakeUpRequest in Sessions APIs rather than specifying sessions-specific
// intents. (Extra will still be owned by sessions).
/** @hide */
@RequiresApi(Build.VERSION_CODES.O)
object SessionIntentConstants {
  /** An action that specifies the intent to transfer. */
  const val SESSION_TRANSFER_ACTION = "com.google.ambient.crossdevice.SESSION_TRANSFER"

  /** An action that specifies the intent to share. */
  const val SESSION_SHARE_ACTION = "com.google.ambient.crossdevice.SESSION_SHARE"

  /**
   * Used as a String extra field containing an ID corresponding to a Session that another device
   * wants to transfer to or share with the device where this activity is launched.
   */
  const val EXTRA_SESSION_ID = "SessionId"
}
