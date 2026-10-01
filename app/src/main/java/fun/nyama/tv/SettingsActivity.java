package fun.nyama.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    private Config config;
    private TextView textScaleValue;
    private TextView clockScaleValue;
    private TextView dataUsageValue;
    private UpdateManager updateManager;
    private boolean suppressNextUserLeaveHint;

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = new Config(this);
        updateManager = new UpdateManager(this);
        setContentView(buildUi());
    }

    @Override protected void onResume() {
        super.onResume();
        suppressNextUserLeaveHint = false;
        updateDataUsage();
        if (updateManager != null) updateManager.onResume();
    }

    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (suppressNextUserLeaveHint) {
            suppressNextUserLeaveHint = false;
            return;
        }
        MainActivity.stopPlaybackAndExitIfRunning();
        finishAndRemoveTask();
    }

    void suppressNextUserLeaveHintOnce() {
        suppressNextUserLeaveHint = true;
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        suppressNextUserLeaveHint = true;
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (updateManager != null) updateManager.destroy();
        super.onDestroy();
    }

    private View buildUi() {
        final int accent = UiTheme.accent(this);
        final boolean phone = config.isPhoneMode();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xD90A0C0F);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = phone ? 20 : 64;
        root.setPadding(dp(side), dp(phone ? 18 : 32), dp(side), dp(phone ? 30 : 44));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView brand = label("NYAMA+  •  v" + appVersion(), 14, accent, true);
        brand.setPadding(0, 0, 0, dp(4));
        root.addView(brand);

        TextView title = label(getString(R.string.settings_title), phone ? 30 : 36, Color.WHITE, true);
        title.setPadding(0, 0, 0, dp(2));
        root.addView(title);

        TextView subtitle = label(getString(R.string.settings_subtitle), 15, 0xFFAAB3BF, false);
        subtitle.setPadding(0, 0, 0, dp(10));
        root.addView(subtitle);

        TextView account = label(getString(R.string.account_user, accountUsername()), 16, Color.WHITE, true);
        account.setPadding(dp(14), dp(10), dp(14), dp(10));
        account.setBackground(rounded(0xFF171C22, 10));
        LinearLayout.LayoutParams accountParams = new LinearLayout.LayoutParams(-1, -2);
        accountParams.setMargins(0, 0, 0, dp(18));
        root.addView(account, accountParams);

        // General settings card.
        LinearLayout general = card();
        Switch autostart = new Switch(this);
        autostart.setText(getString(R.string.autostart));
        autostart.setTextColor(Color.WHITE);
        autostart.setTextSize(UiTheme.sp(this, 18));
        autostart.setChecked(config.autoStart());
        autostart.setFocusable(true);
        autostart.setPadding(0, dp(4), 0, dp(4));
        autostart.setOnCheckedChangeListener((buttonView, isChecked) -> config.setAutoStart(isChecked));
        general.addView(autostart, new LinearLayout.LayoutParams(-1, dp(54)));
        general.addView(description(getString(R.string.autostart_desc)));
        general.addView(divider());

        general.addView(rowTitle(getString(R.string.text_size)));
        LinearLayout textSizeRow = horizontal();
        Button minus = smallButton("A−");
        textScaleValue = label(scaleText(), 18, Color.WHITE, true);
        textScaleValue.setGravity(Gravity.CENTER);
        Button plus = smallButton("A+");
        minus.setOnClickListener(v -> changeScale(-0.1f));
        plus.setOnClickListener(v -> changeScale(0.1f));
        textSizeRow.addView(minus, new LinearLayout.LayoutParams(dp(phone ? 72 : 92), dp(52)));
        textSizeRow.addView(textScaleValue, new LinearLayout.LayoutParams(dp(phone ? 94 : 124), dp(52)));
        textSizeRow.addView(plus, new LinearLayout.LayoutParams(dp(phone ? 72 : 92), dp(52)));
        general.addView(textSizeRow);
        general.addView(divider());

        general.addView(rowTitle(getString(R.string.language)));
        RadioGroup languages = new RadioGroup(this);
        languages.setOrientation(phone ? RadioGroup.VERTICAL : RadioGroup.HORIZONTAL);
        RadioButton bg = radio(getString(R.string.language_bg), Config.LANG_BG.equals(config.language()));
        RadioButton en = radio(getString(R.string.language_en), Config.LANG_EN.equals(config.language()));
        languages.addView(bg, new RadioGroup.LayoutParams(phone ? -1 : 0, dp(52), phone ? 0f : 1f));
        languages.addView(en, new RadioGroup.LayoutParams(phone ? -1 : 0, dp(52), phone ? 0f : 1f));
        bg.setOnClickListener(v -> setLanguage(Config.LANG_BG));
        en.setOnClickListener(v -> setLanguage(Config.LANG_EN));
        general.addView(languages);
        general.addView(divider());

        general.addView(rowTitle(getString(R.string.color_theme)));
        RadioGroup themes = new RadioGroup(this);
        themes.setOrientation(phone ? RadioGroup.VERTICAL : RadioGroup.HORIZONTAL);
        addTheme(themes, getString(R.string.theme_default), Config.THEME_DEFAULT);
        addTheme(themes, getString(R.string.theme_orange), Config.THEME_ORANGE);
        addTheme(themes, getString(R.string.theme_green), Config.THEME_GREEN);
        addTheme(themes, getString(R.string.theme_blue), Config.THEME_BLUE);
        general.addView(themes);
        addSection(root, getString(R.string.general_settings), general);

        // Clock settings card.
        LinearLayout clockCard = card();
        Switch showClock = new Switch(this);
        showClock.setText(getString(R.string.show_clock));
        showClock.setTextColor(Color.WHITE);
        showClock.setTextSize(UiTheme.sp(this, 18));
        showClock.setChecked(config.showClock());
        showClock.setFocusable(true);
        showClock.setPadding(0, dp(4), 0, dp(4));
        showClock.setOnCheckedChangeListener((buttonView, isChecked) -> config.setShowClock(isChecked));
        clockCard.addView(showClock, new LinearLayout.LayoutParams(-1, dp(54)));
        clockCard.addView(description(getString(R.string.show_clock_desc)));
        clockCard.addView(divider());

        clockCard.addView(rowTitle(getString(R.string.clock_size)));
        LinearLayout clockSizeRow = horizontal();
        Button clockMinus = smallButton("−");
        clockScaleValue = label(clockScaleText(), 18, Color.WHITE, true);
        clockScaleValue.setGravity(Gravity.CENTER);
        Button clockPlus = smallButton("+");
        clockMinus.setOnClickListener(v -> changeClockScale(-0.1f));
        clockPlus.setOnClickListener(v -> changeClockScale(0.1f));
        clockSizeRow.addView(clockMinus, new LinearLayout.LayoutParams(dp(phone ? 72 : 92), dp(52)));
        clockSizeRow.addView(clockScaleValue, new LinearLayout.LayoutParams(dp(phone ? 94 : 124), dp(52)));
        clockSizeRow.addView(clockPlus, new LinearLayout.LayoutParams(dp(phone ? 72 : 92), dp(52)));
        clockCard.addView(clockSizeRow);
        clockCard.addView(divider());

        clockCard.addView(rowTitle(getString(R.string.clock_position)));
        RadioGroup clockPositions = new RadioGroup(this);
        clockPositions.setOrientation(phone ? RadioGroup.VERTICAL : RadioGroup.HORIZONTAL);
        addClockPosition(clockPositions, getString(R.string.clock_top_left), Config.CLOCK_TOP_LEFT);
        addClockPosition(clockPositions, getString(R.string.clock_top_right), Config.CLOCK_TOP_RIGHT);
        addClockPosition(clockPositions, getString(R.string.clock_bottom_left), Config.CLOCK_BOTTOM_LEFT);
        addClockPosition(clockPositions, getString(R.string.clock_bottom_right), Config.CLOCK_BOTTOM_RIGHT);
        clockCard.addView(clockPositions);
        addSection(root, getString(R.string.clock_settings), clockCard);

        // Usage card.
        LinearLayout usageCard = card();
        dataUsageValue = label("", 24, Color.WHITE, true);
        dataUsageValue.setPadding(0, 0, 0, dp(4));
        usageCard.addView(dataUsageValue);
        usageCard.addView(description(getString(R.string.data_usage_month_desc)));
        addSection(root, getString(R.string.data_usage_month), usageCard);
        updateDataUsage();

        // Update section intentionally contains only the action button.
        LinearLayout updateCard = card();
        Button update = professionalButton(getString(R.string.check_for_updates));
        update.setOnClickListener(v -> updateManager.checkForUpdate());
        updateCard.addView(update, new LinearLayout.LayoutParams(-1, dp(56)));
        addSection(root, getString(R.string.app_update), updateCard);

        // Administrator card.
        LinearLayout adminCard = card();
        adminCard.addView(description(getString(R.string.admin_desc)));
        Button admin = professionalButton(getString(R.string.open_admin));
        admin.setOnClickListener(v -> askAdminPin());
        LinearLayout.LayoutParams adminParams = new LinearLayout.LayoutParams(-1, dp(56));
        adminParams.setMargins(0, dp(6), 0, 0);
        adminCard.addView(admin, adminParams);
        addSection(root, getString(R.string.admin_section), adminCard);

        Button close = professionalButton(getString(R.string.close));
        close.setOnClickListener(v -> {
            suppressNextUserLeaveHint = true;
            finish();
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(54));
        cp.setMargins(0, dp(22), 0, 0);
        root.addView(close, cp);

        autostart.requestFocus();
        return scroll;
    }

    private void updateDataUsage() {
        if (dataUsageValue == null) return;
        long bytes = DataUsageTracker.checkpoint(this);
        dataUsageValue.setText(DataUsageTracker.format(this, bytes));
    }

    private void changeScale(float delta) {
        float before = config.textScale();
        config.setTextScale(before + delta);
        textScaleValue.setText(scaleText());
        if (Math.abs(before - config.textScale()) > 0.001f) recreate();
    }

    private String scaleText() { return Math.round(config.textScale() * 100f) + "%"; }

    private void changeClockScale(float delta) {
        float before = config.clockScale();
        config.setClockScale(before + delta);
        clockScaleValue.setText(clockScaleText());
        if (Math.abs(before - config.clockScale()) > 0.001f) recreate();
    }

    private String clockScaleText() { return Math.round(config.clockScale() * 100f) + "%"; }

    private void addClockPosition(RadioGroup group, String text, String key) {
        RadioButton button = radio(text, key.equals(config.clockPosition()));
        button.setOnClickListener(v -> {
            if (!key.equals(config.clockPosition())) {
                config.setClockPosition(key);
                recreate();
            }
        });
        group.addView(button, config.isPhoneMode()
                ? new RadioGroup.LayoutParams(-1, dp(50))
                : new RadioGroup.LayoutParams(0, dp(52), 1f));
    }

    private void setLanguage(String language) {
        if (language.equals(config.language())) return;
        config.setLanguage(language);
        recreate();
    }

    private void addTheme(RadioGroup group, String text, String key) {
        RadioButton button = radio(text, key.equals(config.colorTheme()));
        button.setOnClickListener(v -> {
            if (!key.equals(config.colorTheme())) {
                config.setColorTheme(key);
                recreate();
            }
        });
        group.addView(button, config.isPhoneMode()
                ? new RadioGroup.LayoutParams(-1, dp(50))
                : new RadioGroup.LayoutParams(0, dp(52), 1f));
    }

    private void askAdminPin() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint(getString(R.string.admin_pin));
        input.setSingleLine(true);
        int pad = dp(20);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, pad, pad, 0);
        wrap.addView(input, new FrameLayout.LayoutParams(-1, dp(60)));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.admin_title))
                .setView(wrap)
                .setPositiveButton(getString(R.string.open), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (config.verifyPin(input.getText().toString())) {
                dialog.dismiss();
                Intent intent = new Intent(this, AdminSettingsActivity.class);
                intent.putExtra(AdminSettingsActivity.EXTRA_AUTHORIZED, true);
                suppressNextUserLeaveHintOnce();
                startActivity(intent);
            } else {
                input.setText("");
                input.requestFocus();
                Toast.makeText(this, getString(R.string.wrong_pin), Toast.LENGTH_SHORT).show();
            }
        }));
        dialog.show();
        input.requestFocus();
    }

    private String accountUsername() {
        try {
            Uri uri = Uri.parse(config.playlistUrl());
            String username = uri.getQueryParameter("username");
            if (username != null && !username.trim().isEmpty()) return username.trim();
        } catch (Exception ignored) {}
        return getString(R.string.account_unknown);
    }

    @SuppressWarnings("deprecation")
    private String appVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null || info.versionName.trim().isEmpty() ? "-" : info.versionName;
        } catch (Exception ignored) {
            return "-";
        }
    }

    private void addSection(LinearLayout root, String title, LinearLayout content) {
        TextView heading = label(title, 17, 0xFFD5DAE1, true);
        heading.setPadding(dp(4), dp(18), 0, dp(8));
        root.addView(heading);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        root.addView(content, params);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable background = rounded(0xFF14181E, 14);
        background.setStroke(dp(1), 0xFF292F38);
        card.setBackground(background);
        return card;
    }

    private View divider() {
        View view = new View(this);
        view.setBackgroundColor(0xFF2B313A);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.setMargins(0, dp(10), 0, dp(10));
        view.setLayoutParams(params);
        return view;
    }

    private TextView rowTitle(String text) {
        TextView view = label(text, 16, 0xFFE4E8EE, true);
        view.setPadding(0, 0, 0, dp(4));
        return view;
    }

    private TextView description(String text) {
        TextView view = label(text, 13, 0xFF98A3B0, false);
        view.setPadding(0, 0, 0, dp(4));
        return view;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        return layout;
    }

    private Button smallButton(String text) {
        Button b = professionalButton(text);
        b.setTextSize(UiTheme.sp(this, 17));
        return b;
    }

    private Button professionalButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(UiTheme.sp(this, 16));
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setFocusable(true);
        button.setAllCaps(false);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(buttonBackground());
        return button;
    }

    private StateListDrawable buttonBackground() {
        int accent = UiTheme.accent(this);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, rounded(accent, 9));
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(accent, 9));
        states.addState(new int[]{}, rounded(0xFF252B34, 9));
        return states;
    }

    private RadioButton radio(String text, boolean checked) {
        RadioButton r = new RadioButton(this);
        r.setText(text);
        r.setTextColor(Color.WHITE);
        r.setTextSize(UiTheme.sp(this, 15));
        r.setChecked(checked);
        r.setFocusable(true);
        return r;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(UiTheme.sp(this, size));
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
