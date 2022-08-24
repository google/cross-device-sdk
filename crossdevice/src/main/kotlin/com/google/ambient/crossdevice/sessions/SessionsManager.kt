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

import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultCaller
import androidx.annotation.RequiresApi
import com.google.ambient.crossdevice.Participant
import com.google.ambient.crossdevice.ParticipantImpl
import com.google.ambient.crossdevice.connections.ConnectionReceiver
import com.google.ambient.crossdevice.connections.RemoteConnection
import com.google.ambient.crossdevice.discovery.DeviceFilter
import com.google.ambient.crossdevice.discovery.DevicePickerLauncher
import com.google.ambient.crossdevice.discovery.Discovery
import com.google.ambient.crossdevice.logs.proto.ClientLogEnums.ErrorCode
import com.google.ambient.crossdevice.logs.proto.ClientLogEnums.ResultCode
import com.google.ambient.crossdevice.logs.proto.ClientLogEnums.SessionAction
import com.google.ambient.crossdevice.logs.proto.result
import com.google.ambient.crossdevice.sessions.SessionMetadataKt.applicationSessionTag
import com.google.ambient.crossdevice.sessions.SessionProto.OnSessionActionRequestMessage
import com.google.ambient.crossdevice.sessions.SessionProto.SessionMetadataRequest
import com.google.ambient.crossdevice.sessions.SessionProto.SessionMetadataResponse
import com.google.ambient.crossdevice.sessions.SessionProto.SessionState.State.SESSION_CREATED
import com.google.ambient.crossdevice.sessions.SessionProto.SessionState.State.SESSION_SHARE
import com.google.ambient.crossdevice.sessions.SessionProto.SessionState.State.SESSION_TRANSFER
import com.google.ambient.crossdevice.wakeup.StartComponentRequest
import com.google.android.gms.dtdi.analytics.CorrelationData
import com.google.protobuf.MessageLite
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "SessionsManager"

@RequiresApi(Build.VERSION_CODES.O) internal const val SESSIONS_CONNECTION_NAMESPACE = "sessions"

