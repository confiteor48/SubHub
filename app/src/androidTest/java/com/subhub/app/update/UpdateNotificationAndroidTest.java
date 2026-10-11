package com.subhub.app.update;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Until;
import com.subhub.app.BuildConfig;
import com.subhub.app.R;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;

/** Disposable emulator notices and a network-disabled DownloadManager item; no APK transfer/install. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 33)
public final class UpdateNotificationAndroidTest {
    private Context context;
    private NotificationManager notifications;
    private UpdateStateStore state;
    private SharedPreferences updates, privacy;
    private Map<String, ?> updatesBefore, privacyBefore;
    private long id = -1;
    @Before public void prepare() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        notifications = context.getSystemService(NotificationManager.class);
        updates = context.getSharedPreferences("subhub_updates", Context.MODE_PRIVATE);
        privacy = context.getSharedPreferences("subhub_privacy", Context.MODE_PRIVATE);
        updatesBefore = new java.util.LinkedHashMap<>(updates.getAll());
        privacyBefore = new java.util.LinkedHashMap<>(privacy.getAll());
        updates.edit().clear().putBoolean("automatic_checks", false).commit();
        privacy.edit().putBoolean("discreet", false).commit();
        state = new UpdateStateStore(context);
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(
                context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
        notifications.cancelAll();
    }
    @After public void restore() throws Exception {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack();
        if (id >= 0) context.getSystemService(DownloadManager.class).remove(id);
        notifications.cancelAll();
        SharedPreferenceTestRestore.restore(updates, updatesBefore);
        SharedPreferenceTestRestore.restore(privacy, privacyBefore);
    }

    @Test public void realPendingDownloadPostsBrandedNoticeAndCancelRemovesBoth() throws Exception {
        UpdateCandidate candidate = candidate();
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(
                "android.permission.DOWNLOAD_WITHOUT_NOTIFICATION"));
        assertEquals(DownloadManager.Request.VISIBILITY_HIDDEN, UpdateNotifications.downloadVisibility(context));
        UpdateDownloadCoordinator coordinator = new UpdateDownloadCoordinator(context);
        // Use the same request construction as start, with networking explicitly disabled.
        id = context.getSystemService(DownloadManager.class).enqueue(
                coordinator.request(candidate, candidate.manifest.assets.get(0)).setAllowedNetworkTypes(0));
        state.setCandidate(candidate); state.setDownloadId(id);
        UpdateNotifications.downloading(context, candidate, id);
        Notification notice = await(UpdateNotifications.downloadTag(id)).getNotification();
        assertEquals(R.drawable.ic_notification_subhub, notice.getSmallIcon().getResId());
        assertTrue((notice.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertTrue(notice.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE));
        assertNotNull(notice.contentIntent);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertTrue(device.openNotification());
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(
                R.string.update_download_title, candidate.manifest.versionName))), 5000));
        Bitmap screen = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screen);
        try (FileOutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null),
                "update-download-notification.png"))) { assertTrue(screen.compress(Bitmap.CompressFormat.PNG, 100, output)); }
        finally { screen.recycle(); }
        device.pressBack();
        coordinator.cancel();
        assertNull(find(UpdateNotifications.downloadTag(id)));
        try (android.database.Cursor rows = context.getSystemService(DownloadManager.class)
                .query(new DownloadManager.Query().setFilterById(id))) { assertEquals(0, rows.getCount()); }
        assertEquals(-1L, state.downloadId());
    }

    @Test public void completionFailureAndStaleCancellationKeepCorrectNotice() throws Exception {
        UpdateCandidate candidate = candidate();
        UpdateNotifications.downloading(context, candidate, 101);
        await(UpdateNotifications.downloadTag(101));
        UpdateNotifications.downloading(context, candidate, 102);
        await(UpdateNotifications.downloadTag(102));
        UpdateNotifications.cancelDownload(context, 101);
        assertNotNull(find(UpdateNotifications.downloadTag(102)));
        state.setDownloadId(102); state.clearDownload(false);
        assertNull(find(UpdateNotifications.downloadTag(102)));
        UpdateNotifications.ready(context, candidate);
        Notification ready = await(null).getNotification();
        assertEquals(context.getString(R.string.update_ready_title, candidate.manifest.versionName),
                ready.extras.getCharSequence(Notification.EXTRA_TITLE));
        assertEquals(0, ready.flags & Notification.FLAG_ONGOING_EVENT);
        assertEquals(R.drawable.ic_notification_subhub, ready.getSmallIcon().getResId());
        UpdateNotifications.failed(context);
        Notification failed = awaitTitle(context.getString(R.string.update_failed_title));
        assertEquals(0, failed.flags & Notification.FLAG_ONGOING_EVENT);
    }

    @Test public void deniedNotificationsKeepSystemFallbackAndDiscreetModeUsesNeutralIcon() throws Exception {
        Context denied = new ContextWrapper(context) {
            @Override public int checkPermission(String permission, int pid, int uid) {
                return Manifest.permission.POST_NOTIFICATIONS.equals(permission)
                        ? PackageManager.PERMISSION_DENIED : super.checkPermission(permission, pid, uid);
            }
        };
        assertEquals(DownloadManager.Request.VISIBILITY_VISIBLE, UpdateNotifications.downloadVisibility(denied));
        UpdateNotifications.downloading(denied, candidate(), 103);
        assertNull(find(UpdateNotifications.downloadTag(103)));
        privacy.edit().putBoolean("discreet", true).commit();
        UpdateNotifications.downloading(context, candidate(), 104);
        Notification notice = await(UpdateNotifications.downloadTag(104)).getNotification();
        assertEquals(R.drawable.ic_discreet_filter, notice.getSmallIcon().getResId());
        assertEquals(context.getString(R.string.privacy_discreet_name),
                notice.extras.getCharSequence(Notification.EXTRA_TITLE));
        assertTrue((notice.flags & Notification.FLAG_ONGOING_EVENT) != 0);
    }

    @Test public void monochromeBrandHasTransparentCornersAndRecognizableOpenings() {
        Drawable glyph = context.getDrawable(R.drawable.ic_notification_subhub);
        Bitmap image = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        glyph.setBounds(0, 0, 96, 96); glyph.draw(new Canvas(image));
        int visible = 0;
        for (int y = 0; y < 96; y++) for (int x = 0; x < 96; x++) {
            int pixel = image.getPixel(x, y);
            if (android.graphics.Color.alpha(pixel) > 0) {
                visible++; assertEquals(0xFFFFFF, pixel & 0xFFFFFF);
            }
        }
        assertTrue(visible > 1500 && visible < 7000);
        assertEquals(0, image.getPixel(0, 0));
        assertEquals(0, image.getPixel(48, 49));
        assertTrue(android.graphics.Color.alpha(image.getPixel(48, 72)) > 0);
        image.recycle();
    }

    private StatusBarNotification find(String tag) {
        for (StatusBarNotification notice : notifications.getActiveNotifications())
            if (java.util.Objects.equals(tag, notice.getTag())) return notice;
        return null;
    }
    private StatusBarNotification await(String tag) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            StatusBarNotification notice = find(tag); if (notice != null) return notice;
            Thread.sleep(20);
        }
        throw new AssertionError("Expected posted update notice");
    }
    private Notification awaitTitle(String title) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            StatusBarNotification notice = find(null);
            if (notice != null && title.contentEquals(notice.getNotification().extras
                    .getCharSequence(Notification.EXTRA_TITLE))) return notice.getNotification();
            Thread.sleep(20);
        }
        throw new AssertionError("Expected update notice replacement");
    }
    private UpdateCandidate candidate() throws Exception {
        JSONObject asset = new JSONObject().put("abi", "universal").put("name", "notification-fixture.apk")
                .put("url", "https://github.com/confiteor48/SubHub/releases/download/v9.9.9/notification-fixture.apk")
                .put("sha256", "a".repeat(64)).put("size", 42);
        JSONObject manifest = new JSONObject().put("schema", 1).put("packageName", "com.subhub.app")
                .put("versionName", "9.9.9").put("versionCode", BuildConfig.VERSION_CODE + 1L)
                .put("minSdk", 26).put("tag", "v9.9.9").put("assets", new JSONArray().put(asset));
        return new UpdateCandidate(UpdateManifest.parse(manifest.toString()), "", "");
    }
}
