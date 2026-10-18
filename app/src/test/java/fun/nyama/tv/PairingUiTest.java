package fun.nyama.tv;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static fun.nyama.tv.PairingControllerTest.*;

/** Controlled executor + simulated main clock: no live server or actual hardware. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class PairingUiTest {
    @Before public void before() {
        ActivationCooldowns.clear();
        Context c = RuntimeEnvironment.getApplication(); ActivationAccess.deny();
        c.getSharedPreferences("nyama_activation_mode", 0).edit().clear().commit();
        c.getSharedPreferences("nyama_tv_private", 0).edit().clear().commit();
    }

    static final class ManualExecutor extends AbstractExecutorService {
        final Queue<Runnable> queue = new ArrayDeque<>();
        boolean stopped;
        public void execute(Runnable command) { queue.add(command); }
        public void shutdown() { stopped = true; }
        public List<Runnable> shutdownNow() { stopped = true; List<Runnable> rest = new ArrayList<>(queue); queue.clear(); return rest; }
        public boolean isShutdown() { return stopped; }
        public boolean isTerminated() { return stopped; }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
        void runNext() { assertFalse("No queued request", queue.isEmpty()); queue.remove().run(); }
    }

    static final class Harness {
        final ActivityController<ActivationActivity> lifecycle;
        final ActivationActivity activity;
        final ManualExecutor executor = new ManualExecutor();
        final Memory credentials = new Memory();
        final Queue<Object> responses = new ArrayDeque<>();
        final List<String> actions = new ArrayList<>();
        Harness() {
            lifecycle = Robolectric.buildActivity(ActivationActivity.class).create(); activity = lifecycle.get();
            activity.io.shutdownNow(); activity.io = executor;
            credentials.afterSave = () -> activity.getSharedPreferences("nyama_activation_mode",0).edit().putBoolean("managed",true).commit();
            activity.pairingController = new ActivationController((a,b,t) -> {
                actions.add(a);
                Object response = responses.remove();
                if (response instanceof Exception) throw (Exception) response;
                return (JSONObject) response;
            }, credentials);
            lifecycle.start().resume().visible();
        }
        void respond(Object response) { responses.add(response); executor.runNext(); idle(); }
        void advance(long seconds) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds)); }
    }

    static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    static ActivationActivity welcome() {
        Harness h = new Harness();
        try { h.respond(PairingControllerTest.start()); } catch (Exception e) { throw new AssertionError(e); }
        return h.activity;
    }
    private static List<View> views(View root) {
        List<View> result = new ArrayList<>(); result.add(root);
        if (root instanceof ViewGroup) for (int i=0;i<((ViewGroup)root).getChildCount();i++) result.addAll(views(((ViewGroup)root).getChildAt(i)));
        return result;
    }
    private static String text(ActivationActivity activity) {
        StringBuilder result = new StringBuilder();
        for (View v:views(activity.getWindow().getDecorView())) if (v instanceof TextView) {
            result.append(((TextView)v).getText()).append('\n');
            if (((TextView)v).getHint()!=null) result.append(((TextView)v).getHint()).append('\n');
        }
        return result.toString();
    }
    private static Button button(ActivationActivity activity, String label) {
        for (View v:views(activity.getWindow().getDecorView())) if (v instanceof Button && ((Button)v).getText().toString().contains(label)) return (Button)v;
        throw new AssertionError("Missing button: " + label);
    }
    private static String qr(ActivationActivity activity) throws Exception {
        for (View v:views(activity.getWindow().getDecorView())) if (v instanceof ImageView) {
            Bitmap bitmap = ((BitmapDrawable)((ImageView)v).getDrawable()).getBitmap(); int w=bitmap.getWidth(),h=bitmap.getHeight();
            int[] pixels=new int[w*h]; bitmap.getPixels(pixels,0,w,0,0,w,h);
            return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w,h,pixels)))).getText();
        }
        throw new AssertionError("No QR");
    }

    @Test public void startsAutomaticallyAndQrIsExactWithoutSecret() throws Exception {
        Harness h=new Harness(); assertEquals(1,h.executor.queue.size());
        h.respond(start()); assertEquals(Collections.singletonList("pair-start"),h.actions);
        assertEquals(QR,qr(h.activity)); assertFalse(text(h.activity).contains(SECRET));
        assertFalse(text(h.activity).contains("M3U")); assertFalse(text(h.activity).contains("EPG"));
    }
    @Test public void scanAndConfirmationMessagesUpdateAutomatically() throws Exception {
        Harness h=new Harness(); h.respond(start()); h.advance(3); h.respond(waiting("waiting_for_scan"));
        assertTrue(text(h.activity).contains("Очакваме да сканирате"));
        h.advance(3); h.respond(waiting("waiting_for_confirmation"));
        assertTrue(text(h.activity).contains("Регистрацията е получена!"));
        assertTrue(text(h.activity).contains("Потвърдете имейла")); assertEquals(QR,qr(h.activity));
    }
    @Test public void activePairSavesTokenProvisionsAndOpensLiveTvWithoutCode() throws Exception {
        Harness h=new Harness(); h.respond(start()); h.advance(3); h.respond(activated(false));
        assertEquals(TOKEN,h.credentials.token); assertEquals(1,h.credentials.saves);
        assertEquals("https://nyama.fun/playlist.m3u",new fun.nyama.tv.Config(h.activity).playlistUrl());
        assertTrue(h.activity.isFinishing());
        assertEquals(MainActivity.class.getName(),Shadows.shadowOf(h.activity).getNextStartedActivity().getComponent().getClassName());
        h.advance(100); assertTrue(h.executor.queue.isEmpty());
    }
    @Test public void expiredPairSavesIdentityAndOpensRenewalWithoutConfig() throws Exception {
        new fun.nyama.tv.Config(RuntimeEnvironment.getApplication()).setDeviceMode(fun.nyama.tv.Config.MODE_TV);
        Harness h=new Harness(); h.respond(start()); h.advance(3); h.respond(activated(true));
        assertEquals(TOKEN,h.credentials.token); assertFalse(h.activity.isFinishing());
        assertFalse(ActivationAccess.playbackAllowed(h.activity));
        assertTrue(text(h.activity).contains("Абонаментът Ви е изтекъл"));
        assertEquals("customer",h.activity.getSharedPreferences("nyama_tv_private",0).getString("activation_username",""));
        assertEquals("",new fun.nyama.tv.Config(h.activity).playlistUrl());
        h.advance(44); assertTrue(h.executor.queue.isEmpty());
        h.advance(1); assertEquals(1, h.executor.queue.size()); // One normal config check, no OTP/pair restart.
        h.lifecycle.pause().stop().destroy();
    }
    @Test public void onlyOnePollRunsAndServerDelayIsRespected() throws Exception {
        Harness h=new Harness(); h.respond(start().put("poll_after",8)); h.advance(7); assertTrue(h.executor.queue.isEmpty());
        h.advance(1); assertEquals(1,h.executor.queue.size()); h.advance(15); assertEquals(1,h.executor.queue.size());
        h.respond(waiting("waiting_for_scan").put("poll_after",11)); h.advance(10); assertTrue(h.executor.queue.isEmpty());
        h.advance(1); assertEquals(1,h.executor.queue.size());
    }
    @Test public void fallbackCancelsQueuedPollAndNeverSavesDelayedActivation() throws Exception {
        Harness h=new Harness(); h.respond(start()); h.advance(3);
        // Finish transport but leave its result queued on the main thread.
        h.responses.add(activated(false)); h.executor.runNext();
        button(h.activity,"I am registered already").performClick(); idle();
        assertTrue(text(h.activity).contains("Вашият имейл адрес")); assertEquals(0,h.credentials.saves);
        h.advance(100); assertTrue(h.executor.queue.isEmpty());
    }
    @Test public void pauseCancelsDelayedResultResumeReusesSession() throws Exception {
        Harness h=new Harness(); h.respond(start()); h.advance(3); h.responses.add(activated(false)); h.executor.runNext();
        h.lifecycle.pause(); idle(); assertEquals(0,h.credentials.saves); h.advance(60); assertTrue(h.executor.queue.isEmpty());
        h.lifecycle.resume(); idle(); assertEquals(1,h.executor.queue.size());
        h.respond(waiting("waiting_for_scan")); assertEquals(Arrays.asList("pair-start","pair-status","pair-status"),h.actions);
        assertEquals(QR,qr(h.activity));
    }
    @Test public void staleStartCannotOverwriteFreshSessionAfterResume() throws Exception {
        Harness h=new Harness(); h.responses.add(start()); h.executor.runNext(); h.lifecycle.pause(); idle();
        h.lifecycle.resume(); idle(); h.respond(start().put("pair_id",repeat('e',32))
                .put("qr_url","https://nyama.fun/register_now.php?pair="+repeat('e',32)));
        assertEquals("https://nyama.fun/register_now.php?pair="+repeat('e',32),qr(h.activity));
        assertEquals(0,h.credentials.saves);
    }
    @Test public void localExpiryStopsInFlightPollingAndRefreshUsesNewSecret() throws Exception {
        Harness h=new Harness(); h.respond(start().put("expires_in",5)); h.advance(5);
        assertTrue(text(h.activity).contains("QR кодът изтече.")); assertEquals(0,h.credentials.saves);
        h.advance(3); button(h.activity,"Генерирай нов QR код").performClick();
        // The canceled queued poll occupies one executor slot, but executes no transport.
        h.executor.runNext(); h.respond(start().put("pair_secret",repeat('f',64)));
        assertEquals(Arrays.asList("pair-start","pair-start"),h.actions); assertEquals(QR,qr(h.activity));
    }
    @Test public void networkRetryKeepsSameSessionAndPermanentData() throws Exception {
        Harness h=new Harness(); h.respond(start()); h.advance(3); h.respond(new java.io.IOException());
        assertTrue(text(h.activity).contains("Няма връзка със сървъра")); assertEquals(QR,qr(h.activity));
        h.advance(3); button(h.activity,"Опитайте отново").performClick(); idle();
        h.respond(waiting("waiting_for_scan")); assertEquals(Arrays.asList("pair-start","pair-status","pair-status"),h.actions);
    }
    @Test public void rateLimitBlocksRetriesUntilServerDelay() throws Exception {
        Harness h=new Harness(); h.respond(new ActivationApi.Failure("rate_limited",90));
        Button retry=button(h.activity,"Опитайте отново"); assertFalse(retry.isEnabled()); h.advance(89); assertFalse(retry.isEnabled());
        h.advance(1); assertTrue(retry.isEnabled()); retry.performClick(); assertEquals(1,h.executor.queue.size());
    }
    @Test public void deviceLimitAndSuspensionNeverSaveTokenOrGrantAccess() throws Exception {
        for(String code:new String[]{"device_limit_reached","inactive_account","pairing_invalid","activation_invalid"}) {
            Harness h=new Harness(); h.respond(start()); h.advance(3); h.respond(new ActivationApi.Failure(code,0));
            assertEquals(0,h.credentials.saves); assertNotNull(button(h.activity,"I am registered already"));
            h.advance(10); assertTrue(h.executor.queue.isEmpty()); h.lifecycle.pause().stop().destroy();
        }
    }
    @Test public void destroyedActivityNeverAcceptsPendingActivation() throws Exception {
        Harness h=new Harness(); h.respond(start()); h.advance(3); h.responses.add(activated(false)); h.executor.runNext();
        h.lifecycle.pause().stop().destroy(); idle(); assertEquals(0,h.credentials.saves); assertTrue(h.executor.isShutdown());
    }
    @Test public void configurationChangeKeepsQrAndDoesNotStartAnotherSession() throws Exception {
        Harness h=new Harness(); h.respond(start()); h.activity.onConfigurationChanged(h.activity.getResources().getConfiguration());
        assertEquals(QR,qr(h.activity)); assertEquals(1,h.actions.size()); h.advance(3); assertEquals(1,h.executor.queue.size());
    }
    @Test public void englishWaitingAndErrorUseExistingLanguagePreference() throws Exception {
        new fun.nyama.tv.Config(RuntimeEnvironment.getApplication()).setLanguage("en");
        Harness h=new Harness(); h.respond(start()); assertTrue(text(h.activity).contains("Welcome to Nyama+"));
        h.advance(3); h.respond(waiting("waiting_for_confirmation")); assertTrue(text(h.activity).contains("Confirm your email"));
        h.advance(3); h.respond(new ActivationApi.Failure("pairing_expired",0)); assertTrue(text(h.activity).contains("The QR code expired."));
    }
}
