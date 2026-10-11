package com.subhub.app.update;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;

import java.io.File;
import java.util.Collections;
import java.util.List;

/** Durable updater state; contains no credentials. */
public final class UpdateStateStore {
    private static final String PREFS = "subhub_updates";
    private static final String AUTO = "automatic_checks";
    private static final String DEV = "dev_updates";
    private static final String CHANNEL_REVISION = "channel_revision";
    private static final String LAST_CHECK = "last_check";
    private static final String ETAG = "release_etag";
    private static final String CANDIDATE = "candidate";
    private static final String RELEASE_HISTORY = "release_history";
    private static final String LAST_NOTIFIED = "last_notified";
    private static final String DOWNLOAD_ID = "download_id";
    private static final String VERIFIED_PATH = "verified_path";
    private static final String VERIFIED_AT = "verified_at";
    private static final String INSTALL_REQUESTED = "install_requested";
    private final Context context;
    private final SharedPreferences preferences;

    public UpdateStateStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!preferences.contains(DEV)) {
            // Old versions included previews implicitly; force a fresh, stable-only lookup.
            preferences.edit().putBoolean(DEV, false).remove(ETAG).apply();
        }
    }

    public boolean automaticChecks() { return preferences.getBoolean(AUTO, true); }
    public void setAutomaticChecks(boolean enabled) { preferences.edit().putBoolean(AUTO, enabled).apply(); }
    public boolean devUpdates() { return preferences.getBoolean(DEV, false); }
    public long channelRevision() { return preferences.getLong(CHANNEL_REVISION, 0L); }

    public void setDevUpdates(boolean enabled) {
        synchronized (UpdateStateStore.class) {
            if (enabled == devUpdates()) return;
            new UpdateDownloadCoordinator(context).cancel();
            preferences.edit().putBoolean(DEV, enabled)
                    .putLong(CHANNEL_REVISION, channelRevision() + 1L)
                    .remove(ETAG).remove(CANDIDATE).remove(LAST_NOTIFIED).remove(LAST_CHECK).apply();
        }
    }

    /** A check started under another channel must never repopulate its old candidate. */
    boolean saveCheck(long revision, String etag, List<ReleaseHistoryItem> history,
            UpdateCandidate candidate) {
        synchronized (UpdateStateStore.class) {
            if (revision != channelRevision()) return false;
            setEtag(etag);
            setLastCheck(System.currentTimeMillis());
            setReleaseHistory(history);
            if (candidate != null) setCandidate(candidate);
            else {
                clearCandidate();
                new UpdateDownloadCoordinator(context).cancel();
            }
            return true;
        }
    }
    public long lastCheck() { return preferences.getLong(LAST_CHECK, 0L); }
    public void setLastCheck(long value) { preferences.edit().putLong(LAST_CHECK, value).apply(); }
    public String etag() { return preferences.getString(ETAG, ""); }
    public void setEtag(String value) { preferences.edit().putString(ETAG, value == null ? "" : value).apply(); }
    public long downloadId() { return preferences.getLong(DOWNLOAD_ID, -1L); }
    public void setDownloadId(long id) { preferences.edit().putLong(DOWNLOAD_ID, id).apply(); }
    public String verifiedPath() { return preferences.getString(VERIFIED_PATH, ""); }
    public void setVerifiedPath(String path) {
        preferences.edit().putString(VERIFIED_PATH, path)
                .putLong(VERIFIED_AT, path == null || path.isEmpty() ? 0L : System.currentTimeMillis()).apply();
    }
    public boolean installRequested() {
        return preferences.getBoolean(INSTALL_REQUESTED, false);
    }
    public void setInstallRequested(boolean requested) {
        preferences.edit().putBoolean(INSTALL_REQUESTED, requested).apply();
    }

    public UpdateCandidate candidate() {
        String value = preferences.getString(CANDIDATE, "");
        if (value == null || value.isEmpty()) return null;
        try {
            UpdateCandidate candidate = UpdateCandidate.parse(value);
            return candidate.prerelease && !devUpdates() ? null : candidate;
        }
        catch (JSONException | IllegalArgumentException exception) { return null; }
    }

    public void setCandidate(UpdateCandidate candidate) {
        UpdateCandidate previous = candidate();
        if (previous != null && previous.manifest.versionCode != candidate.manifest.versionCode) {
            clearDownload(true);
        }
        try { preferences.edit().putString(CANDIDATE, candidate.json()).apply(); }
        catch (JSONException ignored) { }
    }

    public void clearCandidate() {
        preferences.edit().remove(CANDIDATE).remove(LAST_NOTIFIED).apply();
    }

    public List<ReleaseHistoryItem> releaseHistory() {
        String value = preferences.getString(RELEASE_HISTORY, "");
        try { return ReleaseHistoryItem.decode(value); }
        catch (JSONException exception) { return Collections.emptyList(); }
    }

    public void setReleaseHistory(List<ReleaseHistoryItem> history) {
        try {
            preferences.edit().putString(RELEASE_HISTORY, ReleaseHistoryItem.encode(history)).apply();
        } catch (JSONException ignored) { }
    }

    public boolean markNotified(String version) {
        if (version.equals(preferences.getString(LAST_NOTIFIED, ""))) return false;
        preferences.edit().putString(LAST_NOTIFIED, version).apply();
        return true;
    }

    public void clearDownload(boolean deleteFile) {
        UpdateNotifications.cancelDownload(context, downloadId());
        if (deleteFile) {
            String path = verifiedPath();
            if (!path.isEmpty()) new File(path).delete();
        }
        preferences.edit().remove(DOWNLOAD_ID).remove(VERIFIED_PATH).remove(VERIFIED_AT)
                .remove(INSTALL_REQUESTED).apply();
    }

    public void cleanupInstalled(long installedVersionCode) {
        UpdateCandidate current = candidate();
        if (current != null && installedVersionCode >= current.manifest.versionCode) {
            clearDownload(true);
            preferences.edit().remove(CANDIDATE).remove(LAST_NOTIFIED).apply();
        }
        long verifiedAt = preferences.getLong(VERIFIED_AT, 0L);
        if (verifiedAt > 0 && System.currentTimeMillis() - verifiedAt > 7L * 24L * 60L * 60L * 1000L) {
            clearDownload(true);
        }
    }

    public File updateDirectory() {
        File directory = new File(context.getFilesDir(), "updates");
        if (!directory.exists()) directory.mkdirs();
        return directory;
    }
}
