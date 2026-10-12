package com.subhub.app.penance;

import com.subhub.app.detection.BBox;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/** Read-only hit geometry from a visible draw; never detection, tracking, or billing authority. */
public final class CensorTapTracker {
    public static final long MAX_DISPLAY_AGE_MILLIS = 1500L;
    public static final long DISPLAY_REFRESH_MILLIS = 750L;
    private static final int MAX_REGIONS = 512;
    private static final CensorTapTracker SHARED = new CensorTapTracker();
    private List<BBox> boxes = List.of();
    private BBox viewport = new BBox(0, 0, 0, 0);
    private Object owner;
    private long scope;
    private long drawnAtUptimeMillis;
    private long geometryStartedAtUptimeMillis;
    private boolean suspended;

    /** One process-wide hit map shared by App Mode and Screen Capture. */
    public static CensorTapTracker shared() { return SHARED; }

    public synchronized long scope() { return scope; }

    /** Coordinates are screen pixels, clipped by the actual visible View, using uptime only. */
    public synchronized void publishDisplayed(Object publisher, long expectedScope,
            List<BBox> displayedRegions, BBox visibleViewport, long drawnAtUptime,
            boolean labOwnedSource) {
        if (expectedScope != scope || publisher == null) return;
        List<BBox> current = new ArrayList<>();
        if (!labOwnedSource && visibleViewport != null && displayedRegions != null
                && displayedRegions.size() <= MAX_REGIONS && drawnAtUptime > 0L) {
            for (BBox box : displayedRegions) {
                if (box == null) continue;
                int left = Math.max(box.getX(), visibleViewport.getX());
                int top = Math.max(box.getY(), visibleViewport.getY());
                int right = Math.min(box.getRight(), visibleViewport.getRight());
                int bottom = Math.min(box.getBottom(), visibleViewport.getBottom());
                if (right > left && bottom > top) {
                    current.add(new BBox(left, top, right - left, bottom - top));
                }
            }
        }
        BBox currentViewport = visibleViewport == null ? new BBox(0, 0, 0, 0) : visibleViewport;
        // A stationary redraw renews freshness, not the start time of the same displayed pixels.
        if (owner != publisher || !boxes.equals(current) || !viewport.equals(currentViewport)) {
            geometryStartedAtUptimeMillis = drawnAtUptime;
        }
        owner = publisher;
        boxes = List.copyOf(current);
        viewport = currentViewport;
        drawnAtUptimeMillis = drawnAtUptime;
        suspended = false;
    }

    public synchronized boolean matchesClick(
            int left, int top, int right, int bottom,
            int screenWidth, int screenHeight, long nowUptimeMillis) {
        return matchesClickAt(left, top, right, bottom, nowUptimeMillis, nowUptimeMillis);
    }

    /** A click source proves attribution only when its complete bounds are covered by the union. */
    public synchronized boolean matchesClickAt(int left, int top, int right, int bottom,
            long eventUptimeMillis, long nowUptimeMillis) {
        if (!fresh(nowUptimeMillis) || right <= left || bottom <= top
                || eventUptimeMillis < geometryStartedAtUptimeMillis
                || eventUptimeMillis > nowUptimeMillis
                || nowUptimeMillis - eventUptimeMillis > MAX_DISPLAY_AGE_MILLIS
                || left < viewport.getX() || top < viewport.getY()
                || right > viewport.getRight() || bottom > viewport.getBottom()) return false;
        TreeSet<Integer> edges = new TreeSet<>();
        edges.add(left);
        edges.add(right);
        for (BBox box : boxes) {
            if (box.getX() > left && box.getX() < right) edges.add(box.getX());
            if (box.getRight() > left && box.getRight() < right) edges.add(box.getRight());
        }
        Integer previous = null;
        for (int edge : edges) {
            if (previous != null && !coversVerticalSlice(previous, edge, top, bottom)) return false;
            previous = edge;
        }
        return true;
    }

    private boolean coversVerticalSlice(int left, int right, int top, int bottom) {
        List<BBox> slice = new ArrayList<>();
        for (BBox box : boxes) {
            if (box.getX() <= left && box.getRight() >= right
                    && box.getBottom() > top && box.getY() < bottom) slice.add(box);
        }
        slice.sort(Comparator.comparingInt(BBox::getY));
        int coveredTo = top;
        for (BBox box : slice) {
            if (box.getY() > coveredTo) return false;
            coveredTo = Math.max(coveredTo, box.getBottom());
            if (coveredTo >= bottom) return true;
        }
        return false;
    }

    /** Matches an actual screen touch against the censor regions currently visible to the user. */
    public synchronized boolean matchesPoint(
            float screenX, float screenY,
            int screenWidth, int screenHeight, long nowUptimeMillis) {
        if (!fresh(nowUptimeMillis) || !Float.isFinite(screenX) || !Float.isFinite(screenY)) return false;
        for (BBox box : boxes) {
            if (screenX >= box.getX() && screenX < box.getRight()
                    && screenY >= box.getY() && screenY < box.getBottom()) return true;
        }
        return false;
    }

    public synchronized void clear() {
        scope++;
        boxes = List.of();
        owner = null;
        drawnAtUptimeMillis = 0L;
        geometryStartedAtUptimeMillis = 0L;
        suspended = false;
    }

    /** An old/detached or exported View cannot invalidate another live overlay's map. */
    public synchronized void clearDisplayed(Object publisher) {
        if (owner == publisher) clear();
    }

    /**
     * Withdraw this publisher's visible geometry without changing capture/session provenance.
     * Used for a new empty/reverse/unsupported draw. A later publication starts new geometry time,
     * so delayed clicks cannot match the old map even when the next rectangles happen to be equal.
     * Lifecycle/session invalidation still uses clearDisplayed/clear and advances the scope.
     */
    public synchronized void withdrawDisplayed(Object publisher) {
        if (owner != publisher) return;
        boxes = List.of(); owner = null; drawnAtUptimeMillis = 0L; geometryStartedAtUptimeMillis = 0L; suspended = false;
    }

    /**
     * Before asynchronous native submission, no prior point is billable until current admission.
     * Identical conservative coverage retains its geometry start for delayed stationary clicks.
     * Changed/empty coverage withdraws that continuity even if its completion is later coalesced.
     * Supplied rectangles must already be clipped conservative interiors, not detection bounds.
     */
    public synchronized void suspendDisplayed(Object publisher, long expectedScope,
            List<BBox> nextInteriors, BBox nextViewport) {
        if (owner != publisher) return;
        if (scope != expectedScope || nextInteriors == null || nextInteriors.isEmpty()
                || !boxes.equals(nextInteriors) || !viewport.equals(nextViewport)) {
            withdrawDisplayed(publisher);
        } else suspended = true;
    }

    private boolean fresh(long nowUptimeMillis) {
        return !suspended && !boxes.isEmpty() && drawnAtUptimeMillis > 0L
                && nowUptimeMillis >= drawnAtUptimeMillis
                && nowUptimeMillis - drawnAtUptimeMillis <= MAX_DISPLAY_AGE_MILLIS;
    }
}
