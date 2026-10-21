package com.subhub.app.censor.android;

import android.content.Context;
import android.os.Build;
import androidx.annotation.RequiresApi;
import androidx.annotation.ChecksSdkIntAtLeast;
import com.subhub.app.BuildConfig;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.NativeTextFrame;
import com.subhub.app.censor.engine.CensorCandidateGate;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.penance.PenanceManager;
import android.accessibilityservice.AccessibilityService;
import com.subhub.app.stats.StatsRepository;
import java.util.function.Function;

/** One startup-selected replacement owner; fixture injection remains emulator-only. */
public final class ProductCensorQualification {
    public static final String PREFERENCES = "censor_product_qualification";
    public static final String CANDIDATE_SELECTION = "candidate_selected";
    private static volatile AccessibilityCensorHost.WorkerFactory fixtureModels;
    private static volatile Runnable fixtureSelectionObserved = () -> { };
    private static volatile Runnable fixtureNativeReadSelected = () -> { };
    private static volatile java.util.function.Consumer<CensorEngine.NativeTextInput> fixtureNativeConfirmationPending;
    public interface FixtureInputObserver {
        void boundary(int type, int contentTypes, int windowId, long sourceAt, long receivedAt, String route, boolean completed);
    }
    private static volatile FixtureInputObserver fixtureInputObserver;
    private static volatile CensorProductSession.Observer fixtureObserver;
    private static volatile Function<Context, StatsRepository> fixtureStatistics = StatsRepository::new;
    private static volatile Function<Context, PenanceManager> fixturePenance = PenanceManager::new;
    private static volatile CensorProductSession current;
    private ProductCensorQualification() { }
    @ChecksSdkIntAtLeast(api = 34)
    public static boolean supported() {
        return BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 34
                && (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
    }
    @ChecksSdkIntAtLeast(api = 34)
    public static boolean candidateSupported() {
        return CensorCandidateGate.supported(candidateBuildAllowed(), Build.VERSION.SDK_INT);
    }
    public static boolean candidateSelected(Context context) {
        return CensorCandidateGate.selected(candidateBuildAllowed(), Build.VERSION.SDK_INT,
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getAll().get(CANDIDATE_SELECTION));
    }
    private static boolean candidateBuildAllowed() {
        return CensorCandidateGate.buildAllowed(BuildConfig.REPLACEMENT_LAB_CANDIDATE, BuildConfig.VERSION_NAME);
    }
    /** A selection request only. It never changes a currently connected service owner. */
    public static void selectCandidate(Context context, boolean selected) {
        if (!candidateSupported() || !com.subhub.app.security.ControllerPinManager.isDomModeActive()) {
            throw new IllegalStateException("Editable supported candidate required");
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putBoolean(CANDIDATE_SELECTION, selected).apply();
    }
    public static boolean enabled(Context context) {
        return candidateSelected(context) || (supported() && Boolean.TRUE.equals(
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getAll().get("enabled")));
    }
    /** Only instrumentation in the same owned guest can replace the model boundary. */
    public static void fixtureModels(AccessibilityCensorHost.WorkerFactory models) {
        requireInactive();
        fixtureModels = models;
    }
    static void fixtureSelectionObserved(Runnable action) { requireInactive(); fixtureSelectionObserved = java.util.Objects.requireNonNull(action); }
    static void fixtureNativeReadSelected(Runnable action) { requireInactive(); fixtureNativeReadSelected = java.util.Objects.requireNonNull(action); }
    static void fixtureNativeConfirmationPending(java.util.function.Consumer<CensorEngine.NativeTextInput> action) {
        requireInactive(); fixtureNativeConfirmationPending = action;
    }
    /** No callback outside a supported, same-process owned-emulator qualification session. */
    static void nativeConfirmationPending(CensorEngine.NativeTextInput input) {
        java.util.function.Consumer<CensorEngine.NativeTextInput> action = fixtureNativeConfirmationPending;
        if (action != null && supported() && current != null) action.accept(input);
    }
    static void fixtureInputObserver(FixtureInputObserver observer) { requireInactive(); fixtureInputObserver = observer; }
    /** Primitive, synchronous diagnostic boundary available only to installed owned-emulator fixtures. */
    public static void observeFixtureInput(CensorProductSession selected, int type, int contentTypes, int windowId,
            long sourceAt, long receivedAt, String route, boolean completed) {
        FixtureInputObserver probe = fixtureInputObserver;
        if (probe != null && supported() && current == selected)
            probe.boundary(type, contentTypes, windowId, sourceAt, receivedAt, route, completed);
    }
    static void fixtureObserver(CensorProductSession.Observer observer) { requireInactive(); fixtureObserver = observer; }
    static void fixtureStatistics(Function<Context, StatsRepository> factory) {
        requireInactive(); fixtureStatistics = java.util.Objects.requireNonNull(factory);
    }
    static void fixturePenance(Function<Context, PenanceManager> factory) {
        requireInactive(); fixturePenance = java.util.Objects.requireNonNull(factory);
    }
    private static void requireInactive() {
        if (!supported()) throw new IllegalStateException("Owned-emulator qualification required");
        if (current != null && current.managedResourcesDrained()) current = null;
        if (current != null) throw new IllegalStateException("Inactive owned-emulator qualification required");
    }
    @RequiresApi(34)
    public static CensorProductSession connect(AccessibilityService service, CensorTapTracker taps, CensorProductSession.Observer observer) {
        if (current != null && current.managedResourcesDrained()) current = null;
        if (!enabled(service) || current != null) throw new IllegalStateException("One qualification owner required");
        // Reuse the existing bound-controller fixture policy. These are not accepted product retention settings.
        SceneEngine.Policy policy = new SceneEngine.Policy(16, 100, 64, 256, 2000, 20000, .5);
        AccessibilityCensorHost.WorkerFactory models = fixtureModels;
        CensorProductSession.Observer probe = fixtureObserver;
        CensorProductSession.Observer delivery = probe == null ? observer : new CensorProductSession.Observer() {
            @Override public void foreground(AccessibilityWindowReader.Window window) { observer.foreground(window); probe.foreground(window); }
            @Override public void activeChanged(boolean active) { observer.activeChanged(active); probe.activeChanged(active); }
            @Override public void sessionFailure(Throwable failure) { observer.sessionFailure(failure); probe.sessionFailure(failure); }
            @Override public void accounting(CensorProductSession.AccountingResult result) { observer.accounting(result); probe.accounting(result); }
            @Override public void dwell(CensorProductSession.DwellResult result) { observer.dwell(result); probe.dwell(result); }
            @Override public void trace(CensorEngine.Trace trace) { observer.trace(trace); probe.trace(trace); }
            @Override public void providerReady(SceneEngine.Lane lane, String provider, long millis) { observer.providerReady(lane, provider, millis); probe.providerReady(lane, provider, millis); }
            @Override public void modelUnavailable(SceneEngine.Lane lane) { observer.modelUnavailable(lane); probe.modelUnavailable(lane); }
            @Override public void prepared(long request, PixelFrame.Kind kind, long nanos, int bytes) { observer.prepared(request, kind, nanos, bytes); probe.prepared(request, kind, nanos, bytes); }
            @Override public void failed(long request, int code) { observer.failed(request, code); probe.failed(request, code); }
            @Override public void presentationChanged() { observer.presentationChanged(); probe.presentationChanged(); }
            @Override public void batchAccepted(SceneEngine.Batch batch) { observer.batchAccepted(batch); probe.batchAccepted(batch); }
            @Override public void nativeTextAccepted(NativeTextFrame frame) { observer.nativeTextAccepted(frame); probe.nativeTextAccepted(frame); }
            @Override public void nativeReadCost(com.subhub.app.censor.engine.NativeReadCost value) { observer.nativeReadCost(value); probe.nativeReadCost(value); }
            @Override public void nativeReadCostStarted(com.subhub.app.censor.engine.NativeReadCost.Start value) { observer.nativeReadCostStarted(value); probe.nativeReadCostStarted(value); }
            @Override public void scrollReceived(com.subhub.app.censor.engine.ScrollMotionLedger.Receipt receipt) { observer.scrollReceived(receipt); probe.scrollReceived(receipt); }
            @Override public void evidenceAccepted(CensorEngine.EvidenceReceipt receipt) { observer.evidenceAccepted(receipt); probe.evidenceAccepted(receipt); }
            @Override public void painted(CensorEngine.PaintReceipt receipt) { observer.painted(receipt); probe.painted(receipt); }
            @Override public boolean collectSubmissions() { return observer.collectSubmissions() || probe.collectSubmissions(); }
            @Override public void submitted(SurfaceSubmission submission) { observer.submitted(submission); probe.submitted(submission); }
            @Override public void posted(SnapshotCensorSurface.Post receipt) { observer.posted(receipt); probe.posted(receipt); }
            @Override public void endpointFailure() { observer.endpointFailure(); probe.endpointFailure(); }
            @Override public void resourceFailure() { observer.resourceFailure(); probe.resourceFailure(); }
            @Override public void windowRejected(long token, AccessibilityWindowReader.Window expected, AccessibilityWindowReader.Window actual) { observer.windowRejected(token, expected, actual); probe.windowRejected(token, expected, actual); }
            @Override public void drained() { observer.drained(); probe.drained(); }
            @Override public void executorsTerminated(boolean disposed) { observer.executorsTerminated(disposed); probe.executorsTerminated(disposed); }
        };
        current = new CensorProductSession(service, policy, taps, delivery,
                models == null ? VisualDetectorWorker::initialize : models, fixtureSelectionObserved, fixtureStatistics, fixturePenance,
                fixtureNativeReadSelected);
        return current;
    }
    public static CensorProductSession current() { return current; }
    public static void disconnected(CensorProductSession selected) {
        // A disconnected service is not proof that callbacks/native resources drained.
        if (Build.VERSION.SDK_INT >= 34 && current == selected && selected.managedResourcesDrained()) current = null;
    }
}
