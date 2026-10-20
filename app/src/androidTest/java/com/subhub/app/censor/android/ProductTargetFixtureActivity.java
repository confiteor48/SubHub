package com.subhub.app.censor.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Synthetic real foreground target in the separate test package, not the protected product process. */
public final class ProductTargetFixtureActivity extends Activity {
    public static final int UNDERLYING = 0xff18ac58;
    public static final String MOTION_FRAME_COMMIT = "com.subhub.app.test.motionFrameCommit";
    public static final String NATIVE_COMMAND = "com.subhub.app.test.nativeCommand";
    public static final String NATIVE_SCROLL_Y = "com.subhub.app.test.nativeScrollY";
    public static final String NATIVE_ADDED = "com.subhub.app.test.nativeAdded";
    public static final String NATIVE_MUTATION = "com.subhub.app.test.nativeMutation";
    public static final String VISUAL_COMMAND = "com.subhub.app.test.visualCommand";
    public static final String VISUAL_FRAME = "com.subhub.app.test.visualFrame";
    public static final String VISUAL_FRAME_AT = "com.subhub.app.test.visualFrameAt";
    public static final String VISUAL_SHA = "com.subhub.app.test.visualSha";
    public static final String VISUAL_BOUNDS = "com.subhub.app.test.visualBounds";
    public static final String VISUAL_GEOMETRY = "com.subhub.app.test.visualGeometry";
    public static final String VISUAL_SCROLL = "com.subhub.app.test.visualScroll";
    private static final String POSITIVE_SHA = "88431cd9653ccd539741b555fb0a46b61558b301d4110412b5bc28b5e3ea6cb5";
    private TextView label;
    private FrameLayout root;
    private android.widget.ScrollView scroll;
    private android.view.View motionMedia;
    private android.widget.ScrollView independentScroll;
    private boolean motionFixture;
    private boolean nativeScrollFixture;
    private TextView fixedLabel;
    private TextView addedLabel;
    private boolean compoundMotionScroll;
    private boolean motionOwnerRecyclerLabel, motionEventRecyclerLabel;
    private long motionFrameCommit;
    private long nativeCommand;
    private long nativeMutation;
    private android.widget.ImageView visualImage;
    private android.graphics.Bitmap visualBitmap;
    private android.net.Uri visualUri;
    private android.os.ResultReceiver visualRetirementReceiver;
    private boolean visualStreamsClosed;
    private final android.os.Handler safety = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable safetyFinish = this::finish;
    private long visualCommand, visualFrame, visualFrameAt = -1;
    private long visualDrawCount;
    private android.widget.ScrollView visualScroll;
    private final java.util.List<android.widget.ImageView> visualCopies = new java.util.ArrayList<>();
    private PositivePhotoScrollMotion visualMotion;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); getWindow().setWindowAnimations(0); overridePendingTransition(0, 0);
        root = new FrameLayout(this); root.setBackgroundColor(UNDERLYING);
        label = new TextView(this); label.setTextColor(0xff000000); label.setTextSize(20);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2); params.topMargin = 120; params.leftMargin = 48;
        root.addView(label, params); setContentView(root);
        safety.postDelayed(safetyFinish, 60000); apply(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); apply(intent); }
    private void apply(Intent intent) {
        if (intent.getBooleanExtra("finish", false)) {
            if (visualUri != null && visualUri.toString().equals(intent.getStringExtra("visualRetireUri")) && visualStreamsClosed) {
                visualRetirementReceiver = intent.getParcelableExtra("visualRetirementReceiver");
            }
            finish(); return;
        }
        if (intent.getBooleanExtra("positiveVisualFixture", false)) preparePositiveVisual(intent);
        if (visualImage != null) {
            if (intent.getBooleanExtra("positiveScrollStart", false)) {
                if (visualMotion == null) throw new IllegalStateException("Scroll target not selected");
                visualMotion.start();
            }
            if (intent.hasExtra("visualCommand")) visualCommand = intent.getLongExtra("visualCommand", 0);
            if (visualCommand <= 0) throw new IllegalArgumentException("Positive fixture command");
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                long selected = visualCommand;
                root.getViewTreeObserver().registerFrameCommitCallback(() -> {
                    if (visualCommand != selected) return;
                    visualFrame = selected; visualFrameAt = android.os.SystemClock.uptimeMillis();
                    root.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
                });
                root.invalidate();
            }
            return;
        }
        if (intent.hasExtra("motionOwnerRecyclerLabel")) {
            motionOwnerRecyclerLabel = intent.getBooleanExtra("motionOwnerRecyclerLabel", false);
            if (scroll != null) scroll.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }
        if (intent.hasExtra("motionEventRecyclerLabel")) motionEventRecyclerLabel = intent.getBooleanExtra("motionEventRecyclerLabel", false);
        if (intent.getBooleanExtra("motionFixture", false) && !motionFixture) createMotionFixture();
        if (intent.getBooleanExtra("nativeScrollFixture", false) && !nativeScrollFixture) createNativeScrollFixture();
        if (intent.getBooleanExtra("scrollFixture", false) && scroll == null) {
            android.util.DisplayMetrics display = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(display);
            root.removeView(label);
            FrameLayout content = new FrameLayout(this); content.addView(label);
            scroll = new android.widget.ScrollView(this) {
                @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
                    super.onSizeChanged(width, height, oldWidth, oldHeight);
                    if (height > 0) content.setMinimumHeight(height - getPaddingTop() - getPaddingBottom() + 1);
                }
            };
            scroll.setId(android.R.id.list);
            scroll.setPadding(0, 0, 0, 0);
            scroll.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
            scroll.addView(content, new android.widget.ScrollView.LayoutParams(-1, display.heightPixels + 200));
            root.addView(scroll, new FrameLayout.LayoutParams(-1, 1200));
        }
        if (intent.getBooleanExtra("insetWindow", false)) {
            android.view.WindowManager.LayoutParams layout = getWindow().getAttributes();
            android.util.DisplayMetrics display = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(display);
            layout.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
            layout.x = 40; layout.y = 80;
            layout.width = display.widthPixels - 80; layout.height = display.heightPixels - 160;
            getWindow().setAttributes(layout);
        }
        // Scroll-only commands must not manufacture text-content changes or cancel confirmation.
        String literal = intent.getStringExtra("label");
        if (literal == null) literal = label.getText().length() == 0 ? "Target" : label.getText().toString();
        if (!literal.contentEquals(label.getText())) label.setText(literal);
        if (fixedLabel != null && !literal.contentEquals(fixedLabel.getText())) fixedLabel.setText(literal);
        if (intent.getBooleanExtra("compoundScrollRecord", false)) compoundMotionScroll = true;
        if (scroll != null && intent.hasExtra("scrollToY")) scroll.scrollTo(0, intent.getIntExtra("scrollToY", 0));
        if (nativeScrollFixture && intent.hasExtra("nativeCommand")) nativeCommand = intent.getLongExtra("nativeCommand", 0);
        if (nativeScrollFixture && intent.hasExtra("nativeAdded")) {
            nativeMutation = nativeCommand;
            if (intent.getBooleanExtra("nativeAdded", false) && addedLabel == null) {
                addedLabel = new TextView(this); addedLabel.setId(android.R.id.message);
                addedLabel.setText("porn"); addedLabel.setTextColor(0xff000000); addedLabel.setTextSize(20);
                addedLabel.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                int width = getResources().getDisplayMetrics().widthPixels;
                FrameLayout.LayoutParams added = new FrameLayout.LayoutParams(width / 3, -2);
                added.leftMargin = width / 2; added.topMargin = 850; root.addView(addedLabel, added);
            } else if (!intent.getBooleanExtra("nativeAdded", false) && addedLabel != null) {
                root.removeView(addedLabel); addedLabel = null;
            }
        }
        if (motionMedia != null && intent.hasExtra("mediaTranslationY"))
            motionMedia.setTranslationY(intent.getIntExtra("mediaTranslationY", 0));
        if (independentScroll != null && intent.hasExtra("independentScrollToY"))
            independentScroll.scrollTo(0, intent.getIntExtra("independentScrollToY", 0));
        if (motionFixture && android.os.Build.VERSION.SDK_INT >= 29) {
            root.getViewTreeObserver().registerFrameCommitCallback(() -> {
                motionFrameCommit++; root.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            });
            root.invalidate();
        }
    }

    /** Only the fixed, bounded granted public fixture. This is never a product image loader. */
    private void preparePositiveVisual(Intent intent) {
        android.net.Uri uri = intent.getData();
        if (uri == null || !"content".equals(uri.getScheme()) || !"com.subhub.app.updates".equals(uri.getAuthority())
                || uri.getPath() == null || !uri.getPath().matches("/censor_lab_exports/visual-reentry-[a-f0-9]{32}\\.png")) {
            throw new IllegalArgumentException("Narrow positive fixture grant required");
        }
        if (visualImage != null) {
            if (!uri.equals(visualUri)) throw new IllegalArgumentException("Do not replace live fixture pixels");
            return;
        }
        // The opt-in cohort has multiple independent bounded waits; keep its safety timer separate
        // from ordinary fixture tests without refreshing it on each return command.
        safety.removeCallbacks(safetyFinish); safety.postDelayed(safetyFinish, 180000);
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            int total = 0;
            try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("Missing fixture stream");
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count; if (total > 791555) throw new IllegalStateException("Oversized fixture");
                    digest.update(buffer, 0, count);
                }
            }
            if (total != 791555 || !POSITIVE_SHA.equals(hex(digest.digest()))) throw new IllegalStateException("Changed fixture bytes");
            android.graphics.BitmapFactory.Options dimensions = new android.graphics.BitmapFactory.Options();
            dimensions.inJustDecodeBounds = true;
            try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
                android.graphics.BitmapFactory.decodeStream(input, null, dimensions);
            }
            if (dimensions.outWidth != 512 || dimensions.outHeight != 512) throw new IllegalStateException("Fixture dimensions");
            android.graphics.Bitmap decoded;
            try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
                decoded = android.graphics.BitmapFactory.decodeStream(input);
            }
            // All reads are synchronous on this main thread and every stream is closed here.
            // Subsequent commands reuse decoded pixels; no background reader owns the URI.
            visualStreamsClosed = true;
            if (decoded == null || decoded.getWidth() != 512 || decoded.getHeight() != 512) throw new IllegalStateException("Fixture decode");
            visualBitmap = decoded; visualUri = uri;
            visualImage = new android.widget.ImageView(this) {
                @Override protected void onDraw(android.graphics.Canvas canvas) { super.onDraw(canvas); visualDrawCount++; }
            };
            visualImage.setId(android.R.id.icon);
            visualImage.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            visualImage.setContentDescription("Positive visual fixture");
            visualImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER); visualImage.setImageBitmap(decoded);
            root.removeAllViews(); root.setId(android.R.id.custom); root.setBackgroundColor(android.graphics.Color.WHITE);
            root.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            root.setAccessibilityDelegate(new android.view.View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(android.view.View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.getExtras().putLong(VISUAL_COMMAND, visualCommand);
                    info.getExtras().putLong(VISUAL_FRAME, visualFrame);
                    info.getExtras().putLong(VISUAL_FRAME_AT, visualFrameAt);
                    info.getExtras().putString(VISUAL_SHA, POSITIVE_SHA);
                    int[] screen = new int[2]; visualImage.getLocationOnScreen(screen);
                    info.getExtras().putIntArray(VISUAL_BOUNDS,
                            new int[]{screen[0], screen[1], screen[0] + visualImage.getWidth(), screen[1] + visualImage.getHeight()});
                    android.graphics.drawable.Drawable drawable = visualImage.getDrawable();
                    if (drawable != null) {
                        android.graphics.Rect bounds = drawable.getBounds(); float[] matrix = new float[9];
                        visualImage.getImageMatrix().getValues(matrix);
                        android.os.Bundle geometry = new android.os.Bundle();
                        geometry.putIntArray("bitmapSize", new int[]{visualBitmap.getWidth(), visualBitmap.getHeight()});
                        geometry.putIntArray("viewOrigin", screen);
                        geometry.putIntArray("viewSize", new int[]{visualImage.getWidth(), visualImage.getHeight()});
                        geometry.putIntArray("padding", new int[]{visualImage.getPaddingLeft(), visualImage.getPaddingTop(),
                                visualImage.getPaddingRight(), visualImage.getPaddingBottom()});
                        geometry.putIntArray("drawableBounds", new int[]{bounds.left, bounds.top, bounds.right, bounds.bottom});
                        geometry.putFloatArray("imageMatrix", matrix);
                        geometry.putBoolean("attached", visualImage.isAttachedToWindow()); geometry.putBoolean("shown", visualImage.isShown());
                        geometry.putBoolean("windowFocus", visualImage.hasWindowFocus());
                        geometry.putFloat("viewAlpha", visualImage.getAlpha()); geometry.putFloat("rootAlpha", root.getAlpha());
                        geometry.putLong("drawCount", visualDrawCount);
                        geometry.putIntArray("viewScroll", new int[]{visualImage.getScrollX(), visualImage.getScrollY()});
                        info.getExtras().putBundle(VISUAL_GEOMETRY, geometry);
                    }
                    if (visualMotion != null) info.getExtras().putBundle(VISUAL_SCROLL, visualMotion.snapshot());
                }
            });
            if (intent.getBooleanExtra("positiveVisualScrollFixture", false)) {
                android.widget.LinearLayout content = new android.widget.LinearLayout(this);
                content.setOrientation(android.widget.LinearLayout.VERTICAL);
                visualCopies.add(visualImage);
                for (int index = 1; index < 5; index++) {
                    android.widget.ImageView image = new android.widget.ImageView(this);
                    image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    image.setImageBitmap(decoded);
                    image.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                    image.setContentDescription("Positive visual fixture copy");
                    visualCopies.add(image);
                }
                for (android.widget.ImageView image : visualCopies)
                    content.addView(image, new android.widget.LinearLayout.LayoutParams(-1, 1));
                visualScroll = new android.widget.ScrollView(this) {
                    @Override protected void onMeasure(int widthSpec, int heightSpec) {
                        int height = android.view.View.MeasureSpec.getSize(heightSpec);
                        if (height > 0) {
                            for (android.widget.ImageView image : visualCopies) {
                                android.view.ViewGroup.LayoutParams layout = image.getLayoutParams();
                                if (layout.height != height) { layout.height = height; image.setLayoutParams(layout); }
                            }
                            content.setMinimumHeight(5 * height);
                        }
                        super.onMeasure(widthSpec, heightSpec);
                    }
                };
                visualScroll.setId(android.R.id.list);
                visualScroll.setPadding(0, 0, 0, 0);
                visualScroll.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
                visualScroll.setVerticalScrollBarEnabled(false);
                visualScroll.addView(content, new android.widget.ScrollView.LayoutParams(-1, -2));
                visualMotion = new PositivePhotoScrollMotion(visualScroll);
                root.addView(visualScroll, new FrameLayout.LayoutParams(-1, -1));
            } else root.addView(visualImage, new FrameLayout.LayoutParams(-1, -1));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Positive fixture preparation failed", failure);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder value = new StringBuilder();
        for (byte item : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return value.toString();
    }

    @Override protected void onDestroy() {
        safety.removeCallbacks(safetyFinish);
        if (visualMotion != null) visualMotion.close();
        android.os.ResultReceiver acknowledgement = isFinishing() && visualStreamsClosed ? visualRetirementReceiver : null;
        String retiredUri = visualUri == null ? null : visualUri.toString();
        // Android owns the retired View/texture lifetime; never recycle pixels under a queued draw.
        if (visualImage != null) visualImage.setImageDrawable(null);
        for (android.widget.ImageView image : visualCopies) image.setImageDrawable(null);
        visualCopies.clear(); visualMotion = null; visualScroll = null;
        visualImage = null; visualBitmap = null; visualUri = null;
        super.onDestroy();
        if (acknowledgement != null && retiredUri != null) {
            android.os.Bundle data = new android.os.Bundle();
            data.putString("retiredUri", retiredUri); data.putString("inputSha256", POSITIVE_SHA); data.putBoolean("streamsClosed", true);
            acknowledgement.send(1, data);
        }
    }

    /** Ordinary TextViews: one actual scrolling caption and one fixed sibling with identical text. */
    private void createNativeScrollFixture() {
        nativeScrollFixture = true; root.removeAllViews();
        root.setId(android.R.id.custom);
        root.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        root.setAccessibilityDelegate(new android.view.View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(android.view.View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.getExtras().putLong(NATIVE_COMMAND, nativeCommand);
                info.getExtras().putInt(NATIVE_SCROLL_Y, scroll == null ? -1 : scroll.getScrollY());
                info.getExtras().putBoolean(NATIVE_ADDED, addedLabel != null);
                info.getExtras().putLong(NATIVE_MUTATION, nativeMutation);
            }
        });
        int width = getResources().getDisplayMetrics().widthPixels;
        FrameLayout content = new FrameLayout(this); content.setMinimumHeight(2600);
        label.setId(android.R.id.text1); label.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        FrameLayout.LayoutParams moving = new FrameLayout.LayoutParams(width / 3, -2);
        moving.leftMargin = 48; moving.topMargin = 650; content.addView(label, moving);
        scroll = new android.widget.ScrollView(this); scroll.setId(android.R.id.list);
        scroll.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER); scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new android.widget.ScrollView.LayoutParams(-1, 2600));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, 1200));
        fixedLabel = new TextView(this); fixedLabel.setId(android.R.id.text2);
        fixedLabel.setTextColor(0xff000000); fixedLabel.setTextSize(20);
        fixedLabel.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        FrameLayout.LayoutParams fixed = new FrameLayout.LayoutParams(width / 3, -2);
        fixed.leftMargin = width / 2; fixed.topMargin = 650; root.addView(fixedLabel, fixed);
    }

    /** Containment counterexample: fixed/local/second-owner pixels share the primary viewport. */
    private void createMotionFixture() {
        motionFixture = true; root.removeAllViews();
        root.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        root.setAccessibilityDelegate(new android.view.View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(android.view.View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.getExtras().putLong(MOTION_FRAME_COMMIT, motionFrameCommit);
            }
        });
        android.util.DisplayMetrics display = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(display);
        int width = display.widthPixels, box = Math.max(24, width / 8);
        FrameLayout content = new FrameLayout(this); content.setBackgroundColor(UNDERLYING);
        // ScrollView deliberately measures child height UNSPECIFIED; its requested LayoutParams
        // height alone does not establish a scroll range for a FrameLayout with small children.
        content.setMinimumHeight(2600);
        android.view.View body = fixtureBox(android.R.id.icon, "scroll-body", 0xffe72942);
        content.addView(body, boxParams(width / 12, 650, box));
        motionMedia = fixtureBox(android.R.id.summary, "local-media", 0xff2460df);
        content.addView(motionMedia, boxParams(width / 2, 650, box));
        scroll = new android.widget.ScrollView(this) {
            @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                // A declared provider-label fixture, not an actual X/AndroidX implementation.
                if (motionOwnerRecyclerLabel) info.setClassName("androidx.recyclerview.widget.RecyclerView");
            }
            @Override public void onInitializeAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
                super.onInitializeAccessibilityEvent(event);
                if (motionEventRecyclerLabel && event.getEventType() == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED)
                    event.setClassName("androidx.recyclerview.widget.RecyclerView");
                if (compoundMotionScroll && event.getEventType() == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                    // The framework event is mutable here, before its cross-process sealed delivery.
                    event.appendRecord(android.view.accessibility.AccessibilityRecord.obtain());
                    compoundMotionScroll = false;
                }
            }
        }; scroll.setId(android.R.id.list);
        scroll.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER); scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new android.widget.ScrollView.LayoutParams(-1, 2600));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, 1200));
        android.view.View fixed = fixtureBox(android.R.id.title, "fixed-overlay", 0xfff0c526);
        root.addView(fixed, boxParams(width / 3, 650, box));
        FrameLayout independentContent = new FrameLayout(this); independentContent.setBackgroundColor(0xff8225c9);
        independentContent.setMinimumHeight(600);
        android.view.View independentBody = fixtureBox(android.R.id.text1, "second-body", 0xff17c9a2);
        independentContent.addView(independentBody, boxParams(0, 40, box));
        independentScroll = new android.widget.ScrollView(this); independentScroll.setId(android.R.id.text2);
        independentScroll.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        independentScroll.setVerticalScrollBarEnabled(false);
        independentScroll.addView(independentContent, new android.widget.ScrollView.LayoutParams(-1, 600));
        FrameLayout.LayoutParams independent = new FrameLayout.LayoutParams(box, 200);
        independent.leftMargin = width * 3 / 4; independent.topMargin = 600;
        root.addView(independentScroll, independent);
    }
    private android.view.View fixtureBox(int id, String name, int color) {
        android.view.View view = new android.view.View(this); view.setId(id); view.setContentDescription(name);
        view.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        view.setBackgroundColor(color); return view;
    }
    private static FrameLayout.LayoutParams boxParams(int x, int y, int size) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size); params.leftMargin = x; params.topMargin = y;
        return params;
    }
}
