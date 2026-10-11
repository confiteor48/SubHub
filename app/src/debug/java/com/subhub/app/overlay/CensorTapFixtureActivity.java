package com.subhub.app.overlay;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.FrameLayout;

/** Empty, debug-only surface for native tap attribution fixtures. */
public final class CensorTapFixtureActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // App window transitions move/fade pixels independently of this fixture's View geometry.
        getWindow().setWindowAnimations(0);
        overridePendingTransition(0, 0);
        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(Color.WHITE);
        setContentView(content);
    }
}
