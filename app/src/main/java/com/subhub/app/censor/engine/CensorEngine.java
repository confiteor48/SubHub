package com.subhub.app.censor.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Replacement runtime owner: capture admission, inference, pixels and presentation share one scope.
 * Platform adapters provide acquisition and model work, never a legacy tracker or scene mutation.
 * No platform, model, resource-cleanup or observer callback runs while the owner lock is held.
 */
public final class CensorEngine<P> implements AutoCloseable {
    public enum NativeTextChange { DEMAND, TREE_DIRTY, HARD_CONTENT }
    public interface Cancellation { void cancel(); }
    public interface Timer {
        /** Enqueue, never invoke inline. A cancelled runnable may still be delivered. */
        Cancellation schedule(Runnable action, long delayMillis);
    }
    public interface Capturer<P> {
        /** Resolve scope and acquire/consume input here; callback may precede or follow return. */
        void capture(CaptureRequest request, CaptureCallback<P> callback);
    }
    public interface CaptureCallback<P> {
        void success(Captured<P> captured);
        void failure(long retryAfterMillis);
        /** A platform-owned temporary could not be disposed; ordinary retry cannot prove drain. */
        void resourceFailure();
    }
    public interface Worker<P> {
        /**
         * Completion is terminal: do not use input pixels afterward. Throwing means no async
         * work owns input; after async launch, arrange unconditional terminal delivery first.
         */
        void start(Input<P> input, Completion completion);
    }
    public interface Completion {
        void success(SceneEngine.Batch batch, WorkTiming timing);
        void failure();
        void cancelled();
        /** Worker-owned temporary/native cleanup failed; an ordinary failed result is insufficient. */
        void resourceFailure();
    }
    public interface NativeTextWorker {
        /** Own roots/probes until cleanup and actual terminal completion, including cancellation. */
        void start(NativeTextInput input, NativeTextCompletion completion);
    }
    public interface NativeTextCompletion {
        default void costStarted(NativeReadCost.Start value) { }
        /** One immutable diagnostic before terminal completion; grants no scene or scheduling authority. */
        default void cost(NativeReadCost value) { }
        /** Whole-node positive evidence only; retains the parent job/root and grants no address lease. */
        default boolean observe(int ordinal, NativeTextResult group) { return false; }
        void success(NativeTextResult read);
        /**
         * Explicit provider-address admission, not a live evidence/pose lease. Compatibility
         * completions may publish but cannot authorize retained handles without an owner decision.
         */
        default NativeTextAdmission admit(NativeTextResult read) { success(read); return null; }
        void failure();
        void cancelled();
        void resourceFailure();
    }
    public static final class NativeTextAdmission {
        public final NativeTextFrame frame;
        private final NativeTextInput input;
        private NativeTextAdmission(NativeTextInput input, NativeTextFrame frame) { this.input = input; this.frame = frame; }
        /** Exact caller and exact admitted record, not equality of reusable numeric tokens. */
        public boolean matches(NativeTextInput selected, NativeTextResult read) { return input == selected && frame.read == read; }
    }
    public static final class NativeTextLane {
        /** Minimum admitted read-start spacing; failed/rejected attempts back off from termination. */
        public final long partition, minimumRefreshMillis, maximumAgeMillis;
        public final Executor executor;
        public final NativeTextWorker worker;
        public NativeTextLane(long partition, long minimumRefreshMillis, long maximumAgeMillis,
                Executor executor, NativeTextWorker worker) {
            if (minimumRefreshMillis <= 0 || maximumAgeMillis <= 0) throw new IllegalArgumentException("native policy");
            this.partition = partition; this.minimumRefreshMillis = minimumRefreshMillis;
            this.maximumAgeMillis = maximumAgeMillis;
            this.executor = Objects.requireNonNull(executor); this.worker = Objects.requireNonNull(worker);
        }
    }
    public interface Observer {
        default void trace(Trace trace) { }
        /** Reported event coordinates only; this callback does not establish image-member motion. */
        default void scrollReceived(ScrollMotionLedger.Receipt receipt) { }
        /** Renderer must acquire a read handle, not keep/mutate runtime scene state. */
        default void presentationChanged() { }
        /** Accepted source-local evidence, not a draw/vsync or failed/incomplete worker result. */
        default void batchAccepted(SceneEngine.Batch batch) { }
        /** Typed native evidence; frame.observationOrdinal distinguishes nonterminal observations. */
        default void nativeTextAccepted(NativeTextFrame frame) { }
        /** May be off-main and may describe cancelled/stale/refused work, never accepted evidence. */
        default void nativeReadCost(NativeReadCost value) { }
        default void nativeReadCostStarted(NativeReadCost.Start value) { }
        /** Canonical source-owned subjects; never inferred from presentation or a draw. */
        default void evidenceAccepted(EvidenceReceipt receipt) { }
        /** Actual qualified surface recording, not capture, inference or compositor completion. */
        default void painted(PaintReceipt receipt) { }
        /** Permanent close has no remaining capture, job, pixel or draw-handle owner. */
        default void drained() { }
    }

    public enum Stage {
        CAPTURE_REQUESTED, CAPTURE_DISPATCHED, CAPTURE_RETURNED, CAPTURE_CALLBACK,
        CAPTURE_REJECTED, CAPTURE_BACKPRESSURE, SOURCE_REJECTED, RESOURCE_FAILED,
        JOB_QUEUED, JOB_REPLACED, JOB_STARTED,
        JOB_COMPLETED, JOB_FAILED, JOB_CANCELLED, JOB_REJECTED, RESULT_REJECTED,
        PRESENTATION_REPLACED, PRESENTATION_REJECTED, PRESENTED, DRAWN, SCOPE_CHANGED
    }

    public static final class Trace {
        public final Stage stage;
        public final long epoch;
        /** Exact IMAGE request lineage; zero means this row has no image-request provenance. */
        public final long source;
        public final SceneEngine.Lane lane;
        public final long atMillis;
        public final long queueMillis;
        /** -1 when pixels have no established timestamp. */
        public final long pixelAgeMillis;
        public final WorkTiming work;

        private Trace(Stage stage, long epoch, long source, SceneEngine.Lane lane,
                long atMillis, long queueMillis, long pixelAgeMillis, WorkTiming work) {
            this.stage = stage;
            this.epoch = epoch;
            this.source = source;
            this.lane = lane;
            this.atMillis = atMillis;
            this.queueMillis = queueMillis;
            this.pixelAgeMillis = pixelAgeMillis;
            this.work = work;
        }
    }

    public static final class WorkTiming {
        public final long preprocessMillis;
        public final long runtimeMillis;
        public final long postprocessMillis;

        public WorkTiming(long preprocessMillis, long runtimeMillis, long postprocessMillis) {
            if (preprocessMillis < 0 || runtimeMillis < 0 || postprocessMillis < 0) {
                throw new IllegalArgumentException("work timing");
            }
            this.preprocessMillis = preprocessMillis;
            this.runtimeMillis = runtimeMillis;
            this.postprocessMillis = postprocessMillis;
        }
    }

    /** Active protection before/after sources, authenticated to this exact runtime owner. */
    public static final class ActiveScope {
        public final long epoch, geometry;
        public final ProtectionScope protectionScope;
        private final Object authority;
        private ActiveScope(Object authority, long epoch, long geometry, ProtectionScope protectionScope) {
            this.authority = authority; this.epoch = epoch; this.geometry = geometry; this.protectionScope = protectionScope;
        }
        public boolean same(ActiveScope other) {
            return other != null && authority == other.authority && epoch == other.epoch
                    && geometry == other.geometry && protectionScope.equals(other.protectionScope);
        }
    }

    /** Immutable canonical source-state capability, minted after accepted input mutation. */
    public static final class EvidenceReceipt {
        public final ActiveScope scope;
        public final SceneEngine.Source source;
        /** Lane whose acceptance triggered this inventory, not provenance of every member. */
        public final SceneEngine.Lane triggerLane;
        /** Trigger input contained positives; subjects alone cannot distinguish an empty lane. */
        public final boolean positiveInput;
        public final long acceptedAtMillis;
        /**
         * All surviving positives whose latest evidence is this exact source, across its lanes.
         * Empty means no positive at this source, not empty input or whole-viewport absence.
         */
        public final List<SceneEngine.AcceptedSubject> subjects;
        /** Null for IMAGE inputs; partial positives are never whole-tree/dwell accounting authority. */
        public final NativeTextResult.Coverage nativeCoverage;
        private final Object observationAuthority;
        private final long nativeGeneration, nativeTreeGeneration;

        private EvidenceReceipt(ActiveScope scope, Object observationAuthority, SceneEngine.Source source, SceneEngine.Lane lane, boolean positiveInput,
                long atMillis, List<SceneEngine.AcceptedSubject> subjects, NativeTextResult.Coverage nativeCoverage,
                long nativeGeneration, long nativeTreeGeneration) {
            this.scope = scope; this.source = source; this.triggerLane = lane;
            this.observationAuthority = observationAuthority;
            this.positiveInput = positiveInput;
            acceptedAtMillis = atMillis; this.subjects = subjects;
            this.nativeCoverage = nativeCoverage; this.nativeGeneration = nativeGeneration; this.nativeTreeGeneration = nativeTreeGeneration;
        }
    }

    /** At-most-once evidence consumption, not proof of persistent statistics or a billing write. */
    public static final class EvidenceClaim {
        public final EvidenceReceipt receipt;
        public final List<SceneEngine.AcceptedSubject> subjects;
        private final Object consumerScope;
        private final AtomicBoolean delivered = new AtomicBoolean();
        private EvidenceClaim(EvidenceReceipt receipt, List<SceneEngine.AcceptedSubject> subjects, Object consumerScope) {
            this.receipt = receipt; this.subjects = subjects; this.consumerScope = consumerScope;
        }
        /** Original capability delivery, once, to its captured consumer. A wrapper cannot rearm it. */
        public List<SceneEngine.AcceptedSubject> takeFor(Object expectedScope) {
            return expectedScope != null && consumerScope == expectedScope && delivered.compareAndSet(false, true)
                    ? subjects : null;
        }
    }

    /** Immutable actual paint metadata; no pixel lease or scene mutation. */
    public static final class PaintReceipt {
        public final ActiveScope scope;
        public final long revision, appearanceGeneration, atMillis;
        /** Null for source-free cold reverse. Never fabricated from active scope. */
        public final SceneEngine.Source canvas;
        /** Every mask retains its original evidence/geometry/native/protection provenance. */
        public final List<SceneEngine.Mask> members;
        public final boolean reverseBackground;
        public final long compositorAtMillis = -1;
        private PaintReceipt(ActiveScope scope, long revision, long generation, long at,
                SceneEngine.Source canvas, List<SceneEngine.Mask> members, boolean reverse) {
            this.scope = scope; this.revision = revision; appearanceGeneration = generation; atMillis = at;
            this.canvas = canvas; this.members = Collections.unmodifiableList(members); reverseBackground = reverse;
        }
    }

    public enum CoverageState { WITHDRAWN, UNOBSERVED, EXPIRED, NO_ELIGIBLE_REGIONS, AVAILABLE }

    /** Supported detected geometry, not ordinary paint, tap interiors or a physical pixel bound. */
    public static final class DetectedRegion {
        public final long identity, partition, textGroup;
        public final String category;
        public final SceneEngine.Source evidenceSource, geometrySource;
        /** Clipped once to the physical display canvas; never a group-union interior. */
        public final SceneEngine.Region displayRegion;
        public final boolean nativeValidated;
        public final boolean dwellPositive;
        public final boolean physicalErrorKnown = false;
        private DetectedRegion(SceneEngine.Mask member, SceneEngine.Region clipped) {
            identity = member.identity; partition = member.partition; textGroup = member.textGroup;
            category = member.category; evidenceSource = member.evidenceSource;
            geometrySource = member.geometrySource; displayRegion = clipped;
            nativeValidated = member.nativeValidated;
            dwellPositive = member.dwellPositive;
        }
    }

