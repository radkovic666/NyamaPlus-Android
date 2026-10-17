package fun.nyama.tv;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class DeviceLinkingTest {
    private static final String ID = String.join("", java.util.Collections.nCopies(64, "a"));
    private static final String TOKEN = String.join("", java.util.Collections.nCopies(64, "b"));
    private Context context;
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private int calls;
    private JSONObject request;

    private static Field controllerField() throws Exception {
        Field f = DeviceLinking.class.getDeclaredField("controller"); f.setAccessible(true); return f;
    }
    private String existingPlaylistId() throws Exception {
        Method m = DeviceLinking.class.getDeclaredMethod("existingPlaylistId", Context.class);
        m.setAccessible(true); return (String) m.invoke(null, context);
    }
    @Before public void before() throws Exception {
        controllerField().set(null, null);
        ActivationAccess.deny();
        context = RuntimeEnvironment.getApplication();
        for (String prefs : new String[]{"nyama_device_identity", "nyama_activation_mode", "nyama_tv_private"})
            context.getSharedPreferences(prefs, 0).edit().clear().commit();
        ActivationController.Credentials memory = new ActivationController.Credentials() {
            public String deviceId() { return ID; }
            public String token() { return TOKEN; }
            public void saveToken(String id, String token) { fail("No token writes"); }
            public void clearToken(String token) { fail("No token deletes"); }
        };
        controllerField().set(null, new DeviceLinkController((id, token) -> token.equals(memory.token()) && id.equals(memory.deviceId()), (action, body, token) -> {
            calls++; request = body; assertEquals(TOKEN, token);
            return new JSONObject().put("ok", true).put("linked", true).put("state", "linked");
        }, this::existingPlaylistId, tasks::add, () -> ActivationAccess.playbackAllowed(context),
                () -> 0L, code -> {}, Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE));
    }
    @After public void after() throws Exception { ActivationAccess.deny(); controllerField().set(null, null); }
    private ActivationResponse response(boolean active) throws Exception {
        JSONObject body = new JSONObject().put("ok", true).put("access_status", active ? "active" : "subscription_expired");
        if (active) body.put("config", new JSONObject().put("playlist_url", "https://nyama.fun/synthetic.m3u"));
        else body.put("renewal", new JSONObject().put("subscription_payment_url", "https://nyama.fun/subscription_payment.php?token=synthetic"));
        return new ActivationResponse(body, ID, TOKEN);
    }
    private void managed() { context.getSharedPreferences("nyama_activation_mode", 0).edit().putBoolean("managed", true).commit(); }

    @Test public void existingPlaylistIdUserAgentAndPreferencesSurviveLinking() throws Exception {
        managed();
        context.getSharedPreferences("nyama_device_identity", 0).edit().putString("install_nonce", "existing-installation-nonce").commit();
        String id = DeviceIdentity.deviceHeaderValue(context), ua = DeviceIdentity.userAgent(context);
        assertTrue(id.matches("[0-9a-f]{32}"));
        new fun.nyama.tv.Config(context).setDeviceMode(fun.nyama.tv.Config.MODE_TV);
        assertTrue(ActivationAccess.accept(context, response(true)));
        Map<String, ?> prefs = context.getSharedPreferences("nyama_tv_private", 0).getAll();
        assertEquals(0, calls); tasks.remove().run();
        assertEquals(id, request.getString("playlist_device_id")); assertEquals(ID, request.getString("device_id"));
        assertEquals(DeviceLinkController.display(Build.MANUFACTURER, 80), request.getString("manufacturer"));
        assertEquals(DeviceLinkController.display(Build.MODEL, 80), request.getString("model"));
        assertEquals(DeviceLinkController.display(Build.VERSION.RELEASE, 32), request.getString("android_version"));
        assertEquals(id, DeviceIdentity.deviceHeaderValue(context)); assertEquals(ua, DeviceIdentity.userAgent(context));
        assertEquals("existing-installation-nonce", context.getSharedPreferences("nyama_device_identity", 0).getString("install_nonce", ""));
        assertEquals(prefs, context.getSharedPreferences("nyama_tv_private", 0).getAll());
        assertTrue(ActivationAccess.playbackAllowed(context));
        ActivationAccess.accept(context, response(true)); DeviceLinking.playlistRefreshed();
        assertEquals(1, calls); assertTrue(tasks.isEmpty());
    }

    @Test public void secureCredentialMatchDoesNotCreateMissingActivationIdentity() throws Exception {
        java.io.File record = new java.io.File(context.getNoBackupFilesDir(), "activation.enc");
        assertFalse(record.exists());
        assertFalse(new ActivationStore(context).matchesAuthorization(ID, TOKEN));
        assertFalse(record.exists());
        assertFalse(ActivationStore.managed(context));
    }

    @Test public void missingNonceIsNotCreatedByLinking() throws Exception {
        managed(); ActivationAccess.accept(context, response(true)); tasks.remove().run();
        assertEquals(0, calls); assertNull(existingPlaylistId());
        assertFalse(context.getSharedPreferences("nyama_device_identity", 0).contains("install_nonce"));
        // Ordinary playlist code establishes its normal identity, then reports successful refresh.
        String id = DeviceIdentity.deviceHeaderValue(context);
        DeviceLinking.playlistRefreshed(); tasks.remove().run();
        assertEquals(1, calls); assertEquals(id, request.getString("playlist_device_id"));
    }

    @Test public void legacyManualInstallationDoesNotAttemptLinking() throws Exception {
        assertTrue(ActivationAccess.accept(context, response(true)));
        DeviceLinking.playlistRefreshed(); assertTrue(tasks.isEmpty()); assertEquals(0, calls);
        assertFalse(context.getSharedPreferences("nyama_device_identity", 0).contains("install_nonce"));
    }

    @Test public void expiredSubscriptionKeepsRenewalAndDoesNotLinkOrAllowPlayback() throws Exception {
        managed(); ActivationResponse expired = response(false);
        assertTrue(ActivationAccess.accept(context, expired));
        assertFalse(ActivationAccess.playbackAllowed(context));
        assertTrue(expired.payment.contains("subscription_payment.php"));
        DeviceLinking.playlistRefreshed(); assertTrue(tasks.isEmpty()); assertEquals(0, calls);
    }
}
