# Recording clocks and native analysis dependencies

Android screenrecord output is variable-frame-rate. Average FPS is not an exact mapping from
frame index to screenshot timestamps. Prefer validated Winscope timing metadata when present;
scripts/screenrecord_timestamps.py accepts version2 and requires one unique metadata payload,
valid size/count, and increasing timestamps. Compare the metadata count to every decoded frame
before matching indexes. Keep elapsed, uptime and wall clocks distinct and report clock-fit spread
and nearest-frame error. An accepted nearest timestamp does not prove identical compositor content.

Censor Lab event sequence IDs come from a process-wide counter and need not restart at one for
each recording. Validate ordering, uniqueness and continuity from the observed first ID, compare
raw and parsed record counts with the manifest, and retain the reported drop count. A later
session starting above one is not missing data; gaps, duplicates or reordering still require an
explicit incomplete-evidence result rather than silently renumbering or discarding records.

Accessibility screenshot timestamps are not automatically pixel timestamps. In Android15,
window results are stamped on client receipt in [AccessibilityInteractionClient](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/accessibility/AccessibilityInteractionClient.java#L1354),
and display results on server completion. Keep request, reported, callback and verified pixel time
separate. Never promote receipt-time visual measurements into exact camera anchors or subtract a
fixed offset without source evidence. In the current service, captureAgeMs is request-to-publication
latency; audit the producer/call site rather than inferring pixel age from that legacy field name.

The authoritative format implementation is Android's [screenrecord source](https://android.googlesource.com/platform/frameworks/av/+/b675cea85b508d7f60f1344e5a24c3db7a5b8d0f/cmds/screenrecord/screenrecord.cpp).
Its version2 frame count is uint32 in code despite the nearby prose describing eight bytes.
FFmpeg may discard zero-duration metadata tracks and produce empty data exports with exit0. Verify
nonempty output and expected structure; do not infer successful extraction from the exit status.

For optical intervals hundreds of milliseconds apart, direct sparse flow may choose a wrong match
despite reporting many features. Inspect disagreement frames; sum adjacent-frame flow when useful
and reject intervals with missing edges. Never silently turn failed optical steps into zero motion.

Use a Python interpreter matching the native dependencies' ABI. The existing calibration-python
packages contain cp312 modules; PATH Python can change and must not be assumed compatible. Discover
the bundled Python runtime, verify its version, and set a task-local PYTHONPATH to the analysis
packages. Do not replace global NumPy/OpenCV installations to repair a mismatched interpreter.

When validating decode through FFmpeg's null output, preserve a sufficiently fine output timebase
(for example, `-fps_mode passthrough -enc_time_base 1:90000` with FFmpeg 7.1). A coarse inferred
output timebase can emit duplicate/non-monotonic DTS warnings for otherwise distinct decoded
frames. Check source timing metadata and decoded count independently before treating a null-sink
timestamp warning as source corruption; never repair it by silently dropping source frames.

OpenCV `CAP_PROP_POS_MSEC` can return an invalid timestamp for the first decoded frame even
when later values agree with the media timestamps. Validate the first timestamp and critical
transition indexes independently with FFmpeg `-copyts`, `select`, and `showinfo`. Do not shift
every frame to compensate for a first-frame anomaly, or infer transition duration from average
FPS. Record the discrepancy and use independently verified timestamps for reported events.
