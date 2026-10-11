package com.subhub.app.overlay;

import static org.junit.Assert.*;

import android.app.UiAutomation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.ObjectTracker;
import com.subhub.app.diagnostics.CensorLabCensorSession;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.FeatureModuleManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual native click sources, synthetic ledger preferences, no wallet credentials or payments. */
@RunWith(AndroidJUnit4.class)
public final class CensorTapTributeAndroidTest {
    @Test public void normalNativeClickChargesOnceEvenWhenTheSameEventIsRedelivered() throws Exception {
        withClick(new Rect(130, 190, 200, 240), session -> {
            AccessibilityEvent clicked = session.click();
            ScreenshotAccessibilityService service = session.service(clicked);
            try {
                service.onAccessibilityEvent(clicked);
                assertEquals(1, session.events());
                assertEquals(125, session.manager.snapshot(System.currentTimeMillis()).getEvents().get(0).getAmountCents());
                assertEquals(PenanceInfraction.CENSORED_TAP,
                        session.manager.snapshot(System.currentTimeMillis()).getEvents().get(0).getInfraction());
                AccessibilityEvent rapidSecond = session.click();
                try { service.onAccessibilityEvent(rapidSecond); } finally { rapidSecond.recycle(); }
                assertEquals("The existing 500ms gate must survive", 1, session.events());
                SystemClock.sleep(550L);
                service.onAccessibilityEvent(clicked);
                assertEquals("A delayed duplicate remains the same click", 1, session.events());
            } finally { clicked.recycle(); }
        });
    }

    @Test public void partialLikeOrReplyClickDoesNotCharge() throws Exception {
        withClick(new Rect(250, 190, 320, 240), session -> {
            AccessibilityEvent clicked = session.click();
            try { session.service(clicked).onAccessibilityEvent(clicked); }
            finally { clicked.recycle(); }
            assertEquals(0, session.events());
        });
    }

    @Test public void tributeRuleModuleArmedDomAndLabGatesRemainEffective() throws Exception {
        withClick(new Rect(130, 190, 200, 240), session -> {
            session.manager.disableInfraction(PenanceInfraction.CENSORED_TAP);
            session.deliverNewClick();
            assertEquals(0, session.events());
            session.configure();
            new FeatureModuleManager(session.context).setCensorEnabled(false);
            session.deliverNewClick();
            assertEquals(0, session.events());
            new FeatureModuleManager(session.context).setCensorEnabled(true);
            new AppModeManager(session.context).setArmed(false);
            session.deliverNewClick();
            assertEquals(0, session.events());
            new AppModeManager(session.context).setArmed(true);
            ControllerPinManager.enterDomMode();
            session.deliverNewClick();
            assertEquals(0, session.events());
            ControllerPinManager.enterSubMode();
            try (CensorLabCensorSession.Lease ignored = CensorLabCensorSession.acquire()) {
                session.deliverNewClick();
                assertEquals(0, session.events());
            }
        });
    }

