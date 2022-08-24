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
import com.google.ambient.crossdevice.Participant

/**
 * Represents an individual device that is participating in a Session.
 *
 * This [SessionParticipant] is expected to be passed into the Session APIs to identify the desired
 * participant to interact with. [SessionParticipants][SessionParticipant] are not stable across
 * sessions. If the user starts another session by transferring to or sharing with the same
 * receiving device, the resulting [SessionParticipant] instance will still be different.
 */
@RequiresApi(Build.VERSION_CODES.O)
sealed interface SessionParticipant {
  val displayName: CharSequence
}

@RequiresApi(Build.VERSION_CODES.O)
internal class SessionParticipantImpl(val participant: Participant) : SessionParticipant {
  override val displayName = participant.displayName
}
