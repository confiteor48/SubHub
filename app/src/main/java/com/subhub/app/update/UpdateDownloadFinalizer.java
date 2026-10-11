package com.subhub.app.update;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Idempotently moves a completed DownloadManager item into private verified storage. */
final class UpdateDownloadFinalizer {
    enum Result { READY, FAILED }

    private UpdateDownloadFinalizer() {}

    static synchronized Result finish(Context sourceContext, long id) {
        Context context = sourceContext.getApplicationContext();
        UpdateStateStore state = new UpdateStateStore(context);
        String existing = state.verifiedPath();
        if (!existing.isEmpty() && new File(existing).isFile()) return Result.READY;
        if (id < 0 || state.downloadId() != id) return Result.FAILED;

        UpdateCandidate candidate = state.candidate();
        UpdateManifest.Asset asset = candidate == null ? null
                : candidate.manifest.selectAsset(android.os.Build.SUPPORTED_ABIS);
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        Uri source = manager == null ? null : manager.getUriForDownloadedFile(id);
        if (candidate == null || asset == null || source == null) {
            failIfCurrent(context, state, id, true);
            return Result.FAILED;
        }

        File destination = new File(state.updateDirectory(), asset.name);
        try (InputStream raw = context.getContentResolver().openInputStream(source);
                BufferedInputStream input = new BufferedInputStream(raw);
                BufferedOutputStream output = new BufferedOutputStream(
                        new FileOutputStream(destination))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        } catch (Exception exception) {
            destination.delete();
            failIfCurrent(context, state, id, false);
            return Result.FAILED;
        } finally {
            manager.remove(id);
        }

        UpdateVerifier.Result verified = UpdateVerifier.verify(
                context, destination, candidate.manifest, asset);
        if (!verified.succeeded()) {
            destination.delete();
            failIfCurrent(context, state, id, false);
            return Result.FAILED;
        }
        synchronized (UpdateStateStore.class) {
            UpdateCandidate current = state.candidate();
            if (state.downloadId() != id || current == null
                    || !current.manifest.tag.equals(candidate.manifest.tag)) {
                destination.delete();
                return Result.FAILED;
            }
            state.setVerifiedPath(destination.getAbsolutePath());
            UpdateNotifications.cancelDownload(context, id);
            state.setDownloadId(-1L);
            UpdateNotifications.ready(context, candidate);
        }
        return Result.READY;
    }

    private static void failIfCurrent(Context context, UpdateStateStore state, long id, boolean deleteFile) {
        synchronized (UpdateStateStore.class) {
            if (state.downloadId() != id) return;
            state.clearDownload(deleteFile);
            UpdateNotifications.failed(context);
        }
    }
}
