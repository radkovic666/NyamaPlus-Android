package fun.nyama.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.TrafficStats;
import android.os.Process;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tracks this app's RX+TX traffic for the current calendar month. */
public final class DataUsageTracker {
    private static final String PREFS = "nyama_data_usage";
    private static final String K_MONTH = "month";
    private static final String K_LAST = "last_uid_bytes";
    private static final String K_MONTH_BYTES = "month_bytes";

    // v3.0.1/v3.0.2 daily-counter keys. The current day's known total is used once as a
    // migration seed so updating the app does not unnecessarily reset the visible counter.
    private static final String OLD_K_DAY = "day";
    private static final String OLD_K_TODAY = "today_bytes";
    private static final String K_MIGRATED = "monthly_migrated";

    private DataUsageTracker() {}

    public static synchronized long checkpoint(Context context) {
        long rx = TrafficStats.getUidRxBytes(Process.myUid());
        long tx = TrafficStats.getUidTxBytes(Process.myUid());
        if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) return -1L;
        long current = Math.max(0L, rx) + Math.max(0L, tx);
        String month = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());

        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String oldMonth = prefs.getString(K_MONTH, "");
        long last = prefs.getLong(K_LAST, -1L);
        long total = prefs.getLong(K_MONTH_BYTES, 0L);

        if (!prefs.getBoolean(K_MIGRATED, false)) {
            String oldDay = prefs.getString(OLD_K_DAY, "");
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (today.equals(oldDay)) total = Math.max(total, prefs.getLong(OLD_K_TODAY, 0L));
            // Treat the existing daily baseline as the first checkpoint of this month's counter.
            oldMonth = month;
            prefs.edit().putBoolean(K_MIGRATED, true).putString(K_MONTH, month).apply();
        }

        if (!month.equals(oldMonth)) {
            total = 0L;
            last = current;
        } else if (last >= 0L) {
            if (current >= last) total += current - last;
            // UID counters can reset after reboot. In that case establish a new baseline.
            last = current;
        } else {
            last = current;
        }

        prefs.edit()
                .putString(K_MONTH, month)
                .putLong(K_LAST, last)
                .putLong(K_MONTH_BYTES, total)
                .apply();
        return total;
    }

    public static String format(Context context, long bytes) {
        if (bytes < 0L) return context.getString(R.string.data_usage_unavailable);
        double value = bytes;
        if (value < 1024d) return String.format(Locale.getDefault(), "%.0f B", value);
        value /= 1024d;
        if (value < 1024d) return String.format(Locale.getDefault(), "%.1f KB", value);
        value /= 1024d;
        if (value < 1024d) return String.format(Locale.getDefault(), "%.1f MB", value);
        value /= 1024d;
        return String.format(Locale.getDefault(), "%.2f GB", value);
    }
}
