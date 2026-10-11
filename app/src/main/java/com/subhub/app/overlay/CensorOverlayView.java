package com.subhub.app.overlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.os.Build;
import android.graphics.RenderNode;
import android.view.Choreographer;
import android.view.View;

import com.subhub.app.R;
import com.subhub.app.capture.CustomImagePool;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.TrackedObject;
import com.subhub.app.detection.RenderSourceReference;
import com.subhub.app.diagnostics.CensorLabLog;
import com.subhub.app.diagnostics.CensorLabCensorSession;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.LockpadArtwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Full-screen, touch-through renderer for every recovered censor style and reverse mode. */
final class CensorOverlayView extends View {
    private static final String MOTION_TAG = "CensorMotion";
    private static final long MOTION_TRACE_INTERVAL_MS = 32L;
    private static final long CONSOLIDATION_TRACE_INTERVAL_MS = 250L;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint diagnosticsFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint diagnosticsText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint nearestPaint = new Paint();
    private final Paint filteredPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint cyanShiftPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint redShiftPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint tapeRedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tapeYellowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF drawRect = new RectF();
    private final Rect sourceRect = new Rect();
    private final Rect scratchRect = new Rect();
    private final Rect effectSourceRect = new Rect();
    private final Rect bandSourceRect = new Rect();
    private final RectF effectRect = new RectF();
    private final RectF bandRect = new RectF();
    private final Matrix borderShaderMatrix = new Matrix();
    private final CustomImagePool customImages;
    private final LockpadArtwork lockpad = new LockpadArtwork();
    private final Map<Integer, SolidRenderLayer> solidRenderLayers = new HashMap<>();
    private final List<LabelPlacement> labelPlacements = new ArrayList<>();
    private final CensorTapTracker tapTracker = CensorTapTracker.shared();
    private final List<BBox> displayedTapRegions = new ArrayList<>();
    private final int[] tapScreenLocation = new int[2];
    private final Rect tapVisibleRect = new Rect();
    private long visualTapScope;
    private long textTapScope;
    private boolean tapSourceLabOwned;
    private long tapSourceScope = Long.MIN_VALUE;
    private boolean visualTapSourceLabOwned;
    private boolean textTapSourceLabOwned;
    private final Runnable refreshDisplayedTapGeometry = () -> {
        if (isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE) invalidate();
        else tapTracker.clearDisplayed(this);
    };

    private List<RenderTrackSnapshot> liveTracks = new ArrayList<>();
    private List<RenderTrackSnapshot> cachedTracks = new ArrayList<>();
    private List<RenderTrackSnapshot> tracks = new ArrayList<>();
    private List<RenderTrackSnapshot> textTracks = new ArrayList<>();
    private CensorAppearance appearance = CensorAppearance.defaults();
    private int captureWidth = 1;
    private int captureHeight = 1;
    private int textCaptureWidth = 1;
    private int textCaptureHeight = 1;
    private Bitmap frame;
    private Runnable frameRelease;
    private Bitmap effectScratch;
    private Canvas effectCanvas;
    private Bitmap noiseBitmap;
    private int[] noisePixels;
    private long noiseTick = Long.MIN_VALUE;
    private long lastConsolidationTraceUptime;
    private int lastConsolidationInput = -1;
    private int lastConsolidationOutput = -1;
    private String diagnostics = "";
    private float contentOffsetX;
    private float contentOffsetY;
    private float renderContentOffsetX;
    private float renderContentOffsetY;
    private float renderViewportLeadX;
    private float renderViewportLeadY;
    private final ViewportMotion viewportMotion = new ViewportMotion();
    private final ScrollMotionDiagnostics scrollMotionDiagnostics = new ScrollMotionDiagnostics();
    private final ContinuousTrackSteering visualSteering = new ContinuousTrackSteering();
    private final ContinuousTrackSteering textSteering = new ContinuousTrackSteering();
    private final StableVisualLayout visualLayout = new StableVisualLayout();
    private final PersonCoveragePresentation personCoverage = new PersonCoveragePresentation();
    private Set<Integer> suppressedPersonDecorations = Collections.emptySet();
    private final Runnable expirePersonCoverage = () -> {
        long now = SystemClock.uptimeMillis();
        latestMutationUptime = now;
        schedulePersonCoverageExpiry(now);
        postInvalidateOnAnimation();
    };
    private float sourceFrameOffsetX;
    private float sourceFrameOffsetY;
    private RenderSourceReference bitmapReference = RenderSourceReference.UNKNOWN;
    private RenderSourceReference.Origin measuredOrigin;
    private RenderSourceReference currentRenderReference = RenderSourceReference.UNKNOWN;
    private RenderSourceReference activeEffectReference = RenderSourceReference.UNKNOWN;
    private float activeEffectOffsetX, activeEffectOffsetY;
    private final Map<Integer, RenderSourceReference> visualReferences = new HashMap<>();
    private final Map<Integer, RenderSourceReference> textReferences = new HashMap<>();
    private float textContentOffsetX;
    private float textContentOffsetY;
    private boolean worldSpaceTracks;
    private boolean worldSpaceText;
    private long motionSequence;
    private long motionInputUptime;
    private long lastMotionTraceInputUptime;
    private boolean motionDrawPending;
    private boolean motionAnimationWasActive;
    private long borderAnimationTimeOverride = -1L;
    private long renderTimeOverride = -1L;
    private long presentationFrameTimeMillis = -1L;
    private long lastPresentationFrameTimeMillis = -1L;
    private long renderTickMillis;
    private long latestMutationUptime;
    private long tracksPublishedAtMillis;
    private long activeRenderTimeMillis;
    private float maxExtrapolationMs = 180f;
    private boolean frameCallbackPosted;
    private long frameCallbackGeneration;
    private float activePredictionX;
    private float activePredictionY;
    private final Choreographer.FrameCallback frameCallback = frameTimeNanos -> {
        frameCallbackPosted = false;
        notePresentationFrame(frameTimeNanos / 1_000_000L);
        invalidate();
        scheduleNextFrame(presentationFrameTimeMillis);
    };

    CensorOverlayView(Context context) {
        super(context);
        customImages = new CustomImagePool(context);
        nearestPaint.setFilterBitmap(false);
        cyanShiftPaint.setAlpha(220);
        cyanShiftPaint.setColorFilter(new PorterDuffColorFilter(
                Color.rgb(0, 180, 255), PorterDuff.Mode.SRC_ATOP));
        redShiftPaint.setAlpha(220);
        redShiftPaint.setColorFilter(new PorterDuffColorFilter(
                Color.rgb(255, 0, 80), PorterDuff.Mode.SRC_ATOP));
        tapeRedPaint.setColor(Color.rgb(229, 57, 53));
        tapeRedPaint.setStrokeCap(Paint.Cap.SQUARE);
        tapeYellowPaint.setColor(Color.rgb(243, 211, 59));
        tapeYellowPaint.setStrokeCap(Paint.Cap.SQUARE);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(dp(2));
        label.setColor(context.getColor(R.color.text_primary));
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(dp(11));
        label.setFakeBoldText(true);
        label.setShadowLayer(dp(2), 0, dp(1), Color.BLACK);
        diagnosticsFill.setColor(Color.rgb(13, 13, 20));
        diagnosticsFill.setAlpha(225);
        diagnosticsText.setColor(context.getColor(R.color.text_primary));
        diagnosticsText.setTextSize(dp(10));
        diagnosticsText.setFakeBoldText(true);
    }

    void setTracks(
            List<TrackedObject> value,
            int sourceWidth,
            int sourceHeight,
            Bitmap latestFrame) {
        setTracks(value, sourceWidth, sourceHeight, latestFrame, 0, 0);
    }

    void setTracks(
            List<TrackedObject> value,
            int sourceWidth,
            int sourceHeight,
            Bitmap latestFrame,
            int motionX,
            int motionY) {
        setTracks(value, sourceWidth, sourceHeight, latestFrame,
                motionX, motionY, 0, 0, null);
    }

    /**
     * Publishes tracks already projected to the live viewport while retaining a source frame
     * captured at an earlier scroll position for blur, pixelate, and glitch effects.
     */
    void setTracks(
            List<TrackedObject> value,
            int sourceWidth,
            int sourceHeight,
            Bitmap latestFrame,
            int motionX,
            int motionY,
            int sourceMotionX,
            int sourceMotionY) {
        setTracks(value, sourceWidth, sourceHeight, latestFrame, motionX, motionY,
                sourceMotionX, sourceMotionY, null);
    }

    /** Updates settled coverage geometry without discarding the retained effect source frame. */
    void setTracksPreservingFrame(
            List<TrackedObject> value,
            int sourceWidth,
            int sourceHeight,
            int motionX,
            int motionY) {
        setTracks(value, sourceWidth, sourceHeight, frame,
                motionX, motionY, Math.round(sourceFrameOffsetX),
                Math.round(sourceFrameOffsetY), frameRelease);
    }

    void setTracks(
            List<TrackedObject> value,
            int sourceWidth,
            int sourceHeight,
            Bitmap latestFrame,
            int motionX,
            int motionY,
            int sourceMotionX,
            int sourceMotionY,
            Runnable latestFrameRelease) {
        noteVisualTapSource();
        if (worldSpaceTracks) {
            visualSteering.clear();
            visualLayout.clear();
        }
        worldSpaceTracks = false;
        advancePersonFrame();
        List<RenderTrackSnapshot> snapshots = new ArrayList<>(value.size());
        for (TrackedObject track : value) snapshots.add(RenderTrackSnapshot.from(track));
        tracksPublishedAtMillis = SystemClock.uptimeMillis();
        latestMutationUptime = tracksPublishedAtMillis;
        ViewportMotion.Position displayedViewport = viewportMotion.position(tracksPublishedAtMillis);
        int displayWidth = Math.max(1, getWidth() > 0 ? getWidth() : sourceWidth);
        int displayHeight = Math.max(1, getHeight() > 0 ? getHeight() : sourceHeight);
        visualSteering.offsetAll(
                (displayedViewport.x - motionX) / displayWidth,
                (displayedViewport.y - motionY) / displayHeight,
                tracksPublishedAtMillis);
        captureWidth = Math.max(1, sourceWidth);
        captureHeight = Math.max(1, sourceHeight);
        Set<Integer> visualIds = new HashSet<>();
        for (RenderTrackSnapshot track : snapshots) {
            visualIds.add(track.id());
            updateSteeringReference(visualSteering, visualReferences, track);
            visualSteering.updateTarget(track.id(), track.box(), captureWidth, captureHeight,
                    tracksPublishedAtMillis, true);
        }
        visualSteering.retain(visualIds);
        visualReferences.keySet().retainAll(visualIds);
        liveTracks = snapshots;
        cachedTracks = Collections.emptyList();
        tracks = snapshots;
        contentOffsetX = motionX;
        contentOffsetY = motionY;
        viewportMotion.rebase(contentOffsetX, contentOffsetY, tracksPublishedAtMillis);
        motionAnimationWasActive = false;
        sourceFrameOffsetX = sourceMotionX;
        sourceFrameOffsetY = sourceMotionY;
        bitmapReference = RenderSourceReference.UNKNOWN;
        if (frame != latestFrame) {
            releaseFrame();
            frame = latestFrame;
            frameRelease = latestFrameRelease;
        } else if (latestFrameRelease != null) {
            frameRelease = latestFrameRelease;
        }
        Set<Integer> activeIds = new HashSet<>();
        for (RenderTrackSnapshot track : tracks) activeIds.add(track.id());
        for (RenderTrackSnapshot track : textTracks) activeIds.add(track.id());
        solidRenderLayers.keySet().retainAll(activeIds);
        customImages.retainAssignments(activeIds);
        setVisibility(tracks.isEmpty() && textTracks.isEmpty() ? INVISIBLE : VISIBLE);
        postInvalidateOnAnimation();
        scheduleNextFrame(tracksPublishedAtMillis);
    }

