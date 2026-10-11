package com.subhub.app.stats;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

import com.subhub.app.R;
import com.subhub.app.penance.WalletCurrency;
import com.subhub.app.util.ThemedDialogs;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.*;

/** Daily activity chart and calendar, also reused by the compact Home summary. */
public final class DailyStatsPanel extends LinearLayout {
    private static final String[] METRICS = {
        "service_ms",
        "censors",
        "limits_ms",
        "limit_stops",
        "whispers",
        "popups",
        "photos",
        "videos",
        "assessed_EUR",
        "assessed_USD",
        "paid_EUR",
        "paid_USD"
    };
    private static final int[] LABELS = {
        R.string.daily_service,
        R.string.daily_censors,
        R.string.daily_limits_time,
        R.string.daily_limits_stops,
        R.string.daily_whispers,
        R.string.daily_popups,
        R.string.daily_photos,
        R.string.daily_videos,
        R.string.daily_assessed_eur,
        R.string.daily_assessed_usd,
        R.string.daily_paid_eur,
        R.string.daily_paid_usd
    };
    private final boolean compact;
    private int range = 30, metric, month = LocalDate.now().getMonthValue();
    private LocalDate selected = LocalDate.now();
    private long rendered;
    private Map<String, Map<String, Long>> data = Collections.emptyMap();
    private String since;
    private final com.subhub.app.util.AsyncUiScope uiData;
    private final String taskKey;

    public DailyStatsPanel(Context context, boolean compact) {
        this(context, compact, null);
    }

