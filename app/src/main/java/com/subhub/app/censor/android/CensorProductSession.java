package com.subhub.app.censor.android;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.RequiresApi;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppModePolicy;
import com.subhub.app.capture.CustomImageManager;
import com.subhub.app.censor.engine.LatestReconciliation;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.text.TextSmutConfig;
import com.subhub.app.diagnostics.CensorLabCensorSession;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.settings.*;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Product eligibility and one replacement lifetime, independent of legacy censor state. */
@RequiresApi(30)
public final class CensorProductSession implements AutoCloseable {
    public interface Observer extends AccessibilityCensorController.Observer {
        /** Common foreground features commit first, and may revoke this reconciliation reentrantly. */
        default void foreground(AccessibilityWindowReader.Window window) { }
        default void activeChanged(boolean active) { }
        default void sessionFailure(Throwable failure) { }
    }
    private final AccessibilityService service;
    private final SharedPreferences preferences;
    private final AppModeManager mode;
    private final CensorTapTracker taps;
    private final Observer observer;
    private final Runnable selectionObserved;
    private final AccessibilityCensorController controller;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService lookup = Executors.newSingleThreadExecutor(action -> new Thread(action, "Censor-product-window"));
    private final LatestReconciliation<Desired> reconciliation;
    private volatile String preferredPackage = "";
    private volatile String inputMethodPackage = "";
    private volatile Desired selected;
    private ProtectionScope protection;
    private List<Object> unavailableInference;
    private long scopeToken;
    private volatile boolean active, closed, failed;

