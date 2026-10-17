package fun.nyama.tv;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Shared Nyama+ top-level navigation rail. */
public final class AppSidebar extends LinearLayout {
    public static final String SECTION_TV = "tv";
    public static final String SECTION_MOVIES = "movies";
    public static final String SECTION_SERIES = "series";
    public static final String SECTION_SETTINGS = "settings";

    public interface Listener {
        void onTv();
        void onMovies();
        void onSeries();
        void onSettings();
    }

    private final Context context;
    private final Config config;
    private final int accent;
    private final String activeSection;
    private final boolean compactPhonePortrait;
    private final boolean horizontalPhoneTabs;
    private final boolean mobileLandscape;

    private final List<LinearLayout> menuItems = new ArrayList<>();
    private final List<TextView> menuLabels = new ArrayList<>();
    private final List<Runnable> menuActions = new ArrayList<>();
    private View firstItem;
    private int focusedIndex;
    private boolean expanded = true;
    private ValueAnimator labelAnimator;

    private TextView brandView;
    private TextView sectionView;
    private TextView accountView;

    public AppSidebar(Context context, String activeSection, Listener listener) {
        this(context, activeSection, listener, false);
    }

    /** Use phonePortraitTabs only for the live-TV portrait browser. */
    public AppSidebar(Context context, String activeSection, Listener listener, boolean phonePortraitTabs) {
        super(context);
        this.context = context;
        this.config = new Config(context);
        this.accent = UiTheme.accent(context);
        this.activeSection = activeSection == null ? SECTION_TV : activeSection;
        this.compactPhonePortrait = config.isPhoneMode()
                && getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        this.horizontalPhoneTabs = compactPhonePortrait && phonePortraitTabs;
        this.mobileLandscape = config.isPhoneMode()
                && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        // Only the live-TV phone browser opts into the horizontal tab presentation.
        setOrientation(horizontalPhoneTabs ? HORIZONTAL : VERTICAL);
        setGravity(horizontalPhoneTabs ? Gravity.CENTER : Gravity.TOP);
        int horizontalPadding = horizontalPhoneTabs ? 4 : (compactPhonePortrait ? 8 : (config.isPhoneMode() ? 10 : 8));
        setPadding(dp(horizontalPadding),
                dp(horizontalPhoneTabs ? 4 : (compactPhonePortrait ? 8 : (config.isPhoneMode() ? 14 : 18))),
                dp(horizontalPadding),
                dp(horizontalPhoneTabs ? 4 : (compactPhonePortrait ? 8 : (config.isPhoneMode() ? 12 : 18))));
        setClipChildren(false);
        setClipToPadding(false);
        setBackgroundColor(horizontalPhoneTabs ? 0xE60A0C0F : 0xD90A0C0F);

        if (!compactPhonePortrait) {
            brandView = new TextView(context);
            brandView.setText("NYAMA+");
            brandView.setTextColor(Color.WHITE);
            brandView.setTextSize(UiTheme.sp(context, config.isPhoneMode() ? 18 : 22));
            brandView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            brandView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            addView(brandView, new LayoutParams(-1, dp(config.isPhoneMode() ? 40 : 48)));

            sectionView = new TextView(context);
            sectionView.setText(config.isPhoneMode() ? "" : context.getString(R.string.home_subtitle));
            sectionView.setTextColor(0xFF8A949F);
            sectionView.setTextSize(UiTheme.sp(context, 10));
            sectionView.setSingleLine(true);
            LayoutParams subtitle = new LayoutParams(-1, dp(config.isPhoneMode() ? 8 : 24));
            subtitle.bottomMargin = dp(config.isPhoneMode() ? 4 : 8);
            addView(sectionView, subtitle);
        }

        LinearLayout tv = addItem("▣", R.drawable.ic_nav_tv, context.getString(R.string.home_tv), SECTION_TV, listener::onTv);
        LinearLayout movies = addItem("▶", R.drawable.ic_nav_movies, context.getString(R.string.home_movies), SECTION_MOVIES, listener::onMovies);
        LinearLayout series = addItem("▤", R.drawable.ic_nav_series, context.getString(R.string.home_series), SECTION_SERIES, listener::onSeries);
        LinearLayout settings = addItem("⚙", R.drawable.ic_settings, context.getString(R.string.settings_title), SECTION_SETTINGS, listener::onSettings);

        if (SECTION_MOVIES.equals(this.activeSection)) { firstItem = movies; focusedIndex = 1; }
        else if (SECTION_SERIES.equals(this.activeSection)) { firstItem = series; focusedIndex = 2; }
        else if (SECTION_SETTINGS.equals(this.activeSection)) { firstItem = settings; focusedIndex = 3; }
        else { firstItem = tv; focusedIndex = 0; }

        if (!compactPhonePortrait) {
            Space spacer = new Space(context);
            addView(spacer, new LayoutParams(1, 0, 1f));
            String username = username();
            if (!username.isEmpty()) {
                accountView = new TextView(context);
                accountView.setText(context.getString(R.string.account_user, username));
                accountView.setTextColor(0xFF818B96);
                accountView.setTextSize(UiTheme.sp(context, config.isPhoneMode() ? 10 : 11));
                accountView.setMaxLines(2);
                addView(accountView, new LayoutParams(-1, -2));
            }
        }
    }

