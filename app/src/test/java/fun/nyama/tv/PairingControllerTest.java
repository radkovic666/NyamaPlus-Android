package fun.nyama.tv;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic server fixtures; no live registration, payment or account changes. */
public class PairingControllerTest {
    @Test public void retryAfterSupportsSecondsAndHttpDates() {
        assertEquals(90, ActivationApi.retryDelay("90", 0));
        assertEquals(0, ActivationApi.retryDelay("-1", 0));
        assertEquals(0, ActivationApi.retryDelay(null, 0));
        assertEquals(0, ActivationApi.retryDelay("invalid", 0));
        long now = java.time.Instant.parse("2026-10-13T12:00:00Z").toEpochMilli();
        assertEquals(90, ActivationApi.retryDelay("Tue, 13 Oct 2026 12:01:30 GMT", now));
        assertEquals(0, ActivationApi.retryDelay("Tue, 13 Oct 2026 11:59:00 GMT", now));
    }
    static final String ID = repeat('a', 64), PAIR = repeat('b', 32), SECRET = repeat('c', 64), TOKEN = repeat('d', 64);
    static final String QR = "https://nyama.fun/register_now.php?pair=" + PAIR;
    static String repeat(char c, int count) { return String.join("", java.util.Collections.nCopies(count, String.valueOf(c))); }

    static JSONObject start() throws Exception {
        return new JSONObject().put("ok", true).put("pair_id", PAIR).put("pair_secret", SECRET)
                .put("qr_url", QR).put("expires_in", 1800).put("poll_after", 3);
    }
    static JSONObject waiting(String state) throws Exception {
        return new JSONObject().put("ok", true).put("pairing_status", state).put("poll_after", 3);
    }
    static JSONObject activated(boolean expired) throws Exception {
        JSONObject body = waiting("activated").put("activation_token", TOKEN)
                .put("token_expires_in", JSONObject.NULL).put("token_expires_at", JSONObject.NULL)
                .put("account", new JSONObject().put("username", "customer").put("client_number", "123456").put("status", "active"))
                .put("access_status", expired ? "subscription_expired" : "active");
        if (expired) body.put("renewal", new JSONObject().put("subscription_payment_url", "https://nyama.fun/subscription_payment.php?token=synthetic"));
        else body.put("config", new JSONObject().put("playlist_url", "https://nyama.fun/playlist.m3u")
                .put("vip", false).put("max_devices", 3));
        return body;
    }
    static final class Memory implements ActivationController.Credentials {
        String token = "";
        int saves;
        boolean failSave;
        Runnable afterSave = () -> {};
        public String deviceId() { return ID; }
        public String token() { return token; }
        public void saveToken(String id, String value) throws Exception {
            assertEquals(ID, id);
            if (failSave) throw new java.io.IOException("storage_unavailable");
            token = value; saves++; afterSave.run();
        }
        public void clearToken(String expected) { if (token.equals(expected)) token = ""; }
    }

    @Test public void startsWithSameInstallationIdAndNoBearer() throws Exception {
        Memory credentials = new Memory();
        PairingSession session = new ActivationController((action, body, token) -> {
            assertEquals("pair-start", action); assertEquals(ID, body.getString("device_id"));
            assertEquals(1, body.length()); assertEquals("", token); return start();
        }, credentials).startPairing(100);
        assertEquals(QR, session.qrUrl); assertEquals(1800100, session.expiresAt);
        assertFalse(session.qrUrl.contains(SECRET)); assertEquals(0, credentials.saves);
    }

    @Test public void statusSendsPrivateSecretOnlyInApkPostBody() throws Exception {
        new ActivationController((action, body, token) -> {
            assertEquals("pair-status", action); assertEquals(ID, body.getString("device_id"));
            assertEquals(PAIR, body.getString("pair_id")); assertEquals(SECRET, body.getString("pair_secret"));
            assertEquals(3, body.length()); assertEquals("", token); return waiting("waiting_for_scan");
        }, new Memory()).pollPairing(new PairingSession(ID, start(), 0));
    }

    @Test public void bothWaitingStatesPreserveTokenAndRespectDelay() throws Exception {
        Memory credentials = new Memory(); credentials.token = TOKEN;
        for (String state : new String[]{"waiting_for_scan", "waiting_for_confirmation"}) {
            PairingResult result = new ActivationController((a,b,t) -> waiting(state).put("poll_after", 9), credentials)
                    .pollPairing(new PairingSession(ID, start(), 0));
            assertEquals(state, result.status); assertFalse(result.activated()); assertEquals(9, result.pollAfter);
            assertEquals(0, credentials.saves); assertEquals(TOKEN, credentials.token);
        }
    }

    @Test public void transportResponseDoesNotPersistUntilAccepted() throws Exception {
        Memory credentials = new Memory();
        ActivationController controller = new ActivationController((a,b,t) -> activated(false), credentials);
        PairingSession session = new PairingSession(ID, start(), 0);
        PairingResult result = controller.pollPairing(session); assertEquals(0, credentials.saves);
        ActivationResponse response = controller.completePairing(session, result);
        assertEquals(ID, response.authorizationDeviceId);
        assertEquals(TOKEN, response.authorizationToken);
        assertTrue(response.active); assertEquals(TOKEN, credentials.token); assertEquals(1, credentials.saves);
    }

