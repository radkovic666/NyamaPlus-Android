package fun.nyama.tv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

/**
 * Best-effort autostart helper.
 *
 * Android and some OEMs may legally block background activity launches after boot. Nyama TV
 * cannot override an OS/OEM policy without privileged system permissions, but this combines
 * normal/quick/user-unlocked boot broadcasts with several delayed system PendingIntent attempts.
 * This is substantially more reliable on Android TV / TV boxes than a single BOOT_COMPLETED
 * startActivity() call, and also gives phones the best available non-privileged behavior.
 */
public final class AutoStartManager {
    private static final String PREFS = "nyama_boot_prefs";
    private static final String K_ENABLED = "enabled";
    private static final int[] REQUEST_CODES = {3011, 3012, 3013};
    private static final long[] RETRY_DELAYS_MS = {5_000L, 20_000L, 60_000L};

    private AutoStartManager() {}

    public static void setEnabled(Context context, boolean enabled) {
        Context app = context.getApplicationContext();
        SharedPreferences bootPrefs = deviceProtected(app).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        bootPrefs.edit().putBoolean(K_ENABLED, enabled).apply();

        ComponentName receiver = new ComponentName(app, BootReceiver.class);
        app.getPackageManager().setComponentEnabledSetting(
                receiver,
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        if (!enabled) cancelDelayedLaunches(app);
    }

    public static boolean isEnabledForBoot(Context context) {
        return deviceProtected(context.getApplicationContext())
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(K_ENABLED, false);
    }

    public static void launchAfterBoot(Context context) {
        Context app = context.getApplicationContext();
        if (!isEnabledForBoot(app)) return;

        try { app.startActivity(launchIntent(app)); } catch (Exception ignored) {}

        // Retry through AlarmManager because some firmware rejects an activity launch while the
        // boot broadcast is still being processed. MainActivity cancels the remaining retries as
        // soon as one attempt succeeds, so a working device is not repeatedly brought forward.
        try {
            AlarmManager alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (alarms == null) return;
            long base = SystemClock.elapsedRealtime();
            for (int i = 0; i < REQUEST_CODES.length; i++) {
                PendingIntent pending = PendingIntent.getActivity(
                        app,
                        REQUEST_CODES[i],
                        launchIntent(app),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                long when = base + RETRY_DELAYS_MS[i];
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pending);
                } else {
                    alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pending);
                }
            }
        } catch (Exception ignored) {}
    }

    public static void cancelDelayedLaunches(Context context) {
        Context app = context.getApplicationContext();
        for (int requestCode : REQUEST_CODES) {
            try {
                PendingIntent pending = PendingIntent.getActivity(
                        app,
                        requestCode,
                        launchIntent(app),
                        PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
                if (pending == null) continue;
                AlarmManager alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
                if (alarms != null) alarms.cancel(pending);
                pending.cancel();
            } catch (Exception ignored) {}
        }
    }

    private static Intent launchIntent(Context context) {
        return new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("nyama_autostart", true);
    }

    private static Context deviceProtected(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Context protectedContext = context.createDeviceProtectedStorageContext();
            if (protectedContext != null) return protectedContext;
        }
        return context;
    }
}
