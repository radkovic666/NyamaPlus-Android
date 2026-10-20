package fun.nyama.tv;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Fresh /config refresh and renewal tests use an in-memory credential/transport only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SubscriptionSettingsTest {
    private Application app;
    private ActivationAccess access;
    private final PairingUiTest.ManualExecutor executor = new PairingUiTest.ManualExecutor();
    private final Queue<JSONObject> replies = new ArrayDeque<>();
    private int requests;
    private final PairingControllerTest.Memory credentials = new PairingControllerTest.Memory();

    @Before public void before() throws Exception {
        app = RuntimeEnvironment.getApplication(); ActivationAccess.deny(); ActivationCooldowns.clear();
        app.getSharedPreferences("nyama_activation_mode", Context.MODE_PRIVATE).edit().clear().putBoolean("managed", true).commit();
        app.getSharedPreferences("nyama_tv_private", Context.MODE_PRIVATE).edit().clear().commit();
        credentials.token = "synthetic-device-token";
        ActivationAccess.accept(app, new ActivationResponse(active(8)));
        access = new ActivationAccess(app); access.io.shutdownNow(); access.io = executor;
        access.configController = new ActivationController((action, body, token) -> {
            assertEquals("config", action); assertEquals("synthetic-device-token", token);
            requests++; return replies.remove();
        }, credentials);
    }

    @After public void after() {
        app.unregisterActivityLifecycleCallbacks(access); access.io.shutdownNow(); ActivationAccess.deny();
    }

    private JSONObject active(long seconds) throws Exception {
        return new JSONObject().put("ok", true).put("access_status", "active")
                .put("account", new JSONObject().put("username", "synthetic-account"))
                .put("config", new JSONObject().put("playlist_url", "https://nyama.fun/playlist.m3u"))
                .put("subscription", new JSONObject().put("status", "active").put("phase", "paid").put("remaining_seconds", seconds).put("vip", false));
    }

    private String text(View view) {
        StringBuilder value = new StringBuilder();
        if (view instanceof TextView) value.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) value.append(text(((ViewGroup) view).getChildAt(i)));
        return value.toString();
    }

    private ActivityController<SettingsActivity> open() {
        return Robolectric.buildActivity(SettingsActivity.class).create().start().resume().visible();
    }

    @Test public void settingsRefreshesViaExistingConfigAndTicksWithoutPerSecondHttp() throws Exception {
        ActivityController<SettingsActivity> screen = open(); assertEquals(1, executor.queue.size());
        replies.add(active(3 * 3600 + 24 * 60 + 15)); executor.runNext(); PairingUiTest.idle();
        assertEquals(1, requests); assertTrue(text(screen.get().getWindow().getDecorView()).contains("03:24:15"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertTrue(text(screen.get().getWindow().getDecorView()).contains("03:24:13"));
        assertEquals(1, requests); assertTrue(executor.queue.isEmpty());
        screen.pause(); Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        screen.resume(); assertEquals(1, executor.queue.size());
        replies.add(active(42L * 86400L)); executor.runNext(); PairingUiTest.idle();
        assertEquals(2, requests); assertTrue(text(screen.get().getWindow().getDecorView()).contains("42 дни"));
        screen.pause().stop().destroy();
    }

    @Test public void explicitServerExpiryUsesExistingRenewalWithoutDeletingPermanentToken() throws Exception {
        new fun.nyama.tv.Config(app).setDeviceMode(fun.nyama.tv.Config.MODE_TV);
        ActivityController<SettingsActivity> screen = open();
        replies.add(new JSONObject().put("ok", true).put("access_status", "subscription_expired")
                .put("subscription", new JSONObject().put("status", "expired").put("phase", "paid").put("remaining_seconds", 0))
                .put("renewal", new JSONObject().put("subscription_payment_url", "https://nyama.fun/subscription_payment.php?token=synthetic-billing")));
        executor.runNext(); PairingUiTest.idle();
        assertFalse(ActivationAccess.playbackAllowed(app)); assertEquals("synthetic-device-token", credentials.token);
        Intent gate = Shadows.shadowOf(screen.get()).getNextStartedActivity();
        assertEquals(ActivationActivity.class.getName(), gate.getComponent().getClassName());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK, gate.getFlags());
        ActivityController<ActivationActivity> renewal = Robolectric.buildActivity(ActivationActivity.class).create();
        assertTrue(text(renewal.get().getWindow().getDecorView()).contains("Абонаментът Ви е изтекъл"));
        assertEquals(1, requests); assertTrue(executor.queue.isEmpty()); // Reuses the verified expiry response.
        renewal.destroy(); screen.pause().stop().destroy();
    }

    @Test public void reachingLocalZeroKeepsServerAuthorizationAndDoesNotSendExtraRequests() throws Exception {
        ActivityController<SettingsActivity> screen = open(); replies.add(active(1)); executor.runNext(); PairingUiTest.idle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertTrue(text(screen.get().getWindow().getDecorView()).contains("00:00:00"));
        assertTrue(ActivationAccess.playbackAllowed(app)); assertEquals(1, requests); assertTrue(executor.queue.isEmpty());
        screen.pause().stop().destroy();
    }
}