    /**
     * Metadata-only viewport availability, independent of paint and source-witness inventories.
     * NO_ELIGIBLE_REGIONS is NOT whole-viewport absence or detector completeness. AVAILABLE is
     * source-supported geometry, not a claim that it still matches physical pixels now.
     */
    public static final class ViewportCoverage {
        public final CoverageState state;
        /** Null when withdrawn. Cold reverse has no observed source or detected region. */
        public final ActiveScope scope;
        public final SceneEngine.Source canvas;
        public final long queriedAtMillis, validBeforeMillis;
        public final int unresolvedTracks, tentativeTracks, ineligibleMembers;
        public final List<DetectedRegion> regions;
        public final long compositorAtMillis = -1;
        private final Object owner, publication;
        private final SceneEngine.Source imageTarget;
        private final long nativeGeneration, nativeTreeGeneration;
        private ViewportCoverage(Object owner, Object publication, ActiveScope scope,
                SceneEngine.Source imageTarget, long nativeGeneration, long nativeTreeGeneration, CoverageState state,
                SceneEngine.Snapshot snapshot, long now, List<DetectedRegion> regions, int ineligible) {
            this.owner = owner; this.publication = publication; this.scope = scope;
            this.imageTarget = imageTarget; this.nativeGeneration = nativeGeneration; this.state = state;
            this.nativeTreeGeneration = nativeTreeGeneration;
            canvas = snapshot == null ? null : snapshot.destination; queriedAtMillis = now;
            validBeforeMillis = snapshot == null ? Long.MAX_VALUE : snapshot.nextChangeAtMillis;
            unresolvedTracks = snapshot == null ? 0 : snapshot.unresolvedTracks;
            tentativeTracks = snapshot == null ? 0 : snapshot.tentativeTracks;
            ineligibleMembers = ineligible; this.regions = Collections.unmodifiableList(regions);
        }
    }

    public static final class Policy {
        public final long minimumCaptureMillis;
        public final long failureBackoffMillis;
        public final int maximumPixelFrames;
        public final int maximumEffectSources;
        public final boolean parallelQuality;

        public Policy(long minimumCaptureMillis, long failureBackoffMillis, int maximumPixelFrames,
                int maximumEffectSources, boolean parallelQuality) {
            if (minimumCaptureMillis <= 0 || failureBackoffMillis < 0 || maximumPixelFrames < 2
                    || maximumEffectSources < 0 || maximumEffectSources > maximumPixelFrames) {
                throw new IllegalArgumentException("runtime policy");
            }
            this.minimumCaptureMillis = minimumCaptureMillis;
            this.failureBackoffMillis = failureBackoffMillis;
            this.maximumPixelFrames = maximumPixelFrames;
            this.maximumEffectSources = maximumEffectSources;
            this.parallelQuality = parallelQuality;
        }
    }

    public static final class LaneWorker<P> {
        public final SceneEngine.Lane lane;
        public final Executor executor;
        public final Worker<P> worker;

        public LaneWorker(SceneEngine.Lane lane, Executor executor, Worker<P> worker) {
            this.lane = Objects.requireNonNull(lane);
            this.executor = Objects.requireNonNull(executor);
            this.worker = Objects.requireNonNull(worker);
        }
    }

    public static final class CaptureRequest {
        public final long identity;
        public final long epoch;
        public final long geometry;
        public final long requestedAtMillis;
        public final ProtectionScope protectionScope;
        private final BooleanSupplier gate;

        private CaptureRequest(long identity, long epoch, long geometry, long requestedAtMillis,
                ProtectionScope protectionScope, BooleanSupplier gate) {
            this.identity = identity;
            this.epoch = epoch;
            this.geometry = geometry;
            this.requestedAtMillis = requestedAtMillis;
            this.protectionScope = protectionScope;
            this.gate = gate;
        }

        /**
         * Invoke immediately before the request API or consuming a continuous-source slot, not
         * before scope resolution. Continuous input has its own acquisition clock and session.
         */
        public boolean tryDispatch() { return gate.getAsBoolean(); }
    }

    /** Owns one platform payload. Duplicate delivery of this same object cannot free accepted pixels. */
    public static final class Captured<P> implements AutoCloseable {
        public final P pixels;
        public final int width;
        public final int height;
        public final long pixelAtMillis;
        private final Runnable disposer;
        private final AtomicInteger ownership = new AtomicInteger(); // unclaimed, transferred, closed

        public Captured(P pixels, int width, int height, long pixelAtMillis, Runnable disposer) {
            if (width <= 0 || height <= 0 || pixelAtMillis < -1) throw new IllegalArgumentException("captured");
            this.pixels = Objects.requireNonNull(pixels);
            this.width = width;
            this.height = height;
            this.pixelAtMillis = pixelAtMillis;
            this.disposer = Objects.requireNonNull(disposer);
        }

        private boolean claim() { return ownership.compareAndSet(0, 1); }
        private void releaseOwned() { if (ownership.compareAndSet(1, 2)) disposer.run(); }
        /** Discards only an untransferred payload. Accepted resources belong to the runtime. */
        @Override public void close() { if (ownership.compareAndSet(0, 2)) disposer.run(); }
    }

    public static final class Input<P> {
        public final SceneEngine.Source source;
        public final P pixels;
        private final InputLifetime lifetime;
        private Input(SceneEngine.Source source, P pixels, BooleanSupplier cancellation) {
            this.source = source; this.pixels = pixels; lifetime = new InputLifetime(cancellation);
        }
        public boolean cancelled() { return lifetime.cancelled(); }
        public void onCancellation(Cancellation handle) { lifetime.onCancellation(handle); }
        private void signalCancellation() { lifetime.signalCancellation(); }
        private void finish() { lifetime.finish(); }
    }

    /** Independent provider read request. It is not a captured image or a source descriptor yet. */
    public static final class NativeTextInput {
        public final long identity, epoch, geometry, hardGeneration;
        public final ProtectionScope protectionScope;
        private final InputLifetime lifetime;
        private final LongSupplier beginRead, clock, treeGeneration;
        private volatile long scanGeneration = -1;
        private volatile NativeTextResult.ReadMode readMode;
        private NativeTextInput(long identity, long epoch, long geometry, long hardGeneration, ProtectionScope protectionScope, BooleanSupplier cancellation,
                LongSupplier beginRead, LongSupplier clock, LongSupplier treeGeneration) {
            this.identity = identity; this.epoch = epoch; this.geometry = geometry;
            this.protectionScope = protectionScope;
            this.hardGeneration = hardGeneration; this.treeGeneration = treeGeneration;
            lifetime = new InputLifetime(cancellation); this.beginRead = beginRead; this.clock = clock;
        }
        /** Call once immediately before root/provider access. Negative means admission was cancelled. */
        public long beginRead() { return beginRead(NativeTextResult.ReadMode.FULL_DISCOVERY); }
        public long beginRead(NativeTextResult.ReadMode selected) {
            synchronized (this) {
                if (readMode != null) return -1;
                readMode = Objects.requireNonNull(selected);
            }
            return beginRead.getAsLong();
        }
        public NativeTextResult.ReadMode readMode() { return readMode; }
        public long nowMillis() { return clock.getAsLong(); }
        /** Actual engine dirty-tree revision at this provider boundary, not a renamed scan token. */
        public long treeGeneration() { return treeGeneration.getAsLong(); }
        /** Captured atomically by beginRead; later tree revisions cannot rename the original scan. */
        public long scanGeneration() { return scanGeneration; }
        public boolean cancelled() { return lifetime.cancelled(); }
        public void onCancellation(Cancellation handle) { lifetime.onCancellation(handle); }
    }

    /** One cancellation/terminal implementation for both pixel and native operation lifetimes. */
    private static final class InputLifetime {
        private final BooleanSupplier cancellation;
        private Cancellation operationCancellation;
        private boolean signalled;
        private boolean finished;
        private boolean cancellationInvoked;

        private InputLifetime(BooleanSupplier cancellation) { this.cancellation = cancellation; }
        boolean cancelled() { return cancellation.getAsBoolean(); }

        /**
         * Bind an exact per-operation handle, never a lane-wide "cancel current inference" call.
         * The handle must safely tolerate a race with its own terminal completion/close.
         */
        void onCancellation(Cancellation handle) {
            boolean invoke;
            synchronized (this) {
                if (finished) return;
                if (operationCancellation != null) throw new IllegalStateException("cancellation already bound");
                operationCancellation = Objects.requireNonNull(handle);
                invoke = (signalled || cancelled()) && !cancellationInvoked;
                if (invoke) cancellationInvoked = true;
            }
            if (invoke) handle.cancel();
        }

        private void signalCancellation() {
            Cancellation handle;
            synchronized (this) {
                if (finished) return;
                signalled = true;
                handle = cancellationInvoked ? null : operationCancellation;
                if (handle != null) cancellationInvoked = true;
            }
            if (handle != null) handle.cancel();
        }

        private synchronized void finish() { finished = true; operationCancellation = null; }
    }

    private enum JobPhase { QUEUED, RUNNING, DONE }

    private final class Frame {
        final SceneEngine.Source source;
        final Captured<P> payload;
        int references = 1;

        Frame(SceneEngine.Source source, Captured<P> payload) { this.source = source; this.payload = payload; }

        synchronized boolean retain() {
            if (references == 0) return false;
            references++;
            return true;
        }

        void release() {
            synchronized (this) {
                if (references == 0) throw new IllegalStateException("frame released twice");
                if (--references != 0) return;
            }
            try { payload.releaseOwned(); }
            catch (RuntimeException failure) { resourceFailed(source); throw failure; }
            finally {
                scene.unpin(source);
                frameReleased(this);
            }
        }
    }

    private final class LaneState {
        final LaneWorker<P> specification;
        Frame pending;
        long pendingAt;
        Job active;
        LaneState(LaneWorker<P> specification) { this.specification = specification; }
    }

    private final class Job {
        final LaneState lane;
        final Frame frame;
        final long queuedAt;
        final Input<P> input;
        volatile boolean cancellation;
        JobPhase phase = JobPhase.QUEUED;
        long startedAt;

        Job(LaneState lane, Frame frame, long queuedAt) {
            this.lane = lane; this.frame = frame; this.queuedAt = queuedAt;
            input = new Input<>(frame.source, frame.payload.pixels, () -> cancellation);
        }
    }

    private enum NativeTerminal { READ, FAILURE, CANCELLED, RESOURCE_FAILURE }

    private final class NativeJob {
        final long epoch = CensorEngine.this.epoch;
        final long generation = nativeGeneration;
        final NativeTextInput input;
        final long queuedAt;
        volatile boolean cancellation;
        JobPhase phase = JobPhase.QUEUED;
        long readStartedAt = -1;
        long readTreeGeneration = -1;
        long startedAt = -1;
        boolean costReported;
        long costInvokedAt = -1;
        NativeTextResult.ReadMode costReadMode;
        int lastObservationOrdinal;
        final Set<String> observedNodes = new java.util.HashSet<>();
        NativeJob(long identity, long queuedAt) {
            this.queuedAt = queuedAt;
            input = new NativeTextInput(identity, epoch, geometry, generation, protectionScope, () -> cancellation,
                    () -> beginNativeRead(this), clock, CensorEngine.this::nativeTreeGeneration);
        }
    }

    private final class Ticket {
        final CaptureRequest request;
        boolean dispatched;
        boolean returned;
        boolean callback;
        long receivedAt;
        long retryAfter;
        Captured<P> captured;

        Ticket(long identity, long at) {
            request = new CaptureRequest(identity, epoch, geometry, at, protectionScope, () -> beginDispatch(this));
        }
    }

    private static final class TimerTicket {
        final long at;
        private Cancellation cancellation;
        private boolean cancelled;
        TimerTicket(long at) { this.at = at; }
        synchronized void attach(Cancellation value) { cancellation = value; if (cancelled) value.cancel(); }
        synchronized void cancel() { cancelled = true; if (cancellation != null) cancellation.cancel(); }
    }

