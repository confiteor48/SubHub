package com.subhub.app.detection;

/** Mutable temporal state for one censored object. */
public final class TrackedObject {
    private final int id;
    private final String category;
    private final String className;
    private BBox box;
    private BBox rawBox;
    private float confidence;
    private long lastSeenNanos;
    private int framesTracked;
    private int framesMissing;
    /** Screen-space velocity in pixels per millisecond. */
    private float velocityX;
    private float velocityY;
    private boolean active = true;
    private boolean visible;
    private boolean confirmed;
    private BBox predictionOriginBox;
    private String anchorKey;
    private Detection.ObservationSource observationSource;
    private boolean qualityOnly;
    /** Provenance of rawBox, carried opaquely without affecting tracker decisions. */
    private RenderSourceReference renderSourceReference;

    TrackedObject(int id, Detection detection, long nowNanos) {
        this(id, detection, nowNanos, true);
    }

    TrackedObject(int id, Detection detection, long nowNanos, boolean visibleImmediately) {
        this.id = id;
        category = detection.getCategory();
        className = detection.getClassName();
        box = detection.getBox();
        rawBox = detection.getBox();
        renderSourceReference = detection.getRenderSourceReference();
        predictionOriginBox = box;
        confidence = detection.getConfidence();
        lastSeenNanos = nowNanos;
        framesTracked = 1;
        visible = visibleImmediately;
        anchorKey = detection.getAnchorKey();
        observationSource = detection.getSource();
        qualityOnly = observationSource == Detection.ObservationSource.QUALITY_VISUAL;
    }

    private TrackedObject(TrackedObject source) {
        id = source.id;
        category = source.category;
        className = source.className;
        box = source.box;
        rawBox = source.rawBox;
        renderSourceReference = source.renderSourceReference;
        confidence = source.confidence;
        lastSeenNanos = source.lastSeenNanos;
        framesTracked = source.framesTracked;
        framesMissing = source.framesMissing;
        velocityX = source.velocityX;
        velocityY = source.velocityY;
        active = source.active;
        visible = source.visible;
        confirmed = source.confirmed;
        predictionOriginBox = source.predictionOriginBox;
        anchorKey = source.anchorKey;
        observationSource = source.observationSource;
        qualityOnly = source.qualityOnly;
    }

    public int getId() { return id; }
    public String getCategory() { return category; }
    public String getClassName() { return className; }
    public BBox getBox() { return box; }
    public BBox getRawBox() { return rawBox; }
    public RenderSourceReference getRenderSourceReference() { return renderSourceReference; }
    public float getConfidence() { return confidence; }
    public long getLastSeenNanos() { return lastSeenNanos; }
    public int getFramesTracked() { return framesTracked; }
    public int getFramesMissing() { return framesMissing; }
    public float getVelocityX() { return velocityX; }
    public float getVelocityY() { return velocityY; }
    public boolean isActive() { return active; }
    public boolean isVisible() { return visible; }
    public boolean isConfirmed() { return confirmed; }
    public String getAnchorKey() { return anchorKey; }
    public Detection.ObservationSource getObservationSource() { return observationSource; }
    public boolean isQualityOnly() { return qualityOnly; }

    /** Immutable-by-convention renderer handoff detached from subsequent tracker mutation. */
    public TrackedObject snapshot() { return new TrackedObject(this); }

    /** Detached identity evidence in another viewport; never moves the owned tracker. */
    public TrackedObject snapshotOffsetToViewport(int dx, int dy, int width, int height) {
        TrackedObject projected = new TrackedObject(this);
        projected.offset(dx, dy, width, height);
        return projected;
    }

    /**
     * Preserves this stable identity while presenting geometry from a freshly reacquired track.
     * Used only by render arbitration; tracker state and detection bookkeeping remain untouched.
     */
    TrackedObject renderSnapshotWithGeometryFrom(TrackedObject geometrySource) {
        TrackedObject rendered = new TrackedObject(this);
        if (geometrySource == null) return rendered;
        rendered.box = geometrySource.box;
        rendered.rawBox = geometrySource.rawBox;
        rendered.renderSourceReference = geometrySource.renderSourceReference;
        rendered.predictionOriginBox = geometrySource.predictionOriginBox;
        rendered.confidence = Math.max(confidence, geometrySource.confidence);
        rendered.lastSeenNanos = geometrySource.lastSeenNanos;
        rendered.framesMissing = 0;
        rendered.velocityX = geometrySource.velocityX;
        rendered.velocityY = geometrySource.velocityY;
        rendered.visible = true;
        rendered.active = true;
        if (geometrySource.anchorKey != null) rendered.anchorKey = geometrySource.anchorKey;
        if (geometrySource.observationSource != null) {
            rendered.observationSource = geometrySource.observationSource;
        }
        rendered.qualityOnly = qualityOnly && geometrySource.qualityOnly;
        return rendered;
    }

    void update(Detection detection, BBox renderedBox, float dx, float dy, long nowNanos) {
        rawBox = detection.getBox();
        renderSourceReference = detection.getRenderSourceReference();
        box = renderedBox;
        predictionOriginBox = renderedBox;
        confidence = detection.getConfidence();
        lastSeenNanos = nowNanos;
        framesTracked++;
        framesMissing = 0;
        active = true;
        visible = true;
        velocityX = dx;
        velocityY = dy;
        if (detection.getAnchorKey() != null) anchorKey = detection.getAnchorKey();
        observationSource = detection.getSource();
        if (observationSource != Detection.ObservationSource.QUALITY_VISUAL) qualityOnly = false;
        if (framesTracked >= 5) confirmed = true;
    }

    void miss(BBox predicted) {
        framesMissing++;
        if (predicted != null) box = predicted;
    }

    BBox predict(long nowNanos, float maxExtrapolationMs) {
        if (!active || maxExtrapolationMs <= 0f) return box;
        float elapsedMs = Math.max(0f, (nowNanos - lastSeenNanos) / 1_000_000f);
        float predictionMs = Math.min(elapsedMs, maxExtrapolationMs);
        return new BBox(
                Math.max(0, Math.round(predictionOriginBox.getX() + velocityX * predictionMs)),
                Math.max(0, Math.round(predictionOriginBox.getY() + velocityY * predictionMs)),
                predictionOriginBox.getWidth(),
                predictionOriginBox.getHeight());
    }

    void offset(int dx, int dy, int frameWidth, int frameHeight) {
        box = shifted(box, dx, dy, frameWidth, frameHeight);
        rawBox = shifted(rawBox, dx, dy, frameWidth, frameHeight);
        predictionOriginBox = shifted(predictionOriginBox, dx, dy, frameWidth, frameHeight);
        if (box.getWidth() == 0 || box.getHeight() == 0) active = false;
    }

    private static BBox shifted(BBox value, int dx, int dy, int width, int height) {
        int left = (int) Math.max(0L, Math.min(width, (long) value.getX() + dx));
        int top = (int) Math.max(0L, Math.min(height, (long) value.getY() + dy));
        int right = (int) Math.max(0L, Math.min(width, (long) value.getRight() + dx));
        int bottom = (int) Math.max(0L, Math.min(height, (long) value.getBottom() + dy));
        return new BBox(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    void deactivate() {
        active = false;
        visible = false;
    }
}
