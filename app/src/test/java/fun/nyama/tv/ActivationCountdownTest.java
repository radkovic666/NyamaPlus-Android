package fun.nyama.tv;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class ActivationCountdownTest {
    @Before public void before() { ActivationCooldowns.clear(); }

    @Test public void formatsActualSecondsInMinutesOrHours() {
        assertEquals("04:03", ActivationCooldowns.format(243));
        assertEquals("00:00", ActivationCooldowns.format(0));
        assertEquals("59:59", ActivationCooldowns.format(3599));
        assertEquals("01:00:00", ActivationCooldowns.format(3600));
        assertEquals("24:00:01", ActivationCooldowns.format(86401));
    }

    @Test public void exactDeadlineExpiresWithoutResetsOrShortening() {
        ActivationCooldowns.observe(ActivationCooldowns.EMAIL, 243, 1000L, true);
        assertEquals(243L, ActivationCooldowns.remaining(ActivationCooldowns.EMAIL, 1000L));
        assertEquals(242L, ActivationCooldowns.remaining(ActivationCooldowns.EMAIL, 2000L));
        ActivationCooldowns.observe(ActivationCooldowns.EMAIL, 1, 2000L);
        assertEquals(242L, ActivationCooldowns.remaining(ActivationCooldowns.EMAIL, 2000L));
        assertEquals(1L, ActivationCooldowns.remaining(ActivationCooldowns.EMAIL, 243999L));
        assertEquals(0L, ActivationCooldowns.remaining(ActivationCooldowns.EMAIL, 244000L));
    }

    @Test public void actionsStayIndependentAndMissingDurationDoesNotInventDelay() {
        ActivationCooldowns.observe(ActivationCooldowns.PAIRING, 5, 1000L, true);
        ActivationCooldowns.observe(ActivationCooldowns.EMAIL, 0, 1000L, true);
        assertEquals(0L, ActivationCooldowns.remaining(ActivationCooldowns.EMAIL, 1000L));
        assertEquals(0L, ActivationCooldowns.remaining(ActivationCooldowns.VERIFY, 1000L));
        assertEquals(5L, ActivationCooldowns.remaining(ActivationCooldowns.PAIRING, 1000L));
    }

    @Test public void recreatedTickerKeepsServerSecondBoundariesAndExactZero() {
        ActivationCooldowns.observe(ActivationCooldowns.PAIRING, 5, 1001L, true);
        assertEquals(5L, ActivationCooldowns.remaining(ActivationCooldowns.PAIRING, 2000L));
        assertEquals(1L, ActivationCooldowns.nextTickDelay(ActivationCooldowns.PAIRING, 2000L));
        assertEquals(4L, ActivationCooldowns.remaining(ActivationCooldowns.PAIRING, 2001L));
        assertEquals(1000L, ActivationCooldowns.nextTickDelay(ActivationCooldowns.PAIRING, 2001L));
        assertEquals(1L, ActivationCooldowns.nextTickDelay(ActivationCooldowns.PAIRING, 6000L));
        assertEquals(0L, ActivationCooldowns.nextTickDelay(ActivationCooldowns.PAIRING, 6001L));
        assertEquals(0L, ActivationCooldowns.remaining(ActivationCooldowns.PAIRING, 6001L));
    }

    @Test public void respectsBothActualRetryAndResendFieldsWithNoFallback() throws Exception {
        assertEquals(243, ActivationApi.retrySeconds(new JSONObject().put("retry_after", 243).put("resend_after", 60), 0));
        assertEquals(7, ActivationApi.retrySeconds(new JSONObject().put("resend_after", 7), 0));
        assertEquals(0, ActivationApi.retrySeconds(new JSONObject(), 0));
        assertEquals(0, ActivationApi.retrySeconds(new JSONObject().put("retry_after", -3).put("resend_after", "invalid"), 0));
        assertEquals(5, ActivationApi.retrySeconds(new JSONObject().put("retry_after", 2), 5));
    }
}
