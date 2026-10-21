# SubHub agent workflow

These repository rules apply to every automated or human-assisted change.

## Branch and commit discipline

- `master` is the release branch. Keep it buildable; use a topic branch and pull request for work that is not ready to ship.
- Keep commits narrowly reviewable and start imperative subjects with one of: `[feat]`, `[fix]`, `[perf]`, `[refactor]`, `[docs]`, `[test]`, `[build]`, `[ci]`, `[chore]`, or `[revert]`.
- Commit messages must say what changed and why. Record user-visible behavior, migration or compatibility concerns, and the checks run in the commit body when the subject alone is not enough.
- Release changelogs are generated from commit history. Every release commit must use a supported `[feat]`, `[fix]`, `[perf]`, `[refactor]`, `[docs]`, `[test]`, `[build]`, `[ci]`, `[chore]`, `[revert]` subject and describe the user-visible result clearly. Put one concise release-worthy summary sentence in the first body paragraph; keep checks in a later `Checks:` paragraph and use real line breaks rather than literal `\n` text.
- Do not commit credentials, signing keys, local properties, purchased APKs, decompiled trees, captures, or generated build outputs.

## Versioning

`version.properties` is the only release-version source:

- `VERSION_NAME` uses semantic versioning.
- `VERSION_CODE` is a positive Android integer and must increase for every released APK.
- Public CI builds derive their Android code as `VERSION_CODE * 100000 + release workflow run_number`.
  Stable and development releases share that sequence; a later stable build can replace a dev build
  of the same source version. Manual private replacement-engine candidates use that same sequence
  for in-place signed updates, without publishing a release or adding a dev suffix. Local builds
  and other private candidates retain the unexpanded source code. Keep the same `release.yml`
  workflow sequence; fail closed before run 100000 or Android code overflow.
- Any stable release-bound change must update both source values in the same commit. Automatic dev
  builds keep the source version and derive a unique name/code from the shared CI sequence.
  The helper performs the safe increment:

  `python scripts/release_version.py --set-version 0.2.0`

- Run `python scripts/release_version.py` after editing the file.
- A release tag must be exactly `v<VERSION_NAME>` and point at the tested release commit. Never move or reuse a published tag.

## Required verification

Native text-fit checks must also cover text drawn directly on Canvas inside artwork. TextView bounds checks cannot detect that overflow; measure each drawn string against its allotted region and inspect a native artwork render after copy changes.

Before merging or tagging a release, run:

`./gradlew testDebugUnitTest lintDebug assembleDebug`

The universal APK is `app/build/outputs/apk/debug/app-universal-debug.apk`. It includes every ABI supported by the project; per-ABI APKs are emitted alongside it for smaller direct installs.

For direct ADB instrumentation, first confirm the declared runner with
`adb shell pm list instrumentation`, then invoke
`com.subhub.app.test/com.subhub.app.SubHubTestRunner`. Do not assume the stock
`AndroidJUnitRunner`; the custom runner prepares the controller PIN without
overriding each test's Dom/Sub state.
For first-launch or unkeyed onboarding tests, add `-e controller_fixture false`.
The runner otherwise prepares a PIN before activity creation, which changes the
fresh-setup behavior being tested. Keep the fixture enabled for ordinary screen tests.

For native click fixtures, initialize `UiAutomation` before creating the target Views,
then drain the target main queue before the first click so AccessibilityManager receives
the connection state. For pixel assertions, exclude fixture window transitions and await
a new hardware-frame commit where supported; UI idle and a fixed sleep alone do not
prove composition. Keep independent screenshot and hit-map assertions, and retain failed
pixels before assertions discard their evidence. A frame commit is not a physical pixel clock.

For ImageReader colour-metadata fixtures, supply metadata through the actual producer and assert
the acquired image's format and dataspace before invoking the code under test. Do not assume
`setDataSpace()` on an acquired image changes its overridden getter; use a real ImageWriter path
for explicit source dataspaces. Keep requested reader defaults distinct from received frame metadata.

