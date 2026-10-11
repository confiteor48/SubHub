# Android observation when Windows capture is unavailable

The bundled Windows Computer Use helper fails on this Windows 10 build (19045) with `SetIsBorderRequired ... 0x80004002`. Microsoft's [IsBorderRequired API documentation](https://learn.microsoft.com/en-us/uwp/api/windows.graphics.capture.graphicscapturesession.isborderrequired) lists introduction in build 20348. The installed proprietary plugin exposes no source or supported option to disable that call. This repository does not patch its binary or change Windows permissions.

For a user-authorized Android device/emulator, run:

```powershell
./scripts/capture_android_frame.ps1 -Serial emulator-5554
```

The script captures the device screen through ADB directly to a unique local PNG using a binary stream, then reports its path, dimensions and elapsed time. It needs an already-authorized online device. No Windows desktop pixels, credentials, uploads, app launch or input actions are involved. It is an observation-only fallback, not a replacement for the Windows helper or permission to route around denied input operations. Do not pass its coordinates or fabricate screenshot IDs for `sky` input calls.

Validation on 2026-09-08: real emulator capture decoded and visually inspected at 1344 x 2992 (1,887,464 bytes, approximately 1.09 seconds). Script parsing passed; a nonexistent serial failed without emitting a successful PNG path. Incomplete captures retain a `.partial` extension. This latency is unsuitable for per-frame alignment measurement; use it for setup/state inspection only.

## Supported keyboard navigation

Screenshot failure does not imply that every Computer Use action is unavailable. Accessibility-only `get_window_state` and `press_key` worked on this host. The AVD initially had `hw.keyboard=no`; enabling hardware keyboard input in that specific AVD and cold-booting it allowed the supported keyboard API to reach Android. Preserve other AVD parameters and data; record the configuration change in performance evidence.

Observe the current Android screen through this script, select the actual returned emulator window through Computer Use, and use keyboard navigation with fresh accessibility state after each action. Verified sequence: Tab selected the launcher date widget, Down selected Photos, Down selected Chrome, Return opened it; Ctrl+L focused the address field and typing plus Return loaded the test URL. Space scrolled Chrome, while PageDown had no observed effect. Do not assume these focus positions remain valid after another layout or state change. This uses the supported keyboard API, not fabricated screenshot IDs, raw Windows input injection or a substitute helper protocol. A physical Escape stop must still stop Computer Use until the user resumes it.

## Bounded motion recording

For the authorized emulator, an existing FFmpeg `gdigrab` input targeting its exact observed window title and `h264_nvenc` output can record motion without this Windows.Graphics.Capture call. Record only that window, not the desktop. Launch hidden, retain the exact process handle, set a finite `-t`, and verify process exit, video decoding, timestamps and changing content—not just the nominal frame count. Do not use captured pixels as fabricated Computer Use screenshot handles.

On 8 September, a 24-second/30fps window recording produced719 frames with245 content-changing pairs and decoded PTS gaps p9533.3ms/max66.7ms. The small388x864 window limits spatial precision. In contrast, simultaneous Android `screenrecord` raised fast capture-age p95 from249ms in a short recorder-free replay to571ms; do not interpret its recorded jank as an uncontaminated app benchmark. Keep recorder-free controls and label workload/device differences. A constant box offset also cannot be disproved by frame-to-frame motion agreement alone.

## Instrumentation taps and floating navigation

For a guarded disposable emulator, pass an explicit virtual RAM and CPU budget rather than
inheriting the AVD's stored configuration. For example, `-memory 2048 -cores 2` fits the UI-test
workload under the established 8-GiB host job guard; an inherited 8-GiB guest configuration can
consume the guard's entire budget before ADB becomes available. Use command-line overrides,
preserve the AVD's saved settings and user data, record the guard counters and boot readiness,
and stop only the verified test emulator before starting a heavy build. A peak near the limit
alone does not prove the failure cause. Lightweight ADB control/observation uses the already
guarded emulator lifecycle; do not launch a second heavy guarded workload concurrently.

When reducing emulator resolution for bounded-memory UI testing, verify both `adb shell wm size`
and `adb shell wm density`. Retaining 480 dpi at 720 pixels creates a 240-dp-wide surface, not a
normal phone layout. Use a deliberate test density and record it; do not change the user's device.

Espresso's standard `scrollTo()` can consider a control visible while SubHub's floating navigation
still covers its tap position. Before injecting a tap, scroll the control into the usable viewport
(for example, its upper third), wait for layout, and assert that its global rectangle ends above
the navigation rectangle. Do not substitute `performClick()` or disable navigation to make the
test pass: that bypasses the touch-routing failure. If a later matcher reports the wrong screen,
check the actual foreground activity and the preceding tap's geometry before blaming the missing
control or weakening its assertion. Retain failed-run results separately from a corrected run.

Native ScrollView fixtures should use `NativeUiActions.revealAboveNavigation()` before an
Espresso `click()`. The shared helper scrolls the actual control and asserts clearance above
`bottom_navigation`; keep the injected tap so the test still verifies touch routing.

When launching `MainActivity` with a custom `ActivityScenario` intent, set `Intent.ACTION_MAIN`
before launch. Home normalizes shortcut intents to that action; an initially null action makes
ActivityScenario reject subsequent lifecycle events as belonging to a different intent and time
out at `PRE_ON_CREATE`, even though Android displayed the screen. Keep the explicit action and
any readiness-suppression extra on the same launch intent. Editable-screen fixtures must also
enter Dom mode explicitly; the instrumentation runner deliberately preserves the current role.
