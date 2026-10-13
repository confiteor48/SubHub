# Architecture map

This comparison map is statically derived from the Beta Blocker Android 1.67 feature source. JADX paths are readable navigation aids; APKTool smali is authoritative when the reconstructions disagree.

## Runtime pipeline

```text
MainActivity
  |-- MediaProjection consent -> ScreenCaptureService
  |                              -> ScreenCaptureManager
  |-- Shared app inclusion -> AppModeManager
  |-- Accessibility events   -> ScreenshotAccessibilityService
                                 |-- all external apps, or
                                 |-- selected launcher packages
                                  |
                                  v
                 DetectionEngine + native-text classifier
                    ONNX model + Accessibility nodes
                                  |
                                  v
                           DetectionFusion
                                  |
                                  v
                            ObjectTracker
                                  |
                                  v
              OverlayController / CensorRenderer / ReverseCensorView
```

`DetectionEngine` accepts screen bitmaps, runs an 18-class detector, applies confidence filtering/NMS, and produces bounding boxes. In Accessibility App Mode, a bounded native-node classifier can also identify visible explicit text without OCR. It normalizes common obfuscation, scores explicit/D/s/solicitation signals by sensitivity, ignores aggregate container descriptions when precise descendant text exists, and projects matched spans onto line-height regions instead of blindly censoring a whole post card. `DetectionFusion` keeps a precise child text line when a coarse parent overlaps it, while still joining genuinely adjacent matched lines before the tracker; the same stable post is therefore censored and counted once rather than once per screenshot. Rendering is split between system overlays and image/export rendering. Capture is deliberately single-flight; the performance preset bounds ONNX intra-op concurrency to one through four threads instead of allowing overlapping screenshot/inference jobs to queue stale frames.

The clean source implements both capture branches as ordinary Java source under `app/src/main/java/com/subhub/app`. Android's own consent dialog authorizes every MediaProjection session, while Android's accessibility settings control the alternate screenshot service. Export rendering and reverse censoring are also maintained source. The app does not silently grant or retain platform capture authority.

`AppModeManager` persists the armed state and one included-package set consumed by every globally enabled service feature. The exact armed/disarmed state survives reboot. Existing feature assignments migrate to their union; the former all-app setting becomes a snapshot of installed launcher packages. New installations start with an empty set. Adding or removing an app does not change feature enablement, allowance overrides, or recorded usage. `LegacyAppSelection` contains the one-time migration, and retired mode/assignment keys are removed afterward. `ScreenshotAccessibilityService` consumes only window-state package transitions. It prewarms the local model once while capture remains asleep, tags every asynchronous frame with a capture epoch, and rejects a result after the foreground app changes. Leaving a watched package cancels screenshot scheduling, immediately removes every censor/popup window, and releases retained frame pixels; the Accessibility binding stays available for the next package transition. Input methods and transient Android-owned windows—including notifications, the shade, volume UI, and permission sheets—are ignored so they cannot replace the foreground app and strand recognition in a suspended state.

Source-dependent effects transfer the already-owned capture bitmap to the overlay rather than copying another full-screen frame. `CustomImagePool` decodes a preset-bounded image set on its own loader thread, precomputes 129 center-crop aspect buckets per image, and keeps deterministic track-to-image assignments. The live draw path therefore selects prepared geometry and issues one bitmap draw; it performs no image decode or crop calculation per frame.

`AppTimerManager` is a separate, opt-in policy for the shared included-package set. Limits displays recorded usage, a combined budget and distinct app allowances. Metadata loads through `AsyncUiScope`; retained rows preserve focused drafts and serial lifecycle checks prevent a delayed save after Dom access is lost. The display uses the smaller remaining active budget. The stored aggregate includes earlier included-app usage even after that app leaves the current list. It stores per-app and combined daily foreground milliseconds under a local calendar-day key. The Accessibility service accounts elapsed time on accepted foreground transitions and a one-second boundary tick, independently of detector readiness and recognition mode. When either configured budget is exhausted, recognition is suspended if needed and Android's supported `GLOBAL_ACTION_HOME` returns the watched app to Home. Unwatched apps and disabled limits neither accrue nor enforce timer usage.

