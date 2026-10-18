package com.subhub.app.censor.android;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.RequiresApi;

import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.ProtectionScope;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.censor.engine.ScrollMotionLedger;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.text.NativeTextReader;
import com.subhub.app.detection.text.TextSmutConfig;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.service.ReplacementScrollPresentation;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Actual Android binding for one replacement host and one worker Surface. The product facade
 * supplies eligible application/config/protection scope; no legacy detector/tracker/overlay runs here.
 * The initial window-only qualification deliberately does not qualify API30-33 display feedback.
 */
@RequiresApi(30)
public final class AccessibilityCensorController implements AutoCloseable {
    public interface Observer extends AccessibilityCensorHost.Observer {
        default boolean collectSubmissions() { return false; }
        default void submitted(SurfaceSubmission submission) { }
        default void posted(SnapshotCensorSurface.Post receipt) { }
        default void endpointFailure() { }
        default void resourceFailure() { }
        default void windowRejected(long token, AccessibilityWindowReader.Window expected, AccessibilityWindowReader.Window actual) { }
    }

    public static final class Configuration {
        public final long token, artworkRevision;
        public final AccessibilityWindowReader.Window window;
        public final DetectorConfig detector;
        public final boolean quality, semanticText, exactText;
        public final TextSmutConfig text;
        public final ProtectionScope protection;
        public final CensorAppearance appearance;
        public final SnapshotPaintPolicy paintPolicy;
        public Configuration(long token, AccessibilityWindowReader.Window window, DetectorConfig detector,
                boolean quality, TextSmutConfig text, boolean semanticText, boolean exactText,
                ProtectionScope protection, CensorAppearance appearance, SnapshotPaintPolicy paintPolicy,
                long artworkRevision) {
            if (token < 0 || artworkRevision < 0) throw new IllegalArgumentException("Controller configuration");
            this.token = token; this.window = Objects.requireNonNull(window); this.detector = Objects.requireNonNull(detector);
            this.quality = quality; this.text = text; this.semanticText = semanticText; this.exactText = exactText;
            this.protection = Objects.requireNonNull(protection); this.appearance = Objects.requireNonNull(appearance);
            this.paintPolicy = Objects.requireNonNull(paintPolicy); this.artworkRevision = artworkRevision;
            if (window.id < 0) throw new IllegalArgumentException("A real application window is required");
        }
    }

    /** Actual qualified app-window clipping of detected metadata, not paint/tap coverage. */
    public static final class WindowRegion {
        public final CensorEngine.DetectedRegion member;
        /** Physical display coordinates; native bounds receive no second offset or scale. */
        public final SceneEngine.Region displayRegion;
        private WindowRegion(CensorEngine.DetectedRegion member, SceneEngine.Region clipped) {
            this.member = member; displayRegion = clipped;
        }
    }
    public static final class WindowCoverage {
        public final CensorEngine.ViewportCoverage receipt;
        public final AccessibilityWindowReader.Window window;
        public final boolean windowQualified;
        public final List<WindowRegion> regions;
        private final Transition origin;
        private WindowCoverage(CensorEngine.ViewportCoverage receipt, Transition origin,
                boolean qualified, List<WindowRegion> regions) {
            this.receipt = receipt; this.origin = origin; windowQualified = qualified;
            window = origin == null ? null : origin.configuration.window;
            this.regions = Collections.unmodifiableList(regions);
        }
    }

    private final AccessibilityWindowReader windows;
    private final WindowManager manager;
    private final Observer observer;
    private final AccessibilityCensorHost host;
    private final AccessibilityScrollReader scrollReader;
    private final SnapshotCensorSurface surface;
    private final ReplacementScrollPresentation scrollPresentation = new ReplacementScrollPresentation();
    private final float displayDensity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable nativeReadSelected;
    interface NativeBoundaryControl {
        void beforeCheck(CensorEngine.NativeTextInput input, NativeTextReader.Boundary boundary);
    }
    private final NativeBoundaryControl nativeBoundaryControl;
    private final AtomicLong contentGeneration = new AtomicLong();
    private volatile Configuration configuration;
    private volatile Transition transition;
    private volatile boolean active, closed, resourceFailed;
    private volatile boolean ownerDrained, executorsTerminated, disposalSucceeded;
    private boolean attached;
    private long appearanceGeneration;
    private static final class Transition {
        final Configuration configuration;
        volatile boolean qualified, invalid;
        volatile CensorEngine.ActiveScope scope;
        Transition(Configuration configuration) { this.configuration = configuration; }
    }

