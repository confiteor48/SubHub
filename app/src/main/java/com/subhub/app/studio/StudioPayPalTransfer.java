package com.subhub.app.studio;

import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.subhub.app.pack.PackPayPalCipher;
import com.subhub.app.pack.SubHubPack;
import com.subhub.app.pack.SubHubPackManager;
import com.subhub.app.pack.SubHubPackSchema;
import com.subhub.app.penance.PayPalCredentialStore;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.security.ControllerPinManager;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lifecycle-scoped secret entry and off-main-thread encryption. Never saves a passphrase. */
final class StudioPayPalTransfer {
    private final AppCompatActivity activity;
    private final SubHubPackManager manager;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AlertDialog dialog;
    private SubHubPackManager.UnlockedPayPal pending;
    private boolean busy;
    private int generation;

    StudioPayPalTransfer(AppCompatActivity activity, SubHubPackManager manager) {
        this.activity = activity;
        this.manager = manager;
    }

    void attach(SubHubPack pack, Runnable saved) {
        if (!allowed() || pack == null) return;
        if (!pack.getIncludedSections().contains(SubHubPackSchema.WALLET)) {
            toast("Include Wallet first."); return;
        }
        passwordDialog(true, password -> {
            int token = generation;
            busy = true;
            toast("Encrypting PayPal attachment…");
            worker.execute(() -> {
                JSONObject result = null;
                try {
                    if (ControllerPinManager.isDomModeActive()) {
                        PayPalCredentialStore.Credentials credentials =
                                new PayPalCredentialStore(activity).load();
                        try (PackPayPalCipher.Payload value = new PackPayPalCipher.Payload(
                                credentials.environment().name(), credentials.clientId(),
                                credentials.secret(), new PenanceManager(activity).getPayPalLink())) {
                            result = PackPayPalCipher.encrypt(pack.getId(), pack.getOriginDeviceId(),
                                    value, password);
                        }
                    }
                } catch (Exception ignored) {
                    // Credentials and crypto-provider errors never enter logs or UI.
                } finally { Arrays.fill(password, '\0'); }
                JSONObject encrypted = result;
                activity.runOnUiThread(() -> {
                    busy = false;
                    if (!current(token)) return;
                    try {
                        if (encrypted == null) throw new IllegalStateException();
                        pack.setEncryptedPayPal(encrypted);
                        saved.run();
                        toast("Encrypted PayPal attached. Share the passphrase separately.");
                    } catch (Exception ignored) {
                        toast("Could not attach PayPal. Configure complete credentials in Dom Settings first.");
                    }
                });
            });
        });
    }

    void activate(SubHubPack pack, Set<String> sections, Runnable finished) {
        if (!allowed()) return;
        Set<String> selected = Set.copyOf(sections);
        passwordDialog(false, password -> {
            int token = generation;
            busy = true;
            toast("Unlocking PayPal attachment…");
            worker.execute(() -> {
                SubHubPackManager.UnlockedPayPal result = null;
                try { result = manager.unlockPayPal(pack, password); }
                catch (Exception ignored) { }
                finally { Arrays.fill(password, '\0'); }
                SubHubPackManager.UnlockedPayPal unlocked = result;
                activity.runOnUiThread(() -> {
                    busy = false;
                    if (!current(token)) { if (unlocked != null) unlocked.close(); return; }
                    if (unlocked == null) { toast("Wrong passphrase or invalid PayPal attachment."); return; }
                    pending = unlocked;
                    dialog = com.subhub.app.util.ThemedDialogs.builder(activity).setTitle("Confirm PayPal recipient")
                            .setMessage(unlocked.summary() + "\n\nOrders go to the merchant owning these API "
                                    + "credentials; the fallback link may identify a different recipient. "
                                    + "Confirm the merchant with your Dom independently.\n\n"
                                    + "This replaces local PayPal settings and clears saved payer authorization. "
                                    + "Connect and link your payer locally afterward. No payment is made. "
                                    + "Turn off automatic settlement and finish or cancel any pending checkout first.")
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton("Apply arrangement", (d, which) -> {
                                boolean applied = current(token) && manager.activate(pack, selected, unlocked);
                                toast(applied ? "Arrangement applied. Verify PayPal in Dom Settings."
                                        : "Not applied. Check Dom access, turn off automatic settlement, and finish or cancel pending checkout.");
                                finished.run();
                            }).create();
                    dialog.setOnDismissListener(d -> {
                        unlocked.close();
                        if (pending == unlocked) pending = null;
                    });
                    showDialog(dialog);
                });
            });
        });
    }

    private void passwordDialog(boolean exporting, PasswordAction action) {
        LinearLayout fields = new LinearLayout(activity);
        fields.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        fields.setPadding(pad, 0, pad, 0);
        EditText password = passwordField("Passphrase (12–256 characters)");
        fields.addView(password);
        EditText confirmation = passwordField("Confirm passphrase");
        if (exporting) fields.addView(confirmation);
        dialog = com.subhub.app.util.ThemedDialogs.builder(activity)
                .setTitle(exporting ? "Encrypt PayPal for this arrangement" : "Unlock PayPal attachment")
                .setMessage(exporting
                        ? "Includes your current PayPal client ID, secret, environment and fallback link. "
                        + "Only share with a trusted recipient: their app can read the secret after unlocking. "
                        + "Use a strong, unique passphrase and send it separately. Other pack data is not encrypted."
                        : "Enter the passphrase supplied separately by your Dom. Nothing changes until you "
                        + "review and confirm the unlocked recipient.")
                .setView(fields).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(exporting ? "Encrypt" : "Unlock", null).create();
        AlertDialog entry = dialog;
        entry.setOnDismissListener(d -> { password.setText(""); confirmation.setText(""); });
        showDialog(entry);
        entry.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!allowed()) return;
            char[] chars = new char[password.length()];
            password.getText().getChars(0, chars.length, chars, 0);
            char[] repeat = new char[confirmation.length()];
            confirmation.getText().getChars(0, repeat.length, repeat, 0);
            boolean valid = chars.length >= 12 && chars.length <= 256
                    && (!exporting || Arrays.equals(chars, repeat));
            Arrays.fill(repeat, '\0');
            if (!valid) {
                Arrays.fill(chars, '\0');
                password.setError("Use 12–256 characters; confirmation must match."); return;
            }
            entry.dismiss();
            action.run(chars);
        });
    }

    private EditText passwordField(String hint) {
        EditText field = new EditText(activity);
        field.setHint(hint);
        com.subhub.app.util.UiIdentity.inputType(field, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        return field;
    }

    private void showDialog(AlertDialog value) {
        value.show();
    }
    private boolean allowed() {
        if (!ControllerPinManager.isDomModeActive()) { toast("Unlock Dom first."); return false; }
        if (busy) { toast("PayPal encryption is still running."); return false; }
        return !activity.isFinishing() && !activity.isDestroyed();
    }
    private boolean current(int token) {
        return token == generation && !activity.isFinishing() && !activity.isDestroyed()
                && ControllerPinManager.isDomModeActive();
    }
    void pause() {
        generation++;
        if (dialog != null) dialog.dismiss();
        dialog = null;
        if (pending != null) pending.close();
        pending = null;
    }
    void destroy() { pause(); worker.shutdownNow(); }
    boolean isWorking() { return busy; }
    private void toast(String value) { Toast.makeText(activity, value, Toast.LENGTH_LONG).show(); }
    private interface PasswordAction { void run(char[] password); }
}
