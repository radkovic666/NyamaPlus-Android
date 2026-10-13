package fun.nyama.tv;

import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;

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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAudioManager;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Native remote/lifecycle checks with synthetic WebView responses; no live provider or hardware. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, shadows = MoviePlayerControlsTest.VolumePolicyShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class MoviePlayerControlsTest {
    private final List<ActivityController<MoviePlayerActivity>> activities = new ArrayList<>();

    /** Supply the real device's fixed-volume policy while retaining Robolectric's audio behavior. */
    @Implements(AudioManager.class)
    public static class VolumePolicyShadow extends ShadowAudioManager {
        static boolean fixed;
        int lastStream;
        int lastDirection;
        int lastFlags;
        int adjustments;
        @Implementation protected boolean isVolumeFixed() { return fixed; }
        @Implementation @Override protected void adjustStreamVolume(int stream, int direction, int flags) {
            lastStream = stream;
            lastDirection = direction;
            lastFlags = flags;
            adjustments++;
            // Robolectric 4.16.1 handles MUTE/UNMUTE but omits Android's TOGGLE_MUTE (101).
            // Supply that documented platform operation; retain its standard volume shadow.
            if (direction == AudioManager.ADJUST_TOGGLE_MUTE) setIsStreamMute(stream, !isStreamMute(stream));
            else super.adjustStreamVolume(stream, direction, flags);
        }
    }

    @Before public void before() {
        Context context = RuntimeEnvironment.getApplication();
        VolumePolicyShadow.fixed = false;
        ActivationAccess.deny();
        context.getSharedPreferences("nyama_activation_mode", 0).edit().clear().commit();
        context.getSharedPreferences("nyama_tv_private", 0).edit().clear().commit();
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(context);
        config.saveUrls("https://example.invalid/playlist.m3u", "https://example.invalid/epg.xml");
        config.setPin("1234");
        config.setLanguage(fun.nyama.tv.Config.LANG_EN);
    }

    @After public void after() {
        for (ActivityController<MoviePlayerActivity> lifecycle : activities) lifecycle.pause().stop().destroy();
        activities.clear();
    }

    private ActivityController<MoviePlayerActivity> open(boolean phone, String type) {
        Context context = RuntimeEnvironment.getApplication();
        new fun.nyama.tv.Config(context).setDeviceMode(phone ? fun.nyama.tv.Config.MODE_PHONE : fun.nyama.tv.Config.MODE_TV);
        Intent intent = new Intent(context, MoviePlayerActivity.class)
                .putExtra("id", "tt0111161").putExtra("type", type).putExtra("title", "Synthetic title");
        ActivityController<MoviePlayerActivity> lifecycle = Robolectric.buildActivity(MoviePlayerActivity.class, intent)
                .create().start().resume().visible();
        activities.add(lifecycle);
        MoviePlayerActivity activity = lifecycle.get();
        View root = activity.findViewById(android.R.id.content);
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1920, 1080);
        idle();
        // Physical remote presses occur after initial creation, outside the duplicate BACK guard.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertFalse("Configured legacy playback must remain usable", activity.isFinishing());
        return lifecycle;
    }

    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    private static Field field(String name) throws Exception {
        Field field = MoviePlayerActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static boolean flag(MoviePlayerActivity activity, String name) throws Exception {
        return field(name).getBoolean(activity);
    }

    private static WebView web(MoviePlayerActivity activity) throws Exception {
        return (WebView) field("webView").get(activity);
    }

    private static LinearLayout controls(MoviePlayerActivity activity) throws Exception {
        return (LinearLayout) field("remoteControls").get(activity);
    }

    @SuppressWarnings("unchecked") private static List<Button> buttons(MoviePlayerActivity activity) throws Exception {
        return (List<Button>) field("controlButtons").get(activity);
    }

    private static Button button(MoviePlayerActivity activity, String label) throws Exception {
        for (Button button : buttons(activity)) if (label.equals(button.getText().toString())) return button;
        throw new AssertionError("Missing control: " + label);
    }

    private static void press(MoviePlayerActivity activity, int key) {
        long now = SystemClock.uptimeMillis();
        activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0));
        activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0));
        idle();
    }

    private static void advanceBetweenBackPresses() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
    }

    /** Cross-origin/no-video response. Records the complete native shortcut delivered to the page. */
    private static final class RecordingWebView extends WebView {
        final List<KeyEvent> keys = new ArrayList<>();
        RecordingWebView(Context context) { super(context); }
        @Override public void evaluateJavascript(String script, ValueCallback<String> callback) {
            if (callback != null) callback.onReceiveValue("\"0\"");
        }
        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            keys.add(event);
            return true;
        }
    }

    private static RecordingWebView recordProviderKeys(MoviePlayerActivity activity) throws Exception {
        WebView old = web(activity);
        ViewGroup parent = (ViewGroup) old.getParent();
        int position = parent.indexOfChild(old);
        ViewGroup.LayoutParams layout = old.getLayoutParams();
        parent.removeView(old);
        old.destroy();
        RecordingWebView recording = new RecordingWebView(activity);
        recording.setFocusable(true);
        recording.setFocusableInTouchMode(true);
        parent.addView(recording, position, layout);
        field("webView").set(activity, recording);
        recording.requestFocus();
        return recording;
    }

    private static void assertKeyPair(RecordingWebView recording, int expected) {
        assertEquals(2, recording.keys.size());
        assertEquals(KeyEvent.ACTION_DOWN, recording.keys.get(0).getAction());
        assertEquals(KeyEvent.ACTION_UP, recording.keys.get(1).getAction());
        assertEquals(expected, recording.keys.get(0).getKeyCode());
        assertEquals(expected, recording.keys.get(1).getKeyCode());
        recording.keys.clear();
    }

    @Test public void upMenuAndInfoOpenFocusedNativeModeAndDownClosesIt() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        for (int key : new int[] { KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO }) {
            press(activity, key);
            assertTrue(flag(activity, "tvControlsMode"));
            assertEquals(View.VISIBLE, controls(activity).getVisibility());
            assertTrue("Opening controls must provide an actual focus target", buttons(activity).stream().anyMatch(View::hasFocus));
            press(activity, KeyEvent.KEYCODE_DPAD_DOWN);
            assertFalse(flag(activity, "tvControlsMode"));
            assertEquals(View.GONE, controls(activity).getVisibility());
            assertFalse(flag(activity, "closeArmed"));
        }
    }

    @Test public void controlNavigationMovesFocusWithoutSeekingAndOkActivatesSelection() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        RecordingWebView recording = recordProviderKeys(activity);
        press(activity, KeyEvent.KEYCODE_DPAD_UP);
        Button play = button(activity, activity.getString(R.string.player_play_pause));
        play.requestFocus();
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertFalse(play.hasFocus());
        assertTrue(buttons(activity).stream().anyMatch(View::hasFocus));
        assertTrue("Navigation must not send a seek shortcut", recording.keys.isEmpty());
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT);
        assertTrue(play.hasFocus());
        assertTrue(recording.keys.isEmpty());
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertKeyPair(recording, KeyEvent.KEYCODE_SPACE);
    }

    @Test public void firstBackOnlyClosesControlsAndRestoresPlaybackShortcuts() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        RecordingWebView recording = recordProviderKeys(activity);
        press(activity, KeyEvent.KEYCODE_DPAD_UP);
        press(activity, KeyEvent.KEYCODE_BACK);
        assertFalse(flag(activity, "tvControlsMode"));
        assertFalse(flag(activity, "closeArmed"));
        assertFalse(activity.isFinishing());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertKeyPair(recording, KeyEvent.KEYCODE_SPACE);
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT);
        assertKeyPair(recording, KeyEvent.KEYCODE_DPAD_LEFT);
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertKeyPair(recording, KeyEvent.KEYCODE_DPAD_RIGHT);
    }

    @Test public void twoDistinctBackPressesReleaseMovieBeforeReturningToExistingLiveTv() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        WebView movie = web(activity);
        press(activity, KeyEvent.KEYCODE_BACK);
        assertTrue(flag(activity, "closeArmed"));
        assertFalse(activity.isFinishing());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        advanceBetweenBackPresses();
        press(activity, KeyEvent.KEYCODE_BACK);
        assertTrue(activity.isFinishing());
        assertNull("Movie WebView must be released before launching Live TV", web(activity));
        assertTrue(Shadows.shadowOf(movie).wasDestroyCalled());
        Intent returned = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(returned);
        assertEquals(MainActivity.class.getName(), returned.getComponent().getClassName());
        assertTrue(returned.getBooleanExtra(MainActivity.EXTRA_RETURN_FROM_ON_DEMAND, false));
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP,
                returned.getFlags() & (Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void duplicateBackAndHeldBackCannotConfirmMovieExit() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        press(activity, KeyEvent.KEYCODE_BACK);
        press(activity, KeyEvent.KEYCODE_BACK);
        assertFalse(activity.isFinishing());
        advanceBetweenBackPresses();
        long now = SystemClock.uptimeMillis();
        activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 1));
        assertFalse(activity.isFinishing());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void homeAndGeneralDestructionDoNotLaunchLiveTv() throws Exception {
        ActivityController<MoviePlayerActivity> lifecycle = open(false, "movies");
        MoviePlayerActivity activity = lifecycle.get();
        press(activity, KeyEvent.KEYCODE_HOME);
        assertFalse(flag(activity, "closeArmed"));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        lifecycle.pause().stop().destroy();
        activities.remove(lifecycle);
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void unavailableProviderQualitySubtitlesAndCastAreNotPresentedAsWorkingTvActions() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        recordProviderKeys(activity);
        press(activity, KeyEvent.KEYCODE_DPAD_UP);
        for (Button button : buttons(activity)) {
            String label = button.getText().toString();
            assertNotEquals(activity.getString(R.string.player_quality), label);
            assertFalse(label.toLowerCase(java.util.Locale.ROOT).contains("cast"));
            if (activity.getString(R.string.player_subtitles).equals(label))
                assertTrue("Unavailable text tracks must not create a usable decorative action",
                        button.getVisibility() != View.VISIBLE || !button.isEnabled());
        }
    }

    @Test public void nativeVolumeAndMuteActionsChangeTheMusicStream() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        AudioManager audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        VolumePolicyShadow audioShadow = (VolumePolicyShadow) org.robolectric.shadow.api.Shadow.extract(audio);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0);
        press(activity, KeyEvent.KEYCODE_DPAD_UP);
        button(activity, "Vol +").requestFocus();
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(4, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        button(activity, "Vol −").requestFocus();
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        button(activity, "Mute").requestFocus();
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC));
        assertEquals(AudioManager.STREAM_MUSIC, audioShadow.lastStream);
        assertEquals(AudioManager.ADJUST_TOGGLE_MUTE, audioShadow.lastDirection);
        assertEquals(AudioManager.FLAG_SHOW_UI, audioShadow.lastFlags);
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertFalse(audio.isStreamMute(AudioManager.STREAM_MUSIC));
        assertEquals(4, audioShadow.adjustments);
    }

    @Test public void fixedVolumeDevicesDoNotOfferUnavailableNativeVolumeActions() throws Exception {
        VolumePolicyShadow.fixed = true;
        MoviePlayerActivity activity = open(false, "movies").get();
        for (Button button : buttons(activity)) {
            String label = button.getText().toString();
            assertFalse(label.startsWith("Vol "));
            assertNotEquals("Mute", label);
        }
    }

    @Test public void nativeFullscreenChangesActualWindowStateWithoutProviderCommands() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        RecordingWebView recording = recordProviderKeys(activity);
        assertTrue(flag(activity, "appFullscreen"));
        assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0);
        press(activity, KeyEvent.KEYCODE_DPAD_UP);
        button(activity, activity.getString(R.string.player_fullscreen)).requestFocus();
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertFalse(flag(activity, "appFullscreen"));
        assertEquals(0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN);
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue(flag(activity, "appFullscreen"));
        assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0);
        assertFalse(flag(activity, "tvControlsMode"));
        assertTrue(recording.keys.isEmpty());
    }

    @Test public void onlyRuntimeAccessibleSubtitleTracksCreateANativeSelector() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        WebView movie = web(activity);
        press(activity, KeyEvent.KEYCODE_DPAD_UP);
        assertNull(field("subtitleControl").get(activity));
        String tracks = JSONObject.quote("[{\"index\":0,\"label\":\"Bulgarian\",\"selected\":true},{\"index\":2,\"label\":\"English\",\"selected\":false}]");
        Shadows.shadowOf(movie).getLastEvaluatedJavascriptCallback().onReceiveValue(tracks);
        idle();
        Button subtitles = button(activity, activity.getString(R.string.player_subtitles));
        assertTrue(subtitles.isEnabled());
        subtitles.requestFocus();
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        Shadows.shadowOf(movie).getLastEvaluatedJavascriptCallback().onReceiveValue(tracks);
        idle();
        AlertDialog dialog = (AlertDialog) field("subtitleDialog").get(activity);
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        assertEquals("Off", dialog.getListView().getAdapter().getItem(0));
        assertEquals("Bulgarian", dialog.getListView().getAdapter().getItem(1));
        assertEquals("English", dialog.getListView().getAdapter().getItem(2));
        assertEquals(1, dialog.getListView().getCheckedItemPosition());
        dialog.getListView().performItemClick(null, 2, 2);
        idle();
        assertFalse(dialog.isShowing());
        assertNull(field("subtitleDialog").get(activity));
        assertTrue("The chosen actual text-track index must be passed to accessible HTML5 media",
                Shadows.shadowOf(movie).getLastEvaluatedJavascript().contains("i===2"));
        assertTrue(subtitles.hasFocus());
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN);
        press(activity, KeyEvent.KEYCODE_DPAD_UP);
        Shadows.shadowOf(movie).getLastEvaluatedJavascriptCallback().onReceiveValue(JSONObject.quote("[]"));
        idle();
        assertNull(field("subtitleControl").get(activity));
        assertFalse(buttons(activity).contains(subtitles));
        assertTrue(buttons(activity).stream().anyMatch(View::hasFocus));
    }

    @Test public void staleSubtitleProbeCannotReintroduceAnUnavailableControl() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        WebView movie = web(activity);
        press(activity, KeyEvent.KEYCODE_DPAD_UP);
        ValueCallback<String> oldProbe = Shadows.shadowOf(movie).getLastEvaluatedJavascriptCallback();
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN);
        press(activity, KeyEvent.KEYCODE_DPAD_UP);
        Shadows.shadowOf(movie).getLastEvaluatedJavascriptCallback().onReceiveValue(JSONObject.quote("[]"));
        oldProbe.onReceiveValue(JSONObject.quote("[{\"index\":0,\"label\":\"Bulgarian\",\"selected\":true}]"));
        idle();
        assertNull(field("subtitleControl").get(activity));
    }

    @Test public void phoneBarRetainsExistingPlaybackSubtitleQualityAndFullscreenActions() throws Exception {
        MoviePlayerActivity activity = open(true, "movies").get();
        assertNotNull(button(activity, activity.getString(R.string.player_play_pause)));
        assertNotNull(button(activity, "−10s"));
        assertNotNull(button(activity, "+10s"));
        assertNotNull(button(activity, activity.getString(R.string.player_subtitles)));
        assertNotNull(button(activity, activity.getString(R.string.player_quality)));
        assertNotNull(button(activity, activity.getString(R.string.player_fullscreen)));
        press(activity, KeyEvent.KEYCODE_DPAD_UP);
        assertFalse(flag(activity, "tvControlsMode"));
        assertEquals(View.VISIBLE, controls(activity).getVisibility());
    }

    @Test public void firstAndReopenedMovieUseTheSameExistingProviderWithoutSpeculativeSwitch() throws Exception {
        ActivityController<MoviePlayerActivity> first = open(false, "movies");
        String firstUrl = Shadows.shadowOf(web(first.get())).getLastLoadedUrl();
        first.pause().stop().destroy();
        activities.remove(first);
        MoviePlayerActivity reopened = open(false, "movies").get();
        assertEquals("https://streamimdb.ru/embed/movie/tt0111161", firstUrl);
        assertEquals(firstUrl, Shadows.shadowOf(web(reopened)).getLastLoadedUrl());
    }

    @Test public void seriesFallbackKeepsTheDistinctExistingSeriesRoute() throws Exception {
        MoviePlayerActivity activity = open(false, "series").get();
        assertEquals("https://streamimdb.ru/embed/tv/tt0111161", Shadows.shadowOf(web(activity)).getLastLoadedUrl());
    }
}
