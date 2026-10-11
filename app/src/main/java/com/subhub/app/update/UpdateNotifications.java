package com.subhub.app.update;

import android.Manifest;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.subhub.app.R;

/** Quiet updater notices; DownloadManager remains responsible for the transfer. */
public final class UpdateNotifications {
    private static final String CHANNEL = "subhub_updates";
    private static final int AVAILABLE_ID = 9070;
    private static final int READY_ID = 9071;
    private static final int DOWNLOAD_ID = 9072;
    private UpdateNotifications() {}

    public static void available(Context context, UpdateCandidate candidate) {
        UpdateStateStore state = new UpdateStateStore(context);
        if (candidate.prerelease && !state.devUpdates()) return;
        if (!canNotify(context) || !state.markNotified(candidate.manifest.versionName)) return;
        post(context, AVAILABLE_ID, builder(context)
                .setContentTitle(context.getString(R.string.update_available_title, candidate.manifest.versionName))
                .setContentText(context.getString(R.string.update_available_body)).build());
    }

    /** Never hide the system notice unless this app and channel can show its replacement. */
    static int downloadVisibility(Context context) {
        return canNotify(context) && ContextCompat.checkSelfPermission(context,
                "android.permission.DOWNLOAD_WITHOUT_NOTIFICATION") == PackageManager.PERMISSION_GRANTED
                ? DownloadManager.Request.VISIBILITY_HIDDEN : DownloadManager.Request.VISIBILITY_VISIBLE;
    }

    static void downloading(Context context, UpdateCandidate candidate, long id) {
        if (id < 0 || downloadVisibility(context) != DownloadManager.Request.VISIBILITY_HIDDEN) return;
        android.app.Notification notification = builder(context)
                .setContentTitle(context.getString(R.string.update_download_title, candidate.manifest.versionName))
                .setContentText(context.getString(R.string.update_download_body))
                .setProgress(0, 0, true).setOngoing(true).setAutoCancel(false)
                .setOnlyAlertOnce(true).setSilent(true).build();
        manager(context).notify(downloadTag(id), DOWNLOAD_ID,
                com.subhub.app.privacy.PrivacyNotifications.present(context, DOWNLOAD_ID, notification));
    }

    static void cancelDownload(Context context, long id) {
        if (id >= 0) manager(context).cancel(downloadTag(id), DOWNLOAD_ID);
    }

    static String downloadTag(long id) { return "subhub-download-" + id; }

    public static void ready(Context context, UpdateCandidate candidate) {
        if (!canNotify(context)) return;
        post(context, READY_ID, builder(context)
                .setContentTitle(context.getString(R.string.update_ready_title, candidate.manifest.versionName))
                .setContentText(context.getString(R.string.update_ready_body)).build());
    }

    public static void failed(Context context) {
        if (!canNotify(context)) return;
        post(context, READY_ID, builder(context)
                .setContentTitle(context.getString(R.string.update_failed_title))
                .setContentText(context.getString(R.string.update_failed_body)).build());
    }

    private static void post(Context context, int id, android.app.Notification notification) {
        manager(context).notify(id, com.subhub.app.privacy.PrivacyNotifications.present(context, id, notification));
    }

    private static NotificationCompat.Builder builder(Context context) {
        ensureChannel(context);
        return new NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification_subhub)
                .setColor(ContextCompat.getColor(context, R.color.accent)).setAutoCancel(true)
                .setContentIntent(intent(context)).setPriority(NotificationCompat.PRIORITY_DEFAULT);
    }

    private static PendingIntent intent(Context context) {
        return PendingIntent.getActivity(context, 0, new Intent(context, UpdatesActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static boolean canNotify(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = manager(context);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        ensureChannel(context);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private static NotificationManager manager(Context context) {
        return context.getSystemService(NotificationManager.class);
    }

    private static void ensureChannel(Context context) {
        manager(context).createNotificationChannel(new NotificationChannel(CHANNEL,
                context.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_DEFAULT));
    }
}
