package com.subhub.app.update;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import java.io.File;

/** Starts and restores the one user-approved APK download. */
public final class UpdateDownloadCoordinator {
    public static final class Status {
        public final int state;
        public final long downloaded;
        public final long total;
        Status(int state, long downloaded, long total) {
            this.state = state;
            this.downloaded = downloaded;
            this.total = total;
        }
    }

    private final Context context;
    private final DownloadManager downloads;
    private final UpdateStateStore state;

    public UpdateDownloadCoordinator(Context context) {
        this.context = context.getApplicationContext();
        downloads = this.context.getSystemService(DownloadManager.class);
        state = new UpdateStateStore(this.context);
    }

    public long start(UpdateCandidate candidate) {
        if (candidate.prerelease && !state.devUpdates()) {
            throw new IllegalStateException("Dev updates are disabled");
        }
        UpdateManifest.Asset asset = candidate.manifest.selectAsset(android.os.Build.SUPPORTED_ABIS);
        if (asset == null) throw new IllegalStateException("No compatible APK");
        cancel();
        File destination = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), asset.name);
        if (destination.exists()) destination.delete();
        DownloadManager.Request request = request(candidate, asset);
        state.setCandidate(candidate);
        long id = downloads.enqueue(request);
        state.setDownloadId(id);
        state.setVerifiedPath("");
        state.setInstallRequested(true);
        UpdateNotifications.downloading(context, candidate, id);
        return id;
    }

    DownloadManager.Request request(UpdateCandidate candidate, UpdateManifest.Asset asset) {
        return new DownloadManager.Request(Uri.parse(asset.url))
                .setTitle(context.getString(com.subhub.app.R.string.update_download_title,
                        candidate.manifest.versionName))
                .setDescription(context.getString(com.subhub.app.R.string.update_download_body))
                .setMimeType("application/vnd.android.package-archive")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(UpdateNotifications.downloadVisibility(context))
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, asset.name);
    }

    public void cancel() {
        long id = state.downloadId();
        if (id >= 0) downloads.remove(id);
        state.clearDownload(true);
    }

    public Status status() {
        long id = state.downloadId();
        if (id < 0) return null;
        try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            return new Status(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
        }
    }
}