    public void focusFirst() {
        if (firstItem != null) firstItem.requestFocus();
    }

    public boolean hasMenuFocus() {
        return findFocusedIndex() >= 0;
    }

    public boolean moveFocus(int delta) {
        if (menuItems.isEmpty()) return false;
        int current = findFocusedIndex();
        if (current < 0) current = Math.max(0, Math.min(menuItems.size() - 1, focusedIndex));
        int next = Math.max(0, Math.min(menuItems.size() - 1, current + delta));
        focusedIndex = next;
        menuItems.get(next).requestFocus();
        return true;
    }

    public boolean activateFocused() {
        if (menuItems.isEmpty()) return false;
        int index = findFocusedIndex();
        if (index < 0) index = Math.max(0, Math.min(menuItems.size() - 1, focusedIndex));
        focusedIndex = index;
        menuActions.get(index).run();
        return true;
    }

    private int findFocusedIndex() {
        for (int i = 0; i < menuItems.size(); i++) if (menuItems.get(i).hasFocus()) return i;
        return -1;
    }

    /** Collapse to icons only, or expand to icons + text. */
    public void setExpanded(boolean expanded, boolean animate) {
        // The portrait-phone tab strip is always fully visible and never folds.
        if (horizontalPhoneTabs) {
            this.expanded = true;
            return;
        }
        this.expanded = expanded;
        if (labelAnimator != null) labelAnimator.cancel();
        float from = menuLabels.isEmpty() ? 1f : menuLabels.get(0).getAlpha();
        float to = expanded ? 1f : 0f;

        if (!animate) {
            for (TextView label : menuLabels) {
                label.setAlpha(to);
                label.setVisibility(expanded ? VISIBLE : GONE);
            }
            for (LinearLayout item : menuItems) item.setGravity(expanded ? Gravity.CENTER_VERTICAL : Gravity.CENTER);
            setChromeVisible(expanded, to);
            return;
        }

        if (expanded) {
            for (TextView label : menuLabels) label.setVisibility(VISIBLE);
            setChromeVisible(true, 0f);
        }
        labelAnimator = ValueAnimator.ofFloat(from, to);
        labelAnimator.setDuration(125L);
        labelAnimator.setInterpolator(new DecelerateInterpolator());
        labelAnimator.addUpdateListener(a -> {
            float alpha = (float) a.getAnimatedValue();
            for (TextView label : menuLabels) label.setAlpha(alpha);
            setChromeAlpha(alpha);
        });
        labelAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (!AppSidebar.this.expanded) {
                    for (TextView label : menuLabels) label.setVisibility(GONE);
                    setChromeVisible(false, 0f);
                } else {
                    setChromeVisible(true, 1f);
                }
                for (LinearLayout item : menuItems) {
                    item.setGravity(AppSidebar.this.expanded ? Gravity.CENTER_VERTICAL : Gravity.CENTER);
                }
            }
        });
        labelAnimator.start();
    }

    public boolean isExpanded() { return expanded; }

    /** Width needed by the mobile-landscape rail without truncating its longest localized label. */
    public int recommendedMobileLandscapeWidthDp() {
        if (!mobileLandscape) return 112;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(UiTheme.sp(context, 13) * getResources().getDisplayMetrics().scaledDensity);
        float widest = 0f;
        String[] labels = {
                context.getString(R.string.home_tv),
                context.getString(R.string.home_movies),
                context.getString(R.string.home_series),
                context.getString(R.string.settings_title)
        };
        for (String label : labels) widest = Math.max(widest, paint.measureText(mobileLandscapeLabel(label)));
        int labelDp = (int) Math.ceil(widest / getResources().getDisplayMetrics().density);
        // Outer/item padding + the TV vector icon + the gap before the label.
        return Math.max(148, labelDp + 20 + 14 + 32 + 5);
    }

    private LinearLayout addItem(String phoneIconText, int tvIconRes, String label, String section, Runnable action) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(horizontalPhoneTabs ? VERTICAL : HORIZONTAL);
        item.setGravity(horizontalPhoneTabs ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        int itemPad = horizontalPhoneTabs ? 2 : (compactPhonePortrait ? 5 : (config.isPhoneMode() ? 7 : 4));
        item.setPadding(dp(itemPad), 0, dp(itemPad), 0);
        item.setClipChildren(false);
        item.setClipToPadding(false);
        item.setFocusable(true);
        item.setClickable(true);

        final View icon;
        if (config.isPhoneMode() && !mobileLandscape) {
            TextView textIcon = new TextView(context);
            textIcon.setText(phoneIconText);
            textIcon.setTextColor(0xFFD8DEE6);
            textIcon.setTextSize(UiTheme.sp(context, horizontalPhoneTabs ? 17 : (compactPhonePortrait ? 16 : 17)));
            textIcon.setGravity(Gravity.CENTER);
            textIcon.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            textIcon.setIncludeFontPadding(false);
            if (horizontalPhoneTabs) item.addView(textIcon, new LayoutParams(-1, dp(24)));
            else item.addView(textIcon, new LayoutParams(dp(compactPhonePortrait ? 30 : 36), -1));
            icon = textIcon;
        } else {
            ImageView imageIcon = new ImageView(context);
            imageIcon.setImageResource(tvIconRes);
            imageIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            imageIcon.setPadding(dp(3), dp(3), dp(3), dp(3));
            imageIcon.setColorFilter(0xFFD8DEE6);
            item.addView(imageIcon, new LayoutParams(dp(32), -1));
            icon = imageIcon;
        }

        TextView text = new TextView(context);
        text.setText(mobileLandscapeLabel(label));
        text.setTextColor(0xFFD8DEE6);
        text.setTextSize(UiTheme.sp(context, horizontalPhoneTabs ? 10 : (compactPhonePortrait ? 12 : (config.isPhoneMode() ? 13 : 15))));
        text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.setGravity(horizontalPhoneTabs ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        text.setSingleLine(true);
        LayoutParams tlp;
        if (horizontalPhoneTabs) {
            tlp = new LayoutParams(-1, 0, 1f);
            tlp.topMargin = dp(1);
        } else {
            tlp = new LayoutParams(0, -1, 1f);
            tlp.leftMargin = dp(5);
        }
        item.addView(text, tlp);

        boolean active = section.equals(activeSection);
        applyBackground(item, icon, text, active, false);
        item.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                int idx = menuItems.indexOf(item);
                if (idx >= 0) focusedIndex = idx;
            }
            applyBackground(item, icon, text, active, focused);
        });
        item.setOnClickListener(v -> action.run());
        menuItems.add(item);
        menuLabels.add(text);
        menuActions.add(action);

        LayoutParams lp;
        if (horizontalPhoneTabs) {
            lp = new LayoutParams(0, -1, 1f);
            lp.leftMargin = dp(2);
            lp.rightMargin = dp(2);
        } else {
            lp = new LayoutParams(-1, dp(compactPhonePortrait ? 38 : (config.isPhoneMode() ? 46 : 50)));
            lp.topMargin = dp(compactPhonePortrait ? 3 : 4);
        }
        addView(item, lp);
        return item;
    }

    private String mobileLandscapeLabel(String label) {
        return mobileLandscape
                ? label.toUpperCase(getResources().getConfiguration().getLocales().get(0))
                : label;
    }

    private void applyBackground(LinearLayout item, View icon, TextView label, boolean active, boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(horizontalPhoneTabs ? 9 : 10));
        bg.setColor(horizontalPhoneTabs ? 0x44151A20 : 0x33151A20);
        if (focused) {
            bg.setColor((accent & 0x00FFFFFF) | 0x33000000);
            bg.setStroke(dp(2), accent);
            setIconColor(icon, Color.WHITE);
            label.setTextColor(Color.WHITE);
        } else if (active) {
            if (horizontalPhoneTabs) bg.setColor((accent & 0x00FFFFFF) | 0x22000000);
            bg.setStroke(dp(1), (accent & 0x00FFFFFF) | 0xAA000000);
            setIconColor(icon, accent);
            label.setTextColor(accent);
        } else {
            bg.setStroke(dp(1), 0x332A3139);
            setIconColor(icon, 0xFFD8DEE6);
            label.setTextColor(0xFFD8DEE6);
        }
        item.setBackground(bg);
    }

    private void setIconColor(View icon, int color) {
        if (icon instanceof ImageView) ((ImageView) icon).setColorFilter(color);
        else if (icon instanceof TextView) ((TextView) icon).setTextColor(color);
    }

    private void setChromeVisible(boolean visible, float alpha) {
        if (brandView != null) { brandView.setVisibility(visible ? VISIBLE : GONE); brandView.setAlpha(alpha); }
        if (sectionView != null) { sectionView.setVisibility(visible ? VISIBLE : GONE); sectionView.setAlpha(alpha); }
        if (accountView != null) { accountView.setVisibility(visible ? VISIBLE : GONE); accountView.setAlpha(alpha); }
    }

    private void setChromeAlpha(float alpha) {
        if (brandView != null) brandView.setAlpha(alpha);
        if (sectionView != null) sectionView.setAlpha(alpha);
        if (accountView != null) accountView.setAlpha(alpha);
    }

    private String username() {
        try {
            String username = Uri.parse(config.playlistUrl()).getQueryParameter("username");
            return username == null ? "" : username.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