    public CensorProductSession(AccessibilityService service, SceneEngine.Policy policy, CensorTapTracker taps, Observer observer) {
        this(service, policy, taps, observer, VisualDetectorWorker::initialize);
    }
    CensorProductSession(AccessibilityService service, SceneEngine.Policy policy, CensorTapTracker taps, Observer observer,
            AccessibilityCensorHost.WorkerFactory models) {
        this(service, policy, taps, observer, models, () -> { });
    }
    CensorProductSession(AccessibilityService service, SceneEngine.Policy policy, CensorTapTracker taps, Observer observer,
            AccessibilityCensorHost.WorkerFactory models, Runnable selectionObserved) {
        onMain(); this.service = Objects.requireNonNull(service); this.taps = Objects.requireNonNull(taps);
        this.selectionObserved = Objects.requireNonNull(selectionObserved);
        this.observer = Objects.requireNonNull(observer); mode = new AppModeManager(service);
        preferences = new SettingsRepository(service).preferences();
        controller = new AccessibilityCensorController(service, policy, taps,
                new AccessibilityCensorController.Observer() {
                    @Override public void providerReady(SceneEngine.Lane lane, String provider, long millis) { observer.providerReady(lane, provider, millis); }
                    @Override public void modelUnavailable(SceneEngine.Lane lane) {
                        if (lane == SceneEngine.Lane.FAST && selected != null && active) {
                            unavailableInference = selected.inference; stop();
                        }
                        observer.modelUnavailable(lane);
                    }
                    @Override public void trace(CensorEngine.Trace trace) {
                        if (trace.stage == CensorEngine.Stage.RESOURCE_FAILED) {
                            // This final controller owns the fault even if its former scope stopped.
                            main.post(() -> fail(new IllegalStateException("Replacement native owner quarantined")));
                        }
                        observer.trace(trace);
                    }
                    @Override public void prepared(long request, PixelFrame.Kind kind, long nanos, int bytes) { observer.prepared(request, kind, nanos, bytes); }
                    @Override public void failed(long request, int code) { observer.failed(request, code); }
                    @Override public void presentationChanged() { observer.presentationChanged(); }
                    @Override public void batchAccepted(SceneEngine.Batch batch) { observer.batchAccepted(batch); }
                    @Override public void nativeTextAccepted(com.subhub.app.censor.engine.NativeTextFrame frame) { observer.nativeTextAccepted(frame); }
                    @Override public void painted(com.subhub.app.censor.engine.CensorEngine.PaintReceipt receipt) { observer.painted(receipt); }
                    @Override public void posted(SnapshotCensorSurface.Post receipt) { observer.posted(receipt); }
                    @Override public void endpointFailure() { main.post(() -> fail(new IllegalStateException("Replacement presentation endpoint failed"))); observer.endpointFailure(); }
                    @Override public void resourceFailure() { main.post(() -> fail(new IllegalStateException("Replacement resource ownership failed"))); observer.resourceFailure(); }
                    @Override public void windowRejected(long token, AccessibilityWindowReader.Window expected, AccessibilityWindowReader.Window actual) {
                        main.post(() -> { if (!closed && selected != null && scopeToken == token) { stop(); request(); } });
                        observer.windowRejected(token, expected, actual);
                    }
                    @Override public void drained() { observer.drained(); }
                    @Override public void executorsTerminated(boolean disposed) { observer.executorsTerminated(disposed); }
                }, models);
        reconciliation = new LatestReconciliation<>(lookup, main::post, this::read, this::commit, this::fail);
    }
    public void request() { onMain(); if (!closed && !failed) reconciliation.request(); }
    /** Accepted real foreground changes withdraw old geometry before waiting for a Binder lookup. */
    public void foregroundEvent(String packageName, String className, int windowId, boolean windowStateChange) {
        onMain(); if (closed || failed) return;
        if (AppModePolicy.shouldAcceptForegroundEvent(packageName, className, service.getPackageName(), inputMethodPackage)) {
            if (!packageName.equals(preferredPackage) || (windowStateChange && selected != null
                    && windowId >= 0 && selected.window.id != windowId)) {
                // Keep the last protected window as resolver preference under app-owned overlays.
                // Own Activities and Settings are real escape transitions, not a protected overlay.
                if (packageName.equals(service.getPackageName()) || packageName.equals("com.android.settings")
                        || packageName.equals("com.google.android.settings")) preferredPackage = "";
                stop();
            }
        }
        request();
    }
    public void settingsChanged(String key) {
        onMain(); if (!ProductCensorSettings.relevant(key)) return;
        // Structural edits withdraw immediately; style-only edits retain qualified source geometry.
        if ((!com.subhub.app.capture.export.ExportSettings.allowed(key) && !CustomImageManager.REVISION_KEY.equals(key))
                || key.equals(SettingsRepository.KEY_DETECTION_PRESET)
                || key.equals(SettingsRepository.KEY_CONFIDENCE)
                || key.equals(SettingsRepository.KEY_ENABLED_CATEGORIES)
                || key.equals(SettingsRepository.KEY_CENSOR_COVERAGE)) stop();
        request();
    }
    public void ownershipChanged() { onMain(); stop(); request(); }
    public void contentChanged(int windowId) { onMain(); if (active && !closed) { controller.contentChanged(windowId); controller.requestCapture(); } }
    public void demandCapture() { onMain(); if (active && !closed) controller.requestCapture(); }
    public void stop() {
        onMain(); if (closed) return;
        reconciliation.invalidate();
        selected = null; protection = null;
        boolean previous = active; active = false;
        controller.stop();
        if (previous) observer.activeChanged(false);
    }
    public boolean isActive() { return active && !closed && !failed; }
    public AccessibilityWindowReader.Window window() { Desired value = selected; return value == null ? null : value.window; }
    @Override public void close() {
        onMain(); if (closed) return;
        closed = true; active = false; selected = null; protection = null;
        reconciliation.close(); lookup.shutdown();
        try { controller.close(); } finally { observer.activeChanged(false); }
    }
    public boolean managedResourcesDrained() {
        return closed && reconciliation.drained() && lookup.isTerminated() && controller.managedResourcesDrained();
    }
    AccessibilityCensorController controller() { return controller; }
    AccessibilityService service() { return service; }

