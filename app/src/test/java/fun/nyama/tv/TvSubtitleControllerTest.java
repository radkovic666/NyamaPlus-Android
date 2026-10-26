package fun.nyama.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.ValueCallback;
import android.widget.Button;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import java.time.Duration;
import java.util.ArrayDeque;
import static org.junit.Assert.*;

/** Native modal and async behavior with synthetic JS replies, not live provider tests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w1280dp-h720dp-land-mdpi")
public class TvSubtitleControllerTest {
    private ActivityController<Activity> lifecycle;
    private Activity activity;
    private TvSubtitleController controller;
    private FakeHost host;
    private static final String URL = "https://vaplayer.ru/embed/tv/tt0944947/2/3";
    @Before public void setup() {
        new android.app.Instrumentation().setInTouchMode(false);
        RuntimeEnvironment.getApplication().getSharedPreferences("nyama_tv_subtitles", 0).edit().clear().commit();
        lifecycle = Robolectric.buildActivity(Activity.class).setup().visible(); activity = lifecycle.get();
        new fun.nyama.tv.Config(activity).setLanguage(fun.nyama.tv.Config.LANG_EN);
        host = new FakeHost(); controller = new TvSubtitleController(activity, host, URL, "en");
    }
    @After public void cleanup() { controller.close(); lifecycle.pause().stop().destroy(); }
    private static final class FakeHost implements TvSubtitleController.Host {
        final ArrayDeque<ValueCallback<String>> pending = new ArrayDeque<>();
        String lastScript; int provider, automatic, returned; String picked;
        public void evaluate(String script, ValueCallback<String> callback) { lastScript = script; pending.add(callback); }
        public void automatic() { automatic++; }
        public void pickFile(String language) { picked = language; }
        public void providerControls() { provider++; }
        public void returnFocus() { returned++; }
        void reply(JSONObject data) { pending.remove().onReceiveValue(JSONObject.quote(data.toString())); Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    }
    private static JSONObject snapshot(String selected, double offset) throws Exception {
        JSONArray tracks = new JSONArray();
        tracks.put(new JSONObject().put("key", "track-1").put("label", "BG — Release A").put("language", "bg").put("kind", "subtitles")
                .put("source", "https://example.com/sub.vtt?credential=must-not-persist").put("selected", "track-1".equals(selected)).put("canOffset", true).put("offset", offset));
        tracks.put(new JSONObject().put("key", "track-2").put("label", "English — Actual label").put("language", "en").put("kind", "captions")
                .put("source", "https://example.com/en.vtt").put("selected", "track-2".equals(selected)).put("canOffset", true).put("offset", offset));
        return new JSONObject().put("ok", true).put("token", "session-1").put("manual", true).put("canImport", true)
                .put("signature", "actual-original-cue-content").put("tracks", tracks);
    }
    private Button button(String tag) {
        AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        return dialog.getWindow().getDecorView().findViewWithTag(tag);
    }
    private void show(String selected) throws Exception { controller.show(); host.reply(snapshot(selected, 0)); }
    @Test public void realLabelsAndDelayControlsAppearOnlyForAvailableCapabilities() throws Exception {
        show("track-1"); assertTrue(controller.isShowing());
        assertTrue(button("track-1").getText().toString().contains("Release A"));
        assertTrue(button("track-2").getText().toString().contains("Actual label"));
        assertNotNull(button("earlier")); assertNotNull(button("later")); assertNotNull(button("reset"));
        assertNotNull(button("import-bg")); assertNotNull(button("import-en"));
        controller.dismiss(); controller.show(); host.reply(new JSONObject().put("ok", false));
        assertNotNull(button("provider")); assertNull(button("track-1")); assertNull(button("off"));
        assertNull(button("earlier")); assertNull(button("import-en"));
    }
    @Test public void selectingAndAdjustingActualTrackStoresOnlyHashedIdentity() throws Exception {
        show("track-1"); button("track-2").performClick(); assertTrue(host.lastScript.contains("'select'" ) || host.lastScript.contains("\"select\""));
        host.reply(snapshot("track-2", 0)); button("later").performClick(); host.reply(snapshot("track-2", .5));
        JSONObject saved = new TvSubtitleStore(activity, URL).read(); assertEquals("track", saved.getString("mode"));
        assertEquals(.5, saved.getDouble("offset"), .001); assertEquals(64, saved.getString("fingerprint").length());
        String preferences = activity.getSharedPreferences("nyama_tv_subtitles", 0).getAll().toString();
        assertFalse(preferences.contains("credential")); assertFalse(preferences.contains("https://")); assertFalse(preferences.contains("cue-content"));
    }
    @Test public void staleOrTimedOutCallbacksCannotReplaceNewerSelection() throws Exception {
        controller.show(); ValueCallback<String> stale = host.pending.remove();
        controller.pause(); controller.show(); host.reply(snapshot("track-2", 0));
        stale.onReceiveValue(JSONObject.quote(snapshot("track-1", 0).toString()));
        button("off").performClick(); ValueCallback<String> late = host.pending.remove();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        late.onReceiveValue(JSONObject.quote(snapshot("", 0).toString()));
        assertEquals(0, new TvSubtitleStore(activity, URL).read().length());
    }
    @Test public void providerControlsAndLocalFileActionsAreExplicitUserChoices() throws Exception {
        show("track-1"); button("provider").performClick();
        host.reply(new JSONObject().put("ok", true).put("provider", "focus"));
        assertEquals(1, host.provider); assertFalse(controller.isShowing());
        controller.show(); host.reply(snapshot("track-1", 0)); button("import-en").performClick();
        assertEquals("en", host.picked); assertFalse(controller.isShowing());
    }
    @Test public void offRestorationIsScopedToTheExactDocumentAndDoesNotLoop() throws Exception {
        TvSubtitleStore store = new TvSubtitleStore(activity, URL); store.save("off", "", "", "", 0);
        assertTrue(controller.restorePreference()); host.reply(snapshot("track-1", 0).put("manual", false));
        assertTrue(host.lastScript.contains("\"off\"")); host.reply(snapshot("", 0));
        int remaining = host.pending.size(); assertTrue(controller.restorePreference()); host.reply(snapshot("", 0));
        assertEquals(remaining, host.pending.size());
    }
    @Test public void manualSelectionPreventsSavedPreferencesFromBeingReappliedOverIt() throws Exception {
        TvSubtitleStore store = new TvSubtitleStore(activity, URL); store.save("off", "", "", "", 0);
        assertTrue(controller.restorePreference()); host.reply(snapshot("track-2", 0));
        assertTrue(host.pending.isEmpty());
    }
    @Test public void shortcutReleaseDoesNotImmediatelyCloseTheNewlyOpenedMenuAndBackDoesNotExitPlayback() throws Exception {
        show("track-1");
        assertTrue(controller.dispatch(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU))); assertTrue(controller.isShowing());
        assertTrue(controller.dispatch(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertFalse(controller.isShowing());
        assertFalse(activity.isFinishing()); assertTrue(host.returned > 0);
    }
    @Test public void offsetsAreFiniteHalfSecondStepsWithinFiveSeconds() {
        for (double value : new double[]{-5,-.5,0,.5,5}) assertTrue(TvSubtitleController.validOffset(value));
        for (double value : new double[]{-5.5,5.5,.25,Double.NaN,Double.POSITIVE_INFINITY}) assertFalse(TvSubtitleController.validOffset(value));
    }
    @Test public void savedDelayIsRestoredOnlyForTheSameActualCueFingerprint() throws Exception {
        String source=TvSubtitleFile.hash("https://example.com/en.vtt");
        TvSubtitleStore store=new TvSubtitleStore(activity,URL);
        store.save("track",source,TvSubtitleFile.hash("actual-original-cue-content"),"",1);
        controller.restorePreference();host.reply(snapshot("track-1",0).put("manual",false));
        host.reply(snapshot("track-2",0));assertTrue(host.lastScript.contains("\"offset\""));host.reply(snapshot("track-2",1));
        controller.close();controller=new TvSubtitleController(activity,host,URL,"en");
        controller.restorePreference();host.reply(snapshot("track-1",0).put("manual",false));
        host.reply(snapshot("track-2",0).put("signature","different release cues"));
        assertTrue(host.pending.isEmpty());
    }

    @Test public void authorizedLocalFileIsParsedOnWorkerAndAppliedOnlyAfterCurrentVideoInspection() throws Exception {
        show("track-1");
        android.net.Uri uri=android.net.Uri.parse("content://user-picked/subtitle.srt");
        byte[] data="1\n00:00:01,000 --> 00:00:03,000\nAuthorized local captions".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Shadows.shadowOf(activity.getContentResolver()).registerInputStream(uri,new java.io.ByteArrayInputStream(data));
        controller.importFile(uri,"en");
        java.lang.reflect.Field executor=TvSubtitleController.class.getDeclaredField("files");executor.setAccessible(true);
        ((java.util.concurrent.ExecutorService)executor.get(controller)).submit(()->{}).get(3,java.util.concurrent.TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();host.reply(snapshot("track-1",0));
        assertTrue(host.lastScript.contains("Authorized local captions"));
        JSONObject applied=snapshot("",0);applied.getJSONArray("tracks").put(new JSONObject().put("key","local-3").put("kind","subtitles")
                .put("language","en").put("label","Local English").put("local",TvSubtitleFile.hash(data)).put("selected",true).put("canOffset",true));
        host.reply(applied);JSONObject saved=new TvSubtitleStore(activity,URL).read();
        assertEquals("local",saved.getString("mode"));assertEquals(TvSubtitleFile.hash(data),saved.getString("file"));
    }
    @Test public void genericSeriesChooserDoesNotPersistAnOffsetForAnUnidentifiedEpisode() throws Exception {
        controller.close();String generic="https://vaplayer.ru/embed/tv/tt0944947";
        controller=new TvSubtitleController(activity,host,generic,"en");controller.show();host.reply(snapshot("track-1",0));
        button("later").performClick();host.reply(snapshot("track-1",.5));
        assertEquals(0,new TvSubtitleStore(activity,generic).read().length());assertFalse(controller.restorePreference());
    }

    @Test public void savedDelayWaitsForTheSelectedFileToLoadWithoutUnboundedPolling() throws Exception {
        TvSubtitleStore store=new TvSubtitleStore(activity,URL);
        store.save("track",TvSubtitleFile.hash("https://example.com/en.vtt"),TvSubtitleFile.hash("actual-original-cue-content"),"",1);
        controller.restorePreference();host.reply(snapshot("track-1",0).put("manual",false));
        JSONObject unloaded=snapshot("track-2",0).put("signature","");unloaded.getJSONArray("tracks").getJSONObject(1).put("canOffset",false);
        host.reply(unloaded);assertTrue(host.pending.isEmpty());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800));host.reply(snapshot("track-2",0));
        assertTrue(host.lastScript.contains("\"offset\""));host.reply(snapshot("track-2",1));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));assertTrue(host.pending.isEmpty());
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void tvDialogRendersAndDpadFocusDoesNotInvokeAPlaybackAction() throws Exception {
        show("track-1");AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        View decor=dialog.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(660,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(612,View.MeasureSpec.EXACTLY));decor.layout(0,0,660,612);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(button("provider").requestFocus());
        controller.dispatch(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));
        controller.dispatch(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_DOWN));
        assertTrue(host.pending.isEmpty());assertTrue(controller.isShowing());assertFalse(activity.isFinishing());
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(660,612,android.graphics.Bitmap.Config.ARGB_8888);
        decor.draw(new android.graphics.Canvas(bitmap));
        String destination=System.getenv("NYAMA_UI_ARTIFACTS");
        if(destination!=null){java.io.File directory=new java.io.File(destination);assertTrue(directory.isDirectory()||directory.mkdirs());try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(directory,"tv-subtitles.png"))){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output));}}
        bitmap.recycle();
    }

}
