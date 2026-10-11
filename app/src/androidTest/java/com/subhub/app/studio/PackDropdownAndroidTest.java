package com.subhub.app.studio;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.*;

import android.widget.Spinner;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.test.core.app.ActivityScenario;
import com.subhub.app.pack.PackSettingCatalog;
import com.subhub.app.pack.SubHubPackSchema;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class PackDropdownAndroidTest {
    @Test public void themedDropdownOpensSelectsAndSavesTheActualChoice() {
        AtomicReference<JSONObject> saved = new AtomicReference<>();
        try (ActivityScenario<StudioActivity> page = ActivityScenario.launch(StudioActivity.class)) {
            page.onActivity(a -> PackSectionEditor.show(a, SubHubPackSchema.CENSOR, "Censor",
                    PackSettingCatalog.defaults(SubHubPackSchema.CENSOR), saved::set));
            onView(withTagValue(is((Object) ("pack_group:censor:"
                    + PackSettingCatalog.field(SubHubPackSchema.CENSOR, "censor_type").group))))
                    .inRoot(isDialog()).perform(scrollTo(), click());
            onView(withTagValue(is((Object) "censor_type"))).inRoot(isDialog())
                    .check((view, error) -> {
                        assertNull(error);
                        assertTrue(view instanceof AppCompatSpinner);
                        assertNotNull(((Spinner) view).getPopupBackground());
                        assertNotNull(view.getBackground());
                    }).perform(scrollTo(), click());
            assertTrue(androidx.test.uiautomator.UiDevice.getInstance(
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
                    .takeScreenshot(new java.io.File(
                            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                                    .getTargetContext().getFilesDir(), "pack-dropdown-theme.png")));
            PackSettingCatalog.Field field = PackSettingCatalog.field(SubHubPackSchema.CENSOR, "censor_type");
            int position = field.choices.indexOf("blur");
            onData(instanceOf(String.class)).inRoot(isPlatformPopup()).atPosition(position).perform(click());
            onView(withTagValue(is((Object) "censor_type"))).inRoot(isDialog()).check((view, error) -> {
                assertNull(error);
                assertEquals(position, ((Spinner) view).getSelectedItemPosition());
            });
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
            assertNotNull(saved.get());
            assertEquals("blur", saved.get().optString("censor_type"));
        }
    }
}
