package fun.nyama.tv;

import java.util.Locale;

/** Non-secret, process-only monotonic deadlines survive Activity recreation, never authorize access. */
final class ActivationCooldowns {
    static final int EMAIL = 0, VERIFY = 1, PAIRING = 2;
    private static final long[] deadlines = new long[3];
    private static final boolean[] rateLimited = new boolean[3];

    private ActivationCooldowns() { }

    static synchronized void observe(int action, int seconds, long now) {
        observe(action, seconds, now, false);
    }

    static synchronized void observe(int action, int seconds, long now, boolean limited) {
        if (seconds <= 0) return;
        if (deadlines[action] <= now) rateLimited[action] = false;
        deadlines[action] = Math.max(deadlines[action], now + seconds * 1000L);
        rateLimited[action] |= limited;
    }

    static synchronized boolean rateLimited(int action, long now) {
        return deadlines[action] > now && rateLimited[action];
    }

    static synchronized long remaining(int action, long now) {
        return Math.max(0L, (deadlines[action] - now + 999L) / 1000L);
    }

    static synchronized long nextTickDelay(int action, long now) {
        long millis = deadlines[action] - now;
        return millis <= 0L ? 0L : (millis - 1L) % 1000L + 1L;
    }

    static synchronized void clear() { java.util.Arrays.fill(deadlines, 0L); java.util.Arrays.fill(rateLimited, false); }

    static String format(long seconds) {
        long safe = Math.max(0L, seconds);
        if (safe >= 3600L) return String.format(Locale.ROOT, "%02d:%02d:%02d", safe / 3600L, safe / 60L % 60L, safe % 60L);
        return String.format(Locale.ROOT, "%02d:%02d", safe / 60L, safe % 60L);
    }
}
