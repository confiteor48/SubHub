package com.subhub.app;

import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.pack.*;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;

@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
public final class HomeDetailConfigurationAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private SharedPreferences prefs, auth;
    private Map<String, ?> before, authBefore;
    private SubHubPackManager packs;
    private String id;
    @Before public void prepare() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        prefs = new SettingsRepository(context).preferences();
        auth = context.getSharedPreferences("subhub_controller_auth", Context.MODE_PRIVATE);
        before = new java.util.LinkedHashMap<>(prefs.getAll()); authBefore = new java.util.LinkedHashMap<>(auth.getAll());
        prefs.edit().putBoolean(AppModeManager.KEY_ARMED, false).remove("commitment_ends_at").commit();
        packs = new SubHubPackManager(context);
        assertNull("Disposable fixture must have no active personal pack", packs.activePackId());
    }
    @After public void restore() {
        ControllerPinManager.enterDomMode();
        if (id != null) { assertTrue(packs.deactivate()); packs.deleteLibrary(id); }
        SharedPreferenceTestRestore.restore(prefs, before); SharedPreferenceTestRestore.restore(auth, authBefore);
        ControllerPinManager.enterSubMode();
    }
    @Test public void limitsListsDistinctInstalledAppAllowancesAndEmptySelection() throws Exception {
        String chrome = "com.android.chrome", settings = "com.android.settings";
        context.getPackageManager().getApplicationInfo(chrome, 0);
        context.getPackageManager().getApplicationInfo(settings, 0);
        new AppModeManager(context).saveIncludedPackages(Set.of(chrome, settings));
        AppTimerManager timers = new AppTimerManager(context);
        timers.saveSettings(true, 30, false, 120);
        timers.saveAllowances(Set.of(chrome, settings), Map.of(chrome, 35, settings, 75));
        try (ActivityScenario<MainActivity> scenario = launch()) {
            open(scenario, R.id.sub_limits_card);
            inspect("home-limits-allowances.png", text -> {
                assertTrue(text.contains("Chrome · 35 min"));
                assertTrue(text.contains("Settings · 75 min"));
            });
            onView(withId(R.id.arrangement_detail_close)).inRoot(isDialog()).perform(click());
            new AppModeManager(context).saveIncludedPackages(Set.of());
            open(scenario, R.id.sub_limits_card);
            inspect(null, text -> assertTrue(text.contains(context.getString(R.string.arrangement_no_apps))));
            onView(withId(R.id.arrangement_detail_close)).inRoot(isDialog()).perform(click());
        }
    }
    @Test public void ritualsShowsActivePackAndConfiguredMethodsWithoutSecrets() throws Exception {
        ControllerPinManager.enterDomMode();
        SubHubPack pack = packs.createBlank(); id = pack.getId();
        pack.setMetadata("Native Rituals pack", "Synthetic holder", "", "2.3.0");
        pack.setSection(SubHubPackSchema.CENSOR, PackSettingCatalog.defaults(SubHubPackSchema.CENSOR));
        packs.addToLibrary(pack); assertTrue(packs.activate(pack, Set.of(SubHubPackSchema.CENSOR)));
        auth.edit().putString("encrypted_secret", "SYNTHETIC_SECRET_DO_NOT_SHOW").commit();
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<MainActivity> scenario = launch()) {
            open(scenario, R.id.sub_atmosphere_card);
            inspect("home-rituals-config.png", text -> {
                assertTrue(text.contains("Native Rituals pack · v2.3.0"));
                assertTrue(text.contains(context.getString(R.string.keyholder_status_both)));
                assertFalse(text.contains("SYNTHETIC_SECRET_DO_NOT_SHOW"));
            });
            onView(withId(R.id.arrangement_detail_close)).inRoot(isDialog()).perform(click());
            ControllerPinManager.enterDomMode(); assertTrue(packs.deactivate());
            open(scenario, R.id.sub_atmosphere_card);
            inspect(null, text -> assertTrue(text.contains(context.getString(R.string.arrangement_none_selected))));
            onView(withId(R.id.arrangement_detail_close)).inRoot(isDialog()).perform(click());
        }
    }
    private ActivityScenario<MainActivity> launch() {
        return ActivityScenario.launch(new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN));
    }
    private static void open(ActivityScenario<MainActivity> scenario, int card) throws Exception {
        onView(withId(card)).perform(revealAboveNavigation(), click());
        long end = android.os.SystemClock.uptimeMillis() + 10000;
        AtomicBoolean pending = new AtomicBoolean(true);
        while (android.os.SystemClock.uptimeMillis() < end) {
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Field field = MainActivity.class.getDeclaredField("uiData"); field.setAccessible(true);
                    pending.set(((com.subhub.app.util.AsyncUiScope) field.get(activity)).isPending("arrangement-details"));
                } catch (ReflectiveOperationException failed) { throw new AssertionError(failed); }
            });
            if (!pending.get()) return;
            android.os.SystemClock.sleep(25);
        }
        fail("Detail rows did not load");
    }
    private void inspect(String file, java.util.function.Consumer<String> check) {
        awaitExpandedFrame();
        onView(withId(R.id.arrangement_detail_close)).inRoot(isDialog()).check((close, missing) -> {
            if (missing != null) throw missing;
            View root = close.getRootView(); check.accept(text(root));
            Rect body = new Rect(), button = new Rect();
            assertTrue(root.findViewById(R.id.arrangement_detail_body).getGlobalVisibleRect(body));
            assertTrue(close.getGlobalVisibleRect(button)); assertTrue(body.bottom <= button.top);
            Rect window = new Rect(); root.getWindowVisibleDisplayFrame(window);
            int[] position = new int[2]; close.getLocationOnScreen(position);
            assertTrue("Close fits the physical visible window", window.contains(position[0], position[1])
                    && window.contains(position[0] + close.getWidth() - 1,
                            position[1] + close.getHeight() - 1));
        });
        if (file != null) {
            Bitmap bitmap = renderedCloseScreenshot(file);
            try (FileOutputStream out = new FileOutputStream(new File(context.getExternalFilesDir(null), file))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
            } catch (java.io.IOException failure) { throw new AssertionError(failure); }
            finally { bitmap.recycle(); }
        }
    }
    private Bitmap renderedCloseScreenshot(String file) {
        java.util.concurrent.atomic.AtomicReference<Rect> bounds = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger color = new java.util.concurrent.atomic.AtomicInteger();
        onView(withId(R.id.arrangement_detail_close)).inRoot(isDialog()).check((close, missing) -> {
            if (missing != null) throw missing;
            int[] point = new int[2]; close.getLocationOnScreen(point);
            bounds.set(new Rect(point[0], point[1], point[0] + close.getWidth(), point[1] + close.getHeight()));
            color.set(((TextView) close).getCurrentTextColor());
        });
        long end = android.os.SystemClock.uptimeMillis() + 5000;
        boolean first = true;
        while (android.os.SystemClock.uptimeMillis() < end) {
            Bitmap frame = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull("Native compositor screenshot available", frame);
            Rect label = new Rect(bounds.get());
            label.inset(label.width() / 3, label.height() / 4);
            int matched = 0, expected = color.get();
            if (label.left >= 0 && label.top >= 0 && label.right <= frame.getWidth() && label.bottom <= frame.getHeight()) {
                for (int y = label.top; y < label.bottom; y++) for (int x = label.left; x < label.right; x++) {
                    int pixel = frame.getPixel(x, y);
                    if (Math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(expected)) < 24
                            && Math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(expected)) < 24
                            && Math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(expected)) < 24) matched++;
                }
            }
            if (matched > 40) return frame;
            if (first) {
                try (FileOutputStream out = new FileOutputStream(new File(context.getExternalFilesDir(null),
                        file.replace(".png", "-pending.png")))) { frame.compress(Bitmap.CompressFormat.PNG, 100, out); }
                catch (java.io.IOException failure) { throw new AssertionError(failure); }
                first = false;
            }
            frame.recycle();
            // A hardware frame callback alone does not establish compositor visibility.
            awaitExpandedFrame();
        }
        throw new AssertionError("Close label absent from compositor at " + bounds.get());
    }
    private static void awaitExpandedFrame() {
        AtomicBoolean expanded = new AtomicBoolean();
        long end = android.os.SystemClock.uptimeMillis() + 5000;
        while (android.os.SystemClock.uptimeMillis() < end) {
            onView(withId(R.id.arrangement_detail_close)).inRoot(isDialog()).check((close, missing) -> {
                if (missing != null) throw missing;
                View sheet = close.getRootView().findViewById(com.google.android.material.R.id.design_bottom_sheet);
                expanded.set(com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet).getState()
                        == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
            });
            if (expanded.get()) break;
            android.os.SystemClock.sleep(25);
        }
        assertTrue("Sheet reached expanded state", expanded.get());
        java.util.concurrent.CountDownLatch frame = new java.util.concurrent.CountDownLatch(1);
        onView(withId(R.id.arrangement_detail_close)).inRoot(isDialog()).check((close, missing) -> {
            if (missing != null) throw missing;
            View root = close.getRootView(); assertTrue(root.isHardwareAccelerated());
            root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown); root.invalidate();
        });
        try { assertTrue("Expanded sheet committed a native frame", frame.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static String text(View view) {
        StringBuilder result = new StringBuilder();
        if (view instanceof TextView) result.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            result.append(text(((ViewGroup) view).getChildAt(i)));
        return result.toString();
    }
}
