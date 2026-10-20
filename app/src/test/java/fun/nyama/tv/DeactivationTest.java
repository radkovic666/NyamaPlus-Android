package fun.nyama.tv;
import android.app.Application;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.util.ArrayDeque;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class DeactivationTest {
    private static final String ID="a".repeat(64),TOKEN="b".repeat(64);
    private Context context;
    private static final class Memory implements ActivationController.Credentials {
        String token=TOKEN;int writes;
        public String deviceId(){return ID;}public String token(){return token;}
        public void saveToken(String id,String value){writes++;token=value;}
        public void clearToken(String expected){writes++;token="";}
    }
    @Before public void before() {
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("nyama_activation_mode",0).edit().clear().commit();
        context.getSharedPreferences("nyama_tv_private",0).edit().clear().commit();
        ActivationAccess.deny();PlaylistRepository.finishDeactivationRequest();
    }
    private JSONObject response() throws Exception {
        return new JSONObject().put("ok",true).put("access_status","active")
                .put("config",new JSONObject().put("playlist_url","https://example.invalid/list"));
    }
    private void authorize(boolean supported) throws Exception {
        context.getSharedPreferences("nyama_activation_mode",0).edit().putBoolean("managed",true).commit();
        ActivationAccess.accept(context,new ActivationResponse(response().put("capabilities",new JSONObject().put("revoke_current_device",supported))));
    }
    @Test public void revokeSendsOnlyExistingIdAndBearerAndRequiresBothServerConfirmations() throws Exception {
        Memory memory=new Memory();
        ActivationController controller=new ActivationController((action,body,token)->{
            assertEquals("revoke",action);assertEquals(1,body.length());assertEquals(ID,body.getString("device_id"));assertEquals(TOKEN,token);
            return new JSONObject().put("revoked",true).put("playlist_binding_released",true);
        },memory);
        ActivationController.Revocation result=controller.revokeCurrentDevice();
        assertEquals(ID,result.deviceId);assertEquals(TOKEN,result.token);assertEquals(TOKEN,memory.token);assertEquals(0,memory.writes);
        for(JSONObject incomplete:new JSONObject[]{new JSONObject().put("revoked",true),new JSONObject().put("revoked",true).put("playlist_binding_released","true")}) {
            try{new ActivationController((a,b,t)->incomplete,memory).revokeCurrentDevice();fail();}
            catch(ActivationApi.Failure failure){assertEquals("deactivation_unavailable",failure.code);}
            assertEquals(0,memory.writes);
        }
    }
    @Test public void networkAndServerErrorsNeverClearCredentialsButAlreadyRevokedCanFinishLocally() throws Exception {
        Memory memory=new Memory();
        for(String code:new String[]{"server_unavailable","device_link_conflict","device_link_required","inactive_account"}) {
            try{new ActivationController((a,b,t)->{throw new ActivationApi.Failure(code,0);},memory).revokeCurrentDevice();fail();}
            catch(ActivationApi.Failure failure){assertEquals(code,failure.code);}
        }
        ActivationController.Revocation stale=new ActivationController((a,b,t)->{throw new ActivationApi.Failure("activation_invalid",0);},memory).revokeCurrentDevice();
        assertEquals(TOKEN,stale.token);assertEquals(TOKEN,memory.token);assertEquals(0,memory.writes);
    }
    @Test public void capabilitiesFailClosedAndLegacyHasNoDeactivateSection() throws Exception {
        assertFalse(new ActivationResponse(response()).revokeCurrentDeviceSupported);
        assertFalse(new ActivationResponse(response().put("capabilities",new JSONObject().put("revoke_current_device","true"))).revokeCurrentDeviceSupported);
        org.robolectric.android.controller.ActivityController<SettingsActivity> legacy=Robolectric.buildActivity(SettingsActivity.class).create();
        assertNull(legacy.get().getWindow().getDecorView().findViewWithTag("deactivate-device"));legacy.destroy();
        authorize(false);
        org.robolectric.android.controller.ActivityController<SettingsActivity> managed=Robolectric.buildActivity(SettingsActivity.class).create();
        Button button=managed.get().getWindow().getDecorView().findViewWithTag("deactivate-device");assertNotNull(button);assertFalse(button.isEnabled());managed.destroy();
    }
    @Test public void managedSectionIsLastAndCancelDoesNothing() throws Exception {
        authorize(true);
        org.robolectric.android.controller.ActivityController<SettingsActivity> lifecycle=Robolectric.buildActivity(SettingsActivity.class).create();
        View root=lifecycle.get().getWindow().getDecorView();View card=root.findViewWithTag("deactivation-section");
        ViewGroup parent=(ViewGroup)card.getParent();assertEquals(parent.getChildCount()-1,parent.indexOfChild(card));
        Button button=root.findViewWithTag("deactivate-device");assertTrue(button.isEnabled());button.performClick();
        AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertTrue(dialog.isShowing());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertFalse(DeviceDeactivation.instance().busy());assertTrue(ActivationAccess.playbackAllowed(context));
        lifecycle.destroy();
    }
    @Test public void processRequestSurvivesObserverReplacementAndRejectsRepeatedPresses() throws Exception {
        DeviceDeactivation operation=new DeviceDeactivation();ArrayDeque<Runnable> jobs=new ArrayDeque<>();operation.io=jobs::add;
        int[] requests={0},commits={0},success={0},changes={0};
        DeviceDeactivation.Listener old=()->changes[0]++,replacement=()->changes[0]+=10;
        operation.observe(old);
        DeviceDeactivation.Request request=()->{requests[0]++;return new ActivationController.Revocation(ID,TOKEN);};
        operation.start(request,r->{commits[0]++;return true;},()->success[0]++);
        operation.forget(old);operation.observe(replacement);
        operation.start(request,r->{fail();return false;},()->fail());assertEquals(1,jobs.size());assertTrue(operation.busy());
        jobs.remove().run();assertEquals(1,requests[0]);assertEquals(1,commits[0]);assertEquals(0,success[0]);
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(operation.busy());assertEquals(1,success[0]);assertEquals("",operation.error());assertTrue(changes[0]>=20);
    }
    @Test public void failureAndStaleActivationNeverRunCleanupOrSuccess() throws Exception {
        authorize(true);
        DeviceDeactivation operation=new DeviceDeactivation();operation.io=Runnable::run;
        operation.start(()->{throw new java.io.IOException("synthetic");},r->{fail();return false;},()->fail());
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(operation.busy());assertEquals("server_unavailable",operation.error());assertTrue(ActivationAccess.playbackAllowed(context));
        operation.start(()->new ActivationController.Revocation(ID,TOKEN),r->false,()->fail());
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(operation.error().isEmpty());assertTrue(ActivationAccess.playbackAllowed(context));
    }
    @Test public void onlyManagedProvisionedCredentialsAreClearedAndPreferencesSurvive() throws Exception {
        fun.nyama.tv.Config config=new fun.nyama.tv.Config(context);config.saveUrls("https://example.invalid/secret-list","https://example.invalid/epg");
        config.setPin("1234");config.setLanguage("en");config.setClockPosition("top_right");config.setMovieSeriesPlayer(fun.nyama.tv.Config.PLAYER_1);config.toggleFavorite("channel");config.setLastChannelKey("channel");
        config.clearProvisionedActivation();assertFalse(config.playlistUrl().isEmpty());
        context.getSharedPreferences("nyama_activation_mode",0).edit().putBoolean("managed",true).commit();
        java.nio.file.Files.write(PlaylistRepository.cacheFile(context).toPath(),"cached credential".getBytes());
        config.clearProvisionedActivation();assertEquals("",config.playlistUrl());assertFalse(PlaylistRepository.cacheFile(context).exists());
        assertTrue(config.isFavorite("channel"));assertEquals("channel",config.lastChannelKey());assertEquals("en",config.language());assertEquals("top_right",config.clockPosition());assertEquals(fun.nyama.tv.Config.PLAYER_1,config.movieSeriesPlayer());assertTrue(config.verifyPin("1234"));assertTrue(ActivationStore.managed(context));
    }
    @Test public void deactivationWaitsForPlaylistIoAndDoesNotPermitAnotherBindingRequest() throws Exception {
        fun.nyama.tv.Config config=new fun.nyama.tv.Config(context);config.saveUrls("https://example.invalid/list","https://example.invalid/epg");
        PlaylistRepository.Downloader old=PlaylistRepository.downloader;
        java.util.concurrent.CountDownLatch downloading=new java.util.concurrent.CountDownLatch(1),complete=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        PlaylistRepository.downloader=(c,u,f)->{downloading.countDown();if(!complete.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new java.io.IOException();java.nio.file.Files.write(f.toPath(),"#EXTM3U\n#EXTINF:-1,One\nhttps://example.invalid/1\n".getBytes());};
        try {
            java.util.concurrent.Future<?> download=executor.submit(()->{try{PlaylistRepository.refresh(context);}catch(Exception e){throw new RuntimeException(e);}});
            assertTrue(downloading.await(5,java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<?> waiting=executor.submit(()->{try{PlaylistRepository.awaitDeactivationQuiescence();}catch(Exception e){throw new RuntimeException(e);}});
            // Wait until the repository has acquired the pause, without a blocking sleep.
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while(System.nanoTime()<until){synchronized(PlaylistRepository.class){java.lang.reflect.Field flag=PlaylistRepository.class.getDeclaredField("deactivationPending");flag.setAccessible(true);if(flag.getBoolean(null))break;}Thread.yield();}
            try{PlaylistRepository.refresh(context);fail();}catch(java.io.IOException expected){}
            assertFalse(waiting.isDone());complete.countDown();download.get(5,java.util.concurrent.TimeUnit.SECONDS);waiting.get(5,java.util.concurrent.TimeUnit.SECONDS);
        } finally {complete.countDown();executor.shutdownNow();PlaylistRepository.finishDeactivationRequest();PlaylistRepository.downloader=old;}
    }
}