    private Desired read() {
        ProductCensorSettings snapshot = new ProductCensorSettings(preferences);
        CensorLabCensorSession.Stamp lab = CensorLabCensorSession.stamp();
        String ime = mode.inputMethodPackage();
        inputMethodPackage = ime;
        AccessibilityWindowReader.Window window = controller.observeForeground(ime, preferredPackage);
        SettingsRepository settings = SettingsRepository.forPreferences(snapshot);
        DetectorConfig detector = settings.loadDetectorConfig(); TextSmutConfig text = settings.loadTextSmutConfig();
        boolean enabled = lab.active || (snapshot.getBoolean(AppModeManager.KEY_ARMED, false)
                && snapshot.getBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, true)
                && settings.loadCaptureMethod() == CaptureMethod.APP_MODE);
        boolean eligible = window != null && AppModePolicy.shouldRecognize(enabled, snapshot.included(),
                window.packageName, service.getPackageName(), ime);
        SnapshotPaintPolicy paint = SnapshotPaintPolicy.from(settings.loadDetectionPreset());
        CensorAppearance appearance = settings.loadAppearance();
        long revision = snapshot.getLong(CustomImageManager.REVISION_KEY, 0);
        Desired result = new Desired(snapshot, lab, window, detector, text, appearance, paint, revision, eligible);
        selectionObserved.run(); // Adverse delivery control after all actual platform handles were released.
        return result;
    }
    private void commit(Desired value) {
        onMain(); if (closed || failed) return;
        long generation = reconciliation.generation();
        if (!value.lab.isCurrent() || !value.settings.same(new ProductCensorSettings(preferences))) { request(); return; }
        observer.foreground(value.window);
        if (!reconciliation.isCurrent(generation) || closed || failed) return;
        if (!value.lab.isCurrent() || !value.settings.same(new ProductCensorSettings(preferences))) { request(); return; }
        if (!value.eligible) { preferredPackage = ""; stop(); return; }
        if (value.inference.equals(unavailableInference)) { stop(); return; }
        unavailableInference = null;
        preferredPackage = value.window.packageName;
        ProtectionScope desiredProtection = value.lab.active ? ProtectionScope.lab(taps.scope()) : ProtectionScope.normal(taps.scope());
        if (active && selected != null && selected.window.same(value.window) && selected.lab == value.lab
                && selected.inference.equals(value.inference) && desiredProtection.equals(protection)) {
            if (!selected.appearanceKey.equals(value.appearanceKey)) {
                controller.appearanceChanged(value.appearance, value.paint, value.artworkRevision);
            }
            if (reconciliation.isCurrent(generation) && !closed && !failed && active) selected = value;
            return;
        }
        selected = value; protection = desiredProtection; active = true;
        try {
            int threads = value.detector.getInferenceThreads();
            controller.start(new AccessibilityCensorController.Configuration(++scopeToken, value.window, value.detector,
                    threads >= 3 && value.detector.getInferenceResolution() > 320, value.text,
                    threads >= 3, threads >= 4, desiredProtection, value.appearance, value.paint, value.artworkRevision));
            if (reconciliation.isCurrent(generation) && !closed && !failed && active) observer.activeChanged(true);
        } catch (RuntimeException | Error error) { fail(error); }
    }
    private void fail(Throwable failure) {
        onMain(); if (closed || failed) return;
        failed = true; reconciliation.close();
        try { stop(); } finally { observer.sessionFailure(failure); }
    }
    private static final class Desired {
        final ProductCensorSettings settings;
        final CensorLabCensorSession.Stamp lab;
        final AccessibilityWindowReader.Window window;
        final DetectorConfig detector; final TextSmutConfig text;
        final CensorAppearance appearance; final SnapshotPaintPolicy paint;
        final List<Object> inference, appearanceKey;
        final long artworkRevision; final boolean eligible;
        Desired(ProductCensorSettings settings, CensorLabCensorSession.Stamp lab, AccessibilityWindowReader.Window window,
                DetectorConfig detector, TextSmutConfig text, CensorAppearance appearance, SnapshotPaintPolicy paint,
                long revision, boolean eligible) {
            this.settings = settings; this.lab = lab; this.window = window; this.detector = detector; this.text = text;
            this.appearance = appearance; this.paint = paint; artworkRevision = revision; this.eligible = eligible;
            inference = CensorConfigurationKey.inference(detector, text);
            appearanceKey = CensorConfigurationKey.appearance(appearance, paint, revision);
        }
    }
    private static void onMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main-looper product lifecycle required");
    }
}
