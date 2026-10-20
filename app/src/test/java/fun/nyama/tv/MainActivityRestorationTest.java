package fun.nyama.tv;

import android.app.Application;

import androidx.annotation.OptIn;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.*;

/** Exercises loaded-stream restoration without constructing a player or issuing HTTP requests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@OptIn(markerClass = UnstableApi.class)
public class MainActivityRestorationTest {
    private static final String PLAYLIST = "https://legacy.example/live.m3u";
    private static final String EPG = "https://legacy.example/epg.xml";
    private MainActivity activity;
    private fun.nyama.tv.Config config;
    private PlayerCalls calls;

    @Before public void before() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences("nyama_tv_private", 0).edit().clear().commit();
        application.getSharedPreferences("nyama_activation_mode", 0).edit().clear().commit();
        ActivationAccess.deny();
        // Deliberately skip onCreate: no real ExoPlayer, network, playlist or EPG initialization.
        activity = Robolectric.buildActivity(MainActivity.class).get();
        config = new fun.nyama.tv.Config(activity);
        config.saveUrls(PLAYLIST, EPG);
        config.setPin("123456");
        config.setLastChannelKey("id:last-live-channel");
        config.toggleFavorite("id:saved-favorite");
        set("config", config);
        set("loadedPlaylistUrl", PLAYLIST);
        calls = new PlayerCalls();
        set("player", calls.proxy());
    }

    @After public void after() throws Exception {
        if (activity != null) {
            ((ExecutorService) field("io").get(activity)).shutdownNow();
            ((ExecutorService) field("scheduler").get(activity)).shutdownNow();
        }
        ActivationAccess.deny();
    }

    private static Field field(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void set(String name, Object value) throws Exception { field(name).set(activity, value); }

    private boolean resume() throws Exception {
        Method method = MainActivity.class.getDeclaredMethod("resumeLoadedLiveChannel");
        method.setAccessible(true);
        return (Boolean) method.invoke(activity);
    }

    @Test public void readyStreamResumesWithoutPreparingOrChangingUserPreferences() throws Exception {
        assertTrue(resume());
        assertEquals(0, calls.prepare);
        assertEquals(1, calls.playWhenReady);
        assertEquals("id:last-live-channel", config.lastChannelKey());
        assertTrue(config.isFavorite("id:saved-favorite"));
        assertEquals(PLAYLIST, config.playlistUrl());
        assertEquals(EPG, config.epgUrl());
        assertTrue(config.verifyPin("123456"));
    }

    @Test public void idleLoadedStreamPreparesOnceBeforeResuming() throws Exception {
        calls.state = Player.STATE_IDLE;
        assertTrue(resume());
        assertEquals(1, calls.prepare);
        assertEquals(1, calls.playWhenReady);
        assertTrue(calls.preparedBeforePlay);
    }

    @Test public void bufferingLoadedStreamResumesWithoutRestartingPreparation() throws Exception {
        calls.state = Player.STATE_BUFFERING;
        assertTrue(resume());
        assertEquals(0, calls.prepare);
        assertEquals(1, calls.playWhenReady);
    }

    @Test public void emptyPlayerRequestsExistingChannelRestorationFallback() throws Exception {
        calls.mediaCount = 0;
        assertFalse(resume());
        assertEquals(0, calls.prepare);
        assertEquals(0, calls.playWhenReady);
    }

    @Test public void destroyedPlayerRequestsExistingChannelRestorationFallback() throws Exception {
        set("player", null);
        assertFalse(resume());
        assertEquals(0, calls.prepare);
        assertEquals(0, calls.playWhenReady);
    }

    @Test public void changedPlaylistCannotResumeStaleLoadedMedia() throws Exception {
        config.saveUrls("https://legacy.example/replaced.m3u", EPG);
        assertFalse(resume());
        assertEquals(0, calls.prepare);
        assertEquals(0, calls.playWhenReady);
        assertEquals("id:last-live-channel", config.lastChannelKey());
        assertTrue(config.isFavorite("id:saved-favorite"));
    }

    @Test public void managedAccountWithoutAuthorizationCannotRestartLiveAudio() throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("nyama_activation_mode", 0)
                .edit().putBoolean("managed", true).commit();
        assertFalse(resume());
        assertEquals(0, calls.prepare);
        assertEquals(0, calls.playWhenReady);
        assertEquals(0, calls.mediaCountReads);
    }

    private static final class PlayerCalls {
        int mediaCount = 1;
        int state = Player.STATE_READY;
        int mediaCountReads;
        int prepare;
        int playWhenReady;
        boolean preparedBeforePlay;

        ExoPlayer proxy() {
            return (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(),
                    new Class<?>[] { ExoPlayer.class }, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getMediaItemCount": mediaCountReads++; return mediaCount;
                            case "getPlaybackState": return state;
                            case "prepare": prepare++; return null;
                            case "setPlayWhenReady":
                                assertEquals(Boolean.TRUE, args[0]);
                                playWhenReady++;
                                preparedBeforePlay = prepare == 1;
                                return null;
                            case "toString": return "Synthetic loaded live player";
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == args[0];
                            default: throw new AssertionError("Unexpected player operation: " + method.getName());
                        }
                    });
        }
    }
}
