package com.subhub.app.commitment;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;

import com.subhub.app.R;

import java.math.BigDecimal;

/**
 * Shared UI choices only. A duration is drawn and a lock starts through the existing service flow.
 */
public final class ServiceDurationView extends LinearLayout {
    public static final long PERMANENT = -1, RANDOM = -2;
    private static final long HOUR = 3_600_000;
    private final SharedPreferences preferences;
    private final TextView[] choices = new TextView[6];
    private final long[] durations = {
        HOUR, 24 * HOUR, 7 * 24 * HOUR, 30 * 24 * HOUR, PERMANENT, RANDOM
    };
    private final EditText minimum, maximum;
    private final com.subhub.app.util.StateToggle hidden;
    private final View range;
    private long selected = PERMANENT, savedMin = HOUR, savedMax = 24 * HOUR;
    private boolean updating, editing = true;

    public ServiceDurationView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setOrientation(VERTICAL);
        preferences =
                context.getApplicationContext()
                        .getSharedPreferences("subhub_service_duration_ui", Context.MODE_PRIVATE);
        LayoutInflater.from(context).inflate(R.layout.view_service_duration_selection, this, true);
        int[] ids = {
            R.id.commitment_timer_1h,
            R.id.commitment_timer_24h,
            R.id.commitment_timer_7d,
            R.id.commitment_timer_30d,
            R.id.commitment_timer_permanent,
            R.id.service_duration_random
        };
        range = findViewById(R.id.service_duration_range);
        minimum = findViewById(R.id.service_duration_min_hours);
        maximum = findViewById(R.id.service_duration_max_hours);
        hidden = findViewById(R.id.service_duration_hide);
        for (int index = 0; index < ids.length; index++) {
            choices[index] = findViewById(ids[index]);
            final long duration = durations[index];
            choices[index].setOnClickListener(
                    view -> {
                        if (!editing) return;
                        selected = duration;
                        persist();
                        render();
                    });
        }
        hidden.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (!updating && editing) persist();
                });
        TextWatcher changes =
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable value) {
                        if (!updating && editing) persist();
                    }
                };
        minimum.addTextChangedListener(changes);
        maximum.addTextChangedListener(changes);
        reload();
    }

    public void reload() {
        long mode = preferences.getLong("duration", PERMANENT);
        long min = preferences.getLong("minimum", HOUR),
                max = preferences.getLong("maximum", 24 * HOUR);
        boolean hide = preferences.getBoolean("hidden", false);
        if (mode != selected
                || min != savedMin
                || max != savedMax
                || hidden.isChecked() != hide
                || minimum.getText().length() == 0
                || maximum.getText().length() == 0) {
            updating = true;
            try {
                selected = mode;
                savedMin = min;
                savedMax = max;
                minimum.setText(hours(min));
                maximum.setText(hours(max));
                hidden.setChecked(hide);
            } finally {
                updating = false;
            }
        }
        render();
    }

    private void persist() {
        try {
            long min = PactDuration.hours(minimum.getText().toString());
            long max = PactDuration.hours(maximum.getText().toString());
            PactDuration.validate(min, max);
            savedMin = min;
            savedMax = max;
            maximum.setError(null);
        } catch (IllegalArgumentException incomplete) {
            // Keep incomplete input visible, but persist only valid bounds.
        }
        preferences
                .edit()
                .putLong("duration", selected)
                .putLong("minimum", savedMin)
                .putLong("maximum", savedMax)
                .putBoolean("hidden", hidden.isChecked())
                .apply();
    }

    public boolean validateSelection() {
        if (selected != RANDOM) return true;
        try {
            PactDuration.validate(
                    PactDuration.hours(minimum.getText().toString()),
                    PactDuration.hours(maximum.getText().toString()));
            persist();
            return true;
        } catch (IllegalArgumentException invalid) {
            com.subhub.app.util.InputFocusPolicy.showError(maximum,
                    getContext().getString(R.string.pact_range_invalid));
            return false;
        }
    }

    public boolean isCountdownHidden() { return hidden.isChecked(); }

    public Selection selection() {
        long min =
                selected == RANDOM
                        ? PactDuration.hours(minimum.getText().toString())
                        : Math.max(0, selected);
        long max = selected == RANDOM ? PactDuration.hours(maximum.getText().toString()) : min;
        return new Selection(min, max, hidden.isChecked());
    }

    public void setEditable(boolean enabled) {
        editing = enabled;
        render();
    }

    public int choiceAreaHeight() {
        int measured = 0;
        for (int i = 0; i < choices.length; i += 2) {
            View row = (View) choices[i].getParent();
            measured += row.getMeasuredHeight();
            if (i > 0) measured += ((android.view.ViewGroup.MarginLayoutParams) row.getLayoutParams()).topMargin;
        }
        return measured > 0 ? measured : Math.round(160 * getResources().getDisplayMetrics().density);
    }

    public void setHeadingVisible(boolean visible) {
        findViewById(R.id.service_duration_heading).setVisibility(visible ? VISIBLE : GONE);
    }

    private void render() {
        range.setVisibility(selected == RANDOM ? VISIBLE : GONE);
        hidden.setVisibility(VISIBLE);
        for (int index = 0; index < choices.length; index++) {
            TextView button = choices[index];
            boolean checked = selected == durations[index];
            if (button.isSelected() != checked || button.getTag() == null) {
                button.setTag(Boolean.TRUE);
                button.setSelected(checked);
                button.setBackgroundResource(
                        checked
                                ? R.drawable.bg_home_duration_selected
                                : R.drawable.bg_home_duration_idle);
                button.setTextColor(
                        getContext()
                                .getColor(checked ? R.color.text_primary : R.color.text_secondary));
            }
            button.setEnabled(editing);
        }
        minimum.setEnabled(editing);
        maximum.setEnabled(editing);
        hidden.setEnabled(editing);
    }

    private static String hours(long millis) {
        return BigDecimal.valueOf(millis)
                .divide(BigDecimal.valueOf(HOUR))
                .stripTrailingZeros()
                .toPlainString();
    }

    public static final class Selection {
        public final long minimum, maximum;
        public final boolean hidden;

        Selection(long minimum, long maximum, boolean hidden) {
            this.minimum = minimum;
            this.maximum = maximum;
            this.hidden = hidden;
        }
    }
}
