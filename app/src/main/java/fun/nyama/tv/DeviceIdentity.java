package fun.nyama.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

public final class DeviceIdentity {
    private static final String PREFS = "nyama_device_identity";
    private static final String K_INSTALL_NONCE = "install_nonce";

    private DeviceIdentity() {}

    public static String stableId(Context context) {
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null) androidId = "";
        String raw = androidId + "|" + Build.MANUFACTURER + "|" + Build.MODEL + "|" + Build.FINGERPRINT + "|" + installNonce(context);
        return shortHash(raw);
    }

    /**
     * A stable, per-install/per-device User-Agent. The original auth_playlist.php binds devices
     * using account + User-Agent, so two TVs must not present the same User-Agent.
     */
    public static String userAgent(Context context) {
        String manufacturer = clean(Build.MANUFACTURER);
        String model = clean(Build.MODEL);
        String release = clean(Build.VERSION.RELEASE);
        String device = stableId(context);
        return "Kodi/21.2 (Linux; Android " + release + "; " + manufacturer + " " + model + ") "
                + "NyamaTV/3.0 Device/" + device;
    }

    public static String deviceHeaderValue(Context context) {
        return stableId(context);
    }

    private static String installNonce(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = prefs.getString(K_INSTALL_NONCE, "");
        if (value != null && !value.trim().isEmpty()) return value;
        value = UUID.randomUUID().toString();
        prefs.edit().putString(K_INSTALL_NONCE, value).commit();
        return value;
    }

    private static String clean(String value) {
        if (value == null || value.trim().isEmpty()) return "unknown";
        return value.replace('(', '_').replace(')', '_').replace(';', '_').trim();
    }

    private static String shortHash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format(Locale.US, "%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
