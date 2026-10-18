package com.subhub.app.update;

import com.subhub.app.util.PrimaryHeader;

import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.subhub.app.BuildConfig;
import com.subhub.app.R;
import com.subhub.app.databinding.ActivityUpdatesBinding;
import com.subhub.app.security.ControllerPinManager;

import java.io.File;
import java.text.DateFormat;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Maintenance surface shared by Dom and Sub mode for signed GitHub releases. */
public final class UpdatesActivity extends AppCompatActivity {
    private ActivityUpdatesBinding binding;
    private UpdateStateStore state;
    private UpdateDownloadCoordinator downloads;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean checking;
    private boolean changingAutomatic;
    private boolean installAfterPermission;
    private boolean finalizingDownload;
    private boolean installPromptQueued;
    private String historyFingerprint = "";
    private final Set<String> expandedHistory = new HashSet<>();
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            render();
            if (binding != null && state.downloadId() >= 0) main.postDelayed(this, 750L);
        }
    };
    private final ActivityResultLauncher<Intent> installPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (installAfterPermission) {
                    installAfterPermission = false;
                    installVerified();
                }
            });

    @Override protected void onCreate(Bundle stateBundle) {
        super.onCreate(stateBundle);
        binding = ActivityUpdatesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bindSecondary(binding.getRoot(), R.string.update_title, false);
        state = new UpdateStateStore(this);
        downloads = new UpdateDownloadCoordinator(this);
        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> finish());
        binding.buttonCheck.setOnClickListener(view -> checkNow());
        binding.buttonCancel.setOnClickListener(view -> {
            downloads.cancel();
            binding.updateStatus.setText(R.string.update_download_failed);
            render();
        });
        changingAutomatic = true;
        binding.automaticChecks.setChecked(state.automaticChecks());
        changingAutomatic = false;
        binding.automaticChecks.setOnCheckedChangeListener((button, checked) -> {
            if (changingAutomatic || !ControllerPinManager.isDomModeActive()) return;
            state.setAutomaticChecks(checked);
            UpdateScheduler.synchronize(this);
        });
        binding.devUpdates.setChecked(state.devUpdates());
        binding.devUpdates.setOnCheckedChangeListener((button, checked) -> {
            if (changingAutomatic || !ControllerPinManager.isDomModeActive()) return;
            state.setDevUpdates(checked);
            UpdateScheduler.synchronize(this, false);
            render();
            checkNow();
        });
        render();
    }

    @Override protected void onRestoreInstanceState(Bundle savedInstanceState) {
        changingAutomatic = true;
        try {
            super.onRestoreInstanceState(savedInstanceState);
        } finally {
            changingAutomatic = false;
        }
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        render();
        main.removeCallbacks(poll);
        if (state.downloadId() >= 0) main.post(poll);
    }

    private void checkNow() {
        if (checking) return;
        checking = true;
        binding.buttonCheck.setEnabled(false);
        binding.devUpdates.setEnabled(false);
        binding.updateStatus.setText(R.string.update_checking);
        network.execute(() -> {
            GitHubReleaseRepository.Result result = new GitHubReleaseRepository(this).check();
            main.post(() -> {
                if (binding == null) return;
                checking = false;
                binding.buttonCheck.setEnabled(true);
                if (!result.succeeded()) binding.updateStatus.setText(error(result.failure));
                else binding.updateStatus.setText(result.candidate == null
                        ? R.string.update_none_available : R.string.update_available_body);
                render();
            });
        });
    }

    private int error(GitHubReleaseRepository.Failure failure) {
        if (failure == GitHubReleaseRepository.Failure.OFFLINE) return R.string.update_offline;
        if (failure == GitHubReleaseRepository.Failure.RATE_LIMITED) return R.string.update_rate_limited;
        if (failure == GitHubReleaseRepository.Failure.SERVER) return R.string.update_server_error;
        return R.string.update_invalid_release;
    }

    private void render() {
        if (binding == null) return;
        UpdateCandidate candidate = state.candidate();
        if (candidate == null && (state.downloadId() >= 0 || !state.verifiedPath().isEmpty())) {
            downloads.cancel();
        }
        binding.installedVersion.setText(getString(R.string.update_installed_version,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        binding.availableVersion.setText(candidate == null
                ? getString(R.string.update_none_available)
                : getString(R.string.update_available_version, candidate.manifest.versionName));
        long last = state.lastCheck();
        binding.lastChecked.setText(last <= 0 ? getString(R.string.update_not_checked)
                : getString(R.string.update_last_checked,
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(new Date(last))));
        boolean dom = ControllerPinManager.isDomModeActive();
        binding.automaticChecks.setText(state.devUpdates()
                ? R.string.update_automatic_dev_toggle : R.string.update_automatic_toggle);
        binding.automaticChecks.setEnabled(dom);
        binding.devUpdates.setEnabled(dom && !checking);
        binding.automaticHelp.setText(dom
                ? R.string.update_automatic_help : R.string.update_automatic_dom_help);
        changingAutomatic = true;
        binding.automaticChecks.setChecked(state.automaticChecks());
        binding.devUpdates.setChecked(state.devUpdates());
        changingAutomatic = false;
        binding.devUpdatesHelp.setText(dom
                ? R.string.update_dev_help : R.string.update_dev_dom_help);
        List<ReleaseHistoryItem> history = state.releaseHistory();
        if (history.isEmpty()) history = ReleaseHistoryCatalog.bundled(this);
        history = ReleaseHistoryCatalog.withCandidate(history, candidate);
        renderReleaseHistory(history, candidate);
        binding.downloadGuideCard.setVisibility(candidate == null ? View.GONE : View.VISIBLE);
        if (candidate != null) {
            UpdateManifest.Asset asset = candidate.manifest.selectAsset(Build.SUPPORTED_ABIS);
            binding.downloadGuide.setText(getString(R.string.update_download_guide,
                    asset == null ? getString(R.string.update_download_universal) : asset.name));
        }

        String verifiedPath = state.verifiedPath();
        if (!verifiedPath.isEmpty() && new File(verifiedPath).isFile()) {
            binding.downloadProgress.setVisibility(View.GONE);
            binding.buttonAction.setVisibility(View.VISIBLE);
            binding.buttonAction.setText(R.string.update_install);
            binding.buttonAction.setOnClickListener(view -> installVerified());
            binding.buttonCancel.setVisibility(View.GONE);
            if (!checking) binding.updateStatus.setText(R.string.update_ready_body);
            if (state.installRequested() && !installAfterPermission && !installPromptQueued) {
                state.setInstallRequested(false);
                installPromptQueued = true;
                main.post(() -> {
                    installPromptQueued = false;
                    installVerified();
                });
            }
            return;
        }
        UpdateDownloadCoordinator.Status download = downloads.status();
        if (download != null && (download.state == DownloadManager.STATUS_PENDING
                || download.state == DownloadManager.STATUS_RUNNING
                || download.state == DownloadManager.STATUS_PAUSED)) {
            int percent = download.total > 0
                    ? (int) Math.min(100, download.downloaded * 100L / download.total) : 0;
            binding.downloadProgress.setVisibility(View.VISIBLE);
            binding.downloadProgress.setIndeterminate(download.total <= 0);
            binding.downloadProgress.setProgress(percent);
            binding.buttonAction.setVisibility(View.GONE);
            binding.buttonCancel.setVisibility(View.VISIBLE);
            if (!checking) binding.updateStatus.setText(getString(R.string.update_downloading, percent));
            return;
        }
        if (download != null && download.state == DownloadManager.STATUS_SUCCESSFUL) {
            binding.downloadProgress.setVisibility(View.VISIBLE);
            binding.downloadProgress.setIndeterminate(true);
            binding.buttonAction.setVisibility(View.GONE);
            binding.buttonCancel.setVisibility(View.GONE);
            if (!checking) binding.updateStatus.setText(R.string.update_verifying);
            finalizeDownload();
            return;
        }
        if (download != null && download.state == DownloadManager.STATUS_FAILED) {
            downloads.cancel();
            if (!checking) binding.updateStatus.setText(R.string.update_download_failed);
        }
        binding.downloadProgress.setVisibility(View.GONE);
        binding.buttonCancel.setVisibility(View.GONE);
        binding.buttonAction.setVisibility(candidate == null ? View.GONE : View.VISIBLE);
        binding.buttonAction.setText(R.string.update_download);
        binding.buttonAction.setOnClickListener(view -> beginDownload());
    }

    private void renderReleaseHistory(List<ReleaseHistoryItem> history, UpdateCandidate candidate) {
        binding.releaseNotesCard.setVisibility(history.isEmpty() ? View.GONE : View.VISIBLE);
        if (history.isEmpty()) return;
        StringBuilder fingerprint = new StringBuilder();
        fingerprint.append(getResources().getConfiguration().getLocales().toLanguageTags())
                .append(':').append(ZoneId.systemDefault().getId()).append(';');
        for (ReleaseHistoryItem item : history) {
            fingerprint.append(item.tag).append(':').append(item.notes.hashCode())
                    .append(':').append(item.publishedAt).append(':').append(item.prerelease).append(';');
        }
        String value = fingerprint.toString();
        if (value.equals(historyFingerprint)) return;
        boolean firstRender = historyFingerprint.isEmpty();
        historyFingerprint = value;
        if (firstRender && candidate != null) expandedHistory.add(candidate.manifest.tag);
        binding.releaseHistoryList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (ReleaseHistoryItem item : history) {
            View row = inflater.inflate(R.layout.item_release_history,
                    binding.releaseHistoryList, false);
            TextView version = row.findViewById(R.id.release_version);
            TextView meta = row.findViewById(R.id.release_meta);
            TextView details = row.findViewById(R.id.release_details);
            TextView chevron = row.findViewById(R.id.release_chevron);
            version.setText(getString(R.string.update_history_version, item.versionName));
            meta.setText(historyMeta(item, candidate));
            details.setText(ReleaseNotesFormatter.forView(details,
                    item.notes, getString(R.string.update_notes_unavailable)));
            boolean expanded = expandedHistory.contains(item.tag);
            details.setVisibility(expanded ? View.VISIBLE : View.GONE);
            chevron.setText(expanded ? "⌄" : "›");
            row.setContentDescription(getString(R.string.update_history_open, item.versionName));
            row.setOnClickListener(view -> {
                boolean show = details.getVisibility() != View.VISIBLE;
                details.setVisibility(show ? View.VISIBLE : View.GONE);
                chevron.setText(show ? "⌄" : "›");
                if (show) expandedHistory.add(item.tag);
                else expandedHistory.remove(item.tag);
            });
            binding.releaseHistoryList.addView(row);
        }
    }

    private String historyMeta(ReleaseHistoryItem item, UpdateCandidate candidate) {
        if (candidate != null && item.tag.equals(candidate.manifest.tag)) {
            return getString(R.string.update_history_available);
        }
        if (item.versionName.equals(BuildConfig.VERSION_NAME)) {
            return getString(R.string.update_history_installed);
        }
        if (item.prerelease) {
            Locale locale = getResources().getConfiguration().getLocales().get(0);
            String timestamp = ReleaseTimestampFormatter.preview(item.publishedAt,
                    locale == null ? Locale.getDefault() : locale, ZoneId.systemDefault());
            return timestamp.isEmpty() ? getString(R.string.update_history_preview)
                    : getString(R.string.update_history_preview_date, timestamp);
        }
        String date = item.publishedAt.length() >= 10
                ? item.publishedAt.substring(0, 10) : "";
        return date.isEmpty() ? getString(R.string.update_history_published) : date;
    }

    private void finalizeDownload() {
        if (finalizingDownload) return;
        long id = state.downloadId();
        if (id < 0) return;
        finalizingDownload = true;
        network.execute(() -> {
            UpdateDownloadFinalizer.Result result = UpdateDownloadFinalizer.finish(this, id);
            main.post(() -> {
                if (binding == null) return;
                finalizingDownload = false;
                if (result == UpdateDownloadFinalizer.Result.FAILED && !checking) {
                    binding.updateStatus.setText(R.string.update_verification_failed);
                }
                render();
            });
        });
    }

    private void beginDownload() {
        UpdateCandidate candidate = state.candidate();
        if (candidate == null) return;
        try {
            downloads.start(candidate);
            binding.updateStatus.setText(getString(R.string.update_downloading, 0));
            main.removeCallbacks(poll);
            main.post(poll);
            render();
        } catch (RuntimeException exception) {
            binding.updateStatus.setText(R.string.update_no_compatible_apk);
        }
    }

    private void installVerified() {
        if (state.candidate() == null) {
            downloads.cancel();
            render();
            return;
        }
        String path = state.verifiedPath();
        File apk = path.isEmpty() ? null : new File(path);
        if (apk == null || !apk.isFile()) {
            binding.updateStatus.setText(R.string.update_verification_failed);
            state.clearDownload(true);
            render();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            installAfterPermission = true;
            binding.updateStatus.setText(R.string.update_install_permission);
            installPermission.launch(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", apk);
        startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                        "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        network.shutdownNow();
        binding = null;
        super.onDestroy();
    }
}
