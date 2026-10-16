package com.subhub.app.censor.android;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/** Disabled-by-default actual service; never included or activated by the release source set. */
public final class BoundCensorFixtureService extends AccessibilityService {
    public static volatile BoundCensorFixtureService current;
    static final FixtureServiceConnection<BoundCensorFixtureService> CONNECTIONS =
            new FixtureServiceConnection<>(service -> current = service);
    FixtureServiceConnection.Lease<BoundCensorFixtureService> connection;
    public int boundPid;
    public AccessibilityCensorController controller;
    public final java.util.concurrent.atomic.AtomicInteger scrollCallbacks = new java.util.concurrent.atomic.AtomicInteger();
    @Override protected void onServiceConnected() {
        // Framework lifecycle callbacks identify this object, not a protocol generation.
        if (connection != null) throw new IllegalStateException("Fixture service instance reconnection is unsupported");
        super.onServiceConnected(); boundPid = Process.myPid();
        synchronized (CONNECTIONS) { connection = CONNECTIONS.connected(this); }
        Log.i("BoundCensorFixture", "BOUND pid=" + Process.myPid() + " sdk=" + Build.VERSION.SDK_INT);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (Build.VERSION.SDK_INT >= 30 && controller != null
                && event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            scrollCallbacks.incrementAndGet(); controller.scrolled(event); return;
        }
        if (Build.VERSION.SDK_INT >= 30 && controller != null
                && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            controller.contentChanged(event.getWindowId());
        }
    }
    @Override public void onInterrupt() {
        if (Build.VERSION.SDK_INT >= 30 && controller != null) controller.stop();
    }
    @Override public boolean onUnbind(Intent intent) {
        if (connection == null) return super.onUnbind(intent);
        boolean[] result = {false};
        CONNECTIONS.retire(connection, this::closeController, () -> result[0] = super.onUnbind(intent));
        return result[0];
    }
    @Override public void onDestroy() {
        if (connection == null) { super.onDestroy(); return; }
        CONNECTIONS.retire(connection, this::closeController, () -> super.onDestroy());
    }
    private void closeController() { if (Build.VERSION.SDK_INT >= 30 && controller != null) controller.close(); }
}
