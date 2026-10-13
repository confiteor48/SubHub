package com.subhub.app.penance;

import android.content.Context;
import android.content.SharedPreferences;

import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.settings.FeatureModuleManager;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Local, bounded penance ledger. Detection can create debt entries, but this class never initiates
 * a network request or payment by itself.
 */
public final class PenanceManager {
    public static final String PREFS_NAME = "subhub_penance";
    public static final int DEFAULT_STRIKE_CENTS = 100;
    public static final int DEFAULT_DAILY_CAP_CENTS = 500;
    public static final int DEFAULT_WEEKLY_CAP_CENTS = 2_000;
    public static final int DEFAULT_MERCY_MINUTES = 10;
    public static final int DEFAULT_DWELL_SECONDS = 10;
    public static final int DEFAULT_DETECTION_BATCH = 1;
    public static final int DEFAULT_TAMPER_COOLDOWN_MINUTES = 5;
    public static final int MIN_TAMPER_COOLDOWN_MINUTES = 1;
    public static final int MAX_TAMPER_COOLDOWN_MINUTES = 1_440;
    public static final String CURRENCY = "EUR";
    private static final String KEY_CURRENCY = "wallet_currency";
    private static final String KEY_ENABLED = "enabled";
    public static final String KEY_RULE_PARTICIPATION_MIGRATED = "rule_participation_migrated_v1";
    private static final String KEY_STRIKE_CENTS = "strike_cents";
    private static final String KEY_DAILY_CAP_CENTS = "daily_cap_cents";
    private static final String KEY_WEEKLY_CAP_CENTS = "weekly_cap_cents";
    private static final String KEY_MERCY_MINUTES = "mercy_minutes";
    private static final String KEY_DWELL_SECONDS = "dwell_seconds";
    private static final String KEY_DETECTION_BATCH = "detection_batch";
    private static final String KEY_DETECTION_REMAINDER = "detection_batch_remainder";
    private static final String KEY_TAMPER_COOLDOWN_MINUTES = "tamper_cooldown_minutes";
    private static final String KEY_LAST_TAMPER_AT = "last_tamper_at";
    private static final String KEY_EVENTS = "events_v1";
    private static final String KEY_TOTAL_PAID_CENTS = "total_paid_cents_v1";

    public String getCurrency() {
        String code = preferences.getString(KEY_CURRENCY, CURRENCY);
        return WalletCurrency.isSupported(code) ? code : CURRENCY;
    }

    public String money(long cents) { return WalletCurrency.format(getCurrency(), cents); }

    /** No unpaid balance or in-flight payment may be re-denominated. */
    public boolean changeCurrency(String code) {
        WalletCurrency.requireSupported(code);
        synchronized (LOCK) {
            if (code.equals(getCurrency())) return true;
            if (!getActiveOrderId().isEmpty() || !getActiveSettlementId().isEmpty()
                    || getActiveCheckoutMode() != CheckoutMode.NONE) return false;
            for (PenanceEvent event : loadEvents()) {
                if (event.getStatus() == PenanceEvent.Status.OPEN
                        || event.getStatus() == PenanceEvent.Status.CHECKOUT) return false;
            }
            // Preserve a legacy denomination's lifetime sum before later history trimming.
            totalPaidCentsLocked(loadEvents());
            // Consent must be reviewed again in the new denomination before scheduling resumes.
            new HardcoreAutoPayManager(context).disable();
            return preferences.edit().putString(KEY_CURRENCY, code)
                    .putInt(KEY_DETECTION_REMAINDER, 0).commit();
        }
    }

    private List<PenanceEvent> currentCurrencyEvents(List<PenanceEvent> events) {
        List<PenanceEvent> selected = new ArrayList<>();
        for (PenanceEvent event : events) {
            if (getCurrency().equals(event.getCurrency())) selected.add(event);
        }
        return selected;
    }

    private String totalPaidKey() {
        return totalPaidKey(getCurrency());
    }

    private static String totalPaidKey(String currency) {
        return CURRENCY.equals(currency) ? KEY_TOTAL_PAID_CENTS
                : KEY_TOTAL_PAID_CENTS + "_" + currency;
    }
    private static final String LEGACY_KEY_BACKEND_URL = "paypal_backend_url";
    public static final String KEY_PAYPAL_LINK = "paypal_payment_link";
    private static final String KEY_ORDER_ID = "active_order_id";
    private static final String KEY_APPROVAL_URL = "active_approval_url";
    private static final String KEY_PAYPAL_BOUNDARY = "active_paypal_boundary";
    private static final String KEY_CHECKOUT_MODE = "active_checkout_mode";
    private static final int MAX_EVENTS = 200;
    private static final Object LOCK = new Object();

    private final SharedPreferences preferences;
    private final FeatureModuleManager modules;
    private final Context context;

