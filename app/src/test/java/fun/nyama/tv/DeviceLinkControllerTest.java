package fun.nyama.tv;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Synthetic transport only. No live server, playlist binding, Keystore or hardware claims. */
public class DeviceLinkControllerTest {
    private static final String ID = repeat('a', 64), TOKEN = repeat('b', 64), PLAYLIST = repeat('c', 32);
    private static String repeat(char c, int n) { return String.join("", java.util.Collections.nCopies(n, "" + c)); }
    private static JSONObject linked() throws Exception {
        return new JSONObject().put("ok", true).put("linked", true).put("state", "linked");
    }
    private static JSONObject waiting() throws Exception {
        return new JSONObject().put("ok", true).put("linked", false).put("state", "awaiting_playlist");
    }
    private static final class Memory implements ActivationController.Credentials {
        String id = ID, token = TOKEN;
        int writes, idReads;
        public String deviceId() { idReads++; return id; }
        public String token() { return token; }
        public void saveToken(String id, String token) { writes++; fail("Linking cannot write credentials"); }
        public void clearToken(String token) { writes++; fail("Linking cannot clear credentials"); }
    }
    private static final class Fixture {
        final Memory credentials = new Memory();
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final List<String> events = new ArrayList<>();
        boolean allowed = true;
        long now;
        String playlist = PLAYLIST;
        int calls;
        JSONObject body;
        String bearer;
        ActivationController.Transport reply = (a, b, t) -> linked();
        final DeviceLinkController controller = new DeviceLinkController((id, token) -> token.equals(credentials.token()) && id.equals(credentials.deviceId()), (a, b, t) -> {
            assertEquals("device-link", a); calls++; body = b; bearer = t; return reply.post(a, b, t);
        }, () -> playlist, tasks::add, () -> allowed, () -> now, events::add,
                " Xiaomi\n", "2312DRA50G\u0000", "16");
        void authorize() { controller.authorized(credentials.id, credentials.token); }
        void run() { assertFalse(tasks.isEmpty()); tasks.remove().run(); }
    }

    @Test public void existingIdsAndBearerAreSentWithoutChangingCredentials() throws Exception {
        Fixture f = new Fixture(); f.authorize();
        assertEquals(0, f.calls); // Authorization only queues I/O.
        f.run();
        assertEquals(ID, f.body.getString("device_id"));
        assertEquals(PLAYLIST, f.body.getString("playlist_device_id"));
        assertEquals(TOKEN, f.bearer);
        assertEquals("Xiaomi", f.body.getString("manufacturer"));
        assertEquals("2312DRA50G", f.body.getString("model"));
        assertEquals("16", f.body.getString("android_version"));
        assertEquals(5, f.body.length());
        assertEquals(ID, f.credentials.id); assertEquals(TOKEN, f.credentials.token);
        assertEquals(0, f.credentials.writes);
    }

    @Test public void successfulLinkIsNotRepeatedByAuthorizationOrPlaylistRefresh() {
        Fixture f = new Fixture(); f.authorize(); f.run();
        for (int i = 0; i < 10; i++) { f.authorize(); f.controller.playlistRefreshed(); }
        assertEquals(1, f.calls); assertTrue(f.tasks.isEmpty());
    }

    @Test public void awaitingPlaylistRetriesOnlyAfterOrdinarySuccess() throws Exception {
        Fixture f = new Fixture(); f.reply = (a,b,t) -> waiting(); f.authorize(); f.run();
        f.authorize(); assertTrue(f.tasks.isEmpty());
        f.controller.playlistRefreshed(); assertEquals(1, f.tasks.size());
        f.reply = (a,b,t) -> linked(); f.run();
        assertEquals(2, f.calls); assertTrue(f.tasks.isEmpty());
    }

    @Test public void manyConcurrentTriggersProduceOnlyOneRequest() {
        Fixture f = new Fixture(); f.authorize();
        for (int i = 0; i < 10; i++) { f.authorize(); f.controller.playlistRefreshed(); }
        assertEquals(1, f.tasks.size()); f.run();
        assertEquals(1, f.calls); assertTrue(f.tasks.isEmpty());
    }

    @Test public void ordinaryPlaylistFinishingDuringInitialRequestIsNotLost() {
        Fixture f = new Fixture(); f.reply = (a,b,t) -> {
            f.controller.playlistRefreshed(); return waiting();
        };
        f.authorize(); f.run(); assertEquals(1, f.tasks.size());
        f.reply = (a,b,t) -> linked(); f.run(); assertEquals(2, f.calls);
    }

    @Test public void conflictsAndAuthorizationFailuresNeverRetryOrChangeIdentity() {
        for (String code : new String[]{"activation_invalid", "inactive_account", "email_unverified",
                "invalid_input", "device_link_conflict", "device_link_ambiguous"}) {
            Fixture f = new Fixture(); f.reply = (a,b,t) -> { throw new ActivationApi.Failure(code, 0); };
            f.authorize(); f.run(); f.authorize(); f.controller.playlistRefreshed();
            assertTrue(f.tasks.isEmpty()); assertEquals(1, f.calls);
            assertEquals(java.util.Collections.singletonList(code), f.events);
            assertEquals(ID, f.credentials.id); assertEquals(TOKEN, f.credentials.token);
            assertEquals(0, f.credentials.writes); assertTrue(f.allowed);
        }
    }