    public AccessibilityCensorController(AccessibilityService service, SceneEngine.Policy scenePolicy,
            CensorTapTracker taps, Observer observer) {
        this(service, scenePolicy, taps, observer, VisualDetectorWorker::initialize);
    }

    /** Only model execution is injectable for adverse native controls; capture/scope/Surface remain real. */
    AccessibilityCensorController(AccessibilityService service, SceneEngine.Policy scenePolicy,
            CensorTapTracker taps, Observer observer, AccessibilityCensorHost.WorkerFactory models) {
        this(service, scenePolicy, taps, observer, models, () -> { });
    }

    AccessibilityCensorController(AccessibilityService service, SceneEngine.Policy scenePolicy,
            CensorTapTracker taps, Observer observer, AccessibilityCensorHost.WorkerFactory models, Runnable rootAcquired) {
        this(service, scenePolicy, taps, observer, models, rootAcquired, () -> { });
    }

    AccessibilityCensorController(AccessibilityService service, SceneEngine.Policy scenePolicy,
            CensorTapTracker taps, Observer observer, AccessibilityCensorHost.WorkerFactory models,
            Runnable rootAcquired, Runnable nativeReadSelected) {
        this(service, scenePolicy, taps, observer, models, rootAcquired, nativeReadSelected, (input, boundary) -> { });
    }