@RequiresApi(Build.VERSION_CODES.O)
internal class SessionsManager(
  private val discovery: Discovery,
  private val logger: SessionsAnalyticsLogger,
  private val coroutineScope: CoroutineScope
) {
  private val sessionStorage: MutableMap<SessionId, SessionImpl> = ConcurrentHashMap()
  private lateinit var launcher: DevicePickerLauncher

  private val sessionIdForLauncherMutex = Mutex()

  private var sessionIdForLauncher: SessionId? = null

  @SuppressWarnings("GmsCoreFirstPartyApiChecker")
  private fun hasDuplicateSessionIdExtra(startComponentRequest: StartComponentRequest): Boolean {
    return (startComponentRequest.extras
      .asSequence()
      .filter { it.key == SessionIntentConstants.EXTRA_SESSION_ID }
      .take(2)
      .count() > 1)
  }

  private fun buildStartComponentRequest(
    startComponentRequest: StartComponentRequest,
    sessionId: SessionId
  ): StartComponentRequest {
    val newStartComponentRequest =
      try {
        startComponentRequest
          .toBuilder()
          .addExtra(SessionIntentConstants.EXTRA_SESSION_ID, sessionId.id)
          .build()
      } catch (e: Exception) {
        throw SessionException(SessionException.INVALID_START_COMPONENT_REQUEST, cause = e)
      }

    if (hasDuplicateSessionIdExtra(newStartComponentRequest)) {
      throw SessionException(
        SessionException.INVALID_START_COMPONENT_REQUEST,
        "Application not allowed to populate Session ID Extra in StartComponentRequest.Builder."
      )
    }

    return newStartComponentRequest
  }

  /** Internal functions in `SessionsManager` should only be called by [SessionsImpl]. */

  /**
   * Registers an [ActivityResultCaller] to enable Sessions to show available devices via
   * Discovery's device launcher.
   */
  internal fun registerActivityResultCaller(activityResultCaller: ActivityResultCaller) {
    launcher =
      discovery.registerForResult(activityResultCaller) { participants ->
        coroutineScope.launch {
          sessionIdForLauncherMutex.withLock {
            if (sessionIdForLauncher == null) {
              Log.e(TAG, "Received participants from discovery without session ID; discarding.")
              return@launch
            }

            val session = sessionStorage[sessionIdForLauncher]
            if (session == null) {
              Log.e(TAG, "Received participants from discovery with invalid session ID; failing.")
              sessionIdForLauncher = null
              return@launch
            }

            onDiscoveryResult(session, participants)
            sessionIdForLauncher = null
          }
        }
      }
  }

  /**
   * Creates an internal representation of an app user-experience. When called, designates the
   * calling device as the originating device in cross-device actions (transfer/share).
   */
  internal fun createSession(applicationSessionTag: ApplicationSessionTag? = null): SessionId {
    val sessionId = SessionId(UUID.randomUUID().toString())
    sessionStorage[sessionId] =
      SessionImpl(sessionId, applicationSessionTag, logger, coroutineScope).also {
        logger.logSessionEvent(
          SessionAction.SESSION_ACTION_CREATE,
          result { resultCode = ResultCode.RESULT_CODE_SUCCESS },
          sessionId,
          applicationSessionTag,
          null,
          it.correlationData
        )
      }
    return sessionId
  }

  /**
   * Removes the Session from D2DI. If there is a cross-device action ongoing, that action is
   * cancelled. Called on either originating or receiving device.
   */
  internal suspend fun removeSession(sessionId: SessionId) {
    val session =
      sessionStorage[sessionId] ?: throw SessionException(SessionException.INVALID_SESSION_ID)

    session.setOnActionCancelledCallback(::cleanupSession)

    // If `sessions.cancelled` is already set, but we are not yet in the `SESSION_CREATED` state,
    // cancel is currently ongoing and the application attempted to remove the session. No further
    // action is necessary at this time because the `onActionCancelledCallback` will be fired once
    // the cancel is complete.
    if (session.cancelled.get() && session.getState() != SESSION_CREATED) {
      return
    }

    when (session.getState()) {
      SESSION_TRANSFER -> session.cancelTransfer(SessionCancellationReason.SESSION_REMOVED_BY_APP)
      SESSION_SHARE -> session.cancelShare(SessionCancellationReason.SESSION_REMOVED_BY_APP)
      else -> cleanupSession(sessionId)
    }
  }

  /**
   * Removes the specified session from `sessionStorage`. To be passed as a callback to sessions
   * that should be cleaned up after their action is complete or cancelled.
   */
  private fun cleanupSession(sessionId: SessionId) {
    val session = sessionStorage.remove(sessionId)

    val result =
      if (session != null) result { resultCode = ResultCode.RESULT_CODE_SUCCESS }
      else
        result {
          resultCode = ResultCode.RESULT_CODE_ERROR
          errorCode = ErrorCode.ERROR_CODE_SESSIONS_INVALID_SESSION
        }

    logger.logSessionEvent(
      SessionAction.SESSION_ACTION_DESTROY,
      result,
      sessionId,
      session?.applicationSessionTag,
      null,
      session?.correlationData
    )

    Log.i(TAG, "Session $sessionId removed.")
  }

  /**
   * Initiates the transfer process. Called on originating device. Opens a DevicePicker UI on the
   * originating device; launches the calling app on the receiving device (the device the user
   * picked).
   * @throws a [SessionException] if the transfer cannot be initiated.
   */
  internal suspend fun transferSession(
    sessionId: SessionId,
    startComponentRequest: StartComponentRequest,
    deviceFilters: List<DeviceFilter>,
    originatingSessionStateCallbacks: OriginatingSessionStateCallback
  ): OriginatingSession {
    var exception: SessionException? = null
    try {
      transferSessionInternal(
        sessionId,
        startComponentRequest,
        deviceFilters,
        originatingSessionStateCallbacks
      )
    } catch (e: SessionException) {
      exception = e
    }
    val session = sessionStorage[sessionId]

    logger.logSessionEvent(
      SessionAction.SESSION_ACTION_TRANSFER_INITIATED,
      sessionExceptionToResult(exception),
      sessionId,
      session?.applicationSessionTag,
      null,
      session?.correlationData
    )

    if (exception != null) throw exception

    return session!!.createOriginatingSession()
  }

  /**
   * Called by `transferSession` to perform error-checking and transfer initiation logic. Used to
   * wrap transfer business logic into one function call for exceptions to be handled internally in
   * one place for logging purposes.
   * @throws a [SessionException] if the transfer cannot be initiated.
   */
  private suspend fun transferSessionInternal(
    sessionId: SessionId,
    startComponentRequest: StartComponentRequest,
    deviceFilters: List<DeviceFilter>,
    originatingSessionStateCallbacks: OriginatingSessionStateCallback
  ) {
    val session =
      sessionStorage[sessionId] ?: throw SessionException(SessionException.INVALID_SESSION_ID)
    if (session.getState() != SESSION_CREATED) {
      throw SessionException(SessionException.OPERATION_NOT_ALLOWED)
    }

    val newStartComponentRequest = buildStartComponentRequest(startComponentRequest, sessionId)

    if (!this::launcher.isInitialized) {
      throw SessionException(
        SessionException.OPERATION_NOT_ALLOWED,
        "No ActivityResultCaller registered."
      )
    }

    session.setState(
      SESSION_TRANSFER,
      SessionActionRole.ORIGINATING,
      originatingSessionStateCallbacks,
    )
    session.waitingOnNewParticipants.set(true)

    sessionIdForLauncherMutex.withLock { sessionIdForLauncher = sessionId }
    // TODO: consider setting a timeout while waiting for a response
    launcher.launchDevicePicker(deviceFilters, newStartComponentRequest, session.correlationData)
  }

  /**
   * Called on the originating device with the results from discovery.
   *
   * The `participants` are devices that the user selected from the device picker UI and were
   * subsequently successfully woken up.
   */
  private fun onDiscoveryResult(session: SessionImpl, participants: Collection<Participant>) {
    coroutineScope.launch {
      session.waitingOnNewParticipants.set(false)
      when (session.getState()) {
        SESSION_TRANSFER -> onDiscoveryResultForTransfer(session, participants)
        SESSION_SHARE -> onDiscoveryResultForShare(session, participants)
        else -> {
          Log.e(TAG, "Discovery Result received, but Session is not being Transferred or Shared.")
          throw SessionException(SessionException.INTERNAL_ERROR)
        }
      }
    }
  }

  private suspend fun onDiscoveryResultForTransfer(
    session: SessionImpl,
    participants: Collection<Participant>
  ) {
    // User is only allowed to choose one device in the transfer case.
    if (participants.size != 1) {
      val exception =
        if (participants.isEmpty()) {
          SessionException.NO_DEVICES_SELECTED
        } else {
          SessionException.INTERNAL_ERROR
        }

      session.onTransferFailure(SessionException(exception))
      return
    }

    // Try to connect to the remote device.
    try {
      setUpOriginatingDeviceForParticipant(session, participants.first())
    } catch (e: Exception) {
      Log.e(TAG, "Exception caught initializing transfer communication.", e)
      val exception =
        when (e) {
          is SessionException -> e
          else -> SessionException(SessionException.CONNECTION_FAILURE, cause = e)
        }
      session.onTransferFailure(exception)
      return
    }

    logger.logSessionEvent(
      SessionAction.SESSION_ACTION_TRANSFER_CONNECTED,
      result { resultCode = ResultCode.RESULT_CODE_SUCCESS },
      session.sessionId,
      session.applicationSessionTag,
      (System.currentTimeMillis() - session.actionStartTime!!).toDuration(
        DurationUnit.MILLISECONDS
      ),
      session.correlationData
    )

    session
      .getSessionStateCallback<OriginatingSessionStateCallback>()
      .onConnected(session.sessionId)
  }

  /**
   * Set up connections with `participant`. Called on the originating device with a participant who
   * has already been woken up for a transfer or share.
   *
   * @throws SessionException if we are unable to complete setup.
   */
  private suspend fun setUpOriginatingDeviceForParticipant(
    session: SessionImpl,
    participant: Participant
  ) {
    val internalConnection = setUpOriginatingInternalConnection(session, participant)
    // Open connections on the originating side, to be accepted on the receiving side.
    val applicationConnection =
      participant
        .openConnection(
          createChannelName(session.sessionId, ConnectionType.APPLICATION),
          SESSIONS_CONNECTION_NAMESPACE
        )
        .getOrThrow()

    session.initializeDeviceDataForParticipant(
      participant,
      internalConnection,
      applicationConnection
    )

    // Listen for internal messages from receiving device.
    session.listenForIncomingMessagesForParticipant(participant, ::onNewMessage)
  }

  /**
   * Sets up the receiving side of Sessions for either Transfer or Share.
   *
   * @throws SessionException on errors.
   */
  private suspend fun setUpReceivingSideSession(
    intent: Intent,
    receivingSessionStateCallback: ReceivingSessionStateCallback? = null,
    secondarySessionStateCallback: SecondarySessionStateCallback? = null
  ) {
    val sessionId =
      SessionId.getSessionIdFromIntent(intent)
        ?: throw SessionException(SessionException.INVALID_SESSION_ID)
    val originatingParticipant =
      discovery.getParticipantFromIntent(intent)
        ?: throw SessionException(SessionException.INVALID_PARTICIPANT)
    Log.i(TAG, "Getting SessionHandle for $sessionId")

    if (sessionStorage.containsKey(sessionId)) {
      Log.e(TAG, "SessionId already exists when initiating the receiving side.")
      throw SessionException(SessionException.UNEXPECTED_STATE)
    }

    // Try to accept the connection from the originating side and parse the metadata response.
    val internalConnection: RemoteConnection
    val metadataResponse: SessionMetadataResponse
    try {
      @Suppress("deprecated")
      internalConnection =
        ChannelRemoteConnection.wrap(
          originatingParticipant
            .acceptConnection(
              createChannelName(sessionId, ConnectionType.INTERNAL),
              SESSIONS_CONNECTION_NAMESPACE
            )
            .getOrThrow()
        )

      Log.i(TAG, "Starting metadata exchange with originating side.")

      // Get the information about the session from the originating side.
      internalConnection.send(sessionMetadataRequest { sessionIdField = sessionId.toProto() })

      metadataResponse = SessionMetadataResponse.parseFrom(internalConnection.receiveOneMessage())

      // If we did not get the metadata back that corresponds to the Session we requested, there is
      // a critical failure in our internals.
      require(metadataResponse.metadata.sessionIdField.id == sessionId.id)
    } catch (e: Exception) {
      throw SessionException(SessionException.CONNECTION_FAILURE, cause = e)
    }

    Log.i(TAG, "Metadata response parsed on receiving side: $metadataResponse")

    // Handle quick cancels and unexpected state.
    if (metadataResponse.cancelled)
      throw SessionException(SessionException.SESSION_ACTION_CANCELLED)
    if (metadataResponse.sessionState == SESSION_CREATED)
      throw SessionException(SessionException.UNEXPECTED_STATE)

    val correlationData =
      if (originatingParticipant is ParticipantImpl) {
        CorrelationData.createParentFromChild(originatingParticipant.correlationData)
      } else {
        Log.w(TAG, "Expected to extract correlationData from ParticipantImpl.")
        CorrelationData.createNew()
      }

    // Create Session.
    // Pass `cleanupSession` as a callback because sessions on the receiving side should always get
    // cleaned up after failures or cancels.
    val session =
      createAndInitializeReceivingSideSession(
        metadataResponse,
        receivingSessionStateCallback,
        secondarySessionStateCallback,
        correlationData,
        coroutineScope,
        ::cleanupSession
      )
    sessionStorage[sessionId] = session

    try {
      // Accept the application channel, which should have been opened on the originating side.
      val applicationConnection =
        originatingParticipant
          .acceptConnection(
            createChannelName(sessionId, ConnectionType.APPLICATION),
            SESSIONS_CONNECTION_NAMESPACE
          )
          .getOrThrow()

      session.initializeDeviceDataForParticipant(
        originatingParticipant,
        internalConnection,
        applicationConnection
      )

      // Start listening for cancel messages from the originating side.
      session.listenForIncomingMessagesForParticipant(originatingParticipant, ::onNewMessage)
    } catch (e: Exception) {
      throw when (e) {
        is SessionException -> e
        else -> SessionException(SessionException.CONNECTION_FAILURE, cause = e)
      }
    }
  }

  /**
   * Factory Method to be used on receiving devices once they have parsed the session metadata from
   * the originating side.
   */
  private suspend fun createAndInitializeReceivingSideSession(
    r: SessionMetadataResponse,
    receivingSessionStateCallback: ReceivingSessionStateCallback?,
    secondarySessionStateCallback: SecondarySessionStateCallback?,
    correlationData: CorrelationData,
    coroutineScope: CoroutineScope,
    onActionCancelledForReceivingCallback: (SessionId) -> Unit
  ): SessionImpl {
    val session =
      SessionImpl(
        SessionId(r.metadata.sessionIdField.id),
        ApplicationSessionTag(r.metadata.applicationSessionTagField.tag),
        logger,
        coroutineScope,
        correlationData = correlationData
      )
    session.setState(
      r.sessionState,
      SessionActionRole.RECEIVING,
      newReceivingSessionStateCallback = receivingSessionStateCallback,
      newSecondarySessionStateCallback = secondarySessionStateCallback
    )
    session.setOnActionCancelledCallback(onActionCancelledForReceivingCallback)
    return session
  }

  /**
   * Creates a `ReceivingSession`, which provides a reference of a transferrable session to apps on
   * receiving devices.
   *
   * Should be called exactly once and only on devices which were woken up for transfer actions
   * initiated on the originating device.
   *
   * @throws SessionException if we are unable to return a `ReceivingSession`.
   */
  internal suspend fun getReceivingSession(
    intent: Intent,
    callback: ReceivingSessionStateCallback
  ): ReceivingSession {
    val startTime = System.currentTimeMillis()

    var exception: SessionException? = null
    try {
      setUpReceivingSideSession(intent, callback)
    } catch (e: SessionException) {
      exception = e
    }

    // Get the session ID to access the Session. Set to default for logging errors.
    val sessionId =
      SessionId.getSessionIdFromIntent(intent) ?: SessionId(SESSION_ID_MISSING_FROM_INTENT)
    val session = sessionStorage[sessionId]

    // Log either success or failure before throwing the exception.
    logger.logSessionEvent(
      SessionAction.SESSION_ACTION_TAKE_INITIATED,
      sessionExceptionToResult(exception),
      sessionId,
      session?.applicationSessionTag,
      (System.currentTimeMillis() - startTime).toDuration(DurationUnit.MILLISECONDS),
      session?.correlationData
    )

    if (exception != null) throw exception

    return session!!.createReceivingSession()
  }

  /**
   * Creates a `SecondarySession`, which provides a reference of the shareable session to apps on
   * receiving devices.
   *
   * Should be called exactly once and only on devices which were woken up for share actions
   * initiated on the originating device.
   *
   * @throws SessionException if we are unable to return a `SecondarySession`.
   */
  internal suspend fun createSecondarySession(
    intent: Intent,
    callback: SecondarySessionStateCallback
  ): SecondarySession {
    val startTime = System.currentTimeMillis()

    var exception: SessionException? = null
    try {
      setUpReceivingSideSession(intent, secondarySessionStateCallback = callback)
    } catch (e: SessionException) {
      exception = e
    }

    // Get the session ID to access the Session. Set to default for logging errors.
    val sessionId =
      SessionId.getSessionIdFromIntent(intent) ?: SessionId(SESSION_ID_MISSING_FROM_INTENT)
    val session = sessionStorage[sessionId]

    // Log either success or failure before throwing the exception.
    logger.logSessionEvent(
      SessionAction.SESSION_ACTION_JOIN,
      sessionExceptionToResult(exception),
      sessionId,
      session?.applicationSessionTag,
      (System.currentTimeMillis() - startTime).toDuration(DurationUnit.MILLISECONDS),
      session?.correlationData
    )

    if (exception != null) throw exception

    return session!!.createSecondarySession()
  }

  /**
   * Initiates the share process from the originating device.
   *
   * Opens a DevicePicker UI; launches the calling app on the receiving device(s) (the device(s) the
   * user selected from the UI).
   *
   * @throws a [SessionException] if the share cannot be initiated.
   */
  internal suspend fun shareSession(
    sessionId: SessionId,
    startComponentRequest: StartComponentRequest,
    deviceFilters: List<DeviceFilter>,
    primarySessionStateCallback: PrimarySessionStateCallback
  ): PrimarySession {
    var exception: SessionException? = null
    try {
      shareSessionInternal(
        sessionId,
        startComponentRequest,
        deviceFilters,
        primarySessionStateCallback
      )
    } catch (e: SessionException) {
      exception = e
    }
    val session = sessionStorage[sessionId]

    logger.logSessionEvent(
      SessionAction.SESSION_ACTION_SHARE_INITIATED,
      sessionExceptionToResult(exception),
      sessionId,
      session?.applicationSessionTag,
      null,
      session?.correlationData
    )

    if (exception != null) throw exception

    return session!!.createPrimarySession()
  }

  /**
   * Called by `shareSession` to perform error-checking and share initiation logic. Used to wrap
   * share business logic into one function call for exceptions to be handled internally in one
   * place for logging purposes.
   * @throws a [SessionException] if the share cannot be initiated.
   */
  private suspend fun shareSessionInternal(
    sessionId: SessionId,
    startComponentRequest: StartComponentRequest,
    deviceFilters: List<DeviceFilter>,
    primarySessionStateCallback: PrimarySessionStateCallback
  ) {
    Log.i(TAG, "Share initiated.")

    val session =
      sessionStorage[sessionId] ?: throw SessionException(SessionException.INVALID_SESSION_ID)
    // TODO: Change share states and handle them appropriately.
    if (!session.canShare()) {
      throw SessionException(SessionException.OPERATION_NOT_ALLOWED)
    }

    val newStartComponentRequest = buildStartComponentRequest(startComponentRequest, sessionId)

    if (!this::launcher.isInitialized) {
      throw SessionException(
        SessionException.OPERATION_NOT_ALLOWED,
        "No ActivityResultCaller registered."
      )
    }

    session.setState(
      SESSION_SHARE,
      SessionActionRole.ORIGINATING,
      newPrimarySessionStateCallback = primarySessionStateCallback
    )
    session.waitingOnNewParticipants.set(true)

    sessionIdForLauncherMutex.withLock { sessionIdForLauncher = sessionId }
    launcher.launchDevicePicker(deviceFilters, newStartComponentRequest, session.correlationData)
  }

  private suspend fun onDiscoveryResultForShare(
    session: SessionImpl,
    participants: Collection<Participant>
  ) {
    val callback = session.getSessionStateCallback<PrimarySessionStateCallback>()
    callback.onShareInitiated(session.sessionId, participants.size)
    if (participants.isEmpty()) {
      Log.i(TAG, "No participants selected.")
      return
    }
    setUpOriginatingDeviceForShare(session, participants)
  }

  /**
   * Set up connections with `participants`. Called on the originating device with the list of
   * participants who have already been woken up for a new share request.
   */
  private suspend fun setUpOriginatingDeviceForShare(
    session: SessionImpl,
    participants: Collection<Participant>
  ) {
    for (participant in participants) {
      var result = result { resultCode = ResultCode.RESULT_CODE_SUCCESS }
      try {
        setUpOriginatingDeviceForParticipant(session, participant)
      } catch (e: Exception) {
        Log.e(TAG, "Exception caught starting channel for $participant", e)
        val exception =
          when (e) {
            is SessionException -> e
            else -> SessionException(SessionException.CONNECTION_FAILURE, cause = e)
          }
        // We should always send the app the same instance of SessionParticipant for a given
        // participant, but since we failed to connect to this participant, this is the only time we
        // will send it to the app, so it's ok to construct a new SessionParticipant here.
        session.onShareFailureWithParticipantCallback(
          exception,
          SessionParticipantImpl(participant)
        )

        result = sessionExceptionToResult(exception)
      }

      logger.logSessionEvent(
        SessionAction.SESSION_ACTION_PARTICIPANT_JOINED,
        result,
        session.sessionId,
        session.applicationSessionTag,
        null,
        session.correlationData
      )

      if (result.resultCode == ResultCode.RESULT_CODE_SUCCESS) {
        session.onSecondaryJoinedShare(participant)
      }
    }
  }

  /**
   * Tries to initiate the internal connection for the originating device.
   *
   * @throws [SessionException] if there is an issue creating the connection or initializing.
   */
  private suspend fun setUpOriginatingInternalConnection(
    session: SessionImpl,
    participant: Participant
  ): ChannelRemoteConnection {
    val internalChannel =
      try {
        // Open connection and wait for the receiving side `SessionsManager` to connect back to us.
        @Suppress("deprecated")
        ChannelRemoteConnection.wrap(
          participant
            .openConnection(
              createChannelName(session.sessionId, ConnectionType.INTERNAL),
              SESSIONS_CONNECTION_NAMESPACE
            )
            .getOrThrow()
        )
      } catch (e: Exception) {
        Log.e(TAG, "Unable to start internal communication channel with $participant.", e)
        throw SessionException(SessionException.CONNECTION_FAILURE, cause = e)
      }

    Log.i(TAG, "Attempting metadata exchange on originating side.")

    try {
      SessionMetadataRequest.parseFrom(internalChannel.receiveOneMessage()).also {
        // If we did not get a request for the same Session ID, there is a critical failure in our
        // internals.
        check(it.sessionIdField.id == session.sessionId.id)
      }
    } catch (e: Exception) {
      throw SessionException(SessionException.INTERNAL_ERROR, cause = e)
    }

    val sessionCancelled = session.cancelled.get()

    internalChannel.send(
      sessionMetadataResponse {
        metadata = session.metadata
        sessionState = session.getState()
        cancelled = sessionCancelled
      }
    )

    // If we sent cancelled in the metadata, throw an exception instead of returning the channel,
    // because we don't need to save it or send a cancelled message.
    if (sessionCancelled) throw SessionException(SessionException.SESSION_ACTION_CANCELLED)

    Log.i(TAG, "Metadata response sent to $participant.")
    return internalChannel
  }

  /**
   * Parses messages from Sessions component on another device. Called on both originating and
   * receiving devices for any ongoing session action.
   */
  private suspend fun onNewMessage(
    bytes: ByteArray,
    session: SessionImpl,
    participant: Participant
  ) {
    val message: OnSessionActionRequestMessage
    val sessionId = session.sessionId
    try {
      message = OnSessionActionRequestMessage.parseFrom(bytes)
      require(message.sessionIdField.id == sessionId.id)
    } catch (e: Exception) {
      throw SessionException(SessionException.INTERNAL_ERROR, cause = e)
    }

    // `onSessionActionCompleteRequest` signifies a transfer that has been successfully completed on
    // the receiving side.
    if (message.hasOnSessionActionCompleteRequest()) {
      if (session.getState() == SESSION_TRANSFER) {
        try {
          session.handleSessionTransferComplete(participant, ::cleanupSession)
        } catch (e: Exception) {
          // If we can't complete the transfer, consider it cancelled.
          session.onTransferFailure(
            SessionException(SessionException.SESSION_ACTION_CANCELLED, cause = e)
          )
        }
        return
      }
      Log.e(TAG, "Transfer complete message received, but Session is not being transferred.")
      throw SessionException(SessionException.INTERNAL_ERROR)
    }

    // `onCancelSessionActionRequest` signifies a cancelled action (either share or transfer), which
    // could have been cancelled from either side.
    if (message.hasOnCancelSessionActionRequest()) {
      val cancelReason = message.onCancelSessionActionRequest.reason
      when (session.getState()) {
        SESSION_TRANSFER -> session.handleSessionTransferCancelled(cancelReason)
        SESSION_SHARE -> session.handleSessionShareCancelled(participant, cancelReason)
        else -> throw SessionException(SessionException.INTERNAL_ERROR)
      }
      return
    }
  }
}

