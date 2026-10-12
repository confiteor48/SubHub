package com.subhub.app.censor.android;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

/** Synthetic source owned by the separate test application process, not the held controller main. */
public final class RemoteBoundSourceActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setWindowAnimations(0); overridePendingTransition(0, 0);
        View content = new View(this);
        content.setBackgroundColor(0xff18ac58);
        setContentView(content);
        // Own this synthetic window's bounded lifetime; no shell kill or injected input is needed.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::finish, 15000);
    }
}
