package com.subhub.app.penance;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.subhub.app.BuildConfig;
import com.subhub.app.R;
import com.subhub.app.databinding.ActivityPenanceBinding;
import com.subhub.app.security.ControllerEditMode;
import com.subhub.app.security.ControllerPinGate;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.SubHubNavigation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Styled local ledger and explicit PayPal settlement surface. */
public final class PenanceActivity extends AppCompatActivity {
    public static final String EXTRA_BEGIN_PAID_PAUSE =
            "com.subhub.app.extra.BEGIN_PAID_PAUSE";
    private ActivityPenanceBinding binding;
    private PenanceManager manager;
    private PayPalCredentialStore paypalCredentials;
    private PayPalOrdersClient paypalClient;
    private String activeClientMetadataId = "";
    private boolean checkoutBusy;
    private boolean populatingRules;
    private boolean historyExpanded;
    public static final String EXTRA_SHOW_CONNECTION = "wallet_show_connection";
    private WalletConnectionController connection;
    private final Map<String, WalletActionRow> walletActions = new java.util.LinkedHashMap<>();
    private final Map<String, View> walletEditors = new java.util.LinkedHashMap<>();
    private final Map<String, Integer> walletTitles = new java.util.LinkedHashMap<>();
    private int overviewScroll;
    private androidx.activity.OnBackPressedCallback editorBack;
    private String expandedWalletSection = "";
    private final Handler ruleSaveHandler = new Handler(Looper.getMainLooper());
    private final Runnable persistRules = () -> saveRules(false);
    private boolean dirtyRules;
    private final TextWatcher ruleMathWatcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence value, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable value) {
            renderRuleMathPreview();
            scheduleRulesSave();
        }
    };
    private final Handler timer = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            timer.postDelayed(this, 1000L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPenanceBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PrimaryHeader.bind(binding.getRoot(), R.drawable.ic_nav_money,
                R.string.penance_title, 0);
        manager = new PenanceManager(this);
        paypalCredentials = new PayPalCredentialStore(this);
        paypalClient = new PayPalOrdersClient(this);
        if (savedInstanceState != null) {
            expandedWalletSection = savedInstanceState.getString("wallet_section", "");
            overviewScroll = savedInstanceState.getInt("wallet_overview_scroll", 0);
            historyExpanded = savedInstanceState.getBoolean("wallet_history_expanded", false);
        }
        arrangeWalletSections();
        binding.walletHistoryMore.setOnClickListener(
                view -> {
                    historyExpanded = !historyExpanded;
                    render();
                });
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.MONEY);
        populateRules();
        attachRuleMathListeners();

        PrimaryHeader.backButton(binding.getRoot()).setOnClickListener(view -> openWalletEditor(""));
        editorBack =
                new androidx.activity.OnBackPressedCallback(!expandedWalletSection.isEmpty()) {
                    @Override
                    public void handleOnBackPressed() {
                        openWalletEditor("");
                    }
                };
        getOnBackPressedDispatcher().addCallback(this, editorBack);
        PrimaryHeader.editLockButton(binding.getRoot())
                .setOnClickListener(view -> toggleEditSession());
        binding.buttonSettle.setOnClickListener(view -> {
                    if (!paypalCredentials.hasCredentials()
                            && !validExternalUrl(manager.getPayPalLink())
                            && ControllerPinManager.isSessionUnlocked()) {
                        openWalletEditor("paypal");
                    } else beginCheckout();
                });
        binding.buttonResumeCheckout.setOnClickListener(view -> openApprovalUrl());
        binding.buttonConfirmPayment.setOnClickListener(view -> confirmPayment());
        binding.buttonCancelCheckout.setOnClickListener(view -> cancelCheckout());
        binding.buttonForgiveLatest.setOnClickListener(view -> forgiveLatest());
        binding.buttonClearUnpaid.setOnClickListener(view -> confirmClearUnpaid());
        binding.buttonTestStrike.setVisibility(BuildConfig.DEBUG ? View.VISIBLE : View.GONE);
        binding.buttonTestStrike.setOnClickListener(view -> {
            long now = System.currentTimeMillis();
            int charged = manager.recordInfraction(PenanceInfraction.NEW_DETECTION, 1, now);
            PenanceChargeNotifier.show(this, manager,
                    PenanceInfraction.NEW_DETECTION, charged, now);
            render();
        });
        renderRuleMathPreview();
        render();
        applyEditState();
        handlePayPalReturn(getIntent());
        if (getIntent().getBooleanExtra(EXTRA_BEGIN_PAID_PAUSE, false)) {
            getIntent().removeExtra(EXTRA_BEGIN_PAID_PAUSE);
            binding.getRoot().post(() -> beginCheckout(true));
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(SubHubNavigation.EXTRA_SECTION_HOME, false)) showSectionHome();
        handlePayPalReturn(intent);
        showConnectionIfRequested();
    }

    public void showSectionHome() {
        openWalletEditor("");
    }

    @Override protected void onResume() {
        super.onResume();
        applyEditState();
        connection.onResume();
        showConnectionIfRequested();
        timer.removeCallbacks(tick);
        timer.post(tick);
    }

    private void arrangeWalletSections() {
        walletDestination(
                "rules",
                R.string.wallet_rules_caps_title,
                R.drawable.ic_tab_settings,
                binding.ruleConfigCard);
        walletDestination(
                "pause",
                R.string.paid_pause_config_title,
                R.drawable.ic_nav_limits, binding.paidPauseConfigCard);
        com.subhub.app.databinding.ViewWalletConnectionBinding panel =
                com.subhub.app.databinding.ViewWalletConnectionBinding.inflate(
                        getLayoutInflater(), binding.walletEditor, false);
        binding.walletEditor.addView(panel.getRoot());
        walletDestination(
                "paypal",
                R.string.wallet_connection_title,
                R.drawable.ic_settings_wallet,
                panel.getRoot());
        connection =
                new WalletConnectionController(this, panel, walletActions.get("paypal").summary());
        walletDestination(
                "corrections",
                R.string.penance_mercy_title,
                R.drawable.ic_ux_lock,
                binding.correctionsCard);
        walletActions.get("corrections").summary().setVisibility(View.GONE);
        displayWalletSections();
    }

    private void walletDestination(String key, int title, int icon, View control) {
        if (!walletActions.isEmpty()) {
            View line = new View(this);
            line.setBackgroundResource(R.color.outline_subtle);
            android.widget.LinearLayout.LayoutParams lineParams =
                    new android.widget.LinearLayout.LayoutParams(-1, dp(1));
            lineParams.setMarginStart(dp(54));
            lineParams.setMarginEnd(dp(16));
            binding.walletActions.addView(line, lineParams);
        }
        WalletActionRow row = new WalletActionRow(this, title, icon);
        row.setTag("wallet:" + key);
        row.setOnClickListener(view -> openWalletEditor(key));
        binding.walletActions.addView(row, new android.widget.LinearLayout.LayoutParams(-1, -2));
        walletActions.put(key, row);
        walletEditors.put(key, control);
        walletTitles.put(key, title);
    }

    private void openWalletEditor(String key) {
        if (!key.isEmpty() && !ControllerPinManager.isDomModeActive()) return;
        if (expandedWalletSection.isEmpty()) overviewScroll = binding.walletScroll.getScrollY();
        if (ControllerPinManager.isSessionUnlocked()) commitRules(false);
        View focused = getCurrentFocus();
        if (focused != null) {
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }
        expandedWalletSection = key;
        displayWalletSections();
        binding.walletScroll.post(
                () ->
                        binding.walletScroll.scrollTo(
                                0, expandedWalletSection.isEmpty() ? overviewScroll : 0));
        render();
    }

    private void displayWalletSections() {
        boolean dom = ControllerPinManager.isDomModeActive();
        if (!dom) expandedWalletSection = "";
        boolean overview = expandedWalletSection.isEmpty();
        binding.walletOverview.setVisibility(overview ? View.VISIBLE : View.GONE);
        binding.walletEditor.setVisibility(overview ? View.GONE : View.VISIBLE);
        binding.walletManagement.setVisibility(dom ? View.VISIBLE : View.GONE);
        for (Map.Entry<String, View> entry : walletEditors.entrySet())
            entry.getValue()
                    .setVisibility(
                            entry.getKey().equals(expandedWalletSection)
                                    ? View.VISIBLE
                                    : View.GONE);
        PrimaryHeader.backButton(binding.getRoot()).setVisibility(overview ? View.GONE : View.VISIBLE);
        PrimaryHeader.bind(
                binding.getRoot(),
                overview ? R.drawable.ic_nav_money : 0,
                overview ? R.string.penance_title : walletTitles.get(expandedWalletSection),
                0);
        PrimaryHeader.subtitle(binding.getRoot()).setText(manager.getCurrency());
        PrimaryHeader.subtitle(binding.getRoot()).setVisibility(View.GONE);
        if (editorBack != null) editorBack.setEnabled(!overview);
    }

    private void showConnectionIfRequested() {
        if (!getIntent().getBooleanExtra(EXTRA_SHOW_CONNECTION, false)) return;
        getIntent().removeExtra(EXTRA_SHOW_CONNECTION);
        if (ControllerPinManager.isDomModeActive()) openWalletEditor("paypal");
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("wallet_section", expandedWalletSection);
        state.putInt("wallet_overview_scroll", overviewScroll);
        state.putBoolean("wallet_history_expanded", historyExpanded);
        super.onSaveInstanceState(state);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toggleEditSession() {
        if (ControllerPinManager.isSessionUnlocked()) {
            // Flush the last edit before leaving the role that is allowed to save it.
            commitRules(true);
            ControllerEditMode.enterSubMode(this);
        } else ControllerPinGate.unlock(this, this::applyEditState, false);
    }

    private void applyEditState() {
        if (binding == null) return;
        boolean editing = ControllerPinManager.isDomModeActive();
        ControllerEditMode.renderButton(this, PrimaryHeader.editLockButton(binding.getRoot()));
        PrimaryHeader.editLockButton(binding.getRoot())
                .setVisibility(editing ? View.VISIBLE : View.GONE);
        PrimaryHeader.subtitle(binding.getRoot()).setText(manager.getCurrency());
        PrimaryHeader.subtitle(binding.getRoot()).setVisibility(View.GONE);
        displayWalletSections();
        if (connection != null) connection.refresh();
        View[] editable = { binding.ruleDetectionEnabled,
                binding.ruleDetectionAmount, binding.detectionBatch, binding.ruleDwellEnabled,
                binding.ruleDwellAmount, binding.dwellSeconds,
                binding.ruleTapEnabled, binding.ruleTapAmount,
                binding.ruleAppOpenEnabled, binding.ruleAppOpenAmount, binding.dailyCap,
                binding.ruleTamperEnabled, binding.ruleTamperAmount,
                binding.tamperCooldownMinutes,
                binding.weeklyCap, binding.mercyMinutes,
                binding.paidPauseEnabled, binding.paidPauseAmount,
                binding.paidPauseMinutes,
                binding.buttonForgiveLatest, binding.buttonClearUnpaid, binding.buttonTestStrike};
        for (View view : editable) view.setEnabled(editing);
        syncRuleInputStates();
        SubHubNavigation.bind(this, binding.getRoot(), SubHubNavigation.Screen.MONEY);
        render();
    }

    @Override protected void onPause() {
        ruleSaveHandler.removeCallbacks(persistRules);
        if (ControllerPinManager.isSessionUnlocked()) saveRules(false);
        timer.removeCallbacks(tick);
        super.onPause();
    }

    private void populateRules() {
        populatingRules = true;
        populateRule(PenanceInfraction.NEW_DETECTION,
                binding.ruleDetectionEnabled, binding.ruleDetectionAmount,
                binding.detectionBatch);
        populateRule(PenanceInfraction.CENSORED_DWELL,
                binding.ruleDwellEnabled, binding.ruleDwellAmount);
        populateRule(PenanceInfraction.CENSORED_TAP,
                binding.ruleTapEnabled, binding.ruleTapAmount);
        populateRule(PenanceInfraction.WATCHED_APP_OPEN,
                binding.ruleAppOpenEnabled, binding.ruleAppOpenAmount);
        populateRule(PenanceInfraction.TAMPER_ATTEMPT,
                binding.ruleTamperEnabled, binding.ruleTamperAmount,
                binding.tamperCooldownMinutes);
        binding.dailyCap.setText(decimalEuros(manager.getDailyCapCents()));
        binding.weeklyCap.setText(decimalEuros(manager.getWeeklyCapCents()));
        binding.mercyMinutes.setText(String.valueOf(manager.getMercyMinutes()));
        binding.dwellSeconds.setText(String.valueOf(manager.getDwellSeconds()));
        binding.detectionBatch.setText(String.valueOf(manager.getDetectionBatch()));
        binding.tamperCooldownMinutes.setText(
                String.valueOf(manager.getTamperCooldownMinutes()));
        PaidPauseManager paidPause = new PaidPauseManager(this);
        binding.paidPauseEnabled.setChecked(paidPause.isEnabled());
        binding.paidPauseAmount.setText(decimalEuros(paidPause.getPriceCents()));
        binding.paidPauseMinutes.setText(String.valueOf(paidPause.getDurationMinutes()));
        populatingRules = false;
        syncPaidPauseInputs();
    }

    private void populateRule(
            PenanceInfraction infraction,
            CompoundButton toggle, EditText amount, View... dependents) {
        toggle.setChecked(manager.isInfractionEnabled(infraction));
        amount.setText(decimalEuros(manager.getInfractionCents(infraction)));
        syncRuleInputState(toggle, amount, dependents);
    }

    private void syncRuleInputStates() {
        syncRuleInputState(binding.ruleDetectionEnabled, binding.ruleDetectionAmount,
                binding.detectionBatch);
        syncRuleInputState(binding.ruleDwellEnabled, binding.ruleDwellAmount,
                binding.dwellSeconds);
        syncRuleInputState(binding.ruleTapEnabled, binding.ruleTapAmount);
        syncRuleInputState(binding.ruleAppOpenEnabled, binding.ruleAppOpenAmount);
        syncRuleInputState(binding.ruleTamperEnabled, binding.ruleTamperAmount,
                binding.tamperCooldownMinutes);
        syncPaidPauseInputs();
    }

    private void syncPaidPauseInputs() {
        if (binding == null) return;
        boolean enabled = binding.paidPauseEnabled.isChecked();
        boolean editable = enabled && ControllerPinManager.isSessionUnlocked();
        binding.paidPauseAmount.setEnabled(editable);
        binding.paidPauseMinutes.setEnabled(editable);
    }

    private void syncRuleInputState(CompoundButton toggle, EditText amount, View... dependents) {
        boolean ruleEnabled = toggle.isChecked();
        boolean editable = ruleEnabled && ControllerPinManager.isSessionUnlocked();
        amount.setEnabled(editable);
        if (dependents == null) return;
        for (View dependent : dependents) {
            dependent.setEnabled(editable);
        }
    }

    private void attachRuleMathListeners() {
        attachRuleToggle(PenanceInfraction.NEW_DETECTION, binding.ruleDetectionEnabled, binding.ruleDetectionAmount,
                binding.detectionBatch);
        attachRuleToggle(PenanceInfraction.CENSORED_DWELL, binding.ruleDwellEnabled, binding.ruleDwellAmount,
                binding.dwellSeconds);
        attachRuleToggle(PenanceInfraction.CENSORED_TAP, binding.ruleTapEnabled, binding.ruleTapAmount);
        attachRuleToggle(PenanceInfraction.WATCHED_APP_OPEN, binding.ruleAppOpenEnabled, binding.ruleAppOpenAmount);
        attachRuleToggle(PenanceInfraction.TAMPER_ATTEMPT, binding.ruleTamperEnabled, binding.ruleTamperAmount,
                binding.tamperCooldownMinutes);
        binding.paidPauseEnabled.setOnCheckedChangeListener((button, checked) -> {
            if (!populatingRules && !ControllerPinManager.isSessionUnlocked()) {
                populateRules();
                return;
            }
            if (!populatingRules && !checked) {
                PaidPauseManager pause = new PaidPauseManager(this);
                pause.configure(false, pause.getPriceCents(), pause.getDurationMinutes());
            }
            syncPaidPauseInputs();
            scheduleRulesSave();
        });
        EditText[] inputs = {binding.ruleDetectionAmount, binding.detectionBatch,
                binding.ruleDwellAmount, binding.ruleTapAmount, binding.ruleAppOpenAmount,
                binding.ruleTamperAmount, binding.tamperCooldownMinutes,
                binding.dailyCap, binding.weeklyCap, binding.mercyMinutes,
                binding.dwellSeconds, binding.paidPauseAmount, binding.paidPauseMinutes};
        for (EditText input : inputs) {
            input.addTextChangedListener(ruleMathWatcher);
            input.setOnFocusChangeListener((view, focused) -> {
                if (!focused) commitRules(true);
            });
            input.setOnEditorActionListener((view, actionId, event) -> {
                if (actionId != EditorInfo.IME_ACTION_DONE) return false;
                commitRules(true);
                view.clearFocus();
                return false;
            });
        }
    }

    private void attachRuleToggle(PenanceInfraction rule, CompoundButton toggle, EditText amount, View... dependents) {
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (!populatingRules && !ControllerPinManager.isSessionUnlocked()) {
                populateRules();
                return;
            }
            if (!populatingRules && !checked) manager.disableInfraction(rule);
            syncRuleInputState(toggle, amount, dependents);
            renderRuleMathPreview();
            scheduleRulesSave();
        });
    }

    private void scheduleRulesSave() {
        if (populatingRules || !ControllerPinManager.isSessionUnlocked()) return;
        dirtyRules = true;
        ruleSaveHandler.removeCallbacks(persistRules);
        ruleSaveHandler.postDelayed(persistRules, 450L);
    }

    private void commitRules(boolean restoreIfInvalid) {
        if (populatingRules || !ControllerPinManager.isSessionUnlocked()) return;
        ruleSaveHandler.removeCallbacks(persistRules);
        if (!saveRules(restoreIfInvalid) && restoreIfInvalid) {
            populateRules();
            dirtyRules = false;
            renderRuleMathPreview();
        }
    }

    private void renderRuleMathPreview() {
        if (binding == null) return;
        binding.ruleMathPreview.setVisibility(View.VISIBLE);
        long now = System.currentTimeMillis();
        if (manager.isEnabled() && manager.isInfractionEnabled(PenanceInfraction.NEW_DETECTION)) {
            if (manager.getDailyRemainingCents(now) == 0) {
                binding.ruleMathPreview.setText(R.string.penance_daily_cap_reached);
                return;
            }
            if (manager.getWeeklyRemainingCents(now) == 0) {
                binding.ruleMathPreview.setText(R.string.penance_weekly_cap_reached);
                return;
            }
        }
        Integer cents = parseEuros(binding.ruleDetectionAmount.getText().toString());
        Integer batch = parseInteger(binding.detectionBatch.getText().toString());
        Integer daily = parseEuros(binding.dailyCap.getText().toString());
        Integer weekly = parseEuros(binding.weeklyCap.getText().toString());
        if (cents == null || batch == null || daily == null || weekly == null
                || cents < PenancePolicy.MIN_STRIKE_CENTS
                || cents > PenancePolicy.MAX_STRIKE_CENTS
                || batch < PenancePolicy.MIN_DETECTION_BATCH
                || batch > PenancePolicy.MAX_DETECTION_BATCH
                || daily < cents || weekly < daily) {
            binding.ruleMathPreview.setText(R.string.penance_rule_math_invalid);
            return;
        }
        binding.ruleMathPreview.setText("");
        binding.ruleMathPreview.setVisibility(View.GONE);
    }

    private boolean saveRules(boolean showInvalid) {
        if (binding == null || populatingRules || !ControllerPinManager.isSessionUnlocked()) return false;
        if (!dirtyRules) return true;
        Map<PenanceInfraction, Integer> rules = new EnumMap<>(PenanceInfraction.class);
        if (!readRule(rules, PenanceInfraction.NEW_DETECTION,
                binding.ruleDetectionEnabled, binding.ruleDetectionAmount)
                || !readRule(rules, PenanceInfraction.CENSORED_DWELL,
                binding.ruleDwellEnabled, binding.ruleDwellAmount)
                || !readRule(rules, PenanceInfraction.CENSORED_TAP,
                binding.ruleTapEnabled, binding.ruleTapAmount)
                || !readRule(rules, PenanceInfraction.WATCHED_APP_OPEN,
                binding.ruleAppOpenEnabled, binding.ruleAppOpenAmount)
                || !readRule(rules, PenanceInfraction.TAMPER_ATTEMPT,
                binding.ruleTamperEnabled, binding.ruleTamperAmount)) {
            if (showInvalid) toast(R.string.penance_rules_invalid);
            return false;
        }
        boolean enabled = !rules.isEmpty();
        Integer daily = parseEuros(binding.dailyCap.getText().toString());
        Integer weekly = parseEuros(binding.weeklyCap.getText().toString());
        Integer mercy = parseInteger(binding.mercyMinutes.getText().toString());
        Integer dwell = parseInteger(binding.dwellSeconds.getText().toString());
        Integer detectionBatch = parseInteger(binding.detectionBatch.getText().toString());
        Integer tamperCooldown = parseInteger(
                binding.tamperCooldownMinutes.getText().toString());
        Integer pausePrice = parseEuros(binding.paidPauseAmount.getText().toString());
        Integer pauseMinutes = parseInteger(binding.paidPauseMinutes.getText().toString());
        int largestCost = 0;
        for (int cost : rules.values()) largestCost = Math.max(largestCost, cost);
        if (daily == null || weekly == null || mercy == null || dwell == null
                || detectionBatch == null
                || tamperCooldown == null
                || pausePrice == null || pauseMinutes == null
                || (enabled && rules.isEmpty()) || daily < largestCost || weekly < daily
                || daily > PenancePolicy.MAX_DAILY_CENTS
                || weekly > PenancePolicy.MAX_WEEKLY_CENTS
                || mercy < 0 || mercy > PenancePolicy.MAX_MERCY_MINUTES
                || dwell < PenancePolicy.MIN_DWELL_SECONDS
                || dwell > PenancePolicy.MAX_DWELL_SECONDS
                || detectionBatch < PenancePolicy.MIN_DETECTION_BATCH
                || detectionBatch > PenancePolicy.MAX_DETECTION_BATCH
                || tamperCooldown < PenanceManager.MIN_TAMPER_COOLDOWN_MINUTES
                || tamperCooldown > PenanceManager.MAX_TAMPER_COOLDOWN_MINUTES
                || pausePrice < PaidPauseManager.MIN_PRICE_CENTS
                || pausePrice > PaidPauseManager.MAX_PRICE_CENTS
                || pauseMinutes < PaidPauseManager.MIN_DURATION_MINUTES
                || pauseMinutes > PaidPauseManager.MAX_DURATION_MINUTES) {
            if (showInvalid) toast(R.string.penance_rules_invalid);
            return false;
        }
        manager.configure(enabled, rules, daily, weekly, mercy, dwell, detectionBatch,
                tamperCooldown);
        new PaidPauseManager(this).configure(binding.paidPauseEnabled.isChecked(),
                pausePrice, pauseMinutes);
        dirtyRules = false;
        HardcoreAutoPayManager.schedule(this);
        render();
        return true;
    }

    private boolean readRule(Map<PenanceInfraction, Integer> rules,
            PenanceInfraction infraction,
            CompoundButton toggle, EditText input) {
        if (!toggle.isChecked()) return true;
        Integer amount = parseEuros(input.getText().toString());
        if (amount == null || amount < PenancePolicy.MIN_STRIKE_CENTS
                || amount > PenancePolicy.MAX_STRIKE_CENTS) return false;
        rules.put(infraction, amount);
        return true;
    }

    private void beginCheckout() {
        beginCheckout(false);
    }

    private void beginCheckout(boolean paidPauseOnly) {
        if (paidPauseOnly && !new PaidPauseManager(this).canPurchase()) {
            toast(R.string.paid_pause_unavailable);
            return;
        }
        HardcoreAutoPayManager autoPay = new HardcoreAutoPayManager(this);
        boolean autoPayConfigured = autoPay.isConfigured();
        boolean automaticContext = autoPay.isAutomaticContextActive();
        boolean autoPayEligible = automaticContext && autoPay.isEligibleNow();
        PayPalRequestPolicy.CheckoutRoute checkoutRoute = PayPalRequestPolicy.checkoutRoute(
                paidPauseOnly, automaticContext, autoPayConfigured, autoPayEligible);
        if (checkoutRoute == PayPalRequestPolicy.CheckoutRoute.BLOCKED) {
            toast(R.string.paypal_auto_pay_unavailable);
            render();
            return;
        }
        if (checkoutRoute == PayPalRequestPolicy.CheckoutRoute.STORED_WALLET) {
            checkoutBusy = true;
            render();
            HardcoreAutoPayEngine.run(this, true, () -> {
                checkoutBusy = false;
                if (binding != null) render();
            });
            return;
        }
        boolean paypalReady = paypalCredentials.hasCredentials();
        boolean linkReady = validExternalUrl(manager.getPayPalLink());
        if (!paypalReady && !linkReady) {
            toast(R.string.penance_backend_required);
            return;
        }
        PenanceManager.Settlement settlement = paidPauseOnly
                ? manager.beginPaidPauseSettlement(System.currentTimeMillis())
                : manager.beginSettlement(System.currentTimeMillis());
        if (settlement == null) {
            toast(R.string.penance_no_due);
            return;
        }
        if (paypalReady) createPayPalOrder(settlement);
        else {
            String approvalUrl = paymentUrl(manager.getPayPalLink(), settlement.getAmountCents(),
                    settlement.getCurrency());
            manager.bindOrder(settlement.getId(), settlement.getId(), approvalUrl);
            render();
            openApprovalUrl();
        }
    }

    private void createPayPalOrder(PenanceManager.Settlement settlement) {
        checkoutBusy = true;
        render();
        java.util.List<PayPalOrdersClient.OrderItem> bill;
        try {
            bill = PayPalOrderDetails.from(this, settlement);
        } catch (RuntimeException invalidBill) {
            checkoutBusy = false;
            manager.cancelSettlement(settlement.getId());
            toast(getString(R.string.penance_checkout_failed, "The local settlement bill was invalid"));
            render();
            return;
        }
        PayPalCredentialStore.Credentials credentials = paypalCredentials.load();
        paypalClient.createOrder(credentials, settlement.getId(),
                settlement.getAmountCents(), settlement.getCurrency(), false,
                bill, result -> {
                    checkoutBusy = false;
                    if (binding == null) return;
                    if (!result.isSuccess()) {
                        manager.cancelSettlement(settlement.getId());
                        toast(getString(R.string.penance_checkout_failed, result.error()));
                        render();
                        return;
                    }
                    if (!canProcessSettlement(settlement.getId())) {
                        manager.cancelSettlement(settlement.getId());
                        toast(R.string.paid_pause_unavailable);
                        render();
                        return;
                    }
                    PayPalOrdersClient.Order order = result.value();
                    activeClientMetadataId = order.clientMetadataId();
                    manager.bindOrder(settlement.getId(), order.id(), order.approvalUrl(),
                            credentials.boundaryId());
                    render();
                    openApprovalUrl();
                });
    }

    private void openApprovalUrl() {
        String approvalUrl = manager.getActiveApprovalUrl();
        if (!validExternalUrl(approvalUrl)) {
            toast(R.string.penance_payment_pending);
            return;
        }
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(approvalUrl)));
    }

    private void confirmPayment() {
        if (manager.getActiveCheckoutMode() == PenanceManager.CheckoutMode.HARDCORE_AUTO) {
            toast(R.string.paypal_auto_payment_processing);
            return;
        }
        String settlementId = manager.getActiveSettlementId();
        String orderId = manager.getActiveOrderId();
        PenanceSnapshot snapshot = manager.snapshot(System.currentTimeMillis());
        if (settlementId.isEmpty() || snapshot.getCheckoutCents() <= 0) {
            toast(R.string.penance_payment_mismatch);
            return;
        }
        if (paypalCredentials.hasCredentials() && !orderId.isEmpty()
                && !orderId.equals(settlementId)) {
            capturePayPalPayment();
            return;
        }
        if (!manager.completeSettlement(settlementId, snapshot.getCheckoutCents())) {
            toast(R.string.penance_payment_mismatch);
            return;
        }
        toast(R.string.penance_payment_complete);
        render();
    }

    private void capturePayPalPayment() {
        if (checkoutBusy) return;
        String settlementId = manager.getActiveSettlementId();
        String orderId = manager.getActiveOrderId();
        PenanceSnapshot snapshot = manager.snapshot(System.currentTimeMillis());
        if (settlementId.isEmpty() || orderId.isEmpty() || snapshot.getCheckoutCents() <= 0
                || !paypalCredentials.hasCredentials()) {
            toast(R.string.penance_payment_mismatch);
            return;
        }
        if (!canProcessSettlement(settlementId)) {
            manager.cancelSettlement(settlementId);
            toast(R.string.paid_pause_unavailable);
            render();
            return;
        }
        PayPalCredentialStore.Credentials credentials = paypalCredentials.load();
        if (!credentials.boundaryId().equals(manager.getActivePayPalBoundary())) {
            toast(R.string.penance_payment_boundary_changed);
            manager.cancelSettlement(settlementId);
            render();
            return;
        }
        checkoutBusy = true;
        render();
        paypalClient.captureOrder(credentials, orderId, settlementId,
                snapshot.getCheckoutCents(), manager.getCurrency(), activeClientMetadataId, result -> {
                    checkoutBusy = false;
                    if (binding == null) return;
                    if (!result.isSuccess()) {
                        toast(getString(R.string.penance_checkout_failed, result.error()));
                        render();
                        return;
                    }
                    PayPalOrdersClient.Capture capture = result.value();
                    if (!capture.vaultId().isEmpty() && !capture.customerId().isEmpty()) {
                        paypalCredentials.recordVaultResult(credentials,
                                capture.vaultStatus(), capture.vaultId(), capture.customerId(),
                                capture.payerEmail(), capture.payerAccountId());
                    }
                    if (!manager.completeSettlement(settlementId, snapshot.getCheckoutCents())) {
                        toast(R.string.penance_payment_mismatch);
                    } else toast(R.string.penance_payment_complete);
                    render();
                });
    }

    private boolean canProcessSettlement(String settlementId) {
        return !manager.isPaidPauseSettlement(settlementId)
                || new PaidPauseManager(this).canPurchase();
    }

    private void handlePayPalReturn(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data == null || !"subhubapp".equalsIgnoreCase(data.getScheme())
                || !"paypal".equalsIgnoreCase(data.getHost())) return;
        String metadata = data.getQueryParameter("cmid");
        if (metadata != null && metadata.length() <= 36) activeClientMetadataId = metadata;
        if ("/checkout/cancel".equalsIgnoreCase(data.getPath())) cancelCheckout();
        else if ("/checkout/return".equalsIgnoreCase(data.getPath())) capturePayPalPayment();
    }

    private void cancelCheckout() {
        String settlementId = manager.getActiveSettlementId();
        manager.cancelSettlement(settlementId);
        toast(R.string.penance_cancelled);
        render();
    }

    private void forgiveLatest() {
        if (manager.forgiveLatestInMercy(System.currentTimeMillis())) {
            toast(R.string.penance_forgiven);
        } else {
            toast(R.string.penance_none_in_mercy);
        }
        render();
    }

    private void confirmClearUnpaid() {
        com.subhub.app.util.ThemedDialogs.builder(this)
                .setTitle(R.string.penance_clear_title)
                .setMessage(R.string.penance_clear_body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.penance_clear_now, (dialog, which) -> {
                    manager.forgiveAllUnpaid();
                    toast(R.string.penance_cleared);
                    render();
                }).show();
    }

    private void render() {
        if (binding == null) return;
        if (!expandedWalletSection.isEmpty()) {
            if ("rules".equals(expandedWalletSection)) renderRuleMathPreview();
            return;
        }
        long now = System.currentTimeMillis();
        PenanceSnapshot snapshot = manager.snapshot(now);
        binding.dueAmount.setText(manager.money(snapshot.getDueCents()));
        binding.mercyAmount.setText(manager.money(snapshot.getMercyCents()));
        binding.checkoutAmount.setText(manager.money(snapshot.getCheckoutCents()));
        binding.paidAmount.setText(manager.money(snapshot.getPaidCents()));
        boolean checkout = snapshot.getCheckoutCents() > 0;
        boolean automaticCheckout = checkout && manager.getActiveCheckoutMode()
                == PenanceManager.CheckoutMode.HARDCORE_AUTO;
        boolean paypalReady = paypalCredentials.hasCredentials();
        boolean linkReady = validExternalUrl(manager.getPayPalLink());
        boolean paymentAvailable = paypalReady || linkReady;
        PayPalCredentialStore.VaultState vaultState = paypalCredentials.vaultState();
        if (vaultState.isReady() && !vaultState.maskedPayer().isEmpty()) {
            binding.paymentAvailability.setText(getString(
                    R.string.paypal_vault_status_linked, vaultState.maskedPayer()));
        } else binding.paymentAvailability.setText(paypalReady
                    ? R.string.penance_payment_ready
                    : linkReady ? R.string.penance_payment_link_ready
                    : R.string.penance_payment_unavailable);
        boolean setupNeeded = !paymentAvailable && ControllerPinManager.isSessionUnlocked();
        binding.buttonSettle.setText(
                setupNeeded
                        ? getString(R.string.wallet_setup_paypal)
                        : getString(
                                R.string.wallet_pay_amount, manager.money(snapshot.getDueCents())));
        binding.buttonSettle.setVisibility(checkout ? View.GONE : View.VISIBLE);
        binding.buttonSettle.setEnabled(
                !checkoutBusy
                        && !checkout
                        && (setupNeeded || paymentAvailable && snapshot.getDueCents() > 0));
        binding.checkoutRecoveryActions.setVisibility(
                checkout && !automaticCheckout ? View.VISIBLE : View.GONE);
        binding.buttonResumeCheckout.setVisibility(
                checkout && !automaticCheckout && !manager.getActiveApprovalUrl().isEmpty()
                        ? View.VISIBLE : View.GONE);
        binding.buttonCancelCheckout.setVisibility(
                checkout && !automaticCheckout ? View.VISIBLE : View.GONE);
        binding.buttonConfirmPayment.setVisibility(
                checkout && !automaticCheckout ? View.VISIBLE : View.GONE);
        boolean sandboxOrder = checkout && paypalReady
                && !manager.getActiveOrderId().isEmpty()
                && !manager.getActiveOrderId().equals(manager.getActiveSettlementId());
        binding.buttonConfirmPayment.setText(sandboxOrder
                ? R.string.penance_confirm_paid : R.string.penance_confirm_link_paid);
        binding.buttonConfirmPayment.setEnabled(!checkoutBusy);
        if (checkoutBusy) binding.paymentStatus.setText(R.string.penance_checking);
        else if (automaticCheckout) {
            binding.paymentStatus.setText(R.string.paypal_auto_payment_processing);
        } else if (checkout) binding.paymentStatus.setText(R.string.penance_payment_pending);
        else binding.paymentStatus.setText("");
        binding.paymentStatus.setVisibility(
                binding.paymentStatus.length() == 0 ? View.GONE : View.VISIBLE);
        updateWalletSummaries(snapshot);
        if (expandedWalletSection.isEmpty()) renderHistory(snapshot, now);
        if ("rules".equals(expandedWalletSection)) renderRuleMathPreview();
    }

    private void updateWalletSummaries(PenanceSnapshot snapshot) {
        int enabled = 0;
        for (PenanceInfraction infraction : PenanceInfraction.values())
            if (infraction != PenanceInfraction.PAID_PAUSE
                    && manager.isInfractionEnabled(infraction)) enabled++;
        walletActions
                .get("rules")
                .summary()
                .setText(
                        getString(
                                R.string.wallet_rules_caps_summary,
                                enabled,
                                manager.money(manager.getDailyCapCents())));
        PaidPauseManager pause = new PaidPauseManager(this);
        walletActions
                .get("pause")
                .summary()
                .setText(
                        pause.isEnabled()
                                ? getString(
                                        R.string.wallet_pause_summary,
                                        pause.getDurationMinutes(),
                                        manager.money(pause.getPriceCents()))
                                : getString(R.string.control_state_off));
    }

    private void renderHistory(PenanceSnapshot snapshot, long nowMillis) {
        binding.history.setText(snapshot.getEvents().isEmpty() ? getString(R.string.penance_history_empty) : "");
        binding.history.setVisibility(snapshot.getEvents().isEmpty() ? View.VISIBLE : View.GONE);
        int groupCount = binding.walletHistoryList.bind(snapshot.getEvents(), nowMillis, historyExpanded ? 12 : 2);
        binding.walletHistoryMore.setVisibility(
                groupCount > 2 ? View.VISIBLE : View.GONE);
        binding.walletHistoryMore.setText(
                historyExpanded ? R.string.wallet_history_less : R.string.wallet_history_more);
    }

    private String infractionLabel(PenanceInfraction infraction) {
        if (infraction == PenanceInfraction.PAID_PAUSE) {
            return getString(R.string.paid_pause_history_label);
        }
        if (infraction == PenanceInfraction.CENSORED_DWELL) {
            return getString(R.string.penance_history_dwell);
        }
        if (infraction == PenanceInfraction.CENSORED_TAP) {
            return getString(R.string.penance_history_tap);
        }
        if (infraction == PenanceInfraction.WATCHED_APP_OPEN) {
            return getString(R.string.penance_history_app_open);
        }
        if (infraction == PenanceInfraction.TAMPER_ATTEMPT) {
            return getString(R.string.penance_history_tamper);
        }
        return getString(R.string.penance_history_detection);
    }

    private static boolean validExternalUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return host != null && "https".equalsIgnoreCase(uri.getScheme())
                    && ("paypal.me".equalsIgnoreCase(host)
                    || "paypal.com".equalsIgnoreCase(host)
                    || host.toLowerCase(Locale.ROOT).endsWith(".paypal.com"));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String paymentUrl(String baseUrl, int amountCents, String currency) {
        URI uri = URI.create(baseUrl);
        if (!"paypal.me".equalsIgnoreCase(uri.getHost())) return baseUrl;
        String trimmed = baseUrl;
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed + "/" + String.format(Locale.ROOT, "%.2f", amountCents / 100.0)
                + WalletCurrency.requireSupported(currency);
    }

    private static Integer parseEuros(String value) {
        try {
            BigDecimal euros = new BigDecimal(value.trim().replace(',', '.'));
            return euros.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact();
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer parseInteger(String value) {
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static String decimalEuros(int cents) {
        return String.format(Locale.ROOT, "%.2f", cents / 100.0);
    }

    private void toast(int resource) {
        Toast.makeText(this, resource, Toast.LENGTH_SHORT).show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    @Override protected void onDestroy() {
        ruleSaveHandler.removeCallbacksAndMessages(null);
        timer.removeCallbacks(tick);
        if (paypalClient != null) paypalClient.close();
        if (connection != null) connection.close();
        binding = null;
        super.onDestroy();
    }
}
