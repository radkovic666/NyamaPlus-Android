package fun.nyama.tv;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public final class AdminSettingsActivity extends Activity {
    public static final String EXTRA_AUTHORIZED = "authorized";

    private EditText playlist;
    private EditText epg;
    private EditText pukanki;
    private EditText pin;
    private Config config;
    private boolean firstTime;
    private String selectedDeviceMode;
    private boolean suppressNextUserLeaveHint;

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = new Config(this);
        firstTime = !config.isConfigured();
        selectedDeviceMode = config.deviceMode();
        if (!firstTime && !getIntent().getBooleanExtra(EXTRA_AUTHORIZED, false)) {
            suppressNextUserLeaveHint = true;
            finish();
            return;
        }
        setContentView(buildUi());
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

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        suppressNextUserLeaveHint = true;
        super.onBackPressed();
    }

    private View buildUi() {
        int accent = UiTheme.accent(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xD90C0E11);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = config.isPhoneMode() ? 24 : 78;
        root.setPadding(dp(side), dp(config.isPhoneMode() ? 22 : 44), dp(side), dp(config.isPhoneMode() ? 34 : 56));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        root.addView(label("NYAMA+ " + appVersion(), 15, accent, true));
        TextView title = label(firstTime ? getString(R.string.first_setup) : getString(R.string.admin_settings), 34, Color.WHITE, true);
        title.setPadding(0, 0, 0, dp(14));
        root.addView(title);
        TextView intro = label(getString(R.string.setup_intro), 17, 0xFFB7BEC8, false);
        intro.setPadding(0, 0, 0, dp(24));
        root.addView(intro);

        if (firstTime) {
            root.addView(label(getString(R.string.device_type), 17, Color.WHITE, true));
            RadioGroup modes = new RadioGroup(this);
            modes.setOrientation(config.isPhoneMode() ? RadioGroup.VERTICAL : RadioGroup.HORIZONTAL);
            RadioButton tv = deviceRadio(getString(R.string.device_type_tv), Config.MODE_TV.equals(selectedDeviceMode));
            RadioButton phone = deviceRadio(getString(R.string.device_type_phone), Config.MODE_PHONE.equals(selectedDeviceMode));
            modes.addView(tv, new RadioGroup.LayoutParams(config.isPhoneMode() ? -1 : 0, dp(58), config.isPhoneMode() ? 0f : 1f));
            modes.addView(phone, new RadioGroup.LayoutParams(config.isPhoneMode() ? -1 : 0, dp(58), config.isPhoneMode() ? 0f : 1f));
            tv.setOnClickListener(v -> selectedDeviceMode = Config.MODE_TV);
            phone.setOnClickListener(v -> selectedDeviceMode = Config.MODE_PHONE);
            root.addView(modes);
            TextView modeHelp = label(getString(R.string.device_type_desc), 14, 0xFF8F98A5, false);
            modeHelp.setPadding(0, 0, 0, dp(18));
            root.addView(modeHelp);
        }

        root.addView(label(getString(R.string.playlist_url), 17, Color.WHITE, true));
        playlist = field(config.playlistUrl(), false);
        playlist.setHint(getString(R.string.playlist_hint));
        root.addView(playlist, params());

        root.addView(label(getString(R.string.epg_url), 17, Color.WHITE, true));
        epg = field(config.epgUrl(), false);
        epg.setHint(Config.DEFAULT_EPG_URL);
        root.addView(epg, params());

        root.addView(label(getString(R.string.pukanki_url), 17, Color.WHITE, true));
        pukanki = field(config.pukankiBaseUrl(), false);
        pukanki.setHint(Config.DEFAULT_PUKANKI_URL);
        root.addView(pukanki, params());

        root.addView(label(config.hasPin() ? getString(R.string.new_admin_pin) : getString(R.string.admin_pin), 17, Color.WHITE, true));
        pin = field("", true);
        pin.setHint(config.hasPin() ? getString(R.string.pin_hint_keep) : getString(R.string.pin_hint_new));
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        root.addView(pin, params());

        TextView device = label(getString(R.string.device_id, DeviceIdentity.deviceHeaderValue(this)), 14, 0xFF8F98A5, false);
        root.addView(device);
        TextView ua = label(getString(R.string.network_ua, DeviceIdentity.userAgent(this)), 14, 0xFF8F98A5, false);
        ua.setPadding(0, 0, 0, dp(24));
        root.addView(ua);

        Button save = new Button(this);
        save.setText(getString(R.string.save_and_start));
        save.setTextSize(UiTheme.sp(this, 19));
        save.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        save.setFocusable(true);
        save.setOnClickListener(v -> save());
        root.addView(save, new LinearLayout.LayoutParams(-1, dp(66)));

        TextView note = label(getString(R.string.admin_tip), 14, 0xFF8F98A5, false);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note);

        playlist.requestFocus();
        return scroll;
    }

    private void save() {
        String p = playlist.getText().toString().trim();
        String e = epg.getText().toString().trim();
        String mediaBase = pukanki.getText().toString().trim();
        String newPin = pin.getText().toString().trim();
        if (!validHttpUrl(p)) {
            Toast.makeText(this, getString(R.string.playlist_url_invalid), Toast.LENGTH_LONG).show();
            return;
        }
        if (!validHttpUrl(e)) {
            Toast.makeText(this, getString(R.string.epg_url_invalid), Toast.LENGTH_LONG).show();
            return;
        }
        if (!validHttpUrl(mediaBase)) {
            Toast.makeText(this, getString(R.string.pukanki_url_invalid), Toast.LENGTH_LONG).show();
            return;
        }
        if (!config.hasPin() && !validPin(newPin)) {
            Toast.makeText(this, getString(R.string.set_admin_pin), Toast.LENGTH_LONG).show();
            return;
        }
        if (!newPin.isEmpty() && !validPin(newPin)) {
            Toast.makeText(this, getString(R.string.pin_invalid), Toast.LENGTH_LONG).show();
            return;
        }

        boolean playlistChanged = !p.equals(config.playlistUrl());
        boolean epgChanged = !e.equals(config.epgUrl());
        config.saveUrls(p, e);
        config.setPukankiBaseUrl(mediaBase);
        if (firstTime) config.setDeviceMode(selectedDeviceMode);
        if (!newPin.isEmpty()) config.setPin(newPin);
        if (playlistChanged) deleteQuietly(PlaylistRepository.cacheFile(this));
        if (epgChanged) {
            deleteQuietly(EpgRepository.cacheFile(this));
            deleteQuietly(EpgRepository.parsedCacheFile(this));
        }
        ((NyamaTvApp) getApplication()).scheduleRefreshes();
        setResult(RESULT_OK);
        suppressNextUserLeaveHint = true;
        finish();
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

    private boolean validPin(String value) { return value != null && value.matches("[0-9]{4,12}"); }

    private boolean validHttpUrl(String value) {
        String lower = value == null ? "" : value.toLowerCase();
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    private void deleteQuietly(File file) { if (file != null && file.exists()) file.delete(); }

    private RadioButton deviceRadio(String text, boolean checked) {
        RadioButton button = new RadioButton(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(UiTheme.sp(this, 17));
        button.setChecked(checked);
        button.setFocusable(true);
        return button;
    }

    private EditText field(String text, boolean password) {
        EditText field = new EditText(this);
        field.setText(text);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(0xFF68707B);
        field.setTextSize(UiTheme.sp(this, 18));
        field.setSingleLine(true);
        field.setSelectAllOnFocus(false);
        field.setPadding(dp(16), 0, dp(16), 0);
        if (!password) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return field;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(UiTheme.sp(this, size));
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setPadding(0, dp(8), 0, dp(8));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(62));
        params.setMargins(0, 0, 0, dp(18));
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