    @Test public void expiredSubscriptionKeepsPermanentTokenAndExactRenewalUrl() throws Exception {
        Memory credentials = new Memory();
        ActivationController controller = new ActivationController((a,b,t) -> activated(true), credentials);
        PairingSession session = new PairingSession(ID, start(), 0);
        ActivationResponse response = controller.completePairing(session, controller.pollPairing(session));
        assertFalse(response.active); assertEquals("", response.playlist); assertEquals(TOKEN, credentials.token);
        assertEquals("customer", response.username);
        assertEquals("https://nyama.fun/subscription_payment.php?token=synthetic", response.payment);
    }

    @Test public void vipTrialAndPaidAllRequireExplicitActiveConfig() throws Exception {
        for (boolean vip : new boolean[]{true, false}) {
            JSONObject body = activated(false); body.getJSONObject("config").put("vip", vip);
            Memory credentials = new Memory();
            ActivationController controller = new ActivationController((a,b,t) -> body, credentials);
            PairingSession session = new PairingSession(ID, start(), 0);
            assertTrue(controller.completePairing(session, controller.pollPairing(session)).active);
        }
    }

    @Test public void serverErrorsCannotSaveOrBypassEntitlement() throws Exception {
        for (String code : new String[]{"pairing_invalid", "pairing_expired", "device_limit_reached", "inactive_account",
                "rate_limited", "server_unavailable", "activation_invalid", "invalid_device_id", "invalid_input"}) {
            Memory credentials = new Memory();
            ActivationController controller = new ActivationController((a,b,t) -> { throw new ActivationApi.Failure(code, 75); }, credentials);
            try { controller.pollPairing(new PairingSession(ID, start(), 0)); fail(code); }
            catch (ActivationApi.Failure expected) { assertEquals(code, expected.code); assertEquals(75, expected.retryAfter); }
            assertEquals(0, credentials.saves);
        }
    }

    @Test public void unsafeQrUrlsAndSecretExposureAreRejected() throws Exception {
        for (String url : new String[]{"http://nyama.fun/register_now.php?pair=" + PAIR,
                "https://evil.example/register_now.php?pair=" + PAIR, QR + "&secret=" + SECRET,
                "https://user@nyama.fun/register_now.php?pair=" + PAIR, QR + "#fragment", QR + "extra"}) {
            try { new PairingSession(ID, start().put("qr_url", url), 0); fail(url); }
            catch (ActivationApi.Failure expected) { }
        }
    }

    @Test public void invalidSessionFieldsAndUnknownStatusAreRejected() throws Exception {
        for (String key : new String[]{"pair_id", "pair_secret", "expires_in", "poll_after"}) {
            JSONObject body = start().put(key, key.contains("in") || key.equals("poll_after") ? -1 : "invalid");
            try { new PairingSession(ID, body, 0); fail(key); } catch (Exception expected) { }
        }
        try { new PairingResult(waiting("scanned")); fail(); } catch (ActivationApi.Failure expected) { }
        assertEquals(3, new PairingSession(ID, start().put("poll_after", 1), 0).pollAfter);
    }

    @Test public void missingEntitlementNeverAcceptsScanAsAuthorization() throws Exception {
        JSONObject body = activated(false); body.remove("access_status");
        try { new PairingResult(body); fail(); } catch (ActivationApi.Failure expected) { }
    }

    @Test public void malformedConfigAfterTokenIssuanceAllowsConfigRetry() throws Exception {
        Memory credentials = new Memory(); JSONObject body = activated(false); body.remove("config");
        ActivationController controller = new ActivationController((a,b,t) -> body, credentials);
        try { controller.completePairing(new PairingSession(ID, start(), 0), new PairingResult(body)); fail(); }
        catch (org.json.JSONException expected) { }
        assertEquals(TOKEN, credentials.token);
    }

    @Test public void storageFailureNeverCompletesEnrollment() throws Exception {
        Memory credentials = new Memory(); credentials.failSave = true;
        ActivationController controller = new ActivationController((a,b,t) -> activated(false), credentials);
        try { controller.completePairing(new PairingSession(ID, start(), 0), new PairingResult(activated(false))); fail(); }
        catch (java.io.IOException expected) { }
        assertEquals("", credentials.token); assertEquals(0, credentials.saves);
    }

    @Test public void restartConfigUsesSamePermanentTokenWithoutRotation() throws Exception {
        Memory credentials = new Memory();
        ActivationController first = new ActivationController((a,b,t) -> activated(false), credentials);
        first.completePairing(new PairingSession(ID, start(), 0), new PairingResult(activated(false)));
        ActivationController restarted = new ActivationController((action,body,token) -> {
            assertEquals("config", action); assertEquals(ID, body.getString("device_id")); assertEquals(TOKEN, token);
            return activated(false);
        }, credentials);
        assertTrue(restarted.config().active); assertEquals(1, credentials.saves);
    }
}
