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

import android.os.Build
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import com.google.android.gms.dtdi.core.DeviceFilter as GmsCoreDeviceFilter

/**
 * A DeviceFilter is a restriction on which devices should be shown in a call to
 * [Discovery.registerForResult].
 *
 * There are no device filters available yet in this version, so you must pass an empty list to
 * [Discovery.registerForResult].
 */
@RequiresApi(Build.VERSION_CODES.O)
sealed interface DeviceFilter {
  /**
   * The possible trust relationship types, indicating what level of trust devices are able to be
   * shown.
   *
   * @hide
   */
  enum class TrustRelationshipType {
    /** The trust relationship type is not specified. */
    UNSPECIFIED,
    /** Filter that removes devices that are not logged in with the same account. */
    MY_DEVICES_ONLY,
    // TODO: Support other types.
  }
  companion object {
    /**
     * Creates a filter that removes devices that do not meet this hardware or software feature
     * requirement.
     *
     * The valid features are defined in
     * [`<uses-feature>`](https://developer.android.com/guide/topics/manifest/uses-feature-element).
     *
     * @hide
     */
    // TODO: Unhide when this feature is implemented
    @JvmStatic @DoNotInline fun featureFilter(name: String): DeviceFilter = FeatureFilter(name)

    /**
     * Creates a filter that removes devices that are not matched with the trust relationship.
     *
     * @hide
     */
    @JvmStatic
    @DoNotInline
    fun trustRelationshipFilter(type: TrustRelationshipType): DeviceFilter =
      TrustRelationshipFilter(type)
  }
}

/**
 * A filter based on Android's
 * [package features](https://developer.android.com/guide/topics/manifest/uses-feature-element).
 */
@RequiresApi(Build.VERSION_CODES.O)
private data class FeatureFilter(val name: String) : DeviceFilter

/** Implementation for [DeviceFilter.trustRelationshipFilter] */
@RequiresApi(Build.VERSION_CODES.O)
private data class TrustRelationshipFilter(val type: DeviceFilter.TrustRelationshipType) :
  DeviceFilter

// TODO: Mark fun as internal after moving tests to //third_party/cross_device_sdk
/** @hide */
@RequiresApi(Build.VERSION_CODES.O)
fun DeviceFilter.toGmsCoreDeviceFilter(): GmsCoreDeviceFilter =
  when (this) {
    is FeatureFilter -> GmsCoreDeviceFilter.Builder().setFeatureFilter(name).build()
    is TrustRelationshipFilter ->
      GmsCoreDeviceFilter.Builder()
        .setTrustRelationshipFilter(type.toGmsCoreTrustRelationshipType())
        .build()
  }

@RequiresApi(Build.VERSION_CODES.O)
internal fun DeviceFilter.TrustRelationshipType.toGmsCoreTrustRelationshipType(): Int =
  when (this) {
    DeviceFilter.TrustRelationshipType.UNSPECIFIED ->
      GmsCoreDeviceFilter.TrustRelationshipType.UNSPECIFIED
    DeviceFilter.TrustRelationshipType.MY_DEVICES_ONLY ->
      GmsCoreDeviceFilter.TrustRelationshipType.MY_DEVICES_ONLY
  }
