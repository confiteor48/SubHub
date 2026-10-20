package com.subhub.app.censor.android;

import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.view.Choreographer;
import android.widget.ScrollView;
import java.util.Arrays;

/** Target-owned ordinary scrolling. No engine event injection or sampling-pose pauses. */
final class PositivePhotoScrollMotion implements Choreographer.FrameCallback {
    static final long DURATION_MILLIS = 24000;
    private static final int CAPACITY = 4096;
    private final ScrollView view;
    private final long[] entries = new long[CAPACITY], returns = new long[CAPACITY], supplied = new long[CAPACITY];
    private final int[] positions = new int[CAPACITY];
    private long started = -1, ended = -1, travel;
    private int count, max, height, reversals, lastDirection;
    private boolean closed, overflow;

    PositivePhotoScrollMotion(ScrollView view) { this.view = view; }

    void start() {
        if (started >= 0 || closed) throw new IllegalStateException("One motion command only");
        height = view.getHeight(); max = view.getChildAt(0).getHeight() - height;
        if (height <= 0 || max != 4 * height || view.getScrollY() != 0)
            throw new IllegalStateException("Actual five-viewport scroll geometry required");
        started = SystemClock.uptimeMillis();
        Choreographer.getInstance().postFrameCallback(this);
    }

    static int position(long elapsed, int maximum) {
        if (elapsed < 0 || maximum <= 0) throw new IllegalArgumentException("Motion geometry/clock");
        if (elapsed >= DURATION_MILLIS) return 0;
        long half = elapsed / 3000, within = elapsed % 3000;
        double progress = within / 3000.0;
        return (int) Math.round(maximum * (half % 2 == 0 ? progress : 1 - progress));
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (closed || ended >= 0) return;
        long entry = SystemClock.uptimeMillis();
        if (count == CAPACITY) { overflow = true; ended = entry; return; }
        int before = view.getScrollY();
        view.scrollTo(0, position(entry - started, max));
        int actual = view.getScrollY(), direction = Integer.compare(actual, before);
        if (direction != 0) {
            if (lastDirection != 0 && direction != lastDirection) reversals++;
            lastDirection = direction;
        }
        travel += Math.abs((long) actual - before);
        entries[count] = entry; supplied[count] = frameTimeNanos; positions[count] = actual;
        returns[count] = SystemClock.uptimeMillis(); count++;
        if (entry - started >= DURATION_MILLIS) ended = SystemClock.uptimeMillis();
        else Choreographer.getInstance().postFrameCallback(this);
    }

    Bundle snapshot() {
        Bundle value = new Bundle();
        value.putInt("pid", Process.myPid()); value.putLong("startedAtMillis", started);
        value.putLong("endedAtMillis", ended); value.putLong("observedAtMillis", SystemClock.uptimeMillis());
        value.putInt("scrollY", view.getScrollY()); value.putInt("maximum", max); value.putInt("viewportHeight", height);
        value.putInt("frameCount", count); value.putInt("reversals", reversals); value.putLong("travel", travel);
        value.putBoolean("overflow", overflow); value.putBoolean("closed", closed);
        if (ended >= 0) {
            value.putLongArray("callbackEntries", Arrays.copyOf(entries, count));
            value.putLongArray("callbackReturns", Arrays.copyOf(returns, count));
            value.putLongArray("suppliedFrameNanos", Arrays.copyOf(supplied, count));
            value.putIntArray("positions", Arrays.copyOf(positions, count));
        }
        return value;
    }

    void close() { closed = true; Choreographer.getInstance().removeFrameCallback(this); }
}
