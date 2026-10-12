package com.subhub.app.stats;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import com.subhub.app.detection.TrackedObject;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.SceneEngine;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Process-safe local counters compatible with the recovered statistics preference store. */
public final class StatsRepository {
    public static final String PREFS_NAME = "betablocker_stats";
    private static final String KEY_ACTIVE_DATES = "active_dates";
    private static final String KEY_ALL_CATEGORIES_CENSORS = "all_categories_censors";
    private static final String KEY_BORDER_COLOR_CHANGED = "border_color_changed";
    private static final String KEY_BORDER_EFFECTS_TRIED = "border_effects_tried";
    private static final String KEY_BROWSER_SESSIONS = "browser_sessions_count";
    private static final String KEY_BROWSER_PAGES = "browser_pages_visited";
    private static final String KEY_CENSOR_STYLES_TRIED = "censor_styles_tried";
    private static final String KEY_CENSOR_STYLE_CHANGES = "censor_style_changes";
    private static final String KEY_CURRENT_STREAK = "current_streak";
    private static final String KEY_CUSTOM_PHRASES_COUNT = "custom_phrases_count";
    private static final String KEY_EXPORTED_IMAGES = "exported_images_count";
    private static final String KEY_LAST_SESSION_DATE = "last_session_date";
    private static final String KEY_LONGEST_SESSION = "longest_session_seconds";
    private static final String KEY_PEAK_SESSION_BLOCKS = "peak_session_blocks";
    private static final String KEY_SESSIONS_COUNT = "sessions_count";
    private static final String KEY_SESSION_HISTORY = "session_history";
    private static final String KEY_ACTIVE_SESSION_START = "active_session_start_ms";
    private static final String KEY_ACTIVE_SESSION_BLOCKS = "active_session_blocks";
    private static final String KEY_TOTAL_BLOCKS = "total_blocks_all_time";
    private static final String KEY_TOTAL_PROTECTED_TIME = "total_protected_time";
    private static final String KEY_TOTAL_SESSION_TIME = "total_session_time";
    private static final String KEY_SUBLIMINAL_IMPRESSIONS = "subliminal_impressions";
    private static final String KEY_ACTIVE_SESSION_SUBLIMINALS = "active_session_subliminals";
    private static final String KEY_LIMITED_APP_MILLIS = "limited_app_millis";
    private static final String KEY_ACTIVE_SESSION_LIMITED_APP_MILLIS =
            "active_session_limited_app_millis";
    private static final String KEY_LIMIT_INTERVENTIONS = "limit_interventions";
    private static final String KEY_ACTIVE_SESSION_LIMIT_INTERVENTIONS =
            "active_session_limit_interventions";
    private static final String KEY_TRIBUTE_EVENTS = "tribute_events";
    private static final String KEY_ACTIVE_SESSION_TRIBUTE_EVENTS =
            "active_session_tribute_events";
    private static final String KEY_TRIBUTE_CENTS = "tribute_cents";
    private static final String KEY_ACTIVE_SESSION_TRIBUTE_CENTS =
            "active_session_tribute_cents";
    private static final String KEY_TAMPER_EVENTS = "tamper_events";
    private static final String KEY_ACTIVE_SESSION_TAMPER_EVENTS =
            "active_session_tamper_events";
    private static final String KEY_POPUP_IMPRESSIONS = "popup_impressions";
    private static final String KEY_ACTIVE_SESSION_POPUP_IMPRESSIONS =
            "active_session_popup_impressions";
    private static final int SESSION_HISTORY_MAX = 30;
    private static final Object SESSION_LOCK = new Object();
    private static final Set<Integer> SEEN_TRACK_IDS = new HashSet<>();
    private static final Set<String> ALL_CATEGORIES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("genitals_female", "genitals_male", "breasts",
                    "buttocks", "anus", "face", "belly", "male_chest", "feet",
                    "armpits", "genitals_covered", "breasts_covered", "buttocks_covered",
                    "anus_covered", "belly_covered", "feet_covered", "armpits_covered")));
    private static long sessionStartMs;
    private static int sessionBlocks;
    private static volatile SessionStamp activeStamp;

    /** Reporting time is deliberately not this process-local session's ABA authority. */
    public static final class SessionStamp {
        private SessionStamp() { }
        public boolean isCurrent() { return activeStamp == this; }
    }

    /** Nonblocking authority recheck for product main; restoration happens on its lookup worker. */
    public static SessionStamp currentSessionStamp() { return activeStamp; }

    public enum EvidenceStatus { COMMITTED, NO_NEW_EVIDENCE, REJECTED, UNACKNOWLEDGED }
    public static final class EvidenceRecord {
        public final EvidenceStatus status;
        public final int count;
        public final StatsSnapshot snapshot;
        private EvidenceRecord(EvidenceStatus status, int count, StatsSnapshot snapshot) {
            this.status = status; this.count = count; this.snapshot = snapshot;
        }
    }

    public SessionStamp sessionStamp() {
        synchronized (SESSION_LOCK) { restoreActiveSession(); return sessionStartMs == 0 ? null : activeStamp; }
    }

    private final SharedPreferences preferences;
    private final Context app;

    public StatsRepository(Context context) {
        app = context.getApplicationContext();
        preferences = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        synchronized (SESSION_LOCK) { syncDaily(); }
    }

    public void startSession() {
        synchronized (SESSION_LOCK) {
            restoreActiveSession();
            if (sessionStartMs != 0) return;
            sessionStartMs = System.currentTimeMillis();
            sessionBlocks = 0;
            activeStamp = new SessionStamp();
            SEEN_TRACK_IDS.clear();
            writeDaily(preferences.edit()
                    .putLong(KEY_ACTIVE_SESSION_START, sessionStartMs)
                    .putInt(KEY_ACTIVE_SESSION_BLOCKS, 0)
                    .putLong(KEY_ACTIVE_SESSION_SUBLIMINALS, 0L)
                    .putLong(KEY_ACTIVE_SESSION_LIMITED_APP_MILLIS, 0L)
                    .putLong(KEY_ACTIVE_SESSION_LIMIT_INTERVENTIONS, 0L)
                    .putLong(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS, 0L)
                    .putLong(KEY_ACTIVE_SESSION_TRIBUTE_CENTS, 0L)
                    .putLong(KEY_ACTIVE_SESSION_TRIBUTE_CENTS + "_USD", 0L)
                    .putLong(KEY_ACTIVE_SESSION_TAMPER_EVENTS, 0L)
                    .putLong(KEY_ACTIVE_SESSION_POPUP_IMPRESSIONS, 0L)
                    .putInt(KEY_SESSIONS_COUNT, preferences.getInt(KEY_SESSIONS_COUNT, 0) + 1)
                    );
            updateStreak();
            DailyDurationRecorder.changed();
        }
    }

    public boolean onTracks(List<TrackedObject> tracks) {
        return recordTracks(tracks, null) > 0;
    }

    public boolean onTracks(List<TrackedObject> tracks, Set<String> enabledCategories) {
        return recordTracks(tracks, enabledCategories) > 0;
    }

    public boolean onTrackIds(List<Integer> trackIds, Set<String> enabledCategories) {
        return recordTrackIds(trackIds, enabledCategories) > 0;
    }

    /** Records new tracker IDs and returns their exact count for bounded downstream ledgers. */
    public int recordTracks(List<TrackedObject> tracks, Set<String> enabledCategories) {
        return recordTrackIds(trackIds(tracks), enabledCategories);
    }

    public int recordTrackIds(List<Integer> trackIds, Set<String> enabledCategories) {
        synchronized (SESSION_LOCK) {
            restoreActiveSession();
            if (sessionStartMs == 0) return 0;
            int added = 0;
            for (Integer id : trackIds) if (id != null && SEEN_TRACK_IDS.add(id)) added++;
            if (added == 0) return 0;
            sessionBlocks += added;
            SharedPreferences.Editor edit = preferences.edit()
                    .putInt(KEY_ACTIVE_SESSION_BLOCKS, sessionBlocks)
                    .putLong(KEY_TOTAL_BLOCKS, preferences.getLong(KEY_TOTAL_BLOCKS, 0) + added);
            if (sessionBlocks > preferences.getInt(KEY_PEAK_SESSION_BLOCKS, 0)) {
                edit.putInt(KEY_PEAK_SESSION_BLOCKS, sessionBlocks);
            }
            Set<String> dates = mutableSet(KEY_ACTIVE_DATES);
            if (dates.add(today())) edit.putStringSet(KEY_ACTIVE_DATES, dates);
            if (enabledCategories != null && enabledCategories.containsAll(ALL_CATEGORIES)) {
                edit.putLong(KEY_ALL_CATEGORIES_CENSORS,
                        preferences.getLong(KEY_ALL_CATEGORIES_CENSORS, 0) + added);
            }
            writeDaily(edit);
            return added;
        }
    }

    /**
     * Off-main, acknowledged counter write. The supplier may only claim source evidence, never
     * invoke wallet/UI/observers under SESSION_LOCK. Downstream work starts after this returns.
     * An unacknowledged commit consumed the claim and must not be replayed speculatively.
     */
    public EvidenceRecord recordEvidence(SessionStamp expected, Supplier<CensorEngine.EvidenceClaim> claimSource,
            Set<String> enabledCategories) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Evidence commit must be off main");
        synchronized (SESSION_LOCK) {
            restoreActiveSession();
            if (expected == null || activeStamp != expected || sessionStartMs == 0
                    || preferences.getLong(KEY_ACTIVE_SESSION_START, 0) != sessionStartMs) {
                return new EvidenceRecord(EvidenceStatus.REJECTED, 0, null);
            }
            CensorEngine.EvidenceClaim claim = claimSource.get();
            if (claim == null) return new EvidenceRecord(EvidenceStatus.REJECTED, 0, null);
            List<SceneEngine.AcceptedSubject> delivered = claim.takeFor(expected);
            if (delivered == null) return new EvidenceRecord(EvidenceStatus.REJECTED, 0, null);
            int count = delivered.size();
            if (count == 0) return new EvidenceRecord(EvidenceStatus.NO_NEW_EVIDENCE, 0, null);
            int nextBlocks = Math.addExact(sessionBlocks, count);
            long total = Math.addExact(preferences.getLong(KEY_TOTAL_BLOCKS, 0), count);
            SharedPreferences.Editor edit = preferences.edit()
                    .putInt(KEY_ACTIVE_SESSION_BLOCKS, nextBlocks).putLong(KEY_TOTAL_BLOCKS, total);
            if (nextBlocks > preferences.getInt(KEY_PEAK_SESSION_BLOCKS, 0)) edit.putInt(KEY_PEAK_SESSION_BLOCKS, nextBlocks);
            Set<String> dates = mutableSet(KEY_ACTIVE_DATES);
            if (dates.add(today())) edit.putStringSet(KEY_ACTIVE_DATES, dates);
            if (enabledCategories != null && enabledCategories.containsAll(ALL_CATEGORIES)) {
                edit.putLong(KEY_ALL_CATEGORIES_CENSORS,
                        Math.addExact(preferences.getLong(KEY_ALL_CATEGORIES_CENSORS, 0), count));
            }
            edit.putLong(DailyStatsStore.STAMP, System.currentTimeMillis())
                    .putString(DailyStatsStore.ZONE, java.time.ZoneId.systemDefault().getId());
            boolean acknowledged;
            try { acknowledged = edit.commit(); }
            finally {
                // Android may publish the preference memory before a false/uncertain disk result.
                // Keep the process counter/history coherent with that observed memory, not a
                // fabricated rollback or durability acknowledgement. The claim remains consumed.
                sessionBlocks = Math.max(0, preferences.getInt(KEY_ACTIVE_SESSION_BLOCKS, 0));
            }
            if (!acknowledged) return new EvidenceRecord(EvidenceStatus.UNACKNOWLEDGED, 0, null);
            syncDaily();
            return new EvidenceRecord(EvidenceStatus.COMMITTED, count, load());
        }
    }

    public void endSession() {
        synchronized (SESSION_LOCK) {
            restoreActiveSession();
            if (sessionStartMs == 0) return;
            long started = sessionStartMs;
            long durationSeconds = Math.max(0, (System.currentTimeMillis() - started) / 1000);
            SharedPreferences.Editor edit = preferences.edit()
                    .putLong(KEY_TOTAL_SESSION_TIME,
                            preferences.getLong(KEY_TOTAL_SESSION_TIME, 0) + durationSeconds)
                    .putLong(KEY_TOTAL_PROTECTED_TIME,
                            preferences.getLong(KEY_TOTAL_PROTECTED_TIME, 0) + durationSeconds);
            if (durationSeconds > preferences.getLong(KEY_LONGEST_SESSION, 0)) {
                edit.putLong(KEY_LONGEST_SESSION, durationSeconds);
            }
            edit.apply();
            appendSessionHistory(new SessionEntry(started, durationSeconds, sessionBlocks,
                    activeLong(KEY_ACTIVE_SESSION_LIMITED_APP_MILLIS),
                    activeLong(KEY_ACTIVE_SESSION_LIMIT_INTERVENTIONS),
                    activeLong(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS),
                    activeLong(KEY_ACTIVE_SESSION_TRIBUTE_CENTS),
                    activeLong(KEY_ACTIVE_SESSION_SUBLIMINALS),
                    activeLong(KEY_ACTIVE_SESSION_POPUP_IMPRESSIONS),
                    activeLong(KEY_ACTIVE_SESSION_TAMPER_EVENTS)));
            preferences.edit().remove(KEY_ACTIVE_SESSION_START)
                    .remove(KEY_ACTIVE_SESSION_BLOCKS)
                    .remove(KEY_ACTIVE_SESSION_SUBLIMINALS)
                    .remove(KEY_ACTIVE_SESSION_LIMITED_APP_MILLIS)
                    .remove(KEY_ACTIVE_SESSION_LIMIT_INTERVENTIONS)
                    .remove(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS)
                    .remove(KEY_ACTIVE_SESSION_TRIBUTE_CENTS)
                    .remove(KEY_ACTIVE_SESSION_TRIBUTE_CENTS + "_USD")
                    .remove(KEY_ACTIVE_SESSION_TAMPER_EVENTS)
                    .remove(KEY_ACTIVE_SESSION_POPUP_IMPRESSIONS).apply();
            sessionStartMs = 0;
            sessionBlocks = 0;
            activeStamp = null;
            SEEN_TRACK_IDS.clear();
            DailyDurationRecorder.changed();
        }
    }

    public StatsSnapshot load() {
        synchronized (SESSION_LOCK) {
            restoreActiveSession();
            long protectedSeconds = preferences.getLong(KEY_TOTAL_PROTECTED_TIME, 0);
            long totalSessionSeconds = preferences.getLong(KEY_TOTAL_SESSION_TIME, 0);
            long currentSessionSeconds = 0;
            if (sessionStartMs > 0) {
                currentSessionSeconds = Math.max(0,
                        (System.currentTimeMillis() - sessionStartMs) / 1000);
                protectedSeconds += currentSessionSeconds;
                totalSessionSeconds += currentSessionSeconds;
            }
            return new StatsSnapshot(preferences.getLong(KEY_TOTAL_BLOCKS, 0),
                    totalSessionSeconds, preferences.getInt(KEY_SESSIONS_COUNT, 0),
                    preferences.getInt(KEY_CURRENT_STREAK, 0),
                    preferences.getString(KEY_LAST_SESSION_DATE, ""), protectedSeconds,
                    sessionBlocks, preferences.getInt(KEY_PEAK_SESSION_BLOCKS, 0),
                    preferences.getLong(KEY_LONGEST_SESSION, 0),
                    preferences.getInt(KEY_BROWSER_SESSIONS, 0),
                    preferences.getInt(KEY_BROWSER_PAGES, 0),
                    getLongCompat(KEY_EXPORTED_IMAGES),
                    preferences.getInt(KEY_CUSTOM_PHRASES_COUNT, 0),
                    preferences.getInt(KEY_CENSOR_STYLE_CHANGES, 0),
                    preferences.getBoolean(KEY_BORDER_COLOR_CHANGED, false),
                    mutableSet(KEY_CENSOR_STYLES_TRIED), mutableSet(KEY_BORDER_EFFECTS_TRIED),
                    mutableSet(KEY_ACTIVE_DATES),
                    preferences.getLong(KEY_ALL_CATEGORIES_CENSORS, 0),
                    currentSessionSeconds,
                    preferences.getLong(KEY_SUBLIMINAL_IMPRESSIONS, 0L),
                    activeLong(KEY_ACTIVE_SESSION_SUBLIMINALS),
                    preferences.getLong(KEY_LIMITED_APP_MILLIS, 0L),
                    activeLong(KEY_ACTIVE_SESSION_LIMITED_APP_MILLIS),
                    preferences.getLong(KEY_LIMIT_INTERVENTIONS, 0L),
                    activeLong(KEY_ACTIVE_SESSION_LIMIT_INTERVENTIONS),
                    preferences.getLong(KEY_TRIBUTE_EVENTS, 0L),
                    activeLong(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS),
                    preferences.getLong(KEY_TRIBUTE_CENTS, 0L),
                    activeLong(KEY_ACTIVE_SESSION_TRIBUTE_CENTS),
                    preferences.getLong(KEY_TAMPER_EVENTS, 0L),
                    activeLong(KEY_ACTIVE_SESSION_TAMPER_EVENTS),
                    preferences.getLong(KEY_POPUP_IMPRESSIONS, 0L),
                    activeLong(KEY_ACTIVE_SESSION_POPUP_IMPRESSIONS));
        }
    }

    private void restoreActiveSession() {
        if (sessionStartMs != 0) return;
        long stored = preferences.getLong(KEY_ACTIVE_SESSION_START, 0);
        long now = System.currentTimeMillis();
        // Discard impossible/corrupt timestamps instead of displaying an unbounded timer.
        if (stored <= 0 || stored > now
                || now - stored > 366L * 24L * 60L * 60L * 1000L) {
            if (stored != 0) preferences.edit().remove(KEY_ACTIVE_SESSION_START)
                    .remove(KEY_ACTIVE_SESSION_BLOCKS).apply();
            return;
        }
        sessionStartMs = stored;
        sessionBlocks = Math.max(0, preferences.getInt(KEY_ACTIVE_SESSION_BLOCKS, 0));
        activeStamp = new SessionStamp();
    }

    public List<SessionEntry> getSessionHistory() {
        return parseSessionHistory(preferences.getString(KEY_SESSION_HISTORY, ""));
    }

    static List<SessionEntry> parseSessionHistory(String raw) {
        List<SessionEntry> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return result;
        for (String encoded : raw.split(";")) {
            String[] parts = encoded.split(",", -1);
            try {
                boolean versionTwo = parts.length == 11 && "2".equals(parts[0]);
                if (!versionTwo && parts.length != 3) continue;
                int offset = versionTwo ? 1 : 0;
                long start = Long.parseLong(parts[offset]);
                long duration = Long.parseLong(parts[offset + 1]);
                int blocks = Integer.parseInt(parts[offset + 2]);
                if (start > 0 && duration >= 0 && blocks >= 0) {
                    result.add(versionTwo
                            ? new SessionEntry(start, duration, blocks,
                            Long.parseLong(parts[4]), Long.parseLong(parts[5]),
                            Long.parseLong(parts[6]), Long.parseLong(parts[7]),
                            Long.parseLong(parts[8]), Long.parseLong(parts[9]),
                            Long.parseLong(parts[10]))
                            : new SessionEntry(start, duration, blocks));
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed legacy entries without discarding the remaining history.
            }
        }
        return Collections.unmodifiableList(result);
    }

    public void recordCensorStyleTried(String style) { addToSet(KEY_CENSOR_STYLES_TRIED, style); }
    public void recordBorderEffectTried(String effect) { addToSet(KEY_BORDER_EFFECTS_TRIED, effect); }
    public void incrementCensorStyleChanges() {
        preferences.edit().putInt(KEY_CENSOR_STYLE_CHANGES,
                preferences.getInt(KEY_CENSOR_STYLE_CHANGES, 0) + 1).apply();
    }
    public void setBorderColorChanged() {
        preferences.edit().putBoolean(KEY_BORDER_COLOR_CHANGED, true).apply();
    }
    public void setCustomPhrasesCount(int count) {
        preferences.edit().putInt(KEY_CUSTOM_PHRASES_COUNT, Math.max(0, count)).apply();
    }
    public void addExportedImages(int count) {
        if (count <= 0) return;
        preferences.edit().putLong(KEY_EXPORTED_IMAGES,
                getLongCompat(KEY_EXPORTED_IMAGES) + count).apply();
    }
    public void recordSubliminalImpression() {
        synchronized (SESSION_LOCK) {
            restoreActiveSession();
            if (sessionStartMs == 0) return;
            writeDaily(preferences.edit()
                    .putLong(KEY_SUBLIMINAL_IMPRESSIONS,
                            preferences.getLong(KEY_SUBLIMINAL_IMPRESSIONS, 0L) + 1L)
                    .putLong(KEY_ACTIVE_SESSION_SUBLIMINALS,
                            preferences.getLong(KEY_ACTIVE_SESSION_SUBLIMINALS, 0L) + 1L)
                    );
        }
    }
    public void recordLimitedAppUsage(long elapsedMillis) {
        incrementDuringSession(KEY_LIMITED_APP_MILLIS,
                KEY_ACTIVE_SESSION_LIMITED_APP_MILLIS, elapsedMillis);
    }
    public void recordLimitIntervention() {
        incrementDuringSession(KEY_LIMIT_INTERVENTIONS,
                KEY_ACTIVE_SESSION_LIMIT_INTERVENTIONS, 1L);
    }
    public void recordTributeEvent(int amountCents, boolean tamper) {
        recordTributeEvent(amountCents, tamper, "EUR");
    }

    private static String currencyKey(String key, String currency) {
        com.subhub.app.penance.WalletCurrency.requireSupported(currency);
        return "EUR".equals(currency) ? key : key + "_" + currency;
    }

    public long tributeCents(String currency, boolean currentSession) {
        synchronized (SESSION_LOCK) {
            restoreActiveSession();
            String key = currencyKey(currentSession ? KEY_ACTIVE_SESSION_TRIBUTE_CENTS
                    : KEY_TRIBUTE_CENTS, currency);
            return currentSession ? activeLong(key) : preferences.getLong(key, 0L);
        }
    }

    public void recordTributeEvent(int amountCents, boolean tamper, String currency) {
        String totalKey = currencyKey(KEY_TRIBUTE_CENTS, currency);
        String sessionKey = currencyKey(KEY_ACTIVE_SESSION_TRIBUTE_CENTS, currency);
        if (amountCents <= 0) return;
        synchronized (SESSION_LOCK) {
            restoreActiveSession();
            if (sessionStartMs == 0) return;
            SharedPreferences.Editor editor = preferences.edit()
                    .putLong(KEY_TRIBUTE_EVENTS,
                            safeAdd(preferences.getLong(KEY_TRIBUTE_EVENTS, 0L), 1L))
                    .putLong(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS,
                            safeAdd(activeLong(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS), 1L))
                    .putLong(totalKey,
                            safeAdd(preferences.getLong(totalKey, 0L), amountCents))
                    .putLong(sessionKey,
                            safeAdd(activeLong(sessionKey), amountCents));
            if (tamper) {
                editor.putLong(KEY_TAMPER_EVENTS,
                                safeAdd(preferences.getLong(KEY_TAMPER_EVENTS, 0L), 1L))
                        .putLong(KEY_ACTIVE_SESSION_TAMPER_EVENTS,
                                safeAdd(activeLong(KEY_ACTIVE_SESSION_TAMPER_EVENTS), 1L));
            }
            writeDaily(editor);
        }
    }
    public void recordPopupImpression() {
        incrementDuringSession(KEY_POPUP_IMPRESSIONS,
                KEY_ACTIVE_SESSION_POPUP_IMPRESSIONS, 1L);
    }
    private void updateStreak() {
        String today = today();
        String previous = preferences.getString(KEY_LAST_SESSION_DATE, "");
        int streak;
        if (today.equals(previous)) streak = preferences.getInt(KEY_CURRENT_STREAK, 0);
        else if (LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                .equals(previous)) streak = preferences.getInt(KEY_CURRENT_STREAK, 0) + 1;
        else streak = 1;
        preferences.edit().putInt(KEY_CURRENT_STREAK, streak)
                .putString(KEY_LAST_SESSION_DATE, today).apply();
    }

    private void appendSessionHistory(SessionEntry entry) {
        List<String> entries = new ArrayList<>();
        String raw = preferences.getString(KEY_SESSION_HISTORY, "");
        if (raw != null) for (String item : raw.split(";")) {
            if (!item.trim().isEmpty()) entries.add(item);
        }
        entries.add(String.join(",", "2", Long.toString(entry.startMillis),
                Long.toString(entry.durationSeconds), Integer.toString(entry.blocks),
                Long.toString(entry.limitedAppMillis),
                Long.toString(entry.limitInterventions),
                Long.toString(entry.tributeEvents), Long.toString(entry.tributeCents),
                Long.toString(entry.subliminals), Long.toString(entry.popupImpressions),
                Long.toString(entry.tamperEvents)));
        while (entries.size() > SESSION_HISTORY_MAX) entries.remove(0);
        preferences.edit().putString(KEY_SESSION_HISTORY, String.join(";", entries)).apply();
    }

    private void addToSet(String key, String value) {
        if (value == null || value.trim().isEmpty()) return;
        Set<String> values = mutableSet(key);
        if (values.add(value.trim())) preferences.edit().putStringSet(key, values).apply();
    }

    private Set<String> mutableSet(String key) {
        Set<String> stored = preferences.getStringSet(key, Collections.emptySet());
        return new LinkedHashSet<>(stored == null ? Collections.emptySet() : stored);
    }

    private long getLongCompat(String key) {
        Object value = preferences.getAll().get(key);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private long activeLong(String key) {
        return Math.max(0L, preferences.getLong(key, 0L));
    }

    private void incrementDuringSession(String totalKey, String activeKey, long amount) {
        if (amount <= 0L) return;
        synchronized (SESSION_LOCK) {
            restoreActiveSession();
            if (sessionStartMs == 0) return;
            writeDaily(preferences.edit()
                    .putLong(totalKey, safeAdd(preferences.getLong(totalKey, 0L), amount))
                    .putLong(activeKey, safeAdd(activeLong(activeKey), amount))
                    );
        }
    }

    /**
     * A committed source ledger event always contributes to lifetime totals, but never to a
     * different newly armed session. No historical session is guessed from a wall-clock timestamp.
     */
    public void recordSourceTribute(SessionStamp expected, int amountCents, String currency) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Source tribute commit must be off main");
        if (expected == null || amountCents <= 0) return;
        synchronized (SESSION_LOCK) {
            restoreActiveSession();
            String totalKey = currencyKey(KEY_TRIBUTE_CENTS, currency);
            SharedPreferences.Editor edit = preferences.edit()
                    .putLong(KEY_TRIBUTE_EVENTS, safeAdd(preferences.getLong(KEY_TRIBUTE_EVENTS, 0), 1))
                    .putLong(totalKey, safeAdd(preferences.getLong(totalKey, 0), amountCents));
            if (activeStamp == expected && sessionStartMs != 0) {
                String sessionKey = currencyKey(KEY_ACTIVE_SESSION_TRIBUTE_CENTS, currency);
                edit.putLong(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS, safeAdd(activeLong(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS), 1))
                        .putLong(sessionKey, safeAdd(activeLong(sessionKey), amountCents));
            }
            edit.putLong(DailyStatsStore.STAMP, System.currentTimeMillis())
                    .putString(DailyStatsStore.ZONE, java.time.ZoneId.systemDefault().getId());
            if (!edit.commit()) throw new IllegalStateException("Source tribute store unacknowledged");
            syncDaily();
        }
    }

    private void writeDaily(SharedPreferences.Editor editor) {
        editor.putLong(DailyStatsStore.STAMP, System.currentTimeMillis())
                .putString(DailyStatsStore.ZONE, java.time.ZoneId.systemDefault().getId()).apply();
        syncDaily();
    }
    private void syncDaily() {
        try { DailyStatsStore.get(app).queueStats(preferences); }
        catch (RuntimeException unavailable) { android.util.Log.w("DailyStats", "Counter checkpoint pending"); }
    }
    /** Reconcile completed photo jobs without double-counting them after a process restart. */
    public void reconcileExportedPhotos(long completed) {
        synchronized (SESSION_LOCK) {
            long previous = preferences.getLong("export_job_photos_seen", 0);
            if (completed <= previous) return;
            preferences.edit().putLong(KEY_EXPORTED_IMAGES, safeAdd(getLongCompat(KEY_EXPORTED_IMAGES), completed - previous))
                    .putLong("export_job_photos_seen", completed).commit();
        }
    }

    private static long safeAdd(long current, long amount) {
        return current > Long.MAX_VALUE - amount ? Long.MAX_VALUE : current + amount;
    }

    private static List<Integer> trackIds(List<TrackedObject> tracks) {
        List<Integer> ids = new ArrayList<>(tracks.size());
        // Render immediately, but only count a region after temporal confirmation. This keeps
        // single-frame detector noise and repeated screenshot refreshes out of stats and money.
        for (TrackedObject track : tracks) {
            if (track.isConfirmed() && track.getFramesMissing() == 0) ids.add(track.getId());
        }
        return ids;
    }
    private static String today() {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static final class SessionEntry {
        private final long startMillis;
        private final long durationSeconds;
        private final int blocks;
        private final long limitedAppMillis;
        private final long limitInterventions;
        private final long tributeEvents;
        private final long tributeCents;
        private final long subliminals;
        private final long popupImpressions;
        private final long tamperEvents;
        public SessionEntry(long startMillis, long durationSeconds, int blocks) {
            this(startMillis, durationSeconds, blocks, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
        public SessionEntry(long startMillis, long durationSeconds, int blocks,
                long limitedAppMillis, long limitInterventions, long tributeEvents,
                long tributeCents, long subliminals, long popupImpressions,
                long tamperEvents) {
            this.startMillis = startMillis;
            this.durationSeconds = durationSeconds;
            this.blocks = blocks;
            this.limitedAppMillis = Math.max(0L, limitedAppMillis);
            this.limitInterventions = Math.max(0L, limitInterventions);
            this.tributeEvents = Math.max(0L, tributeEvents);
            this.tributeCents = Math.max(0L, tributeCents);
            this.subliminals = Math.max(0L, subliminals);
            this.popupImpressions = Math.max(0L, popupImpressions);
            this.tamperEvents = Math.max(0L, tamperEvents);
        }
        public long getStartMillis() { return startMillis; }
        public long getDurationSeconds() { return durationSeconds; }
        public int getBlocks() { return blocks; }
        public long getLimitedAppMillis() { return limitedAppMillis; }
        public long getLimitInterventions() { return limitInterventions; }
        public long getTributeEvents() { return tributeEvents; }
        public long getTributeCents() { return tributeCents; }
        public long getSubliminals() { return subliminals; }
        public long getPopupImpressions() { return popupImpressions; }
        public long getTamperEvents() { return tamperEvents; }
        public long getActivityEvents() {
            return blocks + limitInterventions + tributeEvents + subliminals + popupImpressions;
        }
    }
}