    /** Held adverse boundaries keep the real provider, window checks and serial read owner. */
    AccessibilityCensorController(AccessibilityService service, SceneEngine.Policy scenePolicy,
            CensorTapTracker taps, Observer observer, AccessibilityCensorHost.WorkerFactory models,
            Runnable rootAcquired, Runnable nativeReadSelected, NativeBoundaryControl nativeBoundaryControl) {
        onMain(); Objects.requireNonNull(service); this.observer = Objects.requireNonNull(observer);
        displayDensity = service.getResources().getDisplayMetrics().density;
        this.nativeReadSelected = Objects.requireNonNull(nativeReadSelected);
        this.nativeBoundaryControl = Objects.requireNonNull(nativeBoundaryControl);
        windows = new AccessibilityWindowReader(service, rootAcquired);
        manager = Objects.requireNonNull(service.getSystemService(WindowManager.class));
        AccessibilityCensorHost.Observer bridge = new AccessibilityCensorHost.Observer() {
            @Override public void sourcePrepared(CensorEngine.CaptureRequest request, PixelFrame frame) {
                if (sourceCurrent(request.epoch, request.geometry)) {
                    scrollPresentation.preparedBeforeAdmission(request.epoch, request.geometry, request.identity,
                            frame.reportedUptimeMillis, frame.callbackUptimeMillis);
                    surfaceChanged();
                }
                observer.sourcePrepared(request, frame);
            }
            @Override public void prepared(long request, PixelFrame.Kind kind, long nanos, int bytes) {
                observer.prepared(request, kind, nanos, bytes);
            }
            @Override public void failed(long request, int code) { observer.failed(request, code); }
            @Override public void trace(CensorEngine.Trace trace) { observer.trace(trace); }
            @Override public void scrollReceived(ScrollMotionLedger.Receipt receipt) {
                if (receipt.sample != null && scrollCurrent(receipt.sample.scope)
                        && scrollPresentation.observe(receipt)) surfaceChanged();
                observer.scrollReceived(receipt);
            }
            @Override public void providerReady(SceneEngine.Lane lane, String provider, long millis) {
                if (current(transition)) observer.providerReady(lane, provider, millis);
            }
            @Override public void modelUnavailable(SceneEngine.Lane lane) {
                if (current(transition)) observer.modelUnavailable(lane);
            }
            @Override public void presentationChanged() { surfaceChanged(); observer.presentationChanged(); }
            @Override public void batchAccepted(SceneEngine.Batch batch) {
                if (sourceCurrent(batch.source.epoch, batch.source.geometry)) observer.batchAccepted(batch);
            }
            @Override public void nativeTextAccepted(com.subhub.app.censor.engine.NativeTextFrame frame) {
                if (sourceCurrent(frame.source.epoch, frame.source.geometry)) observer.nativeTextAccepted(frame);
            }
            @Override public void nativeReadCost(com.subhub.app.censor.engine.NativeReadCost value) {
                observer.nativeReadCost(value); // Stale/cancelled reader work is still a real cost, not evidence.
            }
            @Override public void nativeReadCostStarted(com.subhub.app.censor.engine.NativeReadCost.Start value) { observer.nativeReadCostStarted(value); }
            @Override public void evidenceAccepted(CensorEngine.EvidenceReceipt receipt) {
                if (paintEligible(receipt.scope)) observer.evidenceAccepted(receipt);
            }
            @Override public void painted(CensorEngine.PaintReceipt receipt) { observer.painted(receipt); }
            @Override public void drained() { ownerDrained = true; observer.drained(); }
            @Override public void executorsTerminated(boolean disposed) {
                disposalSucceeded = disposed; executorsTerminated = true; observer.executorsTerminated(disposed);
            }
        };
        host = new AccessibilityCensorHost(service, scenePolicy,
                executor -> new AccessibilityCapturer(service, executor, this::captureTarget, bridge),
                Objects.requireNonNull(models), bridge, new NativeProvider());
        surface = new SnapshotCensorSurface(service, new SnapshotRenderSession.Owner() {
            @Override public CensorEngine.ActiveScope activeScope() {
                CensorEngine.ActiveScope scope = host.activeScope(); return paintEligible(scope) ? scope : null;
            }
            @Override public boolean isCurrent(CensorEngine.ActiveScope scope) { return paintEligible(scope); }
            @Override public SnapshotCensorPainter.Pose presentation(SceneEngine.Mask mask, long now) {
                ReplacementScrollPresentation.Pose pose = scrollPresentation.pose(mask, now);
                return pose == null ? null : new SnapshotCensorPainter.Pose(pose.region, pose.clip);
            }
            @Override public long presentationRevision() { return scrollPresentation.revision(); }
            @Override public boolean presentationAnimating(long now) { return scrollPresentation.isAnimating(now); }
            @Override public CensorEngine<PixelFrame>.RenderView acquireRenderView() {
                CensorEngine.ActiveScope scope = activeScope();
                if (scope == null) return null;
                CensorEngine<PixelFrame>.RenderView view = host.acquireRenderView();
                if (view != null && !paintEligible(scope)) { view.close(); return null; }
                return view;
            }
            @Override public CensorEngine.PaintReceipt acknowledgePaint(CensorEngine.ActiveScope scope, long revision,
                    long generation, List<Long> members, boolean reverse) {
                return paintEligible(scope) ? host.acknowledgePaint(scope, revision, generation, members, reverse) : null;
            }
        }, Objects.requireNonNull(taps), new SnapshotCensorSurface.Observer() {
            @Override public boolean collectSubmissions() { return observer.collectSubmissions(); }
            @Override public void submitted(SurfaceSubmission submission) { observer.submitted(submission); }
            @Override public void resourceFailure() { resourceFailed = true; observer.resourceFailure(); }
            @Override public void endpointFailure() { observer.endpointFailure(); }
            @Override public void posted(SnapshotCensorSurface.Post receipt) { observer.posted(receipt); }
        });
        surface.presentationAllowed(false);
        scrollReader = Build.VERSION.SDK_INT < 33 ? null : new AccessibilityScrollReader(this::scrollCurrent,
                new AccessibilityScrollReader.Observer() {
                    @Override public void observed(ScrollMotionLedger.Sample sample) { host.observeScroll(sample, false); }
                    @Override public void gap(ScrollMotionLedger.Sample sample) { host.observeScroll(sample, true); }
                    @Override public void resourceFailure() {
                        resourceFailed = true; host.platformResourceFailure(); observer.resourceFailure();
                    }
                });
    }

    /** Source selection is observed off-main through this same platform reader. */
    public AccessibilityWindowReader.Window observeWindow(String packageName, int windowId) {
        return windows.observe(packageName, windowId);
    }

