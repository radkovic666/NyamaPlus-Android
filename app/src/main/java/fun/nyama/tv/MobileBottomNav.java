package fun.nyama.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowCompat;

/** Phone/tablet-only bottom navigation, measured below content rather than overlaid on it. */
// This view requires a destination listener and is never inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
final class MobileBottomNav extends LinearLayout {
    private static final long HIDE_DELAY_MS = 3000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hide = () -> setVisibility(GONE);
    private boolean autoHide;

    MobileBottomNav(Context context, String active, AppSidebar.Listener listener) {
        super(context);
        setTag("mobile-bottom-navigation");
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setPadding(dp(4), dp(4), dp(4), dp(4));
        setBackgroundColor(0xFF0D1117);
        boolean english = Config.LANG_EN.equals(new Config(context).language());
        String[] keys = {AppSidebar.SECTION_TV, AppSidebar.SECTION_MOVIES, AppSidebar.SECTION_SERIES, AppSidebar.SECTION_SETTINGS};
        String[] labels = english ? new String[]{"TV", "MOVIES", "SERIES", "SETTINGS"}
                : new String[]{"ТВ", "ФИЛМИ", "СЕРИАЛИ", "НАСТРОЙКИ"};
        int[] icons = {R.drawable.ic_mobile_tv, R.drawable.ic_mobile_movies, R.drawable.ic_mobile_series, R.drawable.ic_mobile_settings};
        Runnable[] actions = {listener::onTv, listener::onMovies, listener::onSeries, listener::onSettings};
        int accent = UiTheme.accent(context);
        for (int i = 0; i < keys.length; i++) {
            boolean selected = keys[i].equals(active);
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(VERTICAL); item.setGravity(Gravity.CENTER);
            item.setPadding(dp(2), dp(5), dp(2), dp(4));
            item.setMinimumHeight(dp(56)); item.setFocusable(true); item.setClickable(true);
            item.setOnFocusChangeListener((view, focused) -> { if (focused) revealTemporarily(); });
            item.setSelected(selected); item.setTag("mobile-nav-" + keys[i]); item.setContentDescription(labels[i]);
            ImageView icon = new ImageView(context); icon.setImageResource(icons[i]);
            icon.setColorFilter(selected ? accent : 0xFFB8C0CB);
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(icon, new LayoutParams(dp(24), dp(24)));
            TextView label = new TextView(context); label.setText(labels[i]);
            label.setTextColor(selected ? accent : 0xFFB8C0CB); label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            label.setGravity(Gravity.CENTER); label.setMaxLines(2); label.setIncludeFontPadding(false);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12); label.setMinimumHeight(dp(18));
            LayoutParams text = new LayoutParams(-1, -2); text.topMargin = dp(3); item.addView(label, text);
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_pressed}, background(accent, true));
            states.addState(new int[]{android.R.attr.state_focused}, background(accent, true));
            states.addState(new int[]{}, background(accent, selected)); item.setBackground(states);
            final Runnable action = actions[i]; item.setOnClickListener(v -> action.run());
            LayoutParams lp = new LayoutParams(0, -2, 1f); lp.setMargins(dp(2), 0, dp(2), 0); addView(item, lp);
        }
    }
    /** Opted into only by Live TV; catalogs and Settings keep their persistent bar. */
    void enableAutoHide() { autoHide = true; hideNow(); }

    void revealTemporarily() {
        if (!autoHide) return;
        setVisibility(VISIBLE);
        main.removeCallbacks(hide);
        main.postDelayed(hide, HIDE_DELAY_MS);
    }

    void hideNow() {
        main.removeCallbacks(hide);
        if (autoHide) setVisibility(GONE);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (getVisibility() == VISIBLE) revealTemporarily();
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) revealTemporarily();
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onDetachedFromWindow() {
        hideNow();
        super.onDetachedFromWindow();
    }

    static View wrap(Activity activity, View content, String active, AppSidebar.Listener listener) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        LinearLayout screen = new LinearLayout(activity); screen.setOrientation(VERTICAL);
        screen.setBackgroundColor(Color.BLACK); screen.setTag("mobile-navigation-screen");
        screen.addView(content, new LayoutParams(-1, 0, 1f));
        MobileBottomNav nav = new MobileBottomNav(activity, active, listener);
        screen.addView(nav, new LayoutParams(-1, -2));
        ViewCompat.setOnApplyWindowInsetsListener(screen, (view, insets) -> {
            if (AppSidebar.SECTION_TV.equals(active)) {
                // Live playback uses the real safe rectangle, including when the app bar is GONE.
                // Hidden system bars must not leave stale top/side offsets around the picture.
                androidx.core.graphics.Insets safe = PlayerVideoFit.safeInsets(insets);
                screen.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                nav.setPadding(nav.dp(4), nav.dp(4), nav.dp(4), nav.dp(4));
                return insets.inset(safe.left, safe.top, safe.right, safe.bottom);
            }
            int types = WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();
            // Older WebView/Android hosts can omit stable navigation insets. Honor visible bars too.
            androidx.core.graphics.Insets bars = androidx.core.graphics.Insets.max(
                    insets.getInsets(types), insets.getInsetsIgnoringVisibility(types));
            screen.setPadding(bars.left, bars.top, bars.right, 0);
            nav.setPadding(nav.dp(4), nav.dp(4), nav.dp(4), nav.dp(4) + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(screen);
        return screen;
    }
    static AppSidebar.Listener destinations(Activity activity, String active) {
        return new AppSidebar.Listener() {
            private void open(String section) {
                if (section.equals(active)) return;
                Intent intent = AppSidebar.SECTION_TV.equals(section) ? new Intent(activity, MainActivity.class)
                        : AppSidebar.SECTION_SETTINGS.equals(section) ? new Intent(activity, SettingsActivity.class)
                        : new Intent(activity, CatalogActivity.class).putExtra(CatalogActivity.EXTRA_SECTION, section);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                if (activity instanceof SettingsActivity) ((SettingsActivity) activity).suppressNextUserLeaveHintOnce();
                activity.startActivity(intent); activity.finish();
            }
            public void onTv() { open(AppSidebar.SECTION_TV); }
            public void onMovies() { open(AppSidebar.SECTION_MOVIES); }
            public void onSeries() { open(AppSidebar.SECTION_SERIES); }
            public void onSettings() { open(AppSidebar.SECTION_SETTINGS); }
        };
    }
    private GradientDrawable background(int accent, boolean active) {
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(10));
        bg.setColor(active ? (accent & 0xFFFFFF) | 0x22000000 : Color.TRANSPARENT);
        return bg;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
