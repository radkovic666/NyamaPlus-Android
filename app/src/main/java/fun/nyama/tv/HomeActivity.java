package fun.nyama.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class HomeActivity extends Activity {
    private Config config;
    private boolean setupOpen;
    private String homeSignature;

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        config = new Config(this);
        homeSignature = signature();
        if (!config.isPhoneMode()) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(buildUi());
    }

    @Override protected void onResume() {
        super.onResume();
        if (!config.isConfigured() && !setupOpen) {
            setupOpen = true;
            startActivity(new Intent(this, AdminSettingsActivity.class));
            return;
        }
        if (config.isConfigured()) {
            setupOpen = false;
            String current = signature();
            if (!current.equals(homeSignature)) { recreate(); return; }
        }
    }

    private View buildUi() {
        int accent = UiTheme.accent(this);
        boolean phone = config.isPhoneMode();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(8, 10, 13));
        int side = phone ? 22 : 72;
        root.setPadding(dp(side), dp(phone ? 28 : 52), dp(side), dp(phone ? 28 : 52));

        TextView brand = text("NYAMA+", phone ? 36 : 52, Color.WHITE, true);
        brand.setGravity(Gravity.CENTER);
        root.addView(brand, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = text(getString(R.string.home_subtitle), phone ? 16 : 20, 0xFFAEB7C2, false);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(0, dp(4), 0, dp(phone ? 24 : 38));
        root.addView(subtitle, sp);

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(phone ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        cards.setGravity(Gravity.CENTER);
        root.addView(cards, new LinearLayout.LayoutParams(-1, 0, 1f));

        Button tv = card(getString(R.string.home_tv), getString(R.string.home_tv_desc), accent);
        Button movies = card(getString(R.string.home_movies), getString(R.string.home_movies_desc), accent);
        Button series = card(getString(R.string.home_series), getString(R.string.home_series_desc), accent);
        Button settings = card(getString(R.string.settings_title), getString(R.string.home_settings_desc), accent);

        tv.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_EMBEDDED_IN_NYAMA_PLUS, true)));
        movies.setOnClickListener(v -> openCatalog("movies"));
        series.setOnClickListener(v -> openCatalog("series"));
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        if (phone) {
            addPhoneCard(cards, tv); addPhoneCard(cards, movies); addPhoneCard(cards, series); addPhoneCard(cards, settings);
        } else {
            addTvCard(cards, tv); addTvCard(cards, movies); addTvCard(cards, series); addTvCard(cards, settings);
        }

        TextView account = text(getString(R.string.account_user, accountUsername()), phone ? 13 : 15, 0xFF7F8995, false);
        account.setGravity(Gravity.CENTER);
        root.addView(account, new LinearLayout.LayoutParams(-1, -2));

        tv.requestFocus();
        return root;
    }

    private void openCatalog(String section) {
        startActivity(new Intent(this, CatalogActivity.class).putExtra(CatalogActivity.EXTRA_SECTION, section));
    }

    private Button card(String title, String detail, int accent) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(title + "\n" + detail);
        b.setTextColor(Color.WHITE);
        b.setTextSize(UiTheme.sp(this, config.isPhoneMode() ? 18 : 21));
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF171B20);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(2), accent);
        b.setBackground(bg);
        return b;
    }

    private void addTvCard(LinearLayout parent, View view) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(190), 1f);
        p.setMargins(dp(9), 0, dp(9), 0);
        parent.addView(view, p);
    }

    private void addPhoneCard(LinearLayout parent, View view) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(86));
        p.setMargins(0, dp(4), 0, dp(4));
        parent.addView(view, p);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(UiTheme.sp(this, size));
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private String accountUsername() {
        try {
            String username = Uri.parse(config.playlistUrl()).getQueryParameter("username");
            if (username != null && !username.trim().isEmpty()) return username.trim();
        } catch (Exception ignored) {}
        return getString(R.string.account_unknown);
    }

    private String signature() { return config.uiSignature() + "|" + config.playlistUrl(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