    @Test public void wrongForegroundWrongWindowAndScrollSignalsDoNotCharge() throws Exception {
        withClick(new Rect(130, 190, 200, 240), session -> {
            AccessibilityEvent clicked = session.click();
            try {
                ScreenshotAccessibilityService wrongPackage = session.service(clicked);
                set(wrongPackage, "foregroundPackage", "fixture.unrelated");
                wrongPackage.onAccessibilityEvent(clicked);
                ScreenshotAccessibilityService wrongWindow = session.service(clicked);
                ((java.util.concurrent.atomic.AtomicInteger) get(wrongWindow, "activeApplicationWindowId"))
                        .set(clicked.getWindowId() + 1);
                wrongWindow.onAccessibilityEvent(clicked);
                assertEquals(0, session.events());
                Method record = ScreenshotAccessibilityService.class.getDeclaredMethod("recordCensoredTap",
                        AccessibilityEvent.class, String.class);
                record.setAccessible(true);
                AccessibilityEvent scroll = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED);
                try { record.invoke(session.service(clicked), scroll, session.context.getPackageName()); }
                finally { scroll.recycle(); }
                AccessibilityEvent missingSource = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
                missingSource.setPackageName(session.context.getPackageName());
                missingSource.setEventTime(SystemClock.uptimeMillis());
                try { record.invoke(session.service(clicked), missingSource, session.context.getPackageName()); }
                finally { missingSource.recycle(); }
                assertEquals(0, session.events());
            } finally { clicked.recycle(); }
        });
    }

    private static void withClick(Rect buttonBounds, Check check) throws Exception {
        // Bind the Accessibility observer before native views are created. The first performClick
        // can otherwise precede the target process's AccessibilityManager enabled callback.
        InstrumentationRegistry.getInstrumentation().getUiAutomation();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Context real = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ScopedPreferencesContext isolated = new ScopedPreferencesContext(real);
        boolean wasDom = ControllerPinManager.isDomModeActive();
        Field active = ScreenshotAccessibilityService.class.getDeclaredField("recognitionActive");
        active.setAccessible(true);
        boolean wasActive = active.getBoolean(null);
        CensorTapTracker.shared().clear();
        ControllerPinManager.enterSubMode();
        active.setBoolean(null, true);
        try (ActivityScenario<CensorTapFixtureActivity> scenario = ActivityScenario.launch(CensorTapFixtureActivity.class)) {
            AtomicReference<Button> button = new AtomicReference<>();
            scenario.onActivity(activity -> {
                FrameLayout parent = (FrameLayout) activity.findViewById(android.R.id.content);
                Button target = new Button(activity);
                target.setText("Fixture");
                target.setOnClickListener(view -> { });
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(buttonBounds.width(), buttonBounds.height());
                params.leftMargin = buttonBounds.left;
                params.topMargin = buttonBounds.top;
                parent.addView(target, params);
                button.set(target);
                CensorOverlayView censor = new CensorOverlayView(activity);
                censor.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                censor.setAppearance(new CensorAppearance(CensorAppearance.Type.BOX, 100, 0f,
                        false, false, CensorAppearance.BorderEffect.CLASSIC, false, Color.MAGENTA,
                        List.of(), false, 100, "rectangle", "SubHub", "Blocked"));
                censor.setTracks(new ObjectTracker(DetectorConfig.builder().build()).update(List.of(
                        new Detection("TEST", "face", 1f, new BBox(80, 120, 200, 200), true, false))),
                        320, 480, null);
                parent.addView(censor, new FrameLayout.LayoutParams(320, 480));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            SystemClock.sleep(150L);
            Session session = new Session(isolated, scenario, button.get());
            check.accept(session);
        } finally {
            isolated.restore();
            active.setBoolean(null, wasActive);
            if (wasDom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
            CensorTapTracker.shared().clear();
        }
    }

    private static final class Session {
        final ScopedPreferencesContext context;
        final PenanceManager manager;
        final ActivityScenario<CensorTapFixtureActivity> scenario;
        final Button button;
        Session(ScopedPreferencesContext context, ActivityScenario<CensorTapFixtureActivity> scenario, Button button) {
            this.context = context; this.scenario = scenario; this.button = button;
            manager = new PenanceManager(context);
            new FeatureModuleManager(context).setCensorEnabled(true);
            new AppModeManager(context).setArmed(true);
            assertFalse(context.getSharedPreferences("subhub_hardcore_auto_pay", Context.MODE_PRIVATE)
                    .getBoolean("enabled", false));
            configure();
        }
        void configure() { manager.configure(true, Map.of(PenanceInfraction.CENSORED_TAP, 125), 1000, 5000, 5, 15); }
        int events() { return manager.snapshot(System.currentTimeMillis()).getEvents().size(); }
        AccessibilityEvent click() throws Exception {
            UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
            AccessibilityEvent event = automation.executeAndWaitForEvent(
                    () -> scenario.onActivity(activity -> button.performClick()),
                    candidate -> candidate.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED
                            && context.getPackageName().contentEquals(candidate.getPackageName()), 3000L);
            AccessibilityNodeInfo source = event.getSource();
            assertNotNull("Use a real native click source, not invented coordinates", source);
            try { assertTrue(source.isVisibleToUser()); } finally { source.recycle(); }
            return event;
        }
        ScreenshotAccessibilityService service(AccessibilityEvent event) throws Exception {
            ScreenshotAccessibilityService service = new ScreenshotAccessibilityService();
            Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            attach.setAccessible(true);
            attach.invoke(service, context);
            set(service, "penance", manager);
            set(service, "foregroundPackage", context.getPackageName());
            ((java.util.concurrent.atomic.AtomicInteger) get(service, "activeApplicationWindowId")).set(event.getWindowId());
            return service;
        }
        void deliverNewClick() throws Exception {
            AccessibilityEvent event = click();
            try { service(event).onAccessibilityEvent(event); } finally { event.recycle(); }
        }
    }

    /** Every repository uses a fresh synthetic namespace; originals are never changed. */
    private static final class ScopedPreferencesContext extends ContextWrapper {
        final String prefix = "censor_tap_fixture_" + UUID.randomUUID() + '_';
        final Set<String> names = new HashSet<>();
        ScopedPreferencesContext(Context base) { super(base); }
        @Override public Context getApplicationContext() { return this; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            names.add(name);
            return super.getSharedPreferences(prefix + name, mode);
        }
        void restore() {
            for (String name : names) assertTrue(super.getSharedPreferences(prefix + name, Context.MODE_PRIVATE)
                    .edit().clear().commit());
        }
    }
    private static void set(Object instance, String fieldName, Object value) throws Exception {
        Field field = ScreenshotAccessibilityService.class.getDeclaredField(fieldName);
        field.setAccessible(true); field.set(instance, value);
    }
    private static Object get(Object instance, String fieldName) throws Exception {
        Field field = ScreenshotAccessibilityService.class.getDeclaredField(fieldName);
        field.setAccessible(true); return field.get(instance);
    }
    private interface Check { void accept(Session session) throws Exception; }
}
