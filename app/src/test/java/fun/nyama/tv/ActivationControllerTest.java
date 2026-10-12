package fun.nyama.tv;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

/** Host JVM tests use synthetic account data and mocked API responses, never real OTP requests. */
public class ActivationControllerTest {
    private static final String ID = repeat('a');
    private static final String TOKEN = repeat('b');
    private static final String PAYMENT = "https://nyama.fun/subscription_payment.php?token=private-test-token&lang=bg";

    private static String repeat(char c) { return String.join("", java.util.Collections.nCopies(64, String.valueOf(c))); }

    private static JSONObject active() throws Exception {
        return new JSONObject().put("ok", true).put("access_status", "active")
                .put("account", new JSONObject().put("username", "test").put("client_number", "123456"))
                .put("config", new JSONObject().put("playlist_url", "https://nyama.fun/playlist.m3u?username=test&password=synthetic")
                        .put("epg_url", "https://is.gd/fullepg").put("pukanki_base_url", "https://pukanki.fun"));
    }

    private static JSONObject expired() throws Exception {
        return new JSONObject().put("ok", true).put("access_status", "subscription_expired")
                .put("account", new JSONObject().put("status", "active"))
                .put("renewal", new JSONObject().put("subscription_payment_url", PAYMENT));
    }

    private static class Memory implements ActivationController.Credentials {
        String token = "";
        int saves, clears;
        public String deviceId() { return ID; }
        public String token() { return token; }
        public void saveToken(String id, String value) { assertEquals(ID, id); token = value; saves++; }
        public void clearToken(String expected) { if (token.equals(expected)) { token = ""; clears++; } }
    }