`BootReceiver` is non-exported and performs no capture. Normal boot preserves the last armed/disarmed state. An active service lock or Hardcore Mode forces the persisted App Mode state back to armed; a prior MediaProjection session can only produce a notification that returns to the app for fresh Android approval. The notification Disarm action clears automatic recognition and pending session intent only when service is not locked.

## Replacement evidence boundary (qualification only)

The default services still use the pipeline above. The replacement `CensorEngine` and
`SceneEngine` own a separate, privately qualified Accessibility runtime; this is not default
activation or proof of a physical-device speed improvement.

Accepted inputs can issue immutable `EvidenceReceipt` objects containing canonical `long`
identities and source-local positive geometry after association, merging, negative replay and
capacity retirement. Each live identity stores at most five distinct producer-operation
witnesses. A receipt is a source-state inventory across lanes: `triggerLane` identifies the
accepted input that prompted it, while `positiveInput` distinguishes an empty triggering lane
from its surviving inventory and each subject retains its own `evidenceLane`. An empty
QUALITY input at the same source therefore need not erase a retained FAST positive. FAST and
QUALITY from one image request contribute once; a native witness requires
its admitted native request identity. Old completions, redraws, display-rate motion estimates,
style changes and retained masks do not add fresh witnesses. Five-source consumption eligibility
is independent of the two-QUALITY confirmation used for retention. Complete OCR replacement
does not establish identity continuity between its source frames.

`claimEvidence` atomically validates the issuing owner, current protection scope, still-live
canonical identities, unchanged evidence record/region, current visibility, negative evidence and finite lifetime before consuming
eligible identities once. Merges union bounded witnesses and preserve consumption; a receipt
naming a removed alias is rejected, not resolved through historical aliases. This is at-most-once
evidence consumption, not a statistics/payment transaction or proof that two prior identities
were one physical subject. A failed downstream write must not trigger speculative recharging.

The privately qualified product bridge forwards those receipts through its actual host/controller.
It admits source metadata on main and persists on a separate worker; each handoff retains at most
one active item and one latest pending item. Captured/current Lab lineage, qualified window,
recognition configuration and an opaque active statistics-session stamp gate the original claim.
Appearance edits retain source identity; a real session replacement starts a new source scope,
even when its reporting timestamp is unchanged. The original claim has one downstream delivery
to its captured session, not a persisted track-ID or alias ledger.

Stats records only the claimed count after an acknowledged preference commit and returns an
immutable transaction-time snapshot. Money Rules consumes that exact count with its existing
enable rules, batch remainder and caps; rejected calls differ from acknowledged zero-cent progress.
Tribute from an already-admitted old source can contribute to lifetime totals but never to a newly
armed session. The all-categories milestone still depends on enabled detector configuration, not
the accepted subject label. Counting does not acquire a new Dom-mode suppression policy.

Lab guards linearize short authority admission only. Disk, wallet, achievement and UI work run
outside them, and Stats releases its lock before downstream work to preserve wallet-to-Stats lock
order. Already-admitted synchronous writes may finish after withdrawal against their captured
context; later phases reject stale authority. Failed or ambiguous writes quarantine accounting
without retry or stopping censor presentation. The process counter remains coherent with observed
preference memory even after a failed disk acknowledgement; that is not proof of durability.
Achievement persistence retains its existing asynchronous semantics. These separate stores do not
provide cross-store crash atomicity or process-death replay guarantees.

