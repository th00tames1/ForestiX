# iPad portrait measurement

The app requests a portrait, full-screen interface. On iPadOS 26 and later,
`PortraitHostingController` owns the actual root and full-screen presentation
controllers and requests `prefersInterfaceOrientationLocked`. Merely restricting
the supported orientations can produce a portrait-shaped compatibility window
that still rotates with the device, so the modern iPad metadata supports all
orientations while the controller explicitly requests portrait and locks it.

On earlier iPadOS versions, the full-screen requirement and portrait-only
controller/app-delegate mask remain in use. iPhone remains portrait-only.

DBH capture and Auto alignment require an active portrait scene. On modern iPad
they also require a screen-sized window and an **effective** orientation lock,
not just a requested lock. Resizing, activation changes and orientation/lock
changes invalidate the depth viewport. Queued frames from an older viewport
cannot re-enable capture. Existing screen-to-depth transforms and the recorded
depth-axis metadata remain authoritative; no depth-content rotation heuristic
is added.

## Physical-device checks before release

Use a LiDAR iPad with the app full screen and system Rotation Lock **off**:

1. Cold-launch while holding the device in landscape. Wait for portrait, then
   turn it left, right and upside down. The app interface must stay portrait.
2. Open quick DBH and cruise DBH, including a rescan. Rotate during manual edge
   adjustment and Auto alignment. The horizontal screen guide must remain
   horizontal; capture must not use a frame from a previous viewport.
3. Close/reopen DBH and run the DBH-to-height chain. Check the floating Back,
   Accept, and nested plot-setup/photo views, not just the initial map.
4. Background/resume while rotating. Measurement must wait for current camera
   alignment and then resume normally.
5. Temporarily leave full screen on iPadOS 26. DBH capture must be refused while
   the window is not screen-sized or the system has not granted a scene lock.
   Returning to full screen must restore portrait lock and fresh measurement.
6. Check a plot deep link both while the app is open and from a cold launch.

The system can deny the modern lock outside its supported full-screen/window
conditions. The app blocks DBH in that case; it does not claim to override the
system's window manager. Host policy tests and compilation do not replace the
physical-device checks above.

References: [Apple orientation-lock API](https://developer.apple.com/documentation/uikit/uiviewcontroller/prefersinterfaceorientationlocked),
[Apple full-screen migration guidance](https://developer.apple.com/documentation/technotes/tn3192-migrating-your-app-from-the-deprecated-uirequiresfullscreen-key).