    @Test public void installationIdsAreRandom256BitLowercaseHex() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String id = ActivationController.newInstallationId();
            assertTrue(id.matches("[0-9a-f]{64}")); assertTrue(ids.add(id));
        }
    }

    @Test public void emailAloneRequestsChallengeWithoutSavingToken() throws Exception {
        Memory storage = new Memory();
        ActivationController controller = new ActivationController((action, body, token) -> {
            assertEquals("request-code", action); assertEquals("test@example.com", body.getString("email"));
            assertEquals(ID, body.getString("device_id")); assertEquals("", token);
            return new JSONObject().put("ok", true).put("challenge_id", "0123456789abcdef0123456789abcdef").put("expires_in", 600).put("resend_after", 90);
        }, storage);
        assertEquals(90, controller.requestCode("test@example.com").getInt("resend_after"));
        assertEquals("", storage.token); assertEquals(0, storage.saves);
    }

    @Test public void nullTokenExpiryMeansPermanentAndConfigReusesOriginalToken() throws Exception {
        Memory storage = new Memory();
        ActivationController controller = new ActivationController((action, body, token) -> {
            assertEquals(ID, body.getString("device_id"));
            if (action.equals("verify-code")) {
                assertEquals("012345", body.getString("code")); assertEquals("challenge", body.getString("challenge_id"));
                assertEquals("", token);
                return active().put("activation_token", TOKEN).put("token_expires_in", JSONObject.NULL).put("token_expires_at", JSONObject.NULL);
            }
            assertEquals("config", action); assertEquals(TOKEN, token); assertFalse(body.has("code"));
            return active();
        }, storage);
        assertTrue(controller.verify("challenge", "012345").active);
        for (int i = 0; i < 3; i++) assertTrue(new ActivationController((a, b, t) -> {
            assertEquals("config", a); assertEquals(ID, b.getString("device_id")); assertEquals(TOKEN, t); return active();
        }, storage).config().active);
        assertEquals(TOKEN, storage.token); assertEquals(1, storage.saves); assertEquals(0, storage.clears);
    }

    @Test public void expiredNewDeviceReceivesTokenWithoutPlaybackThenSameTokenRestoresAccess() throws Exception {
        Memory storage = new Memory();
        int[] checks = {0};
        ActivationController controller = new ActivationController((action, body, token) -> {
            if (action.equals("verify-code")) return expired().put("activation_token", TOKEN).put("token_expires_in", JSONObject.NULL);
            assertEquals(TOKEN, token);
            return ++checks[0] == 1 ? expired() : active();
        }, storage);
        ActivationResponse response = controller.verify("challenge", "123456");
        assertFalse(response.active); assertEquals("", response.playlist); assertEquals(PAYMENT, response.payment);
        assertFalse(controller.config().active); assertTrue(controller.config().active);
        assertEquals(TOKEN, storage.token); assertEquals(1, storage.saves); assertEquals(0, storage.clears);
    }

    @Test public void activeTrialPaidAndVipAllFollowExplicitServerEntitlement() throws Exception {
        for (String type : new String[]{"trial", "paid", "vip"}) {
            JSONObject body = active(); body.getJSONObject("config").put("subscription_type", type).put("vip", type.equals("vip"));
            ActivationResponse response = new ActivationResponse(body);
            assertTrue(response.active); assertEquals("test", response.username); assertEquals("123456", response.clientNumber);
        }
    }

    @Test public void expiredTrialAndPaidNeverExposeEvenUnexpectedCachedConfig() throws Exception {
        for (String type : new String[]{"trial", "paid"}) {
            JSONObject body = expired().put("config", active().getJSONObject("config"));
            body.getJSONObject("account").put("subscription_type", type);
            ActivationResponse response = new ActivationResponse(body);
            assertFalse(response.active); assertEquals("", response.playlist); assertEquals("", response.epg); assertEquals("", response.pukanki);
        }
    }

    @Test public void suspendedAndServerFailuresPreserveTokenAndNeverReturnEntitlement() throws Exception {
        for (String code : new String[]{"inactive_account", "server_unavailable", "email_unverified", "rate_limited", "https_required"}) {
            Memory storage = new Memory(); storage.token = TOKEN;
            ActivationController controller = new ActivationController((a, b, t) -> { throw new ActivationApi.Failure(code, 60); }, storage);
            try { controller.config(); fail("Failure must propagate"); }
            catch (ActivationApi.Failure error) { assertEquals(code, error.code); }
            assertEquals(TOKEN, storage.token); assertEquals(0, storage.clears);
        }
    }

    @Test public void timeoutDnsAndTlsFailuresPreserveTokenAndInstallation() throws Exception {
        for (Exception failure : new Exception[]{new java.net.SocketTimeoutException(), new java.net.UnknownHostException(), new javax.net.ssl.SSLHandshakeException("synthetic")}) {
            Memory storage = new Memory(); storage.token = TOKEN;
            ActivationController controller = new ActivationController((a, b, t) -> { throw failure; }, storage);
            try { controller.config(); fail(); } catch (IOException expected) { }
            assertEquals(TOKEN, storage.token); assertEquals(ID, storage.deviceId()); assertEquals(0, storage.clears);
        }
    }

    @Test public void revokedTokenClearsOnlyCredentialAndKeepsDeviceId() throws Exception {
        Memory storage = new Memory(); storage.token = TOKEN;
        ActivationController controller = new ActivationController((a, b, t) -> { throw new ActivationApi.Failure("activation_invalid", 0); }, storage);
        try { controller.config(); fail(); } catch (ActivationApi.Failure expected) { assertEquals("activation_invalid", expected.code); }
        assertEquals("", storage.token); assertEquals(ID, storage.deviceId()); assertEquals(1, storage.clears);
    }

    @Test public void delayedRevocationForOldTokenCannotEraseNewlyVerifiedToken() throws Exception {
        Memory storage = new Memory(); storage.token = TOKEN;
        String replacement = repeat('c');
        ActivationController controller = new ActivationController((a, b, t) -> {
            assertEquals(TOKEN, t); storage.token = replacement; throw new ActivationApi.Failure("activation_invalid", 0);
        }, storage);
        try { controller.config(); fail(); } catch (ActivationApi.Failure expected) { }
        assertEquals(replacement, storage.token); assertEquals(0, storage.clears);
    }

    @Test public void otpErrorsNeverStoreCredential() throws Exception {
        for (String code : new String[]{"wrong_code", "code_expired", "device_limit_reached", "account_not_found", "email_unverified", "rate_limited", "mail_unavailable", "invalid_input", "invalid_device_id"}) {
            Memory storage = new Memory();
            ActivationController controller = new ActivationController((a, b, t) -> { throw new ActivationApi.Failure(code, 0); }, storage);
            try { controller.verify("challenge", "123456"); fail(); } catch (ActivationApi.Failure expected) { assertEquals(code, expected.code); }
            assertEquals(0, storage.saves); assertEquals("", storage.token);
        }
    }

    @Test public void requestErrorsNeverActivate() throws Exception {
        for (String code : new String[]{"account_not_found", "email_unverified", "mail_unavailable", "rate_limited", "invalid_device_id"}) {
            Memory storage = new Memory();
            ActivationController controller = new ActivationController((a, b, t) -> { throw new ActivationApi.Failure(code, 90); }, storage);
            try { controller.requestCode("test@example.com"); fail(); } catch (ActivationApi.Failure expected) { assertEquals(90, expected.retryAfter); }
            assertEquals(0, storage.saves);
        }
    }

    @Test public void missingOrUnknownEntitlementNeverGrantsPlayback() throws Exception {
        for (String state : new String[]{"", "inactive_account", "suspended", "ACTIVE"}) {
            try { new ActivationResponse(active().put("access_status", state)); fail(); }
            catch (ActivationApi.Failure expected) { }
        }
    }

    @Test public void accountStatusActiveAloneNeverGrantsPlayback() throws Exception {
        JSONObject response = new JSONObject().put("ok", true).put("account", new JSONObject().put("status", "active"));
        try { new ActivationResponse(response); fail(); } catch (ActivationApi.Failure expected) { }
    }

    @Test public void missingActiveConfigIsRejected() throws Exception {
        JSONObject response = active(); response.remove("config");
        try { new ActivationResponse(response); fail(); } catch (org.json.JSONException expected) { }
    }

    @Test public void invalidConfigAfterIssuanceKeepsTokenForSafeRetry() throws Exception {
        Memory storage = new Memory();
        ActivationController controller = new ActivationController((a, b, t) -> {
            JSONObject response = active().put("activation_token", TOKEN); response.remove("config"); return response;
        }, storage);
        try { controller.verify("challenge", "123456"); fail(); } catch (org.json.JSONException expected) { }
        assertEquals(TOKEN, storage.token); assertEquals(1, storage.saves);
    }

    @Test public void paymentUrlIsExactAndRejectsHourlyExternalOrInsecureLinks() throws Exception {
        assertEquals(PAYMENT, ActivationResponse.paymentUrl(PAYMENT));
        for (String url : new String[]{"http://nyama.fun/subscription_payment.php?token=x", "https://evil.example/subscription_payment.php?token=x",
                "https://nyama.fun/hourly_payment.php?token=x", "https://nyama.fun/subscription_payment.php", "https://nyama.fun/subscription_payment.php?token=",
                "https://user@nyama.fun/subscription_payment.php?token=x", "https://nyama.fun:8443/subscription_payment.php?token=x"}) {
            try { ActivationResponse.paymentUrl(url); fail(url); } catch (ActivationApi.Failure expected) { }
        }
    }

    @Test public void renewalWithoutPersonalizedUrlNeverFabricatesOne() throws Exception {
        JSONObject response = expired(); response.remove("renewal");
        try { new ActivationResponse(response); fail(); } catch (org.json.JSONException expected) { }
    }

    @Test public void insecurePlaylistIsRejectedBeforeProvisioning() throws Exception {
        JSONObject response = active(); response.getJSONObject("config").put("playlist_url", "http://nyama.fun/playlist.m3u");
        try { new ActivationResponse(response); fail(); } catch (ActivationApi.Failure expected) { }
    }
}