    /**
     * Publishes Accessibility tracks in one stable content space. Viewport events move only the
     * camera; detector publication never rebases or translates existing renderer geometry.
     */
    void setWorldTracks(
            List<TrackedObject> value,
            int sourceWidth,
            int sourceHeight,
            Bitmap latestFrame,
            long trackCameraX,
            long trackCameraY,
            long sourceCameraX,
            long sourceCameraY,
            int viewportWidth,
            int viewportHeight,
            Runnable latestFrameRelease) {
        setWorldTracksAndCache(value, Collections.emptyList(), sourceWidth, sourceHeight,
                latestFrame, trackCameraX, trackCameraY, sourceCameraX, sourceCameraY,
                viewportWidth, viewportHeight, latestFrameRelease);
    }

    /** Publishes one immutable union; cached regions never become tracker or stats authority. */
    void setWorldTracksAndCache(
            List<TrackedObject> value,
            List<Detection> cachedRegions,
            int sourceWidth,
            int sourceHeight,
            Bitmap latestFrame,
            long trackCameraX,
            long trackCameraY,
            long sourceCameraX,
            long sourceCameraY,
            int viewportWidth,
            int viewportHeight,
            Runnable latestFrameRelease) {
        setWorldTracksAndCache(value, cachedRegions, sourceWidth, sourceHeight, latestFrame,
                trackCameraX, trackCameraY, sourceCameraX, sourceCameraY,
                viewportWidth, viewportHeight, latestFrameRelease, RenderSourceReference.UNKNOWN);
    }

    void setWorldTracksAndCache(List<TrackedObject> value, List<Detection> cachedRegions,
            int sourceWidth, int sourceHeight, Bitmap latestFrame,
            long trackCameraX, long trackCameraY, long sourceCameraX, long sourceCameraY,
            int viewportWidth, int viewportHeight, Runnable latestFrameRelease,
            RenderSourceReference sourceReference) {
        noteVisualTapSource();
        advancePersonFrame();
        if (!worldSpaceTracks) {
            visualSteering.clear();
            visualLayout.clear();
        }
        List<RenderTrackSnapshot> snapshots = new ArrayList<>(value.size());
        for (TrackedObject track : value) {
            snapshots.add(RenderTrackSnapshot.fromWorld(
                    track, trackCameraX, trackCameraY,
                    sourceWidth, sourceHeight, viewportWidth, viewportHeight));
        }
        tracksPublishedAtMillis = SystemClock.uptimeMillis();
        latestMutationUptime = tracksPublishedAtMillis;
        captureWidth = Math.max(1, sourceWidth);
        captureHeight = Math.max(1, sourceHeight);
        // Keep original head/body membership for cache refreshes. Feeding previously merged
        // rectangles back into grouping loses head anchors and can bridge neighboring people.
        liveTracks = Collections.unmodifiableList(snapshots);
        List<RenderTrackSnapshot> cachedSnapshots = new ArrayList<>(
                cachedRegions == null ? 0 : cachedRegions.size());
        if (cachedRegions != null) {
            for (Detection cached : cachedRegions) {
                if (cached == null) continue;
                RenderTrackSnapshot candidate = RenderTrackSnapshot.fromWorldCacheDetection(
                        cached, trackCameraX, trackCameraY,
                        sourceWidth, sourceHeight, viewportWidth, viewportHeight);
                // Keep coverage until the render-only grouping pass. Group unions do not feed
                // back into tracker identity, detector policy, or cache confirmation.
                cachedSnapshots.add(candidate);
            }
        }
        cachedTracks = cachedSnapshots;
        tracks = visualLayout.update(mergedVisualTracks(liveTracks, cachedTracks),
                appearance.getSizePadding(), tracksPublishedAtMillis);
        updateVisualSteeringForDisplay(tracksPublishedAtMillis);
        traceVisualConsolidation(
                snapshots.size() + (cachedRegions == null ? 0 : cachedRegions.size()),
                tracks.size(), snapshots.size(), liveTracks.size(),
                cachedRegions == null ? 0 : cachedRegions.size(), cachedTracks.size());
        worldSpaceTracks = true;
        // World geometry minus this absolute source camera maps back into the retained bitmap.
        sourceFrameOffsetX = sourceCameraX;
        sourceFrameOffsetY = sourceCameraY;
        bitmapReference = sourceReference;
        if (frame != latestFrame) {
            releaseFrame();
            frame = latestFrame;
            frameRelease = latestFrameRelease;
        } else if (latestFrameRelease != null) {
            frameRelease = latestFrameRelease;
        }
        Set<Integer> activeIds = new HashSet<>();
        for (RenderTrackSnapshot track : tracks) activeIds.add(track.id());
        for (RenderTrackSnapshot track : textTracks) activeIds.add(track.id());
        solidRenderLayers.keySet().retainAll(activeIds);
        customImages.retainAssignments(activeIds);
        setVisibility(tracks.isEmpty() && textTracks.isEmpty() ? INVISIBLE : VISIBLE);
        postInvalidateOnAnimation();
        scheduleNextFrame(tracksPublishedAtMillis);
    }

    /** Replaces only render memory; live tracks, source frame, tracker, and stats remain untouched. */
    void setWorldCacheRegions(
            List<Detection> cachedRegions,
            int sourceWidth,
            int sourceHeight,
            long cacheCameraX,
            long cacheCameraY,
            int viewportWidth,
            int viewportHeight) {
        if (!worldSpaceTracks) return;
        // Replacing render memory cannot make a retained Lab scene become normal content.
        visualTapSourceLabOwned |= tapSourceLabOwned || CensorLabCensorSession.isActive();
        List<RenderTrackSnapshot> cachedSnapshots = new ArrayList<>(
                cachedRegions == null ? 0 : cachedRegions.size());
        if (cachedRegions != null) {
            for (Detection cached : cachedRegions) {
                if (cached == null) continue;
                RenderTrackSnapshot candidate = RenderTrackSnapshot.fromWorldCacheDetection(
                        cached, cacheCameraX, cacheCameraY,
                        sourceWidth, sourceHeight, viewportWidth, viewportHeight);
                cachedSnapshots.add(candidate);
            }
        }
        cachedTracks = cachedSnapshots;
        long nowMillis = SystemClock.uptimeMillis();
        tracks = visualLayout.update(mergedVisualTracks(liveTracks, cachedTracks),
                appearance.getSizePadding(), nowMillis);
        updateVisualSteeringForDisplay(nowMillis);
        traceVisualConsolidation(
                liveTracks.size() + (cachedRegions == null ? 0 : cachedRegions.size()),
                tracks.size(), liveTracks.size(), liveTracks.size(),
                cachedRegions == null ? 0 : cachedRegions.size(), cachedTracks.size());
        retainRenderAssignments();
        setVisibility(tracks.isEmpty() && textTracks.isEmpty() ? INVISIBLE : VISIBLE);
        postInvalidateOnAnimation();
        scheduleNextFrame(nowMillis);
    }

    private void updateVisualSteeringForDisplay(long nowMillis) {
        Set<Integer> visualIds = new HashSet<>();
        for (RenderTrackSnapshot track : tracks) {
            if (track.isCached()) continue;
            visualIds.add(track.id());
            updateSteeringReference(visualSteering, visualReferences, track);
            visualSteering.updateTarget(track.id(), track.box(), captureWidth, captureHeight,
                    nowMillis, true);
        }
        visualSteering.retain(visualIds);
        visualReferences.keySet().retainAll(visualIds);
    }

    private static void updateSteeringReference(ContinuousTrackSteering steering,
            Map<Integer, RenderSourceReference> references, RenderTrackSnapshot track) {
        RenderSourceReference previous = references.put(track.id(), track.reference());
        if (previous != null && !previous.sameBasis(track.reference())) steering.forget(track.id());
    }

    private void traceVisualConsolidation(
            int inputCount,
            int outputCount,
            int rawLiveCount,
            int selectedLiveCount,
            int rawCacheCount,
            int selectedCacheCount) {
        if (inputCount <= outputCount) return;
        long now = SystemClock.uptimeMillis();
        if (inputCount == lastConsolidationInput
                && outputCount == lastConsolidationOutput
                && now - lastConsolidationTraceUptime < CONSOLIDATION_TRACE_INTERVAL_MS) return;
        lastConsolidationTraceUptime = now;
        lastConsolidationInput = inputCount;
        lastConsolidationOutput = outputCount;
        CensorLabLog.i(MOTION_TAG, "CONSOLIDATE input=" + inputCount
                + " output=" + outputCount
                + " merged=" + (inputCount - outputCount)
                + " rawLive=" + rawLiveCount
                + " selectedLive=" + selectedLiveCount
                + " rawCache=" + rawCacheCount
                + " selectedCache=" + selectedCacheCount);
    }

