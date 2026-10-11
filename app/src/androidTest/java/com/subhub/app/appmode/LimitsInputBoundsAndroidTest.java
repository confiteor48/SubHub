package com.subhub.app.appmode;

import static org.junit.Assert.*;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.subhub.app.R;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class LimitsInputBoundsAndroidTest {
    @Test public void dailyMinutesPillRemainsInsideItsRow() {
        try (ActivityScenario<AppModeActivity> page = ActivityScenario.launch(AppModeActivity.class)) {
            page.onActivity(activity -> {
                EditText input = activity.findViewById(R.id.total_limit_minutes);
                View row = (View) input.getParent();
                assertTrue(input.getTop() >= row.getPaddingTop());
                assertTrue("Entire input including its bottom outline fits the row",
                        input.getBottom() <= row.getHeight() - row.getPaddingBottom());
                Rect visible = new Rect();
                assertTrue(input.getLocalVisibleRect(visible));
                assertEquals(input.getHeight(), visible.height());
                View card = activity.findViewById(R.id.limits_combined_card);
                Bitmap image = Bitmap.createBitmap(card.getWidth(), card.getHeight(), Bitmap.Config.ARGB_8888);
                card.draw(new Canvas(image));
                try (FileOutputStream output = new FileOutputStream(new File(activity.getFilesDir(), "limits-input.png"))) {
                    assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
                } catch (Exception error) { throw new AssertionError(error); }
                finally { image.recycle(); }
            });
        }
    }
}
