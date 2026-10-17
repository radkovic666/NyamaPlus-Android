package fun.nyama.tv;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.webkit.WebView;

import androidx.media3.exoplayer.ExoPlayer;

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
import org.robolectric.annotation.LooperMode;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.*;

/** Mocked provider messages and local persistence only; no live media or remote hardware. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TvEpisodeProgressTest {
    private static final String IMDB = "tt0944947";
    private Context context;
    private TvEpisodeProgressStore store;

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(TvEpisodeProgressStore.PREFERENCES, 0).edit().clear().commit();
        context.getSharedPreferences("nyama_tv_private", 0).edit().clear().commit();
        context.getSharedPreferences("nyama_activation_mode", 0).edit().clear().commit();
        ActivationAccess.deny();
        store = new TvEpisodeProgressStore(context);
    }

    @After public void after() { ActivationAccess.deny(); }

    private static TvPlayerProgressBridge.Event event(String status, double position, double duration) {
        return new TvPlayerProgressBridge.Event(IMDB, 2, 3, status, position, duration);
    }

    private TvEpisodeProgressTracker tracker() { return store.load(IMDB, 2, 3); }

    private static void play(TvEpisodeProgressTracker tracker, int endSeconds, int duration) {
        for (int position = 0; position <= endSeconds; position += 5)
            tracker.accept(event("playing", position, duration), position * 1000L);
    }

    private static String payload(String status, Object position, Object duration) throws Exception {
        return new JSONObject().put("type", "PLAYER_EVENT")
                .put("data", new JSONObject().put("player_info", new JSONObject().put("imdb", IMDB)
                        .put("mediaType", "tv").put("season", 2).put("episode", 3))
                        .put("player_status", status).put("player_progress", position).put("player_duration", duration)).toString();
    }

    @Test public void unwatchedOrUnknownEpisodeHasNoFlag() {
        assertFalse(store.get(IMDB, 2, 3).inProgress);
        assertFalse(store.get(IMDB, 2, 3).watched);
        assertFalse(store.get("not-an-imdb", 2, 3).watched);
        assertFalse(store.get(IMDB, -1, 3).inProgress);
    }

    @Test public void tenSecondsOfVerifiedContinuousViewingIsInProgress() {
        TvEpisodeProgressTracker tracker = tracker();
        play(tracker, 5, 100);
        assertFalse(tracker.snapshot().inProgress);
        tracker.accept(event("playing", 10, 100), 10_000);
        assertTrue(tracker.snapshot().inProgress);
        assertFalse(tracker.snapshot().watched);
        assertEquals(.1, tracker.snapshot().watchedFraction, .00001);
    }

    @Test public void ninetyPercentMeasuredCoverageIsWatched() {
        TvEpisodeProgressTracker tracker = tracker();
        play(tracker, 85, 100);
        assertFalse(tracker.snapshot().watched);
        tracker.accept(event("playing", 90, 100), 90_000);
        assertTrue(tracker.snapshot().watched);
        assertFalse(tracker.snapshot().inProgress);
        assertEquals(.9, tracker.snapshot().watchedFraction, .00001);
    }

    @Test public void seekStraightToNinetyPercentDoesNotCreateCoverage() {
        TvEpisodeProgressTracker tracker = tracker();
        tracker.accept(event("playing", 0, 100), 0);
        tracker.accept(event("seeked", 90, 100), 1000);
        tracker.accept(event("playing", 90, 100), 1100);
        tracker.accept(event("completed", 100, 100), 11_100);
        assertFalse(tracker.snapshot().watched);
        assertTrue(tracker.snapshot().inProgress);
        assertEquals(.1, tracker.snapshot().watchedFraction, .00001);
    }

    @Test public void completedMeansReachedEndAndCannotByItselfProveWatched() {
        TvEpisodeProgressTracker tracker = tracker();
        tracker.accept(event("completed", 100, 100), 0);
        assertFalse(tracker.snapshot().watched);
        assertFalse(tracker.snapshot().inProgress);
        assertEquals(0, tracker.snapshot().watchedFraction, 0);
    }

    @Test public void unannouncedLargePositionJumpIsNotWatchedTime() {
        TvEpisodeProgressTracker tracker = tracker();
        tracker.accept(event("playing", 0, 100), 0);
        tracker.accept(event("playing", 95, 100), 5000);
        assertEquals(0, tracker.snapshot().watchedFraction, 0);
    }

    @Test public void nativeSeekBreaksCoverageEvenIfProviderOmitsSeekEvent() {
        TvEpisodeProgressTracker tracker = tracker();
        play(tracker, 10, 100);
        tracker.breakContinuity();
        tracker.accept(event("playing", 20, 100), 20_000);
        assertEquals(.1, tracker.snapshot().watchedFraction, .00001);
    }

    @Test public void lateCallbacksAndBackgroundTimeAreNotCredited() {
        TvEpisodeProgressTracker tracker = tracker();
        tracker.accept(event("playing", 0, 100), 0);
        tracker.accept(event("playing", 40, 100), 40_000);
        tracker.breakContinuity();
        tracker.accept(event("playing", 60, 100), 60_000);
        assertEquals(0, tracker.snapshot().watchedFraction, 0);
    }

    @Test public void duplicateAndBackwardsClockEventsCannotInventCoverage() {
        TvEpisodeProgressTracker tracker = tracker();
        tracker.accept(event("playing", 0, 100), 10_000);
        tracker.accept(event("playing", 5, 100), 10_000);
        tracker.accept(event("playing", 10, 100), 5000);
        assertEquals(0, tracker.snapshot().watchedFraction, 0);
    }

    @Test public void overlappingRewatchesAreCountedOnce() {
        TvEpisodeProgressTracker tracker = tracker();
        play(tracker, 50, 100);
        tracker.accept(event("seeked", 0, 100), 51_000);
        for (int position = 0; position <= 50; position += 5)
            tracker.accept(event("playing", position, 100), 52_000 + position * 1000L);
        assertEquals(.5, tracker.snapshot().watchedFraction, .00001);
        assertFalse(tracker.snapshot().watched);
    }

    @Test public void pausedEventCreditsOnlyTheVerifiedLastPlayingInterval() {
        TvEpisodeProgressTracker tracker = tracker();
        tracker.accept(event("playing", 0, 100), 0);
        tracker.accept(event("paused", 10, 100), 10_000);
        tracker.accept(event("paused", 50, 100), 50_000);
        assertEquals(.1, tracker.snapshot().watchedFraction, .00001);
    }

    @Test public void materialDurationChangeDiscardsUnrelatedRenditionCoverage() {
        TvEpisodeProgressTracker tracker = tracker();
        play(tracker, 90, 100);
        tracker.accept(event("playing", 5, 120), 95_000);
        assertEquals(0, tracker.snapshot().watchedFraction, 0);
        assertFalse(tracker.snapshot().watched);
    }

    @Test public void coverageNeverExceedsElapsedMonotonicTime() {
        TvEpisodeProgressTracker tracker = tracker();
        tracker.accept(event("playing", 0, 100), 0);
        tracker.accept(event("playing", 5.5, 100), 5000);
        assertEquals(.05, tracker.snapshot().watchedFraction, .00001);
    }

    @Test public void progressPersistsWithASeparateStoreAfterAppRestartOrUpdate() {
        TvEpisodeProgressTracker tracker = tracker();
        play(tracker, 35, 100);
        store.save(tracker);
        TvEpisodeProgressStore recreated = new TvEpisodeProgressStore(context);
        assertTrue(recreated.get(IMDB, 2, 3).inProgress);
        assertEquals(35, recreated.get(IMDB, 2, 3).positionSeconds, .00001);
        assertEquals(.35, recreated.get(IMDB, 2, 3).watchedFraction, .00001);
        TvEpisodeProgressTracker resumed = recreated.load(IMDB, 2, 3);
        resumed.accept(event("playing", 90, 100), 100_000);
        assertEquals(.35, resumed.snapshot().watchedFraction, .00001);
    }

    @Test public void episodeAndSeriesIdentitiesHaveIndependentPersistentKeys() {
        TvEpisodeProgressTracker tracker = tracker();
        play(tracker, 90, 100);
        store.save(tracker);
        assertTrue(store.get(IMDB, 2, 3).watched);
        assertFalse(store.get(IMDB, 2, 4).watched);
        assertFalse(store.get(IMDB, 1, 3).watched);
        assertFalse(store.get("tt0903747", 2, 3).watched);
    }

    @Test public void malformedPersistedIntervalsCannotCreateWatchFlags() {
        context.getSharedPreferences(TvEpisodeProgressStore.PREFERENCES, 0).edit().putString(IMDB + ":2:3",
                "{\"version\":1,\"imdb\":\"" + IMDB + "\",\"season\":2,\"episode\":3,\"position\":99,\"duration\":100,\"intervals\":[[0,999999]]}").commit();
        assertFalse(store.get(IMDB, 2, 3).watched);
        assertFalse(store.get(IMDB, 2, 3).inProgress);
    }

    @Test public void missingMeasurementsDoNotCreateAnyStoredHistory() {
        store.save(tracker());
        assertTrue(context.getSharedPreferences(TvEpisodeProgressStore.PREFERENCES, 0).getAll().isEmpty());
    }

    @Test public void exactDocumentedIdentityAndNumericPayloadIsAccepted() throws Exception {
        TvPlayerProgressBridge.Event event = TvPlayerProgressBridge.parse(payload("playing", 12.5, 100), IMDB, 2, 3);
        assertNotNull(event);
        assertEquals(12.5, event.position, 0);
        assertNull(TvPlayerProgressBridge.parse(payload("playing", 12, 100), IMDB, 2, 4));
        assertNull(TvPlayerProgressBridge.parse(payload("playing", 12, 100), "tt0903747", 2, 3));
    }

    @Test public void wrongMediaTypeFractionalIdentityAndMissingIdentityAreRejected() throws Exception {
        JSONObject object = new JSONObject(payload("playing", 12, 100));
        JSONObject info = object.getJSONObject("data").getJSONObject("player_info");
        info.put("mediaType", "movie");
        assertNull(TvPlayerProgressBridge.parse(object.toString(), IMDB, 2, 3));
        info.put("mediaType", "tv").put("season", 2.5);
        assertNull(TvPlayerProgressBridge.parse(object.toString(), IMDB, 2, 3));
        info.put("season", 2).remove("imdb");
        assertNull(TvPlayerProgressBridge.parse(object.toString(), IMDB, 2, 3));
    }

    @Test public void nonfiniteNegativeHugeAndStringMeasurementsAreRejected() throws Exception {
        assertNull(TvPlayerProgressBridge.parse(payload("playing", "12", 100), IMDB, 2, 3));
        assertNull(TvPlayerProgressBridge.parse(payload("playing", -1, 100), IMDB, 2, 3));
        assertNull(TvPlayerProgressBridge.parse(payload("playing", 101, 100), IMDB, 2, 3));
        assertNull(TvPlayerProgressBridge.parse(payload("playing", 0, 0), IMDB, 2, 3));
        assertNull(TvPlayerProgressBridge.parse(payload("playing", 0, 99_999_999), IMDB, 2, 3));
        assertFalse(TvEpisodeProgressTracker.validMeasurement(Double.NaN, 100));
        assertFalse(TvEpisodeProgressTracker.validMeasurement(0, Double.POSITIVE_INFINITY));
    }

    @Test public void unknownMessageTypeStatusMalformedAndOversizedPayloadsAreRejected() throws Exception {
        assertNull(TvPlayerProgressBridge.parse(payload("buffering", 0, 100), IMDB, 2, 3));
        assertNull(TvPlayerProgressBridge.parse("not-json", IMDB, 2, 3));
        assertNull(TvPlayerProgressBridge.parse(new String(new char[TvPlayerProgressBridge.MAX_EVENT_CHARS + 1]), IMDB, 2, 3));
        assertNull(TvPlayerProgressBridge.parse(payload("playing", 0, 100).replace("PLAYER_EVENT", "STORAGE_SET"), IMDB, 2, 3));
    }

    @Test public void onlyExactHttpsMainOriginIsAccepted() {
        assertTrue(TvPlayerProgressBridge.exactOrigin(Uri.parse("https://vaplayer.ru"), TvPlayerProgressBridge.MAIN_ORIGIN));
        for (String untrusted : new String[] {"http://vaplayer.ru", "https://evil.vaplayer.ru", "https://vaplayer.ru.evil.test",
                "https://nextgencloudfabric.com", "https://user@vaplayer.ru", "https://vaplayer.ru:8443", "https://vaplayer.ru/path", "null"})
            assertFalse(untrusted, TvPlayerProgressBridge.exactOrigin(Uri.parse(untrusted), TvPlayerProgressBridge.MAIN_ORIGIN));
    }

    @Test public void nativeReceiverRejectsSubframesAndForeignOriginsWithoutWriting() throws Exception {
        TvPlayerProgressBridge bridge = new TvPlayerProgressBridge(null, store, IMDB, 2, 3);
        String paused = payload("paused", 95, 100);
        bridge.receive(paused, Uri.parse(TvPlayerProgressBridge.MAIN_ORIGIN), false, 0);
        bridge.receive(paused, Uri.parse(TvPlayerProgressBridge.FRAME_ORIGIN), true, 0);
        bridge.receive(paused, Uri.parse("https://evil.test"), true, 0);
        assertTrue(context.getSharedPreferences(TvEpisodeProgressStore.PREFERENCES, 0).getAll().isEmpty());
        bridge.close();
    }

    @Test public void trustedReceiverBatchesWritesAndPauseFlushesImmediately() throws Exception {
        TvPlayerProgressBridge bridge = new TvPlayerProgressBridge(null, store, IMDB, 2, 3);
        long now = SystemClock.elapsedRealtime();
        Uri origin = Uri.parse(TvPlayerProgressBridge.MAIN_ORIGIN);
        for (int p = 0; p <= 10; p += 5) bridge.receive(payload("playing", p, 100), origin, true, now + p * 1000L);
        assertTrue(context.getSharedPreferences(TvEpisodeProgressStore.PREFERENCES, 0).getAll().isEmpty());
        bridge.pause();
        assertTrue(new TvEpisodeProgressStore(context).get(IMDB, 2, 3).inProgress);
        assertEquals(.1, store.get(IMDB, 2, 3).watchedFraction, .00001);
        bridge.close();
        bridge.receive(payload("completed", 100, 100), origin, true, now + 100_000);
        assertFalse(store.get(IMDB, 2, 3).watched);
    }

    @Test public void javascriptChecksActualFrameSourceOriginAndIdentityWithoutReadingFrameDom() {
        String script = TvPlayerProgressBridge.listenerScript(IMDB, 2, 3);
        assertTrue(script.contains("window!==window.top"));
        assertTrue(script.contains("e.source!==f.contentWindow"));
        assertTrue(script.contains("e.origin!=='https://nextgencloudfabric.com'"));
        assertTrue(script.contains("u.pathname!=='/embed/tv/" + IMDB + "/2/3'"));
        assertTrue(script.contains("i.season!==2||i.episode!==3"));
        assertFalse(script.contains("contentDocument"));
        assertFalse(script.contains("addJavascriptInterface"));
    }

    @Test public void lateEventsWhileActivityIsPausedCannotCreateWatchHistory() throws Exception {
        TvPlayerProgressBridge bridge = new TvPlayerProgressBridge(null, store, IMDB, 2, 3);
        Uri origin = Uri.parse(TvPlayerProgressBridge.MAIN_ORIGIN);
        bridge.receive(payload("playing", 0, 100), origin, true, 0);
        bridge.receive(payload("playing", 10, 100), origin, true, 10_000);
        bridge.pause();
        for (int p = 15; p <= 95; p += 5)
            bridge.receive(payload("playing", p, 100), origin, true, p * 1000L);
        bridge.pause();
        assertEquals(.1, store.get(IMDB, 2, 3).watchedFraction, .00001);
        assertFalse(store.get(IMDB, 2, 3).watched);
        bridge.resume();
        bridge.receive(payload("playing", 20, 100), origin, true, 100_000);
        bridge.receive(payload("playing", 25, 100), origin, true, 105_000);
        bridge.pause();
        assertEquals(.15, store.get(IMDB, 2, 3).watchedFraction, .00001);
        bridge.close();
    }

    @Test public void recoveryPositionRequiresRecentVerifiedEpisodeEvent() throws Exception {
        TvPlayerProgressBridge bridge = new TvPlayerProgressBridge(null, store, IMDB, 2, 3);
        Uri origin = Uri.parse(TvPlayerProgressBridge.MAIN_ORIGIN);
        assertNull(bridge.recentPosition(1_000));
        bridge.receive(payload("playing", 20, 100), origin, true, 1_000);
        TvPlayerProgressBridge.PositionSnapshot position = bridge.recentPosition(1_000);
        assertNotNull(position);
        assertEquals(20, position.position, 0);
        assertEquals(100, position.duration, 0);
        assertEquals(1_000, position.elapsedMs);
        assertEquals(Boolean.FALSE, position.paused);
        assertNotNull(bridge.recentPosition(16_000));
        assertNull(bridge.recentPosition(16_001));
        assertNull(bridge.recentPosition(999));

        // Untrusted or mismatched events cannot replace or refresh the prior measurement.
        bridge.receive(payload("paused", 90, 100), origin, false, 2_000);
        bridge.receive(payload("paused", 90, 100), Uri.parse(TvPlayerProgressBridge.FRAME_ORIGIN), true, 2_000);
        bridge.receive(payload("paused", 90, 100).replace("\"episode\":3", "\"episode\":4"), origin, true, 2_000);
        bridge.receive(payload("playing", "90", 100), origin, true, 2_000);
        assertSame(position, bridge.recentPosition(2_000));
        assertNull(bridge.recentPosition(16_001));
        bridge.close();
    }

    @Test public void recoverySeekPositionDoesNotInventCoverageOrPlaybackState() throws Exception {
        TvPlayerProgressBridge bridge = new TvPlayerProgressBridge(null, store, IMDB, 2, 3);
        Uri origin = Uri.parse(TvPlayerProgressBridge.MAIN_ORIGIN);
        bridge.receive(payload("seeked", 90, 100), origin, true, 1_000);
        TvPlayerProgressBridge.PositionSnapshot seek = bridge.recentPosition(1_000);
        assertNotNull(seek);
        assertEquals(90, seek.position, 0);
        assertNull(seek.paused);

        bridge.receive(payload("paused", 90, 100), origin, true, 2_000);
        assertEquals(Boolean.TRUE, bridge.recentPosition(2_000).paused);
        bridge.receive(payload("seeked", 95, 100), origin, true, 3_000);
        assertEquals(Boolean.TRUE, bridge.recentPosition(3_000).paused);
        assertEquals(95, bridge.recentPosition(3_000).position, 0);
        assertFalse(store.get(IMDB, 2, 3).inProgress);
        assertFalse(store.get(IMDB, 2, 3).watched);
        assertEquals(0, store.get(IMDB, 2, 3).watchedFraction, 0);
        bridge.close();
    }

    @Test public void recoveryPositionExpiresAcrossActivityPauseAndClose() throws Exception {
        TvPlayerProgressBridge bridge = new TvPlayerProgressBridge(null, store, IMDB, 2, 3);
        Uri origin = Uri.parse(TvPlayerProgressBridge.MAIN_ORIGIN);
        bridge.receive(payload("playing", 20, 100), origin, true, 1_000);
        TvPlayerProgressBridge.PositionSnapshot immutable = bridge.recentPosition(1_000);
        assertNotNull(immutable);
        bridge.pause();
        assertNull(bridge.recentPosition(1_000));
        bridge.resume();
        assertNull(bridge.recentPosition(1_000));
        bridge.receive(payload("playing", 30, 100), origin, true, 2_000);
        assertEquals(30, bridge.recentPosition(2_000).position, 0);
        bridge.close();
        assertNull(bridge.recentPosition(2_000));
        assertEquals(20, immutable.position, 0); // Saved snapshots survive only in their caller.
    }

    @Test public void storedIntervalCountIsBoundedWithoutCreditingGaps() {
        TvEpisodeProgressTracker tracker = tracker();
        List<TvEpisodeProgressTracker.Interval> fragmented = new ArrayList<>();
        for (int i = 0; i < 300; i++) fragmented.add(new TvEpisodeProgressTracker.Interval(i * 3, i * 3 + 1));
        tracker.restore(900, 1000, fragmented);
        assertEquals(TvEpisodeProgressTracker.MAX_INTERVALS, tracker.intervals().size());
        assertEquals(.256, tracker.snapshot().watchedFraction, .00001);
    }

    @Test public void episodeExitDestroysVodBeforeResumingAlreadyLoadedLiveAudioWithoutMainLaunch() throws Exception {
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(context);
        config.saveUrls("https://example.invalid/live.m3u", "https://example.invalid/epg.xml");
        config.setPin("1234");
        config.setDeviceMode(fun.nyama.tv.Config.MODE_TV);
        MainActivity mainActivity = Robolectric.buildActivity(MainActivity.class).get();
        Field active = field(MainActivity.class, "activeInstance");
        Object previous = active.get(null);
        ActivityController<MoviePlayerActivity> lifecycle = null;
        try {
            active.set(null, new WeakReference<>(mainActivity));
            // Construct the VOD first, then install a loaded-live-player probe for its exit.
            Intent intent = new Intent(context, MoviePlayerActivity.class).putExtra("id", IMDB).putExtra("type", "series")
                    .putExtra("series_imdb_id", IMDB).putExtra("season", 2).putExtra("episode", 3).putExtra("return_to_series", true);
            lifecycle = Robolectric.buildActivity(MoviePlayerActivity.class, intent).create().start().resume().visible();
            MoviePlayerActivity movieActivity = lifecycle.get();
            WebView movie = (WebView) field(MoviePlayerActivity.class, "webView").get(movieActivity);
            int[] resumeCalls = {0};
            ExoPlayer loaded = (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(), new Class<?>[]{ExoPlayer.class},
                    (proxy, method, args) -> {
                        if ("getMediaItemCount".equals(method.getName())) return 1;
                        if ("setPlayWhenReady".equals(method.getName())) {
                            assertEquals(Boolean.TRUE, args[0]);
                            assertNull(field(MoviePlayerActivity.class, "webView").get(movieActivity));
                            assertTrue(Shadows.shadowOf(movie).wasDestroyCalled());
                            resumeCalls[0]++;
                            return null;
                        }
                        throw new AssertionError("Unrelated live playback operation: " + method.getName());
                    });
            field(MainActivity.class, "player").set(mainActivity, loaded);
            // Invoke the exact confirmed-exit path, avoiding hardware timing in this audio-order test.
            java.lang.reflect.Method exit = MoviePlayerActivity.class.getDeclaredMethod("finishTvPlayback");
            exit.setAccessible(true);
            exit.invoke(movieActivity);
            assertEquals(1, resumeCalls[0]);
            assertTrue(movieActivity.isFinishing());
            assertNull(Shadows.shadowOf(movieActivity).getNextStartedActivity());
            lifecycle.pause().stop().destroy();
            lifecycle = null;
            assertEquals("Old player destruction cannot later restart audio over new VOD", 1, resumeCalls[0]);
        } finally {
            if (lifecycle != null) lifecycle.pause().stop().destroy();
            active.set(null, previous);
            ((ExecutorService) field(MainActivity.class, "io").get(mainActivity)).shutdownNow();
            ((ExecutorService) field(MainActivity.class, "scheduler").get(mainActivity)).shutdownNow();
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
