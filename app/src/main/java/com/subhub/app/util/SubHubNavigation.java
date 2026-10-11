package com.subhub.app.util;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.ImageViewCompat;

import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeActivity;
import com.subhub.app.atmosphere.AtmosphereActivity;
import com.subhub.app.penance.PenanceActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.SettingsActivity;

import java.util.ArrayList;
import java.util.List;

/** Dom feature pages stay reachable even when disabled; Sub retains its safe surfaces. */
public final class SubHubNavigation {
    public static final String EXTRA_SECTION_HOME = "subhub.navigation.section_home";
    public enum Screen { HOME, CENSOR, LIMITS, MONEY, ATMOSPHERE, SETTINGS }

    private static final AccessibilityDelegateCompat TAB_ACCESSIBILITY =
            new AccessibilityDelegateCompat() {
                @Override public void onInitializeAccessibilityNodeInfo(View host,
                        AccessibilityNodeInfoCompat info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Button.class.getName());
                    info.setSelected(host.isSelected());
                }
            };

    private SubHubNavigation() {}

    public static void bind(Activity activity, View root, Screen active) {
        View navigation = root.findViewById(R.id.bottom_navigation);
        boolean domMode = ControllerPinManager.isDomModeActive();
        setVisible(navigation, true);
        setVisible(root.findViewById(R.id.nav_home), true);
        setVisible(root.findViewById(R.id.nav_censor), domMode);
        setVisible(root.findViewById(R.id.nav_limits), domMode);
        setVisible(root.findViewById(R.id.nav_money), domMode);
        setVisible(root.findViewById(R.id.nav_atmosphere), true);
        setVisible(root.findViewById(R.id.nav_settings), true);
        bindTab(activity, root.findViewById(R.id.nav_home),
                root.findViewById(R.id.nav_home_icon), root.findViewById(R.id.nav_home_label),
                active, Screen.HOME, MainActivity.class);
        bindTab(activity, root.findViewById(R.id.nav_censor),
                root.findViewById(R.id.nav_censor_icon), root.findViewById(R.id.nav_censor_label),
                active, Screen.CENSOR, SettingsActivity.class);
        bindTab(activity, root.findViewById(R.id.nav_limits),
                root.findViewById(R.id.nav_limits_icon), root.findViewById(R.id.nav_limits_label),
                active, Screen.LIMITS, AppModeActivity.class);
        bindTab(activity, root.findViewById(R.id.nav_money),
                root.findViewById(R.id.nav_money_icon), root.findViewById(R.id.nav_money_label),
                active, Screen.MONEY, PenanceActivity.class);
        bindTab(activity, root.findViewById(R.id.nav_atmosphere),
                root.findViewById(R.id.nav_atmosphere_icon),
                root.findViewById(R.id.nav_atmosphere_label),
                active, Screen.ATMOSPHERE, AtmosphereActivity.class);
        bindTab(activity, root.findViewById(R.id.nav_settings),
                root.findViewById(R.id.nav_settings_icon), root.findViewById(R.id.nav_settings_label),
                active, Screen.SETTINGS, GlobalSettingsActivity.class);
        bindNavigationLayout(navigation);
    }

    private static void bindNavigationLayout(View navigation) {
        if (navigation == null) return;
        // Bind once: Home refreshes its state repeatedly while a service is running.
        if (navigation.getTag(R.id.bottom_navigation) == null) {
            navigation.setTag(R.id.bottom_navigation, Boolean.TRUE);
            navigation.addOnLayoutChangeListener((view, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> updateNavigationLayout(view));
        }
        updateNavigationLayout(navigation);
    }

    private static void updateNavigationLayout(View navigation) {
        if (!(navigation instanceof GridLayout)
                || !(navigation.getParent() instanceof ViewGroup)) return;
        GridLayout tabs = (GridLayout) navigation;
        ViewGroup page = (ViewGroup) navigation.getParent();
        List<View> visibleTabs = new ArrayList<>();
        for (int index = 0; index < tabs.getChildCount(); index++) {
            View tab = tabs.getChildAt(index);
            if (tab.getVisibility() == View.VISIBLE) visibleTabs.add(tab);
        }
        if (visibleTabs.isEmpty()) return;
        int pageWidth = page.getWidth() > 0 ? page.getWidth()
                : navigation.getResources().getDisplayMetrics().widthPixels;
        int minimumTarget = navigation.getResources()
                .getDimensionPixelSize(R.dimen.control_min_height);
        int desiredMargin = navigation.getResources()
                .getDimensionPixelSize(R.dimen.bottom_nav_margin);
        int minimumMargin = navigation.getResources()
                .getDimensionPixelSize(R.dimen.bottom_nav_min_margin);
        int horizontalPadding = navigation.getPaddingLeft() + navigation.getPaddingRight();
        int available = Math.max(0, pageWidth - horizontalPadding - 2 * minimumMargin);
        int[] targetWidths = new int[visibleTabs.size()];
        for (int index = 0; index < visibleTabs.size(); index++) {
            ViewGroup tab = (ViewGroup) visibleTabs.get(index);
            TextView label = (TextView) tab.getChildAt(1);
            targetWidths[index] = Math.max(minimumTarget,
                    (int) Math.ceil(label.getPaint().measureText(label.getText().toString()))
                            + 2 * minimumMargin);
        }

        // Give long names their natural width. Enlarge the grid before splitting words
        // or shrinking the user's font setting; child order remains the navigation order.
        int columns = visibleTabs.size();
        int[] columnWidths;
        int naturalWidth;
        while (true) {
            columnWidths = new int[columns];
            for (int index = 0; index < targetWidths.length; index++) {
                columnWidths[index % columns] = Math.max(columnWidths[index % columns],
                        targetWidths[index]);
            }
            naturalWidth = 0;
            for (int width : columnWidths) naturalWidth += width;
            if (naturalWidth <= available || columns == 1) break;
            columns = columns == visibleTabs.size() && columns > 3
                    ? (columns + 1) / 2 : columns - 1;
        }
        ViewGroup.MarginLayoutParams params =
                (ViewGroup.MarginLayoutParams) navigation.getLayoutParams();
        int margin = Math.min(desiredMargin, Math.max(minimumMargin,
                (pageWidth - horizontalPadding - naturalWidth) / 2));
        if (params.leftMargin != margin || params.rightMargin != margin) {
            params.leftMargin = margin;
            params.rightMargin = margin;
            navigation.setLayoutParams(params);
        }
        int contentWidth = pageWidth - horizontalPadding - 2 * margin;
        int extra = contentWidth - naturalWidth;
        for (int column = 0; column < columns; column++) {
            columnWidths[column] = extra >= 0
                    ? columnWidths[column] + extra / columns + (column < extra % columns ? 1 : 0)
                    : contentWidth / columns;
        }
        int visibleIndex = 0;
        for (int index = 0; index < tabs.getChildCount(); index++) {
            View tab = tabs.getChildAt(index);
            boolean visible = tab.getVisibility() == View.VISIBLE;
            int row = visible ? visibleIndex / columns : 0;
            int column = visible ? visibleIndex % columns : 0;
            int width = visible ? columnWidths[column] : 0;
            GridLayout.LayoutParams tabParams = (GridLayout.LayoutParams) tab.getLayoutParams();
            GridLayout.Spec rowSpec = GridLayout.spec(row, GridLayout.FILL);
            GridLayout.Spec columnSpec = GridLayout.spec(column, GridLayout.FILL);
            if (tabParams.width != width || !tabParams.rowSpec.equals(rowSpec)
                    || !tabParams.columnSpec.equals(columnSpec)) {
                tabParams.width = width;
                tabParams.rowSpec = rowSpec;
                tabParams.columnSpec = columnSpec;
                tab.setLayoutParams(tabParams);
            }
            if (visible) visibleIndex++;
        }

        // Reserve the measured pill, including labels enlarged by the system font setting.
        // Padding belongs to the scroll content so the final control can scroll above it.
        int pillHeight = Math.max(navigation.getHeight(), navigation.getResources()
                .getDimensionPixelSize(R.dimen.bottom_nav_height));
        int clearance = pillHeight + params.bottomMargin + navigation.getResources()
                .getDimensionPixelSize(R.dimen.page_content_tail_gap);
        for (int index = 0; index < page.getChildCount(); index++) {
            View child = page.getChildAt(index);
            if (!(child instanceof ScrollView)) continue;
            ScrollView scroll = (ScrollView) child;
            if (scroll.getChildCount() == 0) continue;
            if (scroll.getPaddingBottom() != 0) {
                scroll.setPaddingRelative(scroll.getPaddingStart(), scroll.getPaddingTop(),
                        scroll.getPaddingEnd(), 0);
            }
            View content = scroll.getChildAt(0);
            if (content.getPaddingBottom() != clearance) {
                content.setPaddingRelative(content.getPaddingStart(), content.getPaddingTop(),
                        content.getPaddingEnd(), clearance);
            }
        }
    }

    private static void setVisible(View view, boolean visible) {
        if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private static void bindTab(Activity activity, View tab, ImageView icon, TextView label,
            Screen active, Screen destination, Class<? extends Activity> target) {
        if (tab == null) return;
        if (tab.getVisibility() != View.VISIBLE) return;
        boolean selected = destination == active;
        tab.setVisibility(View.VISIBLE);
        tab.setSelected(selected);
        tab.setAlpha(selected ? 1f : 0.82f);
        ViewCompat.setScreenReaderFocusable(tab, true);
        ViewCompat.setAccessibilityDelegate(tab, TAB_ACCESSIBILITY);
        if (label != null) label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (icon != null) icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (label != null) label.setTextColor(activity.getColor(R.color.text_primary));
        if (icon != null) ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(
                activity.getColor(selected ? R.color.accent : R.color.text_primary)));
        tab.setBackgroundResource(R.drawable.bg_bottom_tab_active);
        tab.setOnClickListener(view -> {
            if (selected && activity instanceof PenanceActivity) {
                ((PenanceActivity) activity).showSectionHome();
                return;
            }
            activity.startActivity(new Intent(activity, target)
                    .putExtra(EXTRA_SECTION_HOME, selected)
                    .addFlags(selected ? Intent.FLAG_ACTIVITY_CLEAR_TOP
                            : Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            boolean movingForward = destination.ordinal() > active.ordinal();
            activity.overridePendingTransition(
                    movingForward ? R.anim.subhub_page_enter : R.anim.subhub_page_pop_enter,
                    movingForward ? R.anim.subhub_page_exit : R.anim.subhub_page_pop_exit);
        });
    }
}
