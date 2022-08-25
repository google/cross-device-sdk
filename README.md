# Cross device SDK

First announced during the Google I/O ‘22 Multi-device development session, our
Cross device SDK allows developers to build rich multi-device experiences with a
simple and intuitive set of APIs. This SDK abstracts away the intricacies
involved with working with device discovery, authentication, and connection
protocols, allowing you to focus on what matters most—building delightful user
experiences and connecting these experiences across a variety of form factors
and platforms.

## What’s in Developer Preview

This initial release contains a set of rich APIs centered around the core
functionality of **Device discovery**, **Secure connections**, and
**Multi-device Sessions**.

1.  **Device discovery**: Easily find nearby devices, authorize peer-to-peer
    communication, and start the target application on receiving devices.
1.  **Secure connections**: Enable encrypted, low-latency bi-directional data
    sharing between authorized devices.
1.  **Multi-device Sessions**: Enable transferring or extending an application’s
    user experience across multiple devices.

In turn, this will allow you to build compelling cross-device experiences by
enabling and simplifying the following use cases:

*   Discovering and authorizing communication with nearby devices.
*   Sharing an app’s current state with the same app on another device.
*   Starting the app on a secondary device without having to keep the app
    running in background.
*   Establishing secure connections for devices to communicate with each other.
*   Enabling task handoff where the user starts a task on one device, and can
    easily continue on another device.

## Under The Hood

The Cross device SDK provides a software abstraction layer that handles all
aspects of cross-device connectivity, leveraging wireless technologies such as
Bluetooth, Wi-Fi, and Ultra-wide band; our SDK does all the heavy-lifting under
the hood, offering you a modular,connectivity-agnostic API that supports
bi-directional communication between devices and is backward compatible to
Android 8. In addition, apps will not have to declare or request Runtime
Permissions for any of the underlying connectivity protocols used (such as
`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `ACCESS_FINE_LOCATION`, etc.), and the
user can allow apps to connect to only the device(s) they have selected.

## Getting started with Developer Preview

Head over to our
[developer guide](https://developer.android.com/guide/topics/connectivity/cross-device-sdk/overview)
to get started and try out the Developer Preview of the Cross device SDK for
Android. Make sure to check out our Rock Paper Scissor sample app
([Kotlin](https://github.com/android/connectivity-samples/tree/main/CrossDeviceRockPaperScissorsKotlin)
and
[Java](https://github.com/android/connectivity-samples/tree/main/CrossDeviceRockPaperScissors))
on GitHub for a demonstration on how to work with the various APIs and our
Google I/O ‘22
[Multi-device development session](https://www.youtube.com/watch?v=H6UxTnghkMw)
for a general overview of the SDK.