    public AccessibilityWindowReader.Window observeForeground(String inputMethodPackage, String preferredPackage) {
        return windows.observeForeground(inputMethodPackage, preferredPackage);
    }

    /** One existing host survives window/settings changes and stop/start. Tokens must change. */
    public void start(Configuration selected) {
        onMain(); if (closed) throw new IllegalStateException("Closed controller"); Objects.requireNonNull(selected);
        if (Build.VERSION.SDK_INT < 34) throw new IllegalStateException("Window capture qualification requires API34");
        Configuration previous = configuration;
        if (active && previous != null && previous.token == selected.token) throw new IllegalArgumentException("Scope token must change");
        Transition next = new Transition(selected);
        appearanceGeneration++;
        transition = next; configuration = selected; active = true; contentGeneration.incrementAndGet();
        scrollPresentation.reset(selected.token, selected.window.id);
        scrollPresentation.configureNativeContext(selected.window.packageName, displayDensity, ViewConfiguration.getScrollFriction());
        if (Build.VERSION.SDK_INT >= 33 && scrollReader != null) scrollReader.invalidate();
        try {
            surface.presentationAllowed(false);
            if (!current(next)) return;
            surface.configure(selected.appearance, selected.paintPolicy, selected.artworkRevision, true);
            if (!current(next)) return;
            if (!attached) { manager.addView(surface, overlayParameters()); attached = true; }
            if (!current(next)) return;
            host.canvasSamplingControls(selected.appearance.requiresSourceFrame());
            if (!current(next)) return;
            if (host.activeScope() != null) {
                host.changeScope(selected.token, selected.detector, selected.quality, selected.text,
                        selected.semanticText, selected.exactText, selected.protection, selected.window.id);
            } else host.start(selected.token, selected.detector, selected.quality, selected.text,
                    selected.semanticText, selected.exactText, selected.protection, selected.window.id);
            // Actual capture/native lookup, not this stale descriptor, qualifies presentation.
            if (!current(next)) return;
        } catch (RuntimeException | Error failure) {
            if (transition == next) stop();
            throw failure;
        }
    }

    /** Native content is fenced without pretending it is a new screenshot or image camera pose. */
    public void contentChanged(int windowId) {
        contentChanged(windowId, true);
    }
    public void contentChanged(int windowId, boolean hard) {
        onMain(); Configuration value = configuration;
        if (!closed && active && value != null && value.window.id == windowId) {
            contentGeneration.incrementAndGet();
            host.requestNativeText(hard ? CensorEngine.NativeTextChange.HARD_CONTENT : CensorEngine.NativeTextChange.TREE_DIRTY);
        }
    }

    /** Appearance resources change without resetting capture, inference, geometry or identities. */
    public void appearanceChanged(CensorAppearance appearance, SnapshotPaintPolicy policy, long artworkRevision) {
        onMain(); Objects.requireNonNull(appearance); Objects.requireNonNull(policy);
        if (closed) throw new IllegalStateException("Closed controller");
        if (artworkRevision < 0) throw new IllegalArgumentException("Artwork revision");
        Transition selected = transition;
        long generation = ++appearanceGeneration;
        surface.configure(appearance, policy, artworkRevision, true);
        if (!closed && transition == selected && appearanceGeneration == generation) {
            host.canvasSamplingControls(appearance.requiresSourceFrame());
        }
    }

    /** Demand only: neither an event camera pose nor a new source timestamp is manufactured. */
    public void requestCapture() { onMain(); if (!closed && active) host.requestCapture(); }

    public void stop() {
        onMain(); if (closed) return;
        active = false; configuration = null; transition = null; contentGeneration.incrementAndGet();
        scrollPresentation.reset(-1, -1);
        if (Build.VERSION.SDK_INT >= 33 && scrollReader != null) scrollReader.invalidate();
        appearanceGeneration++;
        surface.presentationAllowed(false);
        if (!active && transition == null) host.stop();
    }