Dwell and popup placement have a separate metadata-only `viewportCoverage` contract in the
replacement owner and its actual Accessibility host/controller. It distinguishes withdrawn,
unobserved, expired and no-eligible-region states from available detected geometry. No state
proves whole-viewport absence, detector completeness or compositor arrival. Supported members
retain their original evidence and geometry sources; held and estimated poses are ineligible.
A new acquired image invalidates an old receipt even before inference or UI delivery, and a
native content-generation change excludes the old read immediately. Queries borrow no pixels,
start no work and renew no source age. The earliest source-local first-invalid deadline fences
the whole receipt even if an expiry callback is late. The qualified controller clips physical
display coordinates to the actual application window once, without another origin or scale.

Detected geometry is distinct from ordinary paint/tap interiors: reverse paint has no billable
members but does not erase detected regions, while a cold reverse background invents no subject.
Presentation-delivery rejection does not erase independently constructed detection metadata.
The privately qualified product consumes this metadata without rebuilding legacy tracker aliases.
`ViewportDwellEpisode` retains one viewport start, spent state and opaque pending ticket; detector
identity churn and empty gaps retain the existing elapsed-time policy. Only an original-owner,
current-source accepted observation advances dwell. A complete empty FAST result excludes missed
FAST members even if their supported geometry remains retained; a regional QUALITY inspection
excludes only its qualified contradicted members. Current unexpired native and uncontradicted
QUALITY/OCR positives may remain eligible without promoting redraws into new producer reads.
Empty regional QUALITY inputs cannot borrow a same-source FAST inventory as positive input.
Scroll, real scope/window/session transitions and stop revoke the episode; appearance edits do not.
Scroll also rotates an opaque dwell-observation authority in the original owner before resetting
the episode, so a queued pre-scroll receipt cannot seed its successor with an old timestamp.
The same receipt's independent geometry and detection-count claims are not revoked by this barrier.
Elapsed duration uses accepted-observation monotonic time, separate from ledger calendar dates.

One bounded pending dwell ticket reaches the same serial accounting worker. Original observation,
coverage, normal Lab lineage, configuration, window, episode and statistics-session stamp are
revalidated at admission. A definitely rejected queued request releases without charge; an admitted
write is never replayed. `recordSourceDwellResult` acknowledges one `CENSORED_DWELL` event with the
existing rules/caps and captured-session tribute semantics, not Every-N detection batching.
Ambiguous acknowledgement quarantines accounting without stopping censor geometry or popup hints.
Already-admitted writes can finish their captured context but cannot tribute a successor session.

Popup cover/avoid placement queries supported clipped member interiors at the actual placement
point. Its scoped supplier holds no persistent region copy, pixels or capture/read demand. Old
bindings cannot clear a successor; stop releases the binding so other capture-mode hint writers
remain independent. Hint presence never changes popup participation and does not require a Stats
session. Reverse uses detected subjects for both consumers, not its source-free background paint.
Availability is still not physical/compositor coverage or glyph-exact proof.

Default activation and legacy retirement still require product parity,
supported-platform checks and real-device long-scroll/reversal, detection-quality and speed
acceptance.

## High-value code areas

| Area | Readable JADX path | Rebuildable APKTool path |
| --- | --- | --- |
| App shell | `jadx/sources/com/betablocker/lite/MainActivity.java` | `apktool/smali/com/betablocker/lite/MainActivity.smali` |
| MediaProjection service | `jadx/.../service/ScreenCaptureService.java` | `apktool/smali/.../service/ScreenCaptureService.smali` |
| Accessibility capture | `jadx/.../service/ScreenshotAccessibilityService.java` | `apktool/smali/.../service/ScreenshotAccessibilityService.smali` |
| ONNX inference | `jadx/.../detection/DetectionEngine.java` | `apktool/smali/.../detection/DetectionEngine.smali` |
| Class/category mapping | `jadx/.../detection/NudeNetClasses.java` | `apktool/smali/.../detection/NudeNetClasses.smali` |
| Tracking | `jadx/.../detection/ObjectTracker.java` | `apktool/smali/.../detection/ObjectTracker.smali` |
| Overlay rendering | `jadx/.../overlay/OverlayController.java` | `apktool/smali/.../overlay/OverlayController.smali` |
| Export rendering | `jadx/.../capture/CensorRenderer.java` | `apktool/smali/.../capture/CensorRenderer.smali` |
| Settings packs | `jadx/.../pack/PackManager.java` | `apktool/smali/.../pack/PackManager.smali` |
| Local statistics | `jadx/.../stats/StatsRepository.java` | `apktool/smali/.../stats/StatsRepository.smali` |

