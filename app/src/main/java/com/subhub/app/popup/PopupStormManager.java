package com.subhub.app.popup;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.subhub.app.R;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.TrackedObject;
import com.subhub.app.stats.StatsRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Safe, bounded reconstruction of the licensed multi-window popup renderer. */
public final class PopupStormManager {
    private static final String TAG = "PopupStorm";
    private static final int WINDOW_SLOP = 6;
    private static final int STOP_MARGIN = 16;
    public static final long PREVIEW_DURATION_MS = 10_000L;
    public enum PreviewResult { STARTED, ALREADY_RUNNING, UNAVAILABLE }
    private static final PopupStormManager INSTANCE = new PopupStormManager();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PopupRunOwnership ownership = new PopupRunOwnership();
    private Runnable previewTimeout;
    private final AtomicLong ids = new AtomicLong();
    private final CopyOnWriteArrayList<LivePopup> popups = new CopyOnWriteArrayList<>();
    private final Random random = new Random();
    private volatile List<RectF> detections = List.of();
    private Context context;
    private PopupBitmapCache bitmapCache;
    private PopupLibrary library;
    private volatile PopupStormSettings settings;
    private WindowManager windowManager;
    private TextView stopControl;
    private volatile boolean running;
    private volatile boolean startRequested;
    private long lastTick;
    private long lastSpawn;
    private long burstStarted;
    private long burstUntil;
    private int screenWidth;
    private int screenHeight;
    private int reservedTop;
    private volatile String scheduledLibrarySignature = "";

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            tick();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private PopupStormManager() {}
    public static PopupStormManager get() { return INSTANCE; }
    public boolean isRunning() { return running; }
    public boolean isPreviewing() { return ownership.isPreview(); }
    /** Foreground/service reevaluation must not reset a preview or restart image I/O each event. */
    public void syncServiceParticipation(Context source, boolean eligible) {
        if (eligible) {
            if (!ownership.isService()) start(source);
        } else if (ownership.isService()) stop();
    }
    public long remainingPreviewMillis() {
        return ownership.remainingPreviewMillis(SystemClock.uptimeMillis());
    }
    public int libraryImageCount() { return library == null ? 0 : library.count(); }

    public void reloadSettings(Context source) {
        initialize(source);
        settings = PopupStormSettings.load(source);
        rescanAsync();
        if (!settings.isEnabled() && !ownership.isPreview() && running) stop();
    }

    public boolean canStart(Context source) {
        initialize(source);
        PopupStormSettings current = settings == null ? PopupStormSettings.load(source) : settings;
        return current.isEnabled() && canRender(source);
    }

    private boolean canRender(Context source) {
        PopupStormSettings current = settings == null ? PopupStormSettings.load(source) : settings;
        return current.isAcknowledged()
                && Settings.canDrawOverlays(source) && library != null && !library.isEmpty();
    }

    public void start(Context source) {
        // Claim before posting so an already queued preview timeout cannot stop
        // an effect that a real service has adopted from another thread.
        long token;
        synchronized (ownership) {
            token = ownership.adoptService();
            startRequested = true;
        }
        initialize(source);
        settings = PopupStormSettings.load(source);
        rescanAsync();
        main.post(() -> {
            synchronized (ownership) {
                if (!ownership.isService(token)) return;
                cancelPreviewTimeout();
                startIfReady();
            }
        });
    }

