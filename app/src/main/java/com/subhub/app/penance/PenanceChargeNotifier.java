package com.subhub.app.penance;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.subhub.app.R;
import java.util.function.BooleanSupplier;

/** Shows a compact confirmation only after a real ledger entry has been created. */
public final class PenanceChargeNotifier {
    private static Toast activeToast;

    private PenanceChargeNotifier() {}

    public static void show(Context context, PenanceManager manager,
            PenanceInfraction infraction, int amountCents, long nowMillis) {
        show(context, manager, infraction, amountCents, nowMillis, () -> true);
    }

    /** Recheck source eligibility on actual UI delivery, not only while preparing the message. */
    public static void show(Context context, PenanceManager manager,
            PenanceInfraction infraction, int amountCents, long nowMillis, BooleanSupplier stillEligible) {
        if (context == null || manager == null || infraction == null || amountCents <= 0) return;
        PenanceSnapshot snapshot = manager.snapshot(nowMillis);
        int unsettledCents = snapshot.getDueCents()
                + snapshot.getMercyCents()
                + snapshot.getCheckoutCents();
        String message = context.getString(R.string.penance_charge_toast,
                label(context, infraction), manager.money(amountCents),
                manager.money(unsettledCents));
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!stillEligible.getAsBoolean()) return;
            if (activeToast != null) activeToast.cancel();
            activeToast = Toast.makeText(app, message, Toast.LENGTH_LONG);
            activeToast.show();
        });
    }

    private static String label(Context context, PenanceInfraction infraction) {
        if (infraction == PenanceInfraction.CENSORED_DWELL) {
            return context.getString(R.string.penance_history_dwell);
        }
        if (infraction == PenanceInfraction.CENSORED_TAP) {
            return context.getString(R.string.penance_history_tap);
        }
        if (infraction == PenanceInfraction.WATCHED_APP_OPEN) {
            return context.getString(R.string.penance_history_app_open);
        }
        if (infraction == PenanceInfraction.TAMPER_ATTEMPT) {
            return context.getString(R.string.penance_history_tamper);
        }
        return context.getString(R.string.penance_history_detection);
    }
}