@RequiresApi(Build.VERSION_CODES.O)
internal fun createChannelName(sessionId: SessionId, type: ConnectionType): String {
  return "${sessionId.id}:$type"
}

@RequiresApi(Build.VERSION_CODES.O)
internal enum class ConnectionType {
  // Used by Sessions internally to communicate between SessionsManagers across participants.
  INTERNAL,
  // Exposed to application developers to use for their own communication within their application.
  APPLICATION
}

@RequiresApi(Build.VERSION_CODES.O)
internal object SessionCancellationReason {
  const val UNKNOWN = "Unknown session action cancelled."
  const val SESSION_REMOVED_BY_APP = "Session removed on app's request."
  const val PARTICIPANT_REMOVED_BY_APP = "Participant removed from session on app's request."
  const val SHARE_CANCELLED_BY_APP = "Share cancelled on app's request."
  const val TRANSFER_CANCELLED_BY_APP = "Transfer cancelled on app's request."
}

@RequiresApi(Build.VERSION_CODES.O)
internal const val SESSION_ID_MISSING_FROM_INTENT = "SESSION_ID_MISSING_FROM_INTENT"

@RequiresApi(Build.VERSION_CODES.O)
internal suspend fun <T : MessageLite> RemoteConnection.send(message: T) =
  send(message.toByteArray())

