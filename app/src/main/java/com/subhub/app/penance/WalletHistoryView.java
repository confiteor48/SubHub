package com.subhub.app.penance;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.subhub.app.R;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Compact recent ledger rows rebuild only when displayed data changes. */
public final class WalletHistoryView extends LinearLayout {
    private String receipt = "";

    public WalletHistoryView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setOrientation(VERTICAL);
    }

    public int bind(List<PenanceEvent> events, long now, int limit) {
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        List<WalletHistoryGroups.Group> groups = WalletHistoryGroups.group(events, now, zone);
        int shown = Math.min(Math.max(0, limit), groups.size());
        StringBuilder next = new StringBuilder().append(limit).append(zone.getId())
                .append(getResources().getConfiguration().getLocales().get(0).toLanguageTag());
        for (int i = 0; i < shown; i++) {
            WalletHistoryGroups.Group group = groups.get(i);
            PenanceEvent e = group.latest;
            next.append(e.getCreatedAtMillis())
                    .append("|")
                    .append(e.getInfraction())
                    .append("|")
                    .append(group.count)
                    .append("|")
                    .append(group.amountCents)
                    .append("|")
                    .append(e.getCurrency())
                    .append("|")
                    .append(e.getStatus())
                    .append("|")
                    .append(e.isInMercy(now))
                    .append("|")
                    .append(android.text.format.DateUtils.isToday(e.getCreatedAtMillis()))
                    .append(";");
        }
        if (receipt.contentEquals(next)) return groups.size();
        receipt = next.toString();
        removeAllViews();
        DateFormat date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (int i = 0; i < shown; i++) {
            WalletHistoryGroups.Group group = groups.get(i);
            PenanceEvent e = group.latest;
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(VERTICAL);
            if (i > 0) {
                android.view.View line = new android.view.View(getContext());
                line.setBackgroundResource(R.color.outline_subtle);
                addView(line, new LayoutParams(-1, dp(1)));
            }
            row.setPadding(0, dp(10), 0, dp(10));
            addView(row, new LayoutParams(-1, -2));
            LinearLayout header = new LinearLayout(getContext());
            boolean stack = getResources().getConfiguration().fontScale > 1.4f;
            header.setOrientation(stack ? VERTICAL : HORIZONTAL);
            row.addView(header, new LayoutParams(-1, -2));
            TextView reason =
                    label(
                            (group.count == 1
                                    ? labelFor(e.getInfraction())
                                    : getContext()
                                            .getString(
                                                    R.string.wallet_history_group_label,
                                                    labelFor(e.getInfraction()),
                                                    group.count)),
                            13,
                            false);
            header.addView(reason, new LayoutParams(stack ? -1 : 0, -2, stack ? 0 : 1));
            TextView amount =
                    label(WalletCurrency.format(e.getCurrency(), group.amountCents), 16, false);
            amount.setTypeface(null, android.graphics.Typeface.BOLD);
            if (!stack) amount.setGravity(Gravity.END);
            header.addView(amount, new LayoutParams(stack ? -1 : -2, -2));
            int status =
                    e.isInMercy(now)
                            ? R.string.wallet_status_correction
                            : e.getStatus() == PenanceEvent.Status.OPEN
                                    ? R.string.wallet_status_due
                                    : e.getStatus() == PenanceEvent.Status.CHECKOUT
                                            ? R.string.wallet_status_pending
                                            : e.getStatus() == PenanceEvent.Status.PAID
                                                    ? R.string.wallet_status_paid
                                                    : R.string.wallet_status_forgiven;
            TextView detail =
                    label(
                            (android.text.format.DateUtils.isToday(e.getCreatedAtMillis())
                                            ? getContext()
                                                    .getString(
                                                            R.string.wallet_today_time,
                                                            DateFormat.getTimeInstance(
                                                                            DateFormat.SHORT)
                                                                    .format(
                                                                            new Date(
                                                                                    e
                                                                                            .getCreatedAtMillis())))
                                            : date.format(new Date(e.getCreatedAtMillis())))
                                    + " · "
                                    + getContext().getString(status),
                            11,
                            true);
            LayoutParams detailParams = new LayoutParams(-1, -2);
            detailParams.topMargin = dp(4);
            row.addView(detail, detailParams);
        }
        return groups.size();
    }

    private String labelFor(PenanceInfraction kind) {
        int id =
                switch (kind) {
                    case PAID_PAUSE -> R.string.paid_pause_history_label;
                    case CENSORED_DWELL -> R.string.penance_history_dwell;
                    case CENSORED_TAP -> R.string.penance_history_tap;
                    case WATCHED_APP_OPEN -> R.string.penance_history_app_open;
                    case TAMPER_ATTEMPT -> R.string.penance_history_tamper;
                    default -> R.string.penance_history_detection;
                };
        String label = getContext().getString(id);
        return label.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + label.substring(1);
    }

    private TextView label(String value, int size, boolean muted) {
        TextView text = new TextView(getContext());
        text.setText(value);
        com.subhub.app.util.UiIdentity.textSize(text, size == 16 ? R.dimen.ui_text_section
                : size == 11 ? R.dimen.ui_text_caption : R.dimen.ui_text_body);
        text.setTextColor(
                getContext().getColor(muted ? R.color.text_secondary : R.color.text_primary));
        return text;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