    int admittedCachedRegionCount(List<Detection> candidates) {
        int count = 0;
        for (RenderTrackSnapshot snapshot : tracks) {
            if (!snapshot.isCached()) continue;
            for (Detection candidate : candidates) {
                // Count only the requested anchored cache entries, not independent one-hit quality
                // regions which may share the presentation list in an experimental pipeline.
                if (candidate != null && candidate.getAnchorKey() != null
                        && !candidate.getAnchorKey().isEmpty()
                        && snapshot.sourceId() == RenderTrackSnapshot.stableCacheId(candidate, candidate.getBox())) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private static List<RenderTrackSnapshot> mergedVisualTracks(
            List<RenderTrackSnapshot> live, List<RenderTrackSnapshot> cached) {
        if (cached == null || cached.isEmpty()) return live;
        List<RenderTrackSnapshot> merged = new ArrayList<>(live.size() + cached.size());
        merged.addAll(live);
        merged.addAll(cached);
        return merged;
    }

    void dumpRenderLayout(java.io.PrintWriter writer) {
        try {
            writer.println("SUBHUB_RENDER_LAYOUT " + RenderLayoutDiagnostics.encode(
                    liveTracks, cachedTracks, tracks, visualLayout, captureWidth, captureHeight,
                    getWidth(), getHeight(), appearance.getSizePadding(), contentOffsetX, contentOffsetY)
                    .put("motion", scrollMotionDiagnostics.encode(SystemClock.uptimeMillis())));
        } catch (org.json.JSONException failure) {
            writer.println("SUBHUB_RENDER_LAYOUT_ERROR invalid-numeric-state");
        }
    }

    private void retainRenderAssignments() {
        Set<Integer> activeIds = new HashSet<>();
        for (RenderTrackSnapshot track : tracks) activeIds.add(track.id());
        for (RenderTrackSnapshot track : textTracks) activeIds.add(track.id());
        solidRenderLayers.keySet().retainAll(activeIds);
        customImages.retainAssignments(activeIds);
    }

    void setWorldTracksPreservingFrame(
            List<TrackedObject> value,
            int sourceWidth,
            int sourceHeight,
            long trackCameraX,
            long trackCameraY,
            int viewportWidth,
            int viewportHeight) {
        setWorldTracksAndCache(value, Collections.emptyList(), sourceWidth, sourceHeight, frame,
                trackCameraX, trackCameraY,
                Math.round(sourceFrameOffsetX), Math.round(sourceFrameOffsetY),
                viewportWidth, viewportHeight, frameRelease, bitmapReference);
    }

    void setTextDetections(
            List<Detection> detections,
            int sourceWidth,
            int sourceHeight,
            int motionX,
            int motionY) {
        noteTextTapSource();
        if (worldSpaceText) textSteering.clear();
        worldSpaceText = false;
        List<RenderTrackSnapshot> snapshots = new ArrayList<>(detections.size());
        for (Detection detection : detections) {
            if (detection != null) snapshots.add(RenderTrackSnapshot.fromTextDetection(detection));
        }
        long nowMillis = SystemClock.uptimeMillis();
        latestMutationUptime = nowMillis;
        ViewportMotion.Position displayedViewport = viewportMotion.position(nowMillis);
        float oldDisplayedTextX = textContentOffsetX
                + displayedViewport.x - contentOffsetX;
        float oldDisplayedTextY = textContentOffsetY
                + displayedViewport.y - contentOffsetY;
        int displayWidth = Math.max(1, getWidth() > 0 ? getWidth() : sourceWidth);
        int displayHeight = Math.max(1, getHeight() > 0 ? getHeight() : sourceHeight);
        textSteering.offsetAll(
                (oldDisplayedTextX - motionX) / displayWidth,
                (oldDisplayedTextY - motionY) / displayHeight,
                nowMillis);
        textCaptureWidth = Math.max(1, sourceWidth);
        textCaptureHeight = Math.max(1, sourceHeight);
        Set<Integer> textIds = new HashSet<>();
        for (RenderTrackSnapshot track : snapshots) {
            textIds.add(track.id());
            updateSteeringReference(textSteering, textReferences, track);
            textSteering.updateTarget(track.id(), track.box(),
                    textCaptureWidth, textCaptureHeight, nowMillis, false);
        }
        textSteering.retain(textIds);
        textReferences.keySet().retainAll(textIds);
        textTracks = snapshots;
        textContentOffsetX = motionX;
        textContentOffsetY = motionY;
        Set<Integer> activeIds = new HashSet<>();
        for (RenderTrackSnapshot track : tracks) activeIds.add(track.id());
        for (RenderTrackSnapshot track : textTracks) activeIds.add(track.id());
        solidRenderLayers.keySet().retainAll(activeIds);
        customImages.retainAssignments(activeIds);
        setVisibility(tracks.isEmpty() && textTracks.isEmpty() ? INVISIBLE : VISIBLE);
        postInvalidateOnAnimation();
        scheduleNextFrame(SystemClock.uptimeMillis());
    }

    void setWorldTextDetections(
            List<Detection> detections,
            int sourceWidth,
            int sourceHeight,
            long cameraX,
            long cameraY,
            int viewportWidth,
            int viewportHeight) {
        noteTextTapSource();
        if (!worldSpaceText) textSteering.clear();
        List<RenderTrackSnapshot> snapshots = new ArrayList<>(detections.size());
        for (Detection detection : detections) {
            if (detection != null) {
                snapshots.add(RenderTrackSnapshot.fromWorldTextDetection(
                        detection, cameraX, cameraY,
                        sourceWidth, sourceHeight, viewportWidth, viewportHeight));
            }
        }
        long nowMillis = SystemClock.uptimeMillis();
        latestMutationUptime = nowMillis;
        textCaptureWidth = Math.max(1, sourceWidth);
        textCaptureHeight = Math.max(1, sourceHeight);
        Set<Integer> textIds = new HashSet<>();
        for (RenderTrackSnapshot track : snapshots) {
            textIds.add(track.id());
            updateSteeringReference(textSteering, textReferences, track);
            textSteering.updateTarget(track.id(), track.box(),
                    textCaptureWidth, textCaptureHeight, nowMillis, false);
        }
        textSteering.retain(textIds);
        textReferences.keySet().retainAll(textIds);
        textTracks = snapshots;
        worldSpaceText = true;
        Set<Integer> activeIds = new HashSet<>();
        for (RenderTrackSnapshot track : tracks) activeIds.add(track.id());
        for (RenderTrackSnapshot track : textTracks) activeIds.add(track.id());
        solidRenderLayers.keySet().retainAll(activeIds);
        customImages.retainAssignments(activeIds);
        setVisibility(tracks.isEmpty() && textTracks.isEmpty() ? INVISIBLE : VISIBLE);
        postInvalidateOnAnimation();
        scheduleNextFrame(nowMillis);
    }

    void setNativeScrollSpline(boolean enabled) {
        viewportMotion.setNativeSpline(enabled, getResources().getDisplayMetrics().density,
                android.view.ViewConfiguration.getScrollFriction(), SystemClock.uptimeMillis());
    }

    void offsetContent(int deltaX, int deltaY) {
        offsetContent(deltaX, deltaY, true);
    }

    void offsetContent(int deltaX, int deltaY, boolean authoritative) {
        offsetContent(deltaX, deltaY, authoritative, SystemClock.uptimeMillis());
    }

    void offsetContent(
            int deltaX,
            int deltaY,
            boolean authoritative,
            long effectiveUptimeMillis) {
        long nowMillis = SystemClock.uptimeMillis();
        latestMutationUptime = nowMillis;
        contentOffsetX += deltaX;
        contentOffsetY += deltaY;
        textContentOffsetX += deltaX;
        textContentOffsetY += deltaY;
        // Event-source time belongs to the capture-phase timeline. Presentation cannot begin
        // before this callback reaches the overlay, so anchoring animation in historical time
        // compresses or entirely skips its first visible segment when delivery is delayed.
        viewportMotion.addDelta(deltaX, deltaY, nowMillis,
                Math.max(1, getWidth()), Math.max(1, getHeight()), authoritative,
                effectiveUptimeMillis);
        noteMotionInput("event", deltaX, deltaY, true);
        if (tracks.isEmpty() && textTracks.isEmpty()) return;
        // This mutation already runs on the UI thread. postInvalidateOnAnimation() waits for a
        // Choreographer callback before invalidating and can miss the immediately upcoming
        // traversal, producing the measured two-frame input-to-draw delay. Mark this traversal
        // dirty now; scheduleNextFrame() still owns all follow-up prediction/settle ticks.
        invalidate();
        scheduleNextFrame(SystemClock.uptimeMillis());
    }

    /** Moves presentation from a fast Accessibility bounds sample without aging tracker state. */
    void offsetPresentation(int deltaX, int deltaY) {
        if (tracks.isEmpty() && textTracks.isEmpty()) return;
        long nowMillis = SystemClock.uptimeMillis();
        latestMutationUptime = nowMillis;
        if (deltaX == 0 && deltaY == 0) {
            viewportMotion.settlePresentation(nowMillis);
            noteMotionInput("anchor-settle", 0, 0, false);
        } else {
            viewportMotion.addPresentationDelta(deltaX, deltaY, nowMillis,
                    Math.max(1, getWidth()), Math.max(1, getHeight()));
            noteMotionInput("anchor-poll", deltaX, deltaY, false);
        }
        invalidate();
        scheduleNextFrame(nowMillis);
    }

    boolean measureViewport(float x, float y, long readStart, long sourceMillis) {
        return measureViewport(x, y, readStart, sourceMillis, null);
    }

    boolean measureViewport(float x, float y, long readStart, long sourceMillis,
            RenderSourceReference.Origin origin) {
        if (!worldSpaceTracks) return false;
        long now = SystemClock.uptimeMillis();
        int interval = renderTickMillis > 0L ? (int) renderTickMillis : 16;
        ViewportMotion.Position previous = viewportMotion.position(now);
        boolean accepted = viewportMotion.measurePresentation(x, y, readStart, sourceMillis, now,
                Math.max(1, getWidth()), Math.max(1, getHeight()), interval);
        if (accepted) {
            if (!java.util.Objects.equals(measuredOrigin, origin)) clearPersonCoverage();
            measuredOrigin = origin;
            if (Math.abs(x - previous.x) > .5f || Math.abs(y - previous.y) > .5f
                    || viewportMotion.isAnimating(now)) {
                latestMutationUptime = now;
                noteMotionInput("anchor-absolute", x - previous.x, y - previous.y, false);
                invalidate();
                scheduleNextFrame(now);
            }
        }
        return accepted;
    }

    void clearMeasuredViewport() {
        clearPersonCoverage();
        measuredOrigin = null;
        currentRenderReference = RenderSourceReference.UNKNOWN;
        long now = SystemClock.uptimeMillis();
        viewportMotion.clearMeasuredPresentation(now);
        invalidate();
        scheduleNextFrame(now);
    }

    /** Hide all censor pixels without treating an empty track list as reverse-mode content. */
    void clearContent() {
        tapTracker.clearDisplayed(this);
        removeCallbacks(refreshDisplayedTapGeometry);
        clearPersonCoverage();
        long nowMillis = SystemClock.uptimeMillis();
        latestMutationUptime = nowMillis;
        // Renderer snapshots may be immutable or shared by a consolidation result.
        liveTracks = Collections.emptyList();
        cachedTracks = Collections.emptyList();
        tracks = Collections.emptyList();
        textTracks = Collections.emptyList();
        visualSteering.clear();
        textSteering.clear();
        visualLayout.clear();
        solidRenderLayers.clear();
        contentOffsetX = 0;
        contentOffsetY = 0;
        textContentOffsetX = 0;
        textContentOffsetY = 0;
        worldSpaceTracks = false;
        worldSpaceText = false;
        viewportMotion.reset(0f, 0f, nowMillis);
        scrollMotionDiagnostics.clear();
        measuredOrigin = null;
        currentRenderReference = RenderSourceReference.UNKNOWN;
        bitmapReference = RenderSourceReference.UNKNOWN;
        visualReferences.clear();
        textReferences.clear();
        motionAnimationWasActive = false;
        sourceFrameOffsetX = 0;
        sourceFrameOffsetY = 0;
        releaseFrame();
        customImages.retainAssignments(new HashSet<>());
        setVisibility(INVISIBLE);
        stopFrameCallback();
        invalidate();
    }

    void setAppearance(CensorAppearance value) {
        latestMutationUptime = SystemClock.uptimeMillis();
        CensorAppearance.Type previous = appearance.getType();
        appearance = value;
        visualLayout.clear();
        if (worldSpaceTracks) {
            tracks = visualLayout.update(mergedVisualTracks(liveTracks, cachedTracks),
                    appearance.getSizePadding(), latestMutationUptime);
            visualSteering.clear();
            updateVisualSteeringForDisplay(latestMutationUptime);
        }
        solidRenderLayers.clear();
        cyanShiftPaint.setColorFilter(new PorterDuffColorFilter(
                value.getEffectPalette().first(), PorterDuff.Mode.SRC_ATOP));
        redShiftPaint.setColorFilter(new PorterDuffColorFilter(
                value.getEffectPalette().second(), PorterDuff.Mode.SRC_ATOP));
        tapeRedPaint.setColor(value.getEffectPalette().second());
        tapeYellowPaint.setColor(value.getEffectPalette().third());
        if (value.getType() == CensorAppearance.Type.CUSTOM
                || previous == CensorAppearance.Type.CUSTOM) {
            customImages.reloadAsync(this::postInvalidate);
        }
        postInvalidateOnAnimation();
        scheduleNextFrame(SystemClock.uptimeMillis());
    }

    /** The originating packet's ownership survives recording stop and later redraws. */
    void setTapSourceLabOwned(boolean labOwned) { setTapSource(labOwned, tapTracker.scope()); }

    void setTapSource(boolean labOwned, long sourceScope) {
        tapSourceLabOwned = labOwned;
        tapSourceScope = sourceScope;
    }

    private void noteVisualTapSource() {
        visualTapScope = tapSourceScope == Long.MIN_VALUE ? tapTracker.scope() : tapSourceScope;
        visualTapSourceLabOwned = tapSourceLabOwned || CensorLabCensorSession.isActive();
    }

    private void noteTextTapSource() {
        textTapScope = tapSourceScope == Long.MIN_VALUE ? tapTracker.scope() : tapSourceScope;
        textTapSourceLabOwned = tapSourceLabOwned || CensorLabCensorSession.isActive();
    }

    void setDiagnostics(String value) {
        diagnostics = value == null ? "" : value;
        invalidate();
    }

    void setBorderAnimationTimeForTest(long uptimeMillis) {
        borderAnimationTimeOverride = Math.max(0L, uptimeMillis);
    }

    void setRenderTimeForTest(long uptimeMillis) {
        renderTimeOverride = Math.max(0L, uptimeMillis);
    }

    void setMaxExtrapolationMs(float value) {
        maxExtrapolationMs = Math.max(0f, value);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        displayedTapRegions.clear();
        removeCallbacks(refreshDisplayedTapGeometry);
        activeRenderTimeMillis = renderTimeMillis();
        ViewportMotion.Position viewport = viewportMotion.position(activeRenderTimeMillis);
        if (viewportMotion.isMeasuredPresentationMode()
                && !viewportMotion.hasMeasuredPresentation(activeRenderTimeMillis)) {
            // The estimator's 48-64ms fade still contains expired anchor displacement. It is
            // not event prediction and must not enter the UNKNOWN-reference fallback path.
            viewport = new ViewportMotion.Position(contentOffsetX, contentOffsetY);
        }
        renderContentOffsetX = viewport.x;
        renderContentOffsetY = viewport.y;
        renderViewportLeadX = viewport.x - contentOffsetX;
        renderViewportLeadY = viewport.y - contentOffsetY;
        scrollMotionDiagnostics.record(activeRenderTimeMillis, viewport.x, viewport.y,
                contentOffsetX, contentOffsetY, motionSequence);
        currentRenderReference = measuredOrigin != null
                && viewportMotion.hasMeasuredPresentation(activeRenderTimeMillis)
                ? RenderSourceReference.known(measuredOrigin, activeRenderTimeMillis,
                        renderViewportLeadX, renderViewportLeadY)
                : RenderSourceReference.UNKNOWN;
        activeEffectReference = RenderSourceReference.UNKNOWN;
        activeEffectOffsetX = renderContentOffsetX;
        activeEffectOffsetY = renderContentOffsetY;
        updatePersonDecorations();
        if (appearance.isReverseMode()) drawReverse(canvas);
        else drawNormal(canvas);
        publishDisplayedTapGeometry();
        drawDiagnostics(canvas);
        traceRenderedMotion(viewportMotion.isAnimating(activeRenderTimeMillis));
        scheduleNextFrame(activeRenderTimeMillis);
        presentationFrameTimeMillis = -1L;
    }

    private void recordDisplayedTapRegion(RectF rect, boolean textRegion) {
        long sourceScope = textRegion ? textTapScope : visualTapScope;
        boolean labOwned = textRegion ? textTapSourceLabOwned : visualTapSourceLabOwned;
        if (labOwned || sourceScope != tapTracker.scope() || rect.isEmpty()) return;
        // All effects cover these two intersecting interior strips. Rounded transparent corners
        // are deliberately excluded: target bounds cannot prove a touch inside those corners.
        float radius = Math.min(dp(8), Math.min(rect.width(), rect.height()) / 2f);
        addDisplayedTapRect(rect.left + radius, rect.top, rect.right - radius, rect.bottom);
        addDisplayedTapRect(rect.left, rect.top + radius, rect.right, rect.bottom - radius);
    }

    private void addDisplayedTapRect(float left, float top, float right, float bottom) {
        int x = (int) Math.ceil(left), y = (int) Math.ceil(top);
        int endX = (int) Math.floor(right), endY = (int) Math.floor(bottom);
        if (endX > x && endY > y) displayedTapRegions.add(new BBox(x, y, endX - x, endY - y));
    }

    private void publishDisplayedTapGeometry() {
        if (!isAttachedToWindow() || !isShown() || getWindowVisibility() != VISIBLE
                || CensorLabCensorSession.isActive() || !getLocalVisibleRect(tapVisibleRect)) {
            tapTracker.clearDisplayed(this);
            return;
        }
        getLocationOnScreen(tapScreenLocation);
        BBox viewport = new BBox(tapVisibleRect.left + tapScreenLocation[0],
                tapVisibleRect.top + tapScreenLocation[1], tapVisibleRect.width(), tapVisibleRect.height());
        List<BBox> screenRegions = new ArrayList<>(displayedTapRegions.size());
        for (BBox box : displayedTapRegions) {
            int left = Math.max(viewport.getX(), box.getX() + tapScreenLocation[0]);
            int top = Math.max(viewport.getY(), box.getY() + tapScreenLocation[1]);
            int right = Math.min(viewport.getRight(), box.getRight() + tapScreenLocation[0]);
            int bottom = Math.min(viewport.getBottom(), box.getBottom() + tapScreenLocation[1]);
            if (right > left && bottom > top) screenRegions.add(new BBox(left, top, right - left, bottom - top));
        }
        tapTracker.publishDisplayed(this, tapTracker.scope(), screenRegions, viewport,
                SystemClock.uptimeMillis(), false);
        // A stationary BOX still has visible pixels. Renew only through another actual draw,
        // at a bounded cadence; this does not request captures or run any detector.
        if (!screenRegions.isEmpty()) postDelayed(refreshDisplayedTapGeometry,
                CensorTapTracker.DISPLAY_REFRESH_MILLIS);
    }

    private void drawDiagnostics(Canvas canvas) {
        if (diagnostics.isEmpty()) return;
        String[] lines = diagnostics.split("\\n", 3);
        float padding = dp(10);
        float lineHeight = dp(15);
        float width = 0;
        for (String line : lines) width = Math.max(width, diagnosticsText.measureText(line));
        float left = dp(12);
        float top = dp(32);
        RectF panel = new RectF(left, top, left + width + padding * 2,
                top + lines.length * lineHeight + padding * 2);
        canvas.drawRoundRect(panel, dp(8), dp(8), diagnosticsFill);
        diagnosticsFill.setColor(getContext().getColor(R.color.accent));
        diagnosticsFill.setAlpha(255);
        canvas.drawRoundRect(new RectF(panel.left, panel.top, panel.left + dp(3), panel.bottom),
                dp(2), dp(2), diagnosticsFill);
        diagnosticsFill.setColor(Color.rgb(13, 13, 20));
        diagnosticsFill.setAlpha(225);
        float baseline = panel.top + padding - diagnosticsText.ascent();
        for (String line : lines) {
            canvas.drawText(line, panel.left + padding, baseline, diagnosticsText);
            baseline += lineHeight;
        }
    }

    private void drawNormal(Canvas canvas) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canUseSolidRenderLayers(canvas)) {
            drawSolidRenderLayers(canvas);
            drawSolidTextRenderLayers(canvas);
            drawOverlayLabels(canvas);
            return;
        }
        float scaleX = (float) getWidth() / captureWidth;
        float scaleY = (float) getHeight() / captureHeight;
        float ageMs = renderAgeMillis();
        for (RenderTrackSnapshot track : visualPaintOrder()) {
            boolean textRegion = "text_smut".equals(track.category());
            BBox base = baseVisualBox(track, ageMs);
            BBox predicted = expandVisualBox(track, base);
            // Coverage expansion is not object motion: keep effect sampling aligned to pixels.
            activePredictionX = (base.getX() - track.box().getX()) * scaleX;
            activePredictionY = (base.getY() - track.box().getY()) * scaleY;
            setTrackRect(track, predicted, scaleX, scaleY, textRegion,
                    renderContentOffsetX, renderContentOffsetY, worldSpaceTracks);
            drawEffect(canvas, drawRect, track.id(), effectTypeFor(track),
                    appearance.getIntensity());
            recordDisplayedTapRegion(drawRect, false);
            if (appearance.isShowBorder() && !suppressedPersonDecorations.contains(track.id())) {
                drawBorder(canvas, drawRect);
            }
        }
        activePredictionX = 0f;
        activePredictionY = 0f;
        drawTextTracks(canvas);
        drawOverlayLabels(canvas);
    }

    private CensorAppearance.Type effectTypeFor(RenderTrackSnapshot track) {
        // Cached geometry has no matching source pixels. Preserve Glitch's existing
        // opaque noise fallback rather than replacing its artwork with a flat palette color.
        if (track.isCached() && appearance.getType() == CensorAppearance.Type.GLITCH) {
            return CensorAppearance.Type.STATIC;
        }
        return track.isCached() && appearance.requiresSourceFrame()
                ? CensorAppearance.Type.BOX : appearance.getType();
    }

    private void drawTextTracks(Canvas canvas) {
        if (textTracks.isEmpty()) return;
        float savedOffsetX = renderContentOffsetX;
        float savedOffsetY = renderContentOffsetY;
        renderContentOffsetX = worldSpaceText ? savedOffsetX
                : textContentOffsetX + renderViewportLeadX;
        renderContentOffsetY = worldSpaceText ? savedOffsetY
                : textContentOffsetY + renderViewportLeadY;
        float scaleX = (float) getWidth() / textCaptureWidth;
        float scaleY = (float) getHeight() / textCaptureHeight;
        activePredictionX = 0f;
        activePredictionY = 0f;
        for (RenderTrackSnapshot track : textTracks) {
            setTrackRect(track, textBox(track), scaleX, scaleY, true,
                    renderContentOffsetX, renderContentOffsetY, worldSpaceText);
            drawEffect(canvas, drawRect, track.id(), appearance.getType(),
                    appearance.getIntensity());
            recordDisplayedTapRegion(drawRect, true);
            if (appearance.isShowBorder()) drawBorder(canvas, drawRect);
        }
        renderContentOffsetX = savedOffsetX;
        renderContentOffsetY = savedOffsetY;
    }

    /**
     * Records each ordinary solid censor once and lets the hardware compositor translate it on
     * every vsync. Geometry motion no longer replays paint, border, and label commands.
     */
    @SuppressLint("NewApi") // Called only behind the API 29 guard in drawNormal.
    private void drawSolidRenderLayers(Canvas canvas) {
        float scaleX = (float) getWidth() / captureWidth;
        float scaleY = (float) getHeight() / captureHeight;
        float ageMs = renderAgeMillis();
        for (RenderTrackSnapshot track : visualPaintOrder()) {
            BBox predicted = visualBox(track, ageMs);
            setTrackRect(track, predicted, scaleX, scaleY,
                    "text_smut".equals(track.category()),
                    renderContentOffsetX, renderContentOffsetY, worldSpaceTracks);
            if (drawRect.isEmpty()) continue;
            int width = Math.max(1, Math.round(drawRect.width()));
            int height = Math.max(1, Math.round(drawRect.height()));
            boolean decorate = !suppressedPersonDecorations.contains(track.id());
            SolidRenderLayer layer = solidRenderLayers.get(track.id());
            if (layer == null || layer.width != width || layer.height != height
                    || layer.decorations != decorate) {
                layer = recordSolidLayer(track.id(), width, height, decorate);
                solidRenderLayers.put(track.id(), layer);
            }
            int left = Math.round(drawRect.left);
            int top = Math.round(drawRect.top);
            layer.node.setPosition(left, top, left + width, top + height);
            canvas.drawRenderNode(layer.node);
            recordDisplayedTapRegion(new RectF(left, top, left + width, top + height), false);
        }
    }

    @SuppressLint("NewApi")
    private void drawSolidTextRenderLayers(Canvas canvas) {
        if (textTracks.isEmpty()) return;
        float savedOffsetX = renderContentOffsetX;
        float savedOffsetY = renderContentOffsetY;
        renderContentOffsetX = worldSpaceText ? savedOffsetX
                : textContentOffsetX + renderViewportLeadX;
        renderContentOffsetY = worldSpaceText ? savedOffsetY
                : textContentOffsetY + renderViewportLeadY;
        float scaleX = (float) getWidth() / textCaptureWidth;
        float scaleY = (float) getHeight() / textCaptureHeight;
        for (RenderTrackSnapshot track : textTracks) {
            setTrackRect(track, textBox(track), scaleX, scaleY, true,
                    renderContentOffsetX, renderContentOffsetY, worldSpaceText);
            if (drawRect.isEmpty()) continue;
            int width = Math.max(1, Math.round(drawRect.width()));
            int height = Math.max(1, Math.round(drawRect.height()));
            SolidRenderLayer layer = solidRenderLayers.get(track.id());
            if (layer == null || layer.width != width || layer.height != height) {
                layer = recordSolidLayer(track.id(), width, height);
                solidRenderLayers.put(track.id(), layer);
            }
            int left = Math.round(drawRect.left);
            int top = Math.round(drawRect.top);
            layer.node.setPosition(left, top, left + width, top + height);
            canvas.drawRenderNode(layer.node);
            recordDisplayedTapRegion(new RectF(left, top, left + width, top + height), true);
        }
        renderContentOffsetX = savedOffsetX;
        renderContentOffsetY = savedOffsetY;
    }

    @SuppressLint("NewApi") // Called only from the guarded RenderNode path.
    private SolidRenderLayer recordSolidLayer(int stableId, int width, int height) {
        return recordSolidLayer(stableId, width, height, true);
    }

    @SuppressLint("NewApi")
    private SolidRenderLayer recordSolidLayer(int stableId, int width, int height, boolean decorate) {
        RenderNode node = new RenderNode("censor-" + stableId);
        node.setPosition(0, 0, width, height);
        Canvas recording = node.beginRecording(width, height);
        RectF local = new RectF(0f, 0f, width, height);
        drawSolid(recording, local, appearance.getIntensity());
        if (appearance.isShowBorder() && decorate) drawBorder(recording, local);
        node.endRecording();
        return new SolidRenderLayer(node, width, height, decorate);
    }

    private boolean canUseSolidRenderLayers(Canvas canvas) {
        return canvas.isHardwareAccelerated()
                && !appearance.isReverseMode()
                && appearance.getType() == CensorAppearance.Type.BOX
                && !appearance.isAnimateBorder();
    }

    private void drawReverse(Canvas canvas) {
        // Reverse detections describe revealed holes, not censored targets. Do not attribute
        // clicks from those bounds; a future complement-region contract can prove that case.
        RectF whole = new RectF(0, 0, getWidth(), getHeight());
        int layer = canvas.saveLayer(whole, null);
        CensorAppearance.Type type = appearance.getType();
        if (type == CensorAppearance.Type.BOX
                || type == CensorAppearance.Type.CUSTOM) type = CensorAppearance.Type.PIXELATE;
        activePredictionX = 0f;
        activePredictionY = 0f;
        drawEffect(canvas, whole, 0, type, appearance.getReverseStrength());

        float scaleX = (float) getWidth() / captureWidth;
        float scaleY = (float) getHeight() / captureHeight;
        List<RectF> holes = new ArrayList<>();
        float ageMs = renderAgeMillis();
        for (RenderTrackSnapshot track : tracks) {
            BBox predicted = visualBox(track, ageMs);
            setTrackRect(track, predicted, scaleX, scaleY,
                    "text_smut".equals(track.category()),
                    renderContentOffsetX, renderContentOffsetY, worldSpaceTracks);
            RectF hole = new RectF(drawRect);
            holes.add(hole);
            drawShape(canvas, hole, clear);
        }
        if (!textTracks.isEmpty()) {
            float savedOffsetX = renderContentOffsetX;
            float savedOffsetY = renderContentOffsetY;
            renderContentOffsetX = worldSpaceText ? savedOffsetX
                    : textContentOffsetX + renderViewportLeadX;
            renderContentOffsetY = worldSpaceText ? savedOffsetY
                    : textContentOffsetY + renderViewportLeadY;
            float textScaleX = (float) getWidth() / textCaptureWidth;
            float textScaleY = (float) getHeight() / textCaptureHeight;
            for (RenderTrackSnapshot track : textTracks) {
                setTrackRect(track, textBox(track), textScaleX, textScaleY, true,
                        renderContentOffsetX, renderContentOffsetY, worldSpaceText);
                RectF hole = new RectF(drawRect);
                holes.add(hole);
                drawShape(canvas, hole, clear);
            }
            renderContentOffsetX = savedOffsetX;
            renderContentOffsetY = savedOffsetY;
        }
        canvas.restoreToCount(layer);
        if (appearance.isShowBorder()) {
            for (RectF hole : holes) drawBorder(canvas, hole);
        }
    }

    private void setPaddedRect(BBox box, float scaleX, float scaleY, boolean textRegion) {
        float padding = textRegion
                ? Math.min(0.025f, appearance.getSizePadding())
                : appearance.getSizePadding();
        float horizontal = box.getWidth() * padding * scaleX;
        float vertical = box.getHeight() * padding * scaleY;
        drawRect.set(
                Math.max(0, box.getX() * scaleX - horizontal),
                Math.max(0, box.getY() * scaleY - vertical),
                Math.min(getWidth(), box.getRight() * scaleX + horizontal),
                Math.min(getHeight(), box.getBottom() * scaleY + vertical));
    }

    private void setTrackRect(
            RenderTrackSnapshot track,
            BBox box,
            float scaleX,
            float scaleY,
            boolean textRegion,
            float offsetX,
            float offsetY,
            boolean worldSpace) {
        if (worldSpace) {
            offsetX = RenderCoordinates.offsetX(contentOffsetX, offsetX,
                    track.reference(), currentRenderReference);
            offsetY = RenderCoordinates.offsetY(contentOffsetY, offsetY,
                    track.reference(), currentRenderReference);
        }
        activeEffectReference = track.reference();
        activeEffectOffsetX = offsetX;
        activeEffectOffsetY = offsetY;
        if (!worldSpace) {
            setPaddedRect(box, scaleX, scaleY, textRegion);
            drawRect.offset(offsetX, offsetY);
            return;
        }
        float padding = track.paddingApplied() ? 0f : textRegion
                ? Math.min(0.025f, appearance.getSizePadding())
                : appearance.getSizePadding();
        float horizontal = box.getWidth() * padding * scaleX;
        float vertical = box.getHeight() * padding * scaleY;
        // Apply the camera before clipping. Clipping a world box first destroys offscreen state
        // and is the reason a translated track cannot faithfully re-enter the viewport.
        float left = box.getX() * scaleX + offsetX - horizontal;
        float top = box.getY() * scaleY + offsetY - vertical;
        float right = box.getRight() * scaleX + offsetX + horizontal;
        float bottom = box.getBottom() * scaleY + offsetY + vertical;
        if (right <= 0f || bottom <= 0f || left >= getWidth() || top >= getHeight()) {
            drawRect.setEmpty();
            return;
        }
        drawRect.set(Math.max(0f, left), Math.max(0f, top),
                Math.min(getWidth(), right), Math.min(getHeight(), bottom));
    }

    private void drawEffect(
            Canvas canvas,
            RectF rect,
            int stableId,
            CensorAppearance.Type type,
            int intensity) {
        switch (type) {
            case PIXELATE:
                if (!drawPixelatedFrame(canvas, rect, intensity)) drawSolid(canvas, rect, intensity);
                break;
            case BLUR:
                if (!drawBlurredFrame(canvas, rect, intensity)
                        && !drawPixelatedFrame(canvas, rect, intensity)) drawSolid(canvas, rect, intensity);
                break;
            case CUSTOM:
                if (!drawCustom(canvas, rect, stableId)) drawSolid(canvas, rect, intensity);
                break;
            case STATIC:
                drawStatic(canvas, rect, stableId, intensity);
                break;
            case GLITCH:
                drawGlitch(canvas, rect, stableId, intensity);
                break;
            case TAPE:
                drawTape(canvas, rect, stableId, intensity);
                break;
            case ERROR_POPUP:
                drawErrorPopup(canvas, rect);
                break;
            case BOX:
            default:
                drawSolid(canvas, rect, intensity);
                break;
        }
    }

    private void drawSolid(Canvas canvas, RectF rect, int intensity) {
        fill.setShader(null);
        fill.setColor(appearance.getEffectPalette().first());
        fill.setAlpha(255);
        canvas.drawRoundRect(rect, dp(8), dp(8), fill);
    }

    private boolean drawPixelatedFrame(Canvas canvas, RectF rect, int intensity) {
        if (!prepareSourceRect(rect)) return false;
        int clamped = Math.max(1, Math.min(100, intensity));
        int minimum = Math.max(1, Math.min(sourceRect.width(), sourceRect.height()));
        float fraction = (clamped - 1) / 99f;
        int minimumBlock = Math.max(3, Math.round(minimum * .025f));
        int maximumBlock = Math.max(minimumBlock + 1, Math.round(minimum * .45f));
        int block = Math.max(2, Math.min(minimum,
                Math.round(minimumBlock + (maximumBlock - minimumBlock)
                        * fraction * fraction)));
        int smallWidth = Math.min(192, Math.max(1,
                (int) Math.ceil(sourceRect.width() / (float) block)));
        int smallHeight = Math.min(192, Math.max(1,
                (int) Math.ceil(sourceRect.height() / (float) block)));
        ensureScratch(smallWidth, smallHeight);
        scratchRect.set(0, 0, smallWidth, smallHeight);
        effectCanvas.drawBitmap(frame, sourceRect, scratchRect, filteredPaint);
        canvas.drawBitmap(effectScratch, scratchRect, rect, nearestPaint);
        return true;
    }

    private boolean drawBlurredFrame(Canvas canvas, RectF rect, int intensity) {
        if (!prepareSourceRect(rect)) return false;
        int clamped = Math.max(1, Math.min(100, intensity));
        int divisor = Math.max(2, Math.round(2 + (clamped - 1) / 99f * 18f));
        int smallWidth = Math.min(192, Math.max(1, sourceRect.width() / divisor));
        int smallHeight = Math.min(192, Math.max(1, sourceRect.height() / divisor));
        ensureScratch(smallWidth, smallHeight);
        scratchRect.set(0, 0, smallWidth, smallHeight);
        effectCanvas.drawBitmap(frame, sourceRect, scratchRect, filteredPaint);
        canvas.drawBitmap(effectScratch, scratchRect, rect, filteredPaint);
        return true;
    }

    private void ensureScratch(int width, int height) {
        if (effectScratch != null && !effectScratch.isRecycled()
                && effectScratch.getWidth() >= width && effectScratch.getHeight() >= height) return;
        if (effectScratch != null && !effectScratch.isRecycled()) effectScratch.recycle();
        effectScratch = Bitmap.createBitmap(Math.max(1, width), Math.max(1, height),
                Bitmap.Config.ARGB_8888);
        effectCanvas = new Canvas(effectScratch);
    }

    private boolean drawCustom(Canvas canvas, RectF rect, int stableId) {
        CustomImagePool.PreparedImage prepared = customImages.imageFor(stableId);
        Bitmap bitmap = prepared == null ? null : prepared.bitmap();
        if (bitmap == null || bitmap.isRecycled()) return false;
        Rect source = prepared.cropFor(rect.width() / Math.max(1f, rect.height()));
        canvas.drawBitmap(bitmap, source, rect, bitmapPaint);
        return true;
    }

    private void drawStatic(Canvas canvas, RectF rect, int stableId, int intensity) {
        long tick = SystemClock.uptimeMillis() / 90L;
        if (noiseBitmap == null) {
            noiseBitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
            noisePixels = new int[128 * 128];
        }
        if (noiseTick != tick) {
            int clamped = Math.max(1, Math.min(100, intensity));
            float contrast = .35f + clamped / 100f * .65f;
            long seed = tick * 6364136223846793005L + stableId * 1103515245L;
            for (int index = 0; index < noisePixels.length; index++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                int raw = (int) ((seed >>> 56) & 0xff);
                int value = Math.max(0, Math.min(255,
                        Math.round(128 * (1f - contrast) + raw * contrast)));
                noisePixels[index] = blendColor(appearance.getEffectPalette().first(),
                        appearance.getEffectPalette().second(), value / 255f);
            }
            noiseBitmap.setPixels(noisePixels, 0, 128, 0, 0, 128, 128);
            noiseTick = tick;
        }
        int cell = Math.max(2, Math.min(7, 7 - Math.max(1, Math.min(100, intensity)) / 20));
        int cellsWide = Math.min(128, Math.max(1, (int) Math.ceil(rect.width() / cell)));
        int cellsHigh = Math.min(128, Math.max(1, (int) Math.ceil(rect.height() / cell)));
        effectSourceRect.set(0, 0, cellsWide, cellsHigh);
        canvas.drawBitmap(noiseBitmap, effectSourceRect, rect, nearestPaint);
        fill.setShader(null);
        fill.setColor(appearance.getEffectPalette().first());
        fill.setAlpha(70);
        float scanline = Math.max(4, cell * 2f);
        for (float y = rect.top; y < rect.bottom; y += scanline) {
            canvas.drawRect(rect.left, y, rect.right, Math.min(rect.bottom, y + 1f), fill);
        }
    }

    private void drawGlitch(Canvas canvas, RectF rect, int stableId, int intensity) {
        if (!prepareSourceRect(rect)) {
            drawStatic(canvas, rect, stableId, intensity);
            return;
        }
        int save = canvas.save();
        canvas.clipRect(rect);
        fill.setShader(null);
        fill.setColor(Color.rgb(7, 5, 12));
        fill.setAlpha(255);
        canvas.drawRect(rect, fill);
        float strength = Math.max(1, Math.min(100, intensity)) / 100f;
        float shift = Math.max(3f, (.05f + .10f * strength) * rect.width());
        effectRect.set(rect);
        effectRect.offset(-shift, 0);
        canvas.drawBitmap(frame, sourceRect, effectRect, cyanShiftPaint);
        effectRect.set(rect);
        effectRect.offset(shift, 0);
        canvas.drawBitmap(frame, sourceRect, effectRect, redShiftPaint);
        int bands = Math.max(6, Math.round(8 + strength * 10));
        int sourceBandHeight = Math.max(3, sourceRect.height() / 11);
        long tick = SystemClock.uptimeMillis() / 90L;
        for (int band = 0; band < bands; band++) {
            int available = Math.max(1, sourceRect.height() - sourceBandHeight);
            int sourceTop = sourceRect.top + hashInt(
                    tick + stableId * 131L + band * 31L) % available;
            int sourceBottom = Math.min(sourceRect.bottom, sourceTop + sourceBandHeight);
            float topRatio = (sourceTop - sourceRect.top) / (float) sourceRect.height();
            float bottomRatio = (sourceBottom - sourceRect.top) / (float) sourceRect.height();
            float offset = ((band % 3) - 1) * shift;
            bandSourceRect.set(sourceRect.left, sourceTop, sourceRect.right, sourceBottom);
            bandRect.set(rect.left + offset, rect.top + rect.height() * topRatio,
                    rect.right + offset, rect.top + rect.height() * bottomRatio);
            canvas.drawBitmap(frame, bandSourceRect, bandRect, bitmapPaint);
            fill.setColor(appearance.getEffectPalette().third());
            fill.setAlpha(175);
            canvas.drawRect(rect.left, bandRect.top, rect.right, bandRect.bottom, fill);
        }
        canvas.restoreToCount(save);
    }

    private void drawTape(Canvas canvas, RectF rect, int stableId, int intensity) {
        int save = canvas.save();
        canvas.clipRect(rect);
        if (prepareSourceRect(rect)) {
            bitmapPaint.setAlpha(70);
            canvas.drawBitmap(frame, sourceRect, rect, bitmapPaint);
            bitmapPaint.setAlpha(255);
        }
        fill.setShader(null);
        fill.setColor(appearance.getEffectPalette().first());
        fill.setAlpha(210);
        canvas.drawRect(rect, fill);
        float spacing = Math.max(16f, Math.min(52f,
                50f - Math.max(1, Math.min(100, intensity)) * .25f));
        float shift = (SystemClock.uptimeMillis() / 25f + stableId * 7f) % spacing;
        tapeRedPaint.setStrokeWidth(Math.max(5f, spacing / 3f));
        tapeYellowPaint.setStrokeWidth(Math.max(3f, spacing / 5f));
        float rise = rect.height() * .45f;
        for (float x = rect.left - rect.width(); x < rect.right + rect.width(); x += spacing) {
            canvas.drawLine(x + shift, rect.bottom, x + shift + rise, rect.top, tapeRedPaint);
        }
        for (float x = rect.left - rect.width() + spacing / 2f;
             x < rect.right + rect.width(); x += spacing) {
            canvas.drawLine(x + shift, rect.bottom, x + shift + rise, rect.top, tapeYellowPaint);
        }
        canvas.restoreToCount(save);
    }

    private void drawErrorPopup(Canvas canvas, RectF rect) {
        lockpad.draw(canvas, rect, appearance.getEffectPalette(), activeRenderTimeMillis);
    }

    private static long mix(long seed) {
        long first = (seed ^ (seed >>> 33)) * -49064778989728563L;
        long second = (first ^ (first >>> 33)) * -4265267296055464877L;
        return second ^ (second >>> 33);
    }

    private static int hashInt(long seed) {
        return (int) (mix(seed) & 0x7fffffffL);
    }

    private static int blendColor(int from, int to, float fraction) {
        float value = Math.max(0f, Math.min(1f, fraction));
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * value),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * value),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * value));
    }

    private void drawBorder(Canvas canvas, RectF rect) {
        if (rect.isEmpty()) return;
        border.setStrokeWidth(dp(2));
        border.setShader(null);
        border.setColor(appearance.getBorderColor());
        border.setAlpha(255);
        long animationTime = borderAnimationTimeOverride >= 0L
                ? borderAnimationTimeOverride : SystemClock.uptimeMillis();
        float phase = appearance.isAnimateBorder()
                ? (animationTime % 4000L) / 4000f * 360f : 0f;
        int save = canvas.save();
        canvas.clipRect(rect);
        switch (appearance.getBorderEffect()) {
            case GLOW:
                for (int step = 4; step >= 1; step--) {
                    border.setStrokeWidth(dp(2 + step * 2));
                    border.setAlpha(30 + step * 12);
                    drawShape(canvas, rect, border);
                }
                border.setStrokeWidth(dp(2));
                border.setAlpha(255);
                break;
            case GRADIENT:
                LinearGradient gradient = new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                        appearance.getGradientStart(), appearance.getGradientEnd(), Shader.TileMode.CLAMP);
                borderShaderMatrix.setRotate(phase, rect.centerX(), rect.centerY());
                gradient.setLocalMatrix(borderShaderMatrix);
                border.setShader(gradient);
                break;
            case RAINBOW:
                SweepGradient rainbow = new SweepGradient(rect.centerX(), rect.centerY(),
                        new int[]{Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                                Color.BLUE, Color.MAGENTA, Color.RED}, null);
                borderShaderMatrix.setRotate(phase, rect.centerX(), rect.centerY());
                rainbow.setLocalMatrix(borderShaderMatrix);
                border.setShader(rainbow);
                break;
            case CLASSIC:
            default:
                break;
        }
        drawShape(canvas, rect, border);
        canvas.restoreToCount(save);
        border.setShader(null);
        border.setAlpha(255);
    }

    private void drawShape(Canvas canvas, RectF rect, Paint paint) {
        if (appearance.isReverseMode()
                && "ellipse".equals(appearance.getReverseCutoutShape())) canvas.drawOval(rect, paint);
        else if (appearance.isReverseMode()
                && "rounded".equals(appearance.getReverseCutoutShape())) {
            canvas.drawRoundRect(rect, Math.min(rect.width(), rect.height()) * 0.22f,
                    Math.min(rect.width(), rect.height()) * 0.22f, paint);
        } else canvas.drawRoundRect(rect, dp(8), dp(8), paint);
    }

    /** Paints every label after every censor so no later box can bury earlier text. */
    private void drawOverlayLabels(Canvas canvas) {
        if (!appearance.isShowText()) return;
        labelPlacements.clear();
        float scaleX = (float) getWidth() / captureWidth;
        float scaleY = (float) getHeight() / captureHeight;
        float ageMs = renderAgeMillis();
        for (RenderTrackSnapshot track : tracks) {
            if (suppressedPersonDecorations.contains(track.id())) continue;
            BBox predicted = visualBox(track, ageMs);
            setTrackRect(track, predicted, scaleX, scaleY,
                    "text_smut".equals(track.category()),
                    renderContentOffsetX, renderContentOffsetY, worldSpaceTracks);
            addLabelPlacement(drawRect, track.id());
        }

        float textScaleX = (float) getWidth() / textCaptureWidth;
        float textScaleY = (float) getHeight() / textCaptureHeight;
        float textOffsetX = worldSpaceText ? renderContentOffsetX
                : textContentOffsetX + renderViewportLeadX;
        float textOffsetY = worldSpaceText ? renderContentOffsetY
                : textContentOffsetY + renderViewportLeadY;
        for (RenderTrackSnapshot track : textTracks) {
            setTrackRect(track, textBox(track), textScaleX, textScaleY, true,
                    textOffsetX, textOffsetY, worldSpaceText);
            addLabelPlacement(drawRect, track.id());
        }

        // Bands are below all glyphs. Even two overlapping censors can no longer cover one
        // another's label with their artwork or label background.
        fill.setShader(null);
        fill.setColor(Color.BLACK);
        fill.setAlpha(205);
        for (LabelPlacement placement : labelPlacements) {
            canvas.drawRoundRect(placement.band, dp(5), dp(5), fill);
        }
        for (LabelPlacement placement : labelPlacements) {
            resetLabelPaint();
            label.setTextSize(placement.textSize);
            int save = canvas.save();
            if (appearance.getType() == CensorAppearance.Type.ERROR_POPUP) {
                canvas.clipRect(placement.band);
            }
            canvas.drawText(placement.text, placement.x, placement.baseline, label);
            canvas.restoreToCount(save);
        }
    }

    private void addLabelPlacement(RectF source, int stableId) {
        if (source.width() < dp(32) || source.height() < dp(16)) return;
        RectF rect = new RectF(source);
        boolean lockpadLabel = appearance.getType() == CensorAppearance.Type.ERROR_POPUP;
        if (lockpadLabel) {
            LockpadArtwork.labelBounds(source, rect);
            if (rect.isEmpty()) return;
        }
        float maximumWidth = lockpadLabel ? rect.width() - dp(10)
                : Math.max(dp(18), rect.width() - dp(10));
        float minimumSize = dp(7);
        float heightFraction = lockpadLabel ? .60f : .20f;
        float maximumSize = Math.min(dp(14), Math.max(minimumSize, rect.height() * heightFraction));
        resetLabelPaint();
        label.setTextSize(minimumSize);
        if (lockpadLabel && (maximumWidth <= 0f
                || label.descent() - label.ascent() > rect.height())) return;
        String selected = CensorLabelLayout.selectPhrase(
                appearance.getPhrases(), stableId, maximumWidth, label::measureText);
        label.setTextSize(maximumSize);
        float measured = label.measureText(selected);
        float fittedSize = measured <= maximumWidth || measured <= 0f
                ? maximumSize : Math.max(minimumSize,
                maximumSize * maximumWidth / measured);
        label.setTextSize(fittedSize);
        String fitted = CensorLabelLayout.ellipsize(
                selected, maximumWidth, label::measureText);
        float bandHeight = Math.min(rect.height(),
                Math.max(dp(18), fittedSize * 1.65f));
        RectF band = new RectF(rect.left, rect.centerY() - bandHeight / 2f,
                rect.right, rect.centerY() + bandHeight / 2f);
        float baseline = rect.centerY() - (label.ascent() + label.descent()) / 2f;
        labelPlacements.add(new LabelPlacement(
                band, rect.centerX(), baseline, fittedSize, fitted));
    }

    private void resetLabelPaint() {
        label.setColor(getContext().getColor(R.color.text_primary));
        label.setTextSize(dp(11));
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setFakeBoldText(true);
        label.setShadowLayer(dp(2), 0, dp(1), Color.BLACK);
    }

    private boolean prepareSourceRect(RectF destination) {
        if (frame == null || frame.isRecycled() || getWidth() <= 0 || getHeight() <= 0) return false;
        if (!RenderCoordinates.canSample(activeEffectReference, bitmapReference)) return false;
        // The retained frame predates any compensated scroll. Sample the original source pixels
        // while drawing them at the translated destination so blur/pixelate/glitch remain stable.
        double biasX = activeEffectReference.correctionX(bitmapReference);
        double biasY = activeEffectReference.correctionY(bitmapReference);
        float sourceLeft = RenderCoordinates.source(destination.left, activeEffectOffsetX,
                sourceFrameOffsetX, activePredictionX, biasX);
        float sourceTop = RenderCoordinates.source(destination.top, activeEffectOffsetY,
                sourceFrameOffsetY, activePredictionY, biasY);
        float sourceRight = RenderCoordinates.source(destination.right, activeEffectOffsetX,
                sourceFrameOffsetX, activePredictionX, biasX);
        float sourceBottom = RenderCoordinates.source(destination.bottom, activeEffectOffsetY,
                sourceFrameOffsetY, activePredictionY, biasY);
        int left = Math.max(0, Math.min(frame.getWidth() - 1,
                Math.round(sourceLeft / getWidth() * frame.getWidth())));
        int top = Math.max(0, Math.min(frame.getHeight() - 1,
                Math.round(sourceTop / getHeight() * frame.getHeight())));
        int right = Math.max(left + 1, Math.min(frame.getWidth(),
                Math.round(sourceRight / getWidth() * frame.getWidth())));
        int bottom = Math.max(top + 1, Math.min(frame.getHeight(),
                Math.round(sourceBottom / getHeight() * frame.getHeight())));
        sourceRect.set(left, top, right, bottom);
        return true;
    }

    private boolean isAnimated() {
        return appearance.isShowBorder() && appearance.isAnimateBorder()
                || appearance.getType() == CensorAppearance.Type.STATIC
                || appearance.getType() == CensorAppearance.Type.GLITCH
                || appearance.getType() == CensorAppearance.Type.TAPE
                || appearance.getType() == CensorAppearance.Type.ERROR_POPUP;
    }

    private long renderTimeMillis() {
        if (renderTimeOverride >= 0L) return renderTimeOverride;
        if (presentationFrameTimeMillis >= latestMutationUptime) {
            return presentationFrameTimeMillis;
        }
        // A callback timestamp describes the vsync that began the traversal. Accessibility can
        // publish newer state between that callback and onDraw; evaluating that mutation in the
        // past deliberately renders one stale frame and corrupts input-to-draw diagnostics.
        return SystemClock.uptimeMillis();
    }

    private float renderAgeMillis() {
        return Math.max(0f, renderTimeMillis() - tracksPublishedAtMillis);
    }

    /** Called immediately after publishing the matching raw frame, on the main thread. */
    long beginPersonCoverage(List<Detection> selected, List<Detection> support,
            long capturedAt, long cameraX, long cameraY, int viewportWidth, int viewportHeight,
            RenderSourceReference reference) {
        removeCallbacks(expirePersonCoverage);
        long now = SystemClock.uptimeMillis();
        long token = personCoverage.begin(liveTracks, selected, support, captureWidth, captureHeight,
                capturedAt, now, worldSpaceTracks, cameraX, cameraY,
                viewportWidth, viewportHeight, reference);
        latestMutationUptime = now;
        schedulePersonCoverageExpiry(now);
        postInvalidateOnAnimation();
        return token;
    }

    boolean refinePersonCoverage(long token,
            List<com.subhub.app.detection.PersonBox> people) {
        boolean applied = personCoverage.refine(token, people, SystemClock.uptimeMillis());
        if (applied) {
            latestMutationUptime = SystemClock.uptimeMillis();
            schedulePersonCoverageExpiry(latestMutationUptime);
            postInvalidateOnAnimation();
        }
        return applied;
    }

    void clearPersonCoverage() {
        removeCallbacks(expirePersonCoverage);
        personCoverage.clear();
        latestMutationUptime = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    private void schedulePersonCoverageExpiry(long now) {
        removeCallbacks(expirePersonCoverage);
        long delay = personCoverage.nextRefreshDelay(now);
        if (delay > 0L) postDelayed(expirePersonCoverage, delay);
    }

    private void advancePersonFrame() {
        removeCallbacks(expirePersonCoverage);
        personCoverage.advanceFrame();
    }

    private List<RenderTrackSnapshot> visualPaintOrder() {
        if (suppressedPersonDecorations.isEmpty()) return tracks;
        // Paint the enclosing owner last so a duplicate fill cannot erase its border.
        List<RenderTrackSnapshot> ordered = new ArrayList<>(tracks.size());
        for (RenderTrackSnapshot track : tracks) {
            if (suppressedPersonDecorations.contains(track.id())) ordered.add(track);
        }
        for (RenderTrackSnapshot track : tracks) {
            if (!suppressedPersonDecorations.contains(track.id())) ordered.add(track);
        }
        return ordered;
    }

    private void updatePersonDecorations() {
        suppressedPersonDecorations = Collections.emptySet();
        if (appearance.isReverseMode() || !personCoverage.hasActiveCoverage(activeRenderTimeMillis)) return;
        List<BBox> base = new ArrayList<>(tracks.size());
        List<BBox> expanded = new ArrayList<>(tracks.size());
        float age = renderAgeMillis();
        for (RenderTrackSnapshot track : tracks) {
            BBox box = baseVisualBox(track, age);
            base.add(box);
            expanded.add(expandVisualBox(track, box));
        }
        suppressedPersonDecorations = PersonDecorationLayout.suppressed(tracks, base, expanded);
    }

    private BBox visualBox(RenderTrackSnapshot track, float ageMs) {
        return expandVisualBox(track, baseVisualBox(track, ageMs));
    }

    private BBox expandVisualBox(RenderTrackSnapshot track, BBox current) {
        return personCoverage.expand(track, current,
                worldSpaceTracks ? visualLayout.memberIds(track.id()) : Collections.emptyList(),
                activeRenderTimeMillis);
    }

    private BBox baseVisualBox(RenderTrackSnapshot track, float ageMs) {
        if (usesContinuousSteering()) {
            BBox steered = visualSteering.position(
                    track.id(), captureWidth, captureHeight, activeRenderTimeMillis);
            if (steered != null) return track.preserveGroupCoverage(steered);
        }
        return track.preserveGroupCoverage(track.predict(ageMs, maxExtrapolationMs));
    }

    private BBox textBox(RenderTrackSnapshot track) {
        if (usesContinuousSteering()) {
            BBox steered = textSteering.position(
                    track.id(), textCaptureWidth, textCaptureHeight, activeRenderTimeMillis);
            if (steered != null) return steered;
        }
        return track.box();
    }

    private boolean usesContinuousSteering() {
        // Accessibility owns viewport movement and disables raw detector extrapolation. Its
        // geometry corrections still need persistent display-time steering. MediaProjection keeps
        // its existing velocity-prediction path until it receives a separately profiled pass.
        return maxExtrapolationMs <= 0.01f;
    }

    private boolean hasActiveSteering(long nowMillis) {
        return usesContinuousSteering()
                && (visualSteering.isAnimating(nowMillis)
                || textSteering.isAnimating(nowMillis));
    }

    private boolean hasActivePrediction(long nowMillis) {
        if (nowMillis - tracksPublishedAtMillis >= maxExtrapolationMs) return false;
        for (RenderTrackSnapshot track : tracks) {
            if (track.isMoving()) return true;
        }
        return false;
    }

    private void scheduleNextFrame(long nowMillis) {
        if (frameCallbackPosted || !isAttachedToWindow()
                || tracks.isEmpty() && textTracks.isEmpty()
                || (!isAnimated() && !hasActivePrediction(nowMillis)
                && !viewportMotion.isAnimating(nowMillis)
                && !hasActiveSteering(nowMillis)
                && !motionDrawPending)) return;
        frameCallbackPosted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            postExpectedPresentationCallback(++frameCallbackGeneration);
        } else {
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    @SuppressLint("NewApi")
    private void postExpectedPresentationCallback(long generation) {
        Choreographer.getInstance().postVsyncCallback(vsyncData -> {
            if (!frameCallbackPosted || generation != frameCallbackGeneration) return;
            frameCallbackPosted = false;
            notePresentationFrame(vsyncData.getPreferredFrameTimeline()
                    .getExpectedPresentationTimeNanos() / 1_000_000L);
            invalidate();
            scheduleNextFrame(presentationFrameTimeMillis);
        });
    }

    private void notePresentationFrame(long frameTimeMillis) {
        long delta = lastPresentationFrameTimeMillis <= 0L
                ? 0L : frameTimeMillis - lastPresentationFrameTimeMillis;
        renderTickMillis = delta > 0L && delta <= 50L ? delta : 0L;
        lastPresentationFrameTimeMillis = frameTimeMillis;
        presentationFrameTimeMillis = frameTimeMillis;
    }

    private void noteMotionInput(String source, float dx, float dy, boolean forceTrace) {
        long now = SystemClock.uptimeMillis();
        motionSequence++;
        motionInputUptime = now;
        motionDrawPending = true;
        if (forceTrace || now - lastMotionTraceInputUptime >= MOTION_TRACE_INTERVAL_MS) {
            lastMotionTraceInputUptime = now;
            CensorLabLog.i(MOTION_TAG, "INPUT seq=" + motionSequence + " source=" + source
                    + " dx=" + Math.round(dx) + " dy=" + Math.round(dy)
                    + " prediction="
                    + Math.round(viewportMotion.predictionAmplitude().x) + ','
                    + Math.round(viewportMotion.predictionAmplitude().y)
                    + " predictionPeakMs=" + viewportMotion.predictionPeakMillis());
        }
    }

    private void traceRenderedMotion(boolean animationActive) {
        long now = SystemClock.uptimeMillis();
        long presentationTime = renderTimeMillis();
        if (motionDrawPending) {
            motionDrawPending = false;
            CensorLabLog.i(MOTION_TAG, "DRAW seq=" + motionSequence + " inputToDrawMs="
                    + Math.max(0L, now - motionInputUptime)
                    + " drawClock=uptime inputToPresentationMs="
                    + Math.max(0L, presentationTime - motionInputUptime) + " visual="
                    + Math.round(renderContentOffsetX) + ',' + Math.round(renderContentOffsetY)
                    + " text=" + Math.round(textContentOffsetX + renderViewportLeadX) + ','
                    + Math.round(textContentOffsetY + renderViewportLeadY)
                    + " viewportLead=" + Math.round(renderViewportLeadX) + ','
                    + Math.round(renderViewportLeadY)
                    + " renderTickMs=" + renderTickMillis);
        }
        if (motionAnimationWasActive && !animationActive) {
            CensorLabLog.i(MOTION_TAG, "SETTLED seq=" + motionSequence + " inputToSettledMs="
                    + Math.max(0L, now - motionInputUptime)
                    + " visual=" + Math.round(renderContentOffsetX) + ','
                    + Math.round(renderContentOffsetY));
        }
        motionAnimationWasActive = animationActive;
    }

    private void stopFrameCallback() {
        if (!frameCallbackPosted) return;
        frameCallbackGeneration++;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
        }
        frameCallbackPosted = false;
    }

    void release() {
        tapTracker.clearDisplayed(this);
        removeCallbacks(refreshDisplayedTapGeometry);
        clearPersonCoverage();
        stopFrameCallback();
        releaseFrame();
        if (effectScratch != null && !effectScratch.isRecycled()) effectScratch.recycle();
        effectScratch = null;
        effectCanvas = null;
        if (noiseBitmap != null && !noiseBitmap.isRecycled()) noiseBitmap.recycle();
        noiseBitmap = null;
        noisePixels = null;
        customImages.close();
        visualSteering.clear();
        textSteering.clear();
        visualLayout.clear();
        visualReferences.clear();
        textReferences.clear();
        measuredOrigin = null;
        currentRenderReference = RenderSourceReference.UNKNOWN;
        bitmapReference = RenderSourceReference.UNKNOWN;
        solidRenderLayers.clear();
    }

    private void releaseFrame() {
        Bitmap owned = frame;
        Runnable release = frameRelease;
        frame = null;
        frameRelease = null;
        if (owned == null || owned.isRecycled()) return;
        if (release != null) release.run();
        else owned.recycle();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleNextFrame(SystemClock.uptimeMillis());
    }

    @Override
    protected void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (tapTracker != null && !isShown()) {
            tapTracker.clearDisplayed(this);
            removeCallbacks(refreshDisplayedTapGeometry);
        }
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (tapTracker != null && visibility != VISIBLE) {
            tapTracker.clearDisplayed(this);
            removeCallbacks(refreshDisplayedTapGeometry);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static final class SolidRenderLayer {
        private final RenderNode node;
        private final int width;
        private final int height;

        private final boolean decorations;

        private SolidRenderLayer(RenderNode node, int width, int height, boolean decorations) {
            this.node = node;
            this.width = width;
            this.height = height;
            this.decorations = decorations;
        }
    }

    private static final class LabelPlacement {
        private final RectF band;
        private final float x;
        private final float baseline;
        private final float textSize;
        private final String text;

        private LabelPlacement(
                RectF band,
                float x,
                float baseline,
                float textSize,
                String text) {
            this.band = band;
            this.x = x;
            this.baseline = baseline;
            this.textSize = textSize;
            this.text = text;
        }
    }
}
