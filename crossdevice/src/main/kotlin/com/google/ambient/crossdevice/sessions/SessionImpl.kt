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

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.ambient.crossdevice.Participant
import com.google.ambient.crossdevice.connections.RemoteConnection
import com.google.ambient.crossdevice.logs.proto.ClientLogEnums
import com.google.ambient.crossdevice.logs.proto.ClientLogEnums.ErrorCode
import com.google.ambient.crossdevice.logs.proto.ClientLogEnums.ResultCode
import com.google.ambient.crossdevice.logs.proto.ClientLogProtos.Result
import com.google.ambient.crossdevice.logs.proto.result
import com.google.ambient.crossdevice.sessions.SessionMetadataKt.applicationSessionTag
import com.google.ambient.crossdevice.sessions.SessionProto.OnSessionActionCompleteResponse
import com.google.ambient.crossdevice.sessions.SessionProto.SessionState
import com.google.ambient.crossdevice.sessions.SessionProto.SessionState.State.SESSION_CREATED
import com.google.ambient.crossdevice.sessions.SessionProto.SessionState.State.SESSION_SHARE
import com.google.ambient.crossdevice.sessions.SessionProto.SessionState.State.SESSION_TRANSFER
import com.google.android.gms.dtdi.analytics.CorrelationData
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "SessionImpl"

/**
 * [SessionParticipant] should always be an opaque wrapper around a [Participant], passed to apps so
 * they cannot access the `Participant`. Get the `Participant` from a `SessionParticipant` passed by
 * the app.
 */
@RequiresApi(Build.VERSION_CODES.O)
private fun getParticipantFromSessionParticipant(
  sessionParticipant: SessionParticipant
): Participant {
  return when (sessionParticipant) {
    is SessionParticipantImpl -> sessionParticipant.participant
    else -> throw SessionException(SessionException.INTERNAL_ERROR)
  }
}

/** Data associated with a specific remote device we are connected to. */
private class DeviceSessionData(val sessionParticipant: SessionParticipant) {
  // This is intentionally being stored as a map rather than two individual values for INTERNAL
  // and APPLICATION since we intend to support applications opening additional channels as needed.
  val remoteConnectionsByType: MutableMap<ConnectionType, RemoteConnection> = ConcurrentHashMap()

  // TODO: Avoid storing the SessionRemoteConnection directly.
  var applicationSessionRemoteConnection: SessionRemoteConnection? = null

  var listenForMessages: AtomicBoolean = AtomicBoolean(false)
  var listenForMessagesJob: Job? = null
}

/**
 * The active role of this device in the current session action.
 *
 * The role is set when the `TRANSFER`/`SHARE` state is first set. The role should always be `NONE`
 * for sessions in the `CREATED` state.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal enum class SessionActionRole {
  /** Session is not currently being shared or transferred. */
  NONE,
  /** The session originated on this device. */
  ORIGINATING,
  /** This device is a receiving device for this session action. */
  RECEIVING
}

@RequiresApi(Build.VERSION_CODES.O)
internal fun sessionExceptionToResult(exception: SessionException?): Result {
  if (exception == null) return result { resultCode = ResultCode.RESULT_CODE_SUCCESS }

  return result {
    resultCode = ResultCode.RESULT_CODE_ERROR
    errorCode =
      when (exception.errorCode) {
        SessionException.INTERNAL_ERROR -> ErrorCode.ERROR_CODE_SESSIONS_INTERNAL_ERROR
        SessionException.INVALID_SESSION_ID -> ErrorCode.ERROR_CODE_SESSIONS_INVALID_SESSION
        SessionException.OPERATION_NOT_ALLOWED ->
          ErrorCode.ERROR_CODE_SESSIONS_OPERATION_NOT_ALLOWED
        SessionException.INVALID_PARTICIPANT -> ErrorCode.ERROR_CODE_SESSIONS_INVALID_PARTICIPANT
        SessionException.INTERNAL_CONNECTION_DISCONNECTED ->
          ErrorCode.ERROR_CODE_SESSIONS_INTERNAL_CONNECTION_CLOSED
        SessionException.CONNECTION_FAILURE -> ErrorCode.ERROR_CODE_SESSIONS_CONNECTION_FAILURE
        SessionException.UNEXPECTED_STATE -> ErrorCode.ERROR_CODE_SESSIONS_UNEXPECTED_STATE
        SessionException.SESSION_ACTION_CANCELLED -> ErrorCode.ERROR_CODE_SESSIONS_ACTION_CANCELLED
        SessionException.NO_DEVICES_SELECTED -> ErrorCode.ERROR_CODE_SESSIONS_NO_DEVICES_SELECTED
        SessionException.HANDLE_INVALIDATED -> ErrorCode.ERROR_CODE_SESSIONS_HANDLE_INVALIDATED
        SessionException.INVALID_START_COMPONENT_REQUEST ->
          ErrorCode.ERROR_CODE_SESSIONS_INVALID_START_COMPONENT_REQUEST
        SessionException.SESSIONS_NOT_INITIALIZED -> ErrorCode.ERROR_CODE_SESSIONS_NOT_INITIALIZED
        else -> {
          Log.e(TAG, "Attempt to convert unknown SessionException to Result.")
          ErrorCode.ERROR_CODE_UNKNOWN
        }
      }
  }
}