    public PenanceManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        modules = new FeatureModuleManager(this.context);
        migrateRuleParticipation();
    }

    public boolean isEnabled() {
        for (PenanceInfraction rule : PenanceInfraction.values())
            if (rule != PenanceInfraction.PAID_PAUSE && isInfractionEnabled(rule)) return true;
        return new PaidPauseManager(context).isEnabled();
    }

    /** Preserve effective off states without retaining a second switch or changing saved costs. */
    private void migrateRuleParticipation() {
        synchronized (LOCK) {
            SharedPreferences global = context.getSharedPreferences(
                    com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
            if (!preferences.getBoolean(KEY_RULE_PARTICIPATION_MIGRATED, false)) {
                boolean wasEnabled = preferences.getBoolean(KEY_ENABLED, false)
                        && global.getBoolean(FeatureModuleManager.KEY_WALLET_ENABLED, true);
                SharedPreferences.Editor edit = preferences.edit()
                        .putBoolean(KEY_RULE_PARTICIPATION_MIGRATED, true).remove(KEY_ENABLED);
                if (!wasEnabled) {
                    for (PenanceInfraction rule : PenanceInfraction.values())
                        edit.putBoolean(ruleEnabledKey(rule), false);
                    edit.putBoolean(PaidPauseManager.KEY_ENABLED, false)
                            .putInt(KEY_DETECTION_REMAINDER, 0);
                }
                edit.apply();
            }
        }
    }

    public int getStrikeCents() {
        return getInfractionCents(PenanceInfraction.NEW_DETECTION);
    }

    public boolean isInfractionEnabled(PenanceInfraction infraction) {
        String key = ruleEnabledKey(infraction);
        if (preferences.contains(key)) {
            return preferences.getBoolean(key, infraction.enabledByDefault());
        }
        return infraction == PenanceInfraction.NEW_DETECTION
                ? infraction.enabledByDefault() : false;
    }

    /** Turning a rule off must not depend on validating unrelated editor drafts. */
    public void disableInfraction(PenanceInfraction infraction) {
        synchronized (LOCK) {
            SharedPreferences.Editor edit = preferences.edit().putBoolean(ruleEnabledKey(infraction), false);
            if (infraction == PenanceInfraction.NEW_DETECTION) edit.putInt(KEY_DETECTION_REMAINDER, 0);
            edit.apply();
        }
    }

    public int getInfractionCents(PenanceInfraction infraction) {
        String key = ruleCentsKey(infraction);
        int fallback = infraction == PenanceInfraction.NEW_DETECTION
                ? preferences.getInt(KEY_STRIKE_CENTS, DEFAULT_STRIKE_CENTS)
                : infraction.defaultCents();
        return PenancePolicy.clampStrikeCents(preferences.getInt(key, fallback));
    }

    public int getDailyCapCents() {
        return preferences.getInt(KEY_DAILY_CAP_CENTS, DEFAULT_DAILY_CAP_CENTS);
    }

    public int getWeeklyCapCents() {
        return preferences.getInt(KEY_WEEKLY_CAP_CENTS, DEFAULT_WEEKLY_CAP_CENTS);
    }

    public int getMercyMinutes() {
        return preferences.getInt(KEY_MERCY_MINUTES, DEFAULT_MERCY_MINUTES);
    }

    public int getDwellSeconds() {
        return PenancePolicy.clampDwellSeconds(
                preferences.getInt(KEY_DWELL_SECONDS, DEFAULT_DWELL_SECONDS));
    }

    public int getDetectionBatch() {
        return PenancePolicy.clampDetectionBatch(
                preferences.getInt(KEY_DETECTION_BATCH, DEFAULT_DETECTION_BATCH));
    }

    public int getDetectionRemainder() {
        return Math.max(0, Math.min(getDetectionBatch() - 1,
                preferences.getInt(KEY_DETECTION_REMAINDER, 0)));
    }

    public int getTamperCooldownMinutes() {
        return clampTamperCooldown(preferences.getInt(
                KEY_TAMPER_COOLDOWN_MINUTES, DEFAULT_TAMPER_COOLDOWN_MINUTES));
    }

    public int getDailyRemainingCents(long nowMillis) {
        synchronized (LOCK) {
            int used = PenancePolicy.periodTotal(
                    currentCurrencyEvents(loadEvents()), nowMillis, ZoneId.systemDefault(), true);
            return Math.max(0, getDailyCapCents() - used);
        }
    }

    public int getWeeklyRemainingCents(long nowMillis) {
        synchronized (LOCK) {
            int used = PenancePolicy.periodTotal(
                    currentCurrencyEvents(loadEvents()), nowMillis, ZoneId.systemDefault(), false);
            return Math.max(0, getWeeklyCapCents() - used);
        }
    }

    public String getPayPalLink() {
        String value = preferences.getString(KEY_PAYPAL_LINK, "");
        return value == null ? "" : value.trim();
    }

    public void savePayPalLink(String value) {
        preferences.edit().putString(KEY_PAYPAL_LINK,
                value == null ? "" : value.trim()).remove(LEGACY_KEY_BACKEND_URL).commit();
    }

    public void configure(boolean enabled, int strikeCents, int dailyCapCents,
            int weeklyCapCents, int mercyMinutes) {
        Map<PenanceInfraction, Integer> rules = new EnumMap<>(PenanceInfraction.class);
        rules.put(PenanceInfraction.NEW_DETECTION, strikeCents);
        configure(enabled, rules, dailyCapCents, weeklyCapCents, mercyMinutes,
                DEFAULT_DWELL_SECONDS, DEFAULT_DETECTION_BATCH);
    }

    public void configure(boolean enabled, Map<PenanceInfraction, Integer> enabledRuleCosts,
            int dailyCapCents, int weeklyCapCents, int mercyMinutes, int dwellSeconds) {
        configure(enabled, enabledRuleCosts, dailyCapCents, weeklyCapCents, mercyMinutes,
                dwellSeconds, getDetectionBatch());
    }

    public void configure(boolean enabled, Map<PenanceInfraction, Integer> enabledRuleCosts,
            int dailyCapCents, int weeklyCapCents, int mercyMinutes, int dwellSeconds,
            int detectionBatch) {
        configure(enabled, enabledRuleCosts, dailyCapCents, weeklyCapCents, mercyMinutes,
                dwellSeconds, detectionBatch, getTamperCooldownMinutes());
    }

    public void configure(boolean enabled, Map<PenanceInfraction, Integer> enabledRuleCosts,
            int dailyCapCents, int weeklyCapCents, int mercyMinutes, int dwellSeconds,
            int detectionBatch, int tamperCooldownMinutes) {
        Map<PenanceInfraction, Integer> rules = enabledRuleCosts == null
                ? Collections.emptyMap() : enabledRuleCosts;
        int largestRule = PenancePolicy.MIN_STRIKE_CENTS;
        for (Map.Entry<PenanceInfraction, Integer> entry : rules.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                largestRule = Math.max(largestRule,
                        PenancePolicy.clampStrikeCents(entry.getValue()));
            }
        }
        int boundedDaily = PenancePolicy.clampDailyCapCents(dailyCapCents, largestRule);
        int boundedWeekly = PenancePolicy.clampWeeklyCapCents(weeklyCapCents, boundedDaily);
        int boundedBatch = PenancePolicy.clampDetectionBatch(detectionBatch);
        boolean resetDetectionProgress = !enabled || !isInfractionEnabled(PenanceInfraction.NEW_DETECTION)
                || !rules.containsKey(PenanceInfraction.NEW_DETECTION)
                || boundedBatch != getDetectionBatch();
        SharedPreferences.Editor editor = preferences.edit()
                .putInt(KEY_DAILY_CAP_CENTS, boundedDaily)
                .putInt(KEY_WEEKLY_CAP_CENTS, boundedWeekly)
                .putInt(KEY_MERCY_MINUTES, PenancePolicy.clampMercyMinutes(mercyMinutes))
                .putInt(KEY_DWELL_SECONDS, PenancePolicy.clampDwellSeconds(dwellSeconds))
                .putInt(KEY_DETECTION_BATCH, boundedBatch)
                .putInt(KEY_TAMPER_COOLDOWN_MINUTES,
                        clampTamperCooldown(tamperCooldownMinutes))
                .remove(LEGACY_KEY_BACKEND_URL);
        if (resetDetectionProgress) editor.putInt(KEY_DETECTION_REMAINDER, 0);
        for (PenanceInfraction infraction : PenanceInfraction.values()) {
            boolean ruleEnabled = enabled && rules.containsKey(infraction);
            int cents = rules.containsKey(infraction) && rules.get(infraction) != null
                    ? PenancePolicy.clampStrikeCents(rules.get(infraction))
                    : getInfractionCents(infraction);
            editor.putBoolean(ruleEnabledKey(infraction), ruleEnabled)
                    .putInt(ruleCentsKey(infraction), cents);
            if (infraction == PenanceInfraction.NEW_DETECTION) {
                editor.putInt(KEY_STRIKE_CENTS, cents);
            }
        }
        editor.apply();
    }

    /** Returns the bounded amount added to the ledger in cents. */
    public int recordInfraction(PenanceInfraction infraction, int count, long nowMillis) {
        return recordInfractionResult(infraction, count, nowMillis, null).chargedCents;
    }

    public enum SourceDetectionStatus { REJECTED, APPLIED, ACKNOWLEDGED }
    public static final class SourceDetectionResult {
        public final SourceDetectionStatus status;
        public final int chargedCents;
        private SourceDetectionResult(SourceDetectionStatus status, int chargedCents) {
            this.status = status; this.chargedCents = chargedCents;
        }
    }

    /** Source accounting uses acknowledged local writes; ordinary legacy callers retain apply(). */
    public int recordSourceDetection(int count, long nowMillis, StatsRepository.SessionStamp expected) {
        return recordSourceDetectionResult(count, nowMillis, expected).chargedCents;
    }

    /** Rejection and acknowledged zero-cent batch progress are different outcomes. */
    public SourceDetectionResult recordSourceDetectionResult(int count, long nowMillis, StatsRepository.SessionStamp expected) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            throw new IllegalStateException("Source ledger commit must be off main");
        }
        if (expected == null) return new SourceDetectionResult(SourceDetectionStatus.REJECTED, 0);
        return recordInfractionResult(PenanceInfraction.NEW_DETECTION, count, nowMillis, expected);
    }

    /** One admitted viewport episode; same acknowledged/stamped result without detection batching. */
    public SourceDetectionResult recordSourceDwellResult(long nowMillis, StatsRepository.SessionStamp expected) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            throw new IllegalStateException("Source ledger commit must be off main");
        }
        if (expected == null) return new SourceDetectionResult(SourceDetectionStatus.REJECTED, 0);
        return recordInfractionResult(PenanceInfraction.CENSORED_DWELL, 1, nowMillis, expected);
    }

    private SourceDetectionResult recordInfractionResult(PenanceInfraction infraction, int count, long nowMillis,
            StatsRepository.SessionStamp sourceSession) {
        SourceDetectionResult rejected = new SourceDetectionResult(SourceDetectionStatus.REJECTED, 0);
        SourceDetectionStatus written = sourceSession == null ? SourceDetectionStatus.APPLIED : SourceDetectionStatus.ACKNOWLEDGED;
        if ((infraction == PenanceInfraction.NEW_DETECTION
                || infraction == PenanceInfraction.CENSORED_DWELL
                || infraction == PenanceInfraction.CENSORED_TAP) && !modules.isCensorEnabled()) return rejected;
        if (infraction == null || !isEnabled() || !isInfractionEnabled(infraction)
                || !new AppModeManager(context).isArmed() || count <= 0) return rejected;
        synchronized (LOCK) {
            if (sourceSession != null && !sourceSession.isCurrent()) return rejected;
            SharedPreferences.Editor acknowledged = sourceSession == null ? null : preferences.edit();
            if (infraction == PenanceInfraction.TAMPER_ATTEMPT) {
                HardcoreModeManager hardcore = new HardcoreModeManager(context);
                if (!hardcore.isRequested()) return rejected;
                long last = preferences.getLong(KEY_LAST_TAMPER_AT, 0L);
                long cooldown = getTamperCooldownMinutes() * 60_000L;
                if (last > 0L && nowMillis >= last && nowMillis - last < cooldown) return rejected;
            }
            int billableCount = count;
            if (infraction == PenanceInfraction.NEW_DETECTION) {
                int batch = getDetectionBatch();
                int accumulated = preferences.getInt(KEY_DETECTION_REMAINDER, 0) + count;
                billableCount = accumulated / batch;
                if (acknowledged == null) preferences.edit().putInt(KEY_DETECTION_REMAINDER, accumulated % batch).apply();
                else acknowledged.putInt(KEY_DETECTION_REMAINDER, accumulated % batch);
                if (billableCount <= 0) { acknowledge(acknowledged); return new SourceDetectionResult(written, 0); }
            }
            List<PenanceEvent> events = loadEvents();
            trimHistoryForInsertion(events);
            if (events.size() >= MAX_EVENTS) { acknowledge(acknowledged); return new SourceDetectionResult(written, 0); }
            int amount = PenancePolicy.boundedCharge(currentCurrencyEvents(events), nowMillis, billableCount,
                    getInfractionCents(infraction), getDailyCapCents(), getWeeklyCapCents(),
                    ZoneId.systemDefault());
            if (amount <= 0) { acknowledge(acknowledged); return new SourceDetectionResult(written, 0); }
            long mercyEnds = nowMillis + getMercyMinutes() * 60_000L;
            events.add(new PenanceEvent(UUID.randomUUID().toString(), nowMillis, mercyEnds,
                    amount, billableCount, infraction, PenanceEvent.Status.OPEN, "", getCurrency()));
            if (acknowledged == null) {
                saveEvents(events);
                new StatsRepository(context).recordTributeEvent(amount,
                        infraction == PenanceInfraction.TAMPER_ATTEMPT, getCurrency());
            } else {
                acknowledge(acknowledged.putString(KEY_EVENTS, encodeEvents(events)));
                new StatsRepository(context).recordSourceTribute(sourceSession, amount, getCurrency());
            }
            if (infraction == PenanceInfraction.TAMPER_ATTEMPT) {
                preferences.edit().putLong(KEY_LAST_TAMPER_AT, nowMillis).apply();
            }
            HardcoreAutoPayManager.schedule(context);
            return new SourceDetectionResult(written, amount);
        }
    }

    private static void acknowledge(SharedPreferences.Editor edit) {
        if (edit != null && !edit.commit()) throw new IllegalStateException("Source ledger store unacknowledged");
    }

    private static int clampTamperCooldown(int minutes) {
        return Math.max(MIN_TAMPER_COOLDOWN_MINUTES,
                Math.min(MAX_TAMPER_COOLDOWN_MINUTES, minutes));
    }

    /** Compatibility entry point for the original new-detection rule. */
    public int recordStrikes(int strikes, long nowMillis) {
        return recordInfraction(PenanceInfraction.NEW_DETECTION, strikes, nowMillis);
    }

    /** Adds one immediate, fixed-price pause purchase without applying infraction caps. */
    public boolean requestPaidPause(long nowMillis) {
        PaidPauseManager pause = new PaidPauseManager(context);
        if (!isEnabled() || !pause.canPurchase()) return false;
        synchronized (LOCK) {
            List<PenanceEvent> events = loadEvents();
            for (PenanceEvent event : events) {
                if (event.getInfraction() == PenanceInfraction.PAID_PAUSE
                        && (event.getStatus() == PenanceEvent.Status.OPEN
                        || event.getStatus() == PenanceEvent.Status.CHECKOUT)) {
                    return true;
                }
            }
            trimHistoryForInsertion(events);
            if (events.size() >= MAX_EVENTS) return false;
            events.add(new PenanceEvent(UUID.randomUUID().toString(), nowMillis, nowMillis,
                    pause.getPriceCents(), 1, PenanceInfraction.PAID_PAUSE,
                    PenanceEvent.Status.OPEN, "", getCurrency()));
            saveEvents(events);
            new StatsRepository(context).recordTributeEvent(pause.getPriceCents(), false, getCurrency());
            return true;
        }
    }

    private static String ruleEnabledKey(PenanceInfraction infraction) {
        return "rule_" + infraction.preferenceKey() + "_enabled";
    }

    private static String ruleCentsKey(PenanceInfraction infraction) {
        return "rule_" + infraction.preferenceKey() + "_cents";
    }

    public PenanceSnapshot snapshot(long nowMillis) {
        synchronized (LOCK) {
            List<PenanceEvent> events = loadEvents();
            int due = 0;
            int mercy = 0;
            int checkout = 0;
            int paid = 0;
            for (PenanceEvent event : events) {
                if (!getCurrency().equals(event.getCurrency())) continue;
                if (event.isDue(nowMillis)) due += event.getAmountCents();
                else if (event.isInMercy(nowMillis)) mercy += event.getAmountCents();
                else if (event.getStatus() == PenanceEvent.Status.CHECKOUT) {
                    checkout += event.getAmountCents();
                } else if (event.getStatus() == PenanceEvent.Status.PAID) {
                    paid += event.getAmountCents();
                }
            }
            List<PenanceEvent> newestFirst = new ArrayList<>(events);
            newestFirst.sort(Comparator.comparingLong(PenanceEvent::getCreatedAtMillis).reversed());
            return new PenanceSnapshot(isEnabled(), due, mercy, checkout, paid, newestFirst);
        }
    }

    /**
     * Lifetime value of settlements confirmed as paid. Open, grace-period, and checkout entries
     * never contribute. Existing installs migrate from the retained paid ledger the first time
     * this value is read, after which the accumulator survives bounded history trimming.
     */
    public long getTotalPaidCents() {
        synchronized (LOCK) {
            return totalPaidCentsLocked(loadEvents());
        }
    }

    public long getTotalPaidCents(String currency) {
        WalletCurrency.requireSupported(currency);
        synchronized (LOCK) {
            return totalPaidCentsLocked(loadEvents(), currency);
        }
    }

    public boolean forgiveLatestInMercy(long nowMillis) {
        synchronized (LOCK) {
            List<PenanceEvent> events = loadEvents();
            for (int index = events.size() - 1; index >= 0; index--) {
                PenanceEvent event = events.get(index);
                if (event.isInMercy(nowMillis)) {
                    events.set(index, event.withStatus(PenanceEvent.Status.FORGIVEN, ""));
                    saveEvents(events);
                    HardcoreAutoPayManager.schedule(context);
                    return true;
                }
            }
            return false;
        }
    }

    /** Safety action: releases every unpaid ledger entry and cancels any local checkout state. */
    public void forgiveAllUnpaid() {
        synchronized (LOCK) {
            List<PenanceEvent> events = loadEvents();
            for (int index = 0; index < events.size(); index++) {
                PenanceEvent event = events.get(index);
                if (event.getStatus() == PenanceEvent.Status.OPEN
                        || event.getStatus() == PenanceEvent.Status.CHECKOUT) {
                    events.set(index, event.withStatus(PenanceEvent.Status.FORGIVEN, ""));
                }
            }
            saveEvents(events);
            clearOrderState();
            HardcoreAutoPayManager.schedule(context);
        }
    }

    public Settlement beginSettlement(long nowMillis) {
        return beginSettlement(nowMillis, 0);
    }

    Settlement beginAutomaticSettlement(long nowMillis) {
        return beginSettlement(nowMillis, AutoCashoutPolicy.MINIMUM_CENTS);
    }

    private Settlement beginSettlement(long nowMillis, int minimumCents) {
        synchronized (LOCK) {
            List<PenanceEvent> events = loadEvents();
            String existingId = "";
            for (PenanceEvent event : events) {
                if (event.getStatus() == PenanceEvent.Status.CHECKOUT) {
                    existingId = event.getSettlementId();
                }
            }
            if (!existingId.isEmpty()) {
                Settlement existing = settlement(events, existingId);
                // An already-submitted automatic payment must reconcile with the same ID,
                // even if it predates this minimum. Never cancel/recreate an ambiguous charge.
                if (existing != null && existing.getAmountCents() < minimumCents
                        && getActiveCheckoutMode() != CheckoutMode.HARDCORE_AUTO) return null;
                return existing;
            }

            int amount = 0;
            for (PenanceEvent event : events) {
                if (event.isDue(nowMillis)) amount += event.getAmountCents();
            }
            if (amount <= 0 || amount < minimumCents) return null;
            String settlementId = UUID.randomUUID().toString();
            for (int index = 0; index < events.size(); index++) {
                PenanceEvent event = events.get(index);
                if (event.isDue(nowMillis)) {
                    events.set(index, event.withStatus(
                            PenanceEvent.Status.CHECKOUT, settlementId));
                }
            }
            saveEvents(events);
            clearOrderState();
            return settlement(events, settlementId);
        }
    }

    /** Begins an exact-price checkout containing only the pending paid-pause request. */
    public Settlement beginPaidPauseSettlement(long nowMillis) {
        if (!isEnabled() || !new PaidPauseManager(context).canPurchase()) return null;
        synchronized (LOCK) {
            List<PenanceEvent> events = loadEvents();
            for (PenanceEvent event : events) {
                if (event.getStatus() == PenanceEvent.Status.CHECKOUT) {
                    return settlement(events, event.getSettlementId());
                }
            }
            int target = -1;
            for (int index = events.size() - 1; index >= 0; index--) {
                PenanceEvent event = events.get(index);
                if (event.getInfraction() == PenanceInfraction.PAID_PAUSE
                        && event.isDue(nowMillis)) {
                    target = index;
                    break;
                }
            }
            if (target < 0) return null;
            String settlementId = UUID.randomUUID().toString();
            PenanceEvent pause = events.get(target);
            events.set(target, pause.withStatus(
                    PenanceEvent.Status.CHECKOUT, settlementId));
            saveEvents(events);
            clearOrderState();
            return settlement(events, settlementId);
        }
    }

    public void bindOrder(String settlementId, String orderId, String approvalUrl) {
        bindOrder(settlementId, orderId, approvalUrl, "", CheckoutMode.LINK);
    }

    public void bindOrder(String settlementId, String orderId, String approvalUrl,
            String paypalBoundary) {
        bindOrder(settlementId, orderId, approvalUrl, paypalBoundary,
                CheckoutMode.API_APPROVAL);
    }

    private void bindOrder(String settlementId, String orderId, String approvalUrl,
            String paypalBoundary, CheckoutMode mode) {
        if (settlementId == null || settlementId.isEmpty()
                || orderId == null || orderId.isEmpty()) return;
        synchronized (LOCK) {
            List<PenanceEvent> events = loadEvents();
            if (!settlementExists(events, settlementId)
                    || (containsPaidPause(events, settlementId)
                    && !new PaidPauseManager(context).canPurchase())) return;
            preferences.edit().putString(KEY_ORDER_ID, orderId)
                    .putString(KEY_APPROVAL_URL, approvalUrl == null ? "" : approvalUrl)
                    .putString(KEY_PAYPAL_BOUNDARY,
                            paypalBoundary == null ? "" : paypalBoundary)
                    .putString(KEY_CHECKOUT_MODE, mode.name()).apply();
        }
    }

    public void markAutomaticSettlement(String settlementId, String paypalBoundary) {
        if (settlementId == null || settlementId.isEmpty()) return;
        synchronized (LOCK) {
            if (!settlementExists(loadEvents(), settlementId)) return;
            preferences.edit().remove(KEY_ORDER_ID).remove(KEY_APPROVAL_URL)
                    .putString(KEY_PAYPAL_BOUNDARY,
                            paypalBoundary == null ? "" : paypalBoundary)
                    .putString(KEY_CHECKOUT_MODE, CheckoutMode.HARDCORE_AUTO.name()).apply();
        }
    }

    public CheckoutMode getActiveCheckoutMode() {
        try {
            return CheckoutMode.valueOf(preferences.getString(
                    KEY_CHECKOUT_MODE, CheckoutMode.NONE.name()));
        } catch (IllegalArgumentException ignored) {
            return CheckoutMode.NONE;
        }
    }

    public String getActiveOrderId() {
        String value = preferences.getString(KEY_ORDER_ID, "");
        return value == null ? "" : value;
    }

    public String getActiveApprovalUrl() {
        String value = preferences.getString(KEY_APPROVAL_URL, "");
        return value == null ? "" : value;
    }

    public String getActivePayPalBoundary() {
        String value = preferences.getString(KEY_PAYPAL_BOUNDARY, "");
        return value == null ? "" : value;
    }

    public String getActiveSettlementId() {
        synchronized (LOCK) {
            for (PenanceEvent event : loadEvents()) {
                if (event.getStatus() == PenanceEvent.Status.CHECKOUT) {
                    return event.getSettlementId();
                }
            }
            return "";
        }
    }

    public void cancelSettlement(String settlementId) {
        if (settlementId == null || settlementId.isEmpty()) return;
        synchronized (LOCK) {
            List<PenanceEvent> events = loadEvents();
            for (int index = 0; index < events.size(); index++) {
                PenanceEvent event = events.get(index);
                if (event.getStatus() == PenanceEvent.Status.CHECKOUT
                        && settlementId.equals(event.getSettlementId())) {
                    events.set(index, event.withStatus(PenanceEvent.Status.OPEN, ""));
                }
            }
            saveEvents(events);
            clearOrderState();
            HardcoreAutoPayManager.schedule(context);
        }
    }

    public boolean completeSettlement(String settlementId, int paidAmountCents) {
        if (settlementId == null || settlementId.isEmpty() || paidAmountCents <= 0) return false;
        synchronized (LOCK) {
            List<PenanceEvent> events = loadEvents();
            Settlement selected = settlement(events, settlementId);
            if (selected == null || !getCurrency().equals(selected.getCurrency())) return false;
            com.subhub.app.stats.DailyStatsStore daily = com.subhub.app.stats.DailyStatsStore.get(context);
            try { daily.syncWallet(); } catch (RuntimeException unavailable) { android.util.Log.w("DailyStats", "Wallet checkpoint pending"); }
            long paidBeforeSettlement = totalPaidCentsLocked(events);
            int expected = 0;
            boolean activatesPause = false;
            for (PenanceEvent event : events) {
                if (event.getStatus() == PenanceEvent.Status.CHECKOUT
                        && settlementId.equals(event.getSettlementId())) {
                    expected += event.getAmountCents();
                    activatesPause |= event.getInfraction() == PenanceInfraction.PAID_PAUSE;
                }
            }
            if (expected != paidAmountCents) return false;
            if (activatesPause && !new PaidPauseManager(context).activate()) return false;
            for (int index = 0; index < events.size(); index++) {
                PenanceEvent event = events.get(index);
                if (event.getStatus() == PenanceEvent.Status.CHECKOUT
                        && settlementId.equals(event.getSettlementId())) {
                    events.set(index, event.withStatus(
                            PenanceEvent.Status.PAID, settlementId));
                }
            }
            saveEvents(events);
            long updatedTotal = paidBeforeSettlement > Long.MAX_VALUE - paidAmountCents
                    ? Long.MAX_VALUE : paidBeforeSettlement + paidAmountCents;
            preferences.edit().putLong(totalPaidKey(), updatedTotal)
                    .putLong(com.subhub.app.stats.DailyStatsStore.STAMP, System.currentTimeMillis())
                    .putString(com.subhub.app.stats.DailyStatsStore.ZONE, java.time.ZoneId.systemDefault().getId()).commit();
            try { daily.syncWallet(); } catch (RuntimeException unavailable) { android.util.Log.w("DailyStats", "Wallet checkpoint pending"); }
            clearOrderState();
            HardcoreAutoPayManager.schedule(context);
            return true;
        }
    }

    private long totalPaidCentsLocked(List<PenanceEvent> events) {
        return totalPaidCentsLocked(events, getCurrency());
    }

    private long totalPaidCentsLocked(List<PenanceEvent> events, String currency) {
        String key = totalPaidKey(currency);
        if (preferences.contains(key)) {
            return Math.max(0L, preferences.getLong(key, 0L));
        }
        long migratedTotal = 0L;
        for (PenanceEvent event : events) {
            if (event.getStatus() != PenanceEvent.Status.PAID
                    || !currency.equals(event.getCurrency())) continue;
            long amount = Math.max(0, event.getAmountCents());
            migratedTotal = migratedTotal > Long.MAX_VALUE - amount
                    ? Long.MAX_VALUE : migratedTotal + amount;
        }
        preferences.edit().putLong(key, migratedTotal).apply();
        return migratedTotal;
    }

    public boolean isPaidPauseSettlement(String settlementId) {
        if (settlementId == null || settlementId.isEmpty()) return false;
        synchronized (LOCK) {
            return containsPaidPause(loadEvents(), settlementId);
        }
    }

    /** Earliest time an open correction window becomes payable, or zero when none exists. */
    public long nextDueAtMillis() {
        synchronized (LOCK) {
            long next = Long.MAX_VALUE;
            for (PenanceEvent event : loadEvents()) {
                if (event.getStatus() == PenanceEvent.Status.OPEN) {
                    next = Math.min(next, event.getMercyEndsAtMillis());
                }
            }
            return next == Long.MAX_VALUE ? 0L : next;
        }
    }

    long nextAutomaticDueAtMillis(long nowMillis) {
        synchronized (LOCK) {
            return AutoCashoutPolicy.nextEligibleAt(loadEvents(), nowMillis);
        }
    }

    private List<PenanceEvent> loadEvents() {
        String raw = preferences.getString(KEY_EVENTS, "");
        List<PenanceEvent> events = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return events;
        for (String line : raw.split(";")) {
            String[] parts = line.split(",", -1);
            if (parts.length != 7 && parts.length != 8 && parts.length != 9) continue;
            try {
                PenanceEvent.Status status = PenanceEvent.Status.valueOf(parts[5]);
                PenanceInfraction infraction = parts.length >= 8
                        ? PenanceInfraction.valueOf(parts[7])
                        : PenanceInfraction.NEW_DETECTION;
                events.add(new PenanceEvent(parts[0], Long.parseLong(parts[1]),
                        Long.parseLong(parts[2]), Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]), infraction, status, parts[6],
                        parts.length == 9 ? parts[8] : CURRENCY));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed private preference entries without losing valid history.
            }
        }
        return events;
    }

    private void saveEvents(List<PenanceEvent> events) {
        preferences.edit().putString(KEY_EVENTS, encodeEvents(events)).apply();
    }

    private static String encodeEvents(List<PenanceEvent> events) {
        trimHistory(events);
        List<String> encoded = new ArrayList<>(events.size());
        for (PenanceEvent event : events) {
            encoded.add(String.join(",", event.getId(),
                    Long.toString(event.getCreatedAtMillis()),
                    Long.toString(event.getMercyEndsAtMillis()),
                    Integer.toString(event.getAmountCents()),
                    Integer.toString(event.getStrikeCount()),
                    event.getStatus().name(), event.getSettlementId(),
                    event.getInfraction().name(), event.getCurrency()));
        }
        return String.join(";", encoded);
    }

    private static void trimHistory(List<PenanceEvent> events) {
        while (events.size() > MAX_EVENTS) {
            int removable = -1;
            for (int index = 0; index < events.size(); index++) {
                PenanceEvent.Status status = events.get(index).getStatus();
                if (status == PenanceEvent.Status.PAID || status == PenanceEvent.Status.FORGIVEN) {
                    removable = index;
                    break;
                }
            }
            if (removable < 0) break;
            events.remove(removable);
        }
    }

    private static void trimHistoryForInsertion(List<PenanceEvent> events) {
        while (events.size() >= MAX_EVENTS) {
            int removable = -1;
            for (int index = 0; index < events.size(); index++) {
                PenanceEvent.Status status = events.get(index).getStatus();
                if (status == PenanceEvent.Status.PAID
                        || status == PenanceEvent.Status.FORGIVEN) {
                    removable = index;
                    break;
                }
            }
            if (removable < 0) return;
            events.remove(removable);
        }
    }

    private static boolean settlementExists(List<PenanceEvent> events, String settlementId) {
        for (PenanceEvent event : events) {
            if (event.getStatus() == PenanceEvent.Status.CHECKOUT
                    && settlementId.equals(event.getSettlementId())) return true;
        }
        return false;
    }

    private static boolean containsPaidPause(
            List<PenanceEvent> events, String settlementId) {
        for (PenanceEvent event : events) {
            if (event.getStatus() == PenanceEvent.Status.CHECKOUT
                    && settlementId.equals(event.getSettlementId())
                    && event.getInfraction() == PenanceInfraction.PAID_PAUSE) return true;
        }
        return false;
    }

    private static Settlement settlement(List<PenanceEvent> events, String settlementId) {
        List<PenanceEvent> included = new ArrayList<>();
        int amount = 0;
        for (PenanceEvent event : events) {
            if (event.getStatus() == PenanceEvent.Status.CHECKOUT
                    && settlementId.equals(event.getSettlementId())) {
                if (!included.isEmpty()
                        && !included.get(0).getCurrency().equals(event.getCurrency())) return null;
                included.add(event);
                amount += event.getAmountCents();
            }
        }
        return new Settlement(settlementId, amount, included);
    }

    private void clearOrderState() {
        preferences.edit().remove(KEY_ORDER_ID).remove(KEY_APPROVAL_URL)
                .remove(KEY_PAYPAL_BOUNDARY).remove(KEY_CHECKOUT_MODE).apply();
    }

    public enum CheckoutMode { NONE, LINK, API_APPROVAL, HARDCORE_AUTO }

    public static String formatMoney(int cents) {
        return WalletCurrency.format(CURRENCY, cents);
    }

    public static final class Settlement {
        private final String id;
        private final int amountCents;
        private final List<PenanceEvent> events;

        private Settlement(String id, int amountCents, List<PenanceEvent> events) {
            this.id = id;
            this.amountCents = amountCents;
            this.events = Collections.unmodifiableList(new ArrayList<>(events));
        }

        public String getId() { return id; }
        public int getAmountCents() { return amountCents; }
        public List<PenanceEvent> getEvents() { return events; }
        public String getCurrency() {
            return events.isEmpty() ? CURRENCY : events.get(0).getCurrency();
        }
    }
}
