package com.subhub.app.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import com.subhub.app.BuildConfig;
import com.subhub.app.censor.android.CensorProductSession;
import com.subhub.app.censor.android.ProductCensorQualification;
import com.subhub.app.censor.android.AccessibilityWindowReader;
import com.subhub.app.censor.engine.SceneEngine;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppModePolicy;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.appmode.AppTimerRuntimePolicy;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.CensorCoverage;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.FastVisualGate;
import com.subhub.app.detection.ObjectTracker;
import com.subhub.app.detection.RenderSourceReference;
import com.subhub.app.detection.TrackedObject;
import com.subhub.app.detection.VisualDetectionStabilizer;
import com.subhub.app.detection.VisualIdentityReconciler;
import com.subhub.app.detection.VisualTrackArbitrator;
import com.subhub.app.detection.text.AccessibilityTextSmutDetector;
import com.subhub.app.detection.text.DetectionFusion;
import com.subhub.app.detection.text.OcrTextSmutDetector;
import com.subhub.app.detection.text.SmutTextClassifier;
import com.subhub.app.detection.text.TextDetectionCoordinateMapper;
import com.subhub.app.detection.text.TextDetectionStabilizer;
import com.subhub.app.detection.text.TextSmutConfig;
import com.subhub.app.diagnostics.CensorLabLog;
import com.subhub.app.diagnostics.CensorLabCensorSession;
import com.subhub.app.diagnostics.CensorLabRecorder;
import com.subhub.app.diagnostics.DiagnosticsRepository;
import com.subhub.app.overlay.OverlayController;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.penance.DwellInfractionTracker;
import com.subhub.app.penance.PenanceChargeNotifier;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.popup.PopupStormManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.security.HardcoreSettingsGuard;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.AchievementManager;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.subliminal.SubliminalOverlayController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** User-enabled screenshot capture mode backed by Android's accessibility consent screen. */
public final class ScreenshotAccessibilityService extends AccessibilityService {
    /** Android's existing DUMP permission protects this explicit, read-only shell diagnostic. */
    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        if (args != null && args.length == 1 && "render-layout".equals(args[0])) {
            OverlayController current = overlay;
            if (current == null) writer.println("SUBHUB_RENDER_LAYOUT {\"schemaVersion\":1,\"active\":false}");
            else current.dumpRenderLayout(writer);
            return;
        }
        super.dump(fd, writer, args);
    }
    private static final String TAG = "ScreenshotA11y";
    private static final String DIAGNOSTICS_MODE = "Accessibility screenshot";
    private static volatile boolean running;
    private static volatile boolean recognitionActive;
    private static final long MIN_TEXT_REFRESH_MS = 120L;
    private static final long TEXT_CANDIDATE_CONFIRM_MS = 48L;
    private static final long CONTENT_TEXT_REFRESH_MS = 80L;
    private static final long CONTENT_TEXT_MAX_DEBOUNCE_MS = 500L;
    private static final long ACCESSIBILITY_TEXT_STALE_TTL_MS = 1_000L;
    // Short pauses inside a fling regularly exceed the visual 130 ms settle gate. Text traversal
    // is much more expensive than moving the existing overlay, so wait through those micro-pauses
    // instead of launching work that the next Accessibility scroll event immediately invalidates.
    private static final long SETTLED_SCROLL_REFRESH_MS = 240L;
    private static final long POST_SCROLL_TEXT_RECONCILE_MS = 900L;
    private static final long MOTION_SETTLE_MS = 130L;
    // AOSP enforces a strict >333 ms per-window request interval. Schedule at the first safe
    // millisecond instead of leaving an extra 16 ms idle on every capture.
    private static final long ACCESSIBILITY_SCREENSHOT_INTERVAL_MS = 334L;
    private static final int CALIBRATION_MAX_LIVE_BOXES = 16;
    private static final int CALIBRATION_MAX_TOTAL_BOXES = 24;
    private static final long OCR_INTERVAL_MS = 3_000L;
    private static final long OCR_CONFIRM_INTERVAL_MS = 650L;
    private static final long OCR_MOTION_SETTLE_MS = 600L;
    private static final long OCR_VISUAL_IDLE_RETRY_MS = 24L;
    private static final long OCR_VISUAL_IDLE_TIMEOUT_MS = 600L;
    private static final long OCR_RESULT_TTL_MS = 5_000L;
    private static final int OCR_MAX_DIMENSION = 1_024;
    private static final int FAST_INFERENCE_RESOLUTION = 320;
    private static final long QUALITY_REFRESH_INTERVAL_MS = 500L;
    private static final long QUALITY_SLOW_REFRESH_INTERVAL_MS = 1_000L;
    private static final long QUALITY_SLOW_RUNTIME_MS = 180L;
    // The screenshot API already enforces a 334 ms cadence, so an additional 850 ms quality gate
    // delayed confirmed coverage into a visibly separate two-second render. Start refinement on
    // the first platform-safe settled capture; generation fences still discard resumed motion.
    private static final long QUALITY_MOTION_SETTLE_MS = MOTION_SETTLE_MS;
    private static final long QUALITY_RESULT_TTL_MS = 2_500L;
    private static final long STREAMING_QUALITY_RESULT_TTL_MS = 1_000L;
    private static final long QUALITY_CONFIRMATION_INTERVAL_MS = 250L;
    private static final long QUALITY_DEFAULT_EXECUTION_BUDGET_MS = 160L;
    private static final long QUALITY_EXECUTION_GUARD_MS = 16L;
    private static final long QUALITY_MAX_EXECUTION_BUDGET_MS = 600L;
    private static final long QUALITY_RESERVED_TICK_MS = 300L;
    private static final long QUALITY_RESERVATION_DISPATCH_GUARD_MS = 48L;
    private static final long QUALITY_RETRY_MS = 48L;
    private static final long FAST_GATE_MAX_WAIT_MS = 0L;
    private static final long QUALITY_CIRCUIT_BREAKER_MS = 30_000L;
    private static final AutoCloseable CONCURRENT_QUALITY_PERMIT = () -> {};
    /** Hard capture-to-visible budget; lane joining stops early enough to make the next vsync. */
    private static final long ATOMIC_SCENE_VISIBLE_DEADLINE_MS = 280L;
    private static final long ATOMIC_SCENE_JOIN_GUARD_MS = 32L;

    private final AccessibilityCaptureScheduler screenshotScheduler =
            new AccessibilityCaptureScheduler(SystemClock::uptimeMillis,
                    this::scheduleCaptureTask, () -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) requestScreenshot();
                    },
                    ACCESSIBILITY_SCREENSHOT_INTERVAL_MS);
    private final AtomicBoolean inferenceDraining = new AtomicBoolean();
    private final AtomicBoolean settledInferenceNeeded = new AtomicBoolean();
    private final AtomicBoolean qualityConfirmationRequested = new AtomicBoolean();
    private final AtomicBoolean qualityConfirmationBurstUsed = new AtomicBoolean();
    private final AtomicLong qualityBatchCommittedGeneration =
            new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong qualityBatchClosedGeneration =
            new AtomicLong(Long.MIN_VALUE);
    private final AtomicReference<InferenceFrame> pendingInference = new AtomicReference<>();
    private final AtomicBoolean qualityInferenceDraining = new AtomicBoolean();
    private final AtomicBoolean qualityInferenceExecuting = new AtomicBoolean();
    private final Object qualityScheduleLock = new Object();
    private volatile ScheduledFuture<?> qualityRetrySchedule;
    private final AtomicLong droppedInferenceFrames = new AtomicLong();
    private final AtomicLong droppedQualityInferenceFrames = new AtomicLong();
    private final AtomicLong staleQualityInferenceFrames = new AtomicLong();
    private final AtomicLong qualityInferencePreemptions = new AtomicLong();
    private final AtomicLong qualityInferenceCancelledRuns = new AtomicLong();
    private final AtomicLong qualityCircuitOpenUntilUptime = new AtomicLong();
    private final AtomicLong qualityReservationUntilUptime = new AtomicLong();
    private final QualityPresentationSlot<LateQualityPresentation> pendingLateQualityPresentation =
            new QualityPresentationSlot<>();
    private final AtomicBoolean immediateQualityScheduled = new AtomicBoolean();
    private volatile DisplayedQualityBasis displayedQualityBasis;
    private final AtomicLong fastSubmissionSequence = new AtomicLong();
    private final AtomicLong qualityTilePassSequence = new AtomicLong();
    private final SceneTransactionCoordinator<Detection> sceneCoordinator =
            new SceneTransactionCoordinator<>(SystemClock::uptimeMillis);
    private final AtomicReference<SceneContext> currentScene = new AtomicReference<>();
    private final Object sceneLifecycleLock = new Object();
    private volatile LatestFrameBroker<PendingScenePresentation> scenePresenter;
    private final AtomicLong cumulativeScrollX = new AtomicLong();
    private final AtomicLong cumulativeScrollY = new AtomicLong();
    private final AtomicLong pendingTrackerOffsetX = new AtomicLong();
    private final AtomicLong pendingTrackerOffsetY = new AtomicLong();
    private volatile long trackerScrollX;
    private volatile long trackerScrollY;
    private final AtomicLong motionGeneration = new AtomicLong();
    private final AtomicLong textSceneGeneration = new AtomicLong();
    private final AtomicLong textContentGeneration = new AtomicLong();
    private final Object scrollStateLock = new Object();
    private final AtomicBoolean firstFrameReported = new AtomicBoolean();
    private final AtomicBoolean firstOverlayReported = new AtomicBoolean();
    private final AtomicBoolean initializing = new AtomicBoolean();
    private final AtomicBoolean qualityInitializing = new AtomicBoolean();
    private final FastPriorityInferenceGate inferenceGate = new FastPriorityInferenceGate();
    // Person work shares the fast executor; this lock additionally excludes NNAPI quality.
    private final java.util.concurrent.locks.ReentrantLock optionalInferenceLock =
            new java.util.concurrent.locks.ReentrantLock();
    private final QualityConcurrencyGovernor qualityConcurrencyGovernor =
            new QualityConcurrencyGovernor();
    private final AtomicBoolean rectangularFastInputDisabled = new AtomicBoolean();
    private final AtomicBoolean rectangularFastInputReported = new AtomicBoolean();
    private final AtomicLong startupSessionSequence = new AtomicLong();
    private final AtomicBoolean hardcoreGuardRefreshQueued = new AtomicBoolean();
    private final AtomicBoolean textRefreshRequested = new AtomicBoolean(true);
    private final AtomicBoolean textRefreshRunning = new AtomicBoolean();
    private final AtomicBoolean contentTextRefreshScheduled = new AtomicBoolean();
    private final AtomicInteger textContentEvents = new AtomicInteger();
    private final AtomicInteger textContentChangeTypes = new AtomicInteger();
    private final AtomicInteger textContentStaleRetries = new AtomicInteger();
    private final AtomicInteger accessibilityCandidateScans = new AtomicInteger();
    private final AtomicReference<AccessibilityTextSmutDetector.ScanResult>
            pendingTextConfirmation = new AtomicReference<>();
    private final AtomicBoolean ocrRunning = new AtomicBoolean();
    private final AtomicBoolean ocrConfirmationRequested = new AtomicBoolean();
    private final AtomicReference<Bitmap> activeOcrBitmap = new AtomicReference<>();
    private final CaptureEpoch captureEpoch = new CaptureEpoch();
    private final AccessibilityScrollMotionResolver scrollMotionResolver =
            new AccessibilityScrollMotionResolver();
    private final ScrollCompanionDeduplicator scrollCompanionDeduplicator =
            new ScrollCompanionDeduplicator();
    private final AccessibilitySurfaceIdentityResolver scrollSurfaceIdentityResolver =
            new AccessibilitySurfaceIdentityResolver();
    private final ScrollDeltaStabilizer scrollDeltaStabilizer = new ScrollDeltaStabilizer();
    private final CaptureScrollTimeline captureScrollTimeline = new CaptureScrollTimeline();
    private final RenderSourceTimeline renderSourceTimeline = new RenderSourceTimeline();
    private final AtomicReference<RenderSourceReference.Origin> renderSourceOrigin = new AtomicReference<>();
    private final ContentSpaceRegionCache contentSpaceRegionCache =
            new ContentSpaceRegionCache();
    private final QualityBackfillCoordinator<QualityInferenceFrame> qualityBackfillCoordinator =
            new QualityBackfillCoordinator<>();
    /** One owned quality source; motion is intentionally not a fence for this mailbox. */
    private final QualityBackfillRunner<QualityInferenceFrame> qualityBackfillRunner =
            new QualityBackfillRunner<>(
                    qualityBackfillCoordinator, QualityBackfillRunner.Policy.defaults());
    private final Object worldCacheLock = new Object();
    private final AtomicLong visualDocumentEpoch = new AtomicLong(1L);
    private final AtomicInteger activeApplicationWindowId = new AtomicInteger(-1);
    private volatile String activeScrollSurfaceKey = "";
    private volatile int activeScrollSurfaceWindowId = -1;
    private volatile long activeScrollTelemetryToken;
    private volatile boolean activeScrollSurfaceProvisional;
    private volatile byte activeScrollSurfaceConfidence =
            AccessibilitySurfaceIdentityResolver.CONFIDENCE_LOW;
    private volatile long activeScrollSurfaceLastTrustedUptime;
    private volatile int activeScrollSurfaceLowReuseCount;
    private long lastWorldCacheQueryX = Long.MIN_VALUE;
    private long lastWorldCacheQueryY = Long.MIN_VALUE;
    private long lastWorldCacheQueryDocument = Long.MIN_VALUE;
    private String lastWorldCacheQuerySurface = "";
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean productQualificationRoute;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener listener =
            (preferences, key) -> {
                if (productQualificationRoute) reloadProductSettings(key);
                else reloadSettings();
            };
    private CensorLabCensorSession.Subscription labCensorSubscription;
    private int qualificationBoundPid = -1;
    private static final java.util.concurrent.atomic.AtomicLong PRODUCT_CONNECTIONS = new java.util.concurrent.atomic.AtomicLong();
    private CensorProductSession productSession;
    private ScheduledExecutorService worker;
    private final RowMotionObserver rowMotionObserver = new RowMotionObserver();
    private SpatialRegionCache spatialRegionCache;
    private CaptureGapProbe captureGapProbe;
    private java.io.File captureGapArmFile;
    private boolean spatialCacheExperiment;
    private boolean spatialTrackingExperiment;
    private boolean correctSpatialTracks;
    private final SourceTrackContinuity sourceTrackContinuity = new SourceTrackContinuity();
    private final VisualCameraShadow visualCameraShadow = new VisualCameraShadow();
    private PreparedFrameRecorder preparedFrameRecorder;
    private ChromeGeometryProbe chromeGeometryProbe;
    private boolean rowMotionShadow;
    private volatile boolean gpuPreparationExperiment;
    private boolean gpuPreparationReady; // capture-worker owned
    private final AtomicBoolean gpuWarmupScheduled = new AtomicBoolean();
    private GpuBitmapPreparer gpuBitmapPreparer; // capture-worker owned
    private final GpuPreparationHealth gpuPreparationHealth = new GpuPreparationHealth();
    private ScheduledExecutorService inferenceWorker;
    private ScheduledExecutorService qualityInferenceWorker;
    private ScheduledExecutorService textWorker;
    private ScheduledExecutorService ocrWorker;
    private ScheduledExecutorService scrollLookupWorker;
    private SerialOwnedLookup<PendingScrollEvent> scrollLookup;
    private final AtomicLong scrollLookupSequence = new AtomicLong();
    private final AtomicLong scrollInputGeneration = new AtomicLong();
    private static final int SCROLL_LOOKUP_CAPACITY = 32;
    private static final long SCROLL_LOOKUP_MAX_WAIT_MS = 1_500L;
    private volatile AsyncViewportAnchorSampler experimentalAnchorSampler;
    private LatestFrameBroker<AnchorPresentation> anchorPresenter;
    private final AtomicLong anchorSession = new AtomicLong();
    private long lastAnchorTelemetryUptime;
    private long anchorPresented, anchorDeliveryDrops;
    private volatile int anchorFrameIntervalMillis = 16;
    private final Runnable anchorTelemetry = new Runnable() {
        @Override public void run() {
            AsyncViewportAnchorSampler sampler = experimentalAnchorSampler;
            if (sampler == null) return;
            AsyncViewportAnchorSampler.Stats stats = sampler.stats();
            Log.i(TAG, "ANCHOR_ASYNC_STATS reads=" + stats.reads + " accepted=" + stats.accepted
                    + " slowDrops=" + stats.slowDrops + " invalidDrops=" + stats.invalidDrops
                    + " resets=" + stats.resets + " maxReadMs=" + stats.maxReadMs
                    + " rejectionCounts=" + stats.rejectionCounts
                    + " singleNodeBudgetDrops=" + stats.singleNodeBudgetDrops
                    + " aggregateBudgetDrops=" + stats.aggregateBudgetDrops
                    + " discoveryBudgetDrops=" + stats.discoveryBudgetDrops
                    + " maxNodeMs=" + stats.maxNodeMs
                    + " presented=" + anchorPresented + " deliveryDrops=" + anchorDeliveryDrops
                    + " mailboxDrops=" + (anchorPresenter == null ? 0 : anchorPresenter.droppedCount()));
            main.postDelayed(this, 1000L);
        }
    };
    private SettingsRepository settings;
    private StatsRepository stats;
    private volatile DetectionEngine detector;
    private volatile DetectionEngine fastDetector;
    private ObjectTracker tracker;
    private SmutTextClassifier smutTextClassifier;
    private AccessibilityTextSmutDetector accessibilityText;
    private OcrTextSmutDetector screenshotText;
    private final TextDetectionStabilizer accessibilityTextStabilizer =
            new TextDetectionStabilizer();
    private final TextDetectionStabilizer ocrTextStabilizer = new TextDetectionStabilizer();
    private final VisualDetectionStabilizer qualityVisualStabilizer =
            new VisualDetectionStabilizer();
    private OverlayController overlay;
    private volatile DetectorConfig detectorConfig;
    private volatile TextSmutConfig textSmutConfig;
    private volatile TextDetectionSnapshot cachedAccessibilityText = TextDetectionSnapshot.EMPTY;
    private volatile TextDetectionSnapshot cachedOcrText = TextDetectionSnapshot.EMPTY;
    private volatile VisualDetectionSnapshot cachedQualityVisual =
            VisualDetectionSnapshot.EMPTY;
    private volatile boolean accessibilityTextCandidatesPresent;
    private volatile int latestCaptureWidth = 1;
    private volatile int latestCaptureHeight = 1;
    private volatile long lastTextRefreshMillis;
    private volatile long lastTextContentChangeUptime;
    private volatile long textContentBurstStartedUptime;
    private volatile long accessibilityTextInvalidatedAtUptime;
    private volatile long lastOcrCompletionUptime;
    private volatile long lastMotionUptime;
    private volatile long lastInferenceUptime;
    private volatile long lastQualityInferenceUptime;
    private volatile long lastSuccessfulQualityDurationMs;
    private volatile long activeStartupSession;
    private volatile long lastFastOverlayGeneration = Long.MIN_VALUE;
    private volatile long lastScrollDiagnosticUptime;
    private volatile long scrollTraceId;
    private volatile long scrollTraceStartedUptime;
    private volatile long lastScrollTraceEventUptime;
    private volatile long touchTraceId;
    private volatile long touchTraceStartedUptime;
    private volatile boolean touchInteractionActive;
    private String lastPublishedTextFingerprint = "";
    private long skippedUnchangedTextPublishes;
    private final Map<Integer, BBox> lastPublishedVisualBoxes = new HashMap<>();
    private volatile boolean overlayNeedsSourceFrame;
    private volatile String foregroundPackage = "";
    private volatile String guardForegroundPackage = "";
    private AppTimerManager timers;
    private PenanceManager penance;
    private final DwellInfractionTracker dwellTracker = new DwellInfractionTracker();
    private final CensorTapTracker tapTracker = CensorTapTracker.shared();
    private long lastMatchedTapUptimeMillis;
    private long lastMatchedTapEventUptimeMillis;
    private long foregroundSinceMillis;
    private String lastBlockedPackage = "";
    private long lastBlockedAtMillis;
    private HardcoreSettingsGuard hardcoreSettingsGuard;
    private ScrollFrameMotionEstimator motionEstimator;
    private SubliminalOverlayController subliminalOverlay;
    private final Runnable settledHardcoreGuardRefresh = () -> {
        hardcoreGuardRefreshQueued.set(false);
        refreshHardcoreSettingsGuard();
    };
    private final Runnable settledTextRefresh = this::requestTextRefresh;
    private final Runnable contentTextRefresh = () -> {
        contentTextRefreshScheduled.set(false);
        textContentBurstStartedUptime = 0L;
        requestTextRefresh();
    };
    private final Runnable staleAccessibilityTextExpiry = () ->
            publishTextLane(captureEpoch.token(), "accessibility-expiry");
    private final Runnable settledScrollTrace = () -> {
        long now = SystemClock.uptimeMillis();
        long idleMs = now - lastScrollTraceEventUptime;
        if (lastScrollTraceEventUptime > 0L && idleMs >= MOTION_SETTLE_MS) {
            CensorLabLog.i(TAG, "SCROLL_IDLE id=" + scrollTraceId + " afterLastEventMs=" + idleMs
                    + " durationMs=" + (now - scrollTraceStartedUptime));
        }
    };
    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                long now = System.currentTimeMillis();
                boolean foregroundChanged = !productQualificationRoute && syncForegroundFromActiveRoot(now);
                if (!foregroundChanged) accountForegroundUsage(now);
                boolean limited = enforceForegroundLimit(now);
                if (!productQualificationRoute || !limited) reevaluateRecognition();
                reevaluateSubliminals();
                refreshHardcoreSettingsGuard();
            } catch (RuntimeException error) {
                DiagnosticsRepository.fail(DIAGNOSTICS_MODE, error);
                Log.e(TAG, "Accessibility service tick failed", error);
            } finally {
                if (running) main.postDelayed(this, 1_000L);
            }
        }
    };

    public static boolean isRunning() { return running; }
    public static boolean isRecognitionActive() { return recognitionActive; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        MainThreadSampler.startIfArmed(this);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Accessibility screenshot capture requires Android 11 or newer");
            disableSelf();
            return;
        }
        settings = new SettingsRepository(this);
        stats = new StatsRepository(this);
        timers = new AppTimerManager(this);
        penance = new PenanceManager(this);
        productQualificationRoute = ProductCensorQualification.enabled(this);
        hardcoreSettingsGuard = new HardcoreSettingsGuard(this);
        subliminalOverlay = new SubliminalOverlayController(this);
        configureAccessibilityCadence(settings.loadDetectorConfig());
        if (productQualificationRoute && Build.VERSION.SDK_INT >= 34) {
            qualificationBoundPid = Process.myPid();
            settings.preferences().registerOnSharedPreferenceChangeListener(listener);
            running = true;
            connectProductQualification();
            main.post(timerTick);
            return; // No legacy model, text, motion, capture or lookup worker is initialized.
        }
        smutTextClassifier = new SmutTextClassifier(this);
        accessibilityText = new AccessibilityTextSmutDetector(smutTextClassifier);
        screenshotText = new OcrTextSmutDetector(smutTextClassifier);
        motionEstimator = new ScrollFrameMotionEstimator();
        settings.preferences().registerOnSharedPreferenceChangeListener(listener);
        running = true;

        worker = newScheduledWorker("SubHub-capture", Process.THREAD_PRIORITY_DISPLAY);
        rowMotionShadow = BuildConfig.DEBUG && getSharedPreferences("row_motion_experiment", MODE_PRIVATE)
                .getBoolean("enabled", false);
        if (rowMotionShadow) preparedFrameRecorder = PreparedFrameRecorder.startIfArmed(this);
        spatialCacheExperiment = BuildConfig.DEBUG && getSharedPreferences(
                "spatial_cache_experiment", MODE_PRIVATE).getBoolean("enabled", false);
        spatialTrackingExperiment = BuildConfig.DEBUG
                && (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
                && getSharedPreferences("spatial_tracking_experiment", MODE_PRIVATE).getBoolean("enabled", false);
        correctSpatialTracks = spatialTrackingExperiment && getSharedPreferences(
                "spatial_tracking_experiment", MODE_PRIVATE).getBoolean("correctTracks", false);
        // Registration-only A/B must not admit either spatial or legacy cache output.
        if (spatialTrackingExperiment) spatialCacheExperiment = false;
        if (spatialCacheExperiment || spatialTrackingExperiment) {
            spatialRegionCache = new SpatialRegionCache(spatialTrackingExperiment);
        }
        captureGapProbe = null;
        captureGapArmFile = null;
        if (BuildConfig.DEBUG && (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
                && getSharedPreferences("capture_gap_experiment", MODE_PRIVATE).getBoolean("enabled", false)) {
            captureGapProbe = new CaptureGapProbe();
            captureGapArmFile = new java.io.File(getCacheDir(), "capture-gap-arm");
        }
        gpuPreparationExperiment = BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 29
                && getSharedPreferences("gpu_preparation_experiment", MODE_PRIVATE)
                .getBoolean("enabled", false);
        inferenceWorker = newScheduledWorker(
                "SubHub-fast-inference", Process.THREAD_PRIORITY_DISPLAY);
        qualityInferenceWorker = newScheduledWorker(
                "SubHub-quality-inference", Process.THREAD_PRIORITY_DEFAULT);
        textWorker = newScheduledWorker("SubHub-text", Process.THREAD_PRIORITY_BACKGROUND);
        ocrWorker = newScheduledWorker("SubHub-ocr", Process.THREAD_PRIORITY_BACKGROUND);
        scrollLookupWorker = newScheduledWorker("SubHub-scroll-owner", Process.THREAD_PRIORITY_DEFAULT);
        scrollLookup = new SerialOwnedLookup<>(SCROLL_LOOKUP_CAPACITY, scrollLookupWorker,
                this::resolveScrollLookup, pending -> {
                    traceScrollLookup(pending, "released", 0L);
                    pending.event.recycle();
                }, (pending, error) -> main.post(() -> recoverScrollLookupGap(pending, "lookup-failure")));
        worker.execute(this::initializePipeline);
        main.post(this::reevaluateRecognition);
        main.post(this::reevaluateSubliminals);
        labCensorSubscription = CensorLabCensorSession.observe(() -> main.post(() -> {
            if (!running || labCensorSubscription == null) return;
            // Detach usage at the session boundary; Lab must not count as normal Limits time.
            foregroundSinceMillis = System.currentTimeMillis();
            captureEpoch.invalidate();
            invalidateWorldCache("lab-boundary");
            resetTextSnapshots();
            if (overlay != null) overlay.clear();
            if (worker != null) worker.execute(() -> { if (tracker != null) tracker.clear(); });
            reevaluateRecognition();
            reevaluateSubliminals();
            dwellTracker.clear();
            tapTracker.clear();
        }));
        main.post(timerTick);
        chromeGeometryProbe = ChromeGeometryProbe.startIfArmed(this);
    }

    @RequiresApi(34)
    private void connectProductQualification() {
        final long connection = PRODUCT_CONNECTIONS.incrementAndGet();
        if (connection <= 0) throw new IllegalStateException("Replacement connection capacity");
        productSession = ProductCensorQualification.connect(this, tapTracker, new CensorProductSession.Observer() {
            @Override public void nativeReadCostStarted(com.subhub.app.censor.engine.NativeReadCost.Start value) {
                if (CensorLabRecorder.isActive()) CensorLabLog.i(TAG,
                        com.subhub.app.diagnostics.ReplacementEngineTelemetry.nativeCostStart(connection, value));
            }
            @Override public void nativeReadCost(com.subhub.app.censor.engine.NativeReadCost value) {
                if (CensorLabRecorder.isActive()) CensorLabLog.i(TAG,
                        com.subhub.app.diagnostics.ReplacementEngineTelemetry.nativeCost(connection, value));
            }
            @Override public void nativeTextAccepted(com.subhub.app.censor.engine.NativeTextFrame value) {
                if (CensorLabRecorder.isActive()) CensorLabLog.i(TAG,
                        com.subhub.app.diagnostics.ReplacementEngineTelemetry.nativeRead(connection, value));
            }
            @Override public void trace(com.subhub.app.censor.engine.CensorEngine.Trace value) {
                if (CensorLabRecorder.isActive()) CensorLabLog.i(TAG,
                        com.subhub.app.diagnostics.ReplacementEngineTelemetry.trace(connection, value));
            }
            @Override public void scrollReceived(com.subhub.app.censor.engine.ScrollMotionLedger.Receipt value) {
                if (CensorLabRecorder.isActive()) CensorLabLog.i(TAG,
                        com.subhub.app.diagnostics.ReplacementEngineTelemetry.scroll(connection, value));
            }
            @Override public void posted(com.subhub.app.censor.android.SnapshotCensorSurface.Post value) {
                if (CensorLabRecorder.isActive()) CensorLabLog.i(TAG,
                        com.subhub.app.diagnostics.ReplacementEngineTelemetry.post(connection, value));
            }
            @Override public void foreground(AccessibilityWindowReader.Window window) {
                String packageName = window == null ? "" : window.packageName;
                acceptForegroundPackage(packageName, System.currentTimeMillis());
                activeApplicationWindowId.set(window == null ? -1 : window.id);
                guardForegroundPackage = packageName;
                syncProductParticipation();
            }
            @Override public void activeChanged(boolean active) {
                recognitionActive = active;
                if (active) DiagnosticsRepository.begin(DIAGNOSTICS_MODE, settings.loadDetectorConfig().getInferenceResolution());
                else DiagnosticsRepository.stop(DIAGNOSTICS_MODE);
            }
            @Override public void providerReady(SceneEngine.Lane lane, String provider, long millis) {
                if (lane == SceneEngine.Lane.FAST) {
                    DetectorConfig config = settings.loadDetectorConfig();
                    DiagnosticsRepository.ready(DIAGNOSTICS_MODE, provider, config.getModelFilename(), Math.min(320, config.getInferenceResolution()));
                }
            }
            @Override public void modelUnavailable(SceneEngine.Lane lane) {
                DiagnosticsRepository.fail(DIAGNOSTICS_MODE, new IllegalStateException("Replacement " + lane + " model unavailable"));
            }
            @Override public void sessionFailure(Throwable failure) { DiagnosticsRepository.fail(DIAGNOSTICS_MODE, failure); }
            @Override public void accounting(CensorProductSession.AccountingResult result) {
                if (result.status == CensorProductSession.AccountingStatus.UNACKNOWLEDGED
                        || result.status == CensorProductSession.AccountingStatus.UNCERTAIN) {
                    Log.w(TAG, "Replacement accounting quarantined phase=" + result.phase.name());
                }
            }
            @Override public void dwell(CensorProductSession.DwellResult result) {
                if (result.status == CensorProductSession.AccountingStatus.UNCERTAIN) {
                    Log.w(TAG, "Replacement dwell accounting quarantined");
                }
            }
        });
        labCensorSubscription = CensorLabCensorSession.observe(() -> main.post(() -> {
            if (!running || productSession == null) return;
            foregroundSinceMillis = System.currentTimeMillis();
            dwellTracker.clear(); tapTracker.clear();
            productSession.ownershipChanged();
            syncProductParticipation(); reevaluateSubliminals();
        }));
        productSession.request();
    }

    private void syncProductParticipation() {
        AppModeManager mode = new AppModeManager(this);
        PopupStormManager.get().syncServiceParticipation(this,
                !HardcoreSettingsGuard.isSettingsPackage(foregroundPackage) && mode.shouldShowPopups(foregroundPackage));
    }

    /** Read-only native qualification assertion; field construction does not mean a legacy runtime started. */
    public boolean legacyCensorRuntimeAbsentForQualification() {
        return productQualificationRoute && worker == null && inferenceWorker == null && qualityInferenceWorker == null
                && textWorker == null && ocrWorker == null && scrollLookupWorker == null && scrollLookup == null
                && detector == null && fastDetector == null && tracker == null && smutTextClassifier == null
                && accessibilityText == null && screenshotText == null && motionEstimator == null && overlay == null
                && scenePresenter == null && experimentalAnchorSampler == null;
    }
    public int qualificationBoundPid() { return productQualificationRoute ? qualificationBoundPid : -1; }

    @RequiresApi(Build.VERSION_CODES.R)
    private void initializePipeline() {
        if (!running || !initializing.compareAndSet(false, true)) return;
        try {
            DetectorConfig config = settings.loadDetectorConfig();
            DetectorConfig fastConfig = fastDetectorConfig(config);
            long startupSession = activeStartupSession;
            detectorConfig = config;
            textSmutConfig = settings.loadTextSmutConfig();
            warmTextModels(config);
            long fastInitStarted = SystemClock.uptimeMillis();
            Log.i(TAG, "STARTUP session=" + startupSession
                    + " phase=fast-init-begin uptimeMs=" + fastInitStarted);
            if (fastDetector == null) {
                fastDetector = new DetectionEngine(this, fastConfig, true);
                if (usesAtomicScenePipeline(config)) {
                    try {
                        // High/Ultra need two continuously available engines. CPU also unlocks
                        // the aspect-preserving rectangular tensor (about 50% less model work),
                        // while NNAPI remains exclusively available to the 512px quality lane.
                        fastDetector.initializeForProvider("CPU");
                    } catch (Exception cpuUnavailable) {
                        Log.w(TAG, "Dedicated CPU fast topology unavailable; benchmarking fallback",
                                cpuUnavailable);
                        fastDetector.initialize();
                    }
                } else {
                    fastDetector.initialize();
                }
            } else {
                fastDetector.setConfig(fastConfig);
            }
            qualityConcurrencyGovernor.reset();
            Log.i(TAG, "STARTUP session=" + startupSession
                    + " phase=fast-init-end durationMs="
                    + (SystemClock.uptimeMillis() - fastInitStarted)
                    + " uptimeMs=" + SystemClock.uptimeMillis()
                    + " provider=" + fastDetector.getActiveProvider());
            DetectorConfig trackerConfig = accessibilityTrackerConfig(config);
            if (tracker == null) tracker = new ObjectTracker(trackerConfig);
            else tracker.setConfig(trackerConfig);
            tracker.clear();
            sourceTrackContinuity.clear();
            if (!recognitionActive) {
                Log.i(TAG, "Fast detector prewarmed; capture remains asleep");
                return;
            }
            DiagnosticsRepository.begin(DIAGNOSTICS_MODE, config.getInferenceResolution());
            DiagnosticsRepository.ready(DIAGNOSTICS_MODE, fastDetector.getActiveProvider(),
                    fastDetector.getActiveModel(), fastConfig.getInferenceResolution());
            screenshotScheduler.start();
        } catch (Exception error) {
            if (detector != null) detector.close();
            detector = null;
            if (fastDetector != null) fastDetector.close();
            fastDetector = null;
            tracker = null;
            if (recognitionActive) DiagnosticsRepository.fail(DIAGNOSTICS_MODE, error);
            Log.e(TAG, "Could not initialize accessibility capture", error);
            main.post(this::deactivateRecognition);
        } finally {
            initializing.set(false);
        }
    }

    private void scheduleQualityDetectorInitialization(DetectorConfig requestedConfig) {
        ScheduledExecutorService qualityWorker = qualityInferenceWorker;
        if (!running || requestedConfig == null || !usesAtomicScenePipeline(requestedConfig)
                || qualityWorker == null
                || qualityWorker.isShutdown()
                || !qualityInitializing.compareAndSet(false, true)) return;
        try {
            qualityWorker.execute(() -> initializeQualityDetector(requestedConfig));
        } catch (RejectedExecutionException rejected) {
            qualityInitializing.set(false);
            Log.i(TAG, "QUALITY_ENGINE_SKIP reason=executor-shutdown");
        }
    }

    /** Warms optional refinement without holding time-to-first-censor behind provider setup. */
    private void initializeQualityDetector(DetectorConfig requestedConfig) {
        optionalInferenceLock.lock();
        DetectionEngine candidate = null;
        long startupSession = activeStartupSession;
        long started = SystemClock.uptimeMillis();
        Log.i(TAG, "STARTUP session=" + startupSession
                + " phase=quality-init-begin uptimeMs=" + started);
        try {
            DetectionEngine current = detector;
            if (current != null) {
                current.setConfig(qualityDetectorConfig(requestedConfig));
                Log.i(TAG, "STARTUP session=" + startupSession
                        + " phase=quality-init-end durationMs="
                        + (SystemClock.uptimeMillis() - started)
                        + " uptimeMs=" + SystemClock.uptimeMillis() + " reused=true");
                return;
            }
            candidate = new DetectionEngine(this, qualityDetectorConfig(requestedConfig), false);
            DetectionEngine realtime = fastDetector;
            candidate.initializeWithoutBenchmark(
                    realtime == null ? "CPU" : realtime.getActiveProvider());
            if (!running) return;
            DetectorConfig latestConfig = detectorConfig;
            if (latestConfig != null) candidate.setConfig(qualityDetectorConfig(latestConfig));
            detector = candidate;
            candidate = null;
            qualityConcurrencyGovernor.reset();
            qualityBackfillRunner.resetPolicyState();
            Log.i(TAG, "QUALITY_ENGINE_READY provider=" + detector.getActiveProvider()
                    + " model=" + detector.getActiveModel()
                    + " fastFrames=" + fastSubmissionSequence.get()
                    + " initMs=" + (SystemClock.uptimeMillis() - started));
            Log.i(TAG, "STARTUP session=" + startupSession
                    + " phase=quality-init-end durationMs="
                    + (SystemClock.uptimeMillis() - started)
                    + " uptimeMs=" + SystemClock.uptimeMillis());
        } catch (Exception error) {
            Log.w(TAG, "Quality refinement will remain unavailable", error);
        } finally {
            try {
                if (candidate != null) candidate.close();
            } finally {
                qualityInitializing.set(false);
                releaseOptionalInferenceResource();
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private void requestScreenshot() {
        if (!running || !recognitionActive) return;
        long requestUptime = SystemClock.uptimeMillis();
        if (captureGapProbe != null) {
            boolean armed = captureGapProbe.awaitingArm() && !screenshotScheduler.inFlight()
                    && captureGapArmFile.isFile() && captureGapArmFile.delete();
            CaptureGapProbe.Action action = captureGapProbe.poll(requestUptime, armed);
            if (action == CaptureGapProbe.Action.START || action == CaptureGapProbe.Action.END) {
                CensorLabLog.i(TAG, "CAPTURE_GAP phase="
                        + (action == CaptureGapProbe.Action.START ? "start" : "end")
                        + " nowMs=" + requestUptime + " untilMs=" + captureGapProbe.deadline());
            }
            if (action == CaptureGapProbe.Action.START || action == CaptureGapProbe.Action.WAIT) {
                traceCaptureAdmission("defer", 0L, "intentional-gap");
                wakeCaptureAt(captureGapProbe.deadline());
                return;
            }
        }
        if (requestUptime < screenshotScheduler.nextEligibleMillis()) {
            traceCaptureAdmission("defer", 0L,
                    requestUptime < screenshotScheduler.retryNotBeforeMillis()
                            ? "retry-backoff" : "platform-spacing");
            wakeCaptureAt(screenshotScheduler.nextEligibleMillis());
            return;
        }
        boolean concurrentQuality = concurrentQualityAllowed(requestUptime);
        if (!concurrentQuality && qualityBackfillRunner.circuitAllows(requestUptime)
                && shouldReserveQualityTick(
                requestUptime, lastMotionUptime, qualityReservationUntilUptime.get(),
                qualityBackfillRunner.pendingCount() > 0, qualityInferenceDraining.get())) {
            CensorLabLog.i(TAG, "QUALITY_WINDOW action=reserve-quality activeMs=0"
                    + " remainingMs=" + Math.max(0L,
                            qualityReservationUntilUptime.get() - requestUptime)
                    + " pending=" + qualityBackfillRunner.pendingCount());
            scheduleQualityInference();
            traceCaptureAdmission("defer", 0L, "quality-reservation");
            wakeCaptureAt(qualityReservationUntilUptime.get());
            return;
        }
        long qualityActiveMs = inferenceGate.qualityActiveMs(System.nanoTime());
        if (!concurrentQuality
                && shouldPreemptQualityForCapture(inferenceGate.isQualityActive())) {
            Log.i(TAG, "QUALITY_WINDOW action=yield-quality activeMs=" + qualityActiveMs);
            preemptQualityInference("fast-capture-due");
        }
        long requestId = screenshotScheduler.acquire(requestUptime);
        if (requestId == 0L) {
            traceCaptureAdmission("busy", 0L, "in-flight-or-stopped");
            return;
        }
        traceCaptureStage(requestUptime, "accepted");
        try {
            long requestedEpoch = captureEpoch.token();
            long requestedScrollX = cumulativeScrollX.get();
            long requestedScrollY = cumulativeScrollY.get();
            long requestedGeneration = motionGeneration.get();
            boolean requestedLabCensor = CensorLabCensorSession.isActive();
            ForegroundWindowResolver.Candidate liveWindow = resolveLiveApplicationWindow();
            int activeWindowId = liveWindow == null ? -1 : liveWindow.windowId;
            String livePackage = liveWindow == null ? "" : liveWindow.packageName;
            if (activeWindowId >= 0 && livePackage.equals(foregroundPackage)) {
                acceptApplicationWindow(activeWindowId);
                ensureProvisionalScrollSurface(livePackage, activeWindowId);
            }
            AppModeManager mode = new AppModeManager(this);
            if (AppModePolicy.shouldAcceptLiveForegroundPackage(
                    livePackage, mode.inputMethodPackage())
                    && !livePackage.equals(foregroundPackage)) {
                String confirmedPackage = livePackage;
                main.post(() -> acceptForegroundPackage(
                        confirmedPackage, System.currentTimeMillis()));
                traceCaptureAdmission("defer", requestId, "foreground-change");
                finishScreenshotRequest(requestId,
                        SystemClock.uptimeMillis() + ACCESSIBILITY_SCREENSHOT_INTERVAL_MS);
                return;
            }
            long requestedDocumentEpoch = visualDocumentEpoch.get();
            TakeScreenshotCallback callback = new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    traceCaptureStage(requestUptime, "callback-success");
                    try {
                        process(result, requestedEpoch, requestedScrollX, requestedScrollY,
                                requestedGeneration, requestUptime,
                                activeWindowId, requestedDocumentEpoch, requestId, requestedLabCensor);
                    } finally {
                        traceCaptureStage(requestUptime, "callback-exit");
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    traceCaptureStage(requestUptime, "callback-failure-" + errorCode);
                    if (errorCode != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        DiagnosticsRepository.failCode(
                                DIAGNOSTICS_MODE, "Screenshot error", errorCode);
                        Log.w(TAG, "Accessibility screenshot failed with code " + errorCode);
                    }
                    traceCaptureAdmission("failure", requestId, "platform-" + errorCode);
                    finishScreenshotRequest(requestId);
                }
            };
            // Android 14+ can capture the foreground app window directly. Unlike a display capture,
            // this excludes SubHub's own accessibility overlay, so a censor stays continuously visible
            // without becoming part of the next detector input.
            if (!running || !recognitionActive || !isCurrentCapture(requestedEpoch)
                    || !screenshotScheduler.dispatched(requestId, SystemClock.uptimeMillis())) {
                finishScreenshotRequest(requestId);
                return;
            }
            traceCaptureStage(requestUptime, "dispatch");
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                        && activeWindowId >= 0) {
                    takeScreenshotOfWindow(activeWindowId, worker, callback);
                } else {
                    takeScreenshot(Display.DEFAULT_DISPLAY, worker, callback);
                }
            } finally {
                // Callbacks use this same queued worker, so ownership is still held here. Retain
                // spacing from the API return even if Binder was slow or the call threw synchronously.
                screenshotScheduler.dispatchReturned(requestId, SystemClock.uptimeMillis());
                traceCaptureAdmission("dispatch", requestId, "api-return");
            }
        } catch (RuntimeException error) {
            // A synchronous framework/foreground failure must not strand the in-flight owner.
            DiagnosticsRepository.fail(DIAGNOSTICS_MODE, error);
            Log.w(TAG, "Could not dispatch accessibility screenshot", error);
            traceCaptureAdmission("failure", requestId, "dispatch-exception");
            finishScreenshotRequest(requestId,
                    SystemClock.uptimeMillis() + ACCESSIBILITY_SCREENSHOT_INTERVAL_MS);
        }
    }

    private static void traceCaptureStage(long requestUptime, String stage) {
        if (!BuildConfig.DEBUG) return;
        long now = SystemClock.uptimeMillis();
        CensorLabLog.i(TAG, "CAPTURE_SPAN id=" + requestUptime + " stage=" + stage
                + " uptimeMs=" + now + " requestAgeMs=" + (now - requestUptime));
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private void process(
            ScreenshotResult result,
            long requestedEpoch,
            long requestedScrollX,
            long requestedScrollY,
            long requestedGeneration,
            long requestedAtUptimeMillis,
            int requestedWindowId,
            long requestedDocumentEpoch,
            long screenshotRequestId,
            boolean requestedLabCensor) {
        Bitmap wrapped = null;
        Bitmap frame = null;
        HardwareBuffer buffer = result.getHardwareBuffer();
        try {
            if (!isCurrentCapture(requestedEpoch)
                    || requestedDocumentEpoch != visualDocumentEpoch.get()
                    || requestedWindowId >= 0
                    && requestedWindowId != activeApplicationWindowId.get()) return;
            wrapped = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
            if (wrapped == null) return;
            CaptureTimeReference captureTime = CaptureTimeReference.accessibility(
                    Build.VERSION.SDK_INT >= 34 && requestedWindowId >= 0,
                    requestedAtUptimeMillis, result.getTimestamp(), SystemClock.uptimeMillis());
            if (BuildConfig.DEBUG) {
                CensorLabLog.i(TAG, "CAPTURE_TIME id=" + requestedAtUptimeMillis
                        + " kind=" + captureTime.kind
                        + " requestMs=" + captureTime.requestUptimeMillis
                        + " reportedMs=" + captureTime.reportedUptimeMillis
                        + " callbackMs=" + captureTime.callbackUptimeMillis
                        + " valid=" + captureTime.valid
                        + " pixelTimeKnown=" + captureTime.pixelTimeKnown());
            }
            CaptureScrollTimeline.Phase capturePhase = captureScrollTimeline.resolve(
                    captureTime,
                    requestedScrollX, requestedScrollY, requestedGeneration)
                    .withUnresolvedMotion(hasPendingScrollLookup());
            long sourceScrollX = capturePhase.scrollX;
            long sourceScrollY = capturePhase.scrollY;
            latestCaptureWidth = wrapped.getWidth();
            latestCaptureHeight = wrapped.getHeight();
            DetectorConfig currentConfig = detectorConfig;
            boolean continuousMotionInference = usesContinuousMotionInference(currentConfig);
            long sampledGeneration = capturePhase.motionGeneration;
            long motionSampledAt = SystemClock.uptimeMillis();
            CensorLabLog.i(TAG, "CAPTURE_PHASE requestToCaptureMs="
                    + Math.max(0L, capturePhase.screenshotUptimeMillis
                    - requestedAtUptimeMillis)
                    + " callbackDelayMs=" + Math.max(0L,
                    motionSampledAt - capturePhase.screenshotUptimeMillis)
                    + " requestScroll=" + requestedScrollX + ',' + requestedScrollY
                    + " captureScroll=" + sourceScrollX + ',' + sourceScrollY
                    + " requestGeneration=" + requestedGeneration
                    + " captureGeneration=" + sampledGeneration
                    + " timelineResolved=" + capturePhase.resolvedFromMotion
                    + " phaseUncertain=" + capturePhase.phaseUncertain
                    + " eventDeliveryDelayMs=" + capturePhase.maximumDeliveryDelayMs);
            boolean estimateFrameMotion = shouldEstimateFrameMotion(
                    currentConfig, motionSampledAt, lastScrollTraceEventUptime);
            ScrollFrameMotionEstimator.Motion motion = motionEstimator == null
                    || !estimateFrameMotion
                    ? ScrollFrameMotionEstimator.Motion.NONE : motionEstimator.update(wrapped);
            if (!estimateFrameMotion && motionEstimator != null) motionEstimator.reset();
            if (motion.moved()) {
                boolean applied = applyFrameMotion(
                        motion.dx, motion.dy, sampledGeneration,
                        capturePhase.screenshotUptimeMillis, motionSampledAt);
                CensorLabLog.i(TAG, "FRAME_MOTION dx=" + motion.dx + " dy=" + motion.dy
                        + " applied=" + applied
                        + " afterAccessibilityMs=" + (lastScrollTraceEventUptime <= 0L
                                ? 0L : motionSampledAt - lastScrollTraceEventUptime));
                if (!continuousMotionInference) return;
                // Screenshot-estimated motion happened before this frame was captured, so the
                // frame itself already represents the newly updated scroll position.
                if (applied) {
                    sourceScrollX = cumulativeScrollX.get();
                    sourceScrollY = cumulativeScrollY.get();
                    sampledGeneration = motionGeneration.get();
                }
            }
            if (!continuousMotionInference
                    && sampledGeneration != motionGeneration.get()) return;
            long nowUptime = SystemClock.uptimeMillis();
            if (!continuousMotionInference
                    && nowUptime - lastMotionUptime < MOTION_SETTLE_MS) return;
            boolean priorityFrame = settledInferenceNeeded.get();
            if (!priorityFrame
                    && nowUptime - lastInferenceUptime < captureDelayMs(detectorConfig)) return;
            lastInferenceUptime = nowUptime;
            boolean motionSettled = !hasPendingScrollLookup() && (lastMotionUptime <= 0L
                    || nowUptime - lastMotionUptime >= MOTION_SETTLE_MS);
            long inferenceMotionGeneration = sampledGeneration;
            long inferenceDocumentEpoch = requestedDocumentEpoch;
            String inferenceSurfaceKey = activeScrollSurfaceKey;
            long inferenceSurfaceTelemetryToken = activeScrollTelemetryToken;
            if (!isCurrentCapture(requestedEpoch)) return;
            int inferenceResolution = currentConfig == null
                    ? FAST_INFERENCE_RESOLUTION : currentConfig.getInferenceResolution();
            boolean streamingFast = usesAtomicScenePipeline(currentConfig)
                    && fastDetector != null;
            boolean streamingQualityReady = streamingFast
                    && detector != null && detector != fastDetector
                    && usesSplitHardwareQuality(
                    fastDetector.getActiveProvider(), detector.getActiveProvider());
            boolean concurrentQuality = streamingQualityReady
                    && concurrentQualityAllowed(nowUptime);
            // Once motion settles both engines may observe this exact screenshot, but only the
            // fast lane owns presentation. Quality is opportunistic shadow/cache evidence and
            // cannot delay tracking or create a later visible scene wave.
            boolean qualityRefine = streamingFast && streamingQualityReady
                    && !textRefreshRunning.get()
                    && qualityBackfillRunner.circuitAllows(nowUptime)
                    && qualityCircuitAllows(
                    nowUptime, qualityCircuitOpenUntilUptime.get())
                    && (concurrentQuality || motionSettled && shouldRunQualityRefinement(
                    nowUptime, lastMotionUptime, lastQualityInferenceUptime,
                    firstFrameReported.get(), false,
                    lastSuccessfulQualityDurationMs, pendingInference.get() != null, false));
            int fastFrameResolution = fastInferenceFrameResolution(
                    currentConfig, fastDetector != null, overlayNeedsSourceFrame);
            traceCaptureStage(requestedAtUptimeMillis, "prepare-start");
            InferenceBitmapPreparer.Prepared prepared = null;
            if (gpuPreparationExperiment && gpuPreparationReady && Build.VERSION.SDK_INT >= 29) {
                long gpuStarted = SystemClock.uptimeMillis();
                if (gpuBitmapPreparer == null) gpuBitmapPreparer = new GpuBitmapPreparer();
                prepared = gpuBitmapPreparer.prepare(wrapped, fastFrameResolution, overlayNeedsSourceFrame);
                long gpuElapsed = SystemClock.uptimeMillis() - gpuStarted;
                CensorLabLog.i(TAG, "GPU_PREPARE elapsedMs=" + gpuElapsed
                        + " success=" + (prepared != null));
                // A failed/stalled experiment must not repeatedly tax every subsequent capture.
                if (!gpuPreparationHealth.record(prepared != null, gpuElapsed)) {
                    gpuPreparationExperiment = false;
                    gpuPreparationReady = false;
                    gpuBitmapPreparer.close();
                    gpuBitmapPreparer = null;
                }
            }
            if (prepared == null) prepared = InferenceBitmapPreparer.prepare(
                    wrapped, fastFrameResolution, overlayNeedsSourceFrame);
            if (prepared == null) return;
            if (BuildConfig.DEBUG) {
                CensorLabLog.i(TAG, "CAPTURE_PREPARE id=" + requestedAtUptimeMillis
                        + " scaleUs=" + prepared.scaleNanos / 1_000L
                        + " readbackUs=" + prepared.readbackNanos / 1_000L
                        + " hardwareReadback=" + prepared.hardwareReadback
                        + " source=" + prepared.sourceWidth + 'x' + prepared.sourceHeight
                        + " output=" + prepared.bitmap.getWidth() + 'x'
                        + prepared.bitmap.getHeight());
            }
            traceCaptureStage(requestedAtUptimeMillis, "prepare-end");
            frame = prepared.bitmap;
            SpatialRegionCache.Frame spatialFrame = null;
            if (spatialCacheExperiment || spatialTrackingExperiment) {
                long started = android.os.Debug.threadCpuTimeNanos();
                int fw = frame.getWidth(), fh = frame.getHeight();
                int[] pixels = new int[fw * fh];
                frame.getPixels(pixels, 0, fw, 0, 0, fw, fh);
                Rect viewport = screenBounds();
                RowMotionObserver.Scope spatialScope = new RowMotionObserver.Scope(requestedEpoch,
                        requestedDocumentEpoch, requestedWindowId, prepared.sourceWidth, prepared.sourceHeight);
                spatialFrame = spatialRegionCache.register(pixels, fw, fh, fh / 5, fh * 19 / 20,
                        spatialScope,
                        requestedAtUptimeMillis, capturePhase.screenshotUptimeMillis,
                        spatialRegionCache.motionHintForReference(spatialScope,
                                lastScrollTraceEventUptime, capturePhase.screenshotUptimeMillis),
                        sourceScrollX, sourceScrollY, viewport.width(), viewport.height());
                CensorLabLog.i(TAG, "SPATIAL_CACHE_FRAME id=" + requestedAtUptimeMillis
                        + " status=" + spatialFrame.result.status
                        + " known=" + (spatialFrame.result.pose != null)
                        + " cpuUs=" + (android.os.Debug.threadCpuTimeNanos() - started) / 1000);
            }
            if (rowMotionShadow) {
                long rowStarted = SystemClock.uptimeMillis();
                long rowCpuStarted = android.os.Debug.threadCpuTimeNanos();
                if (capturePhase.phaseUncertain) rowMotionObserver.clear();
                else {
                    int fw=frame.getWidth(), fh=frame.getHeight();
                    int[] rowPixels=new int[fw*fh];
                    frame.getPixels(rowPixels,0,fw,0,0,fw,fh);
                    RowMotionObserver.Sample rowSample=rowMotionObserver.observe(rowPixels,fw,fh,
                            fh/5,fh*19/20,new RowMotionObserver.Scope(requestedEpoch,
                            requestedDocumentEpoch,requestedWindowId,prepared.sourceWidth,prepared.sourceHeight),
                            capturePhase.screenshotUptimeMillis,
                            lastScrollTraceEventUptime>0 && SystemClock.uptimeMillis()-lastScrollTraceEventUptime<750);
                    CensorLabLog.i(TAG,"ROW_MOTION accepted="+rowSample.accepted
                            +" previousMs="+rowSample.previousTime+" currentMs="+rowSample.currentTime
                            +" dyMilliPx="+Math.round(rowSample.dy*1000)+" bands="+rowSample.bands
                            +" preparedHeight="+fh+" sourceHeight="+prepared.sourceHeight
                            +" costMs="+(SystemClock.uptimeMillis()-rowStarted)
                            +" cpuUs="+(android.os.Debug.threadCpuTimeNanos()-rowCpuStarted)/1000);
                    VisualCameraShadow.Result cameraSample = visualCameraShadow.observe(
                            new RowMotionObserver.Scope(requestedEpoch, requestedDocumentEpoch,
                                    requestedWindowId, prepared.sourceWidth, prepared.sourceHeight),
                            rowSample, fh, sourceScrollY, captureTime);
                    CensorLabLog.i(TAG, "ROW_CAMERA previousMs=" + rowSample.previousTime
                            + " currentMs=" + rowSample.currentTime
                            + " scopeValid=" + cameraSample.scopeValid
                            + " accepted=" + cameraSample.accepted
                            + " uncertain=" + cameraSample.uncertain
                            + " horizontal=" + cameraSample.horizontal
                            + " pixelTimeKnown=" + cameraSample.pixelTimeKnown
                            + " frameMilliY=" + Math.round(cameraSample.frameY * 1000)
                            + " eventFrameMilliY=" + sourceScrollY * 1000L
                            + " correctionMilliY=" + Math.round(cameraSample.correctionY * 1000)
                            + " cameraMilliY=" + Math.round(cameraSample.cameraY * 1000));
                    if (preparedFrameRecorder != null) {
                        preparedFrameRecorder.offer(rowPixels, fw, fh, rowSample.currentTime);
                    }
                }
            }
            if (requestedDocumentEpoch != visualDocumentEpoch.get()
                    || requestedWindowId >= 0
                    && requestedWindowId != activeApplicationWindowId.get()) return;
            // Priority means "publish the first settled fast frame", not "immediately saturate
            // the CPU with quality and text refinement at the same time".
            if (priorityFrame && motionSettled) {
                settledInferenceNeeded.compareAndSet(true, false);
            }
            long submissionSequence;
            synchronized (scrollStateLock) {
                submissionSequence = fastSubmissionSequence.incrementAndGet();
            }
            SceneContext scene = streamingFast
                    ? stageScene(requestedEpoch, submissionSequence,
                            inferenceMotionGeneration, capturePhase.screenshotUptimeMillis,
                            requestedAtUptimeMillis,
                            motionSettled, qualityRefine, continuousMotionInference, spatialFrame)
                    : null;
            traceCaptureStage(requestedAtUptimeMillis, "scene-staged");
            InferenceFrame inferenceFrame = new InferenceFrame(
                    frame, requestedEpoch, sourceScrollX,
                    sourceScrollY, inferenceMotionGeneration,
                    prepared.sourceWidth, prepared.sourceHeight,
                    prepared.retainedSourceFrame, continuousMotionInference,
                    false,
                    false,
                    requestedAtUptimeMillis,
                    requestedScrollX, requestedScrollY, requestedGeneration,
                    captureTime,
                    capturePhase.phaseUncertain,
                    capturePhase.maximumDeliveryDelayMs,
                    inferenceDocumentEpoch, inferenceSurfaceKey,
                    submissionSequence, requestedWindowId, scene);
            inferenceFrame.labCensorFrame = requestedLabCensor;
            long submittedFastSequence = enqueueInference(inferenceFrame);
            frame = null;
            maybeRequestOcr(wrapped, requestedEpoch, sourceScrollX, sourceScrollY,
                    inferenceMotionGeneration, currentConfig);
            // Transfer the immutable hardware screenshot to the quality lane instead of making
            // its 512 px software copy on the latency-critical capture callback. One retained
            // source is allowed; a replacement or structural reset closes it, while ordinary
            // motion leaves it available for cache-only old-frame refinement.
            if (submittedFastSequence != Long.MIN_VALUE
                    && streamingQualityReady && qualityRefine && scene != null) {
                Rect captureViewport = screenBounds();
                enqueueQualityInference(new QualityInferenceFrame(
                        wrapped, buffer, requestedEpoch, sourceScrollX, sourceScrollY,
                        inferenceMotionGeneration, wrapped.getWidth(), wrapped.getHeight(),
                        inferenceResolution, capturePhase.screenshotUptimeMillis,
                        submittedFastSequence, inferenceDocumentEpoch, inferenceSurfaceKey,
                        inferenceSurfaceTelemetryToken, !capturePhase.phaseUncertain,
                        captureViewport.width(), captureViewport.height(), requestedWindowId, scene));
                wrapped = null;
                buffer = null;
            }
        } catch (Exception error) {
            DiagnosticsRepository.fail(DIAGNOSTICS_MODE, error);
            Log.w(TAG, "Could not process accessibility screenshot", error);
        } finally {
            if (frame != null && !frame.isRecycled()) frame.recycle();
            if (wrapped != null && !wrapped.isRecycled()) wrapped.recycle();
            if (buffer != null) buffer.close();
            finishScreenshotRequest(screenshotRequestId);
        }
    }

    private long enqueueInference(InferenceFrame candidate) {
        if (!running || inferenceWorker == null || inferenceWorker.isShutdown()) {
            invalidateScene(candidate.scene, "fast-worker-unavailable");
            candidate.recycle();
            return Long.MIN_VALUE;
        }
        boolean concurrentQuality = concurrentQualityAllowed(SystemClock.uptimeMillis());
        candidate.fastDemand = concurrentQuality ? null : inferenceGate.registerFastDemand();
        long submissionSequence = candidate.fastSubmissionSequence;
        // A fresh real-time frame outranks optional quality refinement. ORT cancellation is
        // best-effort, but requesting it here avoids the check-then-start race where an idle
        // quality lane begins immediately before new fast work arrives.
        if (!concurrentQuality) preemptQualityInference("fast-arrived");
        InferenceFrame replaced = pendingInference.getAndSet(candidate);
        if (replaced != null) {
            droppedInferenceFrames.incrementAndGet();
            invalidateScene(replaced.scene, "fast-pending-replaced");
            replaced.recycle();
        }
        if (inferenceWorker != null && inferenceDraining.compareAndSet(false, true)) {
            try {
                inferenceWorker.execute(this::drainInferenceQueue);
            } catch (RejectedExecutionException rejected) {
                inferenceDraining.set(false);
                discardPendingInference();
                invalidateScene(candidate.scene, "fast-executor-rejected");
                return Long.MIN_VALUE;
            }
        }
        return submissionSequence;
    }

    private SceneContext stageScene(
            long epoch,
            long fastSequence,
            long generation,
            long screenshotUptimeMillis,
            long requestedAtUptimeMillis,
            boolean motionSettled,
            boolean qualityExpected,
            boolean continuousMotionInference,
            SpatialRegionCache.Frame spatialFrame) {
        SceneTransactionCoordinator.SceneKey key =
                new SceneTransactionCoordinator.SceneKey(
                        epoch, fastSequence, generation, screenshotUptimeMillis);
        SceneTransactionCoordinator.Mode mode = scenePresentationMode(motionSettled);
        long visibleDeadline = Math.max(
                screenshotUptimeMillis,
                requestedAtUptimeMillis + ATOMIC_SCENE_VISIBLE_DEADLINE_MS);
        long joinDeadline = Math.max(
                screenshotUptimeMillis,
                visibleDeadline - ATOMIC_SCENE_JOIN_GUARD_MS);
        // A queued image owns its resources, not the visible transaction. Admission happens
        // when its fast result completes, so sparse capture cannot repeatedly cancel ONNX work.
        return new SceneContext(key, mode, qualityExpected, joinDeadline, visibleDeadline,
                continuousMotionInference, spatialFrame);
    }

    private SceneTransactionCoordinator.Transition<Detection> completeFastScene(
            InferenceFrame candidate, long inferenceGeneration, List<Detection> detections) {
        SceneContext scene = candidate.scene;
        synchronized (sceneLifecycleLock) {
            if (scene.cancelled.get() || !isCurrentCapture(candidate.epoch)
                    || !isCurrentVisualDocument(candidate.visualDocumentEpoch, candidate.scrollSurfaceKey)
                    || candidate.captureWindowId >= 0
                    && candidate.captureWindowId != activeApplicationWindowId.get()
                    || !candidate.continuousMotionInference
                    && inferenceGeneration != motionGeneration.get()) {
                // Keep structural invalidation separate from ordinary pending-frame replacement.
                scene.cancel("completed-fast-scope-changed");
                return null;
            }
            SceneTransactionCoordinator.SceneKey previousKey = sceneCoordinator.currentKey();
            SceneTransactionCoordinator.Transition<Detection> transition =
                    sceneCoordinator.submitCompletedFast(scene.key, scene.mode,
                            scene.joinDeadlineUptimeMillis, detections);
            if (transition.committed()) {
                SceneContext previous = currentScene.getAndSet(scene);
                if (previous != null && previous != scene) previous.cancel("newer-fast-completed");
                scene.deliver(transition.commit());
                CensorLabLog.i(TAG, "SCENE_BEGIN id=" + scene.key
                        + " mode=" + scene.mode.name()
                        + " qualityObserved=" + scene.qualityExpected
                        + " qualityJoinExpected=false"
                        + " joinDeadlineUptimeMs=" + scene.joinDeadlineUptimeMillis
                        + " visibleDeadlineUptimeMs=" + scene.visibleDeadlineUptimeMillis
                        + " superseded=" + (previousKey == null ? "none" : previousKey.toString()));
            }
            return transition;
        }
    }

    static SceneTransactionCoordinator.Mode scenePresentationMode(boolean motionSettled) {
        return motionSettled
                ? SceneTransactionCoordinator.Mode.SETTLED_FAST_ONLY
                : SceneTransactionCoordinator.Mode.ACTIVE_FAST;
    }

    static boolean shouldScheduleQualityNow(boolean fastDemand, boolean fastDraining) {
        return !fastDemand && !fastDraining;
    }

    static boolean qualityCircuitAllows(long nowUptime, long openUntilUptime) {
        return nowUptime >= openUntilUptime;
    }

    static boolean usesSplitHardwareQuality(String fastProvider, String qualityProvider) {
        return "CPU".equals(fastProvider) && "NNAPI".equals(qualityProvider);
    }

    private boolean concurrentQualityConfigured() {
        DetectionEngine fast = fastDetector;
        DetectionEngine quality = detector;
        return fast != null && quality != null && fast != quality
                && usesSplitHardwareQuality(
                fast.getActiveProvider(), quality.getActiveProvider());
    }

    private boolean concurrentQualityAllowed(long nowUptimeMillis) {
        return concurrentQualityConfigured()
                && qualityCircuitAllows(
                nowUptimeMillis, qualityCircuitOpenUntilUptime.get())
                && qualityConcurrencyGovernor.allows(nowUptimeMillis);
    }

    /** Records every completed fast native run, including scenes superseded before publication. */
    private void observeFastConcurrency(long runtimeMs, boolean qualityOverlapped) {
        QualityConcurrencyGovernor.Decision decision =
                qualityConcurrencyGovernor.recordFast(
                        runtimeMs, qualityOverlapped, SystemClock.uptimeMillis());
        if (decision != QualityConcurrencyGovernor.Decision.PAUSE_CONCURRENT_QUALITY) return;
        CensorLabLog.i(TAG, "QUALITY_CONCURRENCY action=pause"
                + " fastRuntimeMs=" + runtimeMs
                + " idleRuntimeEmaMs="
                + Math.round(qualityConcurrencyGovernor.idleRuntimeEmaMs())
                + " pauseUntilUptimeMs="
                + qualityConcurrencyGovernor.pausedUntilUptimeMillis());
        long reservationStarted = SystemClock.uptimeMillis();
        qualityReservationUntilUptime.accumulateAndGet(
                reservationStarted + qualityReservationWindowMs(
                        lastSuccessfulQualityDurationMs), Math::max);
        preemptQualityInference("concurrency-governor");
    }

    private void invalidateScene(SceneContext scene, String reason) {
        if (scene == null) return;
        synchronized (sceneLifecycleLock) {
            sceneCoordinator.invalidate(scene.key);
            scene.cancel(reason);
        }
        CensorLabLog.i(TAG, "SCENE_INVALIDATE id=" + scene.key + " reason=" + reason);
    }

    private void invalidateCurrentScene(String reason) {
        SceneTransactionCoordinator.SceneKey invalidated;
        synchronized (sceneLifecycleLock) {
            invalidated = sceneCoordinator.invalidateCurrent();
            SceneContext scene = currentScene.get();
            if (scene != null) scene.cancel(reason);
        }
        if (invalidated != null) {
            CensorLabLog.i(TAG, "SCENE_INVALIDATE id=" + invalidated + " reason=" + reason);
        }
    }

    private void invalidateNonReprojectableSceneForMotion() {
        // Continuous fast scenes already reproject at publication. Motion is not a structural
        // fence.
        SceneTransactionCoordinator.SceneKey invalidated;
        synchronized (sceneLifecycleLock) {
            SceneContext scene = currentScene.get();
            invalidated = sceneCoordinator.invalidateForMotion(
                    scene != null && scene.continuousMotionInference);
            if (invalidated != null && scene != null) scene.cancel("motion");
        }
        if (invalidated != null) {
            CensorLabLog.i(TAG, "SCENE_INVALIDATE id=" + invalidated + " reason=motion");
        }
    }

    private void enqueueQualityInference(QualityInferenceFrame candidate) {
        if (!running || qualityInferenceWorker == null || qualityInferenceWorker.isShutdown()) {
            CensorLabLog.i(TAG, "QUALITY_BACKFILL_DROP reason=worker-unavailable"
                    + " running=" + running
                    + " workerPresent=" + (qualityInferenceWorker != null)
                    + " workerShutdown=" + (qualityInferenceWorker != null
                            && qualityInferenceWorker.isShutdown())
                    + " sourceFastSequence=" + candidate.fastSubmissionSequence);
            candidate.recycle();
            return;
        }
        QualityBackfillCoordinator.BackfillStamp stamp = backfillStamp(candidate);
        if (stamp == null) {
            CensorLabLog.i(TAG, "QUALITY_BACKFILL_DROP reason=invalid-stamp"
                    + " documentEpoch=" + candidate.visualDocumentEpoch
                    + " surfaceEmpty=" + (candidate.scrollSurfaceKey == null
                            || candidate.scrollSurfaceKey.isEmpty())
                    + " surfaceToken=" + candidate.surfaceTelemetryToken
                    + " geometry=" + candidate.sourceWidth + 'x' + candidate.sourceHeight
                    + " viewport=" + candidate.viewportWidth + 'x' + candidate.viewportHeight
                    + " sourceFastSequence=" + candidate.fastSubmissionSequence);
            candidate.recycle();
            staleQualityInferenceFrames.incrementAndGet();
            return;
        }
        QualityBackfillCoordinator.BackfillFrame<QualityInferenceFrame> source =
                new QualityBackfillCoordinator.BackfillFrame<>(
                        candidate, stamp, QualityInferenceFrame::recycle);
        QualityBackfillCoordinator.OfferResult offered = qualityBackfillRunner.offer(
                source, SystemClock.uptimeMillis());
        if (!offered.accepted()) {
            droppedQualityInferenceFrames.incrementAndGet();
        } else if (offered.status()
                == QualityBackfillCoordinator.OfferStatus.ACCEPTED_REPLACED_OLDER) {
            droppedQualityInferenceFrames.incrementAndGet();
        }
        CensorLabLog.i(TAG, "QUALITY_BACKFILL_OFFER status=" + offered.status().name()
                + " captureAgeMs=" + Math.max(0L, SystemClock.uptimeMillis()
                        - candidate.capturedAtUptimeMillis)
                + " sourceGeneration=" + candidate.motionGeneration
                + " sourceFastSequence=" + candidate.fastSubmissionSequence
                + " pending=" + qualityBackfillRunner.pendingCount());
        long offeredAt = SystemClock.uptimeMillis();
        if ((offered.accepted() || qualityBackfillRunner.pendingCount() > 0)
                && qualityBackfillRunner.circuitAllows(offeredAt)) {
            long reservationStarted = offeredAt;
            qualityReservationUntilUptime.accumulateAndGet(
                    reservationStarted
                            + qualityReservationWindowMs(lastSuccessfulQualityDurationMs),
                    Math::max);
        }
        // Fast demand is registered before this same-capture quality source is offered. Leave the
        // latest source parked until drainInferenceQueue releases the fast lease; otherwise the
        // quality worker would immediately reject and discard every useful settled observation.
        if (concurrentQualityAllowed(SystemClock.uptimeMillis())
                || shouldScheduleQualityNow(
                inferenceGate.hasFastDemand(), inferenceDraining.get())) {
            scheduleQualityInference();
        }
    }

    /** Quality is opportunistic shadow evidence; it must never get ahead of real-time work. */
    private void scheduleQualityInference() {
        scheduleQualityInference(0L);
    }

    private void scheduleQualityInference(long delayMillis) {
        if (!running || qualityInferenceWorker == null || qualityInferenceWorker.isShutdown()
                || qualityBackfillRunner.pendingCount() == 0) return;
        if (delayMillis > 0L) {
            synchronized (qualityScheduleLock) {
                ScheduledFuture<?> existing = qualityRetrySchedule;
                if (existing != null && !existing.isDone()) return;
                try {
                    qualityRetrySchedule = qualityInferenceWorker.schedule(() -> {
                        synchronized (qualityScheduleLock) {
                            qualityRetrySchedule = null;
                        }
                        scheduleQualityInference();
                    }, delayMillis, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException rejected) {
                    qualityRetrySchedule = null;
                }
            }
            return;
        }
        if (qualityInferenceDraining.compareAndSet(false, true)) {
            try {
                qualityInferenceWorker.execute(this::drainQualityInferenceQueue);
            } catch (RejectedExecutionException rejected) {
                qualityInferenceDraining.set(false);
                qualityBackfillRunner.clear();
                qualityReservationUntilUptime.set(0L);
                wakeCaptureAt(SystemClock.uptimeMillis());
            }
        }
    }

    /** Runs one quality source after fast work; both current and old sources are cache-only. */
    private void drainQualityInferenceQueue() {
        QualityBackfillRunner.RunResult lastResult = null;
        try {
            while (running) {
                long now = SystemClock.uptimeMillis();
                QualityBackfillCoordinator.BackfillContext current =
                        currentBackfillContext();
                QualityBackfillRunner.RunResult result = qualityBackfillRunner.runOne(
                        current, now, this::tryAcquireQualityBackfillPermit,
                        this::runQualityBackfillSource);
                lastResult = result;
                CensorLabLog.i(TAG, "QUALITY_BACKFILL_DISPATCH status="
                        + result.status().name()
                        + " durationMs=" + result.durationMillis()
                        + " retryAfterMs=" + result.retryAfterMillis()
                        + " pending=" + qualityBackfillRunner.pendingCount()
                        + " activeLane=" + inferenceGate.activeLane().name().toLowerCase()
                        + " fastDemand=" + inferenceGate.hasFastDemand());
                if (result.status() == QualityBackfillRunner.RunStatus.FAILED
                        && result.failure() != null) {
                    DiagnosticsRepository.fail(DIAGNOSTICS_MODE, result.failure());
                    Log.w(TAG, "Could not process quality backfill source", result.failure());
                }
                if (!result.ran()) return;
                if (qualityBackfillRunner.pendingCount() == 0) return;
            }
        } finally {
            qualityInferenceDraining.set(false);
            long now = SystemClock.uptimeMillis();
            if (lastResult == null || !lastResult.deferred()
                    || lastResult.status()
                    == QualityBackfillRunner.RunStatus.DEFERRED_CIRCUIT) {
                qualityReservationUntilUptime.set(0L);
                wakeCaptureAt(now);
            }
            if (running && qualityBackfillRunner.pendingCount() > 0) {
                long policyDelay = lastResult != null && lastResult.deferred()
                        ? lastResult.retryAfterMillis() : 0L;
                if (policyDelay > 0L) {
                    // Duty/cadence eligibility cannot improve on a 48 ms poll. Sleep until the
                    // runner's exact boundary while newer captures continue replacing the one
                    // pending source.
                    scheduleQualityInference(policyDelay);
                } else if (now < qualityReservationUntilUptime.get()) {
                    // Transient admission can change when the current fast drain completes.
                    scheduleQualityInference(Math.min(QUALITY_RETRY_MS,
                            Math.max(1L, qualityReservationUntilUptime.get() - now)));
                }
            }
        }
    }

    private void cancelQualityRetrySchedule() {
        synchronized (qualityScheduleLock) {
            ScheduledFuture<?> scheduled = qualityRetrySchedule;
            qualityRetrySchedule = null;
            if (scheduled != null) scheduled.cancel(false);
        }
    }

    private void releaseOptionalInferenceResource() {
        optionalInferenceLock.unlock();
    }

    private AutoCloseable tryAcquireQualityBackfillPermit() {
        if (!optionalInferenceLock.tryLock()) return null;
        AutoCloseable permit;
        try {
            permit = tryAcquireQualityBackfillGatePermit();
        } catch (RuntimeException failure) {
            releaseOptionalInferenceResource();
            throw failure;
        }
        if (permit == null) {
            releaseOptionalInferenceResource();
            return null;
        }
        return () -> {
            try { permit.close(); }
            finally { releaseOptionalInferenceResource(); }
        };
    }

    private AutoCloseable tryAcquireQualityBackfillGatePermit() {
        long now = SystemClock.uptimeMillis();
        DetectionEngine fast = fastDetector;
        DetectionEngine quality = detector;
        boolean splitHardware = fast != null && quality != null
                && usesSplitHardwareQuality(
                fast.getActiveProvider(), quality.getActiveProvider());
        if (textRefreshRunning.get()
                || !qualityCircuitAllows(now, qualityCircuitOpenUntilUptime.get())
                || !splitHardware) {
            return null;
        }
        if (qualityConcurrencyGovernor.allows(now)) return CONCURRENT_QUALITY_PERMIT;
        if (inferenceGate.hasFastDemand() || inferenceDraining.get()) return null;
        FastPriorityInferenceGate.QualityAdmission admission = tryAcquireQualityGate();
        if (!admission.admitted()) {
            logQualityGateSkip(admission.rejection(), fastSubmissionSequence.get());
            return null;
        }
        return admission.lease();
    }

    private QualityBackfillCoordinator.BackfillStamp backfillStamp(
            QualityInferenceFrame candidate) {
        if (candidate == null) return null;
        try {
            if (candidate.scrollSurfaceKey.isEmpty()) {
                return QualityBackfillCoordinator.BackfillStamp.currentOnly(
                        candidate.epoch, candidate.visualDocumentEpoch, candidate.captureWindowId,
                        backfillTransformToken(candidate.sourceWidth, candidate.sourceHeight,
                                candidate.viewportWidth, candidate.viewportHeight),
                        candidate.surfaceTelemetryToken, candidate.phaseCertain,
                        candidate.capturedAtUptimeMillis, candidate.motionGeneration,
                        candidate.scrollX, candidate.scrollY, candidate.fastSubmissionSequence);
            }
            return new QualityBackfillCoordinator.BackfillStamp(
                    candidate.epoch,
                    candidate.visualDocumentEpoch,
                    candidate.scrollSurfaceKey,
                    backfillTransformToken(
                            candidate.sourceWidth, candidate.sourceHeight,
                            candidate.viewportWidth, candidate.viewportHeight),
                    candidate.surfaceTelemetryToken,
                    candidate.phaseCertain,
                    candidate.capturedAtUptimeMillis,
                    candidate.motionGeneration,
                    candidate.fastSubmissionSequence);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private QualityBackfillCoordinator.BackfillContext currentBackfillContext() {
        Rect viewport = screenBounds();
        synchronized (scrollStateLock) {
          synchronized (worldCacheLock) {
            String surface = activeScrollSurfaceKey;
            try {
                long transform = backfillTransformToken(latestCaptureWidth, latestCaptureHeight,
                        viewport.width(), viewport.height());
                if (surface == null || surface.isEmpty()) {
                    return QualityBackfillCoordinator.BackfillContext.currentOnly(
                            captureEpoch.token(), visualDocumentEpoch.get(),
                            activeApplicationWindowId.get(), transform, activeScrollTelemetryToken,
                            true, motionGeneration.get(), cumulativeScrollX.get(),
                            cumulativeScrollY.get(), fastSubmissionSequence.get());
                }
                return new QualityBackfillCoordinator.BackfillContext(
                    captureEpoch.token(),
                    visualDocumentEpoch.get(),
                    surface,
                    backfillTransformToken(
                            latestCaptureWidth, latestCaptureHeight,
                            viewport.width(), viewport.height()),
                    activeScrollTelemetryToken,
                    true);
            } catch (IllegalArgumentException invalid) {
                return null;
            }
          }
        }
    }

    private boolean isOldQualityFrame(QualityInferenceFrame candidate) {
        return candidate == null || candidate.scene == null
                || candidate.fastSubmissionSequence != fastSubmissionSequence.get()
                || candidate.motionGeneration != motionGeneration.get();
    }

    private void runQualityBackfillSource(
            QualityInferenceFrame candidate,
            QualityBackfillCoordinator.BackfillStamp sourceStamp) throws Exception {
        qualityInferenceExecuting.set(true);
        try {
            runStreamingQualityInference(candidate, sourceStamp);
        } finally {
            qualityInferenceExecuting.set(false);
        }
    }

    private boolean qualityBackfillCancellationRequested(
            QualityInferenceFrame candidate,
            QualityBackfillCoordinator.BackfillStamp sourceStamp) {
        QualityBackfillCoordinator.BackfillContext current = currentBackfillContext();
        boolean concurrentQuality = concurrentQualityAllowed(SystemClock.uptimeMillis());
        return candidate == null || sourceStamp == null
                || !isCurrentCapture(candidate.epoch)
                || sourceStamp.sourceMode() == QualityBackfillCoordinator.SourceMode.CURRENT_ONLY
                && (SystemClock.uptimeMillis() < candidate.capturedAtUptimeMillis
                    || SystemClock.uptimeMillis() - candidate.capturedAtUptimeMillis
                        > QualityBackfillCoordinator.DEFAULT_MAX_AGE_MS)
                || !qualityBackfillRunner.circuitAllows(SystemClock.uptimeMillis())
                || !concurrentQuality && inferenceGate.hasFastDemand()
                || !concurrentQuality && inferenceDraining.get()
                || !qualityCircuitAllows(
                        SystemClock.uptimeMillis(), qualityCircuitOpenUntilUptime.get())
                || current == null
                || !current.accepts(sourceStamp);
    }

    /**
     * Runs one quality source while the caller-held gate lease is active. Old sources deliberately
     * skip motion/fast-sequence/scene fences and only feed the world-space cache accumulator.
     */
    private void runStreamingQualityInference(
            QualityInferenceFrame candidate,
            QualityBackfillCoordinator.BackfillStamp sourceStamp) throws Exception {
        DetectionEngine quality = detector;
        SceneContext scene = candidate == null ? null : candidate.scene;
        long currentGeneration = motionGeneration.get();
        boolean oldFrame = isOldQualityFrame(candidate);
        if (quality == null || candidate == null || sourceStamp == null
                || !isCurrentCapture(candidate.epoch)
                || (!oldFrame && scene == null)) return;
        if (textRefreshRunning.get()) {
            logStreamingQualityDrop("text-active-before", candidate, currentGeneration);
            return;
        }
        if (!oldFrame && candidate.fastSubmissionSequence != fastSubmissionSequence.get()) {
            logStreamingQualityDrop("fast-sequence-before", candidate, currentGeneration);
            return;
        }
        if (!oldFrame && !concurrentQualityAllowed(SystemClock.uptimeMillis())
                && (candidate.motionGeneration != currentGeneration
                || (lastMotionUptime > 0L
                && SystemClock.uptimeMillis() - lastMotionUptime < QUALITY_MOTION_SETTLE_MS))) {
            logStreamingQualityDrop("motion-generation-before", candidate, currentGeneration);
            return;
        }
        if (oldFrame && qualityBackfillCancellationRequested(candidate, sourceStamp)) {
            logStreamingQualityDrop("backfill-fence-before", candidate, currentGeneration);
            return;
        }
        List<Detection> detected;
        long bitmapPrepareMs;
        QualityTilePlanner.Tile qualityTile = QualityTilePlanner.selectContinuous(
                candidate.sourceWidth, candidate.sourceHeight,
                qualityTilePassSequence.getAndIncrement());
        long prepareStarted = SystemClock.elapsedRealtimeNanos();
        Log.i(TAG, "QUALITY_PREPARE_BEGIN sourceFastSequence="
                + candidate.fastSubmissionSequence
                + " generation=" + candidate.motionGeneration
                + " oldFrame=" + oldFrame
                + " uptimeNanos=" + prepareStarted);
        InferenceBitmapPreparer.Prepared prepared = InferenceBitmapPreparer.prepareRegion(
                candidate.sourceFrame, qualityTile, candidate.inferenceResolution);
        long prepareEnded = SystemClock.elapsedRealtimeNanos();
        bitmapPrepareMs = Math.max(0L, Math.round(
                (prepareEnded - prepareStarted) / 1_000_000d));
        Log.i(TAG, "QUALITY_PREPARE_END sourceFastSequence="
                + candidate.fastSubmissionSequence
                + " generation=" + candidate.motionGeneration
                + " oldFrame=" + oldFrame
                + " durationMs=" + bitmapPrepareMs
                + " uptimeNanos=" + prepareEnded);
        if (prepared == null) return;
        try {
            boolean cancelled = qualityBackfillCancellationRequested(candidate, sourceStamp);
            if (cancelled) {
                logStreamingQualityDrop(
                        oldFrame ? "backfill-fence-after-prepare"
                                : "fast-sequence-after-prepare",
                        candidate, motionGeneration.get());
                return;
            }
            detected = quality.detect(
                    prepared.bitmap, qualityTile.width(), qualityTile.height(),
                    () -> qualityBackfillCancellationRequested(candidate, sourceStamp));
            detected = QualityTilePlanner.toFullFrame(
                    detected, qualityTile, candidate.sourceWidth, candidate.sourceHeight);
        } finally {
            if (!prepared.bitmap.isRecycled()) prepared.bitmap.recycle();
        }
        if (quality.wasLastRunCancelled()) {
            logQualityCancellation(oldFrame ? "backfill-preempted" : "stream-fast-preempted", quality);
            return;
        }
        int rawVisualCount = detected.size();
        if (!isCurrentCapture(candidate.epoch)) return;
        // Quality never re-opens or mutates a visible scene. It only offers world-space evidence
        // to the two-hit backfill accumulator, where ordinary motion is deliberately not a fence.
        // DetectionPostProcessor already applied the user's configured category and confidence
        // policy. The fast-only gate is intentionally stricter because a single real-time hit can
        // flash immediately; quality is cache-only and requires repeated evidence before a later
        // fast tick may render it, so applying FastVisualGate here silently destroys its recall.
        List<Detection> coverage = markQualityCoverage(detected);
        candidate.renderReference = resolveRenderReference(candidate.epoch,
                candidate.visualDocumentEpoch, candidate.captureWindowId,
                candidate.viewportWidth, candidate.viewportHeight,
                candidate.capturedAtUptimeMillis, candidate.scrollX, candidate.scrollY,
                candidate.phaseCertain);
        long readyAt = SystemClock.uptimeMillis();
        // Re-evaluate after inference: motion may have made a once-current source historical.
        // Historical results are valid world backfill; still-current results are aligned and
        // admitted only by a later fast publication.
        boolean currentOnly = sourceStamp.sourceMode()
                == QualityBackfillCoordinator.SourceMode.CURRENT_ONLY;
        boolean visibleLateQuality = currentOnly
                ? lateQualitySceneIsCurrent(candidate) : !isOldQualityFrame(candidate);
        QualityBackfillCoordinator.ObservationResult backfill =
                currentOnly ? null : observeQualityBackfill(
                        candidate, sourceStamp, coverage, readyAt, visibleLateQuality, qualityTile);
        if (currentOnly && visibleLateQuality
                && !qualityBackfillCancellationRequested(candidate, sourceStamp)) {
            stageLateQualityPresentation(candidate, currentOnlyRegions(candidate, coverage), readyAt);
        }
        lastQualityInferenceUptime = readyAt;
        lastSuccessfulQualityDurationMs = quality.getLastInferenceMs() + bitmapPrepareMs;
        String sceneId = scene == null
                ? "backfill:" + candidate.fastSubmissionSequence : scene.key.toString();
        CensorLabLog.i(TAG, "QUALITY_READY id=" + sceneId
                + " scrollId=" + scrollTraceId
                + " captureAgeMs=" + (readyAt - candidate.capturedAtUptimeMillis)
                + " bitmapPrepareMs=" + bitmapPrepareMs
                + " inferenceMs=" + quality.getLastInferenceMs()
                + " preprocessMs=" + quality.getLastPreprocessMs()
                + " runtimeMs=" + quality.getLastRuntimeMs()
                + " postprocessMs=" + quality.getLastPostprocessMs()
                + " afterMotionMs=" + (lastMotionUptime <= 0L
                        ? 0L : readyAt - lastMotionUptime)
                + " rawVisual=" + rawVisualCount
                + " acceptedVisual=" + coverage.size()
                + " tile=" + qualityTile.index() + '/' + qualityTile.count()
                + " tileBounds=" + qualityTile.left() + ',' + qualityTile.top() + ','
                + qualityTile.right() + ',' + qualityTile.bottom()
                + " renderAuthority=none"
                + " backfillStatus=" + (currentOnly ? "CURRENT_ONLY" : backfill.status().name())
                + " backfillMatched=" + (backfill == null ? 0 : backfill.matched())
                + " backfillInserted=" + (backfill == null ? 0 : backfill.inserted())
                + " backfillPromoted=" + (backfill == null ? 0 : backfill.newlyPromoted())
                + " backfillRefined=" + (backfill == null ? 0 : backfill.refined())
                + " oldFrame=" + oldFrame
                + " sourceGeneration=" + candidate.motionGeneration
                + " sourceFastSequence=" + candidate.fastSubmissionSequence
                + " currentFastSequence=" + fastSubmissionSequence.get()
                + " dropped=" + droppedQualityInferenceFrames.get()
                + " staleDropped=" + staleQualityInferenceFrames.get()
                + " preemptions=" + qualityInferencePreemptions.get()
                + " cancelledRuns=" + qualityInferenceCancelledRuns.get());
    }

    /** Pure coordinate conversion only: CURRENT_ONLY never enters cache observation APIs. */
    private static List<QualityBackfillCoordinator.BackfillRegion> currentOnlyRegions(
            QualityInferenceFrame candidate, List<Detection> detections) {
        List<QualityBackfillCoordinator.BackfillRegion> result = new ArrayList<>();
        for (Detection detection : detections) {
            if (detection == null) continue;
            BBox world = ContentSpaceRegionCache.screenToWorld(detection.getBox(),
                    candidate.scrollX, candidate.scrollY, candidate.sourceWidth,
                    candidate.sourceHeight, candidate.viewportWidth, candidate.viewportHeight);
            if (world != null && world.getArea() > 0L) {
                result.add(new QualityBackfillCoordinator.BackfillRegion(detection.getClassName(),
                        detection.getCategory(), detection.getConfidence(), world,
                        detection.isNsfw(), detection.isExposed(), detection.getAnchorKey()));
            }
        }
        return result;
    }

    private QualityBackfillCoordinator.ObservationResult observeQualityBackfill(
            QualityInferenceFrame candidate,
            QualityBackfillCoordinator.BackfillStamp stamp,
            List<Detection> detections,
            long readyAtUptime,
            boolean visibleLateQuality,
            QualityTilePlanner.Tile inspectedTile) {
        if (candidate == null || stamp == null) {
            return qualityBackfillCoordinator.observe(
                    (QualityBackfillCoordinator.BackfillStamp) null,
                    null, readyAtUptime, Collections.emptyList());
        }

        QualityBackfillCoordinator.BackfillContext current = currentBackfillContext();
        if (current == null) {
            return qualityBackfillCoordinator.observe(
                    stamp, null, readyAtUptime, Collections.emptyList());
        }

        List<QualityBackfillCoordinator.BackfillRegion> regions = new ArrayList<>();
        if (detections != null) {
            for (Detection detection : detections) {
                if (detection == null) continue;
                BBox worldBox = ContentSpaceRegionCache.screenToWorld(
                        detection.getBox(), candidate.scrollX, candidate.scrollY,
                        candidate.sourceWidth, candidate.sourceHeight,
                        candidate.viewportWidth, candidate.viewportHeight);
                if (worldBox == null || worldBox.getArea() <= 0L) continue;
                regions.add(new QualityBackfillCoordinator.BackfillRegion(
                        detection.getClassName(), detection.getCategory(),
                        detection.getConfidence(), worldBox,
                        detection.isNsfw(), detection.isExposed(),
                        detection.getAnchorKey()));
            }
        }
        QualityBackfillCoordinator.ObservationResult result =
                qualityBackfillCoordinator.observe(stamp, current, readyAtUptime, regions);
        boolean acceptedCurrentPresentation = visibleLateQuality
                && !regions.isEmpty()
                && qualityObservationAccepted(result.status());
        if (!qualityObservationAccepted(result.status())) return result;

        ContentSpaceRegionCache.Update update;
        List<QualityBackfillCoordinator.BackfillRegion> presentationRegions =
                Collections.emptyList();
        boolean commitVisibleLateQuality;
        boolean cacheGenerationAccepted;
        Rect commitViewport = screenBounds();
        synchronized (scrollStateLock) {
            synchronized (worldCacheLock) {
                if (!isCurrentVisualDocument(
                        candidate.visualDocumentEpoch, candidate.scrollSurfaceKey)
                        || candidate.surfaceTelemetryToken != activeScrollTelemetryToken) {
                    return result;
                }
                commitVisibleLateQuality = visibleLateQuality
                        && acceptedCurrentPresentation
                        && lateQualitySceneIsCurrentLocked(candidate, commitViewport);
                if (commitVisibleLateQuality) {
                    presentationRegions = Collections.unmodifiableList(new ArrayList<>(regions));
                }
                cacheGenerationAccepted = isQualityCacheGenerationCurrent(
                        candidate.motionGeneration, motionGeneration.get());
                List<ContentSpaceRegionCache.Observation> observations = new ArrayList<>();
                // Only the coordinator's two-capture ready set may enter the long-lived cache.
                // A one-hit current result can cover compatible fast publications below, but must
                // never inherit qualityConfirmed's long TTL and contradiction grace.
                if (cacheGenerationAccepted) {
                    // Even a hit awaiting independent confirmation is evidence against a
                    // miss of an existing region. It cannot create/refresh durable coverage.
                    for (QualityBackfillCoordinator.BackfillRegion region : regions) {
                        BBox screenBox = ContentSpaceRegionCache.worldToScreen(
                                region.worldBox(), candidate.scrollX, candidate.scrollY,
                                candidate.sourceWidth, candidate.sourceHeight,
                                candidate.viewportWidth, candidate.viewportHeight);
                        observations.add(new ContentSpaceRegionCache.Observation(
                                -1, region.className(), region.category(), region.confidence(),
                                screenBox, region.nsfw(), region.exposed(), 0, 0, false,
                                region.anchorKey(), !commitVisibleLateQuality, candidate.renderReference));
                    }
                    for (QualityBackfillCoordinator.BackfillRegion region : result.readyRegions()) {
                        BBox screenBox = ContentSpaceRegionCache.worldToScreen(
                                region.worldBox(), candidate.scrollX, candidate.scrollY,
                                candidate.sourceWidth, candidate.sourceHeight,
                                candidate.viewportWidth, candidate.viewportHeight);
                        observations.add(new ContentSpaceRegionCache.Observation(
                                -1, region.className(), region.category(), region.confidence(),
                                screenBox, region.nsfw(), region.exposed(), 0, 0, true,
                                region.anchorKey(), !commitVisibleLateQuality, candidate.renderReference));
                    }
                }
                if (spatialCacheExperiment && candidate.scene != null) {
                    spatialRegionCache.observeSource(candidate.scene.spatialFrame, readyAtUptime,
                            false, observations);
                }
                update = cacheGenerationAccepted ? contentSpaceRegionCache.observeCommittedScene(
                        candidate.visualDocumentEpoch,
                        candidate.scrollSurfaceKey,
                        readyAtUptime,
                        candidate.scrollX,
                        candidate.scrollY,
                        candidate.sourceWidth,
                        candidate.sourceHeight,
                        candidate.viewportWidth,
                        candidate.viewportHeight,
                        ContentSpaceRegionCache.Evidence.quality(candidate.capturedAtUptimeMillis,
                                new BBox(inspectedTile.left(), inspectedTile.top(),
                                        inspectedTile.width(), inspectedTile.height())),
                        observations) : ContentSpaceRegionCache.Update.EMPTY;
                if (update.viewportReset) qualityBackfillCoordinator.clear();
                if (update.viewportReset) presentationRegions = Collections.emptyList();
            }
        }
        CensorLabLog.i(TAG, "QUALITY_BACKFILL_COMMIT sourceFastSequence="
                + candidate.fastSubmissionSequence
                + " promoted=" + result.newlyPromoted()
                + " refined=" + result.refined()
                + " cacheInserted=" + update.inserted
                + " cacheUpdated=" + update.updated
                + " cacheEvicted=" + update.evicted
                + " latePresentationCandidates=" + presentationRegions.size()
                + " cacheGenerationAccepted=" + cacheGenerationAccepted
                + " sourceGeneration=" + candidate.motionGeneration
                + " currentGeneration=" + motionGeneration.get());
        if (commitVisibleLateQuality && !presentationRegions.isEmpty()) {
            stageLateQualityPresentation(candidate, presentationRegions, readyAtUptime);
        }
        return result;
    }

    static boolean qualityObservationAccepted(
            QualityBackfillCoordinator.ObservationStatus status) {
        return status == QualityBackfillCoordinator.ObservationStatus.ACCEPTED
                || status == QualityBackfillCoordinator.ObservationStatus.PROMOTED
                || status == QualityBackfillCoordinator.ObservationStatus.REFINED;
    }

    private boolean lateQualitySceneIsCurrent(QualityInferenceFrame candidate) {
        Rect viewport = screenBounds();
        synchronized (scrollStateLock) {
            synchronized (worldCacheLock) {
                return lateQualitySceneIsCurrentLocked(candidate, viewport);
            }
        }
    }

    /** Caller holds scrollStateLock then worldCacheLock, making the visibility decision atomic. */
    private boolean lateQualitySceneIsCurrentLocked(
            QualityInferenceFrame candidate,
            Rect viewport) {
        long currentFastSequence = fastSubmissionSequence.get();
        return candidate != null && candidate.scene != null
                && running && recognitionActive && overlay != null
                && isCurrentCapture(candidate.epoch)
                && currentFastSequence >= candidate.fastSubmissionSequence
                && (candidate.scrollSurfaceKey.isEmpty()
                    || currentFastSequence - candidate.fastSubmissionSequence <= 1L)
                && candidate.captureWindowId >= 0
                && candidate.captureWindowId == activeApplicationWindowId.get()
                && SystemClock.uptimeMillis() >= candidate.capturedAtUptimeMillis
                && SystemClock.uptimeMillis() - candidate.capturedAtUptimeMillis
                        <= QualityBackfillCoordinator.DEFAULT_MAX_AGE_MS
                && candidate.motionGeneration == motionGeneration.get()
                && candidate.visualDocumentEpoch == visualDocumentEpoch.get()
                && candidate.surfaceTelemetryToken == activeScrollTelemetryToken
                && candidate.scrollSurfaceKey.equals(activeScrollSurfaceKey)
                && latestCaptureWidth == candidate.sourceWidth
                && latestCaptureHeight == candidate.sourceHeight
                && viewport != null
                && viewport.width() == candidate.viewportWidth
                && viewport.height() == candidate.viewportHeight
                && cumulativeScrollX.get() == candidate.scrollX
                && cumulativeScrollY.get() == candidate.scrollY;
    }

    private void stageLateQualityPresentation(
            QualityInferenceFrame candidate,
            List<QualityBackfillCoordinator.BackfillRegion> worldRegions,
            long readyAtUptime) {
        if (candidate == null || worldRegions == null) return;
        if (!lateQualitySceneIsCurrent(candidate)) {
            CensorLabLog.i(TAG, "QUALITY_LATE_DROP reason=stale-before-stage"
                    + " sourceFastSequence=" + candidate.fastSubmissionSequence
                    + " sourceGeneration=" + candidate.motionGeneration);
            return;
        }
        LateQualityPresentation staged = new LateQualityPresentation(
                candidate, new ArrayList<>(worldRegions), readyAtUptime);
        LateQualityPresentation replaced = pendingLateQualityPresentation.getAndSet(staged);
        CensorLabLog.i(TAG, "QUALITY_LATE_STAGE sourceFastSequence="
                + candidate.fastSubmissionSequence
                + " sourceGeneration=" + candidate.motionGeneration
                + " regions=" + worldRegions.size()
                + " replaced=" + (replaced != null)
                + " captureAgeMs=" + Math.max(0L,
                        readyAtUptime - candidate.capturedAtUptimeMillis));
        scheduleImmediateQualityRefresh();
        if (staged.surfaceKey.isEmpty()) {
            // Expiry is anchored to capture, never renewed by a fast read. Remove even if the
            // capture pipeline stops delivering frames. Pending and displayed ownership differ.
            main.postDelayed(() -> expireCurrentQualityPresentation(staged), Math.max(1L,
                    staged.capturedAtUptime + QualityBackfillCoordinator.DEFAULT_MAX_AGE_MS + 1L
                            - SystemClock.uptimeMillis()));
        }
    }

    private void expireCurrentQualityPresentation(LateQualityPresentation source) {
        synchronized (pendingLateQualityPresentation) {
            pendingLateQualityPresentation.compareAndSet(source, null);
            if (!pendingLateQualityPresentation.clearDisplayed(source)) return;
            DisplayedQualityBasis displayed = displayedQualityBasis;
            Rect viewport = screenBounds();
            LateQualityPresentationGate.Stamp current = new LateQualityPresentationGate.Stamp(
                    fastSubmissionSequence.get(), captureEpoch.token(), visualDocumentEpoch.get(),
                    activeScrollSurfaceKey, activeScrollTelemetryToken, motionGeneration.get(),
                    cumulativeScrollX.get(), cumulativeScrollY.get(), latestCaptureWidth,
                    latestCaptureHeight, viewport.width(), viewport.height(),
                    activeApplicationWindowId.get(), activeScrollSurfaceKey.isEmpty());
            if (running && recognitionActive && overlay != null && displayed != null
                    && (source.fastPresented.get() || source.immediatelyPresented.get())
                    && LateQualityPresentationGate.decide(source.stamp(), displayed.stamp)
                            != LateQualityPresentationGate.Decision.STALE
                    && LateQualityPresentationGate.decide(source.stamp(), current)
                            != LateQualityPresentationGate.Decision.STALE) {
                overlay.updateWorldCache(displayed.baseRegions, source.sourceWidth, source.sourceHeight,
                        source.scrollX, source.scrollY, source.viewportWidth, source.viewportHeight);
            }
        }
    }

    /**
     * Experiment: refine only the currently displayed capture, never inventing scroll alignment.
     */
    private void scheduleImmediateQualityRefresh() {
        if (!BuildConfig.IMMEDIATE_QUALITY_EXPERIMENT
                || !immediateQualityScheduled.compareAndSet(false, true)) return;
        main.post(() -> Choreographer.getInstance().postFrameCallback(frameTime -> {
            immediateQualityScheduled.set(false);
            LateQualityPresentation source = pendingLateQualityPresentation.get();
            DisplayedQualityBasis displayed = displayedQualityBasis;
            if (source == null || displayed == null || !running || !recognitionActive
                    || overlay == null || spatialCacheExperiment
                    || source.immediatelyPresented.get()
                    || !source.renderReference.sameBasis(displayed.renderReference)) return;
            synchronized (scrollStateLock) {
                synchronized (worldCacheLock) {
                    Rect viewport = screenBounds();
                    LateQualityPresentationGate.Stamp current = new LateQualityPresentationGate.Stamp(
                            fastSubmissionSequence.get(), captureEpoch.token(), visualDocumentEpoch.get(),
                            activeScrollSurfaceKey, activeScrollTelemetryToken, motionGeneration.get(),
                            cumulativeScrollX.get(), cumulativeScrollY.get(), latestCaptureWidth,
                            latestCaptureHeight, viewport.width(), viewport.height(),
                            activeApplicationWindowId.get(), activeScrollSurfaceKey.isEmpty());
                    long now = SystemClock.uptimeMillis();
                    if (!ImmediateQualityPresentationGate.allows(source.stamp(), displayed.stamp,
                            current, source.phaseCertain && displayed.phaseCertain,
                            source.capturedAtUptime, now, QualityBackfillCoordinator.DEFAULT_MAX_AGE_MS)) return;
                    List<Detection> additions = QualityPresentationAligner.addSafetyCoverage(
                            uncoveredLateQualityRegions(source.screenDetections(source.scrollX,
                                    source.scrollY, source.sourceWidth, source.sourceHeight,
                                    source.viewportWidth, source.viewportHeight), displayed.tracks),
                            source.sourceWidth, source.sourceHeight);
                    synchronized (pendingLateQualityPresentation) {
                    if (pendingLateQualityPresentation.get() != source
                            || !source.immediatelyPresented.compareAndSet(false, true)) return;
                                                        // Retain the normal handoff: consuming the
                                                        // slot here would make a
                                                        // one-hit
                                                        // addition disappear at the next fast
                                                        // publication before cache
                                                        // confirmation.
                                                        List<Detection> combined = new ArrayList<>(displayed.baseRegions);
                    combined.addAll(additions);
                                                        // This replaces only render-memory regions.
                                                        // Live tracks, text, the
                                                        // source
                                                        // bitmap, detector statistics and durable
                                                        // confirmation state are
                                                        // untouched.
                                                        overlay.updateWorldCache(combined, source.sourceWidth, source.sourceHeight,
                            source.scrollX, source.scrollY, source.viewportWidth, source.viewportHeight);
                    pendingLateQualityPresentation.markDisplayed(source);
                    CensorLabLog.i(TAG,
                                                                "QUALITY_IMMEDIATE_PRESENT"
                                                                    + " sourceFastSequence="
                                                                        + source.fastSubmissionSequence + " regions=" + additions.size()
                            + " readyToPresentMs=" + Math.max(0L, now - source.readyAtUptime)
                            + " captureAgeMs=" + Math.max(0L, now - source.capturedAtUptime));
                    }
                }
            }
        }));
    }

    /**
     * Takes quality only for a later fast publication, without waiting for quality inference.
     * Current-only sources require unchanged phase and are bounded by age rather than tick count.
     */
    private LateQualityPresentation takeLateQualityForFast(
            long consumerFastSequence,
            long consumerEpoch,
            long consumerDocumentEpoch,
            String consumerSurfaceKey,
            long consumerMotionGeneration,
            long consumerCameraX,
            long consumerCameraY,
            int sourceWidth,
            int sourceHeight,
            int viewportWidth,
            int viewportHeight,
            int consumerWindowId) {
        while (true) {
            LateQualityPresentation staged = pendingLateQualityPresentation.get();
            if (staged == null) return null;
            LateQualityPresentation.Match match = staged.matchFast(
                    consumerFastSequence,
                    consumerEpoch,
                    consumerDocumentEpoch,
                    consumerSurfaceKey,
                    activeScrollTelemetryToken,
                    consumerMotionGeneration,
                    consumerCameraX,
                    consumerCameraY,
                    sourceWidth,
                    sourceHeight,
                    viewportWidth,
                    viewportHeight,
                    consumerWindowId);
            if (match == LateQualityPresentation.Match.WAIT_FOR_NEXT_FAST) return null;
            if (match != LateQualityPresentation.Match.MATCH) {
                if (!pendingLateQualityPresentation.compareAndSet(staged, null)) continue;
                CensorLabLog.i(TAG, "QUALITY_LATE_DROP reason="
                        + "fast-fence"
                        + " sourceFastSequence=" + staged.fastSubmissionSequence
                        + " consumerFastSequence=" + consumerFastSequence
                        + " sourceGeneration=" + staged.motionGeneration
                        + " consumerGeneration=" + consumerMotionGeneration);
                return null;
            }
            if (!pendingLateQualityPresentation.acquire(staged, staged.surfaceKey.isEmpty())) continue;
            return staged;
        }
    }

    private void clearLateQualityPresentation(String reason) {
        displayedQualityBasis = null;
        pendingLateQualityPresentation.markDisplayed(null);
        LateQualityPresentation removed = pendingLateQualityPresentation.getAndSet(null);
        if (removed != null) {
            CensorLabLog.i(TAG, "QUALITY_LATE_DROP reason=" + reason
                    + " sourceFastSequence=" + removed.fastSubmissionSequence
                    + " sourceGeneration=" + removed.motionGeneration);
        }
    }

    static long backfillTransformToken(
            int sourceWidth, int sourceHeight, int viewportWidth, int viewportHeight) {
        long value = 0xcbf29ce484222325L;
        value = (value ^ Math.max(1, sourceWidth)) * 0x100000001b3L;
        value = (value ^ Math.max(1, sourceHeight)) * 0x100000001b3L;
        value = (value ^ Math.max(1, viewportWidth)) * 0x100000001b3L;
        return (value ^ Math.max(1, viewportHeight)) * 0x100000001b3L;
    }

    private void preemptQualityInference(String reason) {
        DetectionEngine quality = detector;
        if (quality == null || !quality.cancelActiveInference()) return;
        long count = qualityInferencePreemptions.incrementAndGet();
        Log.i(TAG, "QUALITY_PREEMPT reason=" + reason
                + " count=" + count
                + " fastPending=" + (pendingInference.get() != null)
                + " qualityActive=" + qualityInferenceDraining.get());
    }

    private void logQualityCancellation(String reason, DetectionEngine quality) {
        long count = qualityInferenceCancelledRuns.incrementAndGet();
        Log.i(TAG, "QUALITY_DROP reason=" + reason
                + " cancellationMs=" + quality.getLastCancellationMs()
                + " cancelledRuns=" + count
                + " preemptions=" + qualityInferencePreemptions.get());
    }

    private void logStreamingQualityDrop(
            String reason, QualityInferenceFrame candidate, long currentGeneration) {
        long dropped = staleQualityInferenceFrames.incrementAndGet();
        Log.i(TAG, "QUALITY_STREAM_DROP reason=" + reason
                + " sourceGeneration=" + candidate.motionGeneration
                + " currentGeneration=" + currentGeneration
                + " sourceFastSequence=" + candidate.fastSubmissionSequence
                + " currentFastSequence=" + fastSubmissionSequence.get()
                + " staleDropped=" + dropped);
    }

    private FastPriorityInferenceGate.QualityAdmission tryAcquireQualityGate() {
        long nowUptime = SystemClock.uptimeMillis();
        long availableSlackMs = qualityAvailableSlackMs(
                screenshotScheduler.lastDispatchMillis(), nowUptime);
        availableSlackMs = Math.max(availableSlackMs,
                Math.max(0L, qualityReservationUntilUptime.get() - nowUptime));
        long requiredBudgetMs = qualityExecutionBudgetMs(lastSuccessfulQualityDurationMs);
        return inferenceGate.tryAcquireQuality(availableSlackMs, requiredBudgetMs);
    }

    private void logQualityGateSkip(
            FastPriorityInferenceGate.QualityRejection rejection,
            long sourceFastSequence) {
        Log.i(TAG, "QUALITY_GATE_SKIP reason=" + rejection.name().toLowerCase()
                + " fastDemand=" + inferenceGate.hasFastDemand()
                + " activeLane=" + inferenceGate.activeLane().name().toLowerCase()
                + " availableSlackMs=" + Math.max(
                        qualityAvailableSlackMs(
                                screenshotScheduler.lastDispatchMillis(), SystemClock.uptimeMillis()),
                        Math.max(0L, qualityReservationUntilUptime.get()
                                - SystemClock.uptimeMillis()))
                + " requiredBudgetMs="
                + qualityExecutionBudgetMs(lastSuccessfulQualityDurationMs)
                + " lastSuccessfulQualityMs=" + lastSuccessfulQualityDurationMs
                + " sourceFastSequence=" + sourceFastSequence
                + " currentFastSequence=" + fastSubmissionSequence.get());
    }

    private boolean qualityCancellationRequested(QualityInferenceFrame candidate) {
        return candidate == null
                || candidate.scene == null
                || candidate.scene.cancelled.get()
                || textRefreshRunning.get()
                || !isCurrentCapture(candidate.epoch)
                || !isQualitySubmissionCurrent(
                        candidate.motionGeneration, motionGeneration.get(),
                        candidate.fastSubmissionSequence, fastSubmissionSequence.get());
    }

    private boolean qualityCancellationRequested(InferenceFrame candidate) {
        return candidate == null
                || candidate.scene != null && candidate.scene.cancelled.get()
                || !isCurrentCapture(candidate.epoch)
                || !isQualitySubmissionCurrent(
                        candidate.motionGeneration, motionGeneration.get(),
                        candidate.fastSubmissionSequence, fastSubmissionSequence.get());
    }

    static boolean isQualitySubmissionCurrent(
            long sourceMotionGeneration,
            long currentMotionGeneration,
            long sourceFastSequence,
            long currentFastSequence) {
        return sourceMotionGeneration == currentMotionGeneration
                && sourceFastSequence == currentFastSequence;
    }

    static boolean isFastSubmissionCurrent(
            long sourceFastSequence,
            long currentFastSequence) {
        return sourceFastSequence == currentFastSequence;
    }

    static long qualityAvailableSlackMs(long lastScreenshotRequestUptime, long nowUptime) {
        if (lastScreenshotRequestUptime <= 0L) return 0L;
        long nextMandatoryFast = lastScreenshotRequestUptime
                + ACCESSIBILITY_SCREENSHOT_INTERVAL_MS;
        return Math.max(0L, nextMandatoryFast - nowUptime);
    }

    static boolean shouldReserveQualityTick(
            long nowUptime,
            long lastMotionUptime,
            long reservationUntilUptime,
            boolean qualityPending,
            boolean qualityActive) {
        if (!qualityPending && !qualityActive || nowUptime >= reservationUntilUptime) return false;
        return lastMotionUptime <= 0L || nowUptime - lastMotionUptime >= MOTION_SETTLE_MS;
    }

    static long qualityExecutionBudgetMs(long lastSuccessfulDurationMs) {
        if (lastSuccessfulDurationMs <= 0L) return QUALITY_DEFAULT_EXECUTION_BUDGET_MS;
        return Math.max(140L, Math.min(QUALITY_MAX_EXECUTION_BUDGET_MS,
                lastSuccessfulDurationMs + QUALITY_EXECUTION_GUARD_MS));
    }

    static long qualityReservationWindowMs(long lastSuccessfulDurationMs) {
        return Math.max(QUALITY_RESERVED_TICK_MS,
                qualityExecutionBudgetMs(lastSuccessfulDurationMs)
                        + QUALITY_RESERVATION_DISPATCH_GUARD_MS);
    }

    /** Runs warmed ONNX work independently so screenshot motion sampling never waits on ML. */
    private void drainInferenceQueue() {
        try {
            while (running) {
                InferenceFrame candidate = pendingInference.getAndSet(null);
                if (candidate == null) return;
                try {
                    if (!isCurrentCapture(candidate.epoch)
                            || (!candidate.continuousMotionInference
                            && candidate.motionGeneration != motionGeneration.get())) continue;
                    runInference(candidate);
                } catch (Exception error) {
                    DiagnosticsRepository.fail(DIAGNOSTICS_MODE, error);
                    Log.w(TAG, "Could not process detector frame", error);
                } finally {
                    candidate.recycle();
                }
            }
        } finally {
            inferenceDraining.set(false);
            if (pendingInference.get() != null && inferenceWorker != null
                    && !inferenceWorker.isShutdown()
                    && inferenceDraining.compareAndSet(false, true)) {
                try {
                    inferenceWorker.execute(this::drainInferenceQueue);
                } catch (RejectedExecutionException rejected) {
                    inferenceDraining.set(false);
                    discardPendingInference();
                }
            } else {
                scheduleQualityInference();
            }
        }
    }

    private void runInference(InferenceFrame candidate) throws Exception {
        DetectionEngine realtime = fastDetector == null ? detector : fastDetector;
        if (realtime == null) return;
        FastPriorityInferenceGate.FastDemand fastDemand = candidate.fastDemand;
        if (fastDemand == null) {
            // CPU fast and NNAPI quality own distinct sessions/providers. They may execute at the
            // same time; the measured governor below can temporarily pause new quality work, but
            // fast never waits for or cancels an already-running quality invocation.
            runInferencePass(candidate, realtime, true, true);
            return;
        }
        FastPriorityInferenceGate.Lease lease = fastDemand.tryAcquire(FAST_GATE_MAX_WAIT_MS);
        if (lease == null) {
            preemptQualityInference("fast-gate-timeout");
            qualityCircuitOpenUntilUptime.accumulateAndGet(
                    SystemClock.uptimeMillis() + QUALITY_CIRCUIT_BREAKER_MS, Math::max);
            settledInferenceNeeded.set(true);
            queueSettledCapture();
            Log.w(TAG, "FAST_DROP reason=quality-gate-timeout waitBudgetMs="
                    + FAST_GATE_MAX_WAIT_MS + " sequence=" + candidate.fastSubmissionSequence);
            return;
        }
        try (FastPriorityInferenceGate.Lease acquired = lease) {
            if (!isFastSubmissionCurrent(
                    candidate.fastSubmissionSequence, fastSubmissionSequence.get())) {
                Log.i(TAG, "FAST_DROP reason=superseded-after-gate sourceSequence="
                        + candidate.fastSubmissionSequence + " currentSequence="
                        + fastSubmissionSequence.get() + " waitMs=" + acquired.waitMs());
                return;
            }
            if (acquired.waitMs() > 0L) {
                Log.i(TAG, "INFERENCE_GATE lane=fast waitMs=" + acquired.waitMs()
                        + " sequence=" + candidate.fastSubmissionSequence);
            }
            // The fast lane is the sole presentation authority, including source-frame effects.
            // Optional quality runs only through runStreamingQualityInference's cache-only path.
            runInferencePass(candidate, realtime, true, true);
        }
    }

    /** Publishes the fast frame when an optional quality pass yields to newer real-time work. */
    private void publishRetainedSourceFrame(InferenceFrame candidate, String reason) {
        if (!candidate.retainedSourceFrame) return;
        Bitmap sourceFrame = candidate.detachFrame();
        if (sourceFrame == null) return;
        WorldTrackFrame worldFrame = captureWorldTrackFrame();
        List<TrackedObject> currentTracks = worldFrame.tracks;
        long trackCameraX = worldFrame.cameraX;
        long trackCameraY = worldFrame.cameraY;
        Rect viewport = screenBounds();
        main.post(() -> {
            if (isCurrentCapture(candidate.epoch) && overlay != null
                    && isCurrentVisualDocument(candidate.visualDocumentEpoch, candidate.scrollSurfaceKey)) {
                        // A scroll callback may have arrived after the screenshot callback but
                        // before an
                        // optional quality pass yielded. The retained bitmap still belongs to the
                        // hardware
                        // capture timestamp, not to the callback's first estimate of that
                        // timestamp.
                        CaptureScrollTimeline.Phase sourcePhase = resolveCapturePhase(candidate);
                overlay.setTapSourceLabOwned(candidate.labCensorFrame);
                overlay.updateWorld(
                        currentTracks, candidate.sourceWidth, candidate.sourceHeight,
                        sourceFrame, trackCameraX, trackCameraY,
                        sourcePhase.scrollX, sourcePhase.scrollY,
                        viewport.width(), viewport.height());
                traceCalibrationScene(
                        "source:" + candidate.fastSubmissionSequence,
                        currentTracks, Collections.emptyList(),
                        candidate.sourceWidth, candidate.sourceHeight,
                        trackCameraX, trackCameraY,
                        cumulativeScrollX.get(), cumulativeScrollY.get());
                Log.i(TAG, "SOURCE_FRAME_PUBLISH reason=" + reason
                        + " sourceScroll=" + sourcePhase.scrollX + ',' + sourcePhase.scrollY
                        + " sourceGeneration=" + sourcePhase.motionGeneration
                        + " phaseUncertain=" + sourcePhase.phaseUncertain);
            } else {
                sourceFrame.recycle();
            }
        });
    }

    private List<Detection> detectFastFrame(
            DetectionEngine engine,
            Bitmap frame,
            int sourceWidth,
            int sourceHeight) throws Exception {
        int[] inputShape = rectangularFastInputShape(
                sourceWidth, sourceHeight, engine.getInferenceResolution());
        if (inputShape != null
                && "CPU".equals(engine.getActiveProvider())
                && !rectangularFastInputDisabled.get()) {
            try {
                List<Detection> result = engine.detectRectangular(
                        frame, sourceWidth, sourceHeight, inputShape[0], inputShape[1]);
                if (rectangularFastInputReported.compareAndSet(false, true)) {
                    Log.i(TAG, "FAST_INPUT mode=rectangular provider=CPU shape="
                            + inputShape[0] + 'x' + inputShape[1]
                            + " source=" + sourceWidth + 'x' + sourceHeight);
                }
                return result;
            } catch (RuntimeException | ai.onnxruntime.OrtException failure) {
                rectangularFastInputDisabled.set(true);
                Log.w(TAG, "Rectangular fast input rejected; keeping square fallback", failure);
            }
        }
        return engine.detect(frame, sourceWidth, sourceHeight);
    }

    public static int[] rectangularFastInputShape(
            int sourceWidth, int sourceHeight, int longEdge) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || longEdge < 32) return null;
        int longest = Math.max(sourceWidth, sourceHeight);
        int contentWidth = Math.max(1, Math.round(sourceWidth * (longEdge / (float) longest)));
        int contentHeight = Math.max(1, Math.round(sourceHeight * (longEdge / (float) longest)));
        int tensorWidth = Math.min(longEdge, roundUpToStride(contentWidth, 32));
        int tensorHeight = Math.min(longEdge, roundUpToStride(contentHeight, 32));
        // Near-square content does not save enough work to justify a dynamic tensor switch.
        if ((long) tensorWidth * tensorHeight >= (long) longEdge * longEdge * 4L / 5L) {
            return null;
        }
        return new int[]{tensorWidth, tensorHeight};
    }

    private static int roundUpToStride(int value, int stride) {
        int safe = Math.max(1, value);
        return ((safe + stride - 1) / stride) * stride;
    }

    private void runInferencePass(
            InferenceFrame candidate,
            DetectionEngine engine,
            boolean fastPass,
            boolean finalPass) throws Exception {
        if (!fastPass) {
            throw new IllegalStateException(
                    "Render-authoritative quality inference is disabled; use cache-only backfill");
        }
        if (!isCurrentVisualDocument(candidate.visualDocumentEpoch,
                candidate.scrollSurfaceKey)) {
            invalidateScene(candidate.scene, "visual-document-changed-before-inference");
            return;
        }
        if (!fastPass && pendingInference.get() != null) {
            publishRetainedSourceFrame(candidate, "fast-arrived-before-quality");
            return;
        }
        Bitmap frame = candidate.frame;
        long requestedEpoch = candidate.epoch;
        int width = candidate.sourceWidth;
        int height = candidate.sourceHeight;
        boolean qualityActiveAtFastStart = fastPass && qualityInferenceExecuting.get();
        List<Detection> visualDetections;
        if (fastPass) {
            visualDetections = detectFastFrame(engine, frame, width, height);
        } else {
            FastPriorityInferenceGate.QualityAdmission admission = tryAcquireQualityGate();
            if (!admission.admitted()) {
                logQualityGateSkip(admission.rejection(), candidate.fastSubmissionSequence);
                publishRetainedSourceFrame(candidate, "quality-gate-" + admission.rejection());
                return;
            }
            try (FastPriorityInferenceGate.Lease ignored = admission.lease()) {
                visualDetections = engine.detect(frame, width, height,
                        () -> qualityCancellationRequested(candidate));
            }
        }
        boolean concurrentQualityOverlap = fastPass
                && concurrentQualityConfigured()
                && (qualityActiveAtFastStart || qualityInferenceExecuting.get());
        if (fastPass) {
            observeFastConcurrency(engine.getLastRuntimeMs(), concurrentQualityOverlap);
        }
        if (!fastPass && engine.wasLastRunCancelled()) {
            logQualityCancellation("fast-preempted", engine);
            publishRetainedSourceFrame(candidate, "quality-preempted");
            return;
        }
        if (!isCurrentVisualDocument(candidate.visualDocumentEpoch,
                candidate.scrollSurfaceKey)) {
            invalidateScene(candidate.scene, "visual-document-changed-during-inference");
            return;
        }
        CaptureScrollTimeline.Phase refreshedCapturePhase = fastPass
                ? resolveCapturePhase(candidate)
                : null;
        long requestedScrollX = refreshedCapturePhase == null
                ? candidate.scrollX : refreshedCapturePhase.scrollX;
        long requestedScrollY = refreshedCapturePhase == null
                ? candidate.scrollY : refreshedCapturePhase.scrollY;
        long inferenceMotionGeneration = refreshedCapturePhase == null
                ? candidate.motionGeneration : refreshedCapturePhase.motionGeneration;
        boolean capturePhaseUncertain = refreshedCapturePhase == null
                ? candidate.capturePhaseUncertain : refreshedCapturePhase.phaseUncertain;
        long captureEventDeliveryDelayMs = refreshedCapturePhase == null
                ? candidate.captureEventDeliveryDelayMs
                : refreshedCapturePhase.maximumDeliveryDelayMs;
        if (fastPass && (requestedScrollX != candidate.scrollX
                || requestedScrollY != candidate.scrollY
                || inferenceMotionGeneration != candidate.motionGeneration)) {
            CensorLabLog.i(TAG, "CAPTURE_PHASE_REFRESH sourceScroll="
                    + candidate.scrollX + ',' + candidate.scrollY
                    + " refreshedScroll=" + requestedScrollX + ',' + requestedScrollY
                    + " sourceGeneration=" + candidate.motionGeneration
                    + " refreshedGeneration=" + inferenceMotionGeneration
                    + " phaseUncertain=" + capturePhaseUncertain
                    + " eventDeliveryDelayMs=" + captureEventDeliveryDelayMs);
        }
        int rawVisualCount = visualDetections.size();
        if (!fastPass && pendingInference.get() != null) {
            publishRetainedSourceFrame(candidate, "fast-arrived-during-quality");
            return;
        }
        if (!fastPass && (!isCurrentCapture(requestedEpoch)
                || inferenceMotionGeneration != motionGeneration.get()
                || candidate.fastSubmissionSequence != fastSubmissionSequence.get()
                || SystemClock.uptimeMillis() - lastMotionUptime < MOTION_SETTLE_MS)) {
            return;
        }
        int cachedQualityCount;
        int qualityOnlyCount = 0;
        int identityRealtimeLinked = 0;
        int identityQualityLinked = 0;
        int identityFused = 0;
        int identityCarriedQuality = 0;
        int identityUnlinkedQuality = 0;
        SceneTransactionCoordinator.Commit<Detection> sceneCommit = null;
        if (fastPass) {
            visualDetections = FastVisualGate.filter(visualDetections, detectorConfig);
            List<Detection> qualityForScene;
            SceneContext scene = candidate.scene;
            if (scene != null) {
                long fastReadyAt = SystemClock.uptimeMillis();
                traceCaptureStage(candidate.capturedAtUptimeMillis, "fast-ready");
                SceneTransactionCoordinator.Transition<Detection> fastTransition =
                        completeFastScene(candidate, inferenceMotionGeneration, visualDetections);
                if (fastTransition == null) {
                    CensorLabLog.i(TAG, "FAST_DROP reason=completed-scope-changed id=" + scene.key
                            + " sourceSequence=" + candidate.fastSubmissionSequence
                            + " currentSequence=" + fastSubmissionSequence.get());
                    return;
                }
                CensorLabLog.i(TAG, "FAST_READY id=" + scene.key
                        + " captureAgeMs="
                        + (fastReadyAt - candidate.capturedAtUptimeMillis)
                        + " rawVisual=" + rawVisualCount
                        + " acceptedVisual=" + visualDetections.size()
                        + " transactionStatus=" + fastTransition.status().name());
                sceneCommit = fastTransition.commit();
                if (sceneCommit == null
                        && fastTransition.status()
                                == SceneTransactionCoordinator.Status.WAITING) {
                    sceneCommit = scene.awaitCommit();
                    if (sceneCommit == null && !scene.cancelled.get()) {
                        SceneTransactionCoordinator.Transition<Detection> deadline =
                                sceneCoordinator.deadline(scene.key);
                        if (deadline.committed()) scene.deliver(deadline.commit());
                        sceneCommit = deadline.commit();
                        if (sceneCommit == null) sceneCommit = scene.deliveredCommit.get();
                        CensorLabLog.i(TAG, "SCENE_TIMEOUT id=" + scene.key
                                + " status=" + deadline.status().name()
                                + " captureAgeMs=" + Math.max(0L,
                                SystemClock.uptimeMillis()
                                        - candidate.capturedAtUptimeMillis));
                    }
                }
                if (sceneCommit == null) {
                    CensorLabLog.i(TAG, "FAST_DROP reason=scene-closed id=" + scene.key
                            + " sourceSequence=" + candidate.fastSubmissionSequence
                            + " currentSequence=" + fastSubmissionSequence.get());
                    return;
                }
                visualDetections = new ArrayList<>(sceneCommit.fastObservations());
                qualityForScene = sceneCommit.qualityObservations();
            } else {
                qualityForScene = cachedQualityForFrame(
                        width, height, requestedScrollX, requestedScrollY);
            }
            cachedQualityCount = qualityForScene.size();
            VisualIdentityReconciler.Result identity = VisualIdentityReconciler.reconcile(
                    visualDetections, qualityForScene,
                    identityTracksForSource(width, height, requestedScrollX, requestedScrollY));
            visualDetections = identity.detections();
            identityRealtimeLinked = identity.realtimeLinked();
            identityQualityLinked = identity.qualityLinked();
            identityFused = identity.fused();
            identityCarriedQuality = identity.carriedQuality();
            identityUnlinkedQuality = identity.unlinkedQuality();
            for (Detection detection : visualDetections) {
                if (detection != null && detection.getSource()
                        == Detection.ObservationSource.QUALITY_VISUAL) {
                    qualityOnlyCount++;
                }
            }
        } else {
            if (qualityCancellationRequested(candidate)) {
                publishRetainedSourceFrame(candidate, "quality-sequence-stale");
                return;
            }
            VisualDetectionStabilizer.UpdateResult stabilizedQuality =
                    qualityVisualStabilizer.updateWithMetrics(
                            visualDetections, detectorConfig);
            visualDetections = stabilizedQuality.stableDetections();
            if (!isCurrentCapture(requestedEpoch)
                    || inferenceMotionGeneration != motionGeneration.get()) return;
            List<Detection> qualityCoverage = markQualityCoverage(visualDetections);
            VisualIdentityReconciler.Result identity = VisualIdentityReconciler.reconcile(
                    Collections.emptyList(), qualityCoverage,
                    identityTracksForSource(width, height, requestedScrollX, requestedScrollY));
            qualityCoverage = identity.detections();
            int reconciledCoverageCount = qualityCoverage.size();
            boolean batchAlreadyCommitted = qualityBatchCommittedGeneration.get()
                    == inferenceMotionGeneration;
            boolean batchAlreadyClosed = qualityBatchClosedGeneration.get()
                    == inferenceMotionGeneration;
            qualityCoverage = transactionalQualityCoverage(
                    qualityCoverage, stabilizedQuality.pendingCandidates(),
                    batchAlreadyCommitted || batchAlreadyClosed);
            int deferredUnlinked = reconciledCoverageCount - qualityCoverage.size();
            boolean completeQualityScene = stabilizedQuality.pendingCandidates() <= 0;
            int supplementedTracks;
            int retiredQualityTracks = 0;
            boolean confirmationRequested;
            String qualityBatchState;
            long cachedAt;
            boolean cachePreserved;
            synchronized (scrollStateLock) {
                if (qualityCancellationRequested(candidate)) {
                    qualityVisualStabilizer.clear();
                    publishRetainedSourceFrame(candidate, "quality-sequence-stale-commit");
                    return;
                }
                supplementedTracks = tracker.supplementConfirmedQualityCoverage(
                        qualityCoverage, System.nanoTime());
                if (supplementedTracks > 0) {
                    qualityBatchCommittedGeneration.set(inferenceMotionGeneration);
                    qualityBatchClosedGeneration.set(inferenceMotionGeneration);
                    qualityConfirmationRequested.set(false);
                } else if (candidate.qualityConfirmation || completeQualityScene) {
                    // A generation receives one bounded confirmation opportunity. Later periodic
                    // refreshes may strengthen existing identities, but cannot spawn a delayed
                    // second visible wave into an already settled scene.
                    qualityBatchClosedGeneration.set(inferenceMotionGeneration);
                    qualityConfirmationRequested.set(false);
                }
                boolean batchClosed = qualityBatchClosedGeneration.get()
                        == inferenceMotionGeneration;
                confirmationRequested = false;
                if (!batchClosed && stabilizedQuality.pendingCandidates() > 0
                        && qualityConfirmationBurstUsed.compareAndSet(false, true)) {
                    qualityConfirmationRequested.set(true);
                    confirmationRequested = true;
                } else if (stabilizedQuality.pendingCandidates() <= 0 || batchClosed) {
                    qualityConfirmationRequested.set(false);
                }
                qualityBatchState = qualityBatchCommittedGeneration.get()
                        == inferenceMotionGeneration ? "COMMITTED"
                        : batchClosed ? "CLOSED" : confirmationRequested
                                || qualityConfirmationRequested.get()
                                ? "CONFIRMATION_ARMED" : "OPEN";
                cachedAt = SystemClock.uptimeMillis();
                VisualDetectionSnapshot previous = cachedQualityVisual;
                int previousCount = previous != VisualDetectionSnapshot.EMPTY
                        && previous.motionGeneration == inferenceMotionGeneration
                        ? previous.detections.size() : 0;
                cachePreserved = !shouldReplaceQualityCache(
                        previousCount, qualityCoverage.size(), completeQualityScene);
                if (!cachePreserved) {
                    cachedQualityVisual = new VisualDetectionSnapshot(
                            qualityCoverage, width, height, requestedScrollX, requestedScrollY,
                            cachedAt, inferenceMotionGeneration);
                }
            }
            cachedQualityCount = visualDetections.size();
            long inferenceMs = engine.getLastInferenceMs();
            long preprocessMs = engine.getLastPreprocessMs();
            long runtimeMs = engine.getLastRuntimeMs();
            long postprocessMs = engine.getLastPostprocessMs();
            CensorLabLog.i(TAG, "QUALITY_CACHE scrollId=" + scrollTraceId
                    + " captureAgeMs=" + (cachedAt - candidate.capturedAtUptimeMillis)
                    + " inferenceMs=" + inferenceMs
                    + " preprocessMs=" + preprocessMs
                    + " runtimeMs=" + runtimeMs
                    + " postprocessMs=" + postprocessMs
                    + " afterMotionMs=" + (lastMotionUptime <= 0L
                            ? 0L : cachedAt - lastMotionUptime)
                    + " rawVisual=" + rawVisualCount
                    + " stableVisual=" + cachedQualityCount
                    + " identityLinked=" + identity.qualityLinked()
                    + " identityUnlinked=" + identity.unlinkedQuality()
                    + " pendingVisual=" + stabilizedQuality.pendingCandidates()
                    + " deferredUnlinked=" + deferredUnlinked
                    + " supplementedTracks=" + supplementedTracks
                    + " retiredQualityTracks=" + retiredQualityTracks
                    + " confirmationRequested=" + confirmationRequested
                    + " qualityBatchState=" + qualityBatchState
                    + " cachePreserved=" + cachePreserved
                    + " cacheGeneration=" + inferenceMotionGeneration);
            lastSuccessfulQualityDurationMs = engine.getLastInferenceMs();
            // Quality is a background geometry cache. Publishing it as a second authority made
            // a still box alternate between fast and quality rectangles every refresh. The next
            // fast frame consumes this cache once through the ordinary tracker update.
            Bitmap qualityOverlayFrame = candidate.retainedSourceFrame
                    ? candidate.detachFrame() : null;
            if (qualityOverlayFrame != null || supplementedTracks > 0
                    || retiredQualityTracks > 0) {
                List<TrackedObject> activeTracks = tracker.activeTracks();
                WorldTrackFrame worldFrame = captureWorldTrackFrame();
                VisualTrackArbitrator.Result renderArbitration = worldFrame.arbitration;
                List<TrackedObject> currentTracks = worldFrame.tracks;
                if (supplementedTracks > 0) {
                    recordSupplementedTrackState(activeTracks, width, height, candidate.labCensorFrame);
                }
                long trackCameraX = worldFrame.cameraX;
                long trackCameraY = worldFrame.cameraY;
                Rect viewport = screenBounds();
                main.post(() -> {
                    if (isCurrentCapture(requestedEpoch) && overlay != null
                            && inferenceMotionGeneration == motionGeneration.get()
                            && candidate.fastSubmissionSequence
                                    == fastSubmissionSequence.get()) {
                        overlay.setTapSourceLabOwned(candidate.labCensorFrame);
                        if (qualityOverlayFrame != null) {
                            overlay.updateWorld(currentTracks, width, height,
                                    qualityOverlayFrame,
                                    trackCameraX, trackCameraY,
                                    requestedScrollX, requestedScrollY,
                                    viewport.width(), viewport.height());
                        } else {
                            overlay.updateWorldTracksOnly(currentTracks, width, height,
                                    trackCameraX, trackCameraY,
                                    viewport.width(), viewport.height());
                        }
                        traceCalibrationScene(
                                "quality:" + candidate.fastSubmissionSequence,
                                currentTracks, Collections.emptyList(),
                                width, height, trackCameraX, trackCameraY,
                                cumulativeScrollX.get(), cumulativeScrollY.get());
                        if (supplementedTracks > 0 || retiredQualityTracks > 0) {
                            VisualGeometryDelta geometry = recordVisualGeometry(currentTracks);
                            String handoffEvent = supplementedTracks > 0
                                    ? "QUALITY_SUPPLEMENT_PUBLISH" : "QUALITY_RETIRE_PUBLISH";
                            Log.i(TAG, handoffEvent + " scrollId=" + scrollTraceId
                                    + " added=" + supplementedTracks
                                    + " retired=" + retiredQualityTracks
                                    + " afterMotionMs=" + (lastMotionUptime <= 0L ? 0L
                                    : SystemClock.uptimeMillis() - lastMotionUptime)
                                    + " geometryMatched=" + geometry.matched
                                    + " geometryChanged=" + geometry.changed
                                    + " maxCenterDeltaPx=" + geometry.maxCenterDeltaPx
                                    + " maxSizeDeltaPx=" + geometry.maxSizeDeltaPx
                                    + " duplicatesSuppressed=" + renderArbitration.suppressed()
                                    + " renderTracks=" + currentTracks.size());
                        }
                    } else {
                        if (qualityOverlayFrame != null) qualityOverlayFrame.recycle();
                    }
                });
            }
            return;
        }
        Rect referenceViewport = screenBounds();
        RenderSourceReference frameRenderReference = resolveRenderReference(
                requestedEpoch, candidate.visualDocumentEpoch, candidate.captureWindowId,
                referenceViewport.width(), referenceViewport.height(), candidate.screenshotUptimeMillis,
                requestedScrollX, requestedScrollY, !capturePhaseUncertain);
        TextSmutConfig currentTextConfig = textSmutConfig;
        List<Detection> accessibilityDetections = Collections.emptyList();
        if (currentTextConfig != null && currentTextConfig.isEnabled()) {
            accessibilityDetections = cachedTextForFrame(
                    width, height, requestedScrollX, requestedScrollY);
            requestTextRefresh();
        }
        List<Detection> detections = DetectionFusion.merge(
                visualDetections, accessibilityDetections);
        if (!isCurrentCapture(requestedEpoch)
                || (!candidate.continuousMotionInference
                && inferenceMotionGeneration != motionGeneration.get())) return;
        ScrollAlignment alignment;
        List<TrackedObject> tracks;
        synchronized (sceneLifecycleLock) {
            // A capture can pass its entry guard immediately before a calibration fence.
            // Recheck under the same lock as that fence, including non-transactional scenes.
            if (!isCurrentVisualDocument(candidate.visualDocumentEpoch, candidate.scrollSurfaceKey)) return;
            if (sceneCommit != null && !sceneCoordinator.isPresentationCurrent(sceneCommit)) {
                CensorLabLog.i(TAG, "SCENE_LATE_DROP id=" + sceneCommit.key()
                        + " lane=commit reason=not-current-before-tracker");
                return;
            }
            alignment = consumeTrackerMotion(width, height,
                    candidate.scene == null ? null : candidate.scene.spatialFrame,
                    requestedScrollX, requestedScrollY, detections, candidate.continuousMotionInference);
            if (!candidate.continuousMotionInference
                    && inferenceMotionGeneration != motionGeneration.get()) return;
            if (candidate.continuousMotionInference) {
                Rect viewport = screenBounds();
                detections = InferenceScrollReprojector.toCurrentViewport(
                        detections, width, height, viewport.width(), viewport.height(),
                        requestedScrollX, requestedScrollY,
                        alignment.scrollX, alignment.scrollY);
            }
            // Opaque provenance follows raw observations; tracking never consumes its values.
            List<Detection> referenced = new ArrayList<>(detections.size());
            for (Detection detection : detections) {
                referenced.add(detection.getSource() == Detection.ObservationSource.VISUAL
                        && !"text_smut".equals(detection.getCategory())
                        && !detection.getCategory().startsWith("text_")
                        ? detection.withRenderSourceReference(frameRenderReference) : detection);
            }
            tracks = tracker.update(referenced);
            if (spatialTrackingExperiment) {
                sourceTrackContinuity.record(candidate.scene == null ? null : candidate.scene.spatialFrame,
                        requestedScrollX, requestedScrollY);
                CensorLabLog.i(TAG, "SOURCE_TRACK v=2 id=" + candidate.capturedAtUptimeMillis
                        + " enabled=" + correctSpatialTracks + " corrected=" + alignment.correctedTracks
                        + " global=" + alignment.globalCorrections + " local=" + alignment.localCorrections
                        + " pairs=" + alignment.localPairs + " maxAbsExtraDy=" + alignment.maxAbsExtraDy
                        + " cpuUs=" + alignment.correctionCpuUs);
            }
        }
        VisualTrackArbitrator.Result renderArbitration = visualRenderTracks(tracks);
        List<TrackedObject> renderTracks = renderArbitration.tracks();
        Rect cacheViewport = screenBounds();
        LateQualityPresentation lateQualityForScene = takeLateQualityForFast(
                candidate.fastSubmissionSequence,
                requestedEpoch,
                candidate.visualDocumentEpoch,
                candidate.scrollSurfaceKey,
                inferenceMotionGeneration,
                alignment.scrollX,
                alignment.scrollY,
                width,
                height,
                cacheViewport.width(),
                cacheViewport.height(),
                candidate.captureWindowId);
        QualityPresentationAligner.Result qualityAlignment = lateQualityForScene == null
                ? QualityPresentationAligner.Result.EMPTY
                : QualityPresentationAligner.align(
                        lateQualityForScene.screenDetections(
                                alignment.scrollX, alignment.scrollY,
                                width, height,
                                cacheViewport.width(), cacheViewport.height()),
                        renderTracks, width, height);
        List<Detection> lateQualityRegions = QualityPresentationAligner.addSafetyCoverage(
                uncoveredLateQualityRegions(qualityAlignment.detections(), renderTracks),
                width, height);
        if (lateQualityForScene != null) {
            CensorLabLog.i(TAG, "QUALITY_GEOMETRY_ALIGN"
                    + " sourceFastSequence=" + lateQualityForScene.fastSubmissionSequence
                    + " consumerFastSequence=" + candidate.fastSubmissionSequence
                    + " matched=" + qualityAlignment.matched()
                    + " dx=" + qualityAlignment.dx()
                    + " dy=" + qualityAlignment.dy()
                    + " residualPx=" + qualityAlignment.medianResidualPx()
                    + " uncovered=" + lateQualityRegions.size());
        }
        List<Detection> cachedRenderRegions = updateWorldCache(
                renderTracks, alignment.scrollX, alignment.scrollY,
                width, height, cacheViewport.width(), cacheViewport.height(),
                worldCacheEvidence(sceneCommit == null ? null : sceneCommit.kind(),
                        inferenceMotionGeneration == motionGeneration.get(), candidate.capturedAtUptimeMillis),
                sceneCommit == null ? "legacy-fast" : sceneCommit.kind().name(),
                candidate.visualDocumentEpoch, candidate.scrollSurfaceKey,
                inferenceMotionGeneration,
                Collections.emptyList(), candidate.scene == null ? null : candidate.scene.spatialFrame);
        List<Detection> presentationRenderRegions;
        if (lateQualityRegions.isEmpty()) {
            presentationRenderRegions = cachedRenderRegions;
        } else {
            List<Detection> combinedPresentationRegions = new ArrayList<>(
                    cachedRenderRegions.size() + lateQualityRegions.size());
            combinedPresentationRegions.addAll(cachedRenderRegions);
            combinedPresentationRegions.addAll(lateQualityRegions);
            presentationRenderRegions = Collections.unmodifiableList(
                    combinedPresentationRegions);
        }
        int qualityOnlyTrackCount = 0;
        traceCaptureStage(candidate.capturedAtUptimeMillis, "geometry-ready");
        for (TrackedObject track : tracks) {
            if (track != null && track.isQualityOnly()) qualityOnlyTrackCount++;
        }
        DetectorConfig currentConfig = detectorConfig;
        boolean recordNormalEffects = CensorLabCensorSession.allowsNormalCensorEffects(candidate.labCensorFrame);
        int recordedBlocks = recordNormalEffects ? stats.recordTracks(tracks, currentConfig == null
                ? null : currentConfig.getEnabledCategories()) : 0;
        long now = System.currentTimeMillis();
        if (recordedBlocks > 0) {
            int charged = penance.recordInfraction(
                    PenanceInfraction.NEW_DETECTION, recordedBlocks, now);
            PenanceChargeNotifier.show(this, penance,
                    PenanceInfraction.NEW_DETECTION, charged, now);
            new AchievementManager(this).checkAchievements(stats.load());
        }
        long inferenceMs = engine.getLastInferenceMs();
        long preprocessMs = engine.getLastPreprocessMs();
        long runtimeMs = engine.getLastRuntimeMs();
        long postprocessMs = engine.getLastPostprocessMs();
        boolean firstFastFrame = firstFrameReported.compareAndSet(false, true);
        if (firstFastFrame) {
            Log.i(TAG, "First accessibility fast frame processed in "
                    + inferenceMs + " ms at " + width + "x" + height);
        }
        Bitmap overlayFrame = finalPass && candidate.retainedSourceFrame
                ? candidate.detachFrame() : null;
        int dwellInfractions = recordNormalEffects ? dwellTracker.update(
                tracks, now, penance.getDwellSeconds() * 1_000L, false) : 0;
        if (dwellInfractions > 0) {
            int charged = penance.recordInfraction(
                    PenanceInfraction.CENSORED_DWELL, dwellInfractions, now);
            PenanceChargeNotifier.show(this, penance,
                    PenanceInfraction.CENSORED_DWELL, charged, now);
        }
        if (recordNormalEffects) {
            PopupStormManager.get().updateTrackedObjects(tracks, width, height);
        }
        DiagnosticsRepository.Snapshot diagnostics = DiagnosticsRepository.recordFrame(
                DIAGNOSTICS_MODE, inferenceMs, preprocessMs, runtimeMs, postprocessMs,
                SystemClock.uptimeMillis() - candidate.capturedAtUptimeMillis,
                droppedInferenceFrames.get(), tracks.size(), width, height);
        String diagnosticText = diagnosticsOverlayText(diagnostics);
        int publishedQualityOnlyCount = qualityOnlyCount;
        int publishedIdentityRealtimeLinked = identityRealtimeLinked;
        int publishedIdentityQualityLinked = identityQualityLinked;
        int publishedIdentityFused = identityFused;
        int publishedIdentityCarriedQuality = identityCarriedQuality;
        int publishedIdentityUnlinkedQuality = identityUnlinkedQuality;
        int publishedQualityOnlyTrackCount = qualityOnlyTrackCount;
        int publishedVisibleRenderTrackCount = visibleRenderTrackCount(
                renderTracks, width, height);
        SceneTransactionCoordinator.Commit<Detection> publishedSceneCommit = sceneCommit;
        InferenceScrollReprojector.ScreenMotion sourceFrameMotion =
                InferenceScrollReprojector.screenMotion(
                        requestedScrollX, requestedScrollY,
                        alignment.scrollX, alignment.scrollY);
        String faceGeometry = captureGapProbe == null && !spatialTrackingExperiment
                ? null : FaceGeometryTrace.encode(visualDetections, renderTracks);
        String faceSourcePose = faceGeometry == null ? "-" : FaceGeometryTrace.sourcePose(
                candidate.scene == null ? null : candidate.scene.spatialFrame);
        Rect publicationViewport = cacheViewport;
        traceCaptureStage(candidate.capturedAtUptimeMillis, "publication-post");
        Runnable publication = () -> {
            traceCaptureStage(candidate.capturedAtUptimeMillis, "publication-main");
            traceCaptureStage(candidate.capturedAtUptimeMillis, "publication-tick");
            if (isCurrentCapture(requestedEpoch) && overlay != null
                    && isCurrentVisualDocument(candidate.visualDocumentEpoch,
                            candidate.scrollSurfaceKey)
                    && (candidate.continuousMotionInference
                    || inferenceMotionGeneration == motionGeneration.get())
                    && (publishedSceneCommit == null
                    || sceneCoordinator.isPresentationCurrent(publishedSceneCommit))) {
                ScrollPosition current = currentScrollPosition();
                InferenceScrollReprojector.ScreenMotion liveMotion =
                        InferenceScrollReprojector.screenMotion(
                                alignment.scrollX, alignment.scrollY,
                                current.scrollX, current.scrollY);
                long publishedAt = SystemClock.uptimeMillis();
                if (faceGeometry != null) CensorLabLog.i(TAG, "FACE_GEOMETRY v=2 id="
                        + candidate.capturedAtUptimeMillis + " source=" + width + 'x' + height
                        + " viewport=" + publicationViewport.width() + 'x' + publicationViewport.height()
                        + " cameras=" + requestedScrollX + ',' + requestedScrollY + ','
                        + alignment.scrollX + ',' + alignment.scrollY + ',' + current.scrollX + ',' + current.scrollY
                        + " " + faceGeometry + " spatial=" + faceSourcePose);
                overlay.setDiagnostics(diagnosticText);
                if (overlay.ensureShown()) {
                    CensorLabLog.i(TAG, "OVERLAY_REATTACH reason=missing-window"
                            + " sourceFastSequence=" + candidate.fastSubmissionSequence
                            + " tracks=" + renderTracks.size()
                            + " cached=" + cachedRenderRegions.size());
                }
                List<Detection> regionsForView;
                boolean lateQualityPublished;
                                    // Recheck on the actual UI publication, not only on the
                                    // inference worker.
                                    // Slot replacement/expiry cannot race between this fence and
                                    // the overlay write.
                                    synchronized (pendingLateQualityPresentation) {
                Rect liveViewport = screenBounds();
                lateQualityPublished = lateQualityForScene != null
                        && (!lateQualityForScene.surfaceKey.isEmpty()
                        || (pendingLateQualityPresentation.get() == lateQualityForScene
                        && !capturePhaseUncertain
                        && lateQualityForScene.matchFast(candidate.fastSubmissionSequence,
                                captureEpoch.token(), visualDocumentEpoch.get(), activeScrollSurfaceKey,
                                activeScrollTelemetryToken, motionGeneration.get(),
                                current.scrollX, current.scrollY, latestCaptureWidth, latestCaptureHeight,
                                liveViewport.width(), liveViewport.height(), activeApplicationWindowId.get())
                                == LateQualityPresentation.Match.MATCH));
                List<Detection> eligiblePresentationRegions = lateQualityPublished
                        ? presentationRenderRegions : cachedRenderRegions;
                regionsForView = spatialCacheExperiment
                        ? spatialRegionCache.revalidatePresentation(
                                candidate.scene == null ? null : candidate.scene.spatialFrame,
                                publishedAt, cachedRenderRegions, eligiblePresentationRegions)
                        : eligiblePresentationRegions;
                overlay.setTapSourceLabOwned(candidate.labCensorFrame);
                overlay.updateWorldWithCache(
                        renderTracks, regionsForView, width, height, overlayFrame,
                        alignment.scrollX, alignment.scrollY,
                        requestedScrollX, requestedScrollY,
                        publicationViewport.width(), publicationViewport.height(), frameRenderReference);
                pendingLateQualityPresentation.markDisplayed(lateQualityPublished ? lateQualityForScene : null);
                if (fastPass && (BuildConfig.IMMEDIATE_QUALITY_EXPERIMENT
                        || candidate.scrollSurfaceKey.isEmpty())) {
                    displayedQualityBasis = new DisplayedQualityBasis(
                            new LateQualityPresentationGate.Stamp(candidate.fastSubmissionSequence,
                                    requestedEpoch, candidate.visualDocumentEpoch, candidate.scrollSurfaceKey,
                                    activeScrollTelemetryToken, inferenceMotionGeneration,
                                    alignment.scrollX, alignment.scrollY, width, height,
                                    publicationViewport.width(), publicationViewport.height(),
                                    candidate.captureWindowId, candidate.scrollSurfaceKey.isEmpty()),
                            !capturePhaseUncertain, renderTracks, cachedRenderRegions, frameRenderReference);
                    scheduleImmediateQualityRefresh();
                }
                }
                if (spatialCacheExperiment) {
                    int admitted = overlay.admittedCachedRegionCount(cachedRenderRegions);
                    spatialRegionCache.markApplied(candidate.scene == null ? null : candidate.scene.spatialFrame,
                            publishedAt, admitted);
                    CensorLabLog.i(TAG, "SPATIAL_CACHE_APPLIED kind=scene input=" + cachedRenderRegions.size()
                            + " admitted=" + admitted);
                }
                traceCalibrationScene(
                        publishedSceneCommit == null ? "legacy"
                                : publishedSceneCommit.key().toString(),
                        renderTracks, regionsForView,
                        width, height, alignment.scrollX, alignment.scrollY,
                        current.scrollX, current.scrollY);
                if (lateQualityPublished && lateQualityForScene.fastPresented.compareAndSet(false, true)) {
                    CensorLabLog.i(TAG, "QUALITY_LATE_PRESENT sourceFastSequence="
                            + lateQualityForScene.fastSubmissionSequence
                            + " consumerFastSequence=" + candidate.fastSubmissionSequence
                            + " sourceGeneration=" + lateQualityForScene.motionGeneration
                            + " regions=" + lateQualityRegions.size()
                            + " readyToPresentMs=" + Math.max(0L,
                                    publishedAt - lateQualityForScene.readyAtUptime));
                } else if (lateQualityPublished) {
                    CensorLabLog.i(TAG, "QUALITY_RETAINED_PRESENT sourceFastSequence="
                            + lateQualityForScene.fastSubmissionSequence
                            + " consumerFastSequence=" + candidate.fastSubmissionSequence
                            + " regions=" + lateQualityRegions.size()
                            + " captureAgeMs=" + Math.max(0L,
                                    publishedAt - lateQualityForScene.capturedAtUptime));
                }
                lastFastOverlayGeneration = motionGeneration.get();
                long publishDelay = publishedAt - candidate.capturedAtUptimeMillis;
                if (publishedSceneCommit != null) {
                    CensorLabLog.i(TAG, "SCENE_COMMIT id=" + publishedSceneCommit.key()
                            + " kind=" + publishedSceneCommit.kind().name()
                            + " captureAgeMs=" + publishDelay
                            + " fast=" + publishedSceneCommit.fastObservations().size()
                            + " quality="
                            + publishedSceneCommit.qualityObservations().size()
                            + " renderTracks=" + renderTracks.size()
                            + " visibleDeadlineMiss=" + (candidate.scene != null
                            && publishedAt > candidate.scene.visibleDeadlineUptimeMillis));
                }
                if (firstOverlayReported.compareAndSet(false, true)) {
                    Log.i(TAG, "STARTUP session=" + activeStartupSession
                            + " phase=first-fast-overlay uptimeMs=" + publishedAt);
                                        // Session/provider construction can contend for the same
                                        // resources as the
                                        // fast lane. Optional refinement starts only after coverage
                                        // is visible.
                                        scheduleQualityDetectorInitialization(detectorConfig);
                }
                VisualGeometryDelta geometry = recordVisualGeometry(renderTracks);
                DiagnosticsRepository.recordPublishDelay(DIAGNOSTICS_MODE, publishDelay);
                CensorLabLog.i(TAG, "OVERLAY_PUBLISH pass=" + (fastPass ? "fast" : "quality")
                        + " scrollId=" + scrollTraceId
                        + " captureAgeMs=" + publishDelay
                        + " inferenceMs=" + inferenceMs
                        + " preprocessMs=" + preprocessMs
                        + " runtimeMs=" + runtimeMs
                        + " postprocessMs=" + postprocessMs
                        + " afterMotionMs=" + (lastMotionUptime <= 0L
                                ? 0L : publishedAt - lastMotionUptime)
                        + " sourceScroll=" + requestedScrollX + ',' + requestedScrollY
                        + " alignedScroll=" + alignment.scrollX + ',' + alignment.scrollY
                        + " currentScroll=" + current.scrollX + ',' + current.scrollY
                        + " sourceReproject=" + sourceFrameMotion.dx + ','
                        + sourceFrameMotion.dy
                        + " liveReproject=" + liveMotion.dx + ',' + liveMotion.dy
                        + " captureGeneration=" + inferenceMotionGeneration
                        + " capturePhaseUncertain=" + capturePhaseUncertain
                        + " captureEventDelayMs=" + captureEventDeliveryDelayMs
                        + " currentGeneration=" + motionGeneration.get()
                        + " tracks=" + tracks.size()
                        + " rawVisual=" + rawVisualCount
                        + " cachedQuality=" + cachedQualityCount
                        + " qualityOnly=" + publishedQualityOnlyCount
                        + " identityRealtimeLinked=" + publishedIdentityRealtimeLinked
                        + " identityQualityLinked=" + publishedIdentityQualityLinked
                        + " identityFused=" + publishedIdentityFused
                        + " identityCarriedQuality=" + publishedIdentityCarriedQuality
                        + " identityUnlinkedQuality=" + publishedIdentityUnlinkedQuality
                        + " geometryMatched=" + geometry.matched
                        + " geometryChanged=" + geometry.changed
                        + " maxCenterDeltaPx=" + geometry.maxCenterDeltaPx
                        + " maxSizeDeltaPx=" + geometry.maxSizeDeltaPx
                        + " dropped=" + droppedInferenceFrames.get()
                        + " duplicatesSuppressed=" + renderArbitration.suppressed()
                        + " renderHandOffs=" + renderArbitration.handedOff()
                        + " qualityOnlyTracks=" + publishedQualityOnlyTrackCount
                        + " renderTracks=" + renderTracks.size()
                        + " visibleRenderTracks=" + publishedVisibleRenderTrackCount
                        + " qualityActive=" + qualityInferenceDraining.get()
                        + " qualityConcurrent=" + concurrentQualityOverlap
                        + " qualityPreemptions=" + qualityInferencePreemptions.get()
                        + " qualityCancelledRuns=" + qualityInferenceCancelledRuns.get()
                        + " renderSourceKnown=" + frameRenderReference.isKnown()
                        + " renderSourceTime=" + frameRenderReference.sourceUptimeMillis()
                        + " renderSourceBias=" + Math.round(frameRenderReference.biasX())
                        + ',' + Math.round(frameRenderReference.biasY()));
                scheduleGpuPreparationWarmup();
            } else if (overlayFrame != null) {
                overlayFrame.recycle();
            }
        };
        String presentationId = publishedSceneCommit == null
                ? "legacy-" + candidate.fastSubmissionSequence
                : publishedSceneCommit.key().toString();
        submitScenePresentation(presentationId, publication, () -> {
            if (overlayFrame != null && !overlayFrame.isRecycled()) {
                overlayFrame.recycle();
            }
        });
    }

    /** Admission is worker-side; an unavailable main looper must never become a frame queue. */
    private void submitScenePresentation(String sceneId, Runnable publication, Runnable disposer) {
        PendingScenePresentation presentation = new PendingScenePresentation(
                sceneId, publication, disposer);
        LatestFrameBroker<PendingScenePresentation> presenter = scenePresenter;
        if (presenter == null) presentation.dispose();
        else presenter.submit(presentation);
    }

    private void scheduleGpuPreparationWarmup() {
        if (!gpuPreparationExperiment || Build.VERSION.SDK_INT < 29
                || !gpuWarmupScheduled.compareAndSet(false, true)) return;
        ScheduledExecutorService captureWorker = worker;
        if (captureWorker == null || captureWorker.isShutdown()) return;
        try {
            captureWorker.execute(() -> {
                if (!running || !gpuPreparationExperiment) return;
                long start = SystemClock.uptimeMillis();
                Bitmap software = null, hardware = null;
                InferenceBitmapPreparer.Prepared warm = null;
                try {
                    int resolution = fastInferenceFrameResolution(detectorConfig,
                            fastDetector != null, overlayNeedsSourceFrame);
                    int[] size = InferenceBitmapPreparer.targetDimensions(
                            latestCaptureWidth, latestCaptureHeight, resolution);
                    software = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
                    software.eraseColor(android.graphics.Color.BLACK);
                    hardware = software.copy(Bitmap.Config.HARDWARE, false);
                    if (gpuBitmapPreparer == null) gpuBitmapPreparer = new GpuBitmapPreparer();
                    warm = gpuBitmapPreparer.prepare(hardware, resolution, false);
                    gpuPreparationReady = warm != null;
                } catch (RuntimeException failed) {
                    gpuPreparationReady = false;
                } finally {
                    if (warm != null) warm.bitmap.recycle();
                    if (hardware != null) hardware.recycle();
                    if (software != null) software.recycle();
                    if (!gpuPreparationReady) {
                        gpuPreparationExperiment = false;
                        if (gpuBitmapPreparer != null) gpuBitmapPreparer.close();
                        gpuBitmapPreparer = null;
                    }
                    CensorLabLog.i(TAG, "GPU_WARMUP afterFirstPublish=true elapsedMs="
                            + (SystemClock.uptimeMillis() - start) + " ready=" + gpuPreparationReady);
                }
            });
        } catch (RejectedExecutionException stopped) {
            gpuPreparationExperiment = false;
        }
    }

    /** Numeric-only teacher record used to mask/score censors against the recorded pixels. */
    private void traceCalibrationScene(
            String sceneId,
            List<TrackedObject> live,
            List<Detection> cached,
            int width,
            int height,
            long trackCameraX,
            long trackCameraY,
            long currentCameraX,
            long currentCameraY) {
        if (!CensorLabRecorder.isActive()) return;
        StringBuilder liveBoxes = new StringBuilder(512);
        StringBuilder cachedBoxes = new StringBuilder(256);
        int liveCount = appendCalibrationTrackBoxes(
                liveBoxes, live, CALIBRATION_MAX_LIVE_BOXES);
        int cachedCount = appendCalibrationDetectionBoxes(cachedBoxes, cached,
                Math.max(0, CALIBRATION_MAX_TOTAL_BOXES - liveCount));
        CensorLabLog.i(TAG, "CALIBRATION_SCENE id=" + sceneId
                + " size=" + width + 'x' + height
                + " trackCamera=" + trackCameraX + ',' + trackCameraY
                + " currentCamera=" + currentCameraX + ',' + currentCameraY
                + " liveCount=" + (live == null ? 0 : live.size())
                + " cachedCount=" + (cached == null ? 0 : cached.size())
                + " encodedLive=" + liveCount
                + " encodedCached=" + cachedCount
                + " liveBoxes=" + liveBoxes
                + " cachedBoxes=" + cachedBoxes);
    }

    private static int appendCalibrationTrackBoxes(
            StringBuilder output, List<TrackedObject> tracks, int limit) {
        int written = 0;
        if (tracks == null || limit <= 0) return written;
        for (TrackedObject track : tracks) {
            if (track == null || written >= limit) break;
            if (written > 0) output.append(';');
            appendCalibrationBox(output, track.getBox());
            output.append(',').append(track.getId());
            written++;
        }
        return written;
    }

    private static int appendCalibrationDetectionBoxes(
            StringBuilder output, List<Detection> detections, int limit) {
        int written = 0;
        if (detections == null || limit <= 0) return written;
        for (Detection detection : detections) {
            if (detection == null || written >= limit) break;
            if (written > 0) output.append(';');
            appendCalibrationBox(output, detection.getBox());
            written++;
        }
        return written;
    }

    private static void appendCalibrationBox(StringBuilder output, BBox box) {
        if (box == null) {
            output.append("0,0,0,0");
            return;
        }
        output.append(box.getX()).append(',').append(box.getY()).append(',')
                .append(box.getWidth()).append(',').append(box.getHeight());
    }

    private CaptureScrollTimeline.Phase resolveCapturePhase(InferenceFrame candidate) {
        return captureScrollTimeline.resolve(
                candidate.captureTime,
                candidate.requestedScrollX,
                candidate.requestedScrollY,
                candidate.requestedGeneration).withUnresolvedMotion(hasPendingScrollLookup());
    }

    /** Mirrors fast-publish bookkeeping for confirmed tracks first exposed by quality inference. */
    private void recordSupplementedTrackState(
            List<TrackedObject> tracks,
            int width,
            int height,
            boolean labOwnedSource) {
        if (!CensorLabCensorSession.allowsNormalCensorEffects(labOwnedSource)) return;
        DetectorConfig currentConfig = detectorConfig;
        int recordedBlocks = stats.recordTracks(tracks, currentConfig == null
                ? null : currentConfig.getEnabledCategories());
        long now = System.currentTimeMillis();
        if (recordedBlocks > 0) {
            int charged = penance.recordInfraction(
                    PenanceInfraction.NEW_DETECTION, recordedBlocks, now);
            PenanceChargeNotifier.show(this, penance,
                    PenanceInfraction.NEW_DETECTION, charged, now);
            new AchievementManager(this).checkAchievements(stats.load());
        }
        int dwellInfractions = dwellTracker.update(
                tracks, now, penance.getDwellSeconds() * 1_000L, false);
        if (dwellInfractions > 0) {
            int charged = penance.recordInfraction(
                    PenanceInfraction.CENSORED_DWELL, dwellInfractions, now);
            PenanceChargeNotifier.show(this, penance,
                    PenanceInfraction.CENSORED_DWELL, charged, now);
        }
        PopupStormManager.get().updateTrackedObjects(tracks, width, height);
    }

    static long captureDelayMs(DetectorConfig config) {
        int threads = config == null ? 2 : config.getInferenceThreads();
        long floor;
        if (threads <= 1) floor = 450L;
        else if (threads == 2) floor = 300L;
        else if (threads == 3) floor = 240L;
        else floor = 180L;
        return Math.max(floor, config == null ? 0L : config.getDetectionIntervalMs());
    }

    static DetectorConfig fastDetectorConfig(DetectorConfig quality) {
        DetectorConfig source = quality == null ? DetectorConfig.builder().build() : quality;
        return source.toBuilder()
                .inferenceResolution(Math.min(
                        FAST_INFERENCE_RESOLUTION, source.getInferenceResolution()))
                .detectionIntervalMs(0L)
                .build();
    }

    static DetectorConfig qualityDetectorConfig(DetectorConfig source) {
        // Only the fast source owns person publication. Do not copy pixels or decode support
        // cues from a second, cache-only quality pass that cannot consume them.
        return source.toBuilder().censorCoverage(CensorCoverage.DETECTED_AREAS).build();
    }

    static DetectorConfig accessibilityTrackerConfig(DetectorConfig configured) {
        DetectorConfig source = configured == null
                ? DetectorConfig.builder().build() : configured;
        return source.toBuilder()
                .motionPrediction(false)
                .velocitySmoothing(0f)
                .maxExtrapolationMs(0f)
                .build();
    }

    static boolean shouldRunQualityRefinement(
            long nowUptime,
            long lastMotionUptime,
            long lastQualityUptime,
            boolean firstFrameReported,
            boolean qualityCacheEmpty) {
        return shouldRunQualityRefinement(nowUptime, lastMotionUptime, lastQualityUptime,
                firstFrameReported, qualityCacheEmpty, 0L, false, false);
    }

    static boolean shouldRunQualityRefinement(
            long nowUptime,
            long lastMotionUptime,
            long lastQualityUptime,
            boolean firstFrameReported,
            boolean qualityCacheEmpty,
            long lastQualityRuntimeMs,
            boolean fastFramePending) {
        return shouldRunQualityRefinement(nowUptime, lastMotionUptime, lastQualityUptime,
                firstFrameReported, qualityCacheEmpty, lastQualityRuntimeMs,
                fastFramePending, false);
    }

    static boolean shouldRunQualityRefinement(
            long nowUptime,
            long lastMotionUptime,
            long lastQualityUptime,
            boolean firstFrameReported,
            boolean qualityCacheEmpty,
            long lastQualityRuntimeMs,
            boolean fastFramePending,
            boolean confirmationRequested) {
        if (fastFramePending) return false;
        if (lastMotionUptime > 0L
                && nowUptime - lastMotionUptime < QUALITY_MOTION_SETTLE_MS) return false;
        if (confirmationRequested) {
            return nowUptime - lastQualityUptime >= QUALITY_CONFIRMATION_INTERVAL_MS;
        }
        if (!firstFrameReported || qualityCacheEmpty) return true;
        long interval = lastQualityRuntimeMs >= QUALITY_SLOW_RUNTIME_MS
                ? QUALITY_SLOW_REFRESH_INTERVAL_MS : QUALITY_REFRESH_INTERVAL_MS;
        return nowUptime - lastQualityUptime >= interval;
    }

    static List<Detection> transactionalQualityCoverage(
            List<Detection> qualityCoverage,
            int pendingCandidates,
            boolean batchClosed) {
        if (qualityCoverage == null || qualityCoverage.isEmpty()) {
            return Collections.emptyList();
        }
        // VisualDetectionStabilizer has already confirmed every item in qualityCoverage. Pending
        // candidates are separate observations and must not hold unrelated stable regions hostage.
        if (!batchClosed) return qualityCoverage;
        List<Detection> linkedCoverage = new ArrayList<>();
        for (Detection detection : qualityCoverage) {
            if (detection != null && detection.getTrackId() >= 0) {
                linkedCoverage.add(detection);
            }
        }
        return linkedCoverage;
    }

    static boolean shouldReplaceQualityCache(
            int previousCount,
            int candidateCount,
            boolean completeScene) {
        if (Math.max(0, previousCount) == 0) return true;
        return Math.max(0, candidateCount) > 0 && completeScene;
    }

    static boolean usesContinuousMotionInference(DetectorConfig config) {
        return config != null && config.getInferenceThreads() >= 4;
    }

    static boolean usesStreamingQualityPipeline(DetectorConfig config) {
        return usesContinuousMotionInference(config)
                && config.getInferenceResolution() > FAST_INFERENCE_RESOLUTION;
    }

    /** HIGH and ULTRA have a distinct higher-resolution settled observation to join atomically. */
    static boolean usesAtomicScenePipeline(DetectorConfig config) {
        return config != null && config.getInferenceThreads() >= 3
                && config.getInferenceResolution() > FAST_INFERENCE_RESOLUTION;
    }

    static boolean shouldPreemptQualityForCapture(boolean qualityActive) {
        return qualityActive;
    }

    static int fastInferenceFrameResolution(
            DetectorConfig config,
            boolean fastDetectorReady,
            boolean sourceFrameRequired) {
        int configured = config == null
                ? FAST_INFERENCE_RESOLUTION : config.getInferenceResolution();
        return fastDetectorReady && !sourceFrameRequired
                && usesAtomicScenePipeline(config)
                ? FAST_INFERENCE_RESOLUTION : configured;
    }

    private static ScheduledExecutorService newScheduledWorker(
            String name, int androidPriority) {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(() -> {
                Process.setThreadPriority(androidPriority);
                runnable.run();
            }, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    static boolean shouldEstimateFrameMotion(
            DetectorConfig config,
            long nowUptime,
            long lastAccessibilityScrollUptime) {
        // Ultra continuously classifies each accepted Accessibility frame. A second global-motion
        // estimator can only double-apply movement already represented by scroll events and the
        // next detector frame. Retain the estimator solely as a conservative fallback for slower
        // presets/apps that expose no usable Accessibility scroll events.
        if (usesContinuousMotionInference(config)) return false;
        return lastAccessibilityScrollUptime <= 0L
                || nowUptime - lastAccessibilityScrollUptime >= 750L;
    }

    static boolean usesSemanticTextModel(DetectorConfig config) {
        return config != null && config.getInferenceThreads() >= 3;
    }

    static boolean usesScreenshotOcr(DetectorConfig config) {
        return config != null && config.getInferenceThreads() >= 4;
    }

    static long capturePollDelayMs(DetectorConfig config) {
        // Android rejects tighter Accessibility screenshots with
        // ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT. Scroll events own the real-time path.
        return ACCESSIBILITY_SCREENSHOT_INTERVAL_MS;
    }

    private boolean acceptScrollSurface(
            String surfaceKey,
            AccessibilitySurfaceIdentityResolver.Identity identity,
            long trustedAtUptime) {
        String safe = surfaceKey == null ? "" : surfaceKey.trim();
        if (safe.isEmpty()) return false;
        int removed;
        long document;
        boolean changed;
        boolean producerChanged;
        synchronized (worldCacheLock) {
            changed = !safe.equals(activeScrollSurfaceKey);
            int nextWindowId = identity == null ? -1 : identity.windowId;
            long nextTelemetryToken = identity == null ? 0L : identity.telemetryToken();
            producerChanged = !changed && (activeScrollSurfaceWindowId != nextWindowId
                    || activeScrollTelemetryToken != nextTelemetryToken);
            boolean confirmingProvisional = activeScrollSurfaceProvisional
                    && activeScrollSurfaceWindowId == nextWindowId
                    && identity != null && identity.isCacheable();
            if (producerChanged || changed && !confirmingProvisional) fenceScrollInputs("owner-change");
            removed = changed ? contentSpaceRegionCache.clear() : 0;
            if (changed || producerChanged) qualityBackfillCoordinator.clear();
            activeScrollSurfaceKey = safe;
            activeScrollSurfaceWindowId = nextWindowId;
            activeScrollTelemetryToken = nextTelemetryToken;
            activeScrollSurfaceProvisional = false;
            activeScrollSurfaceConfidence = identity == null
                    ? AccessibilitySurfaceIdentityResolver.CONFIDENCE_LOW
                    : identity.confidence;
            activeScrollSurfaceLastTrustedUptime = Math.max(0L, trustedAtUptime);
            activeScrollSurfaceLowReuseCount = 0;
            document = changed ? advanceVisualDocument()
                    : visualDocumentEpoch.get();
            if (changed) resetWorldCacheQueryLocked();
        }
        if (producerChanged) {
            cancelQualityRetrySchedule();
            qualityBackfillRunner.resetPolicyState();
            CensorLabLog.i(TAG, "QUALITY_BACKFILL_RESET reason=producer-change documentEpoch="
                    + document);
        }
        if (changed && !producerChanged) {
            cancelQualityRetrySchedule();
            qualityBackfillRunner.resetPolicyState();
        }
        if (!changed) return false;
        // A freshly identified surface must earn its own committed observations. Seeding it with
        // tracks from the previous surface is precisely how nested lists inherit stale boxes.
        invalidateCurrentScene("world-surface-change");
        CensorLabLog.i(TAG, "WORLD_CACHE_INVALIDATE reason=surface-change removed="
                + removed + " documentEpoch=" + document);
        return true;
    }

    private void disableWorldCacheForUnstableSurface(
            AccessibilitySurfaceIdentityResolver.Identity identity,
            String expectedActiveSurface) {
        int removed;
        long document;
        synchronized (worldCacheLock) {
            if (!Objects.equals(activeScrollSurfaceKey, expectedActiveSurface)) return;
            if (activeScrollSurfaceKey == null || activeScrollSurfaceKey.isEmpty()) return;
            fenceScrollInputs("unstable-owner");
            removed = contentSpaceRegionCache.clear();
            qualityBackfillCoordinator.clear();
            activeScrollSurfaceKey = "";
            activeScrollSurfaceWindowId = -1;
            activeScrollTelemetryToken = 0L;
            activeScrollSurfaceProvisional = false;
            activeScrollSurfaceConfidence = AccessibilitySurfaceIdentityResolver.CONFIDENCE_LOW;
            activeScrollSurfaceLastTrustedUptime = 0L;
            activeScrollSurfaceLowReuseCount = 0;
            document = advanceVisualDocument();
            resetWorldCacheQueryLocked();
        }
        invalidateCurrentScene("world-surface-low-confidence");
        cancelQualityRetrySchedule();
        qualityBackfillRunner.resetPolicyState();
        CensorLabLog.i(TAG, "WORLD_CACHE_INVALIDATE reason=surface-low-confidence removed="
                + removed + " documentEpoch=" + document
                + " confidence=" + (identity == null ? -1 : identity.confidence));
    }

    private static String cacheSurfaceKey(
            AccessibilitySurfaceIdentityResolver.Identity identity) {
        if (identity == null || !identity.isCacheable()) return "";
        return "h:" + Long.toUnsignedString(identity.tokenHi, 16)
                + ':' + Long.toUnsignedString(identity.tokenLo, 16);
    }

    private void invalidateWorldCache(String reason) {
        tapTracker.clear();
        main.post(() -> { if (overlay != null) overlay.clearPersonCoverage(); });
        int removed;
        long document;
        synchronized (worldCacheLock) {
            fenceScrollInputs("document-change");
            removed = contentSpaceRegionCache.clear();
            qualityBackfillCoordinator.clear();
            activeScrollSurfaceKey = "";
            activeScrollSurfaceWindowId = -1;
            activeScrollTelemetryToken = 0L;
            activeScrollSurfaceProvisional = false;
            activeScrollSurfaceConfidence = AccessibilitySurfaceIdentityResolver.CONFIDENCE_LOW;
            activeScrollSurfaceLastTrustedUptime = 0L;
            activeScrollSurfaceLowReuseCount = 0;
            document = advanceVisualDocument();
            resetWorldCacheQueryLocked();
        }
        // A structural reset starts a new document even if Android reuses the same application
        // window. Let the next screenshot establish a fresh document-scoped provisional surface;
        // low-confidence event rejection still keeps its non-structural no-reseed behavior.
        lastScrollTraceEventUptime = 0L;
        cancelQualityRetrySchedule();
        qualityBackfillRunner.resetPolicyState();
        invalidateCurrentScene("world-cache-" + reason);
        CensorLabLog.i(TAG, "WORLD_CACHE_INVALIDATE reason=" + reason
                + " removed=" + removed + " documentEpoch=" + document);
    }

    private long advanceVisualDocument() {
        return visualDocumentEpoch.incrementAndGet();
    }

    private void fenceScrollInputs(String reason) {
        scrollInputGeneration.incrementAndGet();
        if (scrollLookup != null) {
            if (scrollLookup.outstanding() > 1) CensorLabLog.i(TAG, "SCROLL_LOOKUP_GAP reason=" + reason);
            scrollLookup.invalidate();
        }
    }

    private boolean isCurrentVisualDocument(long documentEpoch, String surfaceKey) {
        String currentSurface = activeScrollSurfaceKey;
        return documentEpoch == visualDocumentEpoch.get()
                && (surfaceKey == null ? "" : surfaceKey).equals(
                currentSurface == null ? "" : currentSurface);
    }

    static ContentSpaceRegionCache.Evidence worldCacheEvidence(
            SceneTransactionCoordinator.CommitKind kind, boolean motionCurrent, long capturedAtUptime) {
        int lane = !motionCurrent || kind == null || kind == SceneTransactionCoordinator.CommitKind.ACTIVE_FAST
                ? ContentSpaceRegionCache.Evidence.NONE
                : kind == SceneTransactionCoordinator.CommitKind.SETTLED_FUSED
                        ? ContentSpaceRegionCache.Evidence.COMPLETE : ContentSpaceRegionCache.Evidence.FAST;
        return ContentSpaceRegionCache.Evidence.full(lane, capturedAtUptime);
    }

    private List<Detection> updateWorldCache(
            List<TrackedObject> liveTracks,
            long cameraX,
            long cameraY,
            int sourceWidth,
            int sourceHeight,
            int viewportWidth,
            int viewportHeight,
            ContentSpaceRegionCache.Evidence evidence,
            String source,
            long expectedDocument,
            String expectedSurface,
            long expectedMotionGeneration,
            List<Detection> lateQualityRegions,
            SpatialRegionCache.Frame spatialFrame) {
        if (spatialTrackingExperiment) return Collections.emptyList();
        String surface = expectedSurface == null ? "" : expectedSurface;
        if (surface.isEmpty() && !spatialCacheExperiment) return Collections.emptyList();
        List<ContentSpaceRegionCache.Observation> observations = new ArrayList<>();
        if (liveTracks != null) {
            for (TrackedObject track : liveTracks) {
                if (track == null || !track.isActive() || !track.isVisible()) continue;
                observations.add(new ContentSpaceRegionCache.Observation(
                        track.getId(), track.getClassName(), track.getCategory(),
                        track.getConfidence(), track.getRawBox(), true, false,
                        track.getFramesTracked(), track.getFramesMissing(),
                        false, null, false, track.getRenderSourceReference()));
            }
        }
        if (lateQualityRegions != null) {
            for (Detection detection : lateQualityRegions) {
                if (detection == null) continue;
                observations.add(new ContentSpaceRegionCache.Observation(
                        -1, detection.getClassName(), detection.getCategory(),
                        detection.getConfidence(), detection.getBox(),
                        detection.isNsfw(), detection.isExposed(),
                        0, 0, true, detection.getAnchorKey(), false, detection.getRenderSourceReference()));
            }
        }
        long now = SystemClock.uptimeMillis();
        if (spatialCacheExperiment) {
            synchronized (worldCacheLock) {
                if (!isCurrentVisualDocument(expectedDocument, surface)) return Collections.emptyList();
                if (spatialFrame == null || spatialFrame.viewportWidth != viewportWidth
                        || spatialFrame.viewportHeight != viewportHeight) return Collections.emptyList();
                SpatialRegionCache.WriteResult spatialWrite = spatialRegionCache.observeSourceWithStats(spatialFrame, now,
                        evidence.lane != ContentSpaceRegionCache.Evidence.NONE,
                        SpatialRegionCache.sourceObservations(spatialFrame, cameraX, cameraY, observations));
                ContentSpaceRegionCache.Update spatialUpdate = spatialWrite.update;
                CensorLabLog.i(TAG, "SPATIAL_CACHE_WRITE id=" + spatialFrame.id + " known=" + spatialWrite.known
                        + " input=" + spatialWrite.input + " crop=" + spatialWrite.cropRejected
                        + " unconfirmed=" + spatialWrite.unconfirmed + " faces=" + spatialWrite.faces
                        + " faceCrop=" + spatialWrite.faceCropRejected);
                List<Detection> spatialRegions = spatialRegionCache.querySource(spatialFrame, now);
                CensorLabLog.i(TAG, "SPATIAL_CACHE_QUERY id=" + spatialFrame.id
                        + " entries=" + spatialRegionCache.size() + " inserted=" + spatialUpdate.inserted
                        + " candidates=" + spatialRegions.size());
                return InferenceScrollReprojector.toCurrentViewport(
                        spatialRegions, sourceWidth, sourceHeight,
                        viewportWidth, viewportHeight, spatialFrame.eventX, spatialFrame.eventY, cameraX, cameraY);
            }
        }
        ContentSpaceRegionCache.Update update;
        List<Detection> cached;
        int entries;
        boolean cacheWriteAccepted;
        synchronized (worldCacheLock) {
            if (!isCurrentVisualDocument(expectedDocument, surface)) {
                return Collections.emptyList();
            }
            // Motion-surviving fast results are useful immediately, but their inferred position
            // must not become durable evidence while capture/event phase is still changing.
            cacheWriteAccepted = expectedMotionGeneration == motionGeneration.get();
            update = cacheWriteAccepted ? contentSpaceRegionCache.observeCommittedScene(
                    expectedDocument, surface, now, cameraX, cameraY,
                    sourceWidth, sourceHeight, viewportWidth, viewportHeight,
                    evidence, observations) : ContentSpaceRegionCache.Update.EMPTY;
            if (update.viewportReset) qualityBackfillCoordinator.clear();
            cached = contentSpaceRegionCache.queryNearAsScreenDetections(
                    expectedDocument, surface, now, cameraX, cameraY,
                    sourceWidth, sourceHeight, viewportWidth, viewportHeight);
            entries = contentSpaceRegionCache.size();
            rememberWorldCacheQueryLocked(
                    expectedDocument, surface, cameraX, cameraY);
        }
        CensorLabLog.i(TAG, "WORLD_CACHE_QUERY source=" + source
                + " entries=" + entries
                + " inserted=" + update.inserted
                + " updated=" + update.updated
                + " evicted=" + update.evicted
                + " viewportReset=" + update.viewportReset
                + " candidates=" + cached.size()
                + " camera=" + cameraX + ',' + cameraY
                + " documentEpoch=" + expectedDocument
                + " cacheWriteAccepted=" + cacheWriteAccepted
                + " sourceGeneration=" + expectedMotionGeneration);
        return cached;
    }

    private void resetWorldCacheQueryLocked() {
        lastWorldCacheQueryX = Long.MIN_VALUE;
        lastWorldCacheQueryY = Long.MIN_VALUE;
        lastWorldCacheQueryDocument = Long.MIN_VALUE;
        lastWorldCacheQuerySurface = "";
    }

    private void rememberWorldCacheQueryLocked(
            long document, String surface, long cameraX, long cameraY) {
        lastWorldCacheQueryDocument = document;
        lastWorldCacheQuerySurface = surface == null ? "" : surface;
        lastWorldCacheQueryX = cameraX;
        lastWorldCacheQueryY = cameraY;
    }

    private boolean shouldRefreshWorldCacheQueryLocked(
            long document,
            String surface,
            long cameraX,
            long cameraY,
            int viewportWidth,
            int viewportHeight) {
        if (contentSpaceRegionCache.size() == 0) return false;
        if (document != lastWorldCacheQueryDocument
                || !(surface == null ? "" : surface).equals(lastWorldCacheQuerySurface)) {
            return true;
        }
        long xThreshold = Math.max(96L, Math.max(1, viewportWidth) / 3L);
        long yThreshold = Math.max(96L, Math.max(1, viewportHeight) / 3L);
        return lastWorldCacheQueryX == Long.MIN_VALUE
                || Math.abs(cameraX - lastWorldCacheQueryX) >= xThreshold
                || Math.abs(cameraY - lastWorldCacheQueryY) >= yThreshold;
    }

    private boolean applyFrameMotion(
            int dx,
            int dy,
            long expectedGeneration,
            long effectiveUptimeMillis,
            long receivedUptimeMillis) {
        if (dx == 0 && dy == 0) return false;
        synchronized (scrollStateLock) {
            if (!motionGeneration.compareAndSet(expectedGeneration, expectedGeneration + 1L)) {
                return false;
            }
        }
        applyScrollMotion(dx, dy, false, false,
                effectiveUptimeMillis, receivedUptimeMillis);
        return true;
    }

    private void applyEventMotion(
            int dx,
            int dy,
            boolean allowPrediction,
            long effectiveUptimeMillis,
            long receivedUptimeMillis) {
        if (dx == 0 && dy == 0) return;
        synchronized (scrollStateLock) {
            motionGeneration.incrementAndGet();
        }
        // Establish the next screenshot as a fresh visual baseline. Otherwise the estimator sees
        // the same movement Android just reported and translates every censor a second time.
        if (motionEstimator != null) motionEstimator.reset();
        applyScrollMotion(dx, dy, true, allowPrediction,
                effectiveUptimeMillis, receivedUptimeMillis);
    }

    private void traceScrollEvent(
            long nowUptime,
            long sourceUptime,
            long eventAgeMs,
            int rawDx,
            int rawDy,
            int dx,
            int dy,
            String source,
            String evidence,
            int adjustedPixels,
            boolean amplified,
            long surfaceTelemetryToken,
            byte surfaceConfidence,
            boolean surfaceCacheable,
            byte observedSurfaceConfidence,
            ScrollSurfaceHysteresis.Decision surfaceDecision,
            long motionProducerToken) {
        long gap = lastScrollTraceEventUptime <= 0L
                ? Long.MAX_VALUE : nowUptime - lastScrollTraceEventUptime;
        if (gap > 250L) {
            scrollTraceId++;
            scrollTraceStartedUptime = nowUptime;
            CensorLabLog.i(TAG, "SCROLL_START id=" + scrollTraceId + " source=" + source);
        }
        lastScrollTraceEventUptime = nowUptime;
        CensorLabLog.i(TAG, "SCROLL_EVENT id=" + scrollTraceId + " source=" + source
                + " gapMs=" + (gap == Long.MAX_VALUE ? 0L : gap)
                + " eventAgeMs=" + eventAgeMs
                + " rawDx=" + rawDx + " rawDy=" + rawDy
                + " dx=" + dx + " dy=" + dy
                + " evidence=" + evidence
                + " adjustedPx=" + adjustedPixels
                + " amplified=" + amplified
                + " sourceUptimeMs=" + sourceUptime
                + " receivedUptimeMs=" + nowUptime
                + " surfaceToken=" + Long.toUnsignedString(surfaceTelemetryToken, 16)
                + " surfaceConfidence=" + surfaceConfidence
                + " surfaceCacheable=" + surfaceCacheable
                + " observedSurfaceConfidence=" + observedSurfaceConfidence
                + " surfaceDecision=" + (surfaceDecision == null
                        ? "UNKNOWN" : surfaceDecision.name())
                + " surfaceReuseAgeMs=" + (activeScrollSurfaceLastTrustedUptime <= 0L
                        ? -1L : Math.max(0L,
                                nowUptime - activeScrollSurfaceLastTrustedUptime))
                + " surfaceReuseCount=" + activeScrollSurfaceLowReuseCount
                + " motionToken=" + Long.toUnsignedString(motionProducerToken, 16)
                + " touchId=" + touchTraceId
                + " touchActive=" + touchInteractionActive
                + " sinceTouchStartMs=" + (touchInteractionActive
                        ? Math.max(0L, nowUptime - touchTraceStartedUptime) : -1L)
                + " documentEpoch=" + visualDocumentEpoch.get());
        main.removeCallbacks(settledScrollTrace);
        main.postDelayed(settledScrollTrace, MOTION_SETTLE_MS);
    }

    private void traceTouchInteraction(AccessibilityEvent event, boolean started) {
        long now = SystemClock.uptimeMillis();
        long source = event == null ? now : event.getEventTime();
        if (source <= 0L || source > now) source = now;
        if (started) {
            touchTraceId++;
            touchTraceStartedUptime = now;
            touchInteractionActive = true;
        }
        long duration = touchTraceStartedUptime <= 0L
                ? 0L : Math.max(0L, now - touchTraceStartedUptime);
        CensorLabLog.i(TAG, "CALIBRATION_TOUCH id=" + touchTraceId
                + " phase=" + (started ? "start" : "end")
                + " sourceUptimeMs=" + source
                + " receivedUptimeMs=" + now
                + " eventAgeMs=" + Math.max(0L, now - source)
                + " durationMs=" + duration);
        if (!started) touchInteractionActive = false;
    }

    private void applyScrollMotion(
            int dx,
            int dy,
            boolean alreadyOnMainThread,
            boolean allowPrediction,
            long effectiveUptimeMillis,
            long receivedUptimeMillis) {
        if (dx == 0 && dy == 0) return;
        qualityReservationUntilUptime.set(0L);
        wakeCaptureAt(SystemClock.uptimeMillis());
        // Continuous fast observations are reprojected before tracking and presentation. Closing
        // their transaction here discards useful work even when no newer capture exists.
        invalidateNonReprojectableSceneForMotion();
        clearLateQualityPresentation("motion");
        qualityVisualStabilizer.clear();
        qualityConfirmationRequested.set(false);
        qualityConfirmationBurstUsed.set(false);
        discardPendingQualityInference();
        VisualDetectionSnapshot invalidatedQuality = cachedQualityVisual;
        cachedQualityVisual = VisualDetectionSnapshot.EMPTY;
        if (invalidatedQuality != VisualDetectionSnapshot.EMPTY
                && !invalidatedQuality.detections.isEmpty()) {
            Log.i(TAG, "QUALITY_CACHE_INVALIDATED reason=motion detections="
                    + invalidatedQuality.detections.size()
                    + " ageMs=" + Math.max(0L, SystemClock.uptimeMillis()
                    - invalidatedQuality.capturedAtUptimeMillis)
                    + " generation=" + motionGeneration.get());
        }
        resetTextConfirmationForMotion();
        long receivedAt = Math.max(0L, receivedUptimeMillis);
        long effectiveAt = Math.max(0L, Math.min(effectiveUptimeMillis, receivedAt));
        lastMotionUptime = receivedAt;
        settledInferenceNeeded.set(true);
        // cumulativeScroll stores content-scroll direction; dx/dy are screen movement.
        synchronized (scrollStateLock) {
            cumulativeScrollX.addAndGet(-dx);
            cumulativeScrollY.addAndGet(-dy);
            pendingTrackerOffsetX.addAndGet(dx);
            pendingTrackerOffsetY.addAndGet(dy);
            captureScrollTimeline.record(effectiveAt, receivedAt,
                    -dx, -dy, motionGeneration.get());
        }
        offsetVisualGeometryHistory(dx, dy);
        if (!usesContinuousMotionInference(detectorConfig)) discardPendingInference();
        dwellTracker.onScroll();
        textRefreshRequested.set(true);
        invalidateAccessibilityTextSnapshot();
        invalidateOcrForMotion();
        main.removeCallbacks(settledTextRefresh);
        main.postDelayed(settledTextRefresh, SETTLED_SCROLL_REFRESH_MS);
        queueSettledCapture();
        long expectedMotionGeneration = motionGeneration.get();
        String cacheSurface = activeScrollSurfaceKey;
        long cacheDocument = visualDocumentEpoch.get();
        int cacheSourceWidth = latestCaptureWidth;
        int cacheSourceHeight = latestCaptureHeight;
        ScrollPosition cacheCamera = currentScrollPosition();
        Rect cacheViewport = screenBounds();
        List<Detection> reentryRegions = Collections.emptyList();
        boolean refreshedCacheWindow = false;
        SpatialRegionCache.Frame eventSourceFrame = null;
        if (spatialTrackingExperiment) {
            refreshedCacheWindow = true;
        } else if (spatialCacheExperiment) {
            SpatialRegionCache.Frame source = spatialRegionCache.latest();
            if (source != null && source.scope != null
                    && source.scope.captureEpoch == captureEpoch.token()
                    && source.scope.document == cacheDocument
                    && source.scope.window == activeApplicationWindowId.get()
                    && source.scope.sourceWidth == cacheSourceWidth
                    && source.scope.sourceHeight == cacheSourceHeight
                    && source.viewportWidth == cacheViewport.width()
                    && source.viewportHeight == cacheViewport.height()) {
                eventSourceFrame = source;
                reentryRegions = InferenceScrollReprojector.toCurrentViewport(
                        spatialRegionCache.querySource(source, SystemClock.uptimeMillis()),
                        cacheSourceWidth, cacheSourceHeight, cacheViewport.width(), cacheViewport.height(),
                        source.eventX, source.eventY, cacheCamera.scrollX, cacheCamera.scrollY);
            }
            refreshedCacheWindow = true;
        } else if (cacheSurface != null && !cacheSurface.isEmpty()
                && cacheSourceWidth > 0 && cacheSourceHeight > 0) {
            synchronized (worldCacheLock) {
                if (isCurrentVisualDocument(cacheDocument, cacheSurface)
                        && shouldRefreshWorldCacheQueryLocked(
                        cacheDocument, cacheSurface,
                        cacheCamera.scrollX, cacheCamera.scrollY,
                        cacheViewport.width(), cacheViewport.height())) {
                    reentryRegions = contentSpaceRegionCache.queryNearAsScreenDetections(
                            cacheDocument, cacheSurface, SystemClock.uptimeMillis(),
                            cacheCamera.scrollX, cacheCamera.scrollY,
                            cacheSourceWidth, cacheSourceHeight,
                            cacheViewport.width(), cacheViewport.height());
                    rememberWorldCacheQueryLocked(cacheDocument, cacheSurface,
                            cacheCamera.scrollX, cacheCamera.scrollY);
                    refreshedCacheWindow = true;
                }
            }
        }
        List<Detection> cachedForFrame = reentryRegions;
        boolean publishCacheWindow = refreshedCacheWindow;
        SpatialRegionCache.Frame queriedSpatialFrame = eventSourceFrame;
        Runnable moveOverlay = () -> {
            if (recognitionActive && overlay != null) {
                if (spatialCacheExperiment && publishCacheWindow
                        && queriedSpatialFrame != null
                        && queriedSpatialFrame.scope.window == activeApplicationWindowId.get()
                        && motionGeneration.get() == expectedMotionGeneration
                        && isCurrentVisualDocument(cacheDocument, cacheSurface)
                        && spatialRegionCache.retainAppliedCoverage(
                                queriedSpatialFrame, SystemClock.uptimeMillis())) {
                            // No new image is not the same as an unmatched new image. Move only
                            // coverage
                            // already delivered to this view; do not query or insert expired-source
                            // data.
                            overlay.offsetContent(dx, dy, allowPrediction, effectiveAt);
                    CensorLabLog.i(TAG, "SPATIAL_CACHE_HOLD id=" + spatialRegionCache.appliedFrameId());
                    return;
                }
                if (publishCacheWindow
                        && motionGeneration.get() == expectedMotionGeneration
                        && isCurrentVisualDocument(cacheDocument, cacheSurface)) {
                            // Both mutations occur in this UI callback. The View exposes their
                            // union only
                            // when Android draws the next frame; cache work cannot evict a real
                            // scene from
                            // the detector's latest-only presentation broker.
                    List<Detection> regionsForEvent = spatialCacheExperiment
                            ? spatialRegionCache.revalidatePresentation(queriedSpatialFrame,
                                    SystemClock.uptimeMillis(), cachedForFrame, cachedForFrame)
                            : cachedForFrame;
                    overlay.offsetContentWithWorldCache(
                            dx, dy, allowPrediction, effectiveAt, regionsForEvent,
                            cacheSourceWidth, cacheSourceHeight,
                            cacheCamera.scrollX, cacheCamera.scrollY,
                            cacheViewport.width(), cacheViewport.height());
                    if (spatialCacheExperiment) {
                        int admitted = overlay.admittedCachedRegionCount(regionsForEvent);
                        spatialRegionCache.markApplied(queriedSpatialFrame, SystemClock.uptimeMillis(),
                                admitted);
                        CensorLabLog.i(TAG, "SPATIAL_CACHE_APPLIED kind=event input=" + regionsForEvent.size()
                                + " admitted=" + admitted);
                    }
                    CensorLabLog.i(TAG, "WORLD_CACHE_REENTRY candidates="
                            + regionsForEvent.size()
                            + " generation=" + expectedMotionGeneration
                            + " camera=" + cacheCamera.scrollX + ',' + cacheCamera.scrollY
                            + " documentEpoch=" + cacheDocument);
                } else {
                    overlay.offsetContent(dx, dy, allowPrediction, effectiveAt);
                }
            }
        };
        if (alreadyOnMainThread || Looper.myLooper() == main.getLooper()) moveOverlay.run();
        else main.post(moveOverlay);
    }

    private void resetTextConfirmationForMotion() {
        accessibilityCandidateScans.set(0);
        cancelPendingTextConfirmation();
    }

    private void cancelPendingTextConfirmation() {
        AccessibilityTextSmutDetector.ScanResult pending =
                pendingTextConfirmation.getAndSet(null);
        if (pending != null) pending.close();
    }

    private synchronized void invalidateAccessibilityTextSnapshot() {
        TextDetectionSnapshot snapshot = cachedAccessibilityText;
        if (snapshot == TextDetectionSnapshot.EMPTY || snapshot.detections.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        if (accessibilityTextInvalidatedAtUptime <= snapshot.capturedAtUptimeMillis) {
            accessibilityTextInvalidatedAtUptime = now;
        }
        long delay = Math.max(0L, ACCESSIBILITY_TEXT_STALE_TTL_MS
                - (now - accessibilityTextInvalidatedAtUptime));
        main.removeCallbacks(staleAccessibilityTextExpiry);
        main.postDelayed(staleAccessibilityTextExpiry, delay);
    }

    private synchronized void cacheAccessibilityText(TextDetectionSnapshot snapshot) {
        cachedAccessibilityText = snapshot == null ? TextDetectionSnapshot.EMPTY : snapshot;
        accessibilityTextInvalidatedAtUptime = 0L;
        main.removeCallbacks(staleAccessibilityTextExpiry);
    }

    /** Capture geometry and its consumed camera together; pending motion is not applied here. */
    private List<TrackedObject> identityTracksForSource(
            int width, int height, long sourceCameraX, long sourceCameraY) {
        ObjectTracker currentTracker = tracker;
        if (currentTracker == null) return Collections.emptyList();
        Rect viewport = screenBounds();
        synchronized (currentTracker) {
            return TrackerPhaseProjection.forSource(currentTracker.activeTracks(),
                    trackerScrollX, trackerScrollY, sourceCameraX, sourceCameraY,
                    width, height, viewport.width(), viewport.height());
        }
    }

    private ScrollAlignment consumeTrackerMotion(int width, int height, SpatialRegionCache.Frame frame,
            long sourceX, long sourceY, List<Detection> detections, boolean reproject) {
        long scrollX;
        long scrollY;
        int dx;
        int dy;
        long correctionCpuStarted = spatialTrackingExperiment ? android.os.Debug.threadCpuTimeNanos() : 0;
        SourceTrackContinuity.Proposal proposal = SourceTrackContinuity.Proposal.EMPTY;
        synchronized (scrollStateLock) {
            scrollX = cumulativeScrollX.get();
            scrollY = cumulativeScrollY.get();
            dx = saturatingInt(pendingTrackerOffsetX.getAndSet(0L));
            dy = saturatingInt(pendingTrackerOffsetY.getAndSet(0L));
        }
        if (tracker != null) {
            synchronized (tracker) {
                if (correctSpatialTracks) {
                    Rect viewport = screenBounds();
                    if (frame != null && frame.scope != null
                            && frame.viewportWidth == viewport.width() && frame.viewportHeight == viewport.height()
                            && isCurrentCapture(frame.scope.captureEpoch)
                            && frame.scope.document == visualDocumentEpoch.get()
                            && frame.scope.window == activeApplicationWindowId.get()) {
                        List<Detection> aligned = reproject ? InferenceScrollReprojector.toCurrentViewport(
                                detections, width, height, viewport.width(), viewport.height(),
                                sourceX, sourceY, scrollX, scrollY) : detections;
                        proposal = sourceTrackContinuity.propose(frame, sourceX, sourceY,
                                trackerScrollY, scrollY, dx, dy, width, height, tracker.activeTracks(),
                                aligned, detectorConfig == null ? 1f : detectorConfig.getConfidenceThreshold());
                    }
                }
                // If a subsequent generation guard aborts update, never reuse the pre-mutation
                // basis.
                if (spatialTrackingExperiment) sourceTrackContinuity.clear();
                tracker.offsetActiveTracks(dx, dy, width, height, proposal.offsets);
                trackerScrollX = scrollX;
                trackerScrollY = scrollY;
            }
        } else {
            trackerScrollX = scrollX;
            trackerScrollY = scrollY;
        }
        return new ScrollAlignment(scrollX, scrollY, proposal, spatialTrackingExperiment
                ? (android.os.Debug.threadCpuTimeNanos() - correctionCpuStarted) / 1000 : 0);
    }

    /** Atomically detaches renderer geometry from its tracker-camera coordinate phase. */
    private WorldTrackFrame captureWorldTrackFrame() {
        ObjectTracker currentTracker = tracker;
        if (currentTracker == null) {
            VisualTrackArbitrator.Result empty = visualRenderTracks(Collections.emptyList());
            return new WorldTrackFrame(empty, trackerScrollX, trackerScrollY,
                    motionGeneration.get());
        }
        synchronized (currentTracker) {
            VisualTrackArbitrator.Result arbitration = visualRenderTracks(
                    currentTracker.activeTracks());
            return new WorldTrackFrame(arbitration, trackerScrollX, trackerScrollY,
                    motionGeneration.get());
        }
    }

    private ScrollPosition currentScrollPosition() {
        synchronized (scrollStateLock) {
            return new ScrollPosition(cumulativeScrollX.get(), cumulativeScrollY.get());
        }
    }

    private static int saturatingInt(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    private void discardPendingInference() {
        InferenceFrame pending = pendingInference.getAndSet(null);
        if (pending != null) {
            invalidateScene(pending.scene, "fast-pending-discarded");
            pending.recycle();
        }
    }

    private void discardPendingQualityInference() {
        // With a CPU-fast + NNAPI-quality split, motion turns the result into world-space backfill;
        // it does not cancel useful work. Serialized fallback devices still yield to fast.
        if (!concurrentQualityAllowed(SystemClock.uptimeMillis())) {
            preemptQualityInference("quality-invalidated");
        }
    }

    private void clearQualityBackfill() {
        cancelQualityRetrySchedule();
        qualityBackfillRunner.clear();
        qualityBackfillRunner.resetPolicyState();
        qualityTilePassSequence.set(0L);
        qualityReservationUntilUptime.set(0L);
        wakeCaptureAt(SystemClock.uptimeMillis());
        clearLateQualityPresentation("structural-clear");
        preemptQualityInference("quality-cleared");
    }

    /** Coalesces movement into the earliest screenshot Android's platform gate will accept. */
    private void queueSettledCapture() {
        ScheduledExecutorService captureWorker = worker;
        if (!running || !recognitionActive || captureWorker == null
                || captureWorker.isShutdown()) return;
        // A settled request shares admission with regular capture; it cannot shift a poll phase.
        long now = SystemClock.uptimeMillis();
        wakeCaptureAt(now + settledCaptureDelayMs(now, lastMotionUptime,
                Math.max(0L, screenshotScheduler.lastDispatchMillis())));
    }

    static long settledCaptureDelayMs(
            long nowUptime, long lastMotionUptime, long lastRequestUptime) {
        long motionWait = MOTION_SETTLE_MS - (nowUptime - lastMotionUptime);
        long platformWait = ACCESSIBILITY_SCREENSHOT_INTERVAL_MS
                - (nowUptime - lastRequestUptime);
        return Math.max(0L, Math.max(motionWait, platformWait));
    }

    private Rect screenBounds() {
        WindowManager manager = getSystemService(WindowManager.class);
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = manager.getMaximumWindowMetrics().getBounds();
            if (!bounds.isEmpty()) return new Rect(0, 0, bounds.width(), bounds.height());
        }
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        return new Rect(0, 0, Math.max(1, metrics.widthPixels), Math.max(1, metrics.heightPixels));
    }

    private AccessibilityCaptureScheduler.Cancellation scheduleCaptureTask(
            Runnable task, long delayMillis) {
        ScheduledExecutorService captureWorker = worker;
        if (captureWorker == null || captureWorker.isShutdown()) {
            throw new RejectedExecutionException("Accessibility capture worker stopped");
        }
        ScheduledFuture<?> scheduled = captureWorker.schedule(task, delayMillis,
                TimeUnit.MILLISECONDS);
        return () -> scheduled.cancel(false);
    }

    private void wakeCaptureAt(long deadline) {
        if (!running || !recognitionActive) return;
        try {
            screenshotScheduler.wakeAt(deadline);
        } catch (RejectedExecutionException stopped) {
            Log.i(TAG, "CAPTURE_SCHEDULE_SKIP reason=worker-stopped");
        }
    }

    private void finishScreenshotRequest(long requestId) {
        finishScreenshotRequest(requestId, SystemClock.uptimeMillis());
    }

    private void finishScreenshotRequest(long requestId, long notBeforeMillis) {
        try {
            if (screenshotScheduler.finish(requestId, notBeforeMillis)) {
                traceCaptureAdmission("complete", requestId, "released");
            }
        } catch (RejectedExecutionException stopped) {
            Log.i(TAG, "CAPTURE_SCHEDULE_SKIP reason=worker-stopped");
        }
    }

    private void traceCaptureAdmission(String action, long requestId, String reason) {
        long observedUptime = "dispatch".equals(action)
                ? screenshotScheduler.lastDispatchMillis() : SystemClock.uptimeMillis();
        CensorLabLog.i(TAG, "CAPTURE_ADMISSION v=1 action=" + action
                + " requestId=" + requestId + " uptimeMs=" + observedUptime
                + " eligibleMs=" + screenshotScheduler.nextEligibleMillis()
                + " inFlight=" + screenshotScheduler.inFlight() + " reason=" + reason);
    }

    private void requestTextRefresh() {
        TextSmutConfig config = textSmutConfig;
        if (!running || !recognitionActive || textWorker == null || config == null
                || !config.isEnabled() || !textRefreshRequested.get()) return;
        long nowUptime = SystemClock.uptimeMillis();
        long contentWait = contentTextRefreshDelayMs(
                nowUptime, lastTextContentChangeUptime, textContentBurstStartedUptime);
        if (contentWait > 0L) {
            main.removeCallbacks(settledTextRefresh);
            main.postDelayed(settledTextRefresh, contentWait);
            return;
        }
        long wait = textScanStartDelayMs(
                nowUptime, lastMotionUptime,
                System.currentTimeMillis(), lastTextRefreshMillis);
        if (wait > 0L) {
            main.removeCallbacks(settledTextRefresh);
            main.postDelayed(settledTextRefresh, wait);
            return;
        }
        if (!textRefreshRunning.compareAndSet(false, true)) return;
        cancelPendingTextConfirmation();
        textRefreshRequested.set(false);
        long epoch = captureEpoch.token();
        int captureWidth = latestCaptureWidth;
        int captureHeight = latestCaptureHeight;
        textWorker.execute(() -> {
            long scanStartedUptime = SystemClock.uptimeMillis();
            long scanMotionGeneration = motionGeneration.get();
            long scanSceneGeneration = textSceneGeneration.get();
            long scanContentGeneration = textContentGeneration.get();
            int contentEventsAtStart = textContentEvents.getAndSet(0);
            int contentTypesAtStart = textContentChangeTypes.getAndSet(0);
            long contentChangedAtStart = lastTextContentChangeUptime;
            ScrollPosition scanScroll = currentScrollPosition();
            AccessibilityNodeInfo root = null;
            AccessibilityTextSmutDetector.ScanResult scan = null;
            try {
                root = accessibilityTextRoot();
                long rootReadyUptime = SystemClock.uptimeMillis();
                if (root == null || !isCurrentCapture(epoch)) return;
                Rect screen = screenBounds();
                scan = accessibilityText.detectWithMetrics(
                        root, config, screen.width(), screen.height(),
                        usesSemanticTextModel(detectorConfig),
                        usesScreenshotOcr(detectorConfig),
                        () -> !isCurrentCapture(epoch)
                                || config != textSmutConfig
                                || motionGeneration.get() != scanMotionGeneration
                                || textSceneGeneration.get() != scanSceneGeneration);
                long detectionCompleteUptime = SystemClock.uptimeMillis();
                List<Detection> mapped = TextDetectionCoordinateMapper.screenToCapture(
                        scan.getDetections(), screen.width(), screen.height(),
                        captureWidth, captureHeight);
                long mappedUptime = SystemClock.uptimeMillis();
                if (!isCurrentCapture(epoch) || config != textSmutConfig) return;
                long currentMotionGeneration = motionGeneration.get();
                long currentContentGeneration = textContentGeneration.get();
                ScrollPosition currentScroll = currentScrollPosition();
                if (!shouldPublishTextScan(
                        scanMotionGeneration, currentMotionGeneration,
                        scanScroll.scrollX, scanScroll.scrollY,
                        currentScroll.scrollX, currentScroll.scrollY)
                        || textSceneGeneration.get() != scanSceneGeneration) {
                    textRefreshRequested.set(true);
                    CensorLabLog.i(TAG, "TEXT_SCAN discarded=stale candidates=" + mapped.size()
                            + " durationMs=" + (mappedUptime - scanStartedUptime)
                            + " rootMs=" + (rootReadyUptime - scanStartedUptime)
                            + " detectMs=" + (detectionCompleteUptime - rootReadyUptime)
                            + " mapMs=" + (mappedUptime - detectionCompleteUptime)
                            + " visited=" + scan.getVisitedNodes()
                            + " textNodes=" + scan.getTextNodes()
                            + " classified=" + scan.getClassifiedNodes()
                            + " cancelled=" + scan.isCancelled()
                            + " generation=" + scanMotionGeneration + "->"
                            + currentMotionGeneration
                            + " sceneGeneration=" + scanSceneGeneration + "->"
                            + textSceneGeneration.get()
                            + " contentEvents=" + contentEventsAtStart
                            + " contentTypes=" + contentTypesAtStart);
                    return;
                }
                long contentQuietMs = Math.max(0L,
                        mappedUptime - lastTextContentChangeUptime);
                boolean contentChangedDuringScan =
                        currentContentGeneration != scanContentGeneration;
                if (contentChangedDuringScan
                        && contentQuietMs < CONTENT_TEXT_REFRESH_MS
                        && textContentStaleRetries.getAndIncrement() < 1) {
                    textRefreshRequested.set(true);
                    CensorLabLog.i(TAG, "TEXT_SCAN discarded=content-active candidates=" + mapped.size()
                            + " durationMs=" + (mappedUptime - scanStartedUptime)
                            + " contentGeneration=" + scanContentGeneration + "->"
                            + currentContentGeneration
                            + " contentQuietMs=" + contentQuietMs);
                    return;
                }
                textContentStaleRetries.set(0);
                boolean bestEffortContentSnapshot = contentChangedDuringScan;
                boolean bridgeConfirmedMiss = shouldBridgeTextMisses(
                        scanStartedUptime, lastMotionUptime,
                        Math.max(contentChangedAtStart, lastTextContentChangeUptime))
                        && !bestEffortContentSnapshot;
                TextDetectionStabilizer.UpdateResult scene =
                        accessibilityTextStabilizer.updateWithMetrics(
                        mapped, bridgeConfirmedMiss);
                int candidateScan = accessibilityCandidateScans.incrementAndGet();
                accessibilityTextCandidatesPresent = !mapped.isEmpty();
                if (accessibilityTextCandidatesPresent) clearCachedOcr();
                DiagnosticsRepository.recordAccessibilityText(
                        DIAGNOSTICS_MODE, mapped.size(),
                        scene.getStableDetections().size());
                CensorLabLog.i(TAG, "TEXT_SCAN accepted candidates=" + mapped.size()
                        + " stable=" + scene.getStableDetections().size()
                        + " pending=" + scene.getPendingCandidates()
                        + " present=" + scene.getConfirmedPresent()
                        + " bridged=" + scene.getBridgedConfirmed()
                        + " bridgeMiss=" + bridgeConfirmedMiss
                        + " durationMs=" + (mappedUptime - scanStartedUptime)
                        + " rootMs=" + (rootReadyUptime - scanStartedUptime)
                        + " detectMs=" + (detectionCompleteUptime - rootReadyUptime)
                        + " mapMs=" + (mappedUptime - detectionCompleteUptime)
                        + " visited=" + scan.getVisitedNodes()
                        + " textNodes=" + scan.getTextNodes()
                        + " classified=" + scan.getClassifiedNodes()
                        + " probes=" + scan.getConfirmationProbeCount()
                        + " contentGeneration=" + scanContentGeneration + "->"
                        + currentContentGeneration
                        + " contentQuietMs=" + contentQuietMs
                        + " bestEffort=" + bestEffortContentSnapshot
                        + " contentEvents=" + contentEventsAtStart
                        + " contentTypes=" + contentTypesAtStart);
                lastTextRefreshMillis = System.currentTimeMillis();
                boolean targetedConfirmation = scene.getPendingCandidates() > 0
                        && scan.getConfirmationProbeCount() > 0
                        && !bestEffortContentSnapshot;
                if (targetedConfirmation) {
                    scheduleTextCandidateConfirmation(scan, config, epoch,
                            screen.width(), screen.height(), captureWidth, captureHeight,
                            scanMotionGeneration, scanSceneGeneration,
                            scanContentGeneration, scanScroll);
                    scan = null;
                } else if (scene.getPendingCandidates() == 0) {
                    cacheAccessibilityText(new TextDetectionSnapshot(
                            scene.getStableDetections(), captureWidth, captureHeight,
                            scanScroll.scrollX, scanScroll.scrollY));
                    publishTextLane(epoch, "accessibility",
                            scanMotionGeneration, scanSceneGeneration,
                            currentContentGeneration);
                }
                if (!targetedConfirmation && scene.getPendingCandidates() > 0
                        && (candidateScan < 2 || bestEffortContentSnapshot)) {
                    textRefreshRequested.set(true);
                    main.postDelayed(settledTextRefresh, MIN_TEXT_REFRESH_MS);
                }
            } finally {
                if (root != null) root.recycle();
                if (scan != null) scan.close();
                textRefreshRunning.set(false);
                if (textRefreshRequested.get()) {
                    long retryNowUptime = SystemClock.uptimeMillis();
                    long retryDelay = textRefreshDelayAfterMotion(
                            retryNowUptime, lastMotionUptime);
                    main.removeCallbacks(settledTextRefresh);
                    main.postDelayed(settledTextRefresh, retryDelay);
                }
            }
        });
    }

    private void scheduleTextCandidateConfirmation(
            AccessibilityTextSmutDetector.ScanResult scan,
            TextSmutConfig config,
            long epoch,
            int screenWidth,
            int screenHeight,
            int captureWidth,
            int captureHeight,
            long scanMotionGeneration,
            long scanSceneGeneration,
            long scanContentGeneration,
            ScrollPosition scanScroll) {
        ScheduledExecutorService executor = textWorker;
        if (executor == null || executor.isShutdown()) {
            scan.close();
            return;
        }
        AccessibilityTextSmutDetector.ScanResult replaced =
                pendingTextConfirmation.getAndSet(scan);
        if (replaced != null && replaced != scan) replaced.close();
        try {
            executor.schedule(() -> {
                if (!pendingTextConfirmation.compareAndSet(scan, null)) return;
                if (!textRefreshRunning.compareAndSet(false, true)) {
                    scan.close();
                    textRefreshRequested.set(true);
                    main.post(settledTextRefresh);
                    return;
                }
                long startedUptime = SystemClock.uptimeMillis();
                try {
                    ScrollPosition currentScroll = currentScrollPosition();
                    if (config != textSmutConfig || !isCurrentCapture(epoch)
                            || textSceneGeneration.get() != scanSceneGeneration
                            || textContentGeneration.get() != scanContentGeneration
                            || !shouldPublishTextScan(
                            scanMotionGeneration, motionGeneration.get(),
                            scanScroll.scrollX, scanScroll.scrollY,
                            currentScroll.scrollX, currentScroll.scrollY)) {
                        return;
                    }
                    AccessibilityTextSmutDetector.ConfirmResult confirmation =
                            accessibilityText.confirmCandidates(
                                    scan, config, screenWidth, screenHeight,
                                    usesSemanticTextModel(detectorConfig),
                                    usesScreenshotOcr(detectorConfig),
                                    () -> !isCurrentCapture(epoch)
                                            || motionGeneration.get() != scanMotionGeneration
                                            || textContentGeneration.get()
                                                    != scanContentGeneration
                                            || textSceneGeneration.get()
                                                    != scanSceneGeneration);
                    if (confirmation.isCancelled() || !isCurrentCapture(epoch)) return;
                    List<Detection> mapped = TextDetectionCoordinateMapper.screenToCapture(
                            confirmation.getDetections(), screenWidth, screenHeight,
                            captureWidth, captureHeight);
                    currentScroll = currentScrollPosition();
                    if (!shouldPublishTextScan(
                            scanMotionGeneration, motionGeneration.get(),
                            scanScroll.scrollX, scanScroll.scrollY,
                            currentScroll.scrollX, currentScroll.scrollY)
                            || textContentGeneration.get() != scanContentGeneration
                            || textSceneGeneration.get() != scanSceneGeneration) {
                        return;
                    }
                    TextDetectionStabilizer.UpdateResult scene =
                            accessibilityTextStabilizer.confirmSubset(mapped);
                    cacheAccessibilityText(new TextDetectionSnapshot(
                            scene.getStableDetections(), captureWidth, captureHeight,
                            scanScroll.scrollX, scanScroll.scrollY));
                    DiagnosticsRepository.recordAccessibilityText(
                            DIAGNOSTICS_MODE, confirmation.getConfirmedNodes(),
                            scene.getStableDetections().size());
                    Log.i(TAG, "TEXT_CONFIRM targeted confirmed="
                            + confirmation.getConfirmedNodes()
                            + " promoted=" + scene.getNewlyConfirmedCandidates()
                            + " stable=" + scene.getStableDetections().size()
                            + " pending=" + scene.getPendingCandidates()
                            + " attempted=" + confirmation.getAttemptedNodes()
                            + " refreshed=" + confirmation.getRefreshedNodes()
                            + " durationMs="
                            + (SystemClock.uptimeMillis() - startedUptime));
                    publishTextLane(epoch, "accessibility-targeted",
                            scanMotionGeneration, scanSceneGeneration,
                            scanContentGeneration);
                    lastTextRefreshMillis = System.currentTimeMillis();
                    if (scene.getPendingCandidates() > 0
                            && accessibilityCandidateScans.get() < 2) {
                        textRefreshRequested.set(true);
                    }
                } finally {
                    scan.close();
                    textRefreshRunning.set(false);
                    if (textRefreshRequested.get()) {
                        main.removeCallbacks(settledTextRefresh);
                        main.post(settledTextRefresh);
                    }
                }
            }, TEXT_CANDIDATE_CONFIRM_MS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException error) {
            pendingTextConfirmation.compareAndSet(scan, null);
            scan.close();
            Log.w(TAG, "Could not schedule targeted text confirmation", error);
        }
    }

    static boolean shouldPublishTextScan(
            long sampledMotionGeneration,
            long currentMotionGeneration,
            long sampledScrollX,
            long sampledScrollY,
            long currentScrollX,
            long currentScrollY) {
        return sampledMotionGeneration == currentMotionGeneration
                && sampledScrollX == currentScrollX
                && sampledScrollY == currentScrollY;
    }

    static boolean shouldBridgeTextMisses(
            long scanStartedUptime,
            long lastMotionUptime,
            long lastContentChangeUptime) {
        boolean motionSettled = lastMotionUptime <= 0L
                || scanStartedUptime - lastMotionUptime >= POST_SCROLL_TEXT_RECONCILE_MS;
        boolean contentSettled = lastContentChangeUptime <= 0L
                || scanStartedUptime - lastContentChangeUptime >= POST_SCROLL_TEXT_RECONCILE_MS;
        return motionSettled && contentSettled;
    }

    private AccessibilityNodeInfo accessibilityTextRoot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return getRootInActiveWindow(
                    AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST);
        }
        return getRootInActiveWindow();
    }

    static long textRefreshDelayAfterMotion(long nowUptime, long lastMotionUptime) {
        if (lastMotionUptime <= 0L) return 0L;
        return Math.max(0L,
                SETTLED_SCROLL_REFRESH_MS - (nowUptime - lastMotionUptime));
    }

    static long textScanStartDelayMs(
            long nowUptime,
            long lastMotionUptime,
            long nowMillis,
            long lastRefreshMillis) {
        long motionDelay = textRefreshDelayAfterMotion(nowUptime, lastMotionUptime);
        long cadenceDelay = Math.max(0L,
                MIN_TEXT_REFRESH_MS - (nowMillis - lastRefreshMillis));
        return Math.max(motionDelay, cadenceDelay);
    }

    static long contentTextRefreshDelayMs(
            long nowUptime,
            long lastContentChangeUptime,
            long contentBurstStartedUptime) {
        if (lastContentChangeUptime <= 0L || nowUptime < lastContentChangeUptime) return 0L;
        long quietDelay = Math.max(0L,
                CONTENT_TEXT_REFRESH_MS - (nowUptime - lastContentChangeUptime));
        if (contentBurstStartedUptime <= 0L || nowUptime < contentBurstStartedUptime) {
            return quietDelay;
        }
        long maximumDelay = Math.max(0L,
                CONTENT_TEXT_MAX_DEBOUNCE_MS - (nowUptime - contentBurstStartedUptime));
        return Math.min(quietDelay, maximumDelay);
    }

    static boolean isAccessibilityTextSnapshotFresh(
            long nowUptime,
            long capturedAtUptime,
            long invalidatedAtUptime) {
        if (capturedAtUptime <= 0L || nowUptime < capturedAtUptime) return false;
        if (invalidatedAtUptime <= capturedAtUptime) return true;
        return nowUptime >= invalidatedAtUptime
                && nowUptime - invalidatedAtUptime <= ACCESSIBILITY_TEXT_STALE_TTL_MS;
    }

    private List<Detection> cachedTextForFrame(
            int width, int height, long requestedScrollX, long requestedScrollY) {
        long nowUptime = SystemClock.uptimeMillis();
        TextDetectionSnapshot accessibilitySnapshot = cachedAccessibilityText;
        List<Detection> accessibility = isAccessibilityTextSnapshotFresh(
                nowUptime, accessibilitySnapshot.capturedAtUptimeMillis,
                accessibilityTextInvalidatedAtUptime)
                ? shiftTextSource(accessibilitySnapshot,
                        width, height, requestedScrollX, requestedScrollY)
                : Collections.emptyList();
        TextDetectionSnapshot ocrSnapshot = cachedOcrText;
        List<Detection> ocr = isOcrSnapshotFresh(
                nowUptime, ocrSnapshot.capturedAtUptimeMillis)
                ? shiftTextSource(ocrSnapshot,
                        width, height, requestedScrollX, requestedScrollY)
                : Collections.emptyList();
        return DetectionFusion.merge(Collections.emptyList(), accessibility, ocr);
    }

    private List<Detection> cachedQualityForFrame(
            int width, int height, long requestedScrollX, long requestedScrollY) {
        VisualDetectionSnapshot snapshot = cachedQualityVisual;
        boolean streaming = usesStreamingQualityPipeline(detectorConfig);
        long maximumAgeMs = streaming
                ? STREAMING_QUALITY_RESULT_TTL_MS : QUALITY_RESULT_TTL_MS;
        if (snapshot == VisualDetectionSnapshot.EMPTY
                || SystemClock.uptimeMillis() - snapshot.capturedAtUptimeMillis
                        > maximumAgeMs) {
            return Collections.emptyList();
        }
        long currentGeneration = motionGeneration.get();
        if (!isQualityCacheGenerationCurrent(snapshot.motionGeneration, currentGeneration)) {
            if (cachedQualityVisual == snapshot) cachedQualityVisual = VisualDetectionSnapshot.EMPTY;
            Log.i(TAG, "QUALITY_CACHE_REJECTED reason=motion-generation cacheGeneration="
                    + snapshot.motionGeneration + " currentGeneration=" + currentGeneration
                    + " detections=" + snapshot.detections.size());
            return Collections.emptyList();
        }
        return shiftDetectionSource(snapshot.detections,
                snapshot.width, snapshot.height, snapshot.scrollX, snapshot.scrollY,
                width, height, requestedScrollX, requestedScrollY);
    }

    static boolean isQualityCacheGenerationCurrent(
            long cacheMotionGeneration,
            long currentMotionGeneration) {
        return cacheMotionGeneration == currentMotionGeneration;
    }

    private static int visibleRenderTrackCount(
            List<TrackedObject> tracks,
            int width,
            int height) {
        if (tracks == null || tracks.isEmpty()) return 0;
        int safeWidth = Math.max(1, width);
        int safeHeight = Math.max(1, height);
        int visible = 0;
        for (TrackedObject track : tracks) {
            if (track == null || track.getRawBox() == null) continue;
            BBox box = track.getRawBox();
            if (box.getRight() > 0 && box.getBottom() > 0
                    && box.getX() < safeWidth && box.getY() < safeHeight) {
                visible++;
            }
        }
        return visible;
    }

    private static List<Detection> markQualityCoverage(List<Detection> detections) {
        if (detections == null || detections.isEmpty()) return Collections.emptyList();
        List<Detection> marked = new ArrayList<>(detections.size());
        for (Detection detection : detections) {
            if (detection == null) continue;
            marked.add(detection.withObservation(
                    Detection.ObservationSource.QUALITY_VISUAL,
                    detection.getGeometryQuality(),
                    detection.getAnchorKey()));
        }
        return Collections.unmodifiableList(marked);
    }

    /** Partial quality overlap still contributes coverage; rendering handles consolidation. */
    private static List<Detection> uncoveredLateQualityRegions(
            List<Detection> quality,
            List<TrackedObject> live) {
        return QualityPresentationAligner.uncovered(quality, live);
    }

    private void publishTextLane(long epoch, String source) {
        publishTextLane(epoch, source, -1L, -1L, -1L);
    }

    private void publishTextLane(
            long epoch,
            String source,
            long expectedMotionGeneration,
            long expectedSceneGeneration) {
        publishTextLane(epoch, source, expectedMotionGeneration, expectedSceneGeneration, -1L);
    }

    private void publishTextLane(
            long epoch,
            String source,
            long expectedMotionGeneration,
            long expectedSceneGeneration,
            long expectedContentGeneration) {
        long sourceTapScope = tapTracker.scope();
        main.post(() -> {
            if (!isCurrentCapture(epoch) || overlay == null) return;
            if ((expectedMotionGeneration >= 0L
                    && motionGeneration.get() != expectedMotionGeneration)
                    || (expectedSceneGeneration >= 0L
                    && textSceneGeneration.get() != expectedSceneGeneration)
                    || (expectedContentGeneration >= 0L
                    && textContentGeneration.get() != expectedContentGeneration)) {
                CensorLabLog.i(TAG, "TEXT_PUBLISH skipped=stale source=" + source);
                return;
            }
            int width = Math.max(1, latestCaptureWidth);
            int height = Math.max(1, latestCaptureHeight);
            ScrollPosition current = currentScrollPosition();
            List<Detection> detections = cachedTextForFrame(
                    width, height, current.scrollX, current.scrollY);
            String fingerprint = width + "x" + height + '@'
                    + current.scrollX + ',' + current.scrollY + '|'
                    + detectionFingerprint(detections, false);
            if (fingerprint.equals(lastPublishedTextFingerprint)) {
                skippedUnchangedTextPublishes++;
                return;
            }
            lastPublishedTextFingerprint = fingerprint;
            Rect viewport = screenBounds();
            overlay.setTapSource(CensorLabCensorSession.isActive(), sourceTapScope);
            overlay.updateWorldText(detections, width, height,
                    current.scrollX, current.scrollY,
                    viewport.width(), viewport.height());
            long now = SystemClock.uptimeMillis();
            CensorLabLog.i(TAG, "TEXT_PUBLISH source=" + source + " regions=" + detections.size()
                    + " unchangedSkipped=" + skippedUnchangedTextPublishes
                    + " afterMotionMs=" + (lastMotionUptime <= 0L
                            ? 0L : now - lastMotionUptime));
            skippedUnchangedTextPublishes = 0L;
        });
    }

    private static String detectionFingerprint(
            List<Detection> detections,
            boolean coarseGeometry) {
        if (detections == null || detections.isEmpty()) return "empty";
        List<String> parts = new ArrayList<>(detections.size());
        for (Detection detection : detections) {
            if (detection == null) continue;
            BBox box = detection.getBox();
            String anchor = detection.getAnchorKey();
            if (coarseGeometry && anchor != null && !anchor.isEmpty()) {
                parts.add(detection.getClassName() + '@' + anchor);
            } else {
                int x = coarseGeometry ? box.getX() / 32 : box.getX();
                int y = coarseGeometry ? box.getY() / 24 : box.getY();
                int width = coarseGeometry ? box.getWidth() / 32 : box.getWidth();
                int height = coarseGeometry ? box.getHeight() / 16 : box.getHeight();
                parts.add(detection.getClassName() + '@' + (anchor == null ? "" : anchor)
                        + ':' + x + ',' + y + ',' + width + ',' + height);
            }
        }
        Collections.sort(parts);
        return String.join(";", parts);
    }

    private static VisualTrackArbitrator.Result visualRenderTracks(
            List<TrackedObject> tracks) {
        return VisualTrackArbitrator.arbitrate(tracks);
    }

    private synchronized VisualGeometryDelta recordVisualGeometry(
            List<TrackedObject> tracks) {
        int matched = 0;
        int changed = 0;
        int maxCenterDeltaPx = 0;
        int maxSizeDeltaPx = 0;
        Map<Integer, BBox> current = new HashMap<>();
        if (tracks != null) {
            for (TrackedObject track : tracks) {
                if (track == null) continue;
                BBox box = track.getBox();
                current.put(track.getId(), box);
                BBox previous = lastPublishedVisualBoxes.get(track.getId());
                if (previous == null) continue;
                matched++;
                int centerDelta = Math.max(
                        Math.abs(box.getCenterX() - previous.getCenterX()),
                        Math.abs(box.getCenterY() - previous.getCenterY()));
                int sizeDelta = Math.max(
                        Math.abs(box.getWidth() - previous.getWidth()),
                        Math.abs(box.getHeight() - previous.getHeight()));
                maxCenterDeltaPx = Math.max(maxCenterDeltaPx, centerDelta);
                maxSizeDeltaPx = Math.max(maxSizeDeltaPx, sizeDelta);
                if (centerDelta > 1 || sizeDelta > 1) changed++;
            }
        }
        lastPublishedVisualBoxes.clear();
        lastPublishedVisualBoxes.putAll(current);
        return new VisualGeometryDelta(
                matched, changed, maxCenterDeltaPx, maxSizeDeltaPx);
    }

    private synchronized void clearVisualGeometryHistory() {
        lastPublishedVisualBoxes.clear();
    }

    private synchronized void offsetVisualGeometryHistory(int dx, int dy) {
        if (dx == 0 && dy == 0 || lastPublishedVisualBoxes.isEmpty()) return;
        for (Map.Entry<Integer, BBox> entry : lastPublishedVisualBoxes.entrySet()) {
            BBox box = entry.getValue();
            entry.setValue(new BBox(
                    box.getX() + dx,
                    box.getY() + dy,
                    box.getWidth(),
                    box.getHeight()));
        }
    }

    private List<Detection> shiftTextSource(
            TextDetectionSnapshot snapshot,
            int width,
            int height,
            long requestedScrollX,
            long requestedScrollY) {
        return shiftDetectionSource(snapshot.detections,
                snapshot.width, snapshot.height, snapshot.scrollX, snapshot.scrollY,
                width, height, requestedScrollX, requestedScrollY);
    }

    private List<Detection> shiftDetectionSource(
            List<Detection> source,
            int sourceWidth,
            int sourceHeight,
            long sourceScrollX,
            long sourceScrollY,
            int width,
            int height,
            long requestedScrollX,
            long requestedScrollY) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        float scaleX = width / (float) Math.max(1, sourceWidth);
        float scaleY = height / (float) Math.max(1, sourceHeight);
        Rect screen = screenBounds();
        float scrollScaleX = width / (float) Math.max(1, screen.width());
        float scrollScaleY = height / (float) Math.max(1, screen.height());
        int offsetX = Math.round(-(requestedScrollX - sourceScrollX) * scrollScaleX);
        int offsetY = Math.round(-(requestedScrollY - sourceScrollY) * scrollScaleY);
        List<Detection> shifted = new ArrayList<>(source.size());
        for (Detection detection : source) {
            BBox box = detection.getBox();
            int left = Math.round(box.getX() * scaleX) + offsetX;
            int top = Math.round(box.getY() * scaleY) + offsetY;
            int right = Math.round(box.getRight() * scaleX) + offsetX;
            int bottom = Math.round(box.getBottom() * scaleY) + offsetY;
            if (right <= 0 || bottom <= 0 || left >= width || top >= height) continue;
            left = Math.max(0, left);
            top = Math.max(0, top);
            right = Math.min(width, right);
            bottom = Math.min(height, bottom);
            Detection shiftedDetection = new Detection(
                    detection.getClassName(), detection.getCategory(),
                    detection.getConfidence(),
                    new BBox(left, top, Math.max(1, right - left), Math.max(1, bottom - top)),
                    detection.isNsfw(), detection.isExposed(), detection.getSource(),
                    detection.getGeometryQuality(), detection.getAnchorKey());
            if (detection.getTrackId() >= 0) shiftedDetection.setTrackId(detection.getTrackId());
            shifted.add(shiftedDetection);
        }
        return shifted;
    }

    private void maybeRequestOcr(
            Bitmap source,
            long epoch,
            long scrollX,
            long scrollY,
            long requestedMotionGeneration,
            DetectorConfig config) {
        TextSmutConfig currentTextConfig = textSmutConfig;
        ScheduledExecutorService callbackExecutor = ocrWorker;
        long now = SystemClock.uptimeMillis();
        if (!ocrEligibleForViewport(config,
                    currentTextConfig != null && currentTextConfig.isEnabled(),
                    firstFrameReported.get(), accessibilityTextCandidatesPresent)
                || screenshotText == null
                || callbackExecutor == null || callbackExecutor.isShutdown()
                || ocrDelayMs(now, lastOcrCompletionUptime, lastMotionUptime,
                        ocrConfirmationRequested.get()) > 0L
                || !ocrRunning.compareAndSet(false, true)) return;
        InferenceBitmapPreparer.Prepared prepared = InferenceBitmapPreparer.prepare(
                source, OCR_MAX_DIMENSION, false);
        if (prepared == null) {
            ocrRunning.set(false);
            return;
        }
        Bitmap ocrBitmap = prepared.bitmap;
        activeOcrBitmap.set(ocrBitmap);
        try {
            callbackExecutor.execute(() -> startOcrWhenVisualIdle(
                    ocrBitmap, prepared.sourceWidth, prepared.sourceHeight,
                    epoch, scrollX, scrollY, requestedMotionGeneration,
                    currentTextConfig, callbackExecutor, now));
        } catch (RejectedExecutionException stopped) {
            abandonQueuedOcr(ocrBitmap);
        }
    }

    private void startOcrWhenVisualIdle(
            Bitmap ocrBitmap,
            int sourceWidth,
            int sourceHeight,
            long epoch,
            long scrollX,
            long scrollY,
            long requestedMotionGeneration,
            TextSmutConfig requestedTextConfig,
            ScheduledExecutorService callbackExecutor,
            long queuedAtUptime) {
        long now = SystemClock.uptimeMillis();
        if (!isOcrRequestCurrent(epoch, requestedMotionGeneration, requestedTextConfig)) {
            abandonQueuedOcr(ocrBitmap);
            return;
        }
        if (inferenceDraining.get() || pendingInference.get() != null) {
            if (now - queuedAtUptime >= OCR_VISUAL_IDLE_TIMEOUT_MS) {
                abandonQueuedOcr(ocrBitmap);
            } else {
                try {
                    callbackExecutor.schedule(() -> startOcrWhenVisualIdle(
                                    ocrBitmap, sourceWidth, sourceHeight,
                                    epoch, scrollX, scrollY, requestedMotionGeneration,
                                    requestedTextConfig, callbackExecutor, queuedAtUptime),
                            OCR_VISUAL_IDLE_RETRY_MS, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException stopped) {
                    abandonQueuedOcr(ocrBitmap);
                }
            }
            return;
        }
        long startedAtUptime = now;
        try {
            screenshotText.detect(ocrBitmap, requestedTextConfig,
                    sourceWidth, sourceHeight, callbackExecutor,
                    new OcrTextSmutDetector.Callback() {
                        @Override public void onRecognitionFinished() {
                            releaseOcrBitmap(ocrBitmap);
                        }

                        @Override public void onComplete(List<Detection> detections) {
                            long completedAt = SystemClock.uptimeMillis();
                            boolean stale = !isOcrRequestCurrent(
                                    epoch, requestedMotionGeneration, requestedTextConfig);
                            int stableCount = 0;
                            try {
                                if (stale) {
                                    clearCachedOcr();
                                    return;
                                }
                                List<Detection> stable = ocrTextStabilizer.update(detections);
                                stableCount = stable.size();
                                cachedOcrText = new TextDetectionSnapshot(
                                        stable, sourceWidth, sourceHeight,
                                        scrollX, scrollY, completedAt);
                                ocrConfirmationRequested.set(
                                        stable.size() != detections.size());
                                publishTextLane(epoch, "ocr");
                            } finally {
                                DiagnosticsRepository.recordOcr(
                                        DIAGNOSTICS_MODE,
                                        completedAt - startedAtUptime, stale, stableCount);
                                finishOcr(ocrBitmap, completedAt);
                            }
                        }

                        @Override public void onFailure(Exception error) {
                            long completedAt = SystemClock.uptimeMillis();
                            finishOcr(ocrBitmap, completedAt);
                            Log.w(TAG, "Ultra screenshot OCR failed", error);
                        }
                    });
        } catch (RuntimeException error) {
            finishOcr(ocrBitmap, SystemClock.uptimeMillis());
            Log.w(TAG, "Could not start Ultra screenshot OCR", error);
        }
    }

    private boolean isOcrRequestCurrent(
            long epoch,
            long requestedMotionGeneration,
            TextSmutConfig requestedTextConfig) {
        return isCurrentCapture(epoch)
                && sameOcrViewport(requestedMotionGeneration, motionGeneration.get(),
                        accessibilityTextCandidatesPresent)
                && usesScreenshotOcr(detectorConfig)
                && requestedTextConfig == textSmutConfig;
    }

    private void abandonQueuedOcr(Bitmap bitmap) {
        lastOcrCompletionUptime = SystemClock.uptimeMillis();
        releaseOcrBitmap(bitmap);
        ocrRunning.set(false);
    }

    private void finishOcr(Bitmap bitmap, long completedAtUptime) {
        lastOcrCompletionUptime = completedAtUptime;
        releaseOcrBitmap(bitmap);
        ocrRunning.set(false);
    }

    static long ocrDelayMs(
            long nowUptime,
            long lastCompletionUptime,
            long lastMotionUptime,
            boolean confirmationRequested) {
        long interval = confirmationRequested
                ? OCR_CONFIRM_INTERVAL_MS : OCR_INTERVAL_MS;
        long cadenceWait = lastCompletionUptime <= 0L ? 0L
                : interval - (nowUptime - lastCompletionUptime);
        long motionWait = lastMotionUptime <= 0L ? 0L
                : OCR_MOTION_SETTLE_MS - (nowUptime - lastMotionUptime);
        return Math.max(0L, Math.max(cadenceWait, motionWait));
    }

    static boolean ocrEligibleForViewport(
            DetectorConfig config,
            boolean textEnabled,
            boolean firstVisualFramePublished,
            boolean accessibilityTextCandidatesPresent) {
        return usesScreenshotOcr(config) && textEnabled && firstVisualFramePublished
                && !accessibilityTextCandidatesPresent;
    }

    static boolean sameOcrViewport(
            long requestedMotionGeneration,
            long currentMotionGeneration,
            boolean accessibilityTextCandidatesPresent) {
        return requestedMotionGeneration == currentMotionGeneration
                && !accessibilityTextCandidatesPresent;
    }

    static boolean isOcrSnapshotFresh(long nowUptime, long capturedAtUptime) {
        return capturedAtUptime > 0L && nowUptime >= capturedAtUptime
                && nowUptime - capturedAtUptime <= OCR_RESULT_TTL_MS;
    }

    private void clearCachedOcr() {
        cachedOcrText = TextDetectionSnapshot.EMPTY;
        ocrTextStabilizer.clear();
        ocrConfirmationRequested.set(false);
    }

    private void invalidateOcrForMotion() {
        clearCachedOcr();
        accessibilityTextCandidatesPresent = false;
        DiagnosticsRepository.recordAccessibilityText(DIAGNOSTICS_MODE, 0, 0);
    }

    private void releaseOcrBitmap(Bitmap bitmap) {
        if (bitmap != null && activeOcrBitmap.compareAndSet(bitmap, null)
                && !bitmap.isRecycled()) bitmap.recycle();
    }

    private boolean isCurrentCapture(long requestedEpoch) {
        return captureEpoch.accepts(requestedEpoch, running, recognitionActive);
    }

    private void reloadSettings() {
        if (settings == null) return;
        if (productQualificationRoute) {
            reloadProductSettings(null);
            return;
        }
        main.post(() -> {
            if (overlay != null) {
                CensorAppearance appearance = settings.loadAppearance();
                overlayNeedsSourceFrame = appearance.requiresSourceFrame();
                overlay.setAppearance(appearance);
                overlay.setDiagnostics(diagnosticsOverlayText());
            }
        });
        DetectorConfig config = settings.loadDetectorConfig();
        detectorConfig = config;
        invalidateWorldCache("settings-reload");
        main.post(() -> {
            if (overlay != null) {
                // Accessibility deltas already move every box at event cadence. Detector
                // velocity in this mode is geometry noise, not missing viewport motion.
                overlay.setMaxExtrapolationMs(0f);
            }
        });
        configureAccessibilityCadence(config);
        textSmutConfig = settings.loadTextSmutConfig();
        textSceneGeneration.incrementAndGet();
        accessibilityCandidateScans.set(0);
        cancelPendingTextConfirmation();
        warmTextModels(config);
        if (!usesScreenshotOcr(config)) cachedOcrText = TextDetectionSnapshot.EMPTY;
        textRefreshRequested.set(true);
        if (detector != null) detector.setConfig(qualityDetectorConfig(config));
        if (fastDetector != null) fastDetector.setConfig(fastDetectorConfig(config));
        if (usesAtomicScenePipeline(config) && detector == null
                && firstOverlayReported.get()) {
            scheduleQualityDetectorInitialization(config);
        }
        if (tracker != null) tracker.setConfig(accessibilityTrackerConfig(config));
        PopupStormManager.get().reloadSettings(this);
        main.post(() -> {
            if (subliminalOverlay != null) subliminalOverlay.updateSettings();
            reevaluateRecognition();
            reevaluateSubliminals();
        });
    }

    private void warmTextModels(DetectorConfig config) {
        ScheduledExecutorService accessibilityExecutor = textWorker;
        if (accessibilityExecutor != null && !accessibilityExecutor.isShutdown()
                && usesSemanticTextModel(config) && smutTextClassifier != null) {
            accessibilityExecutor.execute(smutTextClassifier::warmSemanticModel);
        }
        ScheduledExecutorService currentOcrWorker = ocrWorker;
        if (currentOcrWorker != null && !currentOcrWorker.isShutdown()
                && usesScreenshotOcr(config) && screenshotText != null) {
            screenshotText.warmUp(currentOcrWorker);
        }
    }

    private void reloadProductSettings(String key) {
        main.post(() -> {
            if (Build.VERSION.SDK_INT < 34 || !running || productSession == null) return;
            configureAccessibilityCadence(settings.loadDetectorConfig());
            if (key == null) productSession.ownershipChanged(); else productSession.settingsChanged(key);
            PopupStormManager.get().reloadSettings(this);
            if (subliminalOverlay != null) subliminalOverlay.updateSettings();
            syncProductParticipation(); reevaluateSubliminals();
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            handleAccessibilityEvent(event);
        } catch (RuntimeException error) {
            DiagnosticsRepository.fail(DIAGNOSTICS_MODE, error);
            Log.e(TAG, "Accessibility event failed", error);
        }
    }

    private boolean hasPendingScrollLookup() {
        SerialOwnedLookup<PendingScrollEvent> queue = scrollLookup;
        return queue != null && queue.outstanding() > 0;
    }

    private void queueScrollLookup(AccessibilityEvent event, String packageName) {
        long started = System.nanoTime();
        long received = SystemClock.uptimeMillis();
        SerialOwnedLookup<PendingScrollEvent> queue = scrollLookup;
        if (queue == null) return;
        PendingScrollEvent pending = new PendingScrollEvent(AccessibilityEvent.obtain(event),
                scrollLookupSequence.incrementAndGet(), received, captureEpoch.token(),
                visualDocumentEpoch.get(), activeApplicationWindowId.get(), packageName,
                scrollInputGeneration.get(), activeScrollSurfaceProvisional);
        SerialOwnedLookup.Offer offered = queue.offer(pending);
        traceScrollLookup(pending, offered == SerialOwnedLookup.Offer.ACCEPTED
                ? "queued" : "rejected", (System.nanoTime() - started) / 1_000L);
        if (offered != SerialOwnedLookup.Offer.ACCEPTED) {
            recoverScrollLookupGap(pending, offered.name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private boolean scrollLookupExternalCurrent(PendingScrollEvent pending) {
        return isCurrentCapture(pending.captureEpoch)
                && pending.scope.externalMatches(captureEpoch.token(), scrollInputGeneration.get(),
                        activeApplicationWindowId.get(), foregroundPackage);
    }

    private boolean scrollLookupScopeCurrent(PendingScrollEvent pending,
            AccessibilitySurfaceIdentityResolver.Identity identity) {
        boolean confirmedSameOwner = identity != null && identity.isCacheable()
                && identity.windowId == activeApplicationWindowId.get()
                && !activeScrollSurfaceProvisional
                && cacheSurfaceKey(identity).equals(activeScrollSurfaceKey);
        return isCurrentCapture(pending.captureEpoch) && pending.scope.mayApply(
                captureEpoch.token(), scrollInputGeneration.get(), activeApplicationWindowId.get(),
                foregroundPackage, visualDocumentEpoch.get(), confirmedSameOwner);
    }

    private void resolveScrollLookup(PendingScrollEvent pending, SerialOwnedLookup.Lease lease) {
        pending.startedUptime = SystemClock.uptimeMillis();
        if (!lease.isCurrent() || !scrollLookupExternalCurrent(pending)) {
            traceScrollLookup(pending, "stale", 0L);
            lease.release();
            return;
        }
        if (pending.startedUptime - pending.receivedUptime > SCROLL_LOOKUP_MAX_WAIT_MS) {
            traceScrollLookup(pending, "expired", 0L);
            lease.release();
            main.post(() -> recoverScrollLookupGap(pending, "queue-age"));
            return;
        }
        AccessibilitySurfaceIdentityResolver.Identity identity =
                scrollSurfaceIdentityResolver.resolve(pending.event);
        pending.resolvedUptime = SystemClock.uptimeMillis();
        traceScrollSourceBounds(pending.event);
        if (!main.post(() -> {
            pending.appliedUptime = SystemClock.uptimeMillis();
            try {
                if (!lease.isCurrent() || !scrollLookupScopeCurrent(pending, identity)) {
                    traceScrollLookup(pending, "stale", 0L);
                    if (scrollLookupExternalCurrent(pending)
                            && pending.documentEpoch != visualDocumentEpoch.get()) {
                        CensorLabLog.i(TAG, "SCROLL_LOOKUP_GAP reason=unresolved-document");
                    }
                    settledInferenceNeeded.set(true);
                    queueSettledCapture();
                    return;
                }
                applyResolvedScrollEvent(pending, identity);
                traceScrollLookup(pending, "applied", 0L);
            } catch (RuntimeException error) {
                DiagnosticsRepository.fail(DIAGNOSTICS_MODE, error);
                traceScrollLookup(pending, "failed", 0L);
                recoverScrollLookupGap(pending, "apply-failure");
            } finally {
                lease.release();
            }
        })) lease.release();
    }

    private void recoverScrollLookupGap(PendingScrollEvent origin, String reason) {
        if (!running || !recognitionActive) return;
        // Missing unknown-producer deltas cannot be coalesced or silently treated as zero motion.
        // Reset document/camera evidence and require a fresh capture rather than replaying a hole.
        synchronized (sceneLifecycleLock) {
            synchronized (worldCacheLock) {
                if (!scrollLookupExternalCurrent(origin)
                        || origin.documentEpoch != visualDocumentEpoch.get()) {
                    traceScrollLookup(origin, "stale", 0L);
                    return;
                }
                CensorLabLog.i(TAG, "SCROLL_LOOKUP_GAP reason=" + reason);
                resetScrollCompensation();
                if (tracker != null) tracker.clear();
            }
        }
        if (overlay != null) overlay.clear();
        settledInferenceNeeded.set(true);
        queueSettledCapture();
    }

    private void traceScrollLookup(PendingScrollEvent pending, String status, long callbackUs) {
        // Queue admission can race the worker. Its record describes admission, not a partial
        // sample of concurrently changing completion clocks.
        boolean admission = status.equals("queued") || status.equals("rejected");
        CensorLabLog.i(TAG, "SCROLL_LOOKUP v=1 id=" + pending.sequence + " status=" + status
                + " sourceUptimeMs=" + pending.sourceUptime + " receivedUptimeMs=" + pending.receivedUptime
                + " startedUptimeMs=" + (admission ? 0L : pending.startedUptime)
                + " resolvedUptimeMs=" + (admission ? 0L : pending.resolvedUptime)
                + " appliedUptimeMs=" + (admission ? 0L : pending.appliedUptime) + " callbackUs=" + callbackUs
                + " outstanding=" + (scrollLookup == null ? 0 : scrollLookup.outstanding()));
    }

    private void applyResolvedScrollEvent(PendingScrollEvent pending,
            AccessibilitySurfaceIdentityResolver.Identity surfaceIdentity) {
        AccessibilityEvent event = pending.event;
        String packageName = pending.packageName;
        long scrollNow = pending.receivedUptime;
        long appliedNow = SystemClock.uptimeMillis();
        String cacheSurface = cacheSurfaceKey(surfaceIdentity);
        long surfaceTelemetryToken = surfaceIdentity.telemetryToken();
        byte surfaceConfidence = surfaceIdentity.confidence;
        boolean surfaceCacheable = surfaceIdentity.isCacheable();
        ScrollSurfaceHysteresis.Decision surfaceDecision =
                ScrollSurfaceHysteresis.Decision.DISABLE;
        String reusableSurface = "";
        String decisionActiveSurface;
        long reusableToken = 0L;
        byte reusableConfidence = AccessibilitySurfaceIdentityResolver.CONFIDENCE_LOW;
        synchronized (worldCacheLock) {
            decisionActiveSurface = activeScrollSurfaceKey;
            surfaceDecision = ScrollSurfaceHysteresis.decide(
                    surfaceIdentity.isCacheable(), surfaceIdentity.windowId,
                    activeScrollSurfaceKey != null
                            && !activeScrollSurfaceKey.isEmpty(),
                    activeScrollSurfaceWindowId, activeScrollSurfaceProvisional, scrollNow,
                    activeScrollSurfaceLastTrustedUptime,
                    activeScrollSurfaceLowReuseCount);
            if (surfaceDecision == ScrollSurfaceHysteresis.Decision.REUSE_ACTIVE) {
                activeScrollSurfaceLowReuseCount++;
                reusableSurface = activeScrollSurfaceKey;
                reusableToken = activeScrollTelemetryToken;
                reusableConfidence = activeScrollSurfaceConfidence;
            }
        }
        if (surfaceDecision == ScrollSurfaceHysteresis.Decision.USE_OBSERVED) {
            if (acceptScrollSurface(cacheSurface, surfaceIdentity, scrollNow)) {
                CensorLabLog.i(TAG, "WORLD_CACHE_SURFACE token="
                        + Long.toUnsignedString(surfaceIdentity.telemetryToken(), 16)
                        + " confidence=" + surfaceIdentity.confidence
                        + " cacheable=true");
            }
        } else if (surfaceDecision == ScrollSurfaceHysteresis.Decision.REUSE_ACTIVE) {
            // Chrome/WebView can alternate a stable scroll-owner event with a virtual
            // companion event whose source cannot be resolved. Keep the proven surface
            // instead of clearing history every other callback.
            cacheSurface = reusableSurface;
            surfaceTelemetryToken = reusableToken;
            surfaceConfidence = reusableConfidence;
            surfaceCacheable = true;
        } else {
            disableWorldCacheForUnstableSurface(
                    surfaceIdentity, decisionActiveSurface);
        }
        // Cache identity intentionally stays sticky across Chromium's companion nodes,
        // but their scroll coordinate systems are not interchangeable. Keep motion keyed
        // to the actually observed producer so an explicit-delta callback cannot overwrite
        // another node's absolute-position baseline and manufacture multi-viewport jumps.
        long observedMotionToken = surfaceIdentity.telemetryToken();
        CensorLabLog.i(TAG, ScrollMetadataTrace.encode(event.getEventTime(), observedMotionToken,
                ScrollMetadataTrace.classKind(event.getClassName()), event.getScrollX(), event.getScrollY(),
                event.getMaxScrollX(), event.getMaxScrollY(), event.getFromIndex(), event.getToIndex(),
                event.getItemCount(), Build.VERSION.SDK_INT >= 28 ? event.getScrollDeltaX() : -1,
                Build.VERSION.SDK_INT >= 28 ? event.getScrollDeltaY() : -1, surfaceIdentity));
        String motionSurface = observedMotionToken == 0L
                ? AccessibilityScrollMotionResolver.surfaceKey(event)
                : "event:" + Long.toUnsignedString(observedMotionToken, 16);
        int viewportWidth = latestCaptureWidth;
        int viewportHeight = latestCaptureHeight;
        if (viewportWidth <= 1 || viewportHeight <= 1) {
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            viewportWidth = Math.max(1, metrics.widthPixels);
            viewportHeight = Math.max(1, metrics.heightPixels);
        }
        AccessibilityScrollMotionResolver.Motion rawMotion = scrollMotionResolver.resolve(
                event, viewportWidth, viewportHeight, motionSurface);
        long sourceTime = event.getEventTime();
        if (sourceTime <= 0L || sourceTime > scrollNow) sourceTime = scrollNow;
        long eventAgeMs = Math.max(0L, scrollNow - sourceTime);
        boolean companionDuplicate = scrollCompanionDeduplicator.observe(
                visualDocumentEpoch.get(), surfaceTelemetryToken, observedMotionToken,
                viewportWidth, viewportHeight,
                surfaceCacheable && surfaceDecision == ScrollSurfaceHysteresis.Decision.USE_OBSERVED,
                surfaceDecision == ScrollSurfaceHysteresis.Decision.REUSE_ACTIVE,
                rawMotion.evidence, rawMotion.dx, rawMotion.dy, sourceTime, scrollNow);
        if (companionDuplicate) {
            // The trusted absolute event already applied this exact displacement.
            // Do not mutate the camera, restart prediction, or trigger screenshot
            // fallback for rejected motion.
            traceScrollEvent(scrollNow, sourceTime, eventAgeMs,
                    rawMotion.dx, rawMotion.dy, 0, 0, "companion-duplicate",
                    rawMotion.evidence.name(), Math.abs(rawMotion.dx) + Math.abs(rawMotion.dy),
                    false, surfaceTelemetryToken, surfaceConfidence, surfaceCacheable,
                    surfaceIdentity.confidence, surfaceDecision, observedMotionToken);
            return;
        }
        ScrollDeltaStabilizer.Result filteredMotion = scrollDeltaStabilizer.filter(
                rawMotion.dx, rawMotion.dy, sourceTime, viewportWidth, viewportHeight,
                rawMotion.authoritative());
        // Trace adjustment totals remain relative to Android's original producer delta,
        // not to the stabilizer output. The trace schema is unchanged.
        ScrollDeltaStabilizer.Result motion = new ScrollDeltaStabilizer.Result(
                rawMotion.dx, rawMotion.dy, filteredMotion.dx, filteredMotion.dy,
                filteredMotion.rapidReversal, filteredMotion.authoritative);
        if (BuildConfig.DEBUG && scrollNow - lastScrollDiagnosticUptime >= 250L) {
            lastScrollDiagnosticUptime = scrollNow;
            Log.d(TAG, "Scroll event screen motion raw=" + rawMotion.dx + ','
                    + rawMotion.dy + " filtered=" + motion.dx + ',' + motion.dy);
        }
        traceScrollEvent(scrollNow, sourceTime, eventAgeMs,
                rawMotion.dx, rawMotion.dy,
                motion.dx, motion.dy,
                motion.authoritative ? "accessibility-authoritative"
                        : motion.rapidReversal ? "rapid-reversal"
                        : rawMotion.moved() && !motion.moved()
                        ? "direction-suppressed" : "accessibility",
                rawMotion.evidence.name(), motion.adjustedPixels(), motion.amplified(),
                surfaceTelemetryToken, surfaceConfidence, surfaceCacheable,
                surfaceIdentity.confidence, surfaceDecision, observedMotionToken);
        if (motion.moved()) {
            if (overlay != null) overlay.setNativeScrollSpline(
                    NativeScrollCurvePolicy.supports(packageName, event.getClassName()));
            if (rowMotionShadow) {
                visualCameraShadow.event(new RowMotionObserver.Scope(captureEpoch.token(),
                                visualDocumentEpoch.get(), activeApplicationWindowId.get(),
                                latestCaptureWidth, latestCaptureHeight), observedMotionToken,
                        sourceTime, motion.dx, motion.dy, cumulativeScrollY.get());
            }
            applyEventMotion(motion.dx, motion.dy, motion.authoritative,
                    sourceTime, appliedNow);
        } else {
            if (rawMotion.moved() && motionEstimator != null) {
                // Prevent screenshot phase-correlation from applying a rejected producer
                // correction while Accessibility direction is being confirmed.
                motionEstimator.reset();
            }
            dwellTracker.onScroll();
            // Keep screenshot motion as a fallback for custom views which omit deltas.
            lastMotionUptime = SystemClock.uptimeMillis();
            settledInferenceNeeded.set(true);
            textRefreshRequested.set(true);
            main.removeCallbacks(settledTextRefresh);
            main.postDelayed(settledTextRefresh, SETTLED_SCROLL_REFRESH_MS);
        }
    }

    private void handleAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (productQualificationRoute) { handleProductAccessibilityEvent(event); return; }
        int eventType = event.getEventType();
        if (eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
                || eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) {
            traceTouchInteraction(
                    event, eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START);
            return;
        }
        String packageName = event.getPackageName() == null
                ? "" : event.getPackageName().toString();
        if (!packageName.isEmpty()) guardForegroundPackage = packageName;
        boolean settingsEvent = HardcoreSettingsGuard.isSettingsPackage(packageName);
        boolean windowTransition = event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        if (settingsEvent && (windowTransition
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED)) {
            queueHardcoreSettingsGuardRefresh(windowTransition ? 0L : 32L);
        } else if (windowTransition && hardcoreSettingsGuard != null) {
            // Adding the accessibility badge itself emits a window event from our package. A
            // live-root refresh distinguishes that feedback from actually leaving Settings and
            // prevents the guard from clearing/re-adding (the visible flash users reported).
            queueHardcoreSettingsGuardRefresh(0L);
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (recognitionActive && packageName.equals(foregroundPackage)) {
                queueScrollLookup(event, packageName);
            }
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && recognitionActive && packageName.equals(foregroundPackage)) {
            int contentTypes = event.getContentChangeTypes();
            if ((contentTypes & AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_INVALID) != 0) {
                invalidateWorldCache("content-invalid");
            }
            if (!isTextRelevantContentChange(contentTypes)) return;
            long contentChangedAt = SystemClock.uptimeMillis();
            lastTextContentChangeUptime = contentChangedAt;
            textContentGeneration.incrementAndGet();
            textContentEvents.incrementAndGet();
            textContentChangeTypes.updateAndGet(previous -> previous | contentTypes);
            textRefreshRequested.set(true);
            invalidateAccessibilityTextSnapshot();
            if (textContentBurstStartedUptime <= 0L) {
                textContentBurstStartedUptime = contentChangedAt;
            }
            long refreshDelay = contentTextRefreshDelayMs(
                    contentChangedAt, lastTextContentChangeUptime,
                    textContentBurstStartedUptime);
            if (refreshDelay > 0L) {
                main.removeCallbacks(contentTextRefresh);
                contentTextRefreshScheduled.set(true);
                main.postDelayed(contentTextRefresh, refreshDelay);
            } else if (contentTextRefreshScheduled.compareAndSet(false, true)) {
                main.post(contentTextRefresh);
            }
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && !packageName.equals(foregroundPackage)
                && syncForegroundFromActiveRoot(System.currentTimeMillis())) {
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            recordCensoredTap(event, packageName);
            return;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getEventType() != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        String className = event.getClassName() == null ? "" : event.getClassName().toString();
        AppModeManager mode = new AppModeManager(this);
        if (!AppModePolicy.shouldAcceptForegroundEvent(packageName, className, getPackageName(),
                mode.inputMethodPackage())) return;
        ForegroundWindowResolver.Candidate liveWindow = resolveLiveApplicationWindow();
        String confirmedPackage = liveWindow == null || liveWindow.packageName.isEmpty()
                ? packageName : liveWindow.packageName;
        if (confirmedPackage.equals(foregroundPackage)) {
            int confirmedWindowId = liveWindow == null
                    ? event.getWindowId() : liveWindow.windowId;
            if (shouldInvalidateProvisionalDocument(
                    event.getEventType(), activeScrollSurfaceProvisional,
                    confirmedWindowId, activeScrollSurfaceWindowId)) {
                invalidateWorldCache("provisional-window-state-change");
                qualityConcurrencyGovernor.reset();
                qualityBackfillRunner.resetPolicyState();
            }
            if (liveWindow != null) acceptApplicationWindow(liveWindow.windowId);
            if (!packageName.equals(confirmedPackage)) {
                Log.i(TAG, "FOREGROUND_HOLD eventPackage=" + packageName
                        + " protectedPackage=" + confirmedPackage);
            }
            // A service/package update can reconnect while the already-foreground app never
            // produces a package transition. Re-evaluate on its window-state signal instead of
            // leaving recognition asleep until the user switches apps twice.
            reevaluateRecognition();
            return;
        }
        acceptForegroundPackage(confirmedPackage, System.currentTimeMillis());
        if (liveWindow != null) activeApplicationWindowId.set(liveWindow.windowId);
    }

    private void handleProductAccessibilityEvent(AccessibilityEvent event) {
        CensorProductSession session = productSession;
        if (Build.VERSION.SDK_INT < 34 || !running || session == null) return;
        int type = event.getEventType();
        String packageName = event.getPackageName() == null ? "" : event.getPackageName().toString();
        boolean window = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        String route = window ? "FOREGROUND" : type == AccessibilityEvent.TYPE_VIEW_SCROLLED && packageName.equals(foregroundPackage) ? "SCROLL"
                : type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ? (packageName.equals(foregroundPackage) ? "CONTENT" : "REQUEST")
                : type == AccessibilityEvent.TYPE_VIEW_CLICKED ? "CLICK" : "NONE";
        long receivedAt = SystemClock.uptimeMillis();
        ProductCensorQualification.observeFixtureInput(session, type, event.getContentChangeTypes(), event.getWindowId(),
                event.getEventTime(), receivedAt, route, false);
        try {
            if (!packageName.isEmpty()) guardForegroundPackage = packageName;
            if (window || (HardcoreSettingsGuard.isSettingsPackage(packageName)
                    && (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || type == AccessibilityEvent.TYPE_VIEW_CLICKED))) {
                queueHardcoreSettingsGuardRefresh(window ? 0L : 32L);
            }
            if (window) {
                String className = event.getClassName() == null ? "" : event.getClassName().toString();
                session.foregroundEvent(packageName, className, event.getWindowId(), type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            } else if ((type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || type == AccessibilityEvent.TYPE_VIEW_SCROLLED)
                    && packageName.equals(foregroundPackage)) {
                if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) session.scrolled(event);
                else session.contentChanged(event.getWindowId(), event.getContentChangeTypes());
            } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && !packageName.equals(foregroundPackage)) {
                session.request();
            } else if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) recordCensoredTap(event, packageName);
            // No event/receipt timestamp is promoted to measured image motion or source pixel time.
        } finally {
            ProductCensorQualification.observeFixtureInput(session, type, event.getContentChangeTypes(), event.getWindowId(),
                    event.getEventTime(), receivedAt, route, true);
        }
    }

    private void acceptApplicationWindow(int windowId) {
        if (windowId < 0) return;
        int previous = activeApplicationWindowId.getAndSet(windowId);
        if (previous >= 0 && previous != windowId) {
            invalidateWorldCache("application-window-change");
            lastScrollTraceEventUptime = 0L;
        }
    }

    static boolean shouldInvalidateProvisionalDocument(
            int eventType,
            boolean activeProvisional,
            int eventWindowId,
            int activeWindowId) {
        return activeProvisional
                && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && eventWindowId >= 0
                && activeWindowId >= 0
                && eventWindowId == activeWindowId;
    }

    /**
     * Starts current-view quality before the first scroll callback. A proven Accessibility scroll
     * owner replaces this low-confidence window identity atomically on its first event. Once an
     * unstable scroll event disables a surface, the nonzero scroll timestamp prevents this
     * provisional identity from being recreated for that same window.
     */
    /**
     * Debug observer only: cached source bounds, no refresh/parent traversal or camera mutation.
     */
    private void traceScrollSourceBounds(AccessibilityEvent event) {
        if (!BuildConfig.DEBUG) return;
        long started = SystemClock.uptimeMillis();
        android.view.accessibility.AccessibilityNodeInfo node = null;
        try {
            node = event.getSource();
            if (node == null) return;
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            CharSequence kind = node.getClassName();
            CensorLabLog.i(TAG, "SCROLL_SOURCE_BOUNDS sourceUptimeMs=" + event.getEventTime()
                    + " absolute=" + event.getScrollX() + ',' + event.getScrollY()
                    + " observedUptimeMs=" + started + " rect=" + bounds.left + ',' + bounds.top
                    + ',' + bounds.right + ',' + bounds.bottom
                    + " webView=" + "android.webkit.WebView".contentEquals(kind == null ? "" : kind)
                    + " scrollable=" + node.isScrollable() + " window=" + node.getWindowId()
                    + " elapsedMs=" + (SystemClock.uptimeMillis() - started));
        } catch (RuntimeException ignored) {
            // Telemetry is not a reason to interfere with normal event processing.
        } finally {
            if (node != null) node.recycle();
        }
    }

    private void ensureProvisionalScrollSurface(String packageName, int windowId) {
        if (lastScrollTraceEventUptime > 0L) return;
        ProvisionalScrollSurface.Identity provisional =
                ProvisionalScrollSurface.forWindow(packageName, windowId);
        if (!provisional.valid()) return;
        int removed;
        long document;
        synchronized (worldCacheLock) {
            if (activeScrollSurfaceKey != null && !activeScrollSurfaceKey.isEmpty()) return;
            removed = contentSpaceRegionCache.clear();
            qualityBackfillCoordinator.clear();
            activeScrollSurfaceKey = provisional.key();
            activeScrollSurfaceWindowId = provisional.windowId();
            activeScrollTelemetryToken = provisional.telemetryToken();
            activeScrollSurfaceProvisional = true;
            activeScrollSurfaceConfidence = AccessibilitySurfaceIdentityResolver.CONFIDENCE_LOW;
            activeScrollSurfaceLastTrustedUptime = 0L;
            activeScrollSurfaceLowReuseCount = 0;
            document = advanceVisualDocument();
            resetWorldCacheQueryLocked();
        }
        invalidateCurrentScene("world-surface-provisional");
        cancelQualityRetrySchedule();
        qualityBackfillRunner.resetPolicyState();
        CensorLabLog.i(TAG, "WORLD_SURFACE_PROVISIONAL removed=" + removed
                + " documentEpoch=" + document
                + " windowId=" + provisional.windowId()
                + " token=" + Long.toUnsignedString(
                        provisional.telemetryToken(), 16));
    }

    static boolean isTextRelevantContentChange(int changeTypes) {
        if (changeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED) return true;
        int relevant = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
                | AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION
                | AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
                | AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_INVALID
                | AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED
                | AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED;
        return (changeTypes & relevant) != 0;
    }

    private boolean syncForegroundFromActiveRoot(long nowMillis) {
        ForegroundWindowResolver.Candidate liveWindow = resolveLiveApplicationWindow();
        String livePackage = liveWindow == null ? "" : liveWindow.packageName;
        if (livePackage.isEmpty() || livePackage.equals(foregroundPackage)) return false;
        acceptForegroundPackage(livePackage, nowMillis);
        return true;
    }

    private ForegroundWindowResolver.Candidate resolveLiveApplicationWindow() {
        List<ForegroundWindowResolver.Candidate> candidates = new ArrayList<>();
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }
                AccessibilityNodeInfo root = window.getRoot();
                try {
                    String packageName = root != null && root.getPackageName() != null
                            ? root.getPackageName().toString() : "";
                    candidates.add(new ForegroundWindowResolver.Candidate(
                            packageName, window.getId(), window.isActive(), window.isFocused(),
                            window.getLayer()));
                } finally {
                    if (root != null) root.recycle();
                }
            }
        }
        AppModeManager mode = new AppModeManager(this);
        ForegroundWindowResolver.Candidate selected = ForegroundWindowResolver.select(
                candidates, mode.inputMethodPackage(),
                recognitionActive ? foregroundPackage : "");
        if (selected != null) return selected;

        // Some OEMs briefly omit the interactive-window list during transitions. Keep the old
        // active-root fallback for that narrow gap; the next event/tick will retry the full list.
        AccessibilityNodeInfo root = getRootInActiveWindow();
        try {
            String packageName = root != null && root.getPackageName() != null
                    ? root.getPackageName().toString() : "";
            if (!AppModePolicy.shouldAcceptLiveForegroundPackage(
                    packageName, mode.inputMethodPackage())) return null;
            return new ForegroundWindowResolver.Candidate(
                    packageName, root.getWindowId(), true, true, 0);
        } finally {
            if (root != null) root.recycle();
        }
    }

    private void acceptForegroundPackage(String packageName, long now) {
        if (packageName == null || (!productQualificationRoute && packageName.isEmpty())
                || packageName.equals(foregroundPackage)) return;
        accountForegroundUsage(now);
        if (!productQualificationRoute) captureEpoch.invalidate();
        activeApplicationWindowId.set(-1);
        foregroundPackage = packageName;
        com.subhub.app.appmode.ForegroundAppState.update(packageName);
        foregroundSinceMillis = now;
        if (!productQualificationRoute) resetTextSnapshots();
        AppModeManager mode = new AppModeManager(this);
        if (!CensorLabCensorSession.isActive() && mode.getIncludedPackages().contains(packageName)) {
            int charged = penance.recordInfraction(
                    PenanceInfraction.WATCHED_APP_OPEN, 1, now);
            PenanceChargeNotifier.show(this, penance,
                    PenanceInfraction.WATCHED_APP_OPEN, charged, now);
        }
        dwellTracker.clear();
        tapTracker.clear();
        if (!productQualificationRoute) resetScrollCompensation();
        if (recognitionActive && worker != null) {
            worker.execute(() -> {
                if (tracker != null) tracker.clear();
            });
        }
        if (overlay != null) overlay.clear();
        reevaluateSubliminals();
        PopupStormManager.get().updateDetections(Collections.emptyList());
        if (enforceForegroundLimit(System.currentTimeMillis())) return;
        if (!productQualificationRoute) reevaluateRecognition();
    }

    private void resetTextSnapshots() {
        textSceneGeneration.incrementAndGet();
        cancelPendingTextConfirmation();
        textContentGeneration.incrementAndGet();
        textContentEvents.set(0);
        textContentChangeTypes.set(0);
        textContentStaleRetries.set(0);
        lastTextContentChangeUptime = 0L;
        textContentBurstStartedUptime = 0L;
        accessibilityTextInvalidatedAtUptime = 0L;
        main.removeCallbacks(contentTextRefresh);
        main.removeCallbacks(staleAccessibilityTextExpiry);
        contentTextRefreshScheduled.set(false);
        cacheAccessibilityText(TextDetectionSnapshot.EMPTY);
        clearCachedOcr();
        cachedQualityVisual = VisualDetectionSnapshot.EMPTY;
        qualityVisualStabilizer.clear();
        qualityConfirmationRequested.set(false);
        qualityConfirmationBurstUsed.set(false);
        accessibilityTextStabilizer.clear();
        accessibilityCandidateScans.set(0);
        lastPublishedTextFingerprint = "";
        skippedUnchangedTextPublishes = 0L;
        clearVisualGeometryHistory();
        accessibilityTextCandidatesPresent = false;
        textRefreshRequested.set(true);
        main.post(() -> {
            if (overlay != null) {
                Rect viewport = screenBounds();
                ScrollPosition current = currentScrollPosition();
                overlay.updateWorldText(Collections.emptyList(),
                        latestCaptureWidth, latestCaptureHeight,
                        current.scrollX, current.scrollY,
                        viewport.width(), viewport.height());
            }
        });
    }

    private void recordCensoredTap(AccessibilityEvent event, String packageName) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED
                || (!recognitionActive && !ScreenCaptureService.isRunning())
                || packageName.isEmpty() || !packageName.equals(foregroundPackage)
                || penance == null || CensorLabCensorSession.isActive()
                || ControllerPinManager.isDomModeActive()) return;
        long nowUptime = SystemClock.uptimeMillis();
        long eventUptime = event.getEventTime();
        if (eventUptime <= lastMatchedTapEventUptimeMillis
                || (lastMatchedTapUptimeMillis > 0L
                && nowUptime - lastMatchedTapUptimeMillis < 500L)) return;
        int activeWindow = activeApplicationWindowId.get();
        if (activeWindow >= 0 && event.getWindowId() != activeWindow) return;
        if (matchesTapSource(event.getSource(), packageName, eventUptime, nowUptime)) {
            chargeCensoredTap(System.currentTimeMillis(), eventUptime, nowUptime);
        }
    }

    private void chargeCensoredTap(long nowMillis, long eventUptime, long nowUptime) {
        if (CensorLabCensorSession.isActive()
                || ControllerPinManager.isDomModeActive() || penance == null
                || eventUptime <= lastMatchedTapEventUptimeMillis
                || (lastMatchedTapUptimeMillis > 0L
                && nowUptime - lastMatchedTapUptimeMillis < 500L)) return;
        lastMatchedTapUptimeMillis = nowUptime;
        lastMatchedTapEventUptimeMillis = eventUptime;
        int charged = penance.recordInfraction(PenanceInfraction.CENSORED_TAP, 1, nowMillis);
        PenanceChargeNotifier.show(this, penance,
                PenanceInfraction.CENSORED_TAP, charged, nowMillis);
    }

    private boolean matchesTapSource(
            AccessibilityNodeInfo source,
            String packageName,
            long eventUptime,
            long nowUptime) {
        if (source == null) return false;
        Rect bounds = new Rect();
        try {
            if (!source.isVisibleToUser() || source.getPackageName() == null
                    || !packageName.contentEquals(source.getPackageName())) return false;
            source.getBoundsInScreen(bounds);
            // Source bounds contain no finger coordinates. Never substitute an ancestor or a
            // companion record when this clicked target is empty or only partly censored.
            return tapTracker.matchesClickAt(bounds.left, bounds.top, bounds.right, bounds.bottom,
                    eventUptime, nowUptime);
        } finally {
            source.recycle();
        }
    }

    private void accountForegroundUsage(long nowMillis) {
        long started = foregroundSinceMillis;
        foregroundSinceMillis = nowMillis;
        if (timers == null || started <= 0L || nowMillis <= started
                || !appTimerRuntimeActive(nowMillis)) return;
        AppModeManager mode = new AppModeManager(this);
        timers.recordUsage(foregroundPackage, nowMillis - started,
                mode.timerScopeForForeground(foregroundPackage), nowMillis);
    }

    /** Returns true when the current foreground app was dismissed for a spent budget. */
    private boolean enforceForegroundLimit(long nowMillis) {
        if (timers == null || foregroundPackage.isEmpty()
                || !appTimerRuntimeActive(nowMillis)) {
            lastBlockedPackage = "";
            lastBlockedAtMillis = 0L;
            return false;
        }
        AppModeManager mode = new AppModeManager(this);
        Set<String> selected = mode.timerScopeForForeground(foregroundPackage);
        AppTimerManager.LimitStatus status = timers.limitStatus(
                foregroundPackage, selected, nowMillis);
        if (status == AppTimerManager.LimitStatus.NONE) return false;

        String blockedPackage = foregroundPackage;
        deactivateRecognition();
        boolean repeated = blockedPackage.equals(lastBlockedPackage)
                && nowMillis - lastBlockedAtMillis < 3_000L;
        lastBlockedPackage = blockedPackage;
        lastBlockedAtMillis = nowMillis;
        int message = status == AppTimerManager.LimitStatus.PER_APP
                ? com.subhub.app.R.string.app_timer_blocked_app
                : com.subhub.app.R.string.app_timer_blocked_total;
        if (!repeated) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            stats.recordLimitIntervention();
        }
        Log.i(TAG, "Daily app limit enforced for " + blockedPackage + " (" + status + ")");
        if (performGlobalAction(GLOBAL_ACTION_HOME)) {
            foregroundPackage = "";
            foregroundSinceMillis = 0L;
        }
        return true;
    }

    private boolean appTimerRuntimeActive(long nowMillis) {
        if (CensorLabCensorSession.isActive()) return false;
        boolean armed = new AppModeManager(this).isEffectivelyArmed(nowMillis);
        boolean limitsEnabled = new FeatureModuleManager(this).isLimitsEnabled();
        return AppTimerRuntimePolicy.shouldRun(armed, limitsEnabled);
    }

    private void reevaluateRecognition() {
        if (!running || settings == null) return;
        if (productQualificationRoute) {
            if (Build.VERSION.SDK_INT >= 34 && productSession != null) productSession.request();
            syncProductParticipation();
            return;
        }
        // Android Settings is handled by the lightweight Hardcore guard. Running the detector
        // there competes for the same accessibility overlay channel and wastes capture/ML work.
        boolean settingsPackage = HardcoreSettingsGuard.isSettingsPackage(foregroundPackage);
        AppModeManager appMode = new AppModeManager(this);
        FeatureModuleManager modules = new FeatureModuleManager(this);
        boolean shouldRun = !settingsPackage && appMode.shouldRecognize(foregroundPackage);
        Log.i(TAG, "RECOGNITION_DECISION package=" + foregroundPackage
                + " active=" + recognitionActive
                + " shouldRun=" + shouldRun
                + " armed=" + appMode.isArmed()
                + " censorEnabled=" + modules.isCensorEnabled()
                + " captureMethod=" + settings.loadCaptureMethod().name()
                + " selected="
                + appMode.getIncludedPackages().contains(foregroundPackage)
                + " settingsPackage=" + settingsPackage);
        if (shouldRun && !recognitionActive) activateRecognition();
        else if (!shouldRun && recognitionActive) deactivateRecognition();
        PopupStormManager.get().syncServiceParticipation(this,
                !settingsPackage && appMode.shouldShowPopups(foregroundPackage));
    }

    private void reevaluateSubliminals() {
        if (!running || subliminalOverlay == null) return;
        boolean shouldRun = !HardcoreSettingsGuard.isSettingsPackage(foregroundPackage)
                && new AppModeManager(this).shouldShowSubliminal(foregroundPackage);
        subliminalOverlay.setEligible(shouldRun);
    }

    private void refreshHardcoreSettingsGuard() {
        if (hardcoreSettingsGuard == null) return;
        boolean hardcore = new HardcoreModeManager(this).isEnabled();
        boolean domMode = ControllerPinManager.isDomModeActive();
        if (!hardcore || domMode) {
            hardcoreSettingsGuard.clear();
            return;
        }
        String expectedPackage = guardForegroundPackage;
        // Window-state events can be coalesced while Settings restores a Compose page. In
        // Hardcore/Sub mode, resolve the actual application window instead of trusting
        // getRootInActiveWindow(): once our concealment overlay is attached, some Android builds
        // report that overlay as the active root while navigating from App info to Storage.
        AccessibilityNodeInfo root = resolveHardcoreGuardApplicationRoot();
        if (root == null) root = getRootInActiveWindow();
        try {
            String activePackage = root != null && root.getPackageName() != null
                    ? root.getPackageName().toString() : expectedPackage;
            hardcoreSettingsGuard.refresh(activePackage, root);
        } finally {
            if (root != null) root.recycle();
        }
    }

    private AccessibilityNodeInfo resolveHardcoreGuardApplicationRoot() {
        List<AccessibilityWindowInfo> available = getWindows();
        if (available == null || available.isEmpty()) return null;
        AccessibilityNodeInfo bestRoot = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityWindowInfo window : available) {
            if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                continue;
            }
            AccessibilityNodeInfo candidate = window.getRoot();
            if (candidate == null) continue;
            String packageName = candidate.getPackageName() == null
                    ? "" : candidate.getPackageName().toString();
            int score = (window.isActive() ? 1_000 : 0)
                    + (window.isFocused() ? 500 : 0)
                    + (HardcoreSettingsGuard.isSettingsPackage(packageName) ? 100 : 0)
                    + window.getLayer();
            if (score > bestScore) {
                if (bestRoot != null) bestRoot.recycle();
                bestRoot = candidate;
                bestScore = score;
            } else {
                candidate.recycle();
            }
        }
        return bestRoot;
    }

    private void queueHardcoreSettingsGuardRefresh(long delayMillis) {
        if (!hardcoreGuardRefreshQueued.compareAndSet(false, true)) return;
        main.postDelayed(settledHardcoreGuardRefresh, Math.max(0L, delayMillis));
    }

    private RenderSourceReference resolveRenderReference(long epoch, long document, int window,
            int width, int height, long sourceTime, long cameraX, long cameraY, boolean certain) {
        RenderSourceReference.Origin origin = renderSourceOrigin.get();
        if (!certain || origin == null || origin.captureEpoch != epoch
                || origin.documentEpoch != document || origin.windowId != window
                || origin.viewportWidth != width || origin.viewportHeight != height) {
            return RenderSourceReference.UNKNOWN;
        }
        return renderSourceTimeline.resolve(origin, sourceTime, cameraX, cameraY);
    }

    private AsyncViewportAnchorSampler.State anchorState() {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        ScrollPosition camera = currentScrollPosition();
        return new AsyncViewportAnchorSampler.State(recognitionActive,
                visualDocumentEpoch.get(), activeApplicationWindowId.get(),
                metrics.widthPixels, metrics.heightPixels, -camera.scrollX, -camera.scrollY,
                lastMotionUptime, anchorFrameIntervalMillis);
    }

    private void startExperimentalAnchorSampler() {
        if (!BuildConfig.DEBUG || !getSharedPreferences("anchor_motion_experiment", MODE_PRIVATE)
                .getBoolean("enabled", false)) return;
        stopExperimentalAnchorSampler();
        WindowManager displayManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        Display anchorDisplay = displayManager == null ? null : displayManager.getDefaultDisplay();
        float refresh = anchorDisplay == null ? 60f : anchorDisplay.getRefreshRate();
        anchorFrameIntervalMillis = (int) Math.ceil(1000f / Math.max(30f, refresh));
        final long session = anchorSession.incrementAndGet();
        final long anchorCaptureEpoch = captureEpoch.token();
        ScheduledExecutorService executor = newScheduledWorker("SubHub-anchor-position",
                Process.THREAD_PRIORITY_BACKGROUND);
        LatestFrameBroker<AnchorPresentation> presenter = new LatestFrameBroker<>(
                command -> main.post(command), observation -> {
                    long now = SystemClock.uptimeMillis();
                    AsyncViewportAnchorSampler.State current = anchorState();
                    if (session != anchorSession.get() || !observation.reference.sameStructure(current)
                            || now < observation.result.readEndUptimeMs
                            || now - observation.result.readStartUptimeMs > 32L || overlay == null) {
                        anchorDeliveryDrops++;
                        return;
                    }
                    boolean shown = overlay.measureViewport((float) observation.result.measuredX,
                            (float) observation.result.measuredY, observation.result.readStartUptimeMs,
                            observation.result.readEndUptimeMs, observation.origin);
                    if (shown) anchorPresented++; else anchorDeliveryDrops++;
                    if (now - lastAnchorTelemetryUptime >= 100L) {
                        lastAnchorTelemetryUptime = now;
                        Log.i(TAG, "ANCHOR_ASYNC_PRESENT shown=" + shown
                                + " baseline=" + observation.result.baselineIdentity
                                + " ageMs=" + (now - observation.result.readStartUptimeMs)
                                + " readMs=" + (observation.result.readEndUptimeMs
                                - observation.result.readStartUptimeMs)
                                + " screen=" + Math.round(observation.result.measuredX)
                                + ',' + Math.round(observation.result.measuredY)
                                + " authority=" + current.cameraX + ',' + current.cameraY);
                    }
                }, ignored -> { });
        anchorPresenter = presenter;
        AsyncViewportAnchorSampler sampler = new AsyncViewportAnchorSampler(
                SystemClock::uptimeMillis, new AsyncViewportAnchorSampler.Worker() {
                    @Override public void execute(Runnable task) { executor.execute(task); }
                    @Override public void schedule(Runnable task, long delayMs) {
                        executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
                    }
                    @Override public void shutdown() { executor.shutdown(); }
                }, new AsyncViewportAnchorSampler.Source() {
                    @Override public AsyncViewportAnchorSampler.State state() { return anchorState(); }
                    @Override public List<AsyncViewportAnchorSampler.Anchor> acquire(
                            AsyncViewportAnchorSampler.State expected) {
                        if (inferenceDraining.get() || textRefreshRunning.get()) {
                            return Collections.emptyList();
                        }
                        AccessibilityNodeInfo root = getRootInActiveWindow();
                        if (root == null) return Collections.emptyList();
                        return AndroidViewportAnchors.collect(root, foregroundPackage,
                                expected.windowId, expected.width, expected.height);
                    }
                }, (result, reference) -> {
                    RenderSourceReference.Origin origin = new RenderSourceReference.Origin(
                            anchorCaptureEpoch, reference.epoch, reference.windowId,
                            reference.width, reference.height, result.baselineIdentity);
                    synchronized (renderSourceTimeline) {
                        if (session != anchorSession.get() || anchorCaptureEpoch != captureEpoch.token()
                                || !reference.sameStructure(anchorState())) return;
                        renderSourceTimeline.record(origin, result.readStartUptimeMs,
                                result.readEndUptimeMs, result.measuredX, result.measuredY);
                        renderSourceOrigin.set(origin);
                    }
                    presenter.submit(new AnchorPresentation(result, reference, origin));
                });
        experimentalAnchorSampler = sampler;
        anchorPresented = 0; anchorDeliveryDrops = 0;
        sampler.start();
        main.post(anchorTelemetry);
        Log.i(TAG, "ANCHOR_ASYNC_START experimental=true renderAuthority=presentation-only");
    }

    private void stopExperimentalAnchorSampler() {
        anchorSession.incrementAndGet();
        synchronized (renderSourceTimeline) {
            renderSourceOrigin.set(null);
            renderSourceTimeline.clear();
        }
        main.removeCallbacks(anchorTelemetry);
        LatestFrameBroker<AnchorPresentation> presenter = anchorPresenter;
        anchorPresenter = null;
        if (presenter != null) presenter.close();
        AsyncViewportAnchorSampler sampler = experimentalAnchorSampler;
        experimentalAnchorSampler = null;
        if (sampler != null) {
            try { sampler.close(); }
            catch (RuntimeException failure) { Log.w(TAG, "ANCHOR_ASYNC_CLOSE_FAILED", failure); }
        }
        if ((sampler != null || presenter != null) && overlay != null) overlay.clearMeasuredViewport();
    }

    private static final class AnchorPresentation {
        final ViewportAnchorGeometry.Result result;
        final AsyncViewportAnchorSampler.State reference;
        final RenderSourceReference.Origin origin;
        AnchorPresentation(ViewportAnchorGeometry.Result result, AsyncViewportAnchorSampler.State reference,
                RenderSourceReference.Origin origin) {
            this.result = result; this.reference = reference; this.origin = origin;
        }
    }

    private void activateRecognition() {
        if (recognitionActive || !running || worker == null) return;
        captureEpoch.invalidate();
        activeStartupSession = startupSessionSequence.incrementAndGet();
        Log.i(TAG, "STARTUP session=" + activeStartupSession
                + " phase=activation uptimeMs=" + SystemClock.uptimeMillis());
        try {
            droppedInferenceFrames.set(0L);
            firstFrameReported.set(false);
            firstOverlayReported.set(false);
            lastFastOverlayGeneration = Long.MIN_VALUE;
            resetScrollCompensation();
            overlay = new OverlayController(
                    this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
            CensorAppearance appearance = settings.loadAppearance();
            overlayNeedsSourceFrame = appearance.requiresSourceFrame();
            overlay.setAppearance(appearance);
            DetectorConfig config = settings.loadDetectorConfig();
            overlay.setMaxExtrapolationMs(0f);
            overlay.setDiagnostics(diagnosticsOverlayText());
            overlay.show();
            LatestFrameBroker<PendingScenePresentation> oldPresenter = scenePresenter;
            if (oldPresenter != null) oldPresenter.close();
            scenePresenter = createScenePresenter();
            // Publish active only after every synchronous authority surface exists. A failed
            // overlay/window setup must remain retryable on the next foreground window event.
            recognitionActive = true;
            Log.i(TAG, "Recognition activated for foreground package " + foregroundPackage);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                worker.execute(this::initializePipeline);
            }
            try {
                startExperimentalAnchorSampler();
            } catch (RuntimeException diagnosticFailure) {
                stopExperimentalAnchorSampler();
                Log.w(TAG, "ANCHOR_ASYNC_START_FAILED", diagnosticFailure);
            }
        } catch (RuntimeException failure) {
            recognitionActive = false;
            captureEpoch.invalidate();
            LatestFrameBroker<PendingScenePresentation> presenter = scenePresenter;
            scenePresenter = null;
            if (presenter != null) presenter.close();
            if (overlay != null) overlay.close();
            overlay = null;
            DiagnosticsRepository.fail(DIAGNOSTICS_MODE, failure);
            Log.e(TAG, "Recognition activation failed", failure);
        }
    }

    private void deactivateRecognition() {
        if (productQualificationRoute) {
            if (Build.VERSION.SDK_INT >= 34 && productSession != null) productSession.stop();
            recognitionActive = false;
            PopupStormManager.get().updateDetections(Collections.emptyList());
            dwellTracker.clear(); tapTracker.clear();
            return;
        }
        if (!recognitionActive && overlay == null) return;
        recognitionActive = false;
        stopExperimentalAnchorSampler();
        captureEpoch.invalidate();
        invalidateCurrentScene("recognition-deactivated");
        Log.i(TAG, "Recognition suspended for foreground package " + foregroundPackage);
        screenshotScheduler.stop();
        discardPendingInference();
        clearQualityBackfill();
        LatestFrameBroker<PendingScenePresentation> presenter = scenePresenter;
        scenePresenter = null;
        if (presenter != null) presenter.close();
        DiagnosticsRepository.stop(DIAGNOSTICS_MODE);
        if (overlay != null) overlay.close();
        overlay = null;
        PopupStormManager.get().updateDetections(Collections.emptyList());
        dwellTracker.clear();
        tapTracker.clear();
        resetTextSnapshots();
        resetScrollCompensation();
    }

    private void resetScrollCompensation() {
        sourceTrackContinuity.clear();
        qualityConcurrencyGovernor.reset();
        invalidateCurrentScene("scroll-state-reset");
        invalidateWorldCache("scroll-state-reset");
        cancelPendingTextConfirmation();
        synchronized (scrollStateLock) {
            cumulativeScrollX.set(0L);
            cumulativeScrollY.set(0L);
            pendingTrackerOffsetX.set(0L);
            pendingTrackerOffsetY.set(0L);
            trackerScrollX = 0L;
            trackerScrollY = 0L;
        }
        captureScrollTimeline.clear();
        cacheAccessibilityText(TextDetectionSnapshot.EMPTY);
        clearCachedOcr();
        cachedQualityVisual = VisualDetectionSnapshot.EMPTY;
        qualityVisualStabilizer.clear();
        qualityConfirmationRequested.set(false);
        qualityConfirmationBurstUsed.set(false);
        accessibilityTextStabilizer.clear();
        accessibilityCandidateScans.set(0);
        lastPublishedTextFingerprint = "";
        skippedUnchangedTextPublishes = 0L;
        clearVisualGeometryHistory();
        accessibilityTextCandidatesPresent = false;
        settledInferenceNeeded.set(false);
        discardPendingInference();
        clearQualityBackfill();
        motionGeneration.incrementAndGet();
        lastMotionUptime = 0L;
        lastInferenceUptime = 0L;
        lastQualityInferenceUptime = 0L;
        lastSuccessfulQualityDurationMs = 0L;
        // screenshotScheduler retains actual dispatch spacing across logical scene changes.
        lastOcrCompletionUptime = 0L;
        if (motionEstimator != null) motionEstimator.reset();
        scrollMotionResolver.reset();
        scrollCompanionDeduplicator.reset();
        scrollDeltaStabilizer.reset();
        main.removeCallbacks(settledScrollTrace);
        lastScrollTraceEventUptime = 0L;
        touchInteractionActive = false;
        touchTraceStartedUptime = 0L;
        textRefreshRequested.set(true);
        main.removeCallbacks(settledTextRefresh);
    }

    private void configureAccessibilityCadence(DetectorConfig config) {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        boolean ultra = config != null && config.getInferenceThreads() >= 4;
        info.notificationTimeout = ultra ? 0L : 16L;
        if (ultra) info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        else info.flags &= ~AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(info);
    }

    @Override
    public void onInterrupt() {
        // Android may temporarily interrupt feedback; the scheduled capture loop remains owned
        // here.
    }

    private String diagnosticsOverlayText() {
        return diagnosticsOverlayText(DiagnosticsRepository.snapshot());
    }

    private String diagnosticsOverlayText(DiagnosticsRepository.Snapshot snapshot) {
        return settings != null && settings.preferences().getBoolean(
                DiagnosticsRepository.PREF_OVERLAY, false)
                ? DiagnosticsRepository.overlayText(snapshot) : "";
    }

    /** Latest-only presenter: one closed scene is sampled and applied on each display tick. */
    private LatestFrameBroker<PendingScenePresentation> createScenePresenter() {
        return new LatestFrameBroker<>(
                command -> main.post(() -> Choreographer.getInstance()
                        .postFrameCallback(frameTimeNanos -> command.run())),
                PendingScenePresentation::present,
                PendingScenePresentation::dispose,
                1);
    }

    private static final class PendingScenePresentation {
        private final String sceneId;
        private final Runnable presenter;
        private final Runnable disposer;
        private boolean consumed;

        private PendingScenePresentation(
                String sceneId, Runnable presenter, Runnable disposer) {
            this.sceneId = sceneId;
            this.presenter = presenter;
            this.disposer = disposer;
        }

        private synchronized void present() {
            if (consumed) return;
            consumed = true;
            presenter.run();
        }

        private synchronized void dispose() {
            if (consumed) return;
            consumed = true;
            disposer.run();
            CensorLabLog.i(TAG, "SCENE_QUEUE_DROP id=" + sceneId + " reason=superseded");
        }
    }

    /** Bridges the pure scene state machine to the two inference workers without UI authority. */
    private static final class PendingScrollEvent {
        final AccessibilityEvent event;
        final long sequence, receivedUptime, sourceUptime, captureEpoch, documentEpoch;
        final int windowId;
        final String packageName;
        final ScrollLookupScope scope;
        volatile long startedUptime, resolvedUptime, appliedUptime;
        PendingScrollEvent(AccessibilityEvent event, long sequence, long receivedUptime,
                long captureEpoch, long documentEpoch, int windowId, String packageName,
                long inputGeneration, boolean provisional) {
            this.event = event;
            this.sequence = sequence;
            this.receivedUptime = receivedUptime;
            long source = event.getEventTime();
            this.sourceUptime = source > 0 && source <= receivedUptime ? source : receivedUptime;
            this.captureEpoch = captureEpoch;
            this.documentEpoch = documentEpoch;
            this.windowId = windowId;
            this.packageName = packageName;
            this.scope = new ScrollLookupScope(captureEpoch, inputGeneration, documentEpoch,
                    windowId, event.getWindowId(), packageName, provisional);
        }
    }

    private static final class SceneContext {
        private final SpatialRegionCache.Frame spatialFrame;
        private final SceneTransactionCoordinator.SceneKey key;
        private final SceneTransactionCoordinator.Mode mode;
        private final boolean qualityExpected;
        private final boolean continuousMotionInference;
        private final long joinDeadlineUptimeMillis;
        private final long visibleDeadlineUptimeMillis;
        private final CountDownLatch commitReady = new CountDownLatch(1);
        private final AtomicReference<SceneTransactionCoordinator.Commit<Detection>>
                deliveredCommit = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private SceneContext(
                SceneTransactionCoordinator.SceneKey key,
                SceneTransactionCoordinator.Mode mode,
                boolean qualityExpected,
                long joinDeadlineUptimeMillis,
                long visibleDeadlineUptimeMillis,
                boolean continuousMotionInference,
                SpatialRegionCache.Frame spatialFrame) {
            this.spatialFrame = spatialFrame;
            this.key = key;
            this.mode = mode;
            this.qualityExpected = qualityExpected;
            this.continuousMotionInference = continuousMotionInference;
            this.joinDeadlineUptimeMillis = joinDeadlineUptimeMillis;
            this.visibleDeadlineUptimeMillis = visibleDeadlineUptimeMillis;
        }

        private void deliver(SceneTransactionCoordinator.Commit<Detection> commit) {
            if (commit != null && deliveredCommit.compareAndSet(null, commit)) {
                commitReady.countDown();
            }
        }

        private SceneTransactionCoordinator.Commit<Detection> awaitCommit() {
            long waitMs = Math.max(0L,
                    joinDeadlineUptimeMillis - SystemClock.uptimeMillis());
            try {
                if (waitMs > 0L) commitReady.await(waitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return deliveredCommit.get();
        }

        private void cancel(String reason) {
            cancelled.set(true);
            commitReady.countDown();
        }
    }

    private static final class QualityInferenceFrame {
        private RenderSourceReference renderReference = RenderSourceReference.UNKNOWN;
        private Bitmap sourceFrame;
        private HardwareBuffer sourceBuffer;
        private final long epoch;
        private final long scrollX;
        private final long scrollY;
        private final long motionGeneration;
        private final int sourceWidth;
        private final int sourceHeight;
        private final int inferenceResolution;
        private final long capturedAtUptimeMillis;
        private final long fastSubmissionSequence;
        private final long visualDocumentEpoch;
        private final String scrollSurfaceKey;
        private final long surfaceTelemetryToken;
        private final boolean phaseCertain;
        private final int viewportWidth;
        private final int viewportHeight;
        private final int captureWindowId;
        private final SceneContext scene;

        private QualityInferenceFrame(
                Bitmap sourceFrame,
                HardwareBuffer sourceBuffer,
                long epoch,
                long scrollX,
                long scrollY,
                long motionGeneration,
                int sourceWidth,
                int sourceHeight,
                int inferenceResolution,
                long capturedAtUptimeMillis,
                long fastSubmissionSequence,
                long visualDocumentEpoch,
                String scrollSurfaceKey,
                long surfaceTelemetryToken,
                boolean phaseCertain,
                int viewportWidth,
                int viewportHeight,
                int captureWindowId,
                SceneContext scene) {
            this.sourceFrame = sourceFrame;
            this.sourceBuffer = sourceBuffer;
            this.epoch = epoch;
            this.scrollX = scrollX;
            this.scrollY = scrollY;
            this.motionGeneration = motionGeneration;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.inferenceResolution = inferenceResolution;
            this.capturedAtUptimeMillis = capturedAtUptimeMillis;
            this.fastSubmissionSequence = fastSubmissionSequence;
            this.visualDocumentEpoch = visualDocumentEpoch;
            this.scrollSurfaceKey = scrollSurfaceKey == null ? "" : scrollSurfaceKey;
            this.surfaceTelemetryToken = surfaceTelemetryToken;
            this.phaseCertain = phaseCertain;
            this.viewportWidth = Math.max(1, viewportWidth);
            this.viewportHeight = Math.max(1, viewportHeight);
            this.captureWindowId = captureWindowId;
            this.scene = scene;
        }

        private void recycle() {
            if (sourceFrame != null && !sourceFrame.isRecycled()) sourceFrame.recycle();
            sourceFrame = null;
            if (sourceBuffer != null) sourceBuffer.close();
            sourceBuffer = null;
        }
    }

    private static final class InferenceFrame {
        private Bitmap frame;
        private final long epoch;
        private final long scrollX;
        private final long scrollY;
        private final long motionGeneration;
        private final int sourceWidth;
        private final int sourceHeight;
        private final boolean retainedSourceFrame;
        private final boolean continuousMotionInference;
        private final boolean qualityRefine;
        private final boolean qualityConfirmation;
        /** Request-time ownership, assigned before admission to the inference worker. */
        private boolean labCensorFrame;
        /** Request start retained for legacy metrics; not the pixel-capture or receipt time. */
        private final long capturedAtUptimeMillis;
        private final long requestedScrollX;
        private final long requestedScrollY;
        private final long requestedGeneration;
        private final long screenshotUptimeMillis;
        private final CaptureTimeReference captureTime;
        private final boolean capturePhaseUncertain;
        private final long captureEventDeliveryDelayMs;
        private final long visualDocumentEpoch;
        private final String scrollSurfaceKey;
        private final long fastSubmissionSequence;
        private final int captureWindowId;
        private final SceneContext scene;
        private FastPriorityInferenceGate.FastDemand fastDemand;

        private InferenceFrame(
                Bitmap frame,
                long epoch,
                long scrollX,
                long scrollY,
                long motionGeneration,
                int sourceWidth,
                int sourceHeight,
                boolean retainedSourceFrame,
                boolean continuousMotionInference,
                boolean qualityRefine,
                boolean qualityConfirmation,
                long capturedAtUptimeMillis,
                long requestedScrollX,
                long requestedScrollY,
                long requestedGeneration,
                CaptureTimeReference captureTime,
                boolean capturePhaseUncertain,
                long captureEventDeliveryDelayMs,
                long visualDocumentEpoch,
                String scrollSurfaceKey,
                long fastSubmissionSequence,
                int captureWindowId,
                SceneContext scene) {
            this.frame = frame;
            this.epoch = epoch;
            this.scrollX = scrollX;
            this.scrollY = scrollY;
            this.motionGeneration = motionGeneration;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.retainedSourceFrame = retainedSourceFrame;
            this.continuousMotionInference = continuousMotionInference;
            this.qualityRefine = qualityRefine;
            this.qualityConfirmation = qualityConfirmation;
            this.capturedAtUptimeMillis = capturedAtUptimeMillis;
            this.requestedScrollX = requestedScrollX;
            this.requestedScrollY = requestedScrollY;
            this.requestedGeneration = requestedGeneration;
            this.captureTime = captureTime;
            this.screenshotUptimeMillis = captureTime.reportedUptimeMillis;
            this.capturePhaseUncertain = capturePhaseUncertain;
            this.captureEventDeliveryDelayMs = Math.max(0L, captureEventDeliveryDelayMs);
            this.visualDocumentEpoch = visualDocumentEpoch;
            this.scrollSurfaceKey = scrollSurfaceKey == null ? "" : scrollSurfaceKey;
            this.fastSubmissionSequence = fastSubmissionSequence;
            this.captureWindowId = captureWindowId;
            this.scene = scene;
        }

        private Bitmap detachFrame() {
            Bitmap detached = frame;
            frame = null;
            return detached;
        }

        private void recycle() {
            if (fastDemand != null) fastDemand.close();
            fastDemand = null;
            if (frame != null && !frame.isRecycled()) frame.recycle();
            frame = null;
        }
    }

    /** Main-thread display metadata only: deliberately owns no pixels or live tracker objects. */
    private static final class DisplayedQualityBasis {
        final LateQualityPresentationGate.Stamp stamp;
        final boolean phaseCertain;
        final List<TrackedObject> tracks;
        final List<Detection> baseRegions;
        final RenderSourceReference renderReference;

        DisplayedQualityBasis(LateQualityPresentationGate.Stamp stamp, boolean phaseCertain,
                List<TrackedObject> tracks, List<Detection> baseRegions,
                RenderSourceReference renderReference) {
            this.stamp = stamp;
            this.phaseCertain = phaseCertain;
            this.renderReference = renderReference;
            List<TrackedObject> copies = new ArrayList<>();
            for (TrackedObject track : tracks) copies.add(track.snapshot());
            this.tracks = Collections.unmodifiableList(copies);
            this.baseRegions = Collections.unmodifiableList(new ArrayList<>(baseRegions));
        }
    }

    /** Immutable world-space quality evidence waiting for any compatible later fast publication. */
    private static final class LateQualityPresentation {
        private final AtomicBoolean immediatelyPresented = new AtomicBoolean();
        private final AtomicBoolean fastPresented = new AtomicBoolean();
        private final List<QualityBackfillCoordinator.BackfillRegion> worldRegions;
        private final long captureEpoch;
        private final long documentEpoch;
        private final String surfaceKey;
        private final long surfaceTelemetryToken;
        private final long motionGeneration;
        private final long fastSubmissionSequence;
        private final long scrollX;
        private final long scrollY;
        private final int sourceWidth;
        private final int sourceHeight;
        private final int viewportWidth;
        private final int viewportHeight;
        private final long readyAtUptime;
        private final RenderSourceReference renderReference;
        private final long capturedAtUptime;
        private final int captureWindowId;

        private LateQualityPresentation(
                QualityInferenceFrame source,
                List<QualityBackfillCoordinator.BackfillRegion> worldRegions,
                long readyAtUptime) {
            this.worldRegions = Collections.unmodifiableList(new ArrayList<>(worldRegions));
            captureEpoch = source.epoch;
            documentEpoch = source.visualDocumentEpoch;
            surfaceKey = source.scrollSurfaceKey;
            surfaceTelemetryToken = source.surfaceTelemetryToken;
            motionGeneration = source.motionGeneration;
            fastSubmissionSequence = source.fastSubmissionSequence;
            scrollX = source.scrollX;
            scrollY = source.scrollY;
            sourceWidth = source.sourceWidth;
            sourceHeight = source.sourceHeight;
            viewportWidth = source.viewportWidth;
            viewportHeight = source.viewportHeight;
            this.readyAtUptime = Math.max(0L, readyAtUptime);
            this.renderReference = source.renderReference;
            this.capturedAtUptime = source.capturedAtUptimeMillis;
            this.captureWindowId = source.captureWindowId;
            this.phaseCertain = source.phaseCertain;
        }

        private final boolean phaseCertain;

        private LateQualityPresentationGate.Stamp stamp() {
            return new LateQualityPresentationGate.Stamp(fastSubmissionSequence, captureEpoch,
                    documentEpoch, surfaceKey, surfaceTelemetryToken, motionGeneration,
                    scrollX, scrollY, sourceWidth, sourceHeight, viewportWidth, viewportHeight,
                    captureWindowId, surfaceKey.isEmpty());
        }

        private List<Detection> screenDetections(
                long consumerCameraX,
                long consumerCameraY,
                int consumerSourceWidth,
                int consumerSourceHeight,
                int consumerViewportWidth,
                int consumerViewportHeight) {
            if (worldRegions.isEmpty()) return Collections.emptyList();
            List<Detection> result = new ArrayList<>(worldRegions.size());
            for (QualityBackfillCoordinator.BackfillRegion region : worldRegions) {
                if (region == null || region.worldBox() == null) continue;
                BBox screenBox = ContentSpaceRegionCache.worldToScreen(
                        region.worldBox(), consumerCameraX, consumerCameraY,
                        consumerSourceWidth, consumerSourceHeight,
                        consumerViewportWidth, consumerViewportHeight);
                if (screenBox == null || screenBox.getArea() <= 0L) continue;
                result.add(new Detection(
                        region.className(), region.category(), region.confidence(),
                        screenBox, region.nsfw(), region.exposed(),
                        Detection.ObservationSource.QUALITY_VISUAL,
                        Detection.GeometryQuality.MODEL, region.anchorKey())
                        .withRenderSourceReference(renderReference));
            }
            return result.isEmpty()
                    ? Collections.emptyList() : Collections.unmodifiableList(result);
        }

        private Match matchFast(
                long consumerFastSequence,
                long consumerEpoch,
                long consumerDocumentEpoch,
                String consumerSurfaceKey,
                long consumerSurfaceTelemetryToken,
                long consumerMotionGeneration,
                long consumerCameraX,
                long consumerCameraY,
                int consumerSourceWidth,
                int consumerSourceHeight,
                int consumerViewportWidth,
                int consumerViewportHeight,
                int consumerWindowId) {
            LateQualityPresentationGate.Decision decision =
                    LateQualityPresentationGate.decide(
                            new LateQualityPresentationGate.Stamp(
                                    fastSubmissionSequence, captureEpoch, documentEpoch,
                                    surfaceKey, surfaceTelemetryToken, motionGeneration,
                                    scrollX, scrollY, sourceWidth, sourceHeight,
                                    viewportWidth, viewportHeight, captureWindowId,
                                    surfaceKey.isEmpty()),
                            new LateQualityPresentationGate.Stamp(
                                    consumerFastSequence, consumerEpoch, consumerDocumentEpoch,
                                    consumerSurfaceKey, consumerSurfaceTelemetryToken,
                                    consumerMotionGeneration, consumerCameraX, consumerCameraY,
                                    consumerSourceWidth, consumerSourceHeight,
                                    consumerViewportWidth, consumerViewportHeight, consumerWindowId,
                                    consumerSurfaceKey == null || consumerSurfaceKey.isEmpty()),
                            phaseCertain, capturedAtUptime, SystemClock.uptimeMillis(),
                            QualityBackfillCoordinator.DEFAULT_MAX_AGE_MS);
            if (decision == LateQualityPresentationGate.Decision.MATCH) return Match.MATCH;
            if (decision == LateQualityPresentationGate.Decision.WAIT_FOR_NEXT_FAST) {
                return Match.WAIT_FOR_NEXT_FAST;
            }
            return Match.STALE;
        }

        private enum Match { MATCH, WAIT_FOR_NEXT_FAST, STALE }
    }

    private static final class VisualGeometryDelta {
        private final int matched;
        private final int changed;
        private final int maxCenterDeltaPx;
        private final int maxSizeDeltaPx;

        private VisualGeometryDelta(
                int matched,
                int changed,
                int maxCenterDeltaPx,
                int maxSizeDeltaPx) {
            this.matched = matched;
            this.changed = changed;
            this.maxCenterDeltaPx = maxCenterDeltaPx;
            this.maxSizeDeltaPx = maxSizeDeltaPx;
        }
    }

    private static final class WorldTrackFrame {
        private final VisualTrackArbitrator.Result arbitration;
        private final List<TrackedObject> tracks;
        private final long cameraX;
        private final long cameraY;
        private final long motionGeneration;

        private WorldTrackFrame(
                VisualTrackArbitrator.Result arbitration,
                long cameraX,
                long cameraY,
                long motionGeneration) {
            this.arbitration = arbitration;
            this.tracks = arbitration.tracks();
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.motionGeneration = motionGeneration;
        }
    }

    private static class ScrollPosition {
        final long scrollX;
        final long scrollY;

        ScrollPosition(long scrollX, long scrollY) {
            this.scrollX = scrollX;
            this.scrollY = scrollY;
        }
    }

    private static final class TextDetectionSnapshot {
        private static final TextDetectionSnapshot EMPTY = new TextDetectionSnapshot(
                Collections.emptyList(), 1, 1, 0L, 0L, 0L);
        private final List<Detection> detections;
        private final int width;
        private final int height;
        private final long scrollX;
        private final long scrollY;
        private final long capturedAtUptimeMillis;

        private TextDetectionSnapshot(
                List<Detection> detections,
                int width,
                int height,
                long scrollX,
                long scrollY) {
            this(detections, width, height, scrollX, scrollY,
                    SystemClock.uptimeMillis());
        }

        private TextDetectionSnapshot(
                List<Detection> detections,
                int width,
                int height,
                long scrollX,
                long scrollY,
                long capturedAtUptimeMillis) {
            this.detections = detections == null ? Collections.emptyList() : detections;
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            this.scrollX = scrollX;
            this.scrollY = scrollY;
            this.capturedAtUptimeMillis = Math.max(0L, capturedAtUptimeMillis);
        }
    }

    private static final class VisualDetectionSnapshot {
        private static final VisualDetectionSnapshot EMPTY = new VisualDetectionSnapshot(
                Collections.emptyList(), 1, 1, 0L, 0L, 0L, -1L);
        private final List<Detection> detections;
        private final int width;
        private final int height;
        private final long scrollX;
        private final long scrollY;
        private final long capturedAtUptimeMillis;
        private final long motionGeneration;

        private VisualDetectionSnapshot(
                List<Detection> detections,
                int width,
                int height,
                long scrollX,
                long scrollY,
                long capturedAtUptimeMillis,
                long motionGeneration) {
            this.detections = detections == null || detections.isEmpty()
                    ? Collections.emptyList() : new ArrayList<>(detections);
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            this.scrollX = scrollX;
            this.scrollY = scrollY;
            this.capturedAtUptimeMillis = Math.max(0L, capturedAtUptimeMillis);
            this.motionGeneration = motionGeneration;
        }
    }

    private static final class ScrollAlignment extends ScrollPosition {
        final int correctedTracks, globalCorrections, localCorrections, localPairs, maxAbsExtraDy;
        final long correctionCpuUs;
        ScrollAlignment(long scrollX, long scrollY, SourceTrackContinuity.Proposal proposal, long correctionCpuUs) {
            super(scrollX, scrollY);
            this.correctedTracks = proposal.offsets.size();
            this.globalCorrections = proposal.global;
            this.localCorrections = proposal.local;
            this.localPairs = proposal.pairs;
            this.maxAbsExtraDy = proposal.maxAbsDy();
            this.correctionCpuUs = correctionCpuUs;
        }
    }

    @Override
    public void onDestroy() {
        if (labCensorSubscription != null) {
            labCensorSubscription.close();
            labCensorSubscription = null;
        }
        com.subhub.app.appmode.ForegroundAppState.clear();
        accountForegroundUsage(System.currentTimeMillis());
        running = false;
        if (scrollLookup != null) scrollLookup.close();
        if (scrollLookupWorker != null) scrollLookupWorker.shutdown();
        main.removeCallbacks(timerTick);
        deactivateRecognition();
        PopupStormManager.get().stop();
        if (settings != null) {
            settings.preferences().unregisterOnSharedPreferenceChangeListener(listener);
        }
        if (productQualificationRoute && Build.VERSION.SDK_INT >= 34) {
            CensorProductSession selected = productSession;
            productSession = null;
            try { if (selected != null) selected.close(); }
            finally {
                if (selected != null) ProductCensorQualification.disconnected(selected);
                if (hardcoreSettingsGuard != null) hardcoreSettingsGuard.clear();
                hardcoreSettingsGuard = null;
                if (subliminalOverlay != null) subliminalOverlay.close();
                subliminalOverlay = null;
                main.removeCallbacks(settledHardcoreGuardRefresh);
                hardcoreGuardRefreshQueued.set(false);
                super.onDestroy();
            }
            return;
        }
        if (worker != null) {
            worker.execute(() -> {
                if (Build.VERSION.SDK_INT >= 29 && gpuBitmapPreparer != null) {
                    gpuBitmapPreparer.close();
                    gpuBitmapPreparer = null;
                }
            });
            worker.shutdown();
        }
        discardPendingInference();
        if (inferenceWorker != null) inferenceWorker.shutdownNow();
        qualityBackfillRunner.close();
        if (qualityInferenceWorker != null) qualityInferenceWorker.shutdownNow();
        if (screenshotText != null) screenshotText.close();
        screenshotText = null;
        // Queued OCR work drains through its stale-request gate. In-flight SDK work releases
        // input pixels at its terminal callback, not while the recognizer may still read them.
        ocrRunning.set(false);
        if (ocrWorker != null) ocrWorker.shutdown();
        if (textWorker != null) textWorker.shutdownNow();
        if (detector != null) detector.close();
        if (fastDetector != null) fastDetector.close();
        fastDetector = null;
        if (motionEstimator != null) motionEstimator.close();
        motionEstimator = null;
        if (preparedFrameRecorder != null) preparedFrameRecorder.close();
        if (chromeGeometryProbe != null) chromeGeometryProbe.close();
        if (hardcoreSettingsGuard != null) hardcoreSettingsGuard.clear();
        hardcoreSettingsGuard = null;
        if (subliminalOverlay != null) subliminalOverlay.close();
        subliminalOverlay = null;
        main.removeCallbacks(settledHardcoreGuardRefresh);
        hardcoreGuardRefreshQueued.set(false);
        main.removeCallbacks(settledTextRefresh);
        main.removeCallbacks(contentTextRefresh);
        main.removeCallbacks(staleAccessibilityTextExpiry);
        contentTextRefreshScheduled.set(false);
        dwellTracker.clear();
        tapTracker.clear();
        recognitionActive = false;
        super.onDestroy();
    }
}