    private final class Presentation {
        final SceneEngine.Snapshot snapshot;
        final List<Frame> effects;
        final SceneEngine.Source canvasSource;
        Presentation(SceneEngine.Snapshot snapshot, List<Frame> effects, SceneEngine.Source canvasSource) {
            this.snapshot = snapshot; this.effects = effects; this.canvasSource = canvasSource;
        }
        void release() { releaseFrames(effects); }
    }

    /** One read lease. A draw can finish safely after stop or presentation replacement. */
    public final class RenderView implements AutoCloseable {
        public final SceneEngine.Snapshot snapshot;
        /** Explicit reverse-canvas descriptor. Null when disabled or the canvas is native text. */
        public final SceneEngine.Source canvasSource;
        private final List<Frame> effects;
        private final AtomicBoolean released = new AtomicBoolean();
        private RenderView(Presentation presentation) {
            snapshot = presentation.snapshot;
            canvasSource = presentation.canvasSource;
            effects = new ArrayList<>(presentation.effects);
            for (Frame frame : effects) {
                if (!frame.retain()) throw new IllegalStateException("closed presentation source");
            }
            renderViews++;
        }
        /** Null requires the style's solid fallback; never sample another source's pixels. */
        public P sourcePixels(long sequence) {
            if (released.get()) throw new IllegalStateException("closed render view");
            for (Frame frame : effects) if (frame.source.sequence == sequence) return frame.payload.pixels;
            return null;
        }
        /** May be absent even with an approved descriptor; never substitutes member/latest pixels. */
        public P canvasPixels() {
            if (released.get()) throw new IllegalStateException("closed render view");
            return canvasSource == null ? null : sourcePixels(canvasSource.sequence);
        }
        @Override public void close() {
            if (released.compareAndSet(false, true)) {
                try { releaseFrames(effects); }
                finally { renderViewReleased(); }
            }
        }
    }

    private final Policy policy;
    private final LongSupplier clock;
    private final Timer timer;
    private final Capturer<P> capturer;
    private final ScrollMotionLedger scrollMotion;
    private int protectedWindowId = -1;
    private final Executor presentationExecutor;
    private final Observer observer;
    private final NativeTextLane nativeText;
    private final SceneEngine scene;
    private final int maximumRenderBases;
    private final int maximumBasisSources;
    private final EnumMap<SceneEngine.Lane, LaneState> lanes = new EnumMap<>(SceneEngine.Lane.class);
    private final Map<Long, Frame> frames = new LinkedHashMap<>();
    /** Metadata pins, not extra bitmap ownership or a second mutable camera/scene. */
    private final Map<Long, SceneEngine.Source> renderBases = new HashMap<>();
    private final Object scopeAuthority = new Object();
    private long epoch = 1;
    private long geometry;
    private ProtectionScope protectionScope = ProtectionScope.UNSPECIFIED;
    private boolean canvasSampling;
    private long nextRequest;
    private long lastDispatchAt = -1;
    private long retryNotBefore;
    private long destination;
    private SceneEngine.Source destinationSource;
    private SceneEngine.Lane canvasLane;
    /** Actual successful image acquisition, independent of FAST/QUALITY/TEXT completion. */
    private SceneEngine.Source latestImageSource;
    private SceneEngine.Source nativeBasis;
    private long nativeBasisGeneration = -1;
    private long nativeBasisTreeGeneration = -1;
    private int displayWidth, displayHeight;
    private long nextSource, nextNativeRequest, nativeGeneration, nativeTreeGeneration;
    /** Historical FULL credit may bridge only successful native parents of this exact segment. */
    private Object nativeAccountingChain = new Object();
    private long nativeDemandAt = Long.MAX_VALUE, lastNativeTerminatedAt = -1;
    private long nativeAdmittedReadStartedAt = -1, nativeAdmittedEpoch = -1, nativeAdmittedGeneration = -1;
    private NativeJob nativeJob;
    private TimerTicket nativeTimer;
    private boolean nativeEnabled;
    private boolean active;
    private boolean closed;
    private boolean firstFastCompleted;
    private long requestedCaptureMillis;
    private boolean parallelQuality;
    private EnumSet<SceneEngine.Lane> enabledLanes = EnumSet.allOf(SceneEngine.Lane.class);
    private boolean drainedReported;
    private Ticket capture;
    private TimerTicket captureTimer;
    private TimerTicket presentationTimer;
    private Presentation pendingPresentation;
    private Presentation currentPresentation;
    // Metadata publication owns no pixel leases. Source admission also fences its receipts.
    private SceneEngine.Snapshot coverageSnapshot;
    private Object coveragePublication = new Object();
    private Object observationAuthority = new Object();
    private boolean notificationQueued;
    private long notificationIdentity;
    private long callbackFailures;
    private long resourceFailures;
    private int unadoptedDisposals;
    private int renderViews;
    private long basisAdmissionDrops;
    private long nativeCostDiagnosticFailures;

    public CensorEngine(Policy policy, SceneEngine.Policy scenePolicy, LongSupplier clock, Timer timer,
            Capturer<P> capturer, List<LaneWorker<P>> workers, Executor presentationExecutor, Observer observer) {
        this(policy, scenePolicy, clock, timer, capturer, workers, presentationExecutor, observer, null);
    }

    public CensorEngine(Policy policy, SceneEngine.Policy scenePolicy, LongSupplier clock, Timer timer,
            Capturer<P> capturer, List<LaneWorker<P>> workers, Executor presentationExecutor, Observer observer,
            NativeTextLane nativeText) {
        this.policy = Objects.requireNonNull(policy);
        requestedCaptureMillis = policy.minimumCaptureMillis;
        parallelQuality = policy.parallelQuality;
        this.clock = Objects.requireNonNull(clock);
        this.timer = Objects.requireNonNull(timer);
        this.capturer = Objects.requireNonNull(capturer);
        this.presentationExecutor = Objects.requireNonNull(presentationExecutor);
        this.observer = Objects.requireNonNull(observer);
        this.nativeText = nativeText; nativeEnabled = nativeText != null;
        SceneEngine.Policy selectedScenePolicy = Objects.requireNonNull(scenePolicy);
        scene = new SceneEngine(epoch, selectedScenePolicy);
        scrollMotion = new ScrollMotionLedger(selectedScenePolicy.maximumSources, 8, 192);
        maximumRenderBases = Math.min(selectedScenePolicy.maximumTracks, selectedScenePolicy.maximumCorrespondences);
        // Reserve acquisition/job/frame capacity plus the detector/canvas metadata pin. Optional
        // display metadata must not starve active work or the latest pending source after disposal.
        // Native replacement reserves one additional source beside its current basis; do not
        // release valid current metadata or evict graph nodes just to validate a failed read.
        maximumBasisSources = Math.max(0, selectedScenePolicy.maximumSources - policy.maximumPixelFrames
                - 1 - (nativeText == null ? 0 : 1));
        for (LaneWorker<P> worker : workers) {
            if (lanes.put(worker.lane, new LaneState(worker)) != null) throw new IllegalArgumentException("duplicate lane");
        }
        if (!lanes.containsKey(SceneEngine.Lane.FAST)) throw new IllegalArgumentException("fast lane required");
    }

    public void start(long geometry) {
        start(geometry, ProtectionScope.UNSPECIFIED);
    }

    public void start(long geometry, ProtectionScope protectionScope) {
        start(geometry, protectionScope, -1);
    }

