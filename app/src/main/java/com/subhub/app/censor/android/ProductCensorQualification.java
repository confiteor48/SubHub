package com.subhub.app.censor.android;

import android.content.Context;
import android.os.Build;
import androidx.annotation.RequiresApi;
import androidx.annotation.ChecksSdkIntAtLeast;
import com.subhub.app.BuildConfig;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.NativeTextFrame;
import com.subhub.app.penance.CensorTapTracker;
import android.accessibilityservice.AccessibilityService;
import com.subhub.app.stats.StatsRepository;
import java.util.function.Function;

/** Private emulator qualification only; never a product mode, release flag or hot-swap boundary. */
public final class ProductCensorQualification {
    public static final String PREFERENCES = "censor_product_qualification";
    private static volatile AccessibilityCensorHost.WorkerFactory fixtureModels;
    private static volatile Runnable fixtureSelectionObserved = () -> { };
    private static volatile CensorProductSession.Observer fixtureObserver;
    private static volatile Function<Context, StatsRepository> fixtureStatistics = StatsRepository::new;
    private static volatile CensorProductSession current;
    private ProductCensorQualification() { }
    @ChecksSdkIntAtLeast(api = 34)
    public static boolean supported() {
        return BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 34
                && (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
    }
    public static boolean enabled(Context context) {
        return supported() && context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean("enabled", false);
    }
    /** Only instrumentation in the same owned guest can replace the model boundary. */
    public static void fixtureModels(AccessibilityCensorHost.WorkerFactory models) {
        requireInactive();
        fixtureModels = models;
    }
    static void fixtureSelectionObserved(Runnable action) { requireInactive(); fixtureSelectionObserved = java.util.Objects.requireNonNull(action); }
    static void fixtureObserver(CensorProductSession.Observer observer) { requireInactive(); fixtureObserver = observer; }
    static void fixtureStatistics(Function<Context, StatsRepository> factory) {
        requireInactive(); fixtureStatistics = java.util.Objects.requireNonNull(factory);
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
            @Override public void trace(CensorEngine.Trace trace) { observer.trace(trace); probe.trace(trace); }
            @Override public void providerReady(SceneEngine.Lane lane, String provider, long millis) { observer.providerReady(lane, provider, millis); probe.providerReady(lane, provider, millis); }
            @Override public void modelUnavailable(SceneEngine.Lane lane) { observer.modelUnavailable(lane); probe.modelUnavailable(lane); }
            @Override public void prepared(long request, PixelFrame.Kind kind, long nanos, int bytes) { observer.prepared(request, kind, nanos, bytes); probe.prepared(request, kind, nanos, bytes); }
            @Override public void failed(long request, int code) { observer.failed(request, code); probe.failed(request, code); }
            @Override public void presentationChanged() { observer.presentationChanged(); probe.presentationChanged(); }
            @Override public void batchAccepted(SceneEngine.Batch batch) { observer.batchAccepted(batch); probe.batchAccepted(batch); }
            @Override public void nativeTextAccepted(NativeTextFrame frame) { observer.nativeTextAccepted(frame); probe.nativeTextAccepted(frame); }
            @Override public void evidenceAccepted(CensorEngine.EvidenceReceipt receipt) { observer.evidenceAccepted(receipt); probe.evidenceAccepted(receipt); }
            @Override public void painted(CensorEngine.PaintReceipt receipt) { observer.painted(receipt); probe.painted(receipt); }
            @Override public void posted(SnapshotCensorSurface.Post receipt) { observer.posted(receipt); probe.posted(receipt); }
            @Override public void endpointFailure() { observer.endpointFailure(); probe.endpointFailure(); }
            @Override public void resourceFailure() { observer.resourceFailure(); probe.resourceFailure(); }
            @Override public void windowRejected(long token, AccessibilityWindowReader.Window expected, AccessibilityWindowReader.Window actual) { observer.windowRejected(token, expected, actual); probe.windowRejected(token, expected, actual); }
            @Override public void drained() { observer.drained(); probe.drained(); }
            @Override public void executorsTerminated(boolean disposed) { observer.executorsTerminated(disposed); probe.executorsTerminated(disposed); }
        };
        current = new CensorProductSession(service, policy, taps, delivery,
                models == null ? VisualDetectorWorker::initialize : models, fixtureSelectionObserved, fixtureStatistics);
        return current;
    }
    public static CensorProductSession current() { return current; }
    public static void disconnected(CensorProductSession selected) {
        // A disconnected service is not proof that callbacks/native resources drained.
        if (Build.VERSION.SDK_INT >= 34 && current == selected && selected.managedResourcesDrained()) current = null;
    }
}