    @Override public void close() {
        onMain(); if (closed) return; closed = true; active = false; configuration = null; transition = null;
        scrollPresentation.reset(-1, -1);
        appearanceGeneration++;
        contentGeneration.incrementAndGet();
        if (Build.VERSION.SDK_INT >= 33 && scrollReader != null) scrollReader.close();
        windows.close();
        try { host.close(); }
        finally {
            try { surface.close(); }
            finally {
                if (attached || surface.isAttachedToWindow() || surface.getParent() != null) {
                    try { manager.removeViewImmediate(surface); attached = false; }
                    catch (RuntimeException | Error failure) { resourceFailed = true; observer.resourceFailure(); throw failure; }
                }
            }
        }
    }

    /** Closure of the owner, its executor resources and the actual Surface are separate facts. */
    public boolean managedResourcesDrained() {
        return closed && ownerDrained && executorsTerminated && disposalSucceeded && !resourceFailed
                && (Build.VERSION.SDK_INT < 33 || scrollReader == null || scrollReader.managedResourcesDrained())
                && windows.managedResourcesDrained() && !attached && surface.managedResourcesDrained();
    }

    public CensorEngine.ActiveScope activeScope() { return host.activeScope(); }
    /** No Binder/window refresh or render lease. A caller must revalidate just before use. */
    public WindowCoverage viewportCoverage() {
        Transition selected = transition;
        CensorEngine.ViewportCoverage receipt = host.viewportCoverage();
        List<WindowRegion> regions = new ArrayList<>();
        boolean qualified = coverageWindowQualified(selected, receipt);
        AccessibilityWindowReader.Window window = selected == null ? null : selected.configuration.window;
        if (qualified && host.coverageCurrent(receipt)) {
            SceneEngine.Region viewport = new SceneEngine.Region(window.left, window.top,
                    (double) window.left + window.width, (double) window.top + window.height);
            for (CensorEngine.DetectedRegion member : receipt.regions) {
                SceneEngine.Region clipped = member.displayRegion.clippedTo(viewport);
                if (clipped != null) regions.add(new WindowRegion(member, clipped));
            }
        }
        // Actual window qualification can be withdrawn concurrently with this metadata-only read.
        qualified &= coverageWindowQualified(selected, receipt);
        if (!qualified) regions.clear();
        return new WindowCoverage(receipt, selected, qualified, regions);
    }
    public boolean coverageCurrent(WindowCoverage coverage) {
        return coverage != null && coverage.origin == transition
                && coverage.windowQualified == coverageWindowQualified(coverage.origin, coverage.receipt)
                && host.coverageCurrent(coverage.receipt);
    }
    public boolean observationCurrent(WindowCoverage coverage, CensorEngine.EvidenceReceipt observation) {
        return coverageCurrent(coverage) && coverage.windowQualified
                && host.observationCurrent(observation, coverage.receipt);
    }
    public void scrolled(int windowId) {
        onMain(); Configuration value = configuration;
        if (!closed && active && value != null && value.window.id == windowId) {
            host.discardDwellObservations(host.activeScope());
            // Scroll requests fresh node geometry without cancelling unchanged-content confirmation.
            // Literal content/provider checks still fence every full read before and after geometry.
            host.requestNativeText(false);
        }
    }
    public void scrolled(AccessibilityEvent event) {
        onMain(); Objects.requireNonNull(event); scrolled(event.getWindowId());
        Configuration value = configuration;
        ScrollMotionLedger.Scope scope = host.scrollScope();
        if (Build.VERSION.SDK_INT >= 33 && scrollReader != null && value != null && scrollCurrent(scope)) {
            scrollReader.offer(event, scope, value.window.packageName);
        }
    }
    private boolean scrollCurrent(ScrollMotionLedger.Scope scope) {
        Transition selected = transition;
        return current(selected) && scope != null && scope.epoch == host.epoch()
                && scope.geometry == selected.configuration.token && scope.windowId == selected.configuration.window.id
                && scope.protection.equals(selected.configuration.protection);
    }
    public boolean dwellPresent(WindowCoverage coverage, CensorEngine.EvidenceReceipt observation) {
        if (!observationCurrent(coverage, observation)) return false;
        List<CensorEngine.DetectedRegion> visible = new ArrayList<>();
        for (WindowRegion member : coverage.regions) visible.add(member.member);
        return host.dwellPresent(observation, coverage.receipt, visible);
    }
    private boolean coverageWindowQualified(Transition selected, CensorEngine.ViewportCoverage receipt) {
        if (!current(selected) || !paintEligible(receipt.scope)) return false;
        AccessibilityWindowReader.Window window = selected.configuration.window;
        return receipt.canvas == null || (receipt.canvas.width == window.displayWidth
                && receipt.canvas.height == window.displayHeight);
    }
    boolean evidenceCurrent(CensorEngine.EvidenceReceipt receipt) {
        return receipt != null && paintEligible(receipt.scope) && host.evidenceCurrent(receipt);
    }
    CensorEngine.EvidenceClaim claimEvidence(CensorEngine.EvidenceReceipt receipt, Object consumerScope) {
        return evidenceCurrent(receipt) ? host.claimEvidence(receipt, consumerScope) : null;
    }
    AccessibilityCensorHost host() { return host; }
    SnapshotCensorSurface surface() { return surface; }
    AccessibilityWindowReader windows() { return windows; }