/**
 * Represents a session tracked by `SessionsManager`. `PrimarySession`, `SecondarySession`, and
 * `TransferrableSessionHandle` should only interact with a Session via their designated interfaces.
 * All internal methods may be called by `SessionsManager`.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal class SessionImpl(
  override val sessionId: SessionId,
  val applicationSessionTag: ApplicationSessionTag?,
  private val logger: SessionsAnalyticsLogger,
  private val coroutineScope: CoroutineScope,
  private var callback: SessionStateCallback? = null,
  private var state: SessionState.State = SESSION_CREATED,
  private var sessionActionRole: SessionActionRole = SessionActionRole.NONE,
  override var sessionDiscoveryEnabled: AtomicBoolean = AtomicBoolean(false),
  /** An ongoing share or transfer for this session has been cancelled. */
  override var cancelled: AtomicBoolean = AtomicBoolean(false),
  /**
   * `activeHandleId` will be set whenever the app should have an active handle (`PrimarySession`,
   * ``SecondarySession`, or `TransferrableSessionHandle`).
   */
  private var activeHandleId: String? = null,
  val correlationData: CorrelationData? = CorrelationData.createNew(),
  var actionStartTime: Long? = null
) : TransferrableSessionInterface, PrimarySessionInterface, SecondarySessionInterface {
  var metadata = sessionMetadata {
    sessionIdField = sessionId { id = sessionId.id }
    if (applicationSessionTag != null) {
      applicationSessionTagField = applicationSessionTag { tag = applicationSessionTag.sessionTag }
    }
  }

  /**
   * `waitingOnNewParticipants` should be true when `transferSession` or `inviteToSession` has been
   * called and we have not yet received the discovery result with new participants. This is used to
   * avoid resetting the state to `CREATED` until all cleanup (including of new participants) is
   * done.
   */
  internal var waitingOnNewParticipants: AtomicBoolean = AtomicBoolean(false)

  private val deviceDataByParticipant: MutableMap<Participant, DeviceSessionData> =
    ConcurrentHashMap()

  private var listenForMessages: AtomicBoolean = AtomicBoolean(false)
  private val stateMutex = Mutex()

  /**
   * `onActionCancelledCallback` should be set whenever `SessionsManager` wants this session to get
   * cleaned up after cancel or failure. It should always be set on the receiving side, and on the
   * originating side only if `SessionsManager.removeSession` was called. This should only be called
   * via `callOnActionCancelledCallback`.
   */
  private var onActionCancelledCallback: (SessionId) -> Unit = {}

  /**
   * Initialize the `DeviceData` for `participant` and add it to the `deviceDataByParticipant` map.
   * If the session action has been cancelled when this function is called, a connection is already
   * open with the participant, so they will be sent a cancel message before their data is cleaned
   * up.
   *
   * @throws SessionException if the participant was not added or was removed from the map due to
   * the session action already having been cancelled.
   */
  internal suspend fun initializeDeviceDataForParticipant(
    participant: Participant,
    internalConnection: ChannelRemoteConnection,
    applicationConnection: RemoteConnection,
  ) {
    if (deviceDataByParticipant.containsKey(participant)) {
      Log.e(TAG, "Attempting to initialize device data for a participant that already exists.")
      throw SessionException(SessionException.UNEXPECTED_STATE)
    }
    Log.i(TAG, "Storing DeviceData for $participant.")

    val sessionParticipant = SessionParticipantImpl(participant)
    val deviceData = DeviceSessionData(sessionParticipant)
    deviceData.remoteConnectionsByType[ConnectionType.INTERNAL] = internalConnection
    deviceData.remoteConnectionsByType[ConnectionType.APPLICATION] = applicationConnection
    deviceData.applicationSessionRemoteConnection =
      SessionRemoteConnectionImpl(applicationConnection, sessionParticipant)
    deviceDataByParticipant[participant] = deviceData

    // If we reach here and the action has been cancelled, the participant needs to be sent a cancel
    // message and then the deviceData should be cleaned up.
    if (cancelled.get()) {
      val cancelReason =
        when (getState()) {
          SESSION_TRANSFER -> SessionCancellationReason.TRANSFER_CANCELLED_BY_APP
          SESSION_SHARE -> SessionCancellationReason.SHARE_CANCELLED_BY_APP
          else -> SessionCancellationReason.UNKNOWN
        }
      sendCancelMessageAndCleanUpParticipant(participant, cancelReason)
      throw SessionException(SessionException.SESSION_ACTION_CANCELLED, cancelReason)
    }
  }

  /**
   * Get the DeviceData for a specific participant. This is expected to be used for the Primary
   * device for Share, where multiple participants are connected.
   *
   * @throws SessionException if the specified `participant` does not exist.
   */
  private fun getDeviceDataForParticipant(participant: Participant): DeviceSessionData =
    deviceDataByParticipant[participant]
      ?: throw SessionException(SessionException.INVALID_PARTICIPANT)

  /**
   * In the cases where we are expecting a single connection (transfer, secondaries of Share), get
   * the device data for that participant.
   *
   * @throws SessionException if there are multiple devices connected.
   */
  private fun getDefaultDeviceData(): DeviceSessionData {
    if (deviceDataByParticipant.size != 1) {
      Log.e(
        TAG,
        "Attempted to getDefaultDeviceData, but the Session is connected to " +
          "${deviceDataByParticipant.size} devices."
      )
      throw SessionException(SessionException.INTERNAL_ERROR)
    }
    return deviceDataByParticipant.values.first()
  }

  /** Helper function for clarity. */
  private fun numParticipants() = deviceDataByParticipant.size

  /** Get the existing [SessionParticipant] associated with a tracked [Participant]. */
  private fun getSessionParticipant(participant: Participant): SessionParticipant {
    return getDeviceDataForParticipant(participant).sessionParticipant
  }

  internal suspend fun getState(): SessionState.State = stateMutex.withLock { state }

  private fun checkForInvalidStateCallbackAccess(
    expectedState: SessionState.State,
    state: SessionState.State,
    accessedCallbackName: String?
  ) {
    // TODO: Consider adding "CANCEL_IN_PROGRESS" state to make this more
    //  readable. Right now, callback access is valid for the expected state OR SESSION_CREATED
    //  because cleanup callbacks are handled during the SESSION_CREATED state for cancel.
    if (!(state == expectedState || state == SESSION_CREATED)) {
      Log.e(TAG, "Invalid access to $accessedCallbackName in state: $state.")
      throw SessionException(SessionException.INTERNAL_ERROR)
    }
  }

  private fun logCallbackGetterErrorState(
    state: SessionState.State,
    sessionActionRole: SessionActionRole,
    providedCallbackName: String?
  ) {
    Log.e(TAG, "Session has: $state, $sessionActionRole but $providedCallbackName were found.")
  }

  internal suspend fun <T> getSessionStateCallback(): T where T : SessionStateCallback =
    stateMutex.withLock {
      val expectedState =
        when (callback!!) {
          is OriginatingSessionStateCallback -> SESSION_TRANSFER
          is ReceivingSessionStateCallback -> SESSION_TRANSFER
          is PrimarySessionStateCallback -> SESSION_SHARE
          is SecondarySessionStateCallback -> SESSION_SHARE
          else -> throw SessionException(SessionException.INTERNAL_ERROR)
        }
      val className = callback?.javaClass?.toString()
      checkForInvalidStateCallbackAccess(expectedState, state, className)
      val stateCallback = callback as? T
      if (stateCallback == null) {
        logCallbackGetterErrorState(state, sessionActionRole, className)
        throw SessionException(SessionException.INTERNAL_ERROR)
      }
      return stateCallback
    }

  /**
   * Updates the State of a Session to be in `SESSION_CREATED`, `SESSION_TRANSFER` or
   * `SESSION_SHARE` mode. This sets the relevant state and transaction role for the Session.
   *
   * If the `newState` is either `SESSION_TRANSFER` or `SESSION_SHARE`, device filters and callbacks
   * need to be provided in order to perform the transfer/share.
   *
   * @throws a [SessionException] on invalid state transition requests.
   */
  internal suspend fun setState(
    newState: SessionState.State,
    newActionRole: SessionActionRole = SessionActionRole.NONE,
    newOriginatingSessionStateCallback: OriginatingSessionStateCallback? = null,
    newReceivingSessionStateCallback: ReceivingSessionStateCallback? = null,
    newPrimarySessionStateCallback: PrimarySessionStateCallback? = null,
    newSecondarySessionStateCallback: SecondarySessionStateCallback? = null
  ) {
    // TODO: Update SessionState vars to be in a single object that is managed by this
    // mutex.
    stateMutex.withLock {
      if (
        (state == SESSION_TRANSFER && newState == SESSION_SHARE) ||
          (state == SESSION_SHARE && newState == SESSION_TRANSFER)
      ) {
        Log.e(TAG, "Invalid state transition from: $state to $newState.")
        throw SessionException(SessionException.INTERNAL_ERROR)
      }
      if (newState == SESSION_CREATED && newActionRole != SessionActionRole.NONE) {
        Log.e(
          TAG,
          "Invalid SessionActionRole for state transition from: $state, $sessionActionRole to " +
            "$newState, $newActionRole."
        )
        throw SessionException(SessionException.INTERNAL_ERROR)
      }

      // When an action is cancelled, we reset the state to `CREATED` after cleaning up existing
      // participants. But if we are still waiting on new participants to join, we should not reset
      // the state until they have been informed of the cancel.
      if (newState == SESSION_CREATED && waitingOnNewParticipants.get()) {
        require(cancelled.get())
        Log.i(
          TAG,
          "Not resetting session state to created because we are waiting on new " +
            "participants who will have to be cleaned up."
        )
        return
      }

      // If the previous state was `CREATED`, we are starting a new share or transfer, so make sure
      // `cancelled` is set to false.
      if (state == SESSION_CREATED) {
        cancelled.set(false)

        // Previous state was `CREATED` means we are starting a new action - capture the start time.
        actionStartTime = System.currentTimeMillis()
      }

      // If we are resetting to `CREATED`, make sure any active handles are invalidated.
      when (newState) {
        SESSION_CREATED -> {
          activeHandleId = null
        }
        // Update relevant callbacks and device filters for transition to TRANSFER or SHARE.
        SESSION_TRANSFER -> {
          when (newActionRole) {
            SessionActionRole.ORIGINATING -> {
              if (newOriginatingSessionStateCallback == null) {
                Log.e(
                  TAG,
                  "Missing OriginatingSessionStateCallback for state transition from: $state, " +
                    "$sessionActionRole to $newState, $newActionRole."
                )
                throw SessionException(SessionException.INTERNAL_ERROR)
              }
              callback = newOriginatingSessionStateCallback
            }
            SessionActionRole.RECEIVING -> {
              if (newReceivingSessionStateCallback == null) {
                Log.e(
                  TAG,
                  "Missing ReceivingSessionStateCallback for state transition from: $state, " +
                    "$sessionActionRole to $newState, $newActionRole."
                )
                throw SessionException(SessionException.INTERNAL_ERROR)
              }
              callback = newReceivingSessionStateCallback
            }
            else -> {
              Log.e(TAG, "actionRole is invalid: $newActionRole")
              throw SessionException(SessionException.INTERNAL_ERROR)
            }
          }
        }
        SESSION_SHARE -> {
          Log.i(TAG, "Updating callbacks for $newState , $sessionActionRole")
          when (newActionRole) {
            SessionActionRole.ORIGINATING -> {
              if (newPrimarySessionStateCallback == null) {
                Log.e(
                  TAG,
                  "Missing PrimarySessionStateCallback for state transition from: $state, " +
                    "$sessionActionRole to $newState, $newActionRole."
                )
                throw SessionException(SessionException.INTERNAL_ERROR)
              }
              Log.i(TAG, "Setting primarySessionStateCallback")
              callback = newPrimarySessionStateCallback
            }
            SessionActionRole.RECEIVING -> {
              if (newSecondarySessionStateCallback == null) {
                Log.e(
                  TAG,
                  "Missing SecondarySessionStateCallback for state transition from: $state, " +
                    "$sessionActionRole to $newState, $newActionRole."
                )
                throw SessionException(SessionException.INTERNAL_ERROR)
              }
              Log.i(TAG, "Setting secondarySessionStateCallback")
              callback = newSecondarySessionStateCallback
            }
            else -> {
              Log.e(TAG, "actionRole is invalid: $newActionRole")
              throw SessionException(SessionException.INTERNAL_ERROR)
            }
          }
        }
        else -> {
          Log.e(TAG, "State is Invalid: $newState")
          throw SessionException(SessionException.INTERNAL_ERROR)
        }
      }
      state = newState
      sessionActionRole = newActionRole
    }
  }

  /** Get the action role for the current session action. */
  private suspend fun getSessionActionRole(): SessionActionRole =
    stateMutex.withLock {
      if (state == SESSION_CREATED) {
        Log.e(TAG, "Unexpected SessionActionRole access for a Session that is in CREATED state.")
      }
      sessionActionRole
    }

  /** Returns true if this session is eligible for sharing with other devices based on the state. */
  internal suspend fun canShare(): Boolean {
    return stateMutex.withLock {
      when {
        sessionActionRole == SessionActionRole.RECEIVING -> false
        (state == SESSION_CREATED || state == SESSION_SHARE) -> true
        else -> false
      }
    }
  }

  /**
   * The `onActionCancelledCallback` should be set by SessionsManager when it wants the session to
   * be cleaned up after being cancelled (basically, when `SessionsManager.removeSession` is called,
   * or in the Session constructor for sessions on the receiving side).
   */
  internal fun setOnActionCancelledCallback(callback: (SessionId) -> Unit) {
    onActionCancelledCallback = callback
  }

  /**
   * Helper function to call the `onActionCancelledCallback`. Checks first if we are waiting on new
   * participants, because we do not want to remove a session and then get discovery results we
   * can't process, since the discovery results will be a list of participants who have been woken
   * up and need to be sent a cancel message.
   */
  private fun callOnActionCancelledCallback() {
    if (waitingOnNewParticipants.get()) {
      Log.i(
        TAG,
        "Not calling onActionCancelledCallback because we are still waiting on discovery" +
          " results, and the new participants will need to be informed of the cancel."
      )
      return
    }
    onActionCancelledCallback(sessionId)
  }

  /** Called for share on originating devices when a secondary joins. */
  internal suspend fun onSecondaryJoinedShare(participant: Participant) {
    require(getState() == SESSION_SHARE)
    require(getSessionActionRole() != SessionActionRole.RECEIVING)

    // Notify the primary of the new participant joining.
    getSessionStateCallback<PrimarySessionStateCallback>()
      .onParticipantJoined(sessionId, getSessionParticipant(participant))
  }

  /** Creates the `OriginatingSession`. */
  internal suspend fun createOriginatingSession(): OriginatingSession {
    createActiveHandleId()
    return OriginatingSessionImpl(this, activeHandleId!!)
  }

  /** Creates the `PrimarySession`. */
  internal suspend fun createPrimarySession(): PrimarySession {
    createActiveHandleId()
    return PrimarySessionImpl(this, activeHandleId!!)
  }

  /**
   * Creates the activeHandleId when SessionsManager is returing a Session Object to the application
   * (Session Objects: `OriginatingSession`, `ReceivingSession`, `PrimarySession`,
   * `SecondarySession`).
   */
  private fun createActiveHandleId() {
    // There should only be one active handle per session at a time.
    check(activeHandleId == null)

    activeHandleId = UUID.randomUUID().toString()
  }

  private fun verifyHandleId(handleId: String?) {
    if ((handleId == null && activeHandleId == null) || handleId != activeHandleId)
      throw SessionException(SessionException.HANDLE_INVALIDATED)
  }

  internal suspend fun createReceivingSession(): ReceivingSession {
    if (getState() != SESSION_TRANSFER || getSessionActionRole() != SessionActionRole.RECEIVING)
      throw SessionException(SessionException.UNEXPECTED_STATE)

    createActiveHandleId()
    return ReceivingSessionImpl(this, activeHandleId!!)
  }

  internal suspend fun createSecondarySession(): SecondarySession {
    if (getState() != SESSION_SHARE || getSessionActionRole() != SessionActionRole.RECEIVING)
      throw SessionException(SessionException.UNEXPECTED_STATE)

    createActiveHandleId()
    return SecondarySessionImpl(this, activeHandleId!!)
  }

  /** Called by `PrimarySessionImpl`. */
  override fun getSecondaryApplicationConnections(
    handleId: String
  ): Map<SessionParticipant, SessionRemoteConnection> {
    if (cancelled.get()) throw SessionException(SessionException.SESSION_ACTION_CANCELLED)
    verifyHandleId(handleId)
    return deviceDataByParticipant
      .map { (participant, deviceData) ->
        deviceData.sessionParticipant to deviceData.applicationSessionRemoteConnection!!
      }
      .toMap()
  }

  /** Called by `PrimarySessionImpl`. */
  override suspend fun removeParticipantFromSession(
    participant: SessionParticipant,
    handleId: String
  ) {
    if (cancelled.get()) throw SessionException(SessionException.SESSION_ACTION_CANCELLED)
    verifyHandleId(handleId)

    sendCancelMessageAndCleanUpParticipant(
      getParticipantFromSessionParticipant(participant),
      SessionCancellationReason.PARTICIPANT_REMOVED_BY_APP
    )

    onParticipantDeparted(participant)
  }

  /** Send the `participant` a cancel message and removes their data from the session. */
  private suspend fun sendCancelMessageAndCleanUpParticipant(
    participant: Participant,
    reason: String
  ) {
    Log.i(TAG, "Removing participant $participant from $sessionId because $reason")
    sendCancelMessageToParticipant(participant, reason)
    cleanupParticipant(participant)
  }

  /** Clean up `participant` by closing all connections and removing the relevant `deviceData`. */
  private suspend fun cleanupParticipant(participant: Participant) {
    stopListeningForMessagesForParticipant(participant)
    for (connection in deviceDataByParticipant[participant]!!.remoteConnectionsByType.values) {
      coroutineScope.launch { connection.close() }
    }
    deviceDataByParticipant.remove(participant)
  }

  /**
   * Returns the application connection for communicating with the originating device. Called by
   * `OriginatingSession`, `ReceivingSession`, or `SecondarySession`.
   */
  override fun getDefaultApplicationRemoteConnection(handleId: String?): SessionRemoteConnection {
    verifyHandleId(handleId)

    val deviceDataByParticipant = getDefaultDeviceData()

    return deviceDataByParticipant.applicationSessionRemoteConnection
      ?: throw SessionException(SessionException.INTERNAL_ERROR)
  }

  /**
   * Called by `ReceivingSession` to complete a transfer.
   *
   * Resets the session state so that the session may begin new actions.
   *
   * @throws a [SessionException] if the transfer cannot be completed for any reason.
   */
  override suspend fun completeTransfer(handleId: String): SessionId {
    var exception: SessionException? = null
    try {
      if (cancelled.get()) throw SessionException(SessionException.SESSION_ACTION_CANCELLED)
      verifyHandleId(handleId)
      if (getState() != SESSION_TRANSFER) {
        throw SessionException(SessionException.OPERATION_NOT_ALLOWED)
      }

      // Send the complete message. An exception will be thrown if the response is not parsed.
      sendAndGetSessionActionCompleteMessages()

      // Close the connections once the originating device has responded to completion requests.
      cleanupAllParticipants()
      setState(SESSION_CREATED)
    } catch (e: SessionException) {
      exception = e
    }

    logger.logSessionEvent(
      ClientLogEnums.SessionAction.SESSION_ACTION_TAKE_COMPLETE,
      sessionExceptionToResult(exception),
      sessionId,
      applicationSessionTag,
      (System.currentTimeMillis() - actionStartTime!!).toDuration(DurationUnit.MILLISECONDS),
      correlationData
    )

    if (exception != null) throw exception
    return sessionId
  }

  /**
   * Sends a message to inform the originating side that the transfer is complete and waits to
   * receive a confirmation from the originating side. Should only be called from the receiving side
   * during a transfer.
   *
   * @throws SessionException if there is an error sending the message or parsing the response.
   */
  private suspend fun sendAndGetSessionActionCompleteMessages() {
    // Transfer is a 1:1 device connection.
    if (numParticipants() != 1) {
      throw SessionException(SessionException.INTERNAL_ERROR)
    }
    // Stop listening for messages before sending the complete message so we can listen for the
    // response on the internal channel.
    stopListeningForMessagesForParticipant(deviceDataByParticipant.keys.first())

    val internalConnection =
      getDefaultDeviceData().remoteConnectionsByType[ConnectionType.INTERNAL]
        ?: throw SessionException(SessionException.INTERNAL_ERROR)
    try {
      internalConnection.send(
        onSessionActionRequestMessage {
          sessionIdField = sessionId.toProto()
          onSessionActionCompleteRequest = onSessionActionCompleteRequest {}
        }
      )
      val onSessionActionCompleteResponse =
        OnSessionActionCompleteResponse.parseFrom(
          (internalConnection as ChannelRemoteConnection).receiveOneMessage()
        )
      require(onSessionActionCompleteResponse.sessionIdField.id == sessionId.id)
    } catch (exception: Exception) {
      throw SessionException(SessionException.INTERNAL_ERROR, cause = exception)
    }
  }

  /**
   * Listens for messages from `participant` on the internal channel. Should be called for all
   * participants on both sides for any session action.
   */
  internal suspend fun listenForIncomingMessagesForParticipant(
    participant: Participant,
    onNewMessageCb: NewMessageCallback
  ) {
    val deviceData =
      deviceDataByParticipant[participant]
        ?: throw SessionException(SessionException.INTERNAL_ERROR)

    if (deviceData.listenForMessages.getAndSet(true)) {
      Log.w(TAG, "Already listening for messages for participant: ${participant.displayName}")
      return
    }

    val session = this
    deviceData.listenForMessagesJob =
      coroutineScope.launch {
        while (deviceData.listenForMessages.get()) {
          try {
            val connection =
              deviceData.remoteConnectionsByType[ConnectionType.INTERNAL] as ChannelRemoteConnection
            onNewMessageCb(connection.receiveOneMessage(), session, participant)
          } catch (exception: Exception) {
            // If it was cancelled and we are no longer listening to messages, don't treat as error
            // since this means that Sessions was the one that cancelled the coroutine.
            if (!deviceData.listenForMessages.get()) {
              Log.i(TAG, "Stopped listening for messages for participant $participant")
              return@launch
            }

            // If we get a SessionException, we did not recognize this message but should keep
            // listening for other messages.
            // TODO: Stop throwing internal exceptions here.
            if (exception is SessionException) {
              Log.i(TAG, "Got non-fatal exception listening for messages: $exception")
              continue
            }

            // Assuming RemoteConnection should handle retry, an exception here means we are closed
            // for good.
            Log.e(
              TAG,
              " Connection failed to receive message; treating failure as Connection closure.",
              exception
            )

            deviceDataByParticipant.remove(participant)

            when (getState()) {
              SESSION_TRANSFER ->
                onTransferFailure(
                  SessionException(
                    SessionException.INTERNAL_CONNECTION_DISCONNECTED,
                    exception.toString()
                  )
                )
              SESSION_SHARE -> {
                when (getSessionActionRole()) {
                  SessionActionRole.ORIGINATING ->
                    onParticipantDeparted(deviceData.sessionParticipant)
                  SessionActionRole.RECEIVING -> onSecondarySessionCleanup()
                  SessionActionRole.NONE -> {
                    // This case should not happen.
                    throw SessionException(SessionException.INTERNAL_ERROR)
                  }
                }
              }
              else -> throw SessionException(SessionException.INTERNAL_ERROR, cause = exception)
            }

            return@launch
          }
        }
      }
  }

  /** Stops listening for messages on the internal channel for `participant`. */
  private fun stopListeningForMessagesForParticipant(participant: Participant) {
    deviceDataByParticipant[participant]?.listenForMessages?.set(false)
    deviceDataByParticipant[participant]?.listenForMessagesJob?.cancel()
  }

  /**
   * Cleans up local session and responds to completion request from receiving device. Called on the
   * originating device.
   *
   * @throws a [SessionException] if the transfer has been cancelled or the response cannot be sent.
   */
  internal suspend fun handleSessionTransferComplete(
    participant: Participant,
    cleanUpCallback: (SessionId) -> Unit
  ) {
    var exception: SessionException? = null
    try {
      if (cancelled.get()) {
        throw SessionException(SessionException.SESSION_ACTION_CANCELLED)
      }
      require(getState() == SESSION_TRANSFER)
      require(getSessionActionRole() == SessionActionRole.ORIGINATING)

      val remoteConnection =
        getDefaultDeviceData().remoteConnectionsByType[ConnectionType.INTERNAL]!!
      remoteConnection.send(
        onSessionActionCompleteResponse { sessionIdField = sessionId.toProto() }
      )
    } catch (e: Exception) {
      Log.w(
        TAG,
        "Exception while sending complete confirmation from originating to receiving device",
        e
      )
      exception =
        when (e) {
          is SessionException -> e
          else -> SessionException(SessionException.INTERNAL_ERROR, cause = e)
        }
    }

    // Log the event before throwing exceptions.
    logger.logSessionEvent(
      ClientLogEnums.SessionAction.SESSION_ACTION_TRANSFER_COMPLETE,
      sessionExceptionToResult(exception),
      sessionId,
      applicationSessionTag,
      (System.currentTimeMillis() - actionStartTime!!).toDuration(DurationUnit.MILLISECONDS),
      correlationData
    )
    if (exception != null) throw exception

    cleanupParticipant(participant)

    activeHandleId = null

    // Call the `onSessionTransferred` callback after removing the session.
    cleanUpCallback(sessionId)
    getSessionStateCallback<OriginatingSessionStateCallback>().onSessionTransferred(sessionId)
  }

  /** Handles clean up after the other side sent a cancel message during a transfer. */
  internal suspend fun handleSessionTransferCancelled(cancelReason: String) {
    Log.i(TAG, "Transfer was cancelled from the other side.")

    // Log that the remote side cancelled.
    val action =
      if (getSessionActionRole() == SessionActionRole.RECEIVING)
        ClientLogEnums.SessionAction.SESSION_ACTION_TAKE_CANCELLED_REMOTE
      else ClientLogEnums.SessionAction.SESSION_ACTION_TRANSFER_CANCELLED_REMOTE
    logger.logSessionEvent(
      action,
      result { resultCode = ResultCode.RESULT_CODE_SUCCESS },
      sessionId,
      applicationSessionTag,
      (System.currentTimeMillis() - actionStartTime!!).toDuration(DurationUnit.MILLISECONDS),
      correlationData
    )

    // `onTransferFailure` will check which side this is and clean up as necessary.
    onTransferFailure(SessionException(SessionException.SESSION_ACTION_CANCELLED, cancelReason))
  }

  /** Handles clean up when a cancel message is received from the other side during a share. */
  internal suspend fun handleSessionShareCancelled(participant: Participant, cancelReason: String) {
    when (getSessionActionRole()) {
      SessionActionRole.ORIGINATING -> handlePrimarySessionShareCancelledForParticipant(participant)
      SessionActionRole.RECEIVING -> handleSecondarySessionShareCancelled()
      // NONE case really shouldn't happen...
      SessionActionRole.NONE -> {
        Log.e(TAG, "Share cancelled, but SessionActionRole is unset.")
        throw SessionException(SessionException.INTERNAL_ERROR)
      }
    }
  }

  /**
   * Handles clean up when a secondary sent a cancel message during a share. Called on the
   * originating side.
   */
  private suspend fun handlePrimarySessionShareCancelledForParticipant(participant: Participant) {
    Log.i(TAG, "$participant cancelled share from receiving side.")

    val sessionParticipant = getSessionParticipant(participant)
    cleanupParticipant(participant)
    onParticipantDeparted(sessionParticipant)
  }

  /** Handles clean up when the originating side cancelled a share. Called on the receiving side. */
  private suspend fun handleSecondarySessionShareCancelled() {
    Log.i(TAG, "Share was cancelled by the originating device.")

    logger.logSessionEvent(
      ClientLogEnums.SessionAction.SESSION_ACTION_SECONDARY_SHARE_CANCELLED_REMOTE,
      result { resultCode = ResultCode.RESULT_CODE_SUCCESS },
      sessionId,
      applicationSessionTag,
      (System.currentTimeMillis() - actionStartTime!!).toDuration(DurationUnit.MILLISECONDS),
      correlationData
    )

    cleanupAllParticipants()
    onSecondarySessionCleanup()
  }

  override suspend fun cancelTransferForHandle(reason: String, handleId: String) {
    try {
      if (cancelled.get()) throw SessionException(SessionException.SESSION_ACTION_CANCELLED)
      verifyHandleId(handleId)
    } catch (exception: SessionException) {

      val action =
        if (getSessionActionRole() == SessionActionRole.RECEIVING)
          ClientLogEnums.SessionAction.SESSION_ACTION_TAKE_CANCELLED_LOCAL
        else ClientLogEnums.SessionAction.SESSION_ACTION_TRANSFER_CANCELLED_LOCAL

      logCancelFailureAndThrow(action, exception)
    }

    cancelTransfer(reason)
  }

  internal suspend fun cancelTransfer(reason: String) {
    Log.i(TAG, "Cancelling transfer for $sessionId because $reason")

    val action =
      if (getSessionActionRole() == SessionActionRole.RECEIVING)
        ClientLogEnums.SessionAction.SESSION_ACTION_TAKE_CANCELLED_LOCAL
      else ClientLogEnums.SessionAction.SESSION_ACTION_TRANSFER_CANCELLED_LOCAL

    try {
      if (getState() != SESSION_TRANSFER) {
        throw SessionException(SessionException.OPERATION_NOT_ALLOWED)
      }
      if (cancelled.get()) {
        Log.w(TAG, "Session action has already been cancelled.")
        throw SessionException(SessionException.SESSION_ACTION_CANCELLED)
      }

      // Proceed with cancelling for cases where it is a valid operation. Catch exceptions during
      // the cancel process to get logged.
      cancel(reason)
    } catch (exception: SessionException) {
      logCancelFailureAndThrow(action, exception)
    }

    logger.logSessionEvent(
      action,
      result { resultCode = ResultCode.RESULT_CODE_SUCCESS },
      sessionId,
      applicationSessionTag,
      (System.currentTimeMillis() - actionStartTime!!).toDuration(DurationUnit.MILLISECONDS),
      correlationData
    )
  }

  /** Called by either [PrimarySession] or [SecondarySession] with a cancel request from the app. */
  override suspend fun cancelShareForHandle(reason: String, handleId: String) {
    try {
      if (cancelled.get()) throw SessionException(SessionException.SESSION_ACTION_CANCELLED)
      verifyHandleId(handleId)
    } catch (e: SessionException) {
      val action =
        if (getSessionActionRole() == SessionActionRole.RECEIVING)
          ClientLogEnums.SessionAction.SESSION_ACTION_SECONDARY_SHARE_CANCELLED_LOCAL
        else ClientLogEnums.SessionAction.SESSION_ACTION_PRIMARY_SHARE_CANCELLED_LOCAL

      logCancelFailureAndThrow(action, e)
    }

    cancelShare(reason)
  }

  internal suspend fun cancelShare(reason: String) {
    Log.i(TAG, "Cancelling share for $sessionId because $reason")

    val actionRole = getSessionActionRole()
    val action =
      if (actionRole == SessionActionRole.RECEIVING)
        ClientLogEnums.SessionAction.SESSION_ACTION_SECONDARY_SHARE_CANCELLED_LOCAL
      else ClientLogEnums.SessionAction.SESSION_ACTION_PRIMARY_SHARE_CANCELLED_LOCAL

    try {
      if (getState() != SESSION_SHARE) {
        throw SessionException(SessionException.OPERATION_NOT_ALLOWED)
      }
      if (cancelled.get()) {
        Log.w(TAG, "Session action has already been cancelled.")
        throw SessionException(SessionException.SESSION_ACTION_CANCELLED)
      }

      cancel(reason)
    } catch (e: SessionException) {
      logCancelFailureAndThrow(action, e)
    }

    logger.logSessionEvent(
      action,
      result { resultCode = ResultCode.RESULT_CODE_SUCCESS },
      sessionId,
      applicationSessionTag,
      (System.currentTimeMillis() - actionStartTime!!).toDuration(DurationUnit.MILLISECONDS),
      correlationData
    )

    when (actionRole) {
      SessionActionRole.RECEIVING ->
        getSessionStateCallback<SecondarySessionStateCallback>()
          .onSecondarySessionCleanup(sessionId)
      SessionActionRole.ORIGINATING ->
        getSessionStateCallback<PrimarySessionStateCallback>().onPrimarySessionCleanup(sessionId)
      // else case means originating side before any participants have joined, so PrimarySession
      // doesn't exist yet and no extra callbacks to call.
      else -> {}
    }
  }

  private fun logCancelFailureAndThrow(
    action: ClientLogEnums.SessionAction,
    exception: SessionException
  ) {
    logger.logSessionEvent(
      action,
      sessionExceptionToResult(exception),
      sessionId,
      applicationSessionTag,
      null,
      correlationData
    )
    throw exception
  }

  /** Called on either side for any session action to cancel that action. */
  private suspend fun cancel(reason: String) {
    cancelled.set(true)
    val jobs =
      deviceDataByParticipant.keys.map { participant ->
        coroutineScope.launch { sendCancelMessageToParticipant(participant, reason) }
      }
    // Wait for all the cancel messages to be sent before cleaning up.
    jobs.joinAll()
    cleanupAllParticipants()

    // Reset the state to `CREATED` to signify that we have finished the cancel process.
    setState(SESSION_CREATED)

    callOnActionCancelledCallback()
  }

  /** Sends a cancel message to the specified participant. Clean up should be done separately. */
  private suspend fun sendCancelMessageToParticipant(
    participant: Participant,
    cancelReason: String
  ) {
    val deviceData =
      deviceDataByParticipant[participant]
        ?: throw SessionException(SessionException.INVALID_PARTICIPANT)
    deviceData.remoteConnectionsByType[ConnectionType.INTERNAL]!!.send(
      onSessionActionRequestMessage {
        sessionIdField = sessionId.toProto()
        onCancelSessionActionRequest = onCancelSessionActionRequest { reason = cancelReason }
      }
    )
  }

  /**
   * Cleans up all connected participants by closing their connections and removing their data.
   * Called when a session action has been cancelled or completed cleaned up.
   */
  private suspend fun cleanupAllParticipants() {
    Log.i(TAG, "Cleaning up remote connections for session: $sessionId")
    val participants = deviceDataByParticipant.keys
    for (participant in participants) cleanupParticipant(participant)
  }

  /**
   * Calls the `onParticipantDeparted` callback and cleans up the `PrimarySession` if necessary.
   * Called on originating devices with an active `PrimarySession` when a participant cancels, is
   * removed by the app, or has a connection failure.
   */
  private suspend fun onParticipantDeparted(participant: SessionParticipant) {
    check(getState() == SESSION_SHARE)
    check(getSessionActionRole() == SessionActionRole.ORIGINATING)

    // This function should be called after cleaning up the participant.
    check(!deviceDataByParticipant.containsKey(getParticipantFromSessionParticipant(participant)))

    getSessionStateCallback<PrimarySessionStateCallback>()
      .onParticipantDeparted(sessionId, participant)

    // Log participant departed with duration being amount of time between share starting and
    // participant leaving. This might include time that the participant is not part of the shared
    // session.
    logger.logSessionEvent(
      ClientLogEnums.SessionAction.SESSION_ACTION_PARTICIPANT_DEPARTED,
      result { resultCode = ResultCode.RESULT_CODE_SUCCESS },
      sessionId,
      applicationSessionTag,
      (System.currentTimeMillis() - actionStartTime!!).toDuration(DurationUnit.MILLISECONDS),
      correlationData
    )
  }

  /**
   * Should only be called when there is an active `PrimarySession` and all secondaries have
   * departed.
   */
  private suspend fun onPrimarySessionCleanup() {
    Log.i(TAG, "onPrimarySessionCleanup")
    check(getState() == SESSION_SHARE)
    check(getSessionActionRole() == SessionActionRole.ORIGINATING)
    check(numParticipants() == 0)
    // The `activeHandleId` will be reset automatically when we set the state to `CREATED`.
    check(activeHandleId != null)

    // Reset the state to `CREATED` because this session can begin a new action.
    setState(SESSION_CREATED)
    getSessionStateCallback<PrimarySessionStateCallback>().onPrimarySessionCleanup(sessionId)

    callOnActionCancelledCallback()
  }

  /**
   * Should only be called when there is an active `SecondarySession` and the connection to the
   * Primary has ended (due to cancel from either side or connection failure).
   */
  private suspend fun onSecondarySessionCleanup() {
    Log.i(TAG, "onSecondarySessionCleanup")
    check(getState() == SESSION_SHARE)
    check(getSessionActionRole() == SessionActionRole.RECEIVING)
    check(numParticipants() == 0)
    check(activeHandleId != null)

    // Reset the `activeHandleId` in case the app keeps holding onto the `SecondarySession`, but
    // don't reset the state to `CREATED`, because that state implies the session can begin a new
    // action.
    activeHandleId = null
    getSessionStateCallback<SecondarySessionStateCallback>().onSecondarySessionCleanup(sessionId)

    callOnActionCancelledCallback()
  }

  /**
   * Calls the `onShareFailureWithParticipant` callback if necessary. Called from the
   * `SessionsManager.inviteToSession` flow if a participant was selected to share with by the user
   * but the connection failed. We may still be trying to connect with other participants, so the
   * callback is called if necessary but no other cleanup.
   */
  internal suspend fun onShareFailureWithParticipantCallback(
    exception: SessionException,
    participant: SessionParticipant
  ) {
    // Only call the share failure callback if the share has not been purposely cancelled from this
    // side.
    if (!cancelled.get()) {
      getSessionStateCallback<PrimarySessionStateCallback>()
        .onShareFailureWithParticipant(sessionId, exception, participant)
    }
  }

  /**
   * Calls the `onTransferFailure` callback if necessary and handles any needed cleanup. Called on
   * either originating or receiving side when a transfer fails or is cancelled.
   */
  internal suspend fun onTransferFailure(exception: SessionException) {
    // Only call the transfer failure callback if the transfer has not been purposely cancelled from
    // this side.
    activeHandleId = null
    if (!cancelled.get()) {
      when (getSessionActionRole()) {
        SessionActionRole.ORIGINATING ->
          getSessionStateCallback<OriginatingSessionStateCallback>()
            .onTransferFailure(sessionId, exception)
        SessionActionRole.RECEIVING ->
          getSessionStateCallback<ReceivingSessionStateCallback>()
            .onTransferFailure(sessionId, exception)
        else -> Log.e(TAG, "onTransferFailure called when SessionActionRole is not set.")
      }
    }

    // Log with duration being time from action initiated to time of failure.
    val action =
      if (getSessionActionRole() == SessionActionRole.RECEIVING)
        ClientLogEnums.SessionAction.SESSION_ACTION_TAKE_FAILURE
      else ClientLogEnums.SessionAction.SESSION_ACTION_TRANSFER_FAILURE
    logger.logSessionEvent(
      action,
      sessionExceptionToResult(exception),
      sessionId,
      applicationSessionTag,
      (System.currentTimeMillis() - actionStartTime!!).toDuration(DurationUnit.MILLISECONDS),
      correlationData
    )

    // Clean up any open connections if necessary.
    cleanupAllParticipants()

    // On originating side, the session can start a new action from `CREATED` state.
    if (getSessionActionRole() == SessionActionRole.ORIGINATING) {
      setState(SESSION_CREATED)
    }

    callOnActionCancelledCallback()
  }
}

private typealias NewMessageCallback =
  suspend (bytes: ByteArray, session: SessionImpl, participant: Participant) -> Unit