    @Test public void networkFailureDoesNotChangeAccessAndWaitsForNormalRefresh() {
        Fixture f = new Fixture(); f.reply = (a,b,t) -> { throw new IOException("sensitive server text"); };
        f.authorize(); f.run(); f.authorize();
        assertTrue(f.allowed); assertTrue(f.tasks.isEmpty());
        assertEquals(java.util.Collections.singletonList("server_unavailable"), f.events);
        f.reply = (a,b,t) -> linked(); f.controller.playlistRefreshed(); f.run();
        assertEquals(2, f.calls); assertEquals(0, f.credentials.writes);
    }

    @Test public void retryAfterIsRespectedWithoutAddingTimers() {
        Fixture f = new Fixture(); f.reply = (a,b,t) -> { throw new ActivationApi.Failure("server_unavailable", 60); };
        f.authorize(); f.run(); f.controller.playlistRefreshed(); assertTrue(f.tasks.isEmpty());
        f.now = 60000; f.controller.playlistRefreshed(); f.reply = (a,b,t) -> linked(); f.run();
        assertEquals(2, f.calls);
    }

    @Test public void revocationBeforeQueuedRequestPreventsSending() {
        Fixture f = new Fixture(); f.authorize(); f.credentials.token = ""; f.run();
        assertEquals(0, f.calls); assertEquals(0, f.credentials.idReads); assertEquals(0, f.credentials.writes);
    }

    @Test public void tokenReplacementDuringRequestCannotMarkNewSessionLinked() {
        Fixture f = new Fixture(); String replacement = repeat('d', 64);
        f.reply = (a,b,t) -> { f.credentials.token = replacement; f.authorize(); return linked(); };
        f.authorize(); f.run(); assertEquals(1, f.tasks.size());
        f.reply = (a,b,t) -> linked(); f.run();
        assertEquals(replacement, f.bearer); assertEquals(2, f.calls);
    }

    @Test public void denialDuringIoDiscardsResponseWithoutRevivingSession() {
        Fixture f = new Fixture(); f.reply = (a,b,t) -> { f.allowed = false; f.controller.denied(); return linked(); };
        f.authorize(); f.run(); f.controller.playlistRefreshed();
        assertTrue(f.tasks.isEmpty()); assertFalse(f.allowed); assertEquals(1, f.calls);
    }

    @Test public void activityBackgroundingDuringIoDoesNotForgetAnUnchangedSuccessfulLink() {
        Fixture f = new Fixture(); f.reply = (a,b,t) -> { f.allowed = false; return linked(); };
        f.authorize(); f.run(); f.allowed = true; f.authorize(); f.controller.playlistRefreshed();
        assertTrue(f.tasks.isEmpty()); assertEquals(1, f.calls);
    }

    @Test public void backgroundBeforeQueuedIoDefersUntilNextAuthorization() {
        Fixture f = new Fixture(); f.authorize(); f.allowed = false; f.run(); assertEquals(0, f.calls);
        f.allowed = true; f.authorize(); f.run(); assertEquals(1, f.calls);
    }

    @Test public void changedCredentialsWithoutNewAuthorizationAreNotLinked() {
        Fixture f = new Fixture(); f.authorize(); f.credentials.token = repeat('e', 64); f.run();
        f.controller.playlistRefreshed(); assertEquals(0, f.calls); assertTrue(f.tasks.isEmpty());
    }

    @Test public void legacyOrMalformedCredentialsNeverSend() {
        for (String token : new String[]{"", "bad", repeat('A', 64)}) {
            Fixture f = new Fixture(); f.credentials.token = token; f.authorize(); f.controller.playlistRefreshed();
            assertTrue(f.tasks.isEmpty()); assertEquals(0, f.calls); assertEquals(0, f.credentials.idReads);
        }
        Fixture f = new Fixture(); f.credentials.id = "not-an-id"; f.authorize(); assertTrue(f.tasks.isEmpty());
    }

    @Test public void missingPlaylistNonceWaitsWithoutCreatingAnIdentity() {
        Fixture f = new Fixture(); f.playlist = null; f.authorize(); f.run();
        assertEquals(0, f.calls); assertTrue(f.events.isEmpty());
        f.playlist = PLAYLIST; f.controller.playlistRefreshed(); f.run(); assertEquals(1, f.calls);
    }

    @Test public void malformedPlaylistIdStopsWithoutFallback() {
        Fixture f = new Fixture(); f.playlist = "Xiaomi"; f.authorize(); f.run();
        f.controller.playlistRefreshed(); assertTrue(f.tasks.isEmpty()); assertEquals(0, f.calls);
        assertEquals(java.util.Collections.singletonList("invalid_input"), f.events);
    }

    @Test public void malformedServerSuccessNeverCountsAsLinked() throws Exception {
        Fixture f = new Fixture(); f.reply = (a,b,t) -> new JSONObject().put("ok", true).put("linked", true)
                .put("state", "awaiting_playlist");
        f.authorize(); f.run(); assertTrue(f.tasks.isEmpty());
        f.reply = (a,b,t) -> linked(); f.controller.playlistRefreshed(); f.run(); assertEquals(2, f.calls);
    }

    @Test public void metadataIsBoundedAndDoesNotInventNames() {
        assertEquals("", DeviceLinkController.display(null, 80));
        assertEquals("", DeviceLinkController.display("unknown", 80));
        assertEquals("Sony BRAVIA", DeviceLinkController.display("Sony\u202e\nBRAVIA", 80));
        assertEquals(80, DeviceLinkController.display(repeat('x', 100), 80).length());
    }
}
