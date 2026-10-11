package com.subhub.app.penance;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.io.File;
import java.io.FileOutputStream;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Synthetic local ledger only; no provider, service or checkout actions. */
public final class WalletHistoryGroupingAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Map<SharedPreferences, Map<String, ?>> before = new LinkedHashMap<>();
    private List<PenanceEvent> events;

    @Before public void fixture() {
        assertTrue("Use the owned review emulator", android.os.Build.MODEL.contains("sdk_gphone"));
        for (String name : new String[]{SettingsRepository.PREFERENCES_NAME, PenanceManager.PREFS_NAME,
                PayPalCredentialStore.PREFS_NAME, "subhub_onboarding"}) {
            SharedPreferences prefs = context.getSharedPreferences(name, 0);
            before.put(prefs, prefs.getAll());
        }
        context.getSharedPreferences(PenanceManager.PREFS_NAME, 0).edit().clear().commit();
        OnboardingState.complete(context);
        ControllerPinManager.setPin(context, "2468");
        ControllerPinManager.enterSubMode();
        new AppModeManager(context).setArmed(false);
        new PayPalCredentialStore(context).clear();
        new HardcoreAutoPayManager(context).disable();
        new PenanceManager(context).configure(false, Map.of(), 5000, 20000, 0, 30);
        events = new ArrayList<>();
        long time = ZonedDateTime.now().withHour(12).withMinute(30).withSecond(0)
                .withNano(0).toInstant().toEpochMilli();
        for (int index = 0; index < 7; index++) {
            PenanceInfraction kind = index == 3 || index == 4
                    ? PenanceInfraction.WATCHED_APP_OPEN : PenanceInfraction.NEW_DETECTION;
            events.add(new PenanceEvent("fixture-" + index, time - index * 1000L, 0,
                    kind == PenanceInfraction.WATCHED_APP_OPEN ? 50 : 100, 1, kind,
                    index == 6 ? PenanceEvent.Status.PAID : PenanceEvent.Status.OPEN, "", "EUR"));
        }
        save(events);
    }

    @After public void restore() {
        for (Map.Entry<SharedPreferences, Map<String, ?>> entry : before.entrySet())
            SharedPreferenceTestRestore.restore(entry.getKey(), entry.getValue());
        ControllerPinManager.enterSubMode();
    }

    @Test public void actualWalletCountsWholeRunsBeforeLimitingAndPreservesTotals() throws Exception {
        try (ActivityScenario<PenanceActivity> page = ActivityScenario.launch(PenanceActivity.class)) {
            settle();
            page.onActivity(activity -> {
                WalletHistoryView history = activity.findViewById(R.id.wallet_history_list);
                assertEquals(2, rows(history).size());
                assertEquals("New censor 3x", title(rows(history).get(0)));
                assertEquals("€3.00", amount(rows(history).get(0)));
                assertEquals("Included app opened 2x", title(rows(history).get(1)));
                assertEquals("€1.00", amount(rows(history).get(1)));
                assertTrue(activity.findViewById(R.id.wallet_history_more).isShown());
                activity.findViewById(R.id.wallet_history_more).performClick();
            });
            settle();
            page.onActivity(activity -> {
                WalletHistoryView history = activity.findViewById(R.id.wallet_history_list);
                assertEquals(4, rows(history).size());
                assertEquals("New censor", title(rows(history).get(2)));
                assertEquals("New censor", title(rows(history).get(3)));
                checkText(history);
                capture(activity);
            });
            PenanceSnapshot state = new PenanceManager(context).snapshot(System.currentTimeMillis());
            assertEquals(7, state.getEvents().size());
            assertEquals(500, state.getDueCents());
            assertEquals(100, state.getPaidCents());
        }
    }

    @Test public void updatingACachedRunRebuildsCountsAndOneRunNeedsNoExpandButton() {
        try (ActivityScenario<PenanceActivity> page = ActivityScenario.launch(PenanceActivity.class)) {
            settle();
            List<PenanceEvent> oneRun = new ArrayList<>();
            for (PenanceEvent event : events) oneRun.add(new PenanceEvent(event.getId(),
                    event.getCreatedAtMillis(), 0, 100, 1, PenanceInfraction.NEW_DETECTION,
                    PenanceEvent.Status.OPEN, "", "EUR"));
            page.onActivity(activity -> {
                WalletHistoryView history = activity.findViewById(R.id.wallet_history_list);
                assertEquals(1, history.bind(oneRun, System.currentTimeMillis(), 2));
                assertEquals(1, rows(history).size());
                assertEquals("New censor 7x", title(rows(history).get(0)));
                assertEquals("€7.00", amount(rows(history).get(0)));
            });
            assertEquals(7, new PenanceManager(context).snapshot(System.currentTimeMillis()).getEvents().size());
            save(oneRun);
            page.recreate();
            settle();
            page.onActivity(activity -> {
                assertFalse(activity.findViewById(R.id.wallet_history_more).isShown());
                assertEquals("New censor 7x", title(rows((WalletHistoryView)
                        activity.findViewById(R.id.wallet_history_list)).get(0)));
            });
        }
    }

    private void save(List<PenanceEvent> ledger) {
        List<String> encoded = new ArrayList<>();
        for (PenanceEvent event : ledger) encoded.add(String.join(",", event.getId(),
                Long.toString(event.getCreatedAtMillis()), Long.toString(event.getMercyEndsAtMillis()),
                Integer.toString(event.getAmountCents()), Integer.toString(event.getStrikeCount()),
                event.getStatus().name(), event.getSettlementId(), event.getInfraction().name(), event.getCurrency()));
        assertTrue(context.getSharedPreferences(PenanceManager.PREFS_NAME, 0).edit()
                .putString("events_v1", String.join(";", encoded)).commit());
    }

    private static List<LinearLayout> rows(WalletHistoryView history) {
        List<LinearLayout> rows = new ArrayList<>();
        for (int index = 0; index < history.getChildCount(); index++)
            if (history.getChildAt(index) instanceof LinearLayout) rows.add((LinearLayout) history.getChildAt(index));
        return rows;
    }
    private static String title(LinearLayout row) { return ((TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(0)).getText().toString(); }
    private static String amount(LinearLayout row) { return ((TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1)).getText().toString(); }
    private static void checkText(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view; Layout layout = text.getLayout();
            assertNotNull(layout);
            for (int line = 0; line < layout.getLineCount(); line++) assertTrue(layout.getLineMax(line)
                    <= text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight() + 1);
            assertTrue(layout.getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom() + 1);
        }
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) checkText(((ViewGroup) view).getChildAt(index));
    }
    private static void settle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); SystemClock.sleep(700); }
    private static void capture(PenanceActivity activity) {
        View root = activity.getWindow().getDecorView();
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        String profile = activity.getResources().getConfiguration().screenWidthDp + "-" + activity.getResources().getConfiguration().fontScale;
        try (FileOutputStream output = new FileOutputStream(new File(activity.getFilesDir(), "wallet-grouped-" + profile + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        finally { bitmap.recycle(); }
    }
}
