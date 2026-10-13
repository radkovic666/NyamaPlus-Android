package fun.nyama.tv;

import org.json.JSONObject;
import java.util.Locale;

/** Presentation only: server access_status remains the sole subscription authorization decision. */
final class SubscriptionInfo {
    static final SubscriptionInfo UNKNOWN = new SubscriptionInfo("", "", -1L, false, "", 0L);
    final String status, phase, expiresAt;
    final boolean vip;
    final long remainingSeconds, observedAt;

    private SubscriptionInfo(String status, String phase, long remainingSeconds, boolean vip, String expiresAt, long observedAt) {
        this.status = status; this.phase = phase; this.remainingSeconds = remainingSeconds;
        this.vip = vip; this.expiresAt = expiresAt; this.observedAt = observedAt;
    }

    static SubscriptionInfo from(JSONObject body, long now) {
        JSONObject subscription = body.optJSONObject("subscription");
        String expiry = date(subscription);
        if (expiry.isEmpty()) expiry = date(body.optJSONObject("account"));
        if (expiry.isEmpty()) expiry = date(body.optJSONObject("renewal"));
        if (expiry.isEmpty()) expiry = date(body.optJSONObject("config"));
        if (subscription == null) return new SubscriptionInfo("", "", -1L, false, expiry, now);
        long seconds = -1L;
        Object raw = subscription.opt("remaining_seconds");
        if (raw instanceof Number) {
            double number = ((Number) raw).doubleValue();
            if (number >= 0 && number <= Long.MAX_VALUE / 1000L && number == Math.floor(number))
                seconds = ((Number) raw).longValue();
        }
        return new SubscriptionInfo(subscription.optString("status", ""), subscription.optString("phase", ""),
                seconds, subscription.optBoolean("vip", false), expiry, now);
    }

    // This existing field may be an unzoned server date. Preserve it without inventing a timezone.
    private static String date(JSONObject object) {
        return object == null || object.isNull("subscription_expires_at") ? "" : object.optString("subscription_expires_at", "").trim();
    }

    SubscriptionInfo observedAt(long now) {
        return new SubscriptionInfo(status, phase, remainingSeconds, vip, expiresAt, now);
    }

    long remainingAt(long now) {
        if (remainingSeconds < 0L) return -1L;
        long elapsed = Math.max(0L, now - observedAt);
        return Math.max(0L, (remainingSeconds * 1000L - elapsed + 999L) / 1000L);
    }

    boolean hasCountdown() { return !vip && "active".equals(status) && remainingSeconds > 0L; }

    String display(boolean english, long now) {
        if (vip) return english ? "VIP — Unlimited access" : "VIP — Неограничен достъп";
        String label = "trial".equals(phase) ? (english ? "Trial" : "Пробен период")
                : ("paid".equals(phase) || "subscription".equals(phase)) ? (english ? "Subscription" : "Абонамент")
                : (english ? "Subscription" : "Абонамент");
        if ("expired".equals(status) || "subscription_expired".equals(status))
            return label + (english ? " — Expired" : " — Изтекъл");
        long remaining = remainingAt(now);
        if (!"active".equals(status) || remaining < 0L)
            return label + (english ? " — Remaining time unavailable" : " — Няма информация за оставащото време");
        String duration;
        if (remaining >= 86400L) {
            long days = remaining / 86400L;
            duration = days + (english ? (days == 1L ? " day" : " days") : (days == 1L ? " ден" : " дни"));
        } else duration = String.format(Locale.ROOT, "%02d:%02d:%02d", remaining / 3600L, remaining / 60L % 60L, remaining % 60L);
        return label + (english ? " — " + duration + " remaining" : " — остават " + duration);
    }

    String expiryDisplay(boolean english) {
        if (vip || expiresAt.isEmpty()) return "";
        return (english ? "Valid until: " : "Валиден до: ") + expiresAt;
    }
}