    private void surfaceChanged() { SnapshotCensorSurface value = surface; if (value != null) value.presentationChanged(); }

    private AccessibilityCapturer.Target captureTarget(CensorEngine.CaptureRequest request) {
        Transition selected = transition; Configuration value = selected == null ? null : selected.configuration;
        if (!current(selected) || request.geometry != value.token || request.epoch != host.epoch()
                || !request.protectionScope.equals(value.protection)) return null;
        AccessibilityWindowReader.Window actual;
        try { actual = windows.observe(value.window.packageName, value.window.id); }
        catch (RuntimeException | Error failure) { invalidate(selected); throw failure; }
        if (!current(selected)) return null;
        if (!value.window.same(actual)) {
            invalidate(selected);
            try { observer.windowRejected(value.token, value.window, actual); }
            catch (RuntimeException diagnosticFailure) { /* Scope remains rejected. */ }
            return null;
        }
        qualify(selected);
        return new AccessibilityCapturer.Target(request.epoch, value.token, actual.id, actual.packageName,
                actual.displayWidth, actual.displayHeight, actual.left, actual.top, actual.width, actual.height);
    }

    private boolean current(Transition selected) {
        return selected != null && transition == selected && configuration == selected.configuration
                && active && !closed && !selected.invalid;
    }
    private boolean paintEligible(CensorEngine.ActiveScope scope) {
        Transition selected = transition;
        return current(selected) && selected.qualified && scope != null
                && scope.geometry == selected.configuration.token
                && scope.protectionScope.equals(selected.configuration.protection) && host.isCurrent(scope);
    }
    private boolean sourceCurrent(long epoch, long geometry) {
        CensorEngine.ActiveScope scope = host.activeScope();
        return paintEligible(scope) && scope.epoch == epoch && scope.geometry == geometry;
    }
    private void qualify(Transition selected) {
        if (!current(selected)) return;
        CensorEngine.ActiveScope scope = host.activeScope();
        if (scope == null || scope.geometry != selected.configuration.token
                || !scope.protectionScope.equals(selected.configuration.protection)) return;
        boolean changed;
        synchronized (selected) {
            changed = !selected.qualified;
            if (!selected.invalid) { selected.scope = scope; selected.qualified = true; }
        }
        if (changed) main.post(() -> { if (current(selected) && selected.qualified) surface.presentationAllowed(true); });
    }
    private void invalidate(Transition selected) {
        if (selected == null) return;
        synchronized (selected) { if (selected.invalid) return; selected.invalid = true; selected.qualified = false; }
        // Owner-qualified withdrawal is thread-safe and cannot clear a different surface's map.
        // Do not wait for the main looper to make already-invalid geometry nonbillable.
        surface.scopeInvalidated(selected.scope);
        main.post(() -> { if (transition == selected && !closed) stop(); });
    }