/**
 * A wrapper around [RemoteConnection] that receives using a channel, allowing it to receive just
 * one message similar to the old implementation.
 *
 * TODO: Migrate Sessions to use the callback version directly.
 */
@Deprecated("Use the callback version directly")
@RequiresApi(Build.VERSION_CODES.O)
internal class ChannelRemoteConnection
private constructor(
  private val connection: RemoteConnection,
  private val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
) : RemoteConnection by connection {

  private val receiveChannel = Channel<ByteArray>()
  private val receiver =
    object : ConnectionReceiver {
      override fun onMessageReceived(remoteConnection: RemoteConnection, payload: ByteArray) {
        scope.launch { receiveChannel.send(payload) }
      }

      override fun onConnectionClosed(
        remoteConnection: RemoteConnection,
        error: Throwable?,
        reason: String?,
      ) {
        receiveChannel.close(error)
      }
    }

  init {
    require(connection !is ChannelRemoteConnection) {
      "ChannelRemoteConnection should not wrap another ChannelRemoteConnection"
    }
    connection.registerReceiver(receiver)
  }

  override fun registerReceiver(receiver: ConnectionReceiver) {
    throw UnsupportedOperationException(
      "registerReceiver not supported for ChannelRemoteConnection"
    )
  }

  override fun unregisterReceiver(receiver: ConnectionReceiver) {
    throw UnsupportedOperationException(
      "unregisterReceiver not supported for ChannelRemoteConnection"
    )
  }

  override suspend fun close(reason: String?) {
    connection.unregisterReceiver(receiver)
    connection.close(reason)
  }

  suspend fun receiveOneMessage(): ByteArray {
    return receiveChannel.receive()
  }

  companion object {
    fun wrap(
      connection: RemoteConnection,
      scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ): ChannelRemoteConnection =
      connection as? ChannelRemoteConnection ?: ChannelRemoteConnection(connection, scope = scope)
  }
}
