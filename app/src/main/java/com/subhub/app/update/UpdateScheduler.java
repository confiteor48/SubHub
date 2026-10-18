package com.subhub.app.update;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Owns the single periodic and foreground-stale update checks. */
public final class UpdateScheduler {
    public static final long INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(6);
    static final long DEV_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(15);
    static final String PERIODIC_NAME = "subhub-release-check";
    static final String IMMEDIATE_NAME = "subhub-release-check-now";

    private UpdateScheduler() {}

    public static void synchronize(Context context) {
        synchronize(context, true);
    }

    /** A channel toggle already performs its own foreground check. */
    static void synchronize(Context context, boolean checkIfStale) {
        UpdateStateStore state = new UpdateStateStore(context);
        WorkManager manager = WorkManager.getInstance(context);
        if (!state.automaticChecks()) {
            manager.cancelUniqueWork(PERIODIC_NAME);
            return;
        }
        Constraints network = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                UpdateCheckWorker.class, intervalMillis(state.devUpdates()), TimeUnit.MILLISECONDS)
                .setConstraints(network).build();
        manager.enqueueUniquePeriodicWork(PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE, periodic);
        if (checkIfStale && checkDue(System.currentTimeMillis(), state.lastCheck(), state.devUpdates())) {
            checkNow(context);
        }
    }

    static long intervalMillis(boolean developmentChannel) {
        return developmentChannel ? DEV_INTERVAL_MILLIS : INTERVAL_MILLIS;
    }

    static boolean checkDue(long nowMillis, long lastCheckMillis, boolean developmentChannel) {
        return nowMillis - lastCheckMillis >= intervalMillis(developmentChannel);
    }

    public static void checkNow(Context context) {
        Constraints network = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(UpdateCheckWorker.class)
                .setConstraints(network).build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_NAME, ExistingWorkPolicy.REPLACE, request);
    }
}