The private roots shown in the table are relative to `C:\Users\user\Code\SubHub-private`.

## Bundled inference assets

- `assets/320n.onnx`: approximately 11.6 MiB
- `assets/320n_fp16.onnx`: approximately 5.8 MiB
- ONNX Runtime JNI libraries: arm64-v8a and x86_64
- 18 detector outputs cover exposed/covered genitalia, breasts, buttocks, anus, torso, feet, armpits, and male/female faces

## Capture and platform surface

The purchased manifest requests MediaProjection foreground-service, overlay, notification, Internet, and battery-optimization permissions. In the clean source, the Accessibility service is exported so Android's system process can bind it, but remains guarded by the platform signature-only `BIND_ACCESSIBILITY_SERVICE` permission. The maintained minimum SDK is 26 and target/compile SDK is 35; the purchased artifact encoded target/compile SDK 36.

## Network behavior

No Retrofit-style product backend or remote license API was found in the application package. Network use is limited to explicit external links, support destinations, and the optional PayPal Wallet integration.

The app also contains a diagnostics HTTP server on TCP port 8765. It constructs `ServerSocket(8765)`, exposes `/`, `/data`, and `/reset`, and has no apparent authentication. Because the socket is not explicitly bound to loopback, treat it as LAN-exposed whenever started. A future maintained implementation should remove it from release builds or bind it to loopback with explicit opt-in and authentication.

The clean source reconstruction intentionally omits that diagnostics server. The purchased APK's own app code did not contain a billing, premium, entitlement, or remote-license gate in the audited DEX trees. The maintained source adds an original, optional PayPal settlement feature whose detector events and bounded ledger remain on-device.

## Money Rules and payment boundary

`PenanceManager` is the retained internal class name for the user-facing Money Rules feature. It stores opt-in rules, capped entries, correction-window state, settlement state, payment history, and the remainder toward an Every-N threshold in private app preferences. Both capture services forward only newly confirmed stable tracker IDs; repeated frames and the lifetime Blocks statistic do not backfill a rule. Editing unrelated costs or caps retains threshold progress, while changing Every-N resets it. Daily and weekly caps may reduce an otherwise eligible event to zero; the UI now reports that state explicitly while Stats continues counting the detection. The UI can remove a false positive during its correction window or clear every unpaid entry at any time.

`PayPalOrdersClient` selects only one of two compiled origins: `api-m.sandbox.paypal.com` or `api-m.paypal.com`. The Dom-only environment control is an authorization boundary: changing it clears credentials and saved-wallet state and cancels any active checkout. Wallet → PayPal in Dom mode accepts the matching Client ID and secret, encrypts them with an Android Keystore AES-GCM key, and never compiles credentials into the APK. The app creates an Orders v2 order, opens PayPal approval, and captures only when the active boundary, local settlement ID, EUR amount, PayPal order, and returned capture all match. The official Magnes collector supplies a transaction-scoped client metadata ID under the same selected environment.