Resource preparation is effect-specific: solid boxes draw inline and do not create prepared image
resources. For those fixture baselines, await a current accepted post and drained render queues,
not an impossible `resourcesMatchCurrent()` condition.

Every fixture registering or reconfiguring `UiAutomation` must save both
`enabled_accessibility_services` and `accessibility_enabled` before registration. In teardown,
switch to `FLAG_DONT_USE_ACCESSIBILITY` before restoring both exact values, then assert restoration.
UI-only automation can enable an inactive remembered service and contaminate the next bound-service
fixture. Keep rejecting enabled capture intent; use a separately owned fresh-data guest instead of
clearing an existing guest's settings or app data.

`UiAutomation.executeShellCommand()` executes directly, without shell quote interpretation. For
validated, nonempty, whitespace-free setting tokens, pass the literal token without added quotes.
Reject unsupported original values before connecting automation, and verify exact restored values
both in the fixture and through an independent external read after disconnection.

Recover debug-app internal Censor Lab files with the authorized `run-as com.subhub.app` path.
Copies in external app storage may not be shell-readable; do not change permissions or repeat
passed workloads to repair export evidence. Verify the structured payload as well as the command
status: Android `exec-out` can return diagnostic text with a zero host exit code. Preserve failed
exports and shutdown observers, and reconcile actual closure separately.

For keyboard regressions, check initial launch, activity recreation, and tap-to-edit
separately. Android can restore an EditText's focus after `onCreate`, so a launch-only
focus check does not verify recreated screens.
Checked widget state can also be restored after `onCreate` and invoke change listeners.
When preferences are authoritative, fence persistence, scheduling, and network side effects
during framework view-state restoration, then render from those preferences. Native
recreation regressions must cover both directions when saved widgets and stored values differ.
Gate Android instrumentation tests that require newer platform APIs with
`@SdkSuppress(minSdkVersion = ...)`, not `@RequiresApi`. The latter describes production
API contracts rather than runtime test eligibility and fails Android lint's `UseSdkSuppress` check.
Keep debug manifest Activities and Services instantiable at the minimum SDK: guard their entry
points and annotate API-specific helpers, not the whole component. A component-wide `@RequiresApi`
also raises its manifest requirement, even when its entry point contains a runtime SDK guard.
For `CensorEngine` capture adapters, an admitted dispatch must precede the capturer's return. To move
copying off the main thread, schedule the whole capture invocation on the worker; do not return
before a queued adapter attempts admission. Keep `tryDispatch()` at actual source consumption.
Full-display buffer coordinates are not View-local coordinates. Bind small native pixel witnesses
to measured on-screen View bounds, checking those against an independent native bounds source;
do not force an inset app View to origin zero or compensate the image with guessed offsets.
Controlled scheduler fixtures must execute only due, uncancelled tasks in deadline
order. Capture and display-expiry wakes are independent; insertion order can fire an
expiry in place of a capture and invalidate an otherwise unrelated adapter test.
Spinner value and option TextViews must not inherit interactive EditText flags.
Keep them nonfocusable and nonclickable so taps reach the spinner or option list;
verify opening, choosing, and saving with native taps rather than `setSelection` alone.

For Windows 10 emulator setup when the bundled screenshot helper fails, follow
[Android capture fallback](docs/censor-lab/android-capture-fallback.md). Check supported
keyboard-only control before declaring all UI automation unavailable; never fabricate screenshot
IDs or bypass denied permission/input operations.

## Documentation scope

Do not create or retain numbered Censor pass writeups, pass registries, or internal experiment
diaries in project documentation. Keep reusable developer instructions and product documentation;
temporary test evidence belongs in ignored build reports, not GitHub.

## Documentation screenshots

Use meaningful synthetic feature configurations, not empty/default-only screens. Every Limits
capture must show one or two selected installed apps with distinct custom daily allowances.
Seed and assert that fixture in the capture test so future passes retain it. Never use personal
phone data or live payment credentials for documentation or marketing screenshots.

## Diagnostic trace verification

