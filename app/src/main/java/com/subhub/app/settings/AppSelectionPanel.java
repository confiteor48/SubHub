package com.subhub.app.settings;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.util.AsyncUiScope;
import com.subhub.app.util.StateToggle;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** One shared selector for setup and Settings, including the optional dynamic All Apps scope. */
public final class AppSelectionPanel extends LinearLayout {
    private final StateToggle all;
    private final TextView count, allHelp;
    private final ProgressBar loading;
    private final ListView list;
    private final Set<String> included = new LinkedHashSet<>();
    private List<InstalledAppCatalog.Entry> entries;
    private IncludedAppsAdapter adapter;
    private AppModeManager apps;
    private AsyncUiScope jobs;
    private BooleanSupplier editable;
    private Runnable changed;
    private boolean rendering, countOnlyForOverride;

    public AppSelectionPanel(Context context) { this(context, null); }

    public AppSelectionPanel(Context context, AttributeSet attributes) {
        super(context, attributes);
        setOrientation(VERTICAL);
        all = new StateToggle(context);
        all.setId(R.id.all_apps);
        all.setText(R.string.app_selection_all);
        com.subhub.app.util.UiIdentity.textSize(all, R.dimen.ui_text_body);
        all.setMinHeight(dp(48));
        addView(all, new LayoutParams(-1, -2));
        allHelp = (TextView) android.view.LayoutInflater.from(context)
                .inflate(R.layout.view_form_help, this, false);
        allHelp.setText(R.string.app_selection_all_help);
        addView(allHelp);
        count = new TextView(context);
        count.setId(R.id.selected_count);
        count.setTextColor(context.getColor(R.color.text_secondary));
        com.subhub.app.util.UiIdentity.textSize(count, R.dimen.ui_text_label);
        count.setPadding(0, dp(8), 0, dp(8));
        addView(count, new LayoutParams(-1, -2));
        LinearLayout content = new LinearLayout(context);
        content.setId(R.id.app_list_content);
        content.setOrientation(VERTICAL);
        addView(content, new LayoutParams(-1, -2));
        loading = new ProgressBar(context);
        loading.setId(R.id.loading_apps);
        content.addView(loading, new LayoutParams(-1, dp(48)));
        list = new ListView(context);
        list.setId(R.id.app_list);
        list.setNestedScrollingEnabled(true);
        list.setDivider(null);
        list.setDividerHeight(dp(6));
        content.addView(list, new LayoutParams(-1, dp(360)));
        all.setEnabled(false);
        all.setOnCheckedChangeListener((button, selected) -> selectAll(selected));
    }

    public void setCountOnlyForOverride(boolean compact) {
        countOnlyForOverride = compact;
        refresh();
    }

    /** Setup owns a bounded viewport, so let its app list use all remaining height. */
    public void fillAvailableHeight() {
        LinearLayout content = findViewById(R.id.app_list_content);
        content.setLayoutParams(new LayoutParams(-1, 0, 1));
        list.setLayoutParams(new LayoutParams(-1, 0, 1));
    }

    public void bind(AppCompatActivity activity, BooleanSupplier editable, Runnable changed) {
        this.editable = editable;
        this.changed = changed;
        apps = new AppModeManager(activity);
        jobs = AsyncUiScope.forPage(activity);
        refresh();
    }

    /** Re-query metadata when returning from Android or another selector. */
    public void reload() {
        if (jobs == null) return;
        jobs.cancel("included-app-selector");
        entries = null;
        loading.setVisibility(VISIBLE);
        refresh();
        load();
    }

    public void load() {
        if (jobs == null || entries != null || jobs.isPending("included-app-selector")) return;
        Context app = getContext().getApplicationContext();
        jobs.load("included-app-selector", () -> InstalledAppCatalog.load(app), loaded -> {
            entries = loaded;
            loading.setVisibility(GONE);
            adapter = new IncludedAppsAdapter(getContext(), entries, included, (name, selected) -> {
                if (!editable.getAsBoolean()) { refresh(); return; }
                if (selected) included.add(name); else included.remove(name);
                apps.saveIncludedPackages(included);
                refresh();
                changed.run();
            });
            list.setAdapter(adapter);
            refresh();
        }, failure -> {
            loading.setVisibility(GONE);
            count.setText(R.string.settings_apps_unavailable);
        });
    }

    private void selectAll(boolean selected) {
        if (rendering || apps == null) return;
        if (entries == null || !editable.getAsBoolean()) { refresh(); return; }
        if (selected) {
            Set<String> packages = new LinkedHashSet<>();
            for (InstalledAppCatalog.Entry entry : entries) packages.add(entry.packageName);
            apps.saveAllApps(packages);
        } else apps.setAllAppsEnabled(false);
        refresh();
        changed.run();
    }

    public void refresh() {
        if (apps == null) return;
        included.clear(); included.addAll(apps.getSelectedPackages());
        rendering = true;
        all.setChecked(apps.isAllApps());
        all.setEnabled(entries != null && editable.getAsBoolean());
        allHelp.setVisibility(apps.isAllApps() ? VISIBLE : GONE);
        count.setVisibility(!countOnlyForOverride || apps.isAllApps() ? VISIBLE : GONE);
        count.setText(getResources().getQuantityString(R.plurals.apps_selected_count,
                included.size(), included.size()));
        rendering = false;
        if (adapter != null) {
            adapter.setEditing(editable.getAsBoolean());
            adapter.notifyDataSetChanged();
        }
    }

    public String selectionSummary() {
        return apps != null && apps.isAllApps() ? getContext().getString(R.string.app_selection_all)
                : getResources().getQuantityString(R.plurals.apps_selected_count, included.size(), included.size());
    }

    @Override protected void onDetachedFromWindow() {
        if (jobs != null) jobs.cancel("included-app-selector");
        super.onDetachedFromWindow();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
