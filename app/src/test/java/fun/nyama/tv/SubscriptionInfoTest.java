package fun.nyama.tv;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Contract/presentation checks use synthetic server JSON, never a real account or OTP. */
public class SubscriptionInfoTest {
    private SubscriptionInfo info(String phase, Object seconds, boolean vip) throws Exception {
        return SubscriptionInfo.from(new JSONObject().put("subscription", new JSONObject()
                .put("status", "active").put("phase", phase).put("remaining_seconds", seconds).put("vip", vip)), 2000L);
    }

    @Test public void trialUsesActualServerDurationAndMonotonicClock() throws Exception {
        SubscriptionInfo trial = info("trial", 864000, false);
        assertEquals("Пробен период — остават 10 дни", trial.display(false, 2000L));
        assertEquals(863999L, trial.remainingAt(3000L));
        assertEquals(864000L, trial.remainingAt(1000L));
    }

    @Test public void paidDaysAndSoonExpiringCountdown() throws Exception {
        assertEquals("Абонамент — остават 42 дни", info("paid", 42L * 86400L, false).display(false, 2000L));
        SubscriptionInfo paid = info("subscription", 3 * 3600 + 24 * 60 + 15, false);
        assertEquals("Абонамент — остават 03:24:15", paid.display(false, 2000L));
        assertEquals("Абонамент — остават 03:24:14", paid.display(false, 3000L));
        assertEquals("Subscription — 03:24:14 remaining", paid.display(true, 3000L));
    }

    @Test public void unknownAndMalformedDurationAreGraceful() throws Exception {
        for (Object value : new Object[]{JSONObject.NULL, -1, "unknown", 1.5, 1e30})
            assertEquals("Абонамент — Няма информация за оставащото време", info("paid", value, false).display(false, 2000L));
        assertEquals(-1L, SubscriptionInfo.from(new JSONObject(), 0L).remainingAt(1000L));
    }

    @Test public void vipNeverCreatesAnExpirationDate() throws Exception {
        JSONObject body = new JSONObject().put("subscription", new JSONObject().put("vip", true).put("remaining_seconds", JSONObject.NULL)
                .put("subscription_expires_at", "2027-03-01 15:00:00"));
        SubscriptionInfo vip = SubscriptionInfo.from(body, 0L);
        assertEquals("VIP — Неограничен достъп", vip.display(false, Long.MAX_VALUE));
        assertEquals("", vip.expiryDisplay(false)); assertFalse(vip.hasCountdown());
    }

    @Test public void serverDateIsPreservedWithoutGuessingItsTimezone() throws Exception {
        SubscriptionInfo info = SubscriptionInfo.from(new JSONObject().put("account", new JSONObject()
                .put("subscription_expires_at", "2026-10-26 17:10:43")), 0L);
        assertEquals("Валиден до: 2026-10-26 17:10:43", info.expiryDisplay(false));
    }

    @Test public void documentedConfigExpiryIsSupportedAndStillIgnoredForVip() throws Exception {
        JSONObject body = new JSONObject().put("config", new JSONObject().put("subscription_expires_at", "2026-10-26 17:10:43"));
        assertEquals("Валиден до: 2026-10-26 17:10:43", SubscriptionInfo.from(body, 0L).expiryDisplay(false));
        body.put("subscription", new JSONObject().put("vip", true));
        assertEquals("", SubscriptionInfo.from(body, 0L).expiryDisplay(false));
    }

    @Test public void localZeroDoesNotInventExpiredServerStatusOrExtraTime() throws Exception {
        SubscriptionInfo paid = info("paid", 2, false);
        assertEquals(0L, paid.remainingAt(5000L)); assertEquals("active", paid.status);
        assertEquals("Абонамент — остават 00:00:00", paid.display(false, 5000L));
        assertEquals(0L, paid.remainingAt(999999L));
    }

    @Test public void newAuthorizationUpdatesDurationAfterPayment() throws Exception {
        JSONObject subscription = new JSONObject().put("status", "expired").put("phase", "paid").put("remaining_seconds", 0);
        assertEquals("Абонамент — Изтекъл", SubscriptionInfo.from(new JSONObject().put("subscription", subscription), 0L).display(false, 0L));
        subscription.put("status", "active").put("remaining_seconds", 42L * 86400L);
        assertEquals("Абонамент — остават 42 дни", SubscriptionInfo.from(new JSONObject().put("subscription", subscription), 3000L).display(false, 3000L));
    }
}
