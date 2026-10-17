package fun.nyama.tv;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
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

/** Simulated clock/UI checks with only mock transports; no real email, pairing or payment calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ActivationTimersUiTest {
    @Before public void before() {
        ActivationCooldowns.clear(); ActivationAccess.deny();
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("nyama_activation_mode", 0).edit().clear().commit();
        context.getSharedPreferences("nyama_tv_private", 0).edit().clear().commit();
    }

    private static List<View> views(View root) {
        List<View> result = new ArrayList<>(); result.add(root);
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) result.addAll(views(((ViewGroup) root).getChildAt(i)));
        return result;
    }
    private static Button button(ActivationActivity activity, String label) {
        for (View view : views(activity.getWindow().getDecorView())) if (view instanceof Button && ((Button) view).getText().toString().contains(label)) return (Button) view;
        throw new AssertionError("Button missing: " + label);
    }
    private static String text(android.app.Activity activity) {
        StringBuilder result = new StringBuilder();
        for (View view : views(activity.getWindow().getDecorView())) if (view instanceof TextView) result.append(((TextView) view).getText()).append('\n');
        return result.toString();
    }
    private static EditText input(ActivationActivity activity) {
        for (View view : views(activity.getWindow().getDecorView())) if (view instanceof EditText) return (EditText) view;
        throw new AssertionError("Input missing");
    }
    private PairingUiTest.Harness email() throws Exception {
        PairingUiTest.Harness h = new PairingUiTest.Harness(); h.respond(PairingControllerTest.start());
        h.activity.emailController = h.activity.pairingController;
        button(h.activity, "I am registered already").performClick(); input(h.activity).setText("synthetic@example.com");
        return h;
    }
    private JSONObject codeResponse(Integer resend) throws Exception {
        JSONObject body = new JSONObject().put("ok", true).put("challenge_id", "0123456789abcdef0123456789abcdef").put("expires_in", 600);
        if (resend != null) body.put("resend_after", resend);
        return body;
    }

    @Test public void emailRateLimitCountsDownAndBlocksAllSendRequests() throws Exception {
        PairingUiTest.Harness h = email(); button(h.activity, "Изпрати код").performClick();
        h.respond(new ActivationApi.Failure("rate_limited", 243));
        assertTrue(text(h.activity).contains("Твърде много заявки. Опитайте отново след 04:03."));
        assertFalse(button(h.activity, "Изпрати код").isEnabled());
        button(h.activity, "Изпрати код").performClick(); assertTrue(h.executor.queue.isEmpty());
        h.advance(1); assertTrue(text(h.activity).contains("04:02"));
        h.activity.onConfigurationChanged(h.activity.getResources().getConfiguration()); assertTrue(text(h.activity).contains("04:02"));
        h.advance(242); assertTrue(button(h.activity, "Изпрати код").isEnabled());
    }

    @Test public void emailActivityRecreationKeepsDeadlineWithoutPersistingCredentials() throws Exception {
        PairingUiTest.Harness h = email(); button(h.activity, "Изпрати код").performClick(); h.respond(new ActivationApi.Failure("rate_limited", 5));
        h.advance(1); Bundle state = new Bundle(); h.lifecycle.saveInstanceState(state).pause().stop().destroy();
        assertEquals("email", state.getString("activation_screen"));
        assertFalse(state.containsKey("email")); assertFalse(state.containsKey("challenge"));
        ActivityController<ActivationActivity> restored = Robolectric.buildActivity(ActivationActivity.class).create(state);
        restored.get().io.shutdownNow(); restored.get().io = new PairingUiTest.ManualExecutor();
        restored.start().resume().visible();
        assertTrue(text(restored.get()).contains("00:04")); assertFalse(button(restored.get(), "Изпрати код").isEnabled());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));
        assertTrue(button(restored.get(), "Изпрати код").isEnabled()); restored.pause().stop().destroy();
    }

    @Test public void successfulResendAfterUsesSevenSecondsWithoutSixtySecondMinimum() throws Exception {
        PairingUiTest.Harness h = email(); button(h.activity, "Изпрати код").performClick(); h.respond(codeResponse(7));
        assertTrue(button(h.activity, "Изпрати отново").getText().toString().contains("00:07"));
        h.advance(6); assertFalse(button(h.activity, "Изпрати отново").isEnabled());
        h.advance(1); assertTrue(button(h.activity, "Изпрати отново").isEnabled());
    }

    @Test public void absentWaitOnSuccessOrRateLimitDoesNotInventCooldown() throws Exception {
        PairingUiTest.Harness h = email(); button(h.activity, "Изпрати код").performClick(); h.respond(codeResponse(null));
        assertTrue(button(h.activity, "Изпрати отново").isEnabled());
        button(h.activity, "Изпрати отново").performClick(); h.respond(new ActivationApi.Failure("rate_limited", 0));
        assertTrue(button(h.activity, "Изпрати отново").isEnabled()); assertFalse(text(h.activity).contains("01:00"));
    }

    @Test public void verifyRateLimitDisablesVerificationAndKeepsPermanentCredentialUntouched() throws Exception {
        PairingUiTest.Harness h = email(); button(h.activity, "Изпрати код").performClick(); h.respond(codeResponse(null));
        input(h.activity).setText("123456"); button(h.activity, "Потвърди").performClick(); h.respond(new ActivationApi.Failure("rate_limited", 3));
        assertTrue(text(h.activity).contains("00:03")); assertFalse(button(h.activity, "Потвърди").isEnabled());
        assertEquals(0, h.credentials.saves); h.advance(3); assertTrue(button(h.activity, "Потвърди").isEnabled());
    }

    @Test public void qrCreationCooldownSurvivesRecreationAndUsesHourFormat() throws Exception {
        PairingUiTest.Harness h = new PairingUiTest.Harness(); h.respond(new ActivationApi.Failure("rate_limited", 3661));
        assertTrue(text(h.activity).contains("01:01:01")); h.advance(1);
        h.lifecycle.pause().stop().destroy(); PairingUiTest.Harness next = new PairingUiTest.Harness();
        assertTrue(next.executor.queue.isEmpty()); assertTrue(text(next.activity).contains("01:01:00"));
        assertFalse(button(next.activity, "Опитайте отново").isEnabled());
        next.advance(3660); assertTrue(button(next.activity, "Опитайте отново").isEnabled());
        button(next.activity, "Опитайте отново").performClick(); assertEquals(1, next.executor.queue.size());
    }

    @Test public void qrWithoutDurationCanRetryImmediately() throws Exception {
        PairingUiTest.Harness h = new PairingUiTest.Harness(); h.respond(new ActivationApi.Failure("rate_limited", 0));
        assertTrue(button(h.activity, "Опитайте отново").isEnabled());
        button(h.activity, "Опитайте отново").performClick(); assertEquals(1, h.executor.queue.size());
    }

    @Test public void expiredQrCannotGenerateNewSessionDuringKnownCooldown() throws Exception {
        PairingUiTest.Harness h = new PairingUiTest.Harness(); h.respond(PairingControllerTest.start().put("expires_in", 5));
        h.advance(3); h.respond(new ActivationApi.Failure("rate_limited", 10)); h.advance(2);
        assertFalse(button(h.activity, "Генерирай нов QR код").isEnabled()); assertTrue(text(h.activity), text(h.activity).contains("00:08"));
        h.advance(8); assertTrue(button(h.activity, "Генерирай нов QR код").isEnabled());
    }

    @Test public void lateRateLimitResponseStillProtectsNextActivityFromAnotherRequest() throws Exception {
        PairingUiTest.Harness h = email(); button(h.activity, "Изпрати код").performClick();
        h.responses.add(new ActivationApi.Failure("rate_limited", 5)); h.executor.runNext();
        Bundle state = new Bundle(); h.lifecycle.saveInstanceState(state).pause().stop().destroy(); PairingUiTest.idle();
        ActivityController<ActivationActivity> restored = Robolectric.buildActivity(ActivationActivity.class).create(state);
        restored.get().io.shutdownNow(); restored.get().io = new PairingUiTest.ManualExecutor(); restored.start().resume().visible();
        assertFalse(button(restored.get(), "Изпрати код").isEnabled()); assertTrue(text(restored.get()).contains("00:05"));
        restored.pause().stop().destroy();
    }
}