    public void start(long geometry, ProtectionScope protectionScope, int windowId) {
        Objects.requireNonNull(protectionScope);
        if (windowId < -1) throw new IllegalArgumentException("protected window");
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (closed) throw new IllegalStateException("closed engine");
            if (active) throw new IllegalStateException("already active");
            this.geometry = geometry;
            protectedWindowId = windowId;
            this.protectionScope = protectionScope;
            active = true;
            invalidate(actions);
            wake(clock.getAsLong(), actions);
            demandNative(clock.getAsLong(), actions);
        }
        run(actions);
    }

    public void changeScope(long geometry) {
        changeScopeInternal(geometry, null, null);
    }

    public void changeScope(long geometry, ProtectionScope protectionScope) {
        changeScopeInternal(geometry, Objects.requireNonNull(protectionScope), null);
    }

    public void changeScope(long geometry, ProtectionScope protectionScope, int windowId) {
        if (windowId < -1) throw new IllegalArgumentException("protected window");
        changeScopeInternal(geometry, Objects.requireNonNull(protectionScope), windowId);
    }

    private void changeScopeInternal(long geometry, ProtectionScope protectionScope, Integer windowId) {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            this.geometry = geometry;
            if (windowId != null) protectedWindowId = windowId;
            if (protectionScope != null) this.protectionScope = protectionScope;
            invalidate(actions);
            wake(clock.getAsLong(), actions);
            demandNative(clock.getAsLong(), actions);
        }
        run(actions);
    }

    /** Appearance resource policy only: no scene mutation, age renewal or capture demand. */
    public void canvasSamplingControls(boolean enabled) {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (closed || canvasSampling == enabled) return;
            canvasSampling = enabled;
            if (active && destination != 0) queuePresentation(actions);
        }
        run(actions);
    }

    public void stop() {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (!active) return;
            active = false;
            invalidate(actions);
        }
        run(actions);
    }

    @Override public void close() {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (closed) return;
            closed = true;
            active = false;
            invalidate(actions);
            checkDrained(actions);
        }
        run(actions);
    }

    public void requestCaptureAt(long atMillis) {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) { wake(atMillis, actions); }
        run(actions);
    }

    public synchronized ScrollMotionLedger.Scope scrollScope() {
        return !active || closed || resourceFailures != 0 ? null
                : new ScrollMotionLedger.Scope(epoch, geometry, protectedWindowId, protectionScope);
    }

    /** Ingestion only. No graph edge, fake current Source or viewport-wide presentation is added. */
    public ScrollMotionLedger.Receipt observeScroll(ScrollMotionLedger.Sample sample, boolean missingLookup) {
        Objects.requireNonNull(sample);
        List<Runnable> actions = new ArrayList<>();
        ScrollMotionLedger.Receipt receipt;
        synchronized (this) {
            if (!active || closed || resourceFailures != 0) return null;
            long now = clock.getAsLong();
            receipt = missingLookup ? scrollMotion.gap(sample, now) : scrollMotion.observe(sample, now);
            actions.add(() -> observer.scrollReceived(receipt));
        }
        run(actions);
        return receipt;
    }

    public synchronized ScrollMotionLedger.Continuation scrollContinuation(SceneEngine.Source origin, long owner, long producer) {
        return scrollMotion.continuation(origin, owner, producer);
    }

    /** Native demand is independent of image acquisition and its platform spacing. */
    public void requestNativeTextAt(long atMillis, boolean contentChanged) {
        requestNativeTextAt(atMillis, contentChanged ? NativeTextChange.HARD_CONTENT : NativeTextChange.DEMAND);
    }

    public void requestNativeTextAt(long atMillis, NativeTextChange change) {
        if (atMillis < 0) throw new IllegalArgumentException("native request time");
        Objects.requireNonNull(change);
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (nativeText == null || !nativeEnabled || !active || closed) return;
            if (change != NativeTextChange.DEMAND) {
                nativeTreeGeneration++; observationAuthority = new Object();
                if (change == NativeTextChange.HARD_CONTENT) {
                    nativeAccountingChain = new Object();
                    nativeGeneration++; scene.fenceNativeEvidence(nativeText.partition); cancelNative(actions);
                }
            }
            demandNative(atMillis, actions);
        }
        run(actions);
    }

    public void nativeTextControls(boolean enabled) {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (enabled && nativeText == null) throw new IllegalStateException("native reader unavailable");
            if (closed || nativeEnabled == enabled) return;
            nativeEnabled = enabled;
            if (enabled) demandNative(clock.getAsLong(), actions);
            else {
                nativeAccountingChain = new Object();
                nativeGeneration++; nativeDemandAt = Long.MAX_VALUE;
                nativeTreeGeneration++;
                cancelNativeTimer(actions); cancelNative(actions);
                if (nativeBasis != null) { scene.unpin(nativeBasis); nativeBasis = null; }
                scene.clearNative(nativeText.partition);
                if (destination != 0) queuePresentation(actions);
            }
        }
        run(actions);
    }

    public synchronized boolean nativeTextInFlight() { return nativeJob != null; }
    public synchronized long nativeTreeGeneration() { return nativeTreeGeneration; }

    /** A failed read is not an empty read; only current accepted finite native coverage suppresses OCR. */
    public synchronized boolean imageTextSuppressed() {
        return nativeEnabled && nativeBasisGeneration == nativeGeneration
                && nativeBasisTreeGeneration == nativeTreeGeneration
                && scene.hasNativeTextCoverage(nativeText.partition, clock.getAsLong());
    }

    /**
     * Change execution controls without replacing platform/callback ownership. Disabling a lane
     * cancels only that lane; an executing job keeps its pixels until its real terminal callback.
     * Scope/config changes must additionally use changeScope to reject old admitted sources.
     */
    public void executionControls(long captureMillis, boolean parallelQuality,
            java.util.Set<SceneEngine.Lane> enabled) {
        if (captureMillis < 0) throw new IllegalArgumentException("capture interval");
        Objects.requireNonNull(enabled);
        EnumSet<SceneEngine.Lane> selected = enabled.isEmpty()
                ? EnumSet.noneOf(SceneEngine.Lane.class) : EnumSet.copyOf(enabled);
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (closed) return;
            selected.retainAll(lanes.keySet());
            requestedCaptureMillis = Math.max(policy.minimumCaptureMillis, captureMillis);
            this.parallelQuality = parallelQuality;
            enabledLanes = selected;
            for (LaneState lane : lanes.values()) {
                if (selected.contains(lane.specification.lane)) continue;
                if (lane.pending != null) { actions.add(lane.pending::release); lane.pending = null; }
                cancelJob(lane.active, actions);
            }
            cancelTimer(actions);
            pump(actions);
            wake(clock.getAsLong(), actions);
        }
        run(actions);
    }

    public boolean motion(SceneEngine.Motion motion) {
        return motion(new SceneEngine.MotionPair(Collections.singletonList(Objects.requireNonNull(motion))));
    }

    public boolean motion(SceneEngine.MotionPair motion) {
        Objects.requireNonNull(motion);
        List<Runnable> actions = new ArrayList<>();
        boolean accepted;
        synchronized (this) {
            SceneEngine.Source selected = scene.source(motion.to);
            SceneEngine.Source previous = renderBases.get(motion.partition);
            boolean advance = active && motion.authority == SceneEngine.MotionAuthority.IMAGE_CORRESPONDENCE
                    && selected != null && (previous == null || selected.sequence > previous.sequence);
            boolean basisRejected = false;
            if (advance) {
                Set<Long> basisSources = new java.util.HashSet<>();
                for (Map.Entry<Long, SceneEngine.Source> entry : renderBases.entrySet()) {
                    if (entry.getKey() != motion.partition) basisSources.add(entry.getValue().sequence);
                }
                basisSources.add(selected.sequence);
                Set<Long> nativeSources = nativeBasisSources(); basisSources.addAll(nativeSources);
                if ((long) renderBases.size() + (previous == null ? 1 : 0) + nativeSources.size() > maximumRenderBases
                        || basisSources.size() > maximumBasisSources || !scene.pin(selected)) {
                    basisRejected = true;
                    advance = false; // A valid spatial map can still help later detector/quality work.
                }
            }
            SceneEngine.MotionAdmission admission = active ? scene.admitMotion(motion) : SceneEngine.MotionAdmission.INVALID;
            accepted = admission == SceneEngine.MotionAdmission.ADMITTED;
            if (accepted && basisRejected) basisAdmissionDrops++;
            if (advance) {
                if (accepted) {
                    renderBases.put(motion.partition, selected);
                    if (previous != null) scene.unpin(previous);
                } else scene.unpin(selected);
            }
            if (admission == SceneEngine.MotionAdmission.CONFLICT) {
                // This owner alone advances epoch and cancels work; executing pixels/read leases
                // remain held until their actual terminal completion or render-handle release.
                invalidate(actions);
                wake(clock.getAsLong(), actions);
            } else if (accepted && destination != 0) queuePresentation(actions);
        }
        run(actions);
        return accepted;
    }

    public RenderView acquireRenderView() {
        List<Runnable> actions = new ArrayList<>();
        RenderView view;
        synchronized (this) {
            // A late Handler/timer callback must not permit one expired draw. Refresh from the
            // owner clock without demanding another capture, detector completion or UI post.
            long now = clock.getAsLong();
            if (active && !closed && destination != 0
                    && (expired(currentPresentation, now) || expired(pendingPresentation, now))) {
                queuePresentation(actions);
                installPresentation(pendingPresentation, actions);
                pendingPresentation = null;
            }
            view = currentPresentation == null ? null : new RenderView(currentPresentation);
        }
        run(actions);
        // Observer callbacks run outside the owner lock and may synchronously stop/change scope.
        // Do not return a stale draw admitted before those callbacks invalidated its epoch.
        boolean invalid;
        synchronized (this) { invalid = view != null && (!active || closed || view.snapshot.destination.epoch != epoch); }
        if (invalid) { view.close(); return null; }
        return view;
    }

    /** Direct per-member drawing metadata, never a graph edge, destination or basis pin. */
    public boolean presentationEstimate(ImagePresentationEstimate estimate) {
        Objects.requireNonNull(estimate);
        List<Runnable> actions = new ArrayList<>();
        boolean accepted;
        synchronized (this) {
            SceneEngine.PresentationAdmission admission = active && !closed && latestImageSource != null
                    && latestImageSource.sequence == estimate.to
                    ? scene.admitPresentation(estimate) : SceneEngine.PresentationAdmission.INVALID;
            accepted = admission == SceneEngine.PresentationAdmission.ADMITTED;
            if (accepted && destination != 0) queuePresentation(actions);
        }
        run(actions);
        return accepted;
    }

    private boolean expired(Presentation value, long now) {
        return value != null && value.snapshot.nextChangeAtMillis != Long.MAX_VALUE
                && value.snapshot.nextChangeAtMillis <= now;
    }

    /** Actual renderer hook, not an inference/UI-publication proxy for first-censor arrival. */
    public void drawn(long snapshotRevision) {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (currentPresentation == null || currentPresentation.snapshot.revision != snapshotRevision) return;
            SceneEngine.Snapshot snapshot = currentPresentation.snapshot;
            if (snapshot.groups.isEmpty()) return;
            trace(Stage.DRAWN, snapshot.destination, null, 0, null, actions);
        }
        run(actions);
    }

    public synchronized ActiveScope activeScope() {
        return active && !closed ? new ActiveScope(scopeAuthority, epoch, geometry, protectionScope) : null;
    }

    public synchronized boolean isCurrent(ActiveScope scope) {
        return active && !closed && scope != null && scope.authority == scopeAuthority && scope.epoch == epoch
                && scope.geometry == geometry && scope.protectionScope.equals(protectionScope);
    }

    /** Read-only: no scene rebuild, source-age renewal, capture/read demand or pixel ownership. */
    public synchronized ViewportCoverage viewportCoverage() {
        long now = clock.getAsLong();
        ActiveScope scope = activeScope();
        SceneEngine.Snapshot snapshot = coverageSnapshot;
        List<DetectedRegion> regions = new ArrayList<>();
        CoverageState state;
        int ineligible = 0;
        if (scope == null) state = CoverageState.WITHDRAWN;
        else if (snapshot == null) state = CoverageState.UNOBSERVED;
        else if (snapshot.nextChangeAtMillis != Long.MAX_VALUE && now >= snapshot.nextChangeAtMillis) {
            state = CoverageState.EXPIRED;
        } else {
            SceneEngine.Region display = new SceneEngine.Region(0, 0, snapshot.destination.width, snapshot.destination.height);
            for (SceneEngine.Group group : snapshot.groups) for (SceneEngine.Mask member : group.members) {
                boolean supported = !member.heldGeometry && !member.estimatedMotion && !member.imageEstimated
                        && (member.nativeValidated
                        ? nativeEnabled && member.nativeCoverage == NativeTextResult.Coverage.COMPLETE_TREE
                            && nativeBasisGeneration == nativeGeneration && nativeBasisTreeGeneration == nativeTreeGeneration
                            && member.evidenceSource == nativeBasis
                        : member.geometrySource == latestImageSource);
                SceneEngine.Region clipped = supported ? member.displayRegion.clippedTo(display) : null;
                if (clipped == null) ineligible++;
                else regions.add(new DetectedRegion(member, clipped));
            }
            state = regions.isEmpty() ? CoverageState.NO_ELIGIBLE_REGIONS : CoverageState.AVAILABLE;
        }
        return new ViewportCoverage(scopeAuthority, coveragePublication, scope, latestImageSource,
                nativeGeneration, nativeTreeGeneration, state, snapshot, now, regions, ineligible);
    }

    /** Validate the original receipt again immediately before use; no wrapper can renew it. */
    public synchronized boolean coverageCurrent(ViewportCoverage receipt) {
        if (receipt == null || receipt.owner != scopeAuthority || receipt.publication != coveragePublication
                || receipt.imageTarget != latestImageSource || receipt.nativeGeneration != nativeGeneration
                || receipt.nativeTreeGeneration != nativeTreeGeneration) return false;
        long now = clock.getAsLong();
        if (now < receipt.queriedAtMillis) return false;
        // Withdrawal is authenticated without an ActiveScope. It cannot clear a restarted owner.
        if (receipt.state == CoverageState.WITHDRAWN) return receipt.scope == null && (!active || closed);
        return isCurrent(receipt.scope) && receipt.state != CoverageState.EXPIRED
                && (receipt.validBeforeMillis == Long.MAX_VALUE || now < receipt.validBeforeMillis);
    }

    /** An accepted observation tick, not a display/query clock or a delayed obsolete result. */
    public synchronized boolean observationCurrent(EvidenceReceipt observation, ViewportCoverage coverage) {
        return observation != null && coverageCurrent(coverage) && evidenceCurrent(observation)
                && observation.observationAuthority == observationAuthority
                && observation.acceptedAtMillis <= coverage.queriedAtMillis
                && (observation.source.kind == SceneEngine.SourceKind.IMAGE
                    ? observation.source == latestImageSource
                    : observation.nativeCoverage == NativeTextResult.Coverage.COMPLETE_TREE && nativeEnabled
                        && nativeBasisGeneration == nativeGeneration && nativeBasisTreeGeneration == nativeTreeGeneration
                        && observation.source == nativeBasis);
    }

    /** Current-window scroll revokes queued dwell observations without changing count or geometry. */
    public synchronized void discardDwellObservations(ActiveScope scope) {
        if (isCurrent(scope)) observationAuthority = new Object();
    }

    /** Source-supported positive policy for a caller's clipped subset, never physical paint proof. */
    public synchronized boolean dwellPresent(EvidenceReceipt observation, ViewportCoverage coverage,
            List<DetectedRegion> visible) {
        if (!observationCurrent(observation, coverage)) return false;
        // Regional QUALITY and OCR/native triggers cannot borrow another lane's surviving inventory.
        if (observation.triggerLane != SceneEngine.Lane.FAST && !observation.positiveInput) return false;
        for (DetectedRegion member : visible) {
            if (!coverage.regions.contains(member) || !member.dwellPositive) continue;
            if (observation.triggerLane == SceneEngine.Lane.FAST) return true;
            for (SceneEngine.AcceptedSubject subject : observation.subjects) {
                if (subject.identity == member.identity && subject.source == member.evidenceSource
                        && subject.evidenceLane == observation.triggerLane) return true;
            }
        }
        return false;
    }

    /**
     * Product must check its captured/current eligibility and active stats session BEFORE claiming.
     * Null is stale/foreign/retired evidence; an empty claim is valid but consumes no new identity.
     * This is not a transaction with a downstream store: failed writes must not be retried as claims.
     */
    public synchronized EvidenceClaim claimEvidence(EvidenceReceipt receipt) {
        return claimEvidence(receipt, null);
    }

    /** Consumer binding is opaque to the owner, and cannot replace its captured/current scope. */
    public synchronized EvidenceClaim claimEvidence(EvidenceReceipt receipt, Object consumerScope) {
        if (!evidenceCurrent(receipt)) return null;
        List<SceneEngine.AcceptedSubject> claimed = scene.consumeEvidence(receipt.source, receipt.subjects, clock.getAsLong());
        return claimed == null ? null : new EvidenceClaim(receipt, claimed, consumerScope);
    }

    private void acceptedEvidence(SceneEngine.Source source, SceneEngine.Lane lane, boolean positiveInput,
            List<Runnable> actions) {
        acceptedEvidence(source, lane, positiveInput, null, actions);
    }

    /** Also guards already-committed downstream effects; a claim does not renew its captured fence. */
    public synchronized boolean evidenceCurrent(EvidenceReceipt receipt) {
        return receipt != null && isCurrent(receipt.scope)
                && (receipt.source.kind == SceneEngine.SourceKind.IMAGE || (nativeEnabled
                    && receipt.nativeGeneration == nativeGeneration
                    && (receipt.nativeCoverage == NativeTextResult.Coverage.POSITIVE_NODES_ONLY
                        || receipt.nativeTreeGeneration == nativeTreeGeneration)));
    }

    private void acceptedEvidence(SceneEngine.Source source, SceneEngine.Lane lane, boolean positiveInput,
            NativeTextResult.Coverage nativeCoverage, List<Runnable> actions) {
        long now = clock.getAsLong();
        EvidenceReceipt receipt = new EvidenceReceipt(activeScope(), observationAuthority, source, lane, positiveInput,
                now, scene.acceptedSubjects(source, now), nativeCoverage, nativeGeneration, nativeTreeGeneration);
        actions.add(() -> {
            if (evidenceCurrent(receipt)) observer.evidenceAccepted(receipt);
        });
    }

    /**
     * Called only after actual attached, visible, qualified surface recording. No first-censor
     * inference from UI delivery. Empty ordinary paint and stale/foreign identities are rejected.
     * A receipt records the drawing hook; Android compositor/pixel-arrival remains unknown.
     */
    public PaintReceipt acknowledgePaint(ActiveScope scope, long revision, long generation,
            List<Long> paintedIdentities, boolean reverseBackground) {
        Objects.requireNonNull(paintedIdentities);
        if (generation < 0 || revision < -1) throw new IllegalArgumentException("paint metadata");
        List<Runnable> actions = new ArrayList<>(); PaintReceipt receipt;
        synchronized (this) {
            long now = clock.getAsLong();
            if (!isCurrent(scope) || (reverseBackground && !paintedIdentities.isEmpty())) return null;
            SceneEngine.Source canvas = null; List<SceneEngine.Mask> selected = new ArrayList<>();
            if (revision == -1) {
                if (!reverseBackground || currentPresentation != null || !paintedIdentities.isEmpty()) return null;
            } else {
                if (currentPresentation == null || currentPresentation.snapshot.revision != revision
                        || expired(currentPresentation, now)) return null;
                SceneEngine.Snapshot snapshot = currentPresentation.snapshot;
                int count = 0;
                for (SceneEngine.Group group : snapshot.groups) count += group.members.size();
                if (paintedIdentities.size() > count) return null;
                java.util.Set<Long> ids = new java.util.HashSet<>(paintedIdentities);
                if (ids.size() != paintedIdentities.size() || ids.contains(null)) return null;
                for (SceneEngine.Group group : snapshot.groups) for (SceneEngine.Mask mask : group.members) {
                    if (ids.remove(mask.identity)) selected.add(mask);
                }
                if (!ids.isEmpty() || (!reverseBackground && selected.isEmpty())) return null;
                canvas = snapshot.destination;
            }
            receipt = new PaintReceipt(scope, revision, generation, now, canvas, selected, reverseBackground);
            actions.add(() -> observer.painted(receipt));
        }
        run(actions);
        // An observer may stop/reconfigure synchronously; do not authorize a subsequent stale tap map.
        synchronized (this) {
            if (!isCurrent(scope) || (revision != -1 && (currentPresentation == null
                    || currentPresentation.snapshot.revision != revision
                    || expired(currentPresentation, clock.getAsLong())))
                    || (revision == -1 && currentPresentation != null)) return null;
        }
        return receipt;
    }

    public synchronized boolean captureInFlight() { return capture != null; }
    public synchronized int livePixelFrames() {
        return frames.size() + unadoptedDisposals + (capture != null && capture.captured != null ? 1 : 0);
    }
    public synchronized long currentEpoch() { return epoch; }
    public synchronized int renderBasisCount() { return renderBases.size(); }
    public synchronized long basisAdmissionDrops() { return basisAdmissionDrops; }
    public synchronized long callbackFailures() { return callbackFailures; }
    public synchronized long resourceFailures() { return resourceFailures; }
    public synchronized long nativeCostDiagnosticFailures() { return nativeCostDiagnosticFailures; }
    /** A host-owned native context failed disposal outside a pixel job, such as model startup. */
    public void nativeResourceFailure() { resourceFailed(currentEpoch(), 0); }
    public synchronized int pendingCount(SceneEngine.Lane lane) {
        LaneState state = lanes.get(lane); return state != null && state.pending != null ? 1 : 0;
    }
    public synchronized int activeCount(SceneEngine.Lane lane) {
        LaneState state = lanes.get(lane); return state != null && state.active != null ? 1 : 0;
    }

    private void invalidate(List<Runnable> actions) {
        epoch++;
        nativeAccountingChain = new Object();
        scene.reset(epoch);
        if (closed) scrollMotion.close();
        else scrollMotion.reset(new ScrollMotionLedger.Scope(epoch, geometry, protectedWindowId, protectionScope));
        renderBases.clear();
        basisAdmissionDrops = 0;
        destination = 0;
        destinationSource = null;
        canvasLane = null;
        latestImageSource = null;
        nativeBasis = null;
        nativeBasisGeneration = -1;
        nativeBasisTreeGeneration = -1;
        coverageSnapshot = null; coveragePublication = new Object();
        displayWidth = 0; displayHeight = 0;
        nativeGeneration++; nativeTreeGeneration++; nativeDemandAt = Long.MAX_VALUE;
        firstFastCompleted = false;
        cancelTimer(actions);
        cancelPresentationTimer(actions);
        cancelNativeTimer(actions); cancelNative(actions);
        for (LaneState lane : lanes.values()) {
            if (lane.pending != null) { actions.add(lane.pending::release); lane.pending = null; }
            cancelJob(lane.active, actions);
        }
        if (pendingPresentation != null) { actions.add(pendingPresentation::release); pendingPresentation = null; }
        if (currentPresentation != null) { actions.add(currentPresentation::release); currentPresentation = null; }
        trace(Stage.SCOPE_CHANGED, null, null, 0, null, actions);
        notifyPresentation(actions);
        // capture remains owned until method return AND callback, including the old session.
    }

    private long nextCaptureAt() {
        return Math.max(retryNotBefore, lastDispatchAt < 0 ? 0 : plus(lastDispatchAt, requestedCaptureMillis));
    }

    private void demandNative(long at, List<Runnable> actions) {
        if (nativeText == null || !nativeEnabled || !active || closed) return;
        nativeDemandAt = Math.min(nativeDemandAt, at);
        wakeNative(actions);
    }

    private long nextNativeEligibleAt() {
        // Successful work keeps the start-to-start budget; every failed/rejected attempt backs
        // off from its real terminal. An old success cannot accelerate a new scope after callbacks.
        long basis = nativeAdmittedReadStartedAt >= 0 && nativeAdmittedEpoch == epoch
                && nativeAdmittedGeneration == nativeGeneration ? nativeAdmittedReadStartedAt : lastNativeTerminatedAt;
        return basis < 0 ? 0 : plus(basis, nativeText.minimumRefreshMillis);
    }

    private void wakeNative(List<Runnable> actions) {
        if (nativeText == null || !nativeEnabled || !active || closed || nativeJob != null
                || nativeDemandAt == Long.MAX_VALUE || resourceFailures != 0 || unadoptedDisposals != 0) return;
        long target = Math.max(clock.getAsLong(), Math.max(nativeDemandAt, nextNativeEligibleAt()));
        if (nativeTimer != null && nativeTimer.at <= target) return;
        cancelNativeTimer(actions);
        TimerTicket expected = new TimerTicket(target); nativeTimer = expected;
        actions.add(() -> {
            try { expected.attach(timer.schedule(() -> nativeTick(expected), Math.max(0, target - clock.getAsLong()))); }
            catch (RuntimeException failure) {
                synchronized (CensorEngine.this) { if (nativeTimer == expected) nativeTimer = null; callbackFailures++; }
            }
        });
    }

    private void cancelNativeTimer(List<Runnable> actions) {
        if (nativeTimer != null) { actions.add(nativeTimer::cancel); nativeTimer = null; }
    }

    private void nativeTick(TimerTicket expected) {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (nativeTimer != expected || !active || closed || !nativeEnabled) return;
            nativeTimer = null;
            if (nativeJob != null) return;
            long now = clock.getAsLong();
            long eligible = Math.max(expected.at, Math.max(nativeDemandAt, nextNativeEligibleAt()));
            if (now < eligible) {
                // A timer callback is not actual provider-read eligibility.
                wakeNative(actions);
            } else {
                NativeJob job = new NativeJob(++nextNativeRequest, now);
                nativeJob = job; nativeDemandAt = Long.MAX_VALUE;
                actions.add(() -> {
                    try { nativeText.executor.execute(() -> invokeNative(job)); }
                    catch (RuntimeException failure) { completeNative(job, null, NativeTerminal.FAILURE); }
                });
            }
        }
        run(actions);
    }

    private void invokeNative(NativeJob job) {
        synchronized (this) {
            if (job.phase != JobPhase.QUEUED) return;
            job.phase = JobPhase.RUNNING; job.startedAt = clock.getAsLong();
        }
        try {
            nativeText.worker.start(job.input, new NativeTextCompletion() {
                @Override public void costStarted(NativeReadCost.Start value) {
                    synchronized (CensorEngine.this) {
                        if (job.phase != JobPhase.RUNNING || job.costInvokedAt >= 0 || value.request != job.input.identity
                                || value.epoch != job.epoch || value.geometry != job.input.geometry || value.hardGeneration != job.generation
                                || value.invokedAtMillis < job.startedAt || value.invokedAtMillis > clock.getAsLong())
                            throw new IllegalArgumentException("native cost invocation");
                        job.costInvokedAt = value.invokedAtMillis;
                        job.costReadMode = value.readMode;
                    }
                    try { observer.nativeReadCostStarted(value); }
                    catch (RuntimeException diagnosticFailure) { synchronized (CensorEngine.this) { nativeCostDiagnosticFailures++; } }
                }
                @Override public void cost(NativeReadCost value) {
                    synchronized (CensorEngine.this) {
                        if (job.phase != JobPhase.RUNNING || job.costReported || value.request != job.input.identity
                                || value.epoch != job.epoch || value.geometry != job.input.geometry || value.hardGeneration != job.generation
                                || value.readStartedAtMillis != job.readStartedAt || value.scanGeneration != job.readTreeGeneration
                                || job.costInvokedAt < 0 || value.invokedAtMillis != job.costInvokedAt
                                || value.readMode != job.costReadMode || (job.readStartedAt >= 0 && value.readMode != job.input.readMode())
                                || value.terminalAtMillis > clock.getAsLong()) throw new IllegalArgumentException("native cost operation");
                        job.costReported = true;
                    }
                    try { observer.nativeReadCost(value); }
                    catch (RuntimeException diagnosticFailure) {
                        synchronized (CensorEngine.this) { nativeCostDiagnosticFailures++; }
                    }
                }
                @Override public void success(NativeTextResult read) { completeNative(job, read, NativeTerminal.READ); }
                @Override public boolean observe(int ordinal, NativeTextResult group) { return observeNative(job, ordinal, group); }
                @Override public NativeTextAdmission admit(NativeTextResult read) { return completeNative(job, read, NativeTerminal.READ); }
                @Override public void failure() { completeNative(job, null, NativeTerminal.FAILURE); }
                @Override public void cancelled() { completeNative(job, null, NativeTerminal.CANCELLED); }
                @Override public void resourceFailure() { completeNative(job, null, NativeTerminal.RESOURCE_FAILURE); }
            });
        } catch (RuntimeException failure) { completeNative(job, null, NativeTerminal.FAILURE); }
    }

    private long beginNativeRead(NativeJob job) {
        synchronized (this) {
            if (nativeJob != job || job.phase != JobPhase.RUNNING || job.cancellation || !active || closed
                    || !nativeEnabled || job.epoch != epoch || job.generation != nativeGeneration
                    || job.readStartedAt != -1 || resourceFailures != 0) return -1;
            job.readStartedAt = clock.getAsLong();
            job.readTreeGeneration = nativeTreeGeneration;
            job.input.scanGeneration = job.readTreeGeneration;
            return job.readStartedAt;
        }
    }

    private void cancelNative(List<Runnable> actions) {
        NativeJob job = nativeJob;
        if (job == null || job.phase == JobPhase.DONE || job.cancellation) return;
        job.cancellation = true;
        nativeAccountingChain = new Object();
        actions.add(job.input.lifetime::signalCancellation);
        if (job.phase == JobPhase.QUEUED) {
            job.phase = JobPhase.DONE; job.input.lifetime.finish(); nativeJob = null;
        }
    }

    private boolean observeNative(NativeJob job, int ordinal, NativeTextResult read) {
        List<Runnable> actions = new ArrayList<>();
        NativeTextFrame admitted;
        synchronized (this) {
            long now = clock.getAsLong();
            if (nativeJob != job || job.phase != JobPhase.RUNNING || job.costReported || job.cancellation
                    || !active || closed || !nativeEnabled || job.epoch != epoch || job.generation != nativeGeneration
                    || resourceFailures != 0 || job.readStartedAt < 0 || read == null
                    || ordinal <= job.lastObservationOrdinal || ordinal > NativeTextResult.MAXIMUM_CANDIDATES
                    || !read.observationInventoryValid(ordinal)
                    || read.readStartedAtMillis != job.readStartedAt || read.readFinishedAtMillis > now
                    || !nativeReadCurrent(job, read) || now > plus(read.readFinishedAtMillis, nativeText.maximumAgeMillis)
                    || job.observedNodes.contains(read.candidates.get(0).nodeKey)) return false;
            job.lastObservationOrdinal = ordinal;
            admitted = admitNativeFrame(job, read, ordinal, now, actions);
            if (admitted != null) job.observedNodes.add(read.candidates.get(0).nodeKey);
            // No terminal transition, retry wake or lifetime.finish here. The worker still owns its roots.
        }
        run(actions);
        synchronized (this) {
            return admitted != null && nativeJob == job && job.phase == JobPhase.RUNNING && !job.cancellation
                    && active && !closed && nativeEnabled && epoch == job.epoch && nativeGeneration == job.generation
                    && resourceFailures == 0;
        }
    }

    private NativeTextAdmission completeNative(NativeJob job, NativeTextResult read, NativeTerminal terminal) {
        List<Runnable> actions = new ArrayList<>();
        List<Runnable> failedCleanup = new ArrayList<>();
        NativeTextFrame admitted = null;
        try {
            synchronized (this) {
                if (job.phase == JobPhase.DONE) return null;
                job.phase = JobPhase.DONE; job.input.lifetime.finish();
                if (nativeJob == job) nativeJob = null;
                Presentation previousPending = pendingPresentation;
                SceneEngine.Snapshot previousCoverage = coverageSnapshot;
                Object previousPublication = coveragePublication;
                boolean previousNotification = notificationQueued;
                TimerTicket previousNativeTimer = nativeTimer, previousPresentationTimer = presentationTimer;
                boolean terminalFinished = false;
                try {
                    long now = clock.getAsLong();
                    // Retry cooldown always starts at the actual terminal. Only actual Scene admission
                    // below can replace it with a current-owner successful start-spacing budget.
                    lastNativeTerminatedAt = now;
                    nativeAdmittedReadStartedAt = -1;
                    boolean renew = active && !closed && nativeEnabled && !job.cancellation
                            && job.epoch == epoch && job.generation == nativeGeneration
                            && (terminal == NativeTerminal.READ || terminal == NativeTerminal.FAILURE);
                    if (terminal == NativeTerminal.RESOURCE_FAILURE) {
                        if (resourceFailures++ == 0) invalidate(actions);
                        trace(Stage.RESOURCE_FAILED, null, SceneEngine.Lane.TEXT, 0, null, actions);
                    } else if (read != null && active && !closed && nativeEnabled && !job.cancellation
                            && job.epoch == epoch && job.generation == nativeGeneration && job.readStartedAt >= 0
                            && read.readStartedAtMillis == job.readStartedAt && read.readFinishedAtMillis <= now
                            && nativeReadCurrent(job, read) && read.terminalInventoryValid()
                            && nativeObservationInventoryConsistent(job, read)
                            && now <= plus(read.readFinishedAtMillis, nativeText.maximumAgeMillis)) {
                        admitted = admitNativeFrame(job, read, 0, now, actions);
                    }
                    if (admitted != null) {
                        nativeAdmittedReadStartedAt = job.readStartedAt;
                        nativeAdmittedEpoch = job.epoch;
                        nativeAdmittedGeneration = job.generation;
                    }
                    // This owns a producer clock, not evidence. Only an admitted real classified read
                    // above can change witnesses, source age or expiry; all metadata queries stay passive.
                    if (renew && job.epoch == epoch && job.generation == nativeGeneration
                            && resourceFailures == 0 && unadoptedDisposals == 0) demandNative(now, actions);
                    wakeNative(actions); checkDrained(actions);
                    terminalFinished = true;
                } finally {
                    // Scene admission may have restored credit before later owner bookkeeping throws.
                    if (admitted == null || !terminalFinished) nativeAccountingChain = new Object();
                    if (!terminalFinished && epoch == job.epoch && resourceFailures == 0) {
                        scene.discardRestoredNativeAccounting(nativeText.partition, job.input.identity);
                        nativeAdmittedReadStartedAt = -1;
                        // No action in this terminal has run. Restore its previous pending metadata;
                        // never deliver success callbacks or strand an unattached notification/timer.
                        if (pendingPresentation != previousPending && pendingPresentation != null)
                            failedCleanup.add(pendingPresentation::release);
                        pendingPresentation = previousPending;
                        coverageSnapshot = previousCoverage; coveragePublication = previousPublication;
                        notificationQueued = previousNotification;
                        nativeTimer = previousNativeTimer; presentationTimer = previousPresentationTimer;
                    } else if (!terminalFinished) {
                        // An invalidation has already retired the old epoch. Its cancellation and
                        // release actions must still run; old presentation state must not be restored.
                        failedCleanup.addAll(actions);
                    }
                }
            }
        } finally { run(failedCleanup); }
        run(actions);
        synchronized (this) {
            // Notifications can synchronously stop/reconfigure the owner. Historical acceptance
            // is not authority to retain an address in a now-fenced provider scope.
            if (admitted == null || !active || closed || !nativeEnabled || resourceFailures != 0
                    || epoch != job.epoch || geometry != job.input.geometry || nativeGeneration != job.generation
                    || !protectionScope.equals(job.input.protectionScope)) return null;
            return new NativeTextAdmission(job.input, admitted);
        }
    }

    /** One shared source/scene/presentation path for terminal reads and explicit positive observations. */
    private NativeTextFrame admitNativeFrame(NativeJob job, NativeTextResult read, int ordinal, long now, List<Runnable> actions) {
        if (ordinal == 0 && read.readMode == NativeTextResult.ReadMode.FULL_DISCOVERY
                && read.coverage == NativeTextResult.Coverage.POSITIVE_NODES_ONLY) nativeAccountingChain = new Object();
        if (displayWidth != 0 && (read.width != displayWidth || read.height != displayHeight)) {
            invalidate(actions); demandNative(now, actions); wake(now, actions); return null;
        }
        Set<Long> basisSources = new java.util.HashSet<>();
        for (SceneEngine.Source basis : renderBases.values()) basisSources.add(basis.sequence);
        SceneEngine.Source source = SceneEngine.Source.nativeText(job.epoch, ++nextSource, job.input.geometry,
                read.width, read.height, read.windowId, read.readStartedAtMillis, read.readFinishedAtMillis, now,
                job.input.protectionScope);
        NativeTextFrame frame = new NativeTextFrame(source, nativeText.partition, read,
                nativeText.maximumAgeMillis, job.input.identity, job.queuedAt, job.startedAt, ordinal, nativeAccountingChain);
        Set<SceneEngine.Source> retainedNative = scene.nativeOriginsRetainedBy(frame);
        for (SceneEngine.Source retained : retainedNative) basisSources.add(retained.sequence);
        boolean capacity = (long) basisSources.size() + 1 <= maximumBasisSources
                && (long) renderBases.size() + retainedNative.size() + 1 <= maximumRenderBases;
        if (!capacity || !scene.admitNativeFrame(frame)) return null;
        if (nativeBasis != null) scene.unpin(nativeBasis);
        nativeBasis = source;
        nativeBasisGeneration = job.generation;
        nativeBasisTreeGeneration = nativeTreeGeneration;
        if (read.coverage == NativeTextResult.Coverage.COMPLETE_TREE && !frame.read.candidates.isEmpty()) scene.clearImageText();
        displayWidth = read.width; displayHeight = read.height;
        if (destinationSource == null || destinationSource.kind == SceneEngine.SourceKind.NATIVE_TEXT) {
            if (destinationSource != null) scene.unpin(destinationSource);
            if (!scene.pin(source)) throw new IllegalStateException("native canvas source lost");
            destinationSource = source; destination = source.sequence;
            canvasLane = null;
        }
        queuePresentation(actions);
        actions.add(() -> observer.nativeTextAccepted(frame));
        acceptedEvidence(source, SceneEngine.Lane.TEXT, !frame.read.candidates.isEmpty(), read.coverage, actions);
        return frame;
    }

    private boolean nativeReadCurrent(NativeJob job, NativeTextResult read) {
        if (read.readMode != job.input.readMode()) return false;
        if (read.hardGeneration != -1 && read.hardGeneration != job.generation) return false;
        if (read.coverage == NativeTextResult.Coverage.POSITIVE_NODES_ONLY) {
            return read.scanGeneration == job.readTreeGeneration && read.confirmationGeneration <= nativeTreeGeneration;
        }
        return read.scanGeneration == -1 ? job.readTreeGeneration == nativeTreeGeneration
                : read.scanGeneration == job.readTreeGeneration && read.scanGeneration == nativeTreeGeneration;
    }

    private boolean nativeObservationInventoryConsistent(NativeJob job, NativeTextResult read) {
        if (job.observedNodes.isEmpty() || (read.coverage != NativeTextResult.Coverage.COMPLETE_TREE
                && read.readMode != NativeTextResult.ReadMode.ADMITTED_NODE_COHORT_RECLASSIFY)) return true;
        Set<String> finalNodes = new java.util.HashSet<>();
        for (NativeTextResult.Candidate candidate : read.candidates) finalNodes.add(candidate.nodeKey);
        // This contract has positive observations, not an independent later negative-node proof.
        // Ending the acquisition cannot manufacture that proof for a previously observed node.
        return finalNodes.containsAll(job.observedNodes);
    }

    private void wake(long requested, List<Runnable> actions) {
        if (!active || closed || enabledLanes.isEmpty() || capture != null
                || resourceFailures != 0 || unadoptedDisposals != 0) return;
        if (frames.size() >= policy.maximumPixelFrames) {
            trace(Stage.CAPTURE_BACKPRESSURE, null, null, 0, null, actions);
            return;
        }
        long now = clock.getAsLong();
        long target = Math.max(now, Math.max(requested, nextCaptureAt()));
        if (captureTimer != null && captureTimer.at <= target) return;
        cancelTimer(actions);
        TimerTicket expected = new TimerTicket(target);
        captureTimer = expected;
        actions.add(() -> {
            try { expected.attach(timer.schedule(() -> captureTick(expected), Math.max(0, target - clock.getAsLong()))); }
            catch (RuntimeException rejected) { timerRejected(expected); }
        });
    }

    private void cancelTimer(List<Runnable> actions) {
        if (captureTimer != null) { actions.add(captureTimer::cancel); captureTimer = null; }
    }

    private void timerRejected(TimerTicket expected) {
        synchronized (this) { if (captureTimer == expected) captureTimer = null; callbackFailures++; }
    }

    private void captureTick(TimerTicket expected) {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (captureTimer != expected || !active || closed || enabledLanes.isEmpty()) return;
            captureTimer = null;
            long now = clock.getAsLong();
            if (capture != null) return;
            if (frames.size() >= policy.maximumPixelFrames || resourceFailures != 0 || unadoptedDisposals != 0
                    || now < nextCaptureAt()) { wake(nextCaptureAt(), actions); }
            else {
                Ticket ticket = new Ticket(++nextRequest, now);
                capture = ticket;
                traceRequest(Stage.CAPTURE_REQUESTED, ticket, actions);
                actions.add(() -> invokeCapture(ticket));
            }
        }
        run(actions);
    }

    private void invokeCapture(Ticket ticket) {
        try {
            capturer.capture(ticket.request, new CaptureCallback<P>() {
                @Override public void success(Captured<P> captured) { captureResult(ticket, captured, 0); }
                @Override public void failure(long retryAfterMillis) {
                    captureResult(ticket, null, Math.max(policy.failureBackoffMillis, Math.max(0, retryAfterMillis)));
                }
                @Override public void resourceFailure() {
                    resourceFailed(ticket.request.epoch, ticket.request.identity);
                    captureResult(ticket, null, policy.failureBackoffMillis);
                }
            });
        } catch (RuntimeException failure) { captureResult(ticket, null, policy.failureBackoffMillis); }
        finally { captureReturned(ticket); }
    }

    private boolean beginDispatch(Ticket ticket) {
        List<Runnable> actions = new ArrayList<>();
        boolean permitted;
        synchronized (this) {
            long now = clock.getAsLong();
            permitted = capture == ticket && !ticket.callback && !ticket.dispatched
                    && active && !closed && !enabledLanes.isEmpty()
                    && resourceFailures == 0 && unadoptedDisposals == 0
                    && ticket.request.epoch == epoch && now >= nextCaptureAt();
            if (permitted) {
                ticket.dispatched = true;
                lastDispatchAt = now;
                traceRequest(Stage.CAPTURE_DISPATCHED, ticket, actions);
            } else if (capture == ticket && !ticket.dispatched && !ticket.callback) {
                ticket.callback = true; // No API was called; the driver may simply return.
                ticket.receivedAt = now;
                traceRequest(Stage.CAPTURE_REJECTED, ticket, actions);
            }
        }
        run(actions);
        return permitted;
    }

    private void captureResult(Ticket ticket, Captured<P> payload, long retryAfter) {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (capture != ticket || ticket.callback) {
                if (payload != null) discard(payload, false, ticket, actions);
            } else {
                ticket.callback = true;
                ticket.receivedAt = clock.getAsLong();
                ticket.retryAfter = retryAfter;
                if (payload != null && payload.claim()) ticket.captured = payload;
                traceRequest(Stage.CAPTURE_CALLBACK, ticket, actions);
                finishCapture(ticket, actions);
            }
        }
        run(actions);
    }

    private void captureReturned(Ticket ticket) {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            ticket.returned = true;
            if (ticket.dispatched) lastDispatchAt = Math.max(lastDispatchAt, clock.getAsLong());
            traceRequest(Stage.CAPTURE_RETURNED, ticket, actions);
            if (!ticket.dispatched && !ticket.callback) {
                ticket.callback = true; // A driver that omitted the API/result cannot strand the slot.
                ticket.receivedAt = clock.getAsLong();
                ticket.retryAfter = policy.failureBackoffMillis;
            }
            finishCapture(ticket, actions);
        }
        run(actions);
    }

    private void finishCapture(Ticket ticket, List<Runnable> actions) {
        if (capture != ticket || !ticket.returned || !ticket.callback) return;
        capture = null;
        retryNotBefore = Math.max(retryNotBefore, plus(ticket.receivedAt, ticket.retryAfter));
        Captured<P> payload = ticket.captured;
        if (payload != null) {
            if (active && !closed && ticket.dispatched && ticket.request.epoch == epoch
                    && displayWidth != 0
                    && (payload.width != displayWidth || payload.height != displayHeight)) {
                // A display change cannot share scene/presentation coordinates with this epoch.
                // The old request's payload is discarded below; existing readers finish normally.
                invalidate(actions);
            }
            if (active && !closed && ticket.dispatched && ticket.request.epoch == epoch
                    && frames.size() < policy.maximumPixelFrames) {
                nextSource = Math.max(nextSource + 1, ticket.request.identity);
                SceneEngine.Source source = SceneEngine.Source.image(ticket.request.epoch, nextSource, ticket.request.identity, ticket.request.geometry,
                        payload.width, payload.height, ticket.receivedAt, payload.pixelAtMillis, ticket.request.protectionScope);
                if (scene.register(source) && scene.pin(source)) {
                    // Optional event metadata may refuse an invalid administrative clock. That
                    // remains an explicit unknown continuation, never a stranded pixel/frame pin.
                    scrollMotion.registerImage(source, clock.getAsLong());
                    Frame frame = new Frame(source, payload);
                    frames.put(source.sequence, frame);
                    latestImageSource = source;
                    displayWidth = payload.width; displayHeight = payload.height;
                    scene.advanceImageTarget(source.sequence);
                    offer(frame, actions);
                    if (destination != 0 && (scene.hasConstructedImagePose()
                            || hasImageEstimate(currentPresentation) || hasImageEstimate(pendingPresentation))) {
                        queuePresentation(actions);
                    }
                    actions.add(frame::release); // Initial capture ownership transfers to lane leases.
                } else {
                    discard(payload, true, ticket, actions);
                    trace(Stage.SOURCE_REJECTED, source, null, 0, null, actions);
                }
            } else {
                discard(payload, true, ticket, actions);
                traceRequest(Stage.SOURCE_REJECTED, ticket, actions);
            }
        }
        wake(clock.getAsLong(), actions);
        demandNative(clock.getAsLong(), actions);
        checkDrained(actions);
    }

    private void offer(Frame frame, List<Runnable> actions) {
        for (LaneState lane : lanes.values()) {
            if (!enabledLanes.contains(lane.specification.lane)) continue;
            if (lane.pending != null) {
                Frame replaced = lane.pending;
                actions.add(replaced::release);
                trace(Stage.JOB_REPLACED, replaced.source, lane.specification.lane, 0, null, actions);
            }
            if (!frame.retain()) throw new IllegalStateException("capture source closed");
            lane.pending = frame;
            lane.pendingAt = clock.getAsLong();
            trace(Stage.JOB_QUEUED, frame.source, lane.specification.lane, 0, null, actions);
        }
        // Refresh only pending work. Cancelling admitted QUALITY on every new capture can starve
        // its coverage; shared visual admission waits for its real terminal before latest FAST.
        // Scope, lane-disable and stop still cancel exact jobs without releasing their pixels early.
        pump(actions);
    }

    private void pump(List<Runnable> actions) {
        if (!active || closed || resourceFailures != 0) return;
        LaneState fast = lanes.get(SceneEngine.Lane.FAST);
        LaneState quality = lanes.get(SceneEngine.Lane.QUALITY);
        boolean qualityBusy = quality != null && quality.active != null;
        if (parallelQuality || !qualityBusy) launch(fast, actions);
        LaneState text = lanes.get(SceneEngine.Lane.TEXT);
        if (text != null) launch(text, actions);
        if (quality != null && (firstFastCompleted || !enabledLanes.contains(SceneEngine.Lane.FAST))
                && (parallelQuality || (fast.active == null && fast.pending == null))) launch(quality, actions);
    }

    private void launch(LaneState lane, List<Runnable> actions) {
        if (!enabledLanes.contains(lane.specification.lane) || lane.active != null || lane.pending == null) return;
        Job job = new Job(lane, lane.pending, lane.pendingAt);
        lane.pending = null;
        lane.active = job;
        actions.add(() -> {
            try { lane.specification.executor.execute(() -> beginJob(job)); }
            catch (RuntimeException rejected) { complete(job, null, null, Stage.JOB_REJECTED); }
        });
    }

    private void cancelJob(Job job, List<Runnable> actions) {
        if (job == null || job.phase == JobPhase.DONE || job.cancellation) return;
        job.cancellation = true;
        if (job.phase == JobPhase.QUEUED) {
            job.phase = JobPhase.DONE;
            job.input.finish();
            if (job.lane.active == job) job.lane.active = null;
            actions.add(job.frame::release);
            trace(Stage.JOB_CANCELLED, job.frame.source, job.lane.specification.lane, 0, null, actions);
        } else actions.add(job.input::signalCancellation);
    }

    private void beginJob(Job job) {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (job.phase != JobPhase.QUEUED || job.lane.active != job) return;
            job.phase = JobPhase.RUNNING;
            job.startedAt = clock.getAsLong();
            trace(Stage.JOB_STARTED, job.frame.source, job.lane.specification.lane,
                    Math.max(0, job.startedAt - job.queuedAt), null, actions);
        }
        run(actions);
        try {
            job.lane.specification.worker.start(job.input, new Completion() {
                @Override public void success(SceneEngine.Batch batch, WorkTiming timing) {
                    complete(job, batch, timing, Stage.JOB_COMPLETED);
                }
                @Override public void failure() { complete(job, null, null, Stage.JOB_FAILED); }
                @Override public void cancelled() { complete(job, null, null, Stage.JOB_CANCELLED); }
                @Override public void resourceFailure() {
                    resourceFailed(job.frame.source);
                    complete(job, null, null, Stage.JOB_FAILED);
                }
            });
        } catch (RuntimeException failed) { complete(job, null, null, Stage.JOB_FAILED); }
    }

    private void complete(Job job, SceneEngine.Batch batch, WorkTiming timing, Stage terminal) {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (job.phase == JobPhase.DONE) return;
            job.phase = JobPhase.DONE;
            job.input.finish();
            if (job.lane.active == job) job.lane.active = null;
            trace(terminal, job.frame.source, job.lane.specification.lane,
                    Math.max(0, job.startedAt - job.queuedAt), timing, actions);
            if (batch != null && !job.cancellation && active && !closed && job.frame.source.epoch == epoch
                    && enabledLanes.contains(job.lane.specification.lane)
                    && batch.source == job.frame.source && batch.lane == job.lane.specification.lane
                    && observeWithTextBases(batch)) {
                if (batch.lane == SceneEngine.Lane.FAST) firstFastCompleted = true;
                if (destination == 0 || batch.lane == SceneEngine.Lane.FAST
                        || (batch.lane == SceneEngine.Lane.TEXT && !enabledLanes.contains(SceneEngine.Lane.FAST))) {
                    if (destination == 0 || batch.source.sequence > destination
                            || destinationSource.kind == SceneEngine.SourceKind.NATIVE_TEXT
                            || (batch.lane == SceneEngine.Lane.FAST && canvasLane != SceneEngine.Lane.FAST)) {
                        if (destinationSource != null) scene.unpin(destinationSource);
                        if (!scene.pin(batch.source)) throw new IllegalStateException("destination source lost");
                        destinationSource = batch.source;
                        destination = batch.source.sequence;
                        canvasLane = batch.lane;
                    }
                }
                queuePresentation(actions);
                actions.add(() -> observer.batchAccepted(batch));
                acceptedEvidence(batch.source, batch.lane, !batch.observations.isEmpty(), actions);
            } else if (batch != null) trace(Stage.RESULT_REJECTED, job.frame.source,
                    job.lane.specification.lane, 0, null, actions);
            actions.add(job.frame::release);
            pump(actions);
            checkDrained(actions);
        }
        run(actions);
    }

    /** The existing bounded basis map owns OCR metadata; no separate scene or pixel cache. */
    private boolean observeWithTextBases(SceneEngine.Batch batch) {
        if (batch.lane != SceneEngine.Lane.TEXT) return scene.observe(batch);
        if (batch.replacementTextPartition != null && imageTextSuppressed()) return false;
        Set<Long> partitions = new java.util.HashSet<>();
        for (SceneEngine.Observation observation : batch.observations) partitions.add(observation.partition);
        if (batch.replacementTextPartition != null) partitions.add(batch.replacementTextPartition);
        Map<Long, SceneEngine.Source> changed = new HashMap<>();
        Set<Long> sources = new java.util.HashSet<>();
        for (Map.Entry<Long, SceneEngine.Source> entry : renderBases.entrySet()) {
            if (!partitions.contains(entry.getKey()) || entry.getValue().sequence >= batch.source.sequence) {
                sources.add(entry.getValue().sequence);
            }
        }
        for (Long partition : partitions) {
            SceneEngine.Source previous = renderBases.get(partition);
            if (previous == null || previous.sequence < batch.source.sequence) changed.put(partition, batch.source);
        }
        if (!changed.isEmpty()) sources.add(batch.source.sequence);
        Set<Long> nativeSources = nativeBasisSources(); sources.addAll(nativeSources);
        if ((long) renderBases.size() + changed.keySet().stream().filter(p -> !renderBases.containsKey(p)).count()
                + nativeSources.size() > maximumRenderBases
                || sources.size() > maximumBasisSources) { basisAdmissionDrops++; return false; }
        List<SceneEngine.Source> pinned = new ArrayList<>();
        for (SceneEngine.Source value : changed.values()) {
            if (!scene.pin(value)) { for (SceneEngine.Source held : pinned) scene.unpin(held); return false; }
            pinned.add(value);
        }
        if (!scene.observe(batch)) { for (SceneEngine.Source held : pinned) scene.unpin(held); return false; }
        for (Map.Entry<Long, SceneEngine.Source> entry : changed.entrySet()) {
            SceneEngine.Source previous = renderBases.put(entry.getKey(), entry.getValue());
            if (previous != null) scene.unpin(previous);
        }
        return true;
    }

    private Set<Long> nativeBasisSources() {
        Set<Long> sources = new java.util.HashSet<>();
        for (SceneEngine.Source source : scene.activeNativeOrigins()) sources.add(source.sequence);
        if (nativeBasis != null) sources.add(nativeBasis.sequence);
        return sources;
    }

    private void queuePresentation(List<Runnable> actions) {
        Presentation previous = pendingPresentation;
        // Trace before acquiring replacement effects; a diagnostic failure cannot orphan retains.
        if (previous != null)
            trace(Stage.PRESENTATION_REPLACED, previous.snapshot.destination, null, 0, null, actions);
        Presentation value = buildPresentation(actions);
        if (previous != null) {
            actions.add(previous::release);
        }
        pendingPresentation = value;
        notifyPresentation(actions);
    }

    private Presentation buildPresentation(List<Runnable> actions) {
        Map<Long, Long> bases = new HashMap<>();
        for (Map.Entry<Long, SceneEngine.Source> entry : renderBases.entrySet()) {
            bases.put(entry.getKey(), entry.getValue().sequence);
        }
        SceneEngine.Snapshot snapshot = scene.snapshot(destination, clock.getAsLong(), true, bases,
                latestImageSource == null ? 0 : latestImageSource.sequence);
        coverageSnapshot = snapshot; coveragePublication = new Object();
        Set<Long> activePartitions = scene.activeImagePartitions();
        for (Iterator<Map.Entry<Long, SceneEngine.Source>> iterator = renderBases.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<Long, SceneEngine.Source> entry = iterator.next();
            if (!activePartitions.contains(entry.getKey())) { scene.unpin(entry.getValue()); iterator.remove(); }
        }
        List<Frame> effects = new ArrayList<>();
        for (SceneEngine.Group group : snapshot.groups) for (SceneEngine.Mask mask : group.members) {
            Frame frame = frames.get(mask.evidenceSource.sequence);
            if (frame != null && !effects.contains(frame)) effects.add(frame);
        }
        effects.sort(Comparator.comparingLong((Frame frame) -> frame.source.sequence).reversed());
        SceneEngine.Source canvasSource = canvasSampling && snapshot.destination.kind == SceneEngine.SourceKind.IMAGE
                ? snapshot.destination : null;
        Frame canvasFrame = canvasSource == null ? null : frames.get(canvasSource.sequence);
        if (canvasFrame != null) {
            // The one explicit canvas shares the existing cap; evicted member pixels use fallback.
            effects.remove(canvasFrame);
            effects.add(0, canvasFrame);
        }
        if (effects.size() > policy.maximumEffectSources) {
            effects = new ArrayList<>(effects.subList(0, policy.maximumEffectSources));
        }
        effects.removeIf(frame -> !frame.retain());
        schedulePresentationExpiry(snapshot.nextChangeAtMillis, actions);
        return new Presentation(snapshot, effects, canvasSource);
    }

    private boolean hasImageEstimate(Presentation value) {
        if (value == null) return false;
        for (SceneEngine.Group group : value.snapshot.groups) for (SceneEngine.Mask mask : group.members) {
            if (mask.imageEstimated) return true;
        }
        return false;
    }

    private void installPresentation(Presentation value, List<Runnable> actions) {
        if (currentPresentation != null) actions.add(currentPresentation::release);
        currentPresentation = value;
        trace(Stage.PRESENTED, value.snapshot.destination, null, 0, null, actions);
    }

    private void schedulePresentationExpiry(long target, List<Runnable> actions) {
        if (presentationTimer != null && presentationTimer.at == target) return;
        cancelPresentationTimer(actions);
        if (target == Long.MAX_VALUE) return;
        TimerTicket expected = new TimerTicket(target);
        presentationTimer = expected;
        actions.add(() -> {
            try { expected.attach(timer.schedule(() -> presentationTick(expected),
                    Math.max(0, target - clock.getAsLong()))); }
            catch (RuntimeException rejected) {
                synchronized (CensorEngine.this) {
                    if (presentationTimer == expected) presentationTimer = null;
                    callbackFailures++;
                }
            }
        });
    }

    private void cancelPresentationTimer(List<Runnable> actions) {
        if (presentationTimer != null) { actions.add(presentationTimer::cancel); presentationTimer = null; }
    }

    private void presentationTick(TimerTicket expected) {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (presentationTimer != expected || !active || closed || destination == 0) return;
            presentationTimer = null;
            // Timer implementations can deliver early; reconstructing the deadline keeps the
            // scene unchanged until its exact source-local retention boundary.
            queuePresentation(actions);
        }
        run(actions);
    }

    private void notifyPresentation(List<Runnable> actions) {
        if (notificationQueued) return;
        notificationQueued = true;
        long identity = ++notificationIdentity;
        actions.add(() -> {
            try { presentationExecutor.execute(() -> deliverPresentation(identity)); }
            catch (RuntimeException rejected) { presentationRejected(identity); }
        });
    }

    private void deliverPresentation(long identity) {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (!notificationQueued || notificationIdentity != identity) return;
            notificationQueued = false;
            Presentation value = pendingPresentation;
            pendingPresentation = null;
            if (value != null) {
                if (!active || closed || value.snapshot.destination.epoch != epoch) actions.add(value::release);
                else {
                    if (expired(value, clock.getAsLong())) {
                        actions.add(value::release);
                        value = buildPresentation(actions);
                    }
                    installPresentation(value, actions);
                }
            }
            actions.add(observer::presentationChanged);
            checkDrained(actions);
        }
        run(actions);
    }

    private void presentationRejected(long identity) {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (!notificationQueued || notificationIdentity != identity) return;
            notificationQueued = false;
            if (pendingPresentation != null) {
                trace(Stage.PRESENTATION_REJECTED, pendingPresentation.snapshot.destination, null, 0, null, actions);
                actions.add(pendingPresentation::release);
                pendingPresentation = null;
            }
        }
        run(actions);
    }

    private void frameReleased(Frame frame) {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (frames.get(frame.source.sequence) == frame) frames.remove(frame.source.sequence);
            wake(clock.getAsLong(), actions);
            checkDrained(actions);
        }
        run(actions);
    }

    private void checkDrained(List<Runnable> actions) {
        if (!canReportDrained()) return;
        actions.add(() -> {
            synchronized (CensorEngine.this) {
                // Revalidate after actual cleanup and any reentrant callbacks, not at admission.
                if (!canReportDrained()) return;
                drainedReported = true;
            }
            observer.drained();
        });
    }

    private boolean canReportDrained() {
        if (!closed || drainedReported || capture != null || !frames.isEmpty()
                || nativeJob != null || resourceFailures != 0 || unadoptedDisposals != 0 || renderViews != 0) return false;
        for (LaneState lane : lanes.values()) if (lane.active != null || lane.pending != null) return false;
        return true;
    }

    private void renderViewReleased() {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            renderViews--;
            checkDrained(actions);
        }
        run(actions);
    }

    private void discard(Captured<P> payload, boolean transferred, Ticket ticket, List<Runnable> actions) {
        if (!transferred && !payload.claim()) return;
        unadoptedDisposals++;
        actions.add(() -> {
            try { payload.releaseOwned(); }
            catch (RuntimeException failure) {
                resourceFailed(ticket.request.epoch, ticket.request.identity);
                throw failure;
            } finally {
                List<Runnable> followup = new ArrayList<>();
                synchronized (CensorEngine.this) {
                    unadoptedDisposals--;
                    wake(clock.getAsLong(), followup);
                    checkDrained(followup);
                }
                run(followup);
            }
        });
    }

    private void releaseFrames(List<Frame> frames) {
        RuntimeException first = null;
        for (Frame frame : frames) {
            try { frame.release(); }
            catch (RuntimeException failure) { if (first == null) first = failure; }
        }
        if (first != null) throw first;
    }

    private void resourceFailed(SceneEngine.Source source) {
        resourceFailed(source.epoch, source.imageRequestIdentity);
    }

    private void resourceFailed(long failedEpoch, long failedSource) {
        List<Runnable> actions = new ArrayList<>();
        synchronized (this) {
            if (resourceFailures++ == 0) invalidate(actions);
            Trace value = new Trace(Stage.RESOURCE_FAILED, failedEpoch, failedSource, null,
                    clock.getAsLong(), 0, -1, null);
            actions.add(() -> observer.trace(value));
        }
        run(actions);
    }

    private void trace(Stage stage, SceneEngine.Source source, SceneEngine.Lane lane,
            long queueMillis, WorkTiming work, List<Runnable> actions) {
        long now = clock.getAsLong();
        long age = source == null || source.pixelAtMillis < 0 || source.pixelAtMillis > now
                ? -1 : now - source.pixelAtMillis;
        Trace value = new Trace(stage, source == null ? epoch : source.epoch,
                source == null ? 0 : source.imageRequestIdentity, lane, now, queueMillis, age, work);
        actions.add(() -> observer.trace(value));
    }

    private void traceRequest(Stage stage, Ticket ticket, List<Runnable> actions) {
        Trace value = new Trace(stage, ticket.request.epoch, ticket.request.identity, null,
                clock.getAsLong(), 0, -1, null);
        actions.add(() -> observer.trace(value));
    }

    private void run(List<Runnable> actions) {
        for (Runnable action : actions) {
            try { action.run(); }
            catch (RuntimeException callbackFailure) { synchronized (this) { callbackFailures++; } }
        }
    }

    private static long plus(long value, long delta) {
        return value > Long.MAX_VALUE - delta ? Long.MAX_VALUE : value + delta;
    }
}
