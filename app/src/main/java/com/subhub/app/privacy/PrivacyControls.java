package com.subhub.app.privacy;

import android.widget.*;

import androidx.biometric.*;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.subhub.app.R;
import com.subhub.app.security.*;
import com.subhub.app.util.StateToggle;

/** Inline privacy controls, also reused by the direct privacy screen. */
public final class PrivacyControls {
    private final FragmentActivity activity;
    private final LinearLayout container;
    private final Runnable changed;
    private BiometricPrompt prompt;
    private StateToggle discreet, lock;
    private boolean updating;

    private PrivacyControls(FragmentActivity activity, LinearLayout container, Runnable changed) {
        this.activity = activity;
        this.container = container;
        this.changed = changed;
    }

    public static void bind(FragmentActivity activity, LinearLayout container, Runnable changed) {
        if (container.getTag() instanceof PrivacyControls) {
            ((PrivacyControls) container.getTag()).refresh();
            return;
        }
        PrivacyControls controls = new PrivacyControls(activity, container, changed);
        container.setTag(controls);
        controls.render();
    }

    private void render() {
        container.removeAllViews();
        PrivacyManager privacy = new PrivacyManager(activity);
        discreet =
                toggle(
                        R.string.privacy_discreet_mode,
                        R.id.privacy_discreet_toggle,
                        privacy.isDiscreet(),
                        value -> {
                            changed.run();
                            ControllerPinGate.require(
                                    activity,
                                    () -> {
                                        if (!privacy.setDiscreet(value))
                                            notice(
                                                    activity.getString(
                                                            R.string.privacy_save_failed));
                                        changed.run();
                                    },
                                    false);
                        });
        help(R.string.privacy_discreet_help);
        lock =
                toggle(
                        R.string.privacy_app_lock,
                        R.id.privacy_lock_toggle,
                        privacy.isAppLockEnabled(),
                        value -> {
                            changed.run();
                            ControllerPinGate.require(
                                    activity,
                                    () -> {
                                        if (!value) {
                                            privacy.setAppLock(false);
                                            changed.run();
                                            return;
                                        }
                                        if (BiometricManager.from(activity)
                                                        .canAuthenticate(
                                                                AppUnlockActivity.AUTHENTICATORS)
                                                != BiometricManager.BIOMETRIC_SUCCESS) {
                                            notice(
                                                    activity.getString(
                                                            R.string.privacy_no_device_lock));
                                            return;
                                        }
                                        prompt =
                                                new BiometricPrompt(
                                                        activity,
                                                        ContextCompat.getMainExecutor(activity),
                                                        new BiometricPrompt
                                                                .AuthenticationCallback() {
                                                            @Override
                                                            public void onAuthenticationSucceeded(
                                                                    BiometricPrompt
                                                                                    .AuthenticationResult
                                                                            result) {
                                                                if (ControllerPinManager
                                                                        .isDomModeActive()) {
                                                                    PrivacyLifecycle.unlock();
                                                                    privacy.setAppLock(true);
                                                                    changed.run();
                                                                }
                                                            }

                                                            @Override
                                                            public void onAuthenticationError(
                                                                    int error,
                                                                    CharSequence message) {
                                                                notice(message.toString());
                                                                changed.run();
                                                            }
                                                        });
                                        prompt.authenticate(
                                                new BiometricPrompt.PromptInfo.Builder()
                                                        .setTitle(
                                                                activity.getString(
                                                                        R.string
                                                                                .privacy_enable_lock))
                                                        .setAllowedAuthenticators(
                                                                AppUnlockActivity.AUTHENTICATORS)
                                                        .build());
                                    },
                                    false);
                        });
        help(R.string.privacy_lock_help);
        refresh();
    }

    private StateToggle toggle(
            int label, int id, boolean value, java.util.function.Consumer<Boolean> action) {
        StateToggle control = new StateToggle(activity);
        control.setId(id);
        control.setText(label);
        com.subhub.app.util.UiIdentity.textSize(control, R.dimen.ui_text_body);
        control.setChecked(value);
        control.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (!updating) action.accept(checked);
                });
        container.addView(control, new LinearLayout.LayoutParams(-1, -2));
        return control;
    }

    private void refresh() {
        ControllerPinGate.markLocked(discreet);
        ControllerPinGate.markLocked(lock);
        PrivacyManager privacy = new PrivacyManager(activity);
        updating = true;
        try {
            discreet.setChecked(privacy.isDiscreet());
            lock.setChecked(privacy.isAppLockEnabled());
        } finally {
            updating = false;
        }
    }

    private void help(int label) {
        TextView text = (TextView) android.view.LayoutInflater.from(activity)
                .inflate(R.layout.view_form_help, container, false);
        text.setText(label);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) text.getLayoutParams();
        params.bottomMargin = activity.getResources().getDimensionPixelSize(R.dimen.ui_gap_control);
        container.addView(text, params);
    }

    private void notice(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }
}