    /** Starts at most ten seconds of editor preview without changing service state. */
    public PreviewResult preview(Context source) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Popup previews must be requested on the main thread");
        }
        initialize(source);
        settings = PopupStormSettings.load(source);
        if (!settings.isAcknowledged()
                || !Settings.canDrawOverlays(source)) return PreviewResult.UNAVAILABLE;
        synchronized (ownership) {
            if (running || ownership.hasOwner()) return PreviewResult.ALREADY_RUNNING;
            long token = ownership.beginPreview(SystemClock.uptimeMillis(), PREVIEW_DURATION_MS);
            startRequested = true;
            cancelPreviewTimeout();
            previewTimeout = () -> {
                synchronized (ownership) {
                    if (!ownership.expirePreview(token, SystemClock.uptimeMillis())) return;
                    startRequested = false;
                    previewTimeout = null;
                    stopOnMain();
                }
            };
            main.postDelayed(previewTimeout, PREVIEW_DURATION_MS);
            rescanAsync();
            startIfReady();
        }
        return PreviewResult.STARTED;
    }

    public void stop() {
        long token;
        synchronized (ownership) {
            token = ownership.stop();
            startRequested = false;
        }
        Runnable stopRequest = () -> {
            synchronized (ownership) {
                // An older queued stop must not remove a newly owned effect.
                if (!ownership.isStopped(token)) return;
                cancelPreviewTimeout();
                stopOnMain();
            }
        };
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(stopRequest);
        } else {
            stopRequest.run();
        }
    }

    private void cancelPreviewTimeout() {
        if (previewTimeout == null) return;
        main.removeCallbacks(previewTimeout);
        previewTimeout = null;
    }

    public void updateDetections(List<RectF> boxes) {
        List<RectF> copies = new ArrayList<>();
        for (RectF box : boxes) copies.add(new RectF(box));
        detections = Collections.unmodifiableList(copies);
    }

    public void updateTrackedObjects(List<TrackedObject> tracks, int frameWidth, int frameHeight) {
        if (frameWidth <= 0 || frameHeight <= 0) return;
        float scaleX = screenWidth / (float) frameWidth;
        float scaleY = screenHeight / (float) frameHeight;
        List<RectF> boxes = new ArrayList<>();
        for (TrackedObject track : tracks) {
            if (!track.isActive()) continue;
            BBox box = track.getBox();
            boxes.add(new RectF(box.getX() * scaleX, box.getY() * scaleY,
                    box.getRight() * scaleX, box.getBottom() * scaleY));
        }
        updateDetections(boxes);
    }

    void onPopupTapped(Popup target) {
        main.post(() -> {
            PopupStormSettings current = settings;
            if (current != null && current.isClickDismissesAll()) clearPopups();
            else for (LivePopup live : popups) if (live.popup == target) {
                popups.remove(live);
                remove(live);
                break;
            }
        });
    }

    private synchronized void initialize(Context source) {
        if (context != null) return;
        context = source.getApplicationContext();
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        bitmapCache = new PopupBitmapCache(context);
        library = new PopupLibrary(bitmapCache);
        settings = PopupStormSettings.load(context);
        updateDisplayBounds();
    }

    private void rescanAsync() {
        PopupStormSettings snapshot = settings;
        PopupLibrary target = library;
        if (snapshot == null || target == null) return;
        String signature = snapshot.getPackImageDir() + "\n" + String.join("\n", snapshot.getFolders());
        if (signature.equals(scheduledLibrarySignature)) return;
        scheduledLibrarySignature = signature;
        Thread scan = new Thread(() -> {
            target.rescan(snapshot.getPackImageDir(), snapshot.getFolders());
            main.post(this::startIfReady);
        }, "PopupStorm-library");
        scan.setDaemon(true);
        scan.start();
    }

    private void startIfReady() {
        synchronized (ownership) {
            long preview = ownership.previewToken();
            if (preview != 0 && ownership.expirePreview(preview, SystemClock.uptimeMillis())) {
                startRequested = false;
                cancelPreviewTimeout();
                stopOnMain();
                return;
            }
            if (running || !startRequested || !ownership.hasOwner()
                    || context == null || (!ownership.isPreview() && !settings.isEnabled())
                    || !canRender(context)) return;
            updateDisplayBounds();
            long now = System.currentTimeMillis();
            lastTick = now;
            lastSpawn = now;
            burstStarted = 0;
            burstUntil = 0;
            running = true;
            showStopControl();
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    private void stopOnMain() {
        if (running) Choreographer.getInstance().removeFrameCallback(frameCallback);
        running = false;
        clearPopups();
        if (stopControl != null) {
            try { windowManager.removeViewImmediate(stopControl); } catch (Exception ignored) {}
            stopControl = null;
        }
        detections = List.of();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        float seconds = Math.max(0, Math.min(.1f, (now - lastTick) / 1000f));
        lastTick = now;
        PopupStormSettings current = settings;
        if (current == null || (!ownership.isPreview() && !current.isEnabled())
                || !current.isAcknowledged()) { stop(); return; }
        for (LivePopup live : popups) {
            if (now >= live.popup.expiresAt()) {
                popups.remove(live);
                remove(live);
                continue;
            }
            if (current.isBouncing()) {
                live.popup.integrate(seconds, screenWidth, screenHeight, reservedTop);
                reposition(live);
            }
            live.view.postInvalidateOnAnimation();
        }
        float rate = effectiveRate(current, now);
        long interval = rate <= 0 ? Long.MAX_VALUE : Math.max(50, (long) (1000f / rate));
        if (popups.size() < current.getMaxSimultaneous() && now - lastSpawn >= interval
                && spawnOne(current, now)) lastSpawn = now;
    }

    private float effectiveRate(PopupStormSettings current, long now) {
        if (!current.isBurstEnabled()) return current.getSpawnRate();
        if (now > burstUntil && now > burstStarted + (long) (current.getBurstFrequency() * 1000)) {
            burstStarted = now;
            burstUntil = now + (long) (current.getBurstDuration() * 1000);
        }
        if (now < burstUntil) {
            float progress = Math.max(0, Math.min(1,
                    (now - burstStarted) / (current.getBurstDuration() * 1000f)));
            return Math.min(PopupStormSettings.MAX_SPAWN_RATE,
                    current.getSpawnRate() * (1 + (current.getBurstMultiplier() - 1) * progress));
        }
        return current.getSpawnRate();
    }

    private boolean spawnOne(PopupStormSettings current, long now) {
        PopupLibrary.BitmapSource source = library.randomBitmap();
        if (source == null) return false;
        Bitmap bitmap = source.bitmap;
        int requested = "fixed".equals(current.getSizeMode()) ? current.getFixedSize()
                : randomBetween(current.getMinSize(), current.getMaxSize());
        float scale = requested / (float) Math.max(bitmap.getWidth(), bitmap.getHeight());
        int width = Math.max(40, Math.round(bitmap.getWidth() * scale));
        int height = Math.max(40, Math.round(bitmap.getHeight() * scale));
        width = Math.min(width, screenWidth);
        height = Math.min(height, Math.max(40, screenHeight - reservedTop));
        float[] position = choosePosition(current, width, height);
        float rotation = current.isRandomRotation()
                ? randomBetween(-current.getRotationMax(), current.getRotationMax()) : 0;
        float velocityX = 0;
        float velocityY = 0;
        if (current.isBouncing()) {
            double angle = random.nextDouble() * Math.PI * 2;
            velocityX = (float) Math.cos(angle) * current.getBouncingSpeed();
            velocityY = (float) Math.sin(angle) * current.getBouncingSpeed();
        }
        Bitmap denial = null;
        String caption = null;
        if (random.nextInt(100) < current.getDenialChance()) {
            boolean pixelate = "pixelate".equals(current.getDenialStyle())
                    || ("mixed".equals(current.getDenialStyle()) && random.nextBoolean());
            denial = pixelate ? DenialFilter.pixelate(bitmap, current.getDenialIntensity())
                    : DenialFilter.blur(bitmap, current.getDenialIntensity());
            if (current.isDenialCaption()) caption = current.getDenialCaptionText();
        }
        Popup popup = new Popup(ids.incrementAndGet(), source.path, bitmap, denial, caption,
                width, height, rotation, now, (long) (current.getDisplayDuration() * 1000),
                current.getFadeInMs(), current.getFadeOutMs(), position[0], position[1],
                velocityX, velocityY);
        LivePopup live = attach(popup);
        if (live == null) { popup.recycleDerived(); return false; }
        popups.add(live);
        new StatsRepository(context).recordPopupImpression();
        return true;
    }

    private float[] choosePosition(PopupStormSettings current, int width, int height) {
        List<RectF> boxes = detections;
        if ("cover".equals(current.getDetectionMode()) && !boxes.isEmpty()) {
            RectF box = boxes.get(random.nextInt(boxes.size()));
            return clampPosition(box.centerX() - width / 2f, box.centerY() - height / 2f, width, height);
        }
        if ("center".equals(current.getPositionMode())) {
            return clampPosition((screenWidth - width) / 2f, (screenHeight - height) / 2f, width, height);
        }
        for (int attempt = 0; attempt < 20; attempt++) {
            float x = random.nextInt(Math.max(1, screenWidth - width + 1));
            float y = reservedTop + random.nextInt(Math.max(1, screenHeight - reservedTop - height + 1));
            if (!"avoid".equals(current.getDetectionMode())
                    || avoidsDetections(x, y, width, height, current.getAvoidPadding(), boxes)) {
                return new float[]{x, y};
            }
        }
        return clampPosition((screenWidth - width) / 2f, (screenHeight - height) / 2f, width, height);
    }

    private boolean avoidsDetections(float x, float y, int width, int height, int padding,
            List<RectF> boxes) {
        RectF candidate = new RectF(x - padding, y - padding,
                x + width + padding, y + height + padding);
        for (RectF box : boxes) if (RectF.intersects(candidate, box)) return false;
        return true;
    }

    private float[] clampPosition(float x, float y, int width, int height) {
        return new float[]{Math.max(0, Math.min(screenWidth - width, x)),
                Math.max(reservedTop, Math.min(screenHeight - height, y))};
    }

    private LivePopup attach(Popup popup) {
        PopupOverlayView view = new PopupOverlayView(context, this, popup);
        WindowManager.LayoutParams params = paramsFor(popup);
        try {
            windowManager.addView(view, params);
            return new LivePopup(popup, view, params);
        } catch (Exception error) {
            Log.w(TAG, "Could not attach popup", error);
            return null;
        }
    }

    private WindowManager.LayoutParams paramsFor(Popup popup) {
        double radians = Math.toRadians(popup.rotation);
        float cosine = (float) Math.abs(Math.cos(radians));
        float sine = (float) Math.abs(Math.sin(radians));
        int outerWidth = Math.max(1, Math.round(popup.width * cosine + popup.height * sine) + WINDOW_SLOP * 2);
        int outerHeight = Math.max(1, Math.round(popup.width * sine + popup.height * cosine) + WINDOW_SLOP * 2);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                outerWidth, outerHeight, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle(context.getString(R.string.popup_image_window_title));
        params.x = Math.round(popup.x - (outerWidth - popup.width) / 2f);
        params.y = Math.round(popup.y - (outerHeight - popup.height) / 2f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return params;
    }

    private void reposition(LivePopup live) {
        WindowManager.LayoutParams next = paramsFor(live.popup);
        live.params.x = next.x;
        live.params.y = next.y;
        try { windowManager.updateViewLayout(live.view, live.params); } catch (Exception ignored) {}
    }

    private void remove(LivePopup live) {
        try { windowManager.removeViewImmediate(live.view); } catch (Exception ignored) {}
        live.popup.recycleDerived();
    }

    private void clearPopups() {
        for (LivePopup live : popups) remove(live);
        popups.clear();
    }

    private void showStopControl() {
        if (stopControl != null) return;
        TextView stop = new TextView(context);
        stop.setText(R.string.popup_stop_now);
        stop.setTextColor(Color.WHITE);
        com.subhub.app.util.UiIdentity.textSize(stop, R.dimen.ui_text_body);
        stop.setGravity(Gravity.CENTER);
        stop.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        stop.setPadding(dp(18), 0, dp(18), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(context.getColor(R.color.accent_dark));
        background.setStroke(dp(2), context.getColor(R.color.accent));
        background.setCornerRadius(dp(24));
        stop.setBackground(background);
        stop.setOnClickListener(view -> stop());
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, dp(48),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.setTitle(context.getString(R.string.popup_stop_window_title));
        params.x = dp(STOP_MARGIN);
        params.y = dp(STOP_MARGIN);
        try {
            windowManager.addView(stop, params);
            stopControl = stop;
            reservedTop = dp(80);
        } catch (Exception error) {
            Log.w(TAG, "Persistent stop control unavailable; aborting Popup Storm", error);
            running = false;
        }
    }

    private void updateDisplayBounds() {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        reservedTop = dp(80);
    }

    private int randomBetween(int minimum, int maximum) {
        if (maximum <= minimum) return minimum;
        return minimum + random.nextInt(maximum - minimum + 1);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class LivePopup {
        final Popup popup;
        final PopupOverlayView view;
        final WindowManager.LayoutParams params;
        LivePopup(Popup popup, PopupOverlayView view, WindowManager.LayoutParams params) {
            this.popup = popup;
            this.view = view;
            this.params = params;
        }
    }
}
