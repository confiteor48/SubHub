package com.subhub.app.util;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.subhub.app.R;
import java.util.function.Consumer;

/** Small themed composition helpers for focused settings and media screens. */
public abstract class PreferencePage extends AppCompatActivity {
    protected LinearLayout page;
    protected void page(int title) {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        int margin = getResources().getDimensionPixelSize(R.dimen.page_margin);
        page.setPadding(margin, getResources().getDimensionPixelSize(R.dimen.primary_header_top), margin, dp(32));
        scroll.addView(page); setContentView(scroll);
        View header = getLayoutInflater().inflate(R.layout.view_secondary_header, page, false);
        page.addView(header);
        PrimaryHeader.bindSecondary(header, title, false);
        PrimaryHeader.backButton(header).setOnClickListener(view -> finish());
        LinearLayout.LayoutParams headerParams = (LinearLayout.LayoutParams) header.getLayoutParams();
        headerParams.bottomMargin = getResources().getDimensionPixelSize(R.dimen.ui_gap_group);
        header.setLayoutParams(headerParams);
    }
    protected LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int inset = getResources().getDimensionPixelSize(R.dimen.ui_card_padding_compact);
        int gap = getResources().getDimensionPixelSize(R.dimen.ui_gap_group);
        card.setPadding(inset, gap, inset, gap);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = gap;
        parent.addView(card, params); return card;
    }
    protected TextView text(LinearLayout parent, CharSequence value, int size, boolean muted) {
        TextView view = new TextView(this); view.setText(value);
        int textSize = size >= 18 ? R.dimen.ui_text_title
                : size >= 15 ? R.dimen.ui_text_section
                : size == 14 ? R.dimen.ui_text_row_title
                : size == 13 ? R.dimen.ui_text_body
                : size == 12 ? R.dimen.ui_text_label : R.dimen.ui_text_caption;
        UiIdentity.textSize(view, textSize);
        view.setTextColor(getColor(muted ? R.color.text_secondary : R.color.text_primary));
        int gap = getResources().getDimensionPixelSize(R.dimen.ui_gap_inline);
        view.setPadding(0, gap, 0, gap); view.setLineSpacing(dp(2), 1);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    protected TextView fieldHelp(LinearLayout parent, CharSequence value) {
        TextView view = (TextView) getLayoutInflater().inflate(R.layout.view_form_help, parent, false);
        view.setText(value);
        parent.addView(view);
        return view;
    }
    protected TextView fieldLabel(LinearLayout parent, CharSequence value) {
        TextView view = (TextView) getLayoutInflater().inflate(R.layout.view_form_label, parent, false);
        view.setText(value);
        parent.addView(view);
        return view;
    }
    protected Button button(LinearLayout parent, CharSequence label, Runnable action) {
        Button button = (Button) getLayoutInflater().inflate(R.layout.view_ux_action, parent, false);
        button.setText(label); button.setAllCaps(false);
        button.setMinHeight(dp(48)); button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = getResources().getDimensionPixelSize(R.dimen.ui_gap_control);
        params.bottomMargin = 0;
        parent.addView(button, params); return button;
    }
    protected View toggle(LinearLayout parent, int label, boolean enabled, Consumer<Boolean> changed) {
        StateToggle toggle = (StateToggle) getLayoutInflater().inflate(R.layout.view_form_toggle, parent, false); toggle.setText(label);
        toggle.setChecked(enabled);
        toggle.setOnCheckedChangeListener((button, checked) -> changed.accept(checked));
        parent.addView(toggle, new LinearLayout.LayoutParams(-1, -2)); return toggle;
    }
    protected EditText input(LinearLayout parent, int hint, int inputType) {
        EditText input = (EditText) getLayoutInflater().inflate(R.layout.view_preference_input, parent, false);
        input.setSingleLine(); input.setHint(hint);
        com.subhub.app.util.UiIdentity.inputType(input, inputType);
        input.setMinHeight(dp(48));
        parent.addView(input); return input;
    }
    protected void notice(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    protected int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
