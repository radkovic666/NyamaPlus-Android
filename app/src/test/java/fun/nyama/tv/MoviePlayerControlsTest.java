package fun.nyama.tv;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

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
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowWebView;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Native remote/routing checks with synthetic WebView responses; no live provider or hardware. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, shadows = MoviePlayerControlsTest.PlayerLayoutWebViewShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class MoviePlayerControlsTest {
    private static final String MOVIE = "tt0111161";
    private static final String SERIES = "tt0944947";
    private final List<ActivityController<MoviePlayerActivity>> activities = new ArrayList<>();

    @Before public void before() {
        PlaylistReturnRefresh.refresher = context -> java.util.Collections.emptyList();
        Context context = RuntimeEnvironment.getApplication();
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
        PlaylistReturnRefresh.refresher = PlaylistRepository::refresh;
    }

    /** Models the real provider's superclass layout/focus delegation, absent from the stub proxy. */
    @Implements(WebView.class)
    public static class PlayerLayoutWebViewShadow extends ShadowWebView {
        @RealObject private WebView target;
        @Implementation protected boolean setFrame(int left, int top, int right, int bottom) {
            return Shadow.directlyOn(target, View.class, "setFrame",
                    ReflectionHelpers.ClassParameter.from(int.class, left),
                    ReflectionHelpers.ClassParameter.from(int.class, top),
                    ReflectionHelpers.ClassParameter.from(int.class, right),
                    ReflectionHelpers.ClassParameter.from(int.class, bottom));
        }
        @Implementation protected void onMeasure(int width, int height) {
            Shadow.directlyOn(target, View.class, "onMeasure",
                    ReflectionHelpers.ClassParameter.from(int.class, width),
                    ReflectionHelpers.ClassParameter.from(int.class, height));
        }
        @Implementation public boolean requestFocus(int direction, android.graphics.Rect rectangle) {
            return Shadow.directlyOn(target, View.class, "requestFocus",
                    ReflectionHelpers.ClassParameter.from(int.class, direction),
                    ReflectionHelpers.ClassParameter.from(android.graphics.Rect.class, rectangle));
        }
    }

    private static Intent title(String type, String id) {
        return new Intent(RuntimeEnvironment.getApplication(), MoviePlayerActivity.class)
                .putExtra("id", id).putExtra("type", type).putExtra("title", "Synthetic title");
    }

    private static Intent episode(int season, int episode) {
        return title("series", SERIES).putExtra("series_imdb_id", SERIES)
                .putExtra("season", season).putExtra("episode", episode).putExtra("return_to_series", true);
    }

    private ActivityController<MoviePlayerActivity> open(boolean phone, String type) throws Exception {
        return open(phone, title(type, "series".equals(type) ? SERIES : MOVIE));
    }

    private ActivityController<MoviePlayerActivity> open(boolean phone, Intent intent) throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        new fun.nyama.tv.Config(context).setDeviceMode(phone ? fun.nyama.tv.Config.MODE_PHONE : fun.nyama.tv.Config.MODE_TV);
        ActivityController<MoviePlayerActivity> lifecycle = Robolectric.buildActivity(MoviePlayerActivity.class, intent)
                .create().start().resume().visible();
        activities.add(lifecycle);
        MoviePlayerActivity activity = lifecycle.get();
        layoutPlayer(activity);
        idle();
        // Physical remote presses occur after creation, outside the duplicate BACK guard.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        if (!flag(activity, "playbackFailed")) {
            WebView browser = web(activity);
            assertNotNull("Foreground=" + flag(activity, "tvForeground") + ", attached=" + browser.isAttachedToWindow()
                    + ", browser size=" + browser.getWidth() + "x" + browser.getHeight()
                    + ": the foreground player must load its selected document", url(activity));
        }
        return lifecycle;
    }

    private void destroy(ActivityController<MoviePlayerActivity> lifecycle) {
        lifecycle.pause().stop().destroy();
        activities.remove(lifecycle);
    }

    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    private static void layoutPlayer(MoviePlayerActivity activity) {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1920, 1080);
        View root = activity.findViewById(android.R.id.content);
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1920, 1080);
        // A programmatic setContentView replacement does not receive a platform traversal in
        // Robolectric's paused looper. Supply the Activity's full-parent player frame as well.
        if (root instanceof ViewGroup) {
            ViewGroup content = (ViewGroup) root;
            for (int i = 0; i < content.getChildCount(); i++) {
                View playerFrame = content.getChildAt(i);
                playerFrame.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
                playerFrame.layout(0, 0, 1920, 1080);
            }
        }
        for (View child : views(root)) if (child instanceof WebView) {
            assertTrue("The browser fixture must be attached to the actual Activity window", child.isAttachedToWindow());
            assertTrue("The browser fixture must have real full-parent bounds: " + child.getWidth() + "x" + child.getHeight(),
                    child.getWidth() > 0 && child.getHeight() > 0);
        }
    }

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

    private static String url(MoviePlayerActivity activity) throws Exception {
        return web(activity) == null ? null : Shadows.shadowOf(web(activity)).getLastLoadedUrl();
    }

    private static LinearLayout controls(MoviePlayerActivity activity) throws Exception {
        return (LinearLayout) field("remoteControls").get(activity);
    }

    @SuppressWarnings("unchecked") private static List<Button> buttons(MoviePlayerActivity activity) throws Exception {
        return (List<Button>) field("controlButtons").get(activity);
    }

    private static Button phoneButton(MoviePlayerActivity activity, String label) throws Exception {
        for (Button button : buttons(activity)) if (label.equals(button.getText().toString())) return button;
        throw new AssertionError("Missing phone control: " + label);
    }

    private static List<View> views(View root) {
        List<View> views = new ArrayList<>();
        views.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) views.addAll(views(group.getChildAt(i)));
        }
        return views;
    }

    private static List<Button> visibleButtons(MoviePlayerActivity activity) {
        List<Button> result = new ArrayList<>();
        for (View view : views(activity.getWindow().getDecorView()))
            if (view instanceof Button && view.isShown()) result.add((Button) view);
        return result;
    }

    private static String visibleText(MoviePlayerActivity activity) {
        StringBuilder result = new StringBuilder();
        for (View view : views(activity.getWindow().getDecorView()))
            if (view instanceof TextView && view.isShown()) result.append(((TextView) view).getText()).append('\n');
        return result.toString();
    }

    private static Button screenButton(MoviePlayerActivity activity, String label) {
        for (Button button : visibleButtons(activity)) if (label.equals(button.getText().toString())) return button;
        throw new AssertionError("Missing visible error action: " + label);
    }

    private static void press(MoviePlayerActivity activity, int key) {
        long now = SystemClock.uptimeMillis();
        activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0));
        activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0));
        layoutPlayer(activity);
        idle();
    }

    private static void advanceBetweenBackPresses() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
    }

    /** Cross-origin/no-video response. Records complete native shortcuts delivered to the page. */
    private static final class RecordingWebView extends WebView {
        final List<KeyEvent> keys = new ArrayList<>();
        RecordingWebView(Context context) { super(context); }
        @Override public void evaluateJavascript(String script, ValueCallback<String> callback) {
            if (callback != null) callback.onReceiveValue(script.contains("__nyamaTvSeek")
                    ? "\"{\\\"state\\\":\\\"inaccessible\\\"}\""
                    : script.contains("document.activeElement===best") ? "\"1\"" : "\"0\"");
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

    private static void assertNoTvPlaybackBar(MoviePlayerActivity activity) throws Exception {
        assertNull(controls(activity));
        assertTrue(buttons(activity).isEmpty());
    }

    private static void assertVapRoute(MoviePlayerActivity activity, String path, String language) throws Exception {
        assertNotNull("The selected VAPLayer document must actually be loaded", url(activity));
        Uri uri = Uri.parse(url(activity));
        assertEquals("https", uri.getScheme());
        assertEquals("vaplayer.ru", uri.getHost());
        assertEquals(path, uri.getPath());
        assertEquals(language, uri.getQueryParameter("ds_lang"));
        assertEquals("1", uri.getQueryParameter("autoplay"));
        String expectedColor = String.format(java.util.Locale.ROOT, "#%06x", UiTheme.accent(activity) & 0x00ffffff);
        assertEquals(expectedColor, uri.getQueryParameter("primaryColor").toLowerCase(java.util.Locale.ROOT));
    }

    private static void assertNoMediaLoaded(MoviePlayerActivity activity) throws Exception {
        String loaded = url(activity);
        assertTrue("Invalid episode selection must not silently fall back to any media", loaded == null || "about:blank".equals(loaded));
    }

    @Test public void upDownMenuInfoAndSettingsNeverCreateATvPlaybackToolbar() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        RecordingWebView recording = recordProviderKeys(activity);
        assertNoTvPlaybackBar(activity);
        for (int key : new int[] { KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_SETTINGS }) {
            press(activity, key);
            assertNoTvPlaybackBar(activity);
            assertTrue(visibleButtons(activity).isEmpty());
            assertTrue(recording.keys.isEmpty());
            assertFalse(flag(activity, "closeArmed"));
        }
    }

    @Test public void normalRemoteShortcutsDeliverExactlyOneNativeKeyPair() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        RecordingWebView recording = recordProviderKeys(activity);
        for (int key : new int[] { KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }) {
            press(activity, key);
            assertKeyPair(recording, KeyEvent.KEYCODE_SPACE);
        }
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT);
        assertKeyPair(recording, KeyEvent.KEYCODE_DPAD_LEFT);
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertKeyPair(recording, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertNoTvPlaybackBar(activity);
    }

    @Test public void heldRemoteKeysDoNotRepeatPlayPauseOrSeekActions() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        RecordingWebView recording = recordProviderKeys(activity);
        for (int key : new int[] { KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT }) {
            long now = SystemClock.uptimeMillis();
            activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 1));
            activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0));
        }
        idle();
        assertTrue(recording.keys.isEmpty());
    }

    @Test public void originlessMovieExitReleasesVideoAndReturnsToMoviesWithoutOpeningLiveTv() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        WebView movie = web(activity);
        press(activity, KeyEvent.KEYCODE_BACK);
        assertTrue(flag(activity, "closeArmed"));
        assertFalse(activity.isFinishing());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        advanceBetweenBackPresses();
        press(activity, KeyEvent.KEYCODE_BACK);
        assertTrue(activity.isFinishing());
        assertNull("Movie WebView must be released before returning to the catalogue", web(activity));
        assertTrue(Shadows.shadowOf(movie).wasDestroyCalled());
        Intent returned = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(returned);
        assertEquals(CatalogActivity.class.getName(), returned.getComponent().getClassName());
        assertEquals("movies", returned.getStringExtra(CatalogActivity.EXTRA_SECTION));
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

    @Test public void selectedEpisodeBackReleasesMediaAndReturnsToDetailsWithoutStartingLiveTv() throws Exception {
        ActivityController<MoviePlayerActivity> lifecycle = open(false, episode(2, 3));
        MoviePlayerActivity activity = lifecycle.get();
        WebView movie = web(activity);
        press(activity, KeyEvent.KEYCODE_BACK);
        assertFalse(activity.isFinishing());
        advanceBetweenBackPresses();
        press(activity, KeyEvent.KEYCODE_BACK);
        assertTrue(activity.isFinishing());
        assertNull(web(activity));
        assertTrue(Shadows.shadowOf(movie).wasDestroyCalled());
        assertFalse(flag(activity, "resumeTvWhenClosing"));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        destroy(lifecycle);
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void homeAndGeneralDestructionDoNotLaunchLiveTv() throws Exception {
        ActivityController<MoviePlayerActivity> lifecycle = open(false, "movies");
        MoviePlayerActivity activity = lifecycle.get();
        WebView movie = web(activity);
        press(activity, KeyEvent.KEYCODE_HOME);
        assertFalse(flag(activity, "closeArmed"));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        destroy(lifecycle);
        assertTrue(Shadows.shadowOf(movie).wasDestroyCalled());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void phoneBarRetainsExistingPlaybackSubtitleQualityAndFullscreenActions() throws Exception {
        MoviePlayerActivity activity = open(true, "movies").get();
        assertNotNull(phoneButton(activity, activity.getString(R.string.player_play_pause)));
        assertNotNull(phoneButton(activity, "−10s"));
        assertNotNull(phoneButton(activity, "+10s"));
        assertNotNull(phoneButton(activity, activity.getString(R.string.player_subtitles)));
        assertNotNull(phoneButton(activity, activity.getString(R.string.player_quality)));
        assertNotNull(phoneButton(activity, activity.getString(R.string.player_fullscreen)));
        press(activity, KeyEvent.KEYCODE_DPAD_UP);
        assertEquals(View.VISIBLE, controls(activity).getVisibility());
    }

    @Test public void phoneMovieAndSeriesUseTheSavedDefaultPlayer() throws Exception {
        for (String type : new String[] { "movies", "series" }) {
            ActivityController<MoviePlayerActivity> lifecycle = open(true, type);
            assertVapRoute(lifecycle.get(), "series".equals(type) ? "/embed/tv/" + SERIES : "/embed/movie/" + MOVIE, "en");
            destroy(lifecycle);
        }
    }

    @Test public void phoneHonorsExactEpisodeExtrasWithTheSavedPlayer() throws Exception {
        MoviePlayerActivity activity = open(true, episode(2, 3)).get();
        assertVapRoute(activity, "/embed/tv/" + SERIES + "/2/3", "en");
        assertNotNull(controls(activity));
    }

    @Test public void firstAndReopenedTvMovieUseTheSameDocumentedPreferredRoute() throws Exception {
        ActivityController<MoviePlayerActivity> first = open(false, "movies");
        assertVapRoute(first.get(), "/embed/movie/" + MOVIE, "en");
        String firstUrl = url(first.get());
        destroy(first);
        MoviePlayerActivity reopened = open(false, "movies").get();
        assertEquals(firstUrl, url(reopened));
        assertNoTvPlaybackBar(reopened);
    }

    @Test public void tvMovieRequestsConfiguredBulgarianSubtitlePreference() throws Exception {
        new fun.nyama.tv.Config(RuntimeEnvironment.getApplication()).setLanguage(fun.nyama.tv.Config.LANG_BG);
        MoviePlayerActivity activity = open(false, "movies").get();
        assertVapRoute(activity, "/embed/movie/" + MOVIE, "bg");
        assertNoTvPlaybackBar(activity);
    }

    @Test public void selectedTvEpisodeUsesExactParentIdentitySeasonAndEpisode() throws Exception {
        MoviePlayerActivity activity = open(false, episode(2, 3)).get();
        assertVapRoute(activity, "/embed/tv/" + SERIES + "/2/3", "en");
        assertNoTvPlaybackBar(activity);
    }

    @Test public void explicitlyIdentifiedSpecialsSeasonZeroIsPreserved() throws Exception {
        MoviePlayerActivity activity = open(false, episode(0, 1)).get();
        assertVapRoute(activity, "/embed/tv/" + SERIES + "/0/1", "en");
    }

    @Test public void genericSeriesUsesSelectedProviderWithoutGuessedEpisode() throws Exception {
        MoviePlayerActivity activity = open(false, "series").get();
        assertVapRoute(activity, "/embed/tv/" + SERIES, "en");
        assertNoTvPlaybackBar(activity);
    }

    @Test public void mismatchedSeriesIdentityCannotOpenAnotherTitleOrFallback() throws Exception {
        MoviePlayerActivity activity = open(false, episode(2, 3).putExtra("series_imdb_id", "tt0903747")).get();
        assertNoMediaLoaded(activity);
        assertFalse(visibleButtons(activity).isEmpty());
        assertNoTvPlaybackBar(activity);
    }

    @Test public void invalidOrIncompleteExplicitEpisodeSelectionCannotFallBackSilently() throws Exception {
        List<Intent> invalid = new ArrayList<>();
        invalid.add(episode(-1, 1));
        invalid.add(episode(1, 0));
        Intent missingParent = episode(1, 1);
        missingParent.removeExtra("series_imdb_id");
        invalid.add(missingParent);
        invalid.add(title("series", SERIES).putExtra("season", 2).putExtra("return_to_series", true));
        for (Intent intent : invalid) {
            ActivityController<MoviePlayerActivity> lifecycle = open(false, intent);
            assertNoMediaLoaded(lifecycle.get());
            assertFalse(visibleButtons(lifecycle.get()).isEmpty());
            destroy(lifecycle);
        }
    }

    private static WebResourceRequest request(String url, boolean mainFrame) {
        return new WebResourceRequest() {
            @Override public Uri getUrl() { return Uri.parse(url); }
            @Override public boolean isForMainFrame() { return mainFrame; }
            @Override public boolean isRedirect() { return false; }
            @Override public boolean hasGesture() { return false; }
            @Override public String getMethod() { return "GET"; }
            @Override public Map<String, String> getRequestHeaders() { return Collections.emptyMap(); }
        };
    }

    private static WebResourceError networkError() {
        // Android's error constructor is hidden. This client acts on the failed request only,
        // so no framework error instance is needed for the synthetic failure callback.
        return null;
    }

    private static SslErrorHandler sslHandler() {
        // The platform constructor is hidden; use Robolectric's supported reflection helper.
        return ReflectionHelpers.callConstructor(SslErrorHandler.class);
    }

    private static SslError sslError(String url) {
        // The instrumented SDK adds a constructor that initializes only the Robolectric object.
        // Certificate parsing is unrelated to callback routing and requires Android-only crypto.
        SslCertificate certificate = Shadow.newInstanceOf(SslCertificate.class);
        return new SslError(SslError.SSL_UNTRUSTED, certificate, url);
    }

    private static void assertSslRejected(SslErrorHandler handler) {
        assertTrue("Default TLS certificate rejection must always be preserved", Shadows.shadowOf(handler).wasCancelCalled());
        assertFalse(Shadows.shadowOf(handler).wasProceedCalled());
    }

    @Test public void mainFrameNetworkFailureShowsAnHonestActionableErrorWithoutPlaybackToolbar() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        WebView movie = web(activity);
        Shadows.shadowOf(movie).getWebViewClient().onReceivedError(movie, request(url(activity), true), networkError());
        idle();
        assertNoTvPlaybackBar(activity);
        assertFalse("A failed player must provide a visible return/retry action", visibleButtons(activity).isEmpty());
        assertFalse(visibleText(activity).trim().isEmpty());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void mainFrameHttpFailureShowsAnErrorButDoesNotSwitchProviders() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        String selected = url(activity);
        WebView movie = web(activity);
        WebResourceResponse response = new WebResourceResponse("text/html", "UTF-8", 503, "Unavailable",
                Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
        Shadows.shadowOf(movie).getWebViewClient().onReceivedHttpError(movie, request(selected, true), response);
        idle();
        assertFalse(visibleButtons(activity).isEmpty());
        assertNoTvPlaybackBar(activity);
        String after = url(activity);
        assertTrue(after == null || "about:blank".equals(after) || selected.equals(after));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void optionalResourceSslFailuresAreRejectedWithoutTearingDownThePlayer() throws Exception {
        MoviePlayerActivity activity = open(false, episode(2, 3)).get();
        WebView movie = web(activity);
        String selected = url(activity);
        WebViewClient client = Shadows.shadowOf(movie).getWebViewClient();
        for (String optional : new String[] { "https://vaplayer.ru/favicon.ico",
                "https://ads.invalid/banner.js", "https://nextgencloudfabric.com/thumbnail.jpg",
                "https://nextgencloudfabric.com/embed/tv/" + SERIES + "/2/4" }) {
            SslErrorHandler handler = sslHandler();
            client.onReceivedSslError(movie, handler, sslError(optional));
            idle();
            assertSslRejected(handler);
            assertSame(movie, web(activity));
            assertEquals(selected, url(activity));
            assertFalse(Shadows.shadowOf(movie).wasDestroyCalled());
            assertTrue(visibleButtons(activity).isEmpty());
            assertNoTvPlaybackBar(activity);
        }
    }

    @Test public void selectedOuterOrNestedDocumentSslFailureIsRejectedAndShowsPlaybackError() throws Exception {
        for (boolean nested : new boolean[] { false, true }) {
            ActivityController<MoviePlayerActivity> lifecycle = open(false, episode(2, 3));
            MoviePlayerActivity activity = lifecycle.get();
            WebView movie = web(activity);
            String failingUrl = nested ? "https://nextgencloudfabric.com/embed/tv/" + SERIES + "/2/3" : url(activity);
            SslErrorHandler handler = sslHandler();
            Shadows.shadowOf(movie).getWebViewClient().onReceivedSslError(movie, handler, sslError(failingUrl));
            idle();
            assertSslRejected(handler);
            assertNull(web(activity));
            assertTrue(Shadows.shadowOf(movie).wasDestroyCalled());
            assertTrue(visibleText(activity).contains("secure connection"));
            assertNotNull(screenButton(activity, "Try again"));
            assertNotNull(screenButton(activity, "Back to episodes"));
            assertNoTvPlaybackBar(activity);
            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            destroy(lifecycle);
        }
    }

    @Test public void retryReloadsTheExactSelectedEpisodeAfterReleasingFailedMedia() throws Exception {
        MoviePlayerActivity activity = open(false, episode(2, 3)).get();
        String selected = url(activity);
        WebView failed = web(activity);
        Shadows.shadowOf(failed).getWebViewClient().onReceivedError(failed, request(selected, true), networkError());
        idle();
        assertNull(web(activity));
        assertTrue(Shadows.shadowOf(failed).wasDestroyCalled());
        Button retry = screenButton(activity, "Try again");
        assertTrue(retry.hasFocus());
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertNotSame(failed, web(activity));
        assertEquals(selected, url(activity));
        assertNoTvPlaybackBar(activity);
        assertTrue(visibleButtons(activity).isEmpty());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void episodeErrorReturnActionFinishesWithoutRestartingLiveTv() throws Exception {
        MoviePlayerActivity activity = open(false, episode(2, 3)).get();
        WebView failed = web(activity);
        Shadows.shadowOf(failed).getWebViewClient().onReceivedError(failed, request(url(activity), true), networkError());
        idle();
        assertTrue(screenButton(activity, "Back to episodes").requestFocus());
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue(activity.isFinishing());
        assertNull(web(activity));
        assertFalse(flag(activity, "resumeTvWhenClosing"));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void delayedCallbacksFromFailedWebViewCannotTearDownOrModifyTheRetriedPlayer() throws Exception {
        MoviePlayerActivity activity = open(false, episode(2, 3)).get();
        String selected = url(activity);
        WebView failed = web(activity);
        WebViewClient staleClient = Shadows.shadowOf(failed).getWebViewClient();
        staleClient.onReceivedError(failed, request(selected, true), networkError());
        idle();
        assertTrue(Shadows.shadowOf(failed).wasDestroyCalled());
        screenButton(activity, "Try again").performClick();
        layoutPlayer(activity);
        idle();
        WebView retried = web(activity);
        assertNotNull(retried);
        assertNotSame(failed, retried);
        String scriptBefore = Shadows.shadowOf(retried).getLastEvaluatedJavascript();

        // Queued page/error callbacks may still arrive after the previous view was destroyed.
        staleClient.onPageFinished(failed, selected);
        staleClient.onReceivedError(failed, request(selected, true), networkError());
        staleClient.onReceivedHttpError(failed, request(selected, true), new WebResourceResponse(
                "text/html", "UTF-8", 503, "Unavailable", Collections.emptyMap(), new ByteArrayInputStream(new byte[0])));
        SslErrorHandler staleSsl = sslHandler();
        staleClient.onReceivedSslError(failed, staleSsl, sslError(selected));
        idle();

        assertSslRejected(staleSsl);
        assertSame(retried, web(activity));
        assertEquals(selected, url(activity));
        assertFalse(Shadows.shadowOf(retried).wasDestroyCalled());
        assertEquals(scriptBefore, Shadows.shadowOf(retried).getLastEvaluatedJavascript());
        assertTrue(visibleButtons(activity).isEmpty());
        assertNoTvPlaybackBar(activity);
    }

    @Test public void failedNestedPlayerDocumentShowsErrorInsteadOfTreatingWrapperAsPlaybackSuccess() throws Exception {
        MoviePlayerActivity activity = open(false, episode(2, 3)).get();
        WebView movie = web(activity);
        WebViewClient client = Shadows.shadowOf(movie).getWebViewClient();
        client.onPageFinished(movie, url(activity));
        idle();
        assertTrue("An outer page load must not create playback controls", visibleButtons(activity).isEmpty());
        client.onReceivedError(movie, request("https://nextgencloudfabric.com/embed/tv/" + SERIES + "/2/3", false), networkError());
        idle();
        assertNull(web(activity));
        assertTrue(Shadows.shadowOf(movie).wasDestroyCalled());
        assertFalse(visibleButtons(activity).isEmpty());
        assertNoTvPlaybackBar(activity);
    }

    @Test public void unrelatedSubresourceFailureDoesNotReplaceWorkingPlaybackWithErrorUi() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        String selected = url(activity);
        WebView movie = web(activity);
        Shadows.shadowOf(movie).getWebViewClient().onReceivedError(movie,
                request("https://vaplayer.ru/favicon.ico", false), networkError());
        idle();
        assertEquals(selected, url(activity));
        assertTrue(visibleButtons(activity).isEmpty());
        assertNoTvPlaybackBar(activity);
    }

    @Test public void bothPlayersRouteMoviesAndExactEpisodesOnBothDeviceModes() throws Exception {
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(RuntimeEnvironment.getApplication());
        for (String provider : new String[]{fun.nyama.tv.Config.PLAYER_1, fun.nyama.tv.Config.PLAYER_2}) {
            config.setMovieSeriesPlayer(provider);
            for (boolean phone : new boolean[]{false, true}) {
                for (Intent selection : new Intent[]{title("movies", MOVIE), episode(3, 7)}) {
                    ActivityController<MoviePlayerActivity> lifecycle = open(phone, selection);
                    MoviePlayerActivity activity = lifecycle.get();
                    Uri selected = Uri.parse(url(activity));
                    assertEquals("https", selected.getScheme());
                    assertEquals(fun.nyama.tv.Config.PLAYER_1.equals(provider) ? "vidrock.to" : "vaplayer.ru", selected.getHost());
                    assertEquals(selection.hasExtra("episode") ? "/embed/tv/" + SERIES + "/3/7" : "/embed/movie/" + MOVIE, selected.getPath());
                    if (fun.nyama.tv.Config.PLAYER_1.equals(provider)) {
                        assertNull(selected.getQuery());
                        assertNull(field("episodeProgress").get(activity)); // Never trust Player 1 as a Player 2 bridge origin.
                    } else assertEquals("en", selected.getQueryParameter("ds_lang"));
                    assertFalse(views(activity.getWindow().getDecorView()).stream()
                            .anyMatch(view -> "mobile-bottom-navigation".equals(view.getTag())));
                    if (!phone) {
                        RecordingWebView recording = recordProviderKeys(activity);
                        press(activity, KeyEvent.KEYCODE_DPAD_CENTER); assertKeyPair(recording, KeyEvent.KEYCODE_SPACE);
                        press(activity, KeyEvent.KEYCODE_DPAD_LEFT); assertKeyPair(recording, KeyEvent.KEYCODE_DPAD_LEFT);
                        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT); assertKeyPair(recording, KeyEvent.KEYCODE_DPAD_RIGHT);
                    }
                    destroy(lifecycle);
                }
            }
        }
    }

    @Test public void playerChoiceChangesNextPlaybackWithoutRetargetingAnOpenSession() throws Exception {
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(RuntimeEnvironment.getApplication());
        for (boolean phone : new boolean[]{false, true}) {
            config.setMovieSeriesPlayer(fun.nyama.tv.Config.PLAYER_2);
            ActivityController<MoviePlayerActivity> first = open(phone, "movies");
            String original = url(first.get());
            config.setMovieSeriesPlayer(fun.nyama.tv.Config.PLAYER_1);
            assertEquals(original, url(first.get()));
            WebView target = web(first.get());
            assertTrue(Shadows.shadowOf(target).getWebViewClient().shouldOverrideUrlLoading(target, "https://vidrock.to/embed/movie/" + MOVIE));
            destroy(first);
            ActivityController<MoviePlayerActivity> second = open(phone, episode(2, 3));
            assertEquals("https://vidrock.to/embed/tv/" + SERIES + "/2/3", url(second.get()));
            destroy(second);
        }
    }

    @Test public void selectedOriginAndExplicitIdentityCannotBeBroadenedByRedirects() throws Exception {
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(RuntimeEnvironment.getApplication());
        for (String provider : new String[]{fun.nyama.tv.Config.PLAYER_1, fun.nyama.tv.Config.PLAYER_2}) {
            config.setMovieSeriesPlayer(provider);
            String host = fun.nyama.tv.Config.PLAYER_1.equals(provider) ? "vidrock.to" : "vaplayer.ru";
            String other = fun.nyama.tv.Config.PLAYER_1.equals(provider) ? "vaplayer.ru" : "vidrock.to";
            for (boolean phone : new boolean[]{false, true}) {
                ActivityController<MoviePlayerActivity> lifecycle = open(phone, episode(2, 3));
                WebView target = web(lifecycle.get()); WebViewClient client = Shadows.shadowOf(target).getWebViewClient();
                String path = "/embed/tv/" + SERIES + "/2/3";
                assertFalse(client.shouldOverrideUrlLoading(target, "https://" + host + path));
                for (String blocked : new String[]{"http://" + host + path, "https://" + other + path,
                        "https://user@" + host + path, "https://" + host + ":8443" + path,
                        "https://sub." + host + path, "https://" + host + ".evil.invalid" + path,
                        "https://" + host + "/embed/tv/" + SERIES + "/2/4", "https://" + host + "/embed/tv/" + SERIES})
                    assertTrue(blocked, client.shouldOverrideUrlLoading(target, blocked));
                destroy(lifecycle);
            }
        }
    }

    @Test public void explicitInvalidMobileEpisodeCannotFallBackToGenericOrOtherTitle() throws Exception {
        new fun.nyama.tv.Config(RuntimeEnvironment.getApplication()).setDeviceMode(fun.nyama.tv.Config.MODE_PHONE);
        for (Intent bad : new Intent[]{episode(2, 3).putExtra("series_imdb_id", "tt0903747"), episode(2, -1)}) {
            ActivityController<MoviePlayerActivity> lifecycle = Robolectric.buildActivity(MoviePlayerActivity.class, bad).create();
            assertNull(url(lifecycle.get())); assertTrue(lifecycle.get().isFinishing());
            lifecycle.destroy();
        }
    }

    @Test public void topLevelNavigationOnlyAllowsTheSelectedProvider() throws Exception {
        MoviePlayerActivity tv = open(false, "movies").get();
        WebView tvWeb = web(tv);
        WebViewClient tvClient = Shadows.shadowOf(tvWeb).getWebViewClient();
        assertFalse(tvClient.shouldOverrideUrlLoading(tvWeb, "https://vaplayer.ru/embed/movie/" + MOVIE));
        assertTrue(tvClient.shouldOverrideUrlLoading(tvWeb, "https://streamimdb.ru/embed/movie/" + MOVIE));
        assertTrue(tvClient.shouldOverrideUrlLoading(tvWeb, "https://vaplayer.ru.attacker.invalid/embed/movie/" + MOVIE));
        MoviePlayerActivity phone = open(true, "movies").get();
        WebView phoneWeb = web(phone);
        WebViewClient phoneClient = Shadows.shadowOf(phoneWeb).getWebViewClient();
        assertFalse(phoneClient.shouldOverrideUrlLoading(phoneWeb, "https://vaplayer.ru/embed/movie/" + MOVIE));
        assertTrue(phoneClient.shouldOverrideUrlLoading(phoneWeb, "https://streamimdb.ru/embed/movie/" + MOVIE));
    }
    @Test public void genericSeriesRouteKeepsProviderSubtitleControlsWithoutGuessingEpisode() throws Exception {
        MoviePlayerActivity activity = open(false, "series").get();
        String before = Shadows.shadowOf(web(activity)).getLastEvaluatedJavascript();
        ReflectionHelpers.callInstanceMethod(activity, "installTvPlaybackAssist");
        assertEquals(before, Shadows.shadowOf(web(activity)).getLastEvaluatedJavascript());
    }

    @Test public void tvMovieAndEpisodeExitRequestOneAsyncPlaylistRefreshEach() throws Exception {
        java.util.concurrent.Executor previous = PlaylistReturnRefresh.io;
        java.util.ArrayDeque<Runnable> jobs = new java.util.ArrayDeque<>();
        int[] downloads = {0};
        java.lang.reflect.Field instance = MainActivity.class.getDeclaredField("activeInstance");
        instance.setAccessible(true); Object oldInstance = instance.get(null);
        instance.set(null, new java.lang.ref.WeakReference<MainActivity>(null));
        PlaylistReturnRefresh.io = jobs::add;
        PlaylistReturnRefresh.refresher = context -> { downloads[0]++; return java.util.Collections.emptyList(); };
        try {
            for (Intent selection : new Intent[]{title("movies", MOVIE), episode(2, 3)}) {
                ActivityController<MoviePlayerActivity> lifecycle = open(false, selection);
                MoviePlayerActivity activity = lifecycle.get();
                ReflectionHelpers.callInstanceMethod(activity, "finishTvPlayback");
                ReflectionHelpers.callInstanceMethod(activity, "finishTvPlayback");
                assertEquals(1, jobs.size());
                destroy(lifecycle);
                assertEquals(1, jobs.size());
                jobs.remove().run();
            }
            assertEquals(2, downloads[0]);
        } finally {
            while (!jobs.isEmpty()) jobs.remove().run();
            PlaylistReturnRefresh.io = previous;
            PlaylistReturnRefresh.consumeReturn();
            instance.set(null, oldInstance);
        }
    }

    /** Models only Android's render acknowledgement; never claims provider media is playable. */
    private static final class VisualWebView extends WebView {
        VisualStateCallback visualCallback;
        VisualWebView(Context context) { super(context); }
        @Override public void postVisualStateCallback(long requestId, VisualStateCallback callback) {
            visualCallback = callback;
        }

    }

    private static VisualWebView captureVisualState(MoviePlayerActivity activity) throws Exception {
        WebView old = web(activity);
        ViewGroup parent = (ViewGroup) old.getParent();
        int index = parent.indexOfChild(old);
        ViewGroup.LayoutParams params = old.getLayoutParams();
        String selected = url(activity);
        parent.removeView(old);
        old.destroy();
        VisualWebView target = new VisualWebView(activity);
        field("webView").set(activity, target);
        ReflectionHelpers.callInstanceMethod(activity, "configureWebView",
                ReflectionHelpers.ClassParameter.from(WebView.class, target));
        parent.addView(target, index, params);
        target.loadUrl(selected);
        layoutPlayer(activity);
        return target;
    }

    private static void assertNoLoadingOverlay(MoviePlayerActivity activity) throws Exception {
        ViewGroup root = (ViewGroup) field("root").get(activity);
        for (View child : views(root)) {
            if (child instanceof TextView) {
                String text = ((TextView) child).getText().toString();
                assertFalse(text.contains("Loading") || text.contains("Зареждане"));
            }
        }
        assertEquals("Only the WebView and hidden fullscreen container belong on the TV surface", 2, root.getChildCount());
        assertSame(web(activity), root.getChildAt(0));
        assertEquals(View.VISIBLE, web(activity).getVisibility());
        assertEquals(View.GONE, root.getChildAt(1).getVisibility());
    }

    @Test public void tvMovieAndEpisodeAreVisibleWithoutPageOrVisualCallbacks() throws Exception {
        for (Intent selection : new Intent[] {title("movies", MOVIE), episode(2, 3)}) {
            MoviePlayerActivity activity = open(false, selection).get();
            assertNoLoadingOverlay(activity);
            VisualWebView target = captureVisualState(activity);
            WebViewClient client = Shadows.shadowOf(target).getWebViewClient();
            client.onPageCommitVisible(target, url(activity));
            client.onPageFinished(target, url(activity));
            assertNull("Provider visibility must never wait for a render acknowledgement", target.visualCallback);
            assertNoLoadingOverlay(activity);
            assertNoTvPlaybackBar(activity);
        }
    }

    @Test public void staleCommitCannotCoverOrReloadAReplacementPlayer() throws Exception {
        MoviePlayerActivity activity = open(false, episode(2, 3)).get();
        VisualWebView failed = captureVisualState(activity);
        WebViewClient client = Shadows.shadowOf(failed).getWebViewClient();
        assertTrue(client.onRenderProcessGone(failed, null));
        idle();
        WebView replacement = web(activity);
        String selected = url(activity);
        client.onPageCommitVisible(failed, selected);
        assertNull(failed.visualCallback);
        assertSame(replacement, web(activity));
        assertEquals(selected, url(activity));
        assertNoLoadingOverlay(activity);
    }

    @Test public void explicitRendererLossRecoversOnlyOnceUsingTheExactSelectedEpisode() throws Exception {
        MoviePlayerActivity activity = open(false, episode(2, 3)).get();
        String selected = url(activity);
        WebView failed = web(activity);
        assertTrue(Shadows.shadowOf(failed).getWebViewClient().onRenderProcessGone(failed, null));
        idle();
        layoutPlayer(activity);
        idle();
        WebView replacement = web(activity);
        assertNotNull(replacement);
        assertNotSame(failed, replacement);
        assertTrue(Shadows.shadowOf(failed).wasDestroyCalled());
        assertEquals("Dead renderers must not be navigated to about:blank", selected,
                Shadows.shadowOf(failed).getLastLoadedUrl());
        assertEquals(selected, url(activity));
        assertTrue("A replacement in a resumed Activity must resume global WebView timers",
                Shadows.shadowOf(replacement).wasOnResumeCalled());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        assertTrue(Shadows.shadowOf(replacement).getWebViewClient().onRenderProcessGone(replacement, null));
        idle();
        assertNull(web(activity));
        assertNotNull(screenButton(activity, "Try again"));
        assertNotNull(screenButton(activity, "Back to episodes"));
        screenButton(activity, "Try again").performClick();
        layoutPlayer(activity);
        idle();
        assertEquals(selected, url(activity));
        WebView manualRetry = web(activity);
        assertTrue(Shadows.shadowOf(manualRetry).getWebViewClient().onRenderProcessGone(manualRetry, null));
        idle();
        assertNull("Explicit Retry must not create an endless automatic recovery loop", web(activity));
        assertNotNull(screenButton(activity, "Try again"));
    }

    @Test public void rendererRecoveryWaitsUntilTheTvActivityResumes() throws Exception {
        ActivityController<MoviePlayerActivity> lifecycle = open(false, episode(2, 3));
        MoviePlayerActivity activity = lifecycle.get();
        String selected = url(activity);
        WebView failed = web(activity);
        lifecycle.pause();
        assertTrue(Shadows.shadowOf(failed).getWebViewClient().onRenderProcessGone(failed, null));
        idle();
        assertNull(web(activity));
        assertTrue(flag(activity, "rendererRecoveryPending"));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        lifecycle.resume();
        layoutPlayer(activity);
        idle();
        assertNotNull(web(activity));
        assertEquals(selected, url(activity));
        assertFalse(flag(activity, "rendererRecoveryPending"));
    }

    @Test public void tvNavigatesDuringCreateBeforeResumeOrFirstLayout() throws Exception {
        new fun.nyama.tv.Config(RuntimeEnvironment.getApplication()).setDeviceMode(fun.nyama.tv.Config.MODE_TV);
        for (Intent selection : new Intent[] {title("movies", MOVIE), episode(3, 7)}) {
            ActivityController<MoviePlayerActivity> lifecycle = Robolectric.buildActivity(MoviePlayerActivity.class, selection)
                    .create();
            MoviePlayerActivity activity = lifecycle.get();
            assertNotNull("Navigation must not wait for resume, layout, or a posted runnable", url(activity));
            assertEquals(0, web(activity).getWidth());
            assertNoLoadingOverlay(activity);
            String selected = url(activity);
            lifecycle.start().resume().visible();
            activities.add(lifecycle);
            lifecycle.pause().resume();
            idle();
            assertEquals(selected, url(activity));
        }
    }

    @Test public void mobileNavigatesDuringCreateBeforeResumeOrLayoutWithSavedProvider() throws Exception {
        new fun.nyama.tv.Config(RuntimeEnvironment.getApplication()).setDeviceMode(fun.nyama.tv.Config.MODE_PHONE);
        for (String type : new String[]{"movies", "series"}) {
            ActivityController<MoviePlayerActivity> lifecycle = Robolectric.buildActivity(MoviePlayerActivity.class,
                    title(type, "movies".equals(type) ? MOVIE : SERIES)).create();
            MoviePlayerActivity activity = lifecycle.get();
            assertVapRoute(activity, "movies".equals(type) ? "/embed/movie/" + MOVIE : "/embed/tv/" + SERIES, "en");
            assertEquals(0, web(activity).getWidth());
            lifecycle.start().resume().visible(); activities.add(lifecycle);
        }
    }

    @Test public void sameProviderWrongDocumentFinishesWithRetryRatherThanPermanentLoadingCover() throws Exception {
        MoviePlayerActivity activity = open(false, episode(2, 3)).get();
        WebView target = web(activity);
        Shadows.shadowOf(target).getWebViewClient().onPageFinished(target, "https://vaplayer.ru/asb.html");
        idle();
        assertNull(web(activity));
        assertNotNull(screenButton(activity, "Try again"));
        assertTrue(visibleText(activity).contains("selected video"));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void tvCustomFullscreenUsesDarkPosterWithoutNewControls() throws Exception {
        MoviePlayerActivity activity = open(false, episode(2, 3)).get();
        WebView target = web(activity);
        WebChromeClient chrome = Shadows.shadowOf(target).getWebChromeClient();
        assertEquals(Color.BLACK, chrome.getDefaultVideoPoster().getPixel(0, 0));
        chrome.onShowCustomView(new View(activity), () -> {});
        idle();
        assertEquals(View.VISIBLE, web(activity).getVisibility());
        assertNoTvPlaybackBar(activity);
    }

    @Test public void staleFullscreenHideCannotDismissReplacementFullscreen() throws Exception {
        MoviePlayerActivity activity = open(false, episode(2, 3)).get();
        WebView failed = web(activity);
        WebChromeClient staleChrome = Shadows.shadowOf(failed).getWebChromeClient();
        final boolean[] failedCallback = { false };
        staleChrome.onShowCustomView(new View(activity), () -> failedCallback[0] = true);
        Shadows.shadowOf(failed).getWebViewClient().onRenderProcessGone(failed, null);
        idle();
        layoutPlayer(activity);
        idle();
        assertFalse("A dead renderer's fullscreen callback must not be invoked", failedCallback[0]);
        View currentFullscreen = new View(activity);
        Shadows.shadowOf(web(activity)).getWebChromeClient().onShowCustomView(currentFullscreen, () -> {});
        staleChrome.onHideCustomView();
        assertSame(currentFullscreen, field("customView").get(activity));
    }

    @Test public void phoneKeepsImmediateLoadingAndDefaultRendererFailureBehavior() throws Exception {
        MoviePlayerActivity activity = open(true, "movies").get();
        WebView target = web(activity);

        assertFalse(Shadows.shadowOf(target).getWebViewClient().onRenderProcessGone(target, null));
        assertSame(target, web(activity));
        assertFalse(Shadows.shadowOf(target).wasDestroyCalled());
        assertVapRoute(activity, "/embed/movie/" + MOVIE, "en");
        assertNotNull(controls(activity));
    }

    @Test @Config(sdk = 24) public void android7TvMovieRetainsExactRouteWithoutRenderGate() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        assertVapRoute(activity, "/embed/movie/" + MOVIE, "en");
        VisualWebView target = captureVisualState(activity);
        Shadows.shadowOf(target).getWebViewClient().onPageCommitVisible(target, url(activity));
        assertNull(target.visualCallback);
        assertNoLoadingOverlay(activity);
        assertNoTvPlaybackBar(activity);
    }

    /** JavaScript replies are deliberately held while more physical remote presses arrive. */
    private static final class ControlledSeekWebView extends WebView {
        final ArrayDeque<ValueCallback<String>> seeks = new ArrayDeque<>(), focus = new ArrayDeque<>();
        final List<String> scripts = new ArrayList<>();
        final List<KeyEvent> keys = new ArrayList<>();
        int focusCalls, loads;
        boolean automaticFocus = true;
        boolean rejectProbe, rejectFocus;
        int failAfterNativeKeys;
        ControlledSeekWebView(Context context) { super(context); }
        @Override public void loadUrl(String url) { loads++; super.loadUrl(url); }
        @Override public void evaluateJavascript(String script, ValueCallback<String> callback) {
            if (callback == null) return;
            if (script.contains("__nyamaTvSeek")) {
                if (rejectProbe) throw new IllegalStateException("Synthetic unavailable renderer");
                scripts.add(script); seeks.add(callback);
            }
            else if (script.contains("document.activeElement===best")) {
                if (rejectFocus) throw new IllegalStateException("Synthetic unavailable renderer");
                focusCalls++;
                if (automaticFocus) callback.onReceiveValue("\"1\"");
                else focus.add(callback);
            } else callback.onReceiveValue("\"0\"");
        }
        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            keys.add(event);
            if (failAfterNativeKeys > 0 && keys.size() >= failAfterNativeKeys)
                throw new IllegalStateException("Synthetic unavailable input surface");
            return true;
        }
        void answer(String state, double position, double duration, boolean paused) throws Exception {
            assertFalse("There must be an actual pending JavaScript probe", seeks.isEmpty());
            String data = new org.json.JSONObject().put("state", state).put("position", position)
                    .put("duration", duration).put("paused", paused).toString();
            seeks.removeFirst().onReceiveValue(org.json.JSONObject.quote(data));
        }
    }

    private static ControlledSeekWebView controlled(MoviePlayerActivity activity) throws Exception {
        WebView old = web(activity);
        String selected = url(activity);
        ViewGroup parent = (ViewGroup) old.getParent();
        ViewGroup.LayoutParams params = old.getLayoutParams();
        int index = parent.indexOfChild(old);
        TvPlayerProgressBridge bridge = (TvPlayerProgressBridge) field("episodeProgress").get(activity);
        if (bridge != null) bridge.close();
        field("episodeProgress").set(activity, null);
        parent.removeView(old); old.destroy();
        ControlledSeekWebView target = new ControlledSeekWebView(activity);
        field("webView").set(activity, target);
        ReflectionHelpers.callInstanceMethod(activity, "configureWebView",
                ReflectionHelpers.ClassParameter.from(WebView.class, target));
        parent.addView(target, index, params);
        target.loadUrl(selected); target.requestFocus(); layoutPlayer(activity);
        return target;
    }

    private static void rapidRight(MoviePlayerActivity activity, int count) {
        for (int index = 0; index < count; index++) {
            long now = SystemClock.uptimeMillis();
            activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0));
            activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0));
        }
    }

    @Test public void tenRapidMovieSeeksWaitForConfirmationAndKeepCumulativeDistanceWithoutReload() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        ControlledSeekWebView target = controlled(activity);
        String selected = url(activity);
        rapidRight(activity, 10);
        assertEquals(1, target.scripts.size()); assertEquals(1, target.seeks.size());
        assertTrue(target.scripts.get(0).contains("p+(10)"));
        target.answer("waiting", 40, 600, false);
        assertEquals(1, target.scripts.size());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
        assertEquals(2, target.scripts.size());
        assertTrue(target.scripts.get(1).contains("if(s.before)"));
        target.answer("settled", 40, 600, false); idle();
        assertEquals(3, target.scripts.size());
        assertTrue(target.scripts.get(2).contains("p+(90)"));
        target.answer("settled", 130, 600, false); idle();
        assertTrue(target.keys.isEmpty()); assertTrue(target.seeks.isEmpty());
        assertEquals(1, target.loads); assertEquals(selected, url(activity));
        assertFalse(flag(activity, "rendererRecoveryUsed"));
    }

    @Test public void rapidCrossOriginMovieAndEpisodeSeeksUseOneFocusAndExactlyOneKeyPairPerPress() throws Exception {
        for (Intent intent : new Intent[]{title("movies", MOVIE), episode(2, 3)}) {
            MoviePlayerActivity activity = open(false, intent).get();
            ControlledSeekWebView target = controlled(activity);
            String selected = url(activity);
            rapidRight(activity, 10);
            target.answer("inaccessible", 0, 1, false); idle();
            assertEquals(1, target.scripts.size()); assertEquals(1, target.focusCalls);
            assertEquals(20, target.keys.size());
            for (int index = 0; index < 20; index++) {
                assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, target.keys.get(index).getKeyCode());
                assertEquals(index % 2 == 0 ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                        target.keys.get(index).getAction());
            }
            assertEquals(selected, url(activity)); assertEquals(1, target.loads);
            assertFalse(flag(activity, "rendererRecoveryUsed"));
        }
    }

    @Test public void delayedNativeFocusCannotDispatchAfterPauseAndNewBurstWorksAfterResume() throws Exception {
        ActivityController<MoviePlayerActivity> lifecycle = open(false, episode(2, 3));
        MoviePlayerActivity activity = lifecycle.get();
        ControlledSeekWebView target = controlled(activity); target.automaticFocus = false;
        rapidRight(activity, 5); target.answer("inaccessible", 0, 1, false);
        assertEquals(1, target.focus.size());
        lifecycle.pause();
        target.focus.removeFirst().onReceiveValue("\"1\""); idle();
        assertTrue(target.keys.isEmpty());
        lifecycle.resume(); rapidRight(activity, 1);
        target.answer("inaccessible", 0, 1, false);
        target.focus.removeFirst().onReceiveValue("\"1\""); idle();
        assertEquals(2, target.keys.size()); assertEquals(1, target.loads);
    }

    @Test public void unconfirmedDirectAssignmentNeverSendsNativeSeekOrReloadsThePlayer() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        ControlledSeekWebView target = controlled(activity);
        rapidRight(activity, 5); target.answer("failed", 0, 1, false); idle();
        assertTrue(target.keys.isEmpty()); assertEquals(0, target.focusCalls);
        assertEquals(1, target.loads); assertEquals(1, target.scripts.size());
        assertFalse(flag(activity, "rendererRecoveryUsed"));
        rapidRight(activity, 1);
        assertEquals(2, target.scripts.size()); // Another explicit action can retry; no automatic replay.
    }

    @Test public void unconfirmedProviderFocusCannotTurnRapidSeekIntoOuterPageNavigation() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        ControlledSeekWebView target = controlled(activity); target.automaticFocus = false;
        String selected = url(activity);
        rapidRight(activity, 5); target.answer("inaccessible", 0, 1, false);
        target.focus.removeFirst().onReceiveValue("\"0\""); idle();
        assertTrue(target.keys.isEmpty()); assertEquals(1, target.loads);
        assertEquals(selected, url(activity)); assertSame(target, web(activity));
    }

    @Test public void rendererRecoveryUsesOnlyFreshConfirmedPositionAndKeepsEpisodeSubtitlesAndPausedState() throws Exception {
        MoviePlayerActivity activity = open(false, episode(2, 3)).get();
        ControlledSeekWebView target = controlled(activity);
        rapidRight(activity, 1); target.answer("settled", 120.75, 600, true); idle();
        WebViewClient client = Shadows.shadowOf(target).getWebViewClient();
        assertTrue(client.onRenderProcessGone(target, null)); idle(); layoutPlayer(activity); idle();
        Uri resumed = Uri.parse(url(activity));
        assertEquals("/embed/tv/" + SERIES + "/2/3", resumed.getPath());
        assertEquals("en", resumed.getQueryParameter("ds_lang"));
        assertEquals("120", resumed.getQueryParameter("resumeAt"));
        assertEquals("0", resumed.getQueryParameter("autoplay"));
        assertTrue(Shadows.shadowOf(target).wasDestroyCalled());
        assertNotSame(target, web(activity));
        assertEquals(1, views(activity.findViewById(android.R.id.content)).stream()
                .filter(view -> view instanceof WebView).count());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void stalePositionIsNotInventedWhenRendererRecoveryStarts() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        ControlledSeekWebView target = controlled(activity);
        rapidRight(activity, 1); target.answer("settled", 120, 600, false); idle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16));
        Shadows.shadowOf(target).getWebViewClient().onRenderProcessGone(target, null);
        idle(); layoutPlayer(activity); idle();
        assertNull(Uri.parse(url(activity)).getQueryParameter("resumeAt"));
    }

    @Test public void confirmedMovieExitReturnsResultWithoutStartingAnotherCatalogOrMainActivity() throws Exception {
        MoviePlayerActivity activity = open(false, title("movies", MOVIE)
                .putExtra(MoviePlayerActivity.EXTRA_RETURN_TO_CATALOG, true)).get();
        WebView target = web(activity);
        press(activity, KeyEvent.KEYCODE_BACK); advanceBetweenBackPresses(); press(activity, KeyEvent.KEYCODE_BACK);
        assertTrue(activity.isFinishing()); assertNull(web(activity));
        assertTrue(Shadows.shadowOf(target).wasDestroyCalled());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        assertEquals(android.app.Activity.RESULT_OK, Shadows.shadowOf(activity).getResultCode());
        assertTrue(Shadows.shadowOf(activity).getResultIntent()
                .getBooleanExtra(MoviePlayerActivity.EXTRA_CONFIRMED_TV_EXIT, false));
    }

    @Test public void rendererRejectingTheSeekProbeDoesNotCrashOrBlindlySendNativeKeys() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        ControlledSeekWebView target = controlled(activity); target.rejectProbe = true;
        rapidRight(activity, 5); idle();
        assertTrue(target.keys.isEmpty()); assertSame(target, web(activity));
        assertEquals(1, target.loads); assertFalse(flag(activity, "rendererRecoveryUsed"));
    }

    @Test public void rendererRejectingFocusDoesNotCrashOrDispatchToUnverifiedOuterPage() throws Exception {
        MoviePlayerActivity activity = open(false, episode(2, 3)).get();
        ControlledSeekWebView target = controlled(activity); target.rejectFocus = true;
        rapidRight(activity, 5); target.answer("inaccessible", 0, 1, false); idle();
        assertTrue(target.keys.isEmpty()); assertSame(target, web(activity)); assertEquals(1, target.loads);
    }

    @Test public void partiallyDeliveredNativePairIsNotReplayedWhenTheSurfaceFails() throws Exception {
        MoviePlayerActivity activity = open(false, "movies").get();
        ControlledSeekWebView target = controlled(activity); target.failAfterNativeKeys = 1;
        rapidRight(activity, 10); target.answer("inaccessible", 0, 1, false); idle();
        assertEquals(1, target.keys.size()); assertSame(target, web(activity));
        assertEquals(1, target.loads); assertFalse(flag(activity, "rendererRecoveryUsed"));
    }

}