    private final class NativeProvider implements NativeTextReader.Provider {
        @Override public boolean addressRefreshSupported() { return true; }
        @Override public void beforeConfirmationScheduled(CensorEngine.NativeTextInput input, NativeTextReader.Scope scope) {
            ProductCensorQualification.nativeConfirmationPending(input);
        }
        @Override public NativeTextReader.Scope openScope(CensorEngine.NativeTextInput input) {
            Transition selected = transition;
            Configuration value = selected == null ? null : selected.configuration;
            if (!eligible(input, selected)) return null;
            nativeReadSelected.run();
            boolean actual;
            try { actual = eligible(input, selected) && windows.current(value.window, input.epoch, input.identity,
                    WindowReadCost.Operation.OPEN_SCOPE, "PROVIDER_OPEN"); }
            catch (RuntimeException | Error failure) { invalidate(selected); throw failure; }
            if (!actual) { invalidate(selected); return null; }
            if (!eligible(input, selected)) return null;
            qualify(selected);
            return new NativeTextReader.Scope(value.window.displayWidth, value.window.displayHeight,
                    value.window.id, value.window.packageName, contentGeneration.get());
        }
        @Override public NativeTextReader.Tree open(CensorEngine.NativeTextInput input) {
            Transition selected = transition;
            Configuration value = selected == null ? null : selected.configuration; long generation = contentGeneration.get();
            if (!eligible(input, selected)) return null;
            nativeReadSelected.run(); // Exact adverse-control boundary after immutable entry selection.
            NativeTextReader.Tree tree;
            try { tree = windows.open(value.window, generation, input.epoch, input.identity,
                    WindowReadCost.Operation.OPEN_TREE, "PROVIDER_OPEN"); }
            catch (RuntimeException | Error failure) { invalidate(selected); throw failure; }
            if (tree == null) { invalidate(selected); return null; }
            boolean transferred = false;
            try {
                if (!eligible(input, selected)) return null;
                qualify(selected);
                transferred = true; return tree;
            } finally { if (!transferred) windows.release(tree); }
        }
        @Override public boolean current(CensorEngine.NativeTextInput input, NativeTextReader.Tree tree) {
            return current(input, (NativeTextReader.Scope) tree, NativeTextReader.Boundary.RESULT_END);
        }
        @Override public boolean current(CensorEngine.NativeTextInput input, NativeTextReader.Tree tree, NativeTextReader.Boundary boundary) {
            return current(input, (NativeTextReader.Scope) tree, boundary);
        }
        @Override public boolean current(CensorEngine.NativeTextInput input, NativeTextReader.Scope tree, NativeTextReader.Boundary boundary) {
            nativeBoundaryControl.beforeCheck(input, boundary);
            Transition selected = transition;
            Configuration value = selected == null ? null : selected.configuration;
            if (!eligible(input, selected)) return false;
            if (!matches(tree, value)) return false;
            boolean actual;
            try { actual = windows.current(value.window, input.epoch, input.identity,
                    WindowReadCost.Operation.CURRENT, boundary.name()); }
            catch (RuntimeException | Error failure) { invalidate(selected); throw failure; }
            if (!actual) invalidate(selected);
            return actual && eligible(input, selected);
        }
        @Override public boolean locallyCurrent(CensorEngine.NativeTextInput input, NativeTextReader.Tree tree) {
            return locallyCurrent(input, (NativeTextReader.Scope) tree);
        }
        @Override public boolean locallyCurrent(CensorEngine.NativeTextInput input, NativeTextReader.Scope tree) {
            nativeBoundaryControl.beforeCheck(input, NativeTextReader.Boundary.TRAVERSAL);
            Transition selected = transition;
            Configuration value = selected == null ? null : selected.configuration;
            return eligible(input, selected) && matches(tree, value) && eligible(input, selected);
        }
        private boolean matches(NativeTextReader.Scope tree, Configuration value) {
            return tree.windowId == value.window.id && tree.packageName.equals(value.window.packageName)
                    && tree.width == value.window.displayWidth && tree.height == value.window.displayHeight;
        }
        private boolean eligible(CensorEngine.NativeTextInput input, Transition selected) {
            Configuration value = selected == null ? null : selected.configuration;
            return AccessibilityCensorController.this.current(selected) && !input.cancelled()
                    && input.geometry == value.token && input.epoch == host.epoch()
                    && input.protectionScope.equals(value.protection);
        }
    }

    @RequiresApi(30)
    private static WindowManager.LayoutParams overlayParameters() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START; params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        return params;
    }

    private static void onMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main-looper controller lifecycle required");
    }
}
