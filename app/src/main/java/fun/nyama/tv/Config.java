package fun.nyama.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class Config {
    private static final String PREFS = "nyama_tv_private";
    private static final String K_PLAYLIST = "playlist_url";
    private static final String K_EPG = "epg_url";
    private static final String K_PUKANKI = "pukanki_base_url";
    private static final String K_PIN_HASH = "pin_hash";
    private static final String K_PLAYLIST_REFRESH = "playlist_last_refresh";
    private static final String K_EPG_REFRESH = "epg_last_refresh";
    private static final String K_LAST_CHANNEL = "last_channel_key";
    private static final String K_FAVORITES = "favorite_channel_keys";
    private static final String K_AUTOSTART = "autostart_on_boot";
    private static final String K_TEXT_SCALE = "ui_text_scale";
    private static final String K_LANGUAGE = "ui_language";
    private static final String K_COLOR_THEME = "ui_color_theme";
    private static final String K_SHOW_CLOCK = "show_corner_clock";
    private static final String K_CLOCK_SCALE = "corner_clock_scale";
    private static final String K_CLOCK_POSITION = "corner_clock_position";
    private static final String K_DEVICE_MODE = "device_mode";

    public static final String DEFAULT_EPG_URL = "https://is.gd/fullepg";
    public static final String DEFAULT_PUKANKI_URL = "https://pukanki.fun";
    public static final long PLAYLIST_REFRESH_MS = 30L * 60L * 1000L;
    public static final long EPG_REFRESH_MS = 24L * 60L * 60L * 1000L;

    public static final String LANG_BG = "bg";
    public static final String LANG_EN = "en";
    public static final String THEME_DEFAULT = "default";
    public static final String THEME_ORANGE = "orange";
    public static final String THEME_GREEN = "green";
    public static final String THEME_BLUE = "blue";
    public static final String MODE_TV = "tv";
    public static final String MODE_PHONE = "phone";
    public static final String CLOCK_TOP_LEFT = "top_left";
    public static final String CLOCK_TOP_RIGHT = "top_right";
    public static final String CLOCK_BOTTOM_LEFT = "bottom_left";
    public static final String CLOCK_BOTTOM_RIGHT = "bottom_right";

    private final SharedPreferences prefs;
    private final Context context;

    public Config(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String playlistUrl() { return safe(prefs.getString(K_PLAYLIST, "")); }

    public String epgUrl() {
        String value = safe(prefs.getString(K_EPG, DEFAULT_EPG_URL));
        return value.isEmpty() ? DEFAULT_EPG_URL : value;
    }

    public String pukankiBaseUrl() {
        String value = safe(prefs.getString(K_PUKANKI, DEFAULT_PUKANKI_URL));
        if (value.isEmpty()) value = DEFAULT_PUKANKI_URL;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    public void setPukankiBaseUrl(String value) {
        String v = safe(value);
        if (v.isEmpty()) v = DEFAULT_PUKANKI_URL;
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        prefs.edit().putString(K_PUKANKI, v).apply();
    }

    public void saveUrls(String playlistUrl, String epgUrl) {
        String newPlaylist = safe(playlistUrl);
        String newEpg = safe(epgUrl);
        if (newEpg.isEmpty()) newEpg = DEFAULT_EPG_URL;

        String oldPlaylist = playlistUrl();
        String oldEpg = epgUrl();
        SharedPreferences.Editor editor = prefs.edit()
                .putString(K_PLAYLIST, newPlaylist)
                .putString(K_EPG, newEpg);
        if (!oldPlaylist.equals(newPlaylist)) editor.putLong(K_PLAYLIST_REFRESH, 0L);
        if (!oldEpg.equals(newEpg)) editor.putLong(K_EPG_REFRESH, 0L);
        editor.apply();
    }

    public boolean isConfigured() { return !playlistUrl().isEmpty() && hasPin(); }
    public boolean hasPin() { return !safe(prefs.getString(K_PIN_HASH, "")).isEmpty(); }

    public void setPin(String pin) { prefs.edit().putString(K_PIN_HASH, sha256(pin)).apply(); }

    public boolean verifyPin(String pin) {
        String expected = safe(prefs.getString(K_PIN_HASH, ""));
        return !expected.isEmpty() && expected.equals(sha256(pin));
    }

    public long playlistLastRefresh() { return prefs.getLong(K_PLAYLIST_REFRESH, 0L); }
    public void setPlaylistLastRefresh(long time) { prefs.edit().putLong(K_PLAYLIST_REFRESH, time).apply(); }
    public long epgLastRefresh() { return prefs.getLong(K_EPG_REFRESH, 0L); }
    public void setEpgLastRefresh(long time) { prefs.edit().putLong(K_EPG_REFRESH, time).apply(); }

    public String lastChannelKey() { return safe(prefs.getString(K_LAST_CHANNEL, "")); }
    public void setLastChannelKey(String key) { prefs.edit().putString(K_LAST_CHANNEL, safe(key)).apply(); }

    public Set<String> favoriteKeys() {
        Set<String> raw = prefs.getStringSet(K_FAVORITES, Collections.emptySet());
        return raw == null ? new HashSet<>() : new HashSet<>(raw);
    }

    public boolean isFavorite(String stableKey) {
        return stableKey != null && favoriteKeys().contains(stableKey);
    }

    public boolean toggleFavorite(String stableKey) {
        if (stableKey == null || stableKey.trim().isEmpty()) return false;
        Set<String> favorites = favoriteKeys();
        boolean nowFavorite;
        if (favorites.contains(stableKey)) {
            favorites.remove(stableKey);
            nowFavorite = false;
        } else {
            favorites.add(stableKey);
            nowFavorite = true;
        }
        prefs.edit().putStringSet(K_FAVORITES, favorites).apply();
        return nowFavorite;
    }

    public boolean autoStart() { return prefs.getBoolean(K_AUTOSTART, false); }
    public void setAutoStart(boolean enabled) {
        prefs.edit().putBoolean(K_AUTOSTART, enabled).apply();
        AutoStartManager.setEnabled(context, enabled);
    }

    public float textScale() {
        float scale = prefs.getFloat(K_TEXT_SCALE, 1.0f);
        return Math.max(0.80f, Math.min(1.40f, scale));
    }

    public void setTextScale(float value) {
        float clamped = Math.max(0.80f, Math.min(1.40f, Math.round(value * 10f) / 10f));
        prefs.edit().putFloat(K_TEXT_SCALE, clamped).apply();
    }

    public String language() {
        String lang = safe(prefs.getString(K_LANGUAGE, LANG_BG)).toLowerCase(Locale.ROOT);
        return LANG_EN.equals(lang) ? LANG_EN : LANG_BG;
    }

    public void setLanguage(String language) {
        prefs.edit().putString(K_LANGUAGE, LANG_EN.equals(language) ? LANG_EN : LANG_BG).apply();
    }

    public String colorTheme() {
        String theme = safe(prefs.getString(K_COLOR_THEME, THEME_DEFAULT)).toLowerCase(Locale.ROOT);
        if (THEME_ORANGE.equals(theme) || THEME_GREEN.equals(theme) || THEME_BLUE.equals(theme)) return theme;
        return THEME_DEFAULT;
    }

    public void setColorTheme(String theme) {
        if (!THEME_ORANGE.equals(theme) && !THEME_GREEN.equals(theme) && !THEME_BLUE.equals(theme)) theme = THEME_DEFAULT;
        prefs.edit().putString(K_COLOR_THEME, theme).apply();
    }


    public boolean hasDeviceMode() {
        String mode = safe(prefs.getString(K_DEVICE_MODE, ""));
        return MODE_TV.equals(mode) || MODE_PHONE.equals(mode);
    }

    public String deviceMode() {
        String mode = safe(prefs.getString(K_DEVICE_MODE, ""));
        if (MODE_TV.equals(mode) || MODE_PHONE.equals(mode)) return mode;
        return TvDeviceGuard.hardwareLooksTvLike(context) ? MODE_TV : MODE_PHONE;
    }

    public void setDeviceMode(String mode) {
        prefs.edit().putString(K_DEVICE_MODE, MODE_PHONE.equals(mode) ? MODE_PHONE : MODE_TV).apply();
    }

    public boolean isPhoneMode() { return MODE_PHONE.equals(deviceMode()); }

    public boolean showClock() { return prefs.getBoolean(K_SHOW_CLOCK, true); }
    public void setShowClock(boolean show) { prefs.edit().putBoolean(K_SHOW_CLOCK, show).apply(); }

    public float clockScale() {
        float scale = prefs.getFloat(K_CLOCK_SCALE, 1.0f);
        return Math.max(0.70f, Math.min(1.80f, scale));
    }

    public void setClockScale(float value) {
        float clamped = Math.max(0.70f, Math.min(1.80f, Math.round(value * 10f) / 10f));
        prefs.edit().putFloat(K_CLOCK_SCALE, clamped).apply();
    }

    public String clockPosition() {
        String position = safe(prefs.getString(K_CLOCK_POSITION, CLOCK_TOP_LEFT));
        if (CLOCK_TOP_RIGHT.equals(position) || CLOCK_BOTTOM_LEFT.equals(position) || CLOCK_BOTTOM_RIGHT.equals(position)) return position;
        return CLOCK_TOP_LEFT;
    }

    public void setClockPosition(String position) {
        if (!CLOCK_TOP_RIGHT.equals(position) && !CLOCK_BOTTOM_LEFT.equals(position) && !CLOCK_BOTTOM_RIGHT.equals(position)) {
            position = CLOCK_TOP_LEFT;
        }
        prefs.edit().putString(K_CLOCK_POSITION, position).apply();
    }

    public String uiSignature() {
        return language() + "|" + colorTheme() + "|" + textScale() + "|" + showClock() + "|"
                + clockScale() + "|" + clockPosition() + "|" + deviceMode();
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
