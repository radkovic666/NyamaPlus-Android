package fun.nyama.tv;
import android.content.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.lang.reflect.Method;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=android.app.Application.class)
public class TvRenewalTest {
    private PairingUiTest.Harness h;
    @Before public void setup() throws Exception {
        Context c=RuntimeEnvironment.getApplication(); ActivationAccess.deny(); ActivationCooldowns.clear();
        c.getSharedPreferences("nyama_activation_mode",0).edit().clear().commit();
        c.getSharedPreferences("nyama_tv_private",0).edit().clear().commit();
        new fun.nyama.tv.Config(c).setDeviceMode(fun.nyama.tv.Config.MODE_TV);
        h=new PairingUiTest.Harness(); h.respond(PairingControllerTest.start());
        h.activity.emailController=h.activity.pairingController;
        h.credentials.token=PairingControllerTest.TOKEN;
        h.credentials.afterSave.run();
    }
    @After public void done(){h.lifecycle.pause().stop().destroy();}
    private void expired(String phase) throws Exception {
        JSONObject body=new JSONObject().put("ok",true).put("access_status","subscription_expired")
                .put("subscription",new JSONObject().put("status","expired").put("phase",phase))
                .put("renewal",new JSONObject().put("subscription_payment_url","https://nyama.fun/subscription_payment.php?token=synthetic-account"));
        Method m=ActivationActivity.class.getDeclaredMethod("apply",ActivationResponse.class);m.setAccessible(true);
        m.invoke(h.activity,new ActivationResponse(body));
    }
    private String text(View v){String s=v instanceof TextView?((TextView)v).getText().toString():"";if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)s+=" "+text(((ViewGroup)v).getChildAt(i));return s;}
    @Test public void trialAndPaidRenewalExplainCorrectStateAndNeverShowTokens() throws Exception {
        expired("trial");String s=text(h.activity.getWindow().getDecorView());assertTrue(s.contains("Пробният Ви период изтече"));assertFalse(s.contains("synthetic-account"));
        expired("paid");assertTrue(text(h.activity.getWindow().getDecorView()).contains("Абонаментът Ви е изтекъл"));
    }
    @Test public void visibleRenewalPollsOnceAndPaymentRestoresAccessWithoutPairing() throws Exception {
        expired("paid");h.advance(44);assertTrue(h.executor.queue.isEmpty());h.advance(1);assertEquals(1,h.executor.queue.size());
        h.advance(90);assertEquals(1,h.executor.queue.size());
        JSONObject active=new JSONObject().put("ok",true).put("access_status","active").put("config",new JSONObject().put("playlist_url","https://nyama.fun/synthetic.m3u"));
        h.respond(active);assertTrue(ActivationAccess.playbackAllowed(h.activity));assertTrue(h.activity.isFinishing());
        assertEquals("config",h.actions.get(h.actions.size()-1));assertEquals(PairingControllerTest.TOKEN,h.credentials.token);
        assertEquals(MainActivity.class.getName(),Shadows.shadowOf(h.activity).getNextStartedActivity().getComponent().getClassName());
    }
    @Test public void pausedScreenDoesNotPollAndResumingSchedulesOneCheck() throws Exception {
        expired("paid");h.lifecycle.pause();h.advance(120);assertTrue(h.executor.queue.isEmpty());
        h.lifecycle.resume();h.advance(45);assertEquals(1,h.executor.queue.size());
    }
    @Test public void networkFailurePreservesRenewalAndPermanentCredential() throws Exception {
        expired("trial");h.advance(45);h.respond(new java.io.IOException("synthetic"));
        String s=text(h.activity.getWindow().getDecorView());assertTrue(s.contains("Пробният Ви период изтече"));
        assertEquals(PairingControllerTest.TOKEN,h.credentials.token);assertFalse(ActivationAccess.playbackAllowed(h.activity));
        h.advance(45);assertEquals(1,h.executor.queue.size());
    }
    @Test public void disabledAccountShowsAccountErrorInsteadOfPayment() throws Exception {
        expired("paid");h.advance(45);h.respond(new ActivationApi.Failure("inactive_account",0));
        String displayed=text(h.activity.getWindow().getDecorView());
        assertFalse(displayed.contains("Проверка за плащане"));
        assertTrue(displayed.contains("Достъп до профила"));
        assertEquals(PairingControllerTest.TOKEN,h.credentials.token);
        assertFalse(ActivationAccess.playbackAllowed(h.activity));
    }

    @Test public void contradictoryVipExpiryNeverProducesPaymentOrGrantsAccess() throws Exception {
        JSONObject body=new JSONObject().put("ok",true).put("access_status","subscription_expired")
                .put("subscription",new JSONObject().put("vip",true))
                .put("renewal",new JSONObject().put("subscription_payment_url","https://nyama.fun/subscription_payment.php?token=synthetic"));
        try { new ActivationResponse(body); fail(); }
        catch(ActivationApi.Failure expected) { assertEquals("server_unavailable",expected.code); }
    }

    @Test public void invalidActivationRoutesToEnrollmentInsteadOfRenewal() throws Exception {
        expired("paid");h.advance(45);h.respond(new ActivationApi.Failure("activation_invalid",0));
        assertFalse(text(h.activity.getWindow().getDecorView()).contains("Проверка за плащане"));
        assertEquals("",h.credentials.token);assertFalse(ActivationAccess.playbackAllowed(h.activity));
    }
}
