package fun.nyama.tv;

import android.app.Application;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.ActivityInfo;
import android.net.ConnectivityManager;
import android.os.Looper;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
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
import org.robolectric.annotation.LooperMode;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Native lifecycle with a synthetic player and counted local M3U downloads; no live HTTP/media. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class, qualifiers="w360dp-h800dp-port-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class LiveSessionTest {
    private MainActivity live;
    private ActivityController<MainActivity> controller;
    private fun.nyama.tv.Config config;
    private final Channel channel = new Channel(1,"One","one","","","News","https://example.invalid/old",null,null);
    private int downloads, prepare, starts, pause;
    private boolean ready = true;
    private final PlaylistRepository.Downloader original = PlaylistRepository.downloader;

    private static Field field(Class<?> type,String name) throws Exception {
        Field f=type.getDeclaredField(name);f.setAccessible(true);return f;
    }
    private Object get(String name) throws Exception { return field(MainActivity.class,name).get(live); }
    private void set(String name,Object value) throws Exception { field(MainActivity.class,name).set(live,value); }
    private Object call(String name,Class<?>[] types,Object... args) throws Exception {
        Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(live,args);
    }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private void layout(int width,int height) {
        View screen=((ViewGroup)live.findViewById(android.R.id.content)).getChildAt(0);
        screen.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        screen.layout(0,0,width,height);
    }
    private void rotate(Configuration configuration) {
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();metrics.setTo(live.getResources().getDisplayMetrics());
        metrics.widthPixels=Math.round(configuration.screenWidthDp*metrics.density);
        metrics.heightPixels=Math.round(configuration.screenHeightDp*metrics.density);
        // This UI-only fixture skips onCreate, so Android's mCurrentConfig is uninitialized.
        // Supply the actual rotation bits while still letting the controller check the manifest.
        controller.configurationChange(configuration,metrics,ActivityInfo.CONFIG_ORIENTATION|ActivityInfo.CONFIG_SCREEN_SIZE);
    }
    @Before public void before() throws Exception {
        Application app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("nyama_tv_private",0).edit().clear().commit();
        app.getSharedPreferences("nyama_activation_mode",0).edit().clear().commit();
        ActivationAccess.deny();
        controller=Robolectric.buildActivity(MainActivity.class);live=controller.get();
        config=new fun.nyama.tv.Config(live);config.saveUrls("https://example.invalid/list","https://example.invalid/epg");config.setPin("1234");
        config.setDeviceMode(fun.nyama.tv.Config.MODE_PHONE);
        set("config",config);set("compactUi",true);set("resumed",true);set("accent",UiTheme.accent(live));
        set("uiSignatureAtCreate",config.uiSignature());set("loadedPlaylistUrl",config.playlistUrl());
        set("channels",Collections.singletonList(channel));set("currentIndex",0);set("hasUsablePlaylist",true);
        field(MainActivity.class,"activeInstance").set(null,new WeakReference<>(live));
        field(MainActivity.class,"onDemandPlaybackActive").set(null,false);
        call("buildUi",new Class<?>[]{});controller.visible();
        ExoPlayer player=(ExoPlayer)Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(),new Class<?>[]{ExoPlayer.class},(proxy,method,args)->{
            switch(method.getName()) {
                case "getMediaItemCount": return 1;
                case "getPlaybackState": return Player.STATE_READY;
                case "setPlayWhenReady": ready=(boolean)args[0];if(ready)starts++;return null;
                case "pause": pause++;ready=false;return null;
                case "prepare": prepare++;return null;
                case "setMediaItem": case "release": return null;
                default: throw new AssertionError("Unexpected media operation: "+method.getName());
            }
        });
        set("player",player);set("httpFactory",new DefaultHttpDataSource.Factory());
        ((ExecutorService)get("io")).shutdownNow();
        set("io",new AbstractExecutorService() {
            public void shutdown() { } public List<Runnable> shutdownNow() { return Collections.emptyList(); }
            public boolean isShutdown() { return false; } public boolean isTerminated() { return false; }
            public boolean awaitTermination(long t,TimeUnit unit) { return true; }
            public void execute(Runnable task) { task.run(); }
        });
        PlaylistRepository.downloader=(context,url,target)->{
            downloads++;
            Files.write(target.toPath(),("#EXTM3U\n#EXTINF:-1 tvg-id=\"one\" group-title=\"News\",One\nhttps://example.invalid/old\n").getBytes(StandardCharsets.UTF_8));
        };
        idle();layout(360,800);
    }
    @After public void after() throws Exception {
        field(MainActivity.class,"onDemandPlaybackActive").set(null,false);
        field(MainActivity.class,"activeInstance").set(null,new WeakReference<MainActivity>(null));
        call("onDestroy",new Class<?>[]{});
        PlaylistRepository.downloader=original;
    }
    @Test public void repeatedRotationKeepsSessionAndRefreshDeadlineWithoutPlaylistRequests() throws Exception {
        ActivityInfo info=live.getPackageManager().getActivityInfo(live.getComponentName(),0);
        int handled=ActivityInfo.CONFIG_ORIENTATION|ActivityInfo.CONFIG_SCREEN_SIZE|ActivityInfo.CONFIG_KEYBOARD_HIDDEN;
        assertEquals(handled,info.configChanges&handled);
        Object player=get("player"), list=get("channels"), surface=((PlayerView)get("playerView")).getVideoSurfaceView();
        Object network=get("networkCallback"), clock=get("cornerClock"), nav=get("mobileNavigation");
        call("startForegroundRefreshLoop",new Class<?>[]{});ScheduledFuture<?> deadline=(ScheduledFuture<?>)get("refreshTask");
        long remaining=deadline.getDelay(TimeUnit.MILLISECONDS);
        for(int i=0;i<6;i++) {
            Configuration c=new Configuration(live.getResources().getConfiguration());
            c.orientation=i%2==0?Configuration.ORIENTATION_LANDSCAPE:Configuration.ORIENTATION_PORTRAIT;
            c.screenWidthDp=i%2==0?800:360;c.screenHeightDp=i%2==0?360:800;
            rotate(c);idle();layout(c.screenWidthDp,c.screenHeightDp);
            assertSame(live,controller.get());assertSame(player,get("player"));assertSame(surface,((PlayerView)get("playerView")).getVideoSurfaceView());
            assertSame(list,get("channels"));assertSame(clock,get("cornerClock"));assertSame(nav,get("mobileNavigation"));assertSame(network,get("networkCallback"));
            assertSame(deadline,get("refreshTask"));assertEquals(0,downloads);assertEquals(0,prepare);assertEquals(0,starts);
            assertEquals(0,get("currentIndex"));assertEquals(View.VISIBLE,((View)clock).getVisibility());
        }
        assertTrue(deadline.getDelay(TimeUnit.MILLISECONDS)<=remaining);
    }
    @Test public void rotationRetainsBrowserCategoryAndProgrammeSelectionWithResponsiveGeometry() throws Exception {
        List<Channel> channels=Arrays.asList(channel,new Channel(2,"Two","two","","","Sport","https://example.invalid/2",null,null));
        set("channels",channels);
        call("showChannelBrowser",new Class<?>[]{boolean.class},false);
        ChannelBrowserOverlay browser=(ChannelBrowserOverlay)get("browser");
        field(ChannelBrowserOverlay.class,"selectedCategory").set(browser,2);
        Method apply=ChannelBrowserOverlay.class.getDeclaredMethod("applyCategory",boolean.class);apply.setAccessible(true);apply.invoke(browser,false);
        EpgGuideOverlay guide=(EpgGuideOverlay)get("epgGuide");guide.show(channel);
        @SuppressWarnings("unchecked") List<EpgEvent> events=(List<EpgEvent>)field(EpgGuideOverlay.class,"events").get(guide);
        events.add(new EpgEvent(1,2,"One"));events.add(new EpgEvent(3,4,"Two"));field(EpgGuideOverlay.class,"selected").set(guide,1);
        Configuration c=new Configuration(live.getResources().getConfiguration());c.orientation=Configuration.ORIENTATION_LANDSCAPE;c.screenWidthDp=800;c.screenHeightDp=360;
        rotate(c);idle();layout(800,360);
        browser=(ChannelBrowserOverlay)get("browser");guide=(EpgGuideOverlay)get("epgGuide");
        assertTrue(browser.isOpen());assertEquals(2,field(ChannelBrowserOverlay.class,"selectedCategory").get(browser));
        assertEquals(false,field(ChannelBrowserOverlay.class,"portraitPhone").get(browser));
        assertTrue(guide.isOpen());assertSame(channel,field(EpgGuideOverlay.class,"channel").get(guide));
        assertEquals(1,field(EpgGuideOverlay.class,"selected").get(guide));assertEquals(0,downloads);
        assertEquals(View.VISIBLE,((View)get("mobileNavigation")).getVisibility());
    }
    @Test public void clockAndDotUseOverlayAboveExistingSurfaceOnPhoneAndTv() throws Exception {
        for(boolean phone:new boolean[]{true,false}) {
            config.setDeviceMode(phone?"phone":"tv");
            call("buildUi",new Class<?>[]{});idle();layout(800,360);
            PlayerView player=(PlayerView)get("playerView");assertTrue(player.getVideoSurfaceView() instanceof SurfaceView);
            assertEquals(-2,(int)org.robolectric.util.ReflectionHelpers.getField(player.getVideoSurfaceView(),"mSubLayer"));
            FrameLayout overlays=(FrameLayout)get("playbackOverlays");
            assertSame(overlays,((View)get("cornerClock")).getParent());assertSame(overlays,((View)get("refreshSuccessDot")).getParent());
            assertTrue(overlays.getZ()>player.getZ());
            for(android.view.SurfaceHolder.Callback callback:Shadows.shadowOf((SurfaceView)player.getVideoSurfaceView()).getFakeSurfaceHolder().getCallbacks())
                callback.surfaceCreated(((SurfaceView)player.getVideoSurfaceView()).getHolder());
            idle();assertEquals(View.VISIBLE,((View)get("cornerClock")).getVisibility());
        }
    }
    @Test public void routineRefreshShowsOnlyReusableDotAndFailureKeepsLastGoodPlaylist() throws Exception {
        call("refreshPlaylistNow",new Class<?>[]{boolean.class},true);idle();layout(360,800);
        View dot=(View)get("refreshSuccessDot");assertEquals(1,downloads);assertEquals(View.VISIBLE,dot.getVisibility());assertEquals(10,dot.getWidth());
        assertEquals(View.GONE,((View)get("status")).getVisibility());assertEquals(View.GONE,((View)get("hud")).getVisibility());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2300));assertEquals(1f,dot.getAlpha(),.01f);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800));assertEquals(View.GONE,dot.getVisibility());assertEquals(0f,dot.getAlpha(),.01f);
        call("refreshPlaylistNow",new Class<?>[]{boolean.class},true);idle();assertSame(dot,get("refreshSuccessDot"));
        call("onPause",new Class<?>[]{});assertEquals(View.GONE,dot.getVisibility());set("resumed",true);
        PlaylistRepository.downloader=(c,u,f)->{downloads++;throw new java.io.IOException("Synthetic offline");};
        call("refreshPlaylistNow",new Class<?>[]{boolean.class},true);idle();
        assertEquals(View.GONE,dot.getVisibility());assertEquals(live.getString(R.string.playlist_refresh_failed),((TextView)get("status")).getText().toString());
        assertEquals(1,((List<?>)get("channels")).size());assertEquals(0,starts);
    }
    @Test public void changedSourceDuringScheduledRefreshDoesNotProduceSuccessHudOrText() throws Exception {
        set("channels",Collections.singletonList(new Channel(1,"One","one","","","News","https://example.invalid/different",null,null)));
        call("refreshPlaylistNow",new Class<?>[]{boolean.class},true);idle();
        assertEquals(1,starts);assertEquals(View.GONE,((View)get("hud")).getVisibility());assertEquals(View.GONE,((View)get("status")).getVisibility());
        assertEquals(View.VISIBLE,((View)get("refreshSuccessDot")).getVisibility());
    }
    @Test public void dotAvoidsTopRightClockAndOtherRefreshFeedbackIsPreserved() throws Exception {
        config.setClockPosition(fun.nyama.tv.Config.CLOCK_TOP_RIGHT);call("buildUi",new Class<?>[]{});idle();layout(800,360);
        call("refreshPlaylistNow",new Class<?>[]{boolean.class},true);idle();layout(800,360);
        View clock=(View)get("cornerClock"),dot=(View)get("refreshSuccessDot");
        assertTrue(dot.getTop()>=clock.getBottom()+8);assertTrue(dot.getRight()<=800-18);
        call("hideRefreshSuccess",new Class<?>[]{});
        call("refreshPlaylistNow",new Class<?>[]{});idle();
        assertEquals(View.GONE,dot.getVisibility());assertEquals(View.VISIBLE,((View)get("status")).getVisibility());
    }
    @Test public void vodGateBlocksResumeChannelRetryAndRefreshedPlaylistUntilRelease() throws Exception {
        long session=MainActivity.beginOnDemandPlayback();assertFalse(ready);assertEquals(1,pause);
        call("onResume",new Class<?>[]{});assertEquals(0,downloads);assertEquals(0,starts);
        assertFalse((boolean)call("resumeLoadedLiveChannel",new Class<?>[]{}));
        call("playChannel",new Class<?>[]{int.class,boolean.class},0,false);assertEquals(0,starts);
        call("refreshPlaylistNow",new Class<?>[]{boolean.class},true);idle();
        assertEquals(1,downloads);assertEquals(0,starts);assertEquals(View.GONE,((View)get("refreshSuccessDot")).getVisibility());
        assertEquals(View.GONE,((View)get("cornerClock")).getVisibility());
        MainActivity.resumePlaybackAfterOnDemand(session);assertTrue(ready);assertEquals(1,starts);
        MainActivity.resumePlaybackAfterOnDemand(session);assertEquals(1,starts);
    }
    @Test public void networkAndReturnedPlaylistCanUpdateDataWithoutResumingVodBackground() throws Exception {
        call("registerNetworkCallback",new Class<?>[]{});set("hasUsablePlaylist",false);
        long session=MainActivity.beginOnDemandPlayback();
        ((ConnectivityManager.NetworkCallback)get("networkCallback")).onAvailable(null);idle();
        assertEquals(1,downloads);assertEquals(0,starts);
        Channel changed=new Channel(1,"One","one","","","News","https://example.invalid/new",null,null);
        MainActivity.applyReturnedPlaylist(Collections.singletonList(changed));idle();assertEquals(0,starts);
        assertEquals(true,get("liveSourceRefreshPending"));
        MainActivity.resumePlaybackAfterOnDemand(session);assertEquals(1,starts);assertEquals(1,prepare);
    }
    @Test public void movieAndExactEpisodeKeepLivePausedThroughProviderFailureUntilPlayerDestroyed() throws Exception {
        for (boolean phone : new boolean[]{false,true}) {
            config.setDeviceMode(phone ? "phone" : "tv");
            for (String type : new String[]{"movies","series"}) {
                int previousStarts=starts;
                Intent intent=new Intent(live,MoviePlayerActivity.class).putExtra("id","tt0944947").putExtra("type",type);
                if ("series".equals(type)) intent.putExtra("series_imdb_id","tt0944947").putExtra("season",1).putExtra("episode",1);
                ActivityController<MoviePlayerActivity> vod=Robolectric.buildActivity(MoviePlayerActivity.class,intent).create().start().resume().visible();
                MoviePlayerActivity movie=vod.get();
                try {
                    WebView document=(WebView)field(MoviePlayerActivity.class,"webView").get(movie);
                    assertNotNull(document);assertFalse(ready);
                    assertEquals(true,field(MainActivity.class,"onDemandPlaybackActive").get(null));
                    if (!phone) {
                        Method failure=MoviePlayerActivity.class.getDeclaredMethod("showPlaybackError",String.class);failure.setAccessible(true);
                        failure.invoke(movie,"Synthetic provider failure");
                    }
                    call("playChannel",new Class<?>[]{int.class,boolean.class},0,false);
                    assertEquals(previousStarts,starts);assertFalse(ready);
                    // A provider failure or temporary pause does not leave the playback screen.
                    vod.pause();assertFalse(ready);
                    field(MoviePlayerActivity.class,"playlistReturnRequested").set(movie,true); // Return-refresh is covered separately.
                    movie.finish();vod.stop().destroy();
                    assertTrue(Shadows.shadowOf(document).wasDestroyCalled());assertTrue(ready);
                    assertEquals(previousStarts+1,starts);
                } finally {
                    if (!movie.isDestroyed()) { movie.finish();vod.pause().stop().destroy(); }
                }
            }
        }
    }
    @Test public void staleVodExitCannotReleaseNewSessionAndRevokedAccountCannotResume() throws Exception {
        long first=MainActivity.beginOnDemandPlayback(),second=MainActivity.beginOnDemandPlayback();
        MainActivity.resumePlaybackAfterOnDemand(first);assertFalse(ready);assertEquals(0,starts);
        RuntimeEnvironment.getApplication().getSharedPreferences("nyama_activation_mode",0).edit().putBoolean("managed",true).commit();
        MainActivity.resumePlaybackAfterOnDemand(second);assertEquals(0,starts);assertFalse(ready);
    }
}
