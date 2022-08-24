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
import androidx.annotation.RequiresApi

/**
 * Clients of Sessions should implement `PrimarySessionStateCallback` to receive notifications of
 * Session state changes when acting as the Primary for a Shared Session.
 *
 * Provide your implementation in [Sessions.shareSession].
 */
@RequiresApi(Build.VERSION_CODES.O)
interface PrimarySessionStateCallback : SessionStateCallback {
  /**
   * Called when a set of participants is provided with a Share request from this Primary. This
   * method will be called once for each application call to [Sessions.shareSession].
   */
  fun onShareInitiated(sessionId: SessionId, numPotentialParticipants: Int)

  /** Called when a secondary joins the specified session. */
  fun onParticipantJoined(sessionId: SessionId, participant: SessionParticipant)

  /** Called when a secondary leaves the specified session. */
  fun onParticipantDeparted(sessionId: SessionId, participant: SessionParticipant)

  /**
   * Called when a [PrimarySession] has no active secondaries (and no joins in progress). After this
   * call, continued use of the [PrimarySession] will return [SessionException] on all method calls
   * because it is no longer valid.
   */
  fun onPrimarySessionCleanup(sessionId: SessionId)

  /**
   * Called when share cannot complete with a specific participant. Failure reason described by
   * `exception`. Called on the originating device.
   */
  fun onShareFailureWithParticipant(
    sessionId: SessionId,
    exception: SessionException,
    participant: SessionParticipant
  )
}

/**
 * Clients of Sessions should implement `SecondarySessionStateCallback` to receive notifications of
 * Session state changes when acting as the Secondary for a shared Session.
 *
 * Provide your implementation in [Sessions.getSecondarySession].
 */
@RequiresApi(Build.VERSION_CODES.O)
interface SecondarySessionStateCallback : SessionStateCallback {
  /**
   * Called when a [SecondarySession] can no longer be connected to a Primary. Continued use of the
   * [SecondarySession] will return [SessionException] on all method calls because it is no longer
   * valid.
   */
  fun onSecondarySessionCleanup(sessionId: SessionId)
}