Saved-wallet authorization uses a no-charge Payment Method Tokens v3 setup token. `WalletConnectionController` persists the pending setup token, opens PayPal's approval page, and on every resume reads the server-side token state before exchanging an approved setup token for a permanent payment token. This callback-independent path handles PayPal's HTTPS fallback completion page; the Android custom URI remains an optional fast path. Permanent payment-token and customer IDs are accepted only when both are returned, encrypted, and bound to the environment and Client ID. Vault capability errors become unavailable. An optional PayPal.Me or PayPal-hosted link remains available without API credentials. Because this design intentionally runs the merchant client on the phone, Keystore protects secrets at rest but does not provide the isolation or webhook reachability of a separate merchant service.

## JADX limitations

JADX emitted method-level errors in application code including `MainActivity`, `DetectionEngine`, both capture services, `CensorRenderer`, `ScreenCaptureManager`, `CensorBoxView`, pack parsing/management, and popup management. The Java files remain useful around those failures, but edits to affected methods must be derived from their smali implementation.

## App shell and language surface

SubHub now has one product-wide Dom/Sub presentation boundary. Sub mode is the
default after process start and shows one aggregate dashboard containing only
enabled modules and their active state. It exposes Start/Stop Protection but no
bottom navigation, configuration links, or editable controls. Dom mode requires
the controller PIN and restores the full feature-aware navigation and settings
until the user explicitly returns to Sub mode or the process ends.

This boundary is separate from Android permissions and the optional service
lock. It is an app-local presentation and configuration boundary, not a device
security guarantee. The Sub dashboard uses project-owned layouts, art, icons,
copy, and a darker asymmetrical card system rather than reproducing the purchased
reference app's screen hierarchy.

`MainActivity` owns a non-blocking first-run card and two dynamic launcher shortcuts routed through its exported entry point. `HelpActivity` owns current permission status, one-step overlay/notification repair, the accessibility-settings handoff, language selection, and thirteen source-authored expandable guides. `LocaleHelper` persists the recovered 11-choice language set through AppCompat per-app locales. Navigation, onboarding, permission repair, and Help section labels have localized resources; untranslated detail deliberately uses Android's English resource fallback.

The maintained header and Help surfaces use the project-owned gothic header and demoness guardian art. They do not depend on the purchased APK's decorative assets.

## Service lock

`CommitmentManager` is the internal compatibility name for the private service-lock state. The selected duration can be 1 hour, 24 hours, 7 days, 30 days, or Permanent. Home and Settings duration controls show the active lock, with the countdown control hidden for Permanent. The service lock no longer has a separate Settings entry; Dom release remains available through the service controls. While active, protected settings remain behind the controller boundary. Sub mode cannot stop service from Home or either notification action; Dom mode can release it after the control PIN unlocks the session.

The service lock does not activate Device Admin. Starting service immediately arms App Mode, reboot re-arms it when Android has already enabled Accessibility, and an unexpected MediaProjection end falls back to the persistent armed state. Android uninstall and data clearing remain outside the app unless the separate Hardcore option is enabled.

## Device Admin and restart boundary

`HardcoreModeReceiver` is an optional, Dom-only legacy Device Admin surface used solely for Android's supported deactivate-before-uninstall friction. Its metadata contains an empty `uses-policies` set: SubHub requests no wipe, password, camera, force-lock, or login-monitoring capability. Activation runs through Android's own approval UI, Android Settings can revoke it at any time, and revocation clears the local Hardcore preference.

Device Admin is not treated as a keepalive mechanism, foreground-app signal, or capture grant. When active, `HardcoreModeManager` restores the existing Accessibility App Mode armed intent at boot. Modern Android still requires a new MediaProjection token for every capture session and prevents target-35 apps from starting a MediaProjection foreground service from `BOOT_COMPLETED`.

The maintained design therefore separates restartable intent from non-restartable capture authority: Accessibility app mode may resume because the user enabled that service in Android settings, while whole-screen MediaProjection resumes only after the user taps the visible notification and approves Android's system dialog again.

## Recommended next phase

Use the clean source tree for maintained changes. Complete broad emulator and physical Android 15/16 validation, including private visual corpora and long-running performance, before treating the reconstruction as release-ready.