    public DailyStatsPanel(Context context, boolean compact, Bundle state) {
        super(context);
        this.compact = compact;
        if (compact) range = 7;
        uiData = com.subhub.app.util.AsyncUiScope.forContext(context);
        taskKey = "daily-panel:" + System.identityHashCode(this);
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(14), dp(16), dp(16));
        setBackgroundResource(R.drawable.bg_card);
        setId(R.id.daily_statistics_panel);
        restoreState(state);
        refresh();
    }

    public void save(Bundle state) {
        state.putInt("daily_range", range);
        state.putInt("daily_metric", metric);
        state.putInt("daily_month", month);
        state.putString("daily_selected", selected.toString());
    }

    public void restore(Bundle state) {
        restoreState(state);
        refresh();
    }

    private void restoreState(Bundle state) {
        if (state == null) return;
        int requested = state.getInt("daily_range", compact ? 7 : 30);
        range = requested == 7 || requested == 30 || requested == 365 ? requested : 30;
        metric = Math.max(0, Math.min(METRICS.length - 1, state.getInt("daily_metric", 0)));
        month =
                Math.max(
                        1,
                        Math.min(
                                LocalDate.now().getMonthValue(),
                                state.getInt("daily_month", month)));
        try {
            selected =
                    LocalDate.parse(state.getString("daily_selected", LocalDate.now().toString()));
        } catch (RuntimeException ignored) {
            selected = LocalDate.now();
        }
    }

    public void refreshIfNeeded() {
        if (android.os.SystemClock.elapsedRealtime() - rendered >= 15000) refresh();
    }

    public void refresh() {
        rendered = android.os.SystemClock.elapsedRealtime();
        LocalDate today = LocalDate.now();
        LocalDate first = range == 365 ? today.withDayOfYear(1) : today.minusDays(range - 1);
        Context app = getContext().getApplicationContext();
        uiData.load(
                taskKey,
                () -> {
                    DailyStatsStore store = DailyStatsStore.get(app);
                    DailyStatsStore.awaitWrites();
                    return new DailyData(
                            store.since(), store.days(first.toString(), today.toString()));
                },
                loaded -> {
                    since = loaded.since;
                    data = loaded.days;
                    render(today, first);
                },
                failure -> {
                    removeAllViews();
                    label(this, getContext().getString(R.string.daily_unavailable), 14, true);
                });
    }

    private void render(LocalDate today, LocalDate first) {
        removeAllViews();
        if (compact) {
            label(this, getContext().getString(R.string.daily_today), 18, false)
                    .setTypeface(null, Typeface.BOLD);
            calendar(first, today, today, true);
            TextView summary =
                    label(
                            this,
                            getContext()
                                    .getString(
                                            R.string.daily_compact_summary,
                                            format("service_ms", value(today, "service_ms")),
                                            value(today, "censors")),
                            14,
                            true);
            summary.setId(R.id.daily_day_summary);
            action(
                            this,
                            getContext().getString(R.string.daily_open),
                            () ->
                                    getContext()
                                            .startActivity(
                                                    new android.content.Intent(
                                                            getContext(), StatsActivity.class)))
                    .setId(R.id.daily_open_statistics);
            return;
        }
        LinearLayout ranges = new LinearLayout(getContext());
        addView(ranges, new LayoutParams(-1, -2));
        int[] counts = {7, 30, 365},
                ids = {R.id.daily_range_7, R.id.daily_range_30, R.id.daily_range_year};
        for (int i = 0; i < counts.length; i++) {
            final int count = counts[i];
            Button choice =
                    action(
                            ranges,
                            getContext()
                                    .getString(
                                            count == 7
                                                    ? R.string.daily_7
                                                    : count == 30
                                                            ? R.string.daily_30
                                                            : R.string.daily_year),
                            () -> {
                                range = count;
                                month = today.getMonthValue();
                                selected = today;
                                refresh();
                            });
            choice.setId(ids[i]);
            choice.setSelected(range == count);
            choice.setTextColor(
                    getContext()
                            .getColor(range == count ? R.color.text_primary : R.color.accent_hot));
            choice.setBackgroundResource(
                    range == count ? R.drawable.bg_primary_button : R.drawable.bg_outline_button);
        }
        String[] labels = new String[LABELS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = getContext().getString(LABELS[i]);
        Button filter =
                action(
                        this,
                        labels[metric] + "  ▾",
                        () ->
                                ThemedDialogs.builder(getContext())
                                        .setTitle(R.string.daily_filter)
                                        .setSingleChoiceItems(
                                                labels,
                                                metric,
                                                (dialog, which) -> {
                                                    metric = which;
                                                    dialog.dismiss();
                                                    refresh();
                                                })
                                        .show());
        filter.setId(R.id.daily_metric_filter);
        long total = 0;
        List<Long> trend = new ArrayList<>();
        LocalDate recorded = LocalDate.parse(since);
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            long v = value(day, METRICS[metric]);
            total = total > Long.MAX_VALUE - v ? Long.MAX_VALUE : total + v;
            if (!day.isBefore(recorded)) trend.add(v);
        }
        label(this, format(METRICS[metric], total), 32, false).setTypeface(null, Typeface.BOLD);
        label(
                this,
                getContext()
                        .getString(
                                range == 7
                                        ? R.string.stats_period_week
                                        : range == 30
                                                ? R.string.stats_period_month
                                                : R.string.stats_period_year),
                12,
                true);
        if (total > 0) {
            addView(new Trend(getContext(), trend), new LayoutParams(-1, dp(76)));
            LocalDate graphStart = recorded.isAfter(first) ? recorded : first;
            LinearLayout axis = new LinearLayout(getContext());
            addView(axis, new LayoutParams(-1, -2));
            TextView start = label(axis, pretty(graphStart), 11, true);
            start.setLayoutParams(new LayoutParams(0, -2, 1));
            if (!graphStart.equals(today)) {
                TextView end = label(axis, pretty(today), 11, true);
                end.setGravity(Gravity.END);
                end.setLayoutParams(new LayoutParams(0, -2, 1));
            }
        } else
            label(this, getContext().getString(R.string.stats_no_activity_period), 14, true)
                    .setId(R.id.stats_empty_activity);
        // Old lifetime totals have no dates. Never display an invented historical calendar.
        if (recorded.isAfter(first))
            label(
                    this,
                    getContext().getString(R.string.stats_history_from, pretty(recorded)),
                    12,
                    true);
        LocalDate calendarStart = first, calendarEnd = today;
        if (range == 365) {
            LinearLayout navigation = new LinearLayout(getContext());
            navigation.setGravity(Gravity.CENTER_VERTICAL);
            addView(navigation, new LayoutParams(-1, -2));
            Button previous =
                    action(
                            navigation,
                            "‹",
                            () -> {
                                month--;
                                selected = today.withMonth(month).withDayOfMonth(1);
                                refresh();
                            });
            previous.setEnabled(month > 1);
            previous.setContentDescription(getContext().getString(R.string.stats_previous_month));
            TextView name =
                    label(
                            navigation,
                            today.withMonth(month)
                                    .format(
                                            DateTimeFormatter.ofPattern(
                                                    "MMMM yyyy", Locale.getDefault())),
                            15,
                            false);
            name.setGravity(Gravity.CENTER);
            name.setLayoutParams(new LayoutParams(0, -2, 3));
            Button next =
                    action(
                            navigation,
                            "›",
                            () -> {
                                month++;
                                selected = today.withMonth(month).withDayOfMonth(1);
                                refresh();
                            });
            next.setEnabled(month < today.getMonthValue());
            next.setContentDescription(getContext().getString(R.string.stats_next_month));
            calendarStart = today.withMonth(month).withDayOfMonth(1);
            calendarEnd = calendarStart.withDayOfMonth(calendarStart.lengthOfMonth());
            if (calendarEnd.isAfter(today)) calendarEnd = today;
        }
        if (calendarEnd.isBefore(recorded)) {
            label(this, getContext().getString(R.string.stats_no_daily_month), 14, true);
            return;
        }
        if (calendarStart.isBefore(recorded)) calendarStart = recorded;
        if (selected.isBefore(calendarStart) || selected.isAfter(calendarEnd))
            selected = calendarEnd;
        calendar(calendarStart, calendarEnd, selected, false);
        TextView detail =
                label(
                        this,
                        pretty(selected)
                                + " · "
                                + format(METRICS[metric], value(selected, METRICS[metric])),
                        16,
                        false);
        detail.setTypeface(null, Typeface.BOLD);
        detail.setId(R.id.daily_day_summary);
    }

    private void calendar(LocalDate first, LocalDate last, LocalDate selection, boolean week) {
        GridLayout grid = new GridLayout(getContext());
        grid.setColumnCount(7);
        LayoutParams gp = new LayoutParams(-1, -2);
        gp.topMargin = dp(12);
        gp.bottomMargin = dp(8);
        addView(grid, gp);
        java.time.DayOfWeek start = WeekFields.of(Locale.getDefault()).getFirstDayOfWeek();
        int offset =
                week ? 0 : Math.floorMod(first.getDayOfWeek().getValue() - start.getValue(), 7);
        if (!week) {
            for (int i = 0; i < 7; i++) {
                java.time.DayOfWeek d = start.plus(i);
                TextView title = new TextView(getContext());
                title.setText(
                        d.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()));
                title.setGravity(Gravity.CENTER);
                com.subhub.app.util.UiIdentity.textSize(title, R.dimen.ui_text_caption);
                title.setTextColor(getContext().getColor(R.color.text_secondary));
                cell(grid, title, i, dp(24));
            }
        }
        int index = week ? 0 : 7;
        for (int i = 0; i < offset; i++) {
            View blank = new View(getContext());
            blank.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cell(grid, blank, index++, dp(44));
        }
        long maximum = 1;
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1))
            maximum = Math.max(maximum, value(d, METRICS[metric]));
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            final LocalDate chosen = d;
            long amount = value(d, METRICS[metric]);
            boolean known = d.toString().compareTo(since) >= 0 && !d.isAfter(LocalDate.now());
            TextView day = new TextView(getContext());
            day.setText(Integer.toString(d.getDayOfMonth()));
            com.subhub.app.util.UiIdentity.textSize(day, R.dimen.ui_text_body);
            day.setGravity(Gravity.CENTER);
            day.setTextColor(getContext().getColor(R.color.text_primary));
            android.graphics.drawable.GradientDrawable tile =
                    new android.graphics.drawable.GradientDrawable();
            tile.setCornerRadius(dp(8));
            int alpha = amount == 0 ? 20 : 65 + (int) (155 * Math.sqrt(amount / (double) maximum));
            tile.setColor((getContext().getColor(R.color.accent) & 0x00ffffff) | (alpha << 24));
            if (d.equals(selection))
                tile.setStroke(dp(2), getContext().getColor(R.color.accent_hot));
            day.setBackground(tile);
            day.setAlpha(known ? 1f : .3f);
            day.setEnabled(known);
            day.setFocusable(known);
            day.setContentDescription(
                    d
                            + ": "
                            + (known
                                    ? getContext().getString(LABELS[metric])
                                            + " "
                                            + format(METRICS[metric], amount)
                                    : getContext().getString(R.string.daily_no_history)));
            if (known && !week)
                day.setOnClickListener(
                        v -> {
                            selected = chosen;
                            refresh();
                        });
            cell(grid, day, index++, dp(week ? 44 : 48));
        }
    }

    private void cell(GridLayout grid, View v, int index, int height) {
        GridLayout.LayoutParams p =
                new GridLayout.LayoutParams(
                        GridLayout.spec(index / 7), GridLayout.spec(index % 7, 1f));
        p.width = 0;
        p.height = height;
        p.setMargins(dp(2), dp(2), dp(2), dp(2));
        grid.addView(v, p);
    }

    private long value(LocalDate day, String metric) {
        Map<String, Long> row = data.get(day.toString());
        return row == null ? 0 : row.getOrDefault(metric, 0L);
    }

    private String format(String metric, long value) {
        if (metric.endsWith("_ms"))
            return value > 0 && value < 60000
                    ? getContext().getString(R.string.stats_seconds_value, (value + 999) / 1000)
                    : StatsSnapshot.formatDuration(value / 1000);
        if (metric.endsWith("_EUR") || metric.endsWith("_USD")) {
            String currency = metric.substring(metric.length() - 3);
            return WalletCurrency.format(currency, value) + " " + currency;
        }
        return java.text.NumberFormat.getIntegerInstance().format(value);
    }

    private String pretty(LocalDate day) {
        return day.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM));
    }

    private TextView label(LinearLayout parent, String value, int size, boolean muted) {
        TextView t = new TextView(getContext());
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(
                getContext().getColor(muted ? R.color.text_secondary : R.color.text_primary));
        t.setPadding(0, dp(5), 0, dp(5));
        parent.addView(t, new LayoutParams(-1, -2));
        return t;
    }

    private Button action(LinearLayout parent, String title, Runnable run) {
        Button b =
                (Button)
                        LayoutInflater.from(getContext())
                                .inflate(R.layout.view_ux_action, parent, false);
        b.setText(title);
        b.setAllCaps(false);
        b.setOnClickListener(v -> run.run());
        LayoutParams p = parent == this ? new LayoutParams(-1, -2) : new LayoutParams(0, -2, 1);
        p.setMargins(dp(2), dp(5), dp(2), dp(4));
        parent.addView(b, p);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class DailyData {
        final String since;
        final Map<String, Map<String, Long>> days;

        DailyData(String since, Map<String, Map<String, Long>> days) {
            this.since = since;
            this.days = days;
        }
    }

    private final class Trend extends View {
        private final List<Long> values;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Trend(Context c, List<Long> values) {
            super(c);
            this.values = values;
            setContentDescription(c.getString(R.string.daily_trend));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            long max = 1;
            for (long v : values) max = Math.max(max, v);
            paint.setColor(getContext().getColor(R.color.accent));
            float step = getWidth() / (float) Math.max(1, values.size());
            for (int i = 0; i < values.size(); i++) {
                long v = values.get(i);
                if (v <= 0) continue;
                float h = (getHeight() - dp(4)) * v / (float) max;
                canvas.drawRoundRect(
                        i * step,
                        getHeight() - h,
                        Math.max(i * step + 1, (i + 1) * step - dp(2)),
                        getHeight(),
                        dp(2),
                        dp(2),
                        paint);
            }
        }
    }
}
