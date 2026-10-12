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
    public int boundPid;
    public AccessibilityCensorController controller;
    @Override protected void onServiceConnected() {
        super.onServiceConnected(); boundPid = Process.myPid(); current = this;
        Log.i("BoundCensorFixture", "BOUND pid=" + Process.myPid() + " sdk=" + Build.VERSION.SDK_INT);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (Build.VERSION.SDK_INT >= 30 && controller != null
                && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            controller.contentChanged(event.getWindowId());
        }
    }
    @Override public void onInterrupt() {
        if (Build.VERSION.SDK_INT >= 30 && controller != null) controller.stop();
    }
    @Override public boolean onUnbind(Intent intent) {
        if (Build.VERSION.SDK_INT >= 30 && controller != null) controller.close();
        current = null; return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        if (Build.VERSION.SDK_INT >= 30 && controller != null) controller.close();
        current = null; super.onDestroy();
    }
}