For main-thread sampling, follow [live Accessibility profiling](docs/censor-lab/main-thread-profiling.md).
Instrumentation setup must not be mistaken for a live, bound Accessibility pipeline.
For video/source comparisons, follow [recording clock alignment](docs/censor-lab/recording-clock-alignment.md).

Before using performance traces as evidence, compare raw publication counts with parsed counts.
Every trace-schema change must include a parser fixture for the changed record. Unknown fields or
unsupported formats must be exposed as incomplete parsing, never silently reported as zero work.

Select byte and row bounds for each artifact's schema. Expanded raw traces must not pass through
a smaller maintenance-manifest reader; exercise the actual file reader above the small-manifest
limit and at the raw-trace refusal boundary. Preserve observer failures and recover completed
captures offline rather than repeating the producer to obtain a successful receipt.

`SceneEngine.Source.pixelAtMillis == -1` explicitly means unknown. Preserve and test that sentinel;
never substitute receipt time or a raw API-reported stamp. Validate each clock field's own contract
instead of applying a blanket nonnegative range to every timestamp.

Reconcile copied drawing members and accepted POST members in both directions using literal
post serial, endpoint generation and member identity. Bind geometry and evidence source identities
separately because mixed origins are valid. A matching subset is not complete member coverage;
missing or truncated copies must remain explicitly incomplete.

Presentation revisions are opaque signed-long equality tokens, not timestamps. Diagnostics must
preserve their full domain and use explicit record state, not token sign, to distinguish missing data.
At a live renderer callback, verify actual Surface-use and drawing-lease counts. Reserve whole-owner
`managedResourcesDrained()` checks for final shutdown; an open resource owner is not drained.

## Release procedure

1. Complete the version bump and release notes in reviewable commits.
   When refreshing both release outputs locally, write the single-release fragment to a temporary
   `--changelog-output` path and use `--history-output CHANGELOG.md` for the cumulative changelog;
   `--changelog-output CHANGELOG.md` replaces the history with only the current release.
2. Verify the command above and review the staged diff for secrets or unrelated files.
3. Push the tested commit to `master`.
4. Create and push the exact version tag, for example `git tag -a v0.2.0 -m "SubHub 0.2.0"` followed by `git push origin v0.2.0`.
5. `.github/workflows/release.yml` validates tag/version parity, tests and lints the app, signs all APKs, and stages the GitHub release as a draft while uploading each universal/per-ABI artifact and checksum separately. It must verify the complete asset set before publishing because immutable releases reject later uploads and permanently consume a tag even if deleted.
6. `scripts/generate_release_notes.py` categorizes every commit since the previous tag, places those changes in both the GitHub release and updater manifest, and keeps APK-selection guidance in its own release section. The release fails instead of publishing an empty changelog when a commit lacks a supported type prefix.

Release signing is supplied only through the repository Actions secrets named in the workflow. Do not weaken signing or manufacture a different key for a later release; Android updates require the same key.

## Development releases

Every non-`master` branch push builds its exact pushed commit and publishes a signed GitHub
prerelease named `v<VERSION_NAME>-dev.<release workflow run_number>`. Do not cancel an older push's
build just because a new commit arrives. Each tag is unique and immutable; verify all APKs,
checksums and updater metadata in the draft before publishing. Dev builds must not become the
latest stable release or accidentally enable private experimental flags. Stable release tags
must identify a tested commit on `master`; branch pushes never create a normal release.
Dev release notes cover the pushed commit range. For a first or rewritten branch push, describe
the new snapshot's last commit instead of replaying unrelated legacy history. Stable changelogs
remain cumulative since the previous stable tag; automatic dev tags must not truncate them.
The normalized source history has a recorded equivalent for the historical `v0.6.3` release.
Use that source-history base only when its full Git tree equals the published tag's tree;
never move the tag, guess an equivalent, or disable strict commit-type validation.

Dev updates are opt-in and off by default. Selection uses the compatible manifest's Android
version code, not SemVer alone, and stable lookup must still work when dev builds fill the first
release-feed page. Channel changes invalidate cached candidates/ETags and pending downloads;
in-flight checks from the previous channel must not restore an old candidate. Do not downgrade
or uninstall when the user leaves the dev channel.
