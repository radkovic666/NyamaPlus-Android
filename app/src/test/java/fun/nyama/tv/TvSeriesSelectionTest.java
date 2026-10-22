package fun.nyama.tv;

import android.app.Application;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.*;

/** Native selector checks with synthetic dated metadata; no metadata/provider network requests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TvSeriesSelectionTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final String IMDB = "tt0944947";
    private final List<TitleDetailActivity> activities = new ArrayList<>();

    @Before public void before() {
        for (String preferences : new String[] { "nyama_tv_private", "nyama_tv_series_selection", "nyama_tv_episode_progress", "nyama_plus_media_favorites" }) {
            RuntimeEnvironment.getApplication().getSharedPreferences(preferences, 0).edit().clear().commit();
        }
    }

    @After public void after() throws Exception {
        for (TitleDetailActivity activity : activities) ((ExecutorService) field("io").get(activity)).shutdownNow();
        posterCache().evictAll();
    }

    private TitleDetailActivity detail(boolean phone, String imdb) throws Exception {
        Intent intent = new Intent().putExtra("id", imdb).putExtra("type", "series").putExtra("title", "A verified series");
        TitleDetailActivity activity = Robolectric.buildActivity(TitleDetailActivity.class, intent).get();
        activities.add(activity);
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(activity);
        config.setDeviceMode(phone ? fun.nyama.tv.Config.MODE_PHONE : fun.nyama.tv.Config.MODE_TV);
        config.setLanguage(fun.nyama.tv.Config.LANG_EN);
        config.setColorTheme(fun.nyama.tv.Config.THEME_ORANGE);
        field("config").set(activity, config);
        field("favorites").set(activity, new MovieFavorites(activity));
        field("id").set(activity, imdb);
        field("section").set(activity, "series");
        field("title").set(activity, "A verified series");
        posterCache().put(config.pukankiBaseUrl() + "|" + imdb, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
        Method build = TitleDetailActivity.class.getDeclaredMethod("buildUi");
        build.setAccessible(true);
        activity.setContentView((View) build.invoke(activity));
        layout(activity);
        return activity;
    }

    private static Field field(String name) throws Exception {
        Field field = TitleDetailActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked") private static LruCache<String, Bitmap> posterCache() throws Exception {
        Field memory = PosterLoader.class.getDeclaredField("MEMORY"); memory.setAccessible(true);
        return (LruCache<String, Bitmap>) memory.get(null);
    }

    private static TvSeriesMetadata.Episode episode(int season, int number, long airStamp) {
        return new TvSeriesMetadata.Episode(season, number, "Actual title " + number, 42,
                "Actual summary " + number, "", airStamp, NOW);
    }

    private static TvSeriesMetadata metadata(String imdb) {
        return new TvSeriesMetadata(imdb, "TVmaze (CC BY-SA)", "https://www.tvmaze.com/shows/82/game-of-thrones", Arrays.asList(
                new TvSeriesMetadata.Season(1, Arrays.asList(episode(1, 1, NOW - 1000), episode(1, 4, NOW - 500))),
                new TvSeriesMetadata.Season(3, Arrays.asList(episode(3, 2, NOW - 1000), episode(3, 4, NOW - 500),
                        episode(3, 7, -1), episode(3, 9, NOW + 1000))),
                new TvSeriesMetadata.Season(0, Arrays.asList(episode(0, 1, NOW - 1000)))));
    }

    private static TvSeriesSelectionView selector(TitleDetailActivity activity) throws Exception {
        return (TvSeriesSelectionView) field("seriesSelection").get(activity);
    }

    private static List<View> views(View view) {
        List<View> result = new ArrayList<>(); result.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) result.addAll(views(group.getChildAt(i)));
        }
        return result;
    }

    private static View tagged(TvSeriesSelectionView selector, String tag) {
        for (View view : views(selector)) if (tag.equals(view.getTag())) return view;
        return null;
    }

    private static String text(View root) {
        StringBuilder out = new StringBuilder();
        for (View view : views(root)) if (view instanceof TextView) out.append(((TextView) view).getText()).append('\n');
        return out.toString();
    }

    private static Button watch(TitleDetailActivity activity) throws Exception { return (Button) field("watchButton").get(activity); }

    private static void layout(Activity activity) {
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1920, 1080);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private static void key(View view, int key) {
        view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
    }

    @Test public void actualSeasonAndEpisodeNumbersArePreservedWithoutInventingGaps() throws Exception {
        TitleDetailActivity activity = detail(false, IMDB);
        TvSeriesSelectionView selector = selector(activity);
        selector.bind(metadata(IMDB));
        assertNotNull(tagged(selector, "season:1"));
        assertNotNull(tagged(selector, "season:3"));
        assertNotNull(tagged(selector, "season:0"));
        assertNull(tagged(selector, "season:2"));
        assertNotNull(tagged(selector, "episode:1:1"));
        assertNotNull(tagged(selector, "episode:1:4"));
        assertNull(tagged(selector, "episode:1:2"));
        tagged(selector, "season:3").performClick();
        assertTrue(tagged(selector, "episode:3:7").isFocusable());
        assertFalse(tagged(selector, "episode:3:7").isClickable());
        assertNull(tagged(selector, "episode:3:9"));
        assertTrue(text(selector).contains("Air date is unconfirmed"));
        assertTrue(text(selector).contains("Provider availability is not confirmed"));
        assertTrue(text(selector).contains("Actual title 4"));
        assertTrue(text(selector).contains("Actual summary 4"));
        assertTrue(text(selector).contains("42 min"));
        assertFalse(text(selector).contains("<p>"));
    }

    @Test public void episodeOkLaunchesExactEpisodeWithoutRedundantTvWatchButton() throws Exception {
        TitleDetailActivity activity = detail(false, IMDB);
        TvSeriesSelectionView selector = selector(activity);
        selector.bind(metadata(IMDB));
        tagged(selector, "season:3").performClick();
        tagged(selector, "episode:3:4").performClick();
        Intent episode = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(episode);
        assertEquals(MoviePlayerActivity.class.getName(), episode.getComponent().getClassName());
        assertEquals(IMDB, episode.getStringExtra("id"));
        assertEquals(IMDB, episode.getStringExtra("series_imdb_id"));
        assertEquals("series", episode.getStringExtra("type"));
        assertEquals("A verified series", episode.getStringExtra("title"));
        assertEquals(3, episode.getIntExtra("season", -1));
        assertEquals(4, episode.getIntExtra("episode", -1));
        assertTrue(episode.getBooleanExtra("return_to_series", false));
        assertNull(watch(activity));
    }

    @Test public void returningOrRecreatingRestoresSelectionAndFocusPerImdb() throws Exception {
        TitleDetailActivity activity = detail(false, IMDB);
        TvSeriesSelectionView selector = selector(activity);
        selector.bind(metadata(IMDB));
        tagged(selector, "season:3").performClick();
        tagged(selector, "episode:3:4").performClick();
        TitleDetailActivity recreated = detail(false, IMDB);
        TvSeriesSelectionView restored = selector(recreated);
        restored.bind(metadata(IMDB));
        layout(recreated);
        assertTrue(restored.hadRememberedSelection());
        assertEquals(3, restored.selectedEpisode().season);
        assertEquals(4, restored.selectedEpisode().number);
        assertTrue(restored.restoreSelectionFocus());
        assertTrue(tagged(restored, "episode:3:4").hasFocus());
        String otherId = "tt0903747";
        TvSeriesSelectionView other = selector(detail(false, otherId));
        other.bind(metadata(otherId));
        assertEquals(1, other.selectedEpisode().season);
        assertEquals(1, other.selectedEpisode().number);
    }

    @Test public void dpadMovesBetweenSeasonAndEpisodesWithVisibleThemeFocus() throws Exception {
        TitleDetailActivity activity = detail(false, IMDB);
        TvSeriesSelectionView selector = selector(activity);
        selector.bind(metadata(IMDB));
        layout(activity);
        Button season = (Button) tagged(selector, "season:3");
        assertTrue(season.requestFocus());
        key(season, KeyEvent.KEYCODE_DPAD_DOWN);
        layout(activity); // Switching seasons creates new cards before the next physical key/frame.
        View first = tagged(selector, "episode:3:2");
        assertTrue(first.hasFocus());
        assertTrue(first.getBackground() instanceof GradientDrawable);
        int focusedCardColor = ((GradientDrawable) first.getBackground()).getColor().getDefaultColor() | 0xFF000000;
        assertTrue(views(first).stream().filter(view -> view instanceof TextView && view.getVisibility() == View.VISIBLE)
                .allMatch(view -> androidx.core.graphics.ColorUtils.calculateContrast(
                        ((TextView) view).getCurrentTextColor(), focusedCardColor) >= 4.5));
        key(first, KeyEvent.KEYCODE_DPAD_RIGHT);
        View second = tagged(selector, "episode:3:4");
        assertTrue(second.hasFocus());
        key(second, KeyEvent.KEYCODE_DPAD_UP);
        assertTrue(season.hasFocus());
        key(season, KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue(second.hasFocus());
        assertTrue(first.requestFocus());
        key(first, KeyEvent.KEYCODE_DPAD_UP);
        assertTrue(season.hasFocus());
        assertTrue(text(season).contains("✓"));
        assertTrue(views(selector).stream().anyMatch(view -> view instanceof HorizontalScrollView));
        assertEquals(2, views(selector).stream().filter(view -> view instanceof HorizontalScrollView).count());
        assertNull(tagged(selector, "episode-information"));
        assertEquals(visibleCardCount(selector), views(selector).stream()
                .filter(view -> "episode-card-text-scroll".equals(view.getTag())).count());
    }

    @Test public void unknownDatesCannotPlayAndFutureOnlyMetadataDoesNotUseGenericFallback() throws Exception {
        TitleDetailActivity activity = detail(false, IMDB);
        TvSeriesSelectionView selector = selector(activity);
        selector.bind(new TvSeriesMetadata(IMDB, "TVmaze (CC BY-SA)", "", Arrays.asList(
                new TvSeriesMetadata.Season(2, Arrays.asList(episode(2, 1, -1))))));
        assertNull(watch(activity));
        assertTrue(tagged(selector, "episode:2:1").isFocusable());
        tagged(selector, "episode:2:1").performClick();
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        assertNull(selector.selectedEpisode());
        selector.bind(new TvSeriesMetadata(IMDB, "TVmaze (CC BY-SA)", "", Arrays.asList(
                new TvSeriesMetadata.Season(2, Arrays.asList(episode(2, 1, NOW + 1000))))));
        assertNull(watch(activity));
        assertNull(tagged(selector, "season:2"));
        assertTrue(text(selector).contains("No confirmed aired episodes"));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void mismatchOrUnavailableMetadataExplainsRetryWithoutHiddenPlaybackFallback() throws Exception {
        TitleDetailActivity activity = detail(false, IMDB);
        TvSeriesSelectionView selector = selector(activity);
        selector.bind(metadata("tt0903747"));
        assertFalse(selector.hasMetadata());
        assertNull(watch(activity));
        assertTrue(text(selector).contains("could not be loaded"));
        assertTrue(text(selector).contains("Reopen this series to try again"));
        assertFalse(text(selector).contains("Watch now"));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void actualNumberedSpecialsUseSeasonZero() throws Exception {
        TitleDetailActivity activity = detail(false, IMDB);
        TvSeriesSelectionView selector = selector(activity);
        selector.bind(metadata(IMDB));
        tagged(selector, "season:0").performClick();
        tagged(selector, "episode:0:1").performClick();
        Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(0, intent.getIntExtra("season", -1));
        assertEquals(1, intent.getIntExtra("episode", -1));
        assertEquals(IMDB, intent.getStringExtra("series_imdb_id"));
    }

    @Test public void selectorHasNoMetadataButtonOrAttributionFocusStop() throws Exception {
        assertEquals("", TvSeriesSelectionView.safeSourceUrl("https://evil.invalid/shows/82"));
        assertEquals("", TvSeriesSelectionView.safeSourceUrl("javascript:alert(1)"));
        assertEquals("", TvSeriesSelectionView.safeSourceUrl("https://www.tvmaze.com@evil.invalid/shows/82"));
        assertEquals("", TvSeriesSelectionView.safeSourceUrl("https://www.tvmaze.com/shows/82?next=https://evil.invalid"));
        TitleDetailActivity activity = detail(false, IMDB);
        TvSeriesSelectionView selector = selector(activity);
        selector.bind(metadata(IMDB));
        assertFalse(text(selector).contains("CC BY-SA"));
        assertFalse(text(selector).contains("Metadata:"));
        for (View view : views(selector)) {
            if (view.isFocusable()) assertTrue(view.getTag() != null
                    && (view.getTag().toString().startsWith("season:") || view.getTag().toString().startsWith("episode:")));
        }
    }

    @Test public void scrollingReleasesOffscreenArtworkAndKeepsVisibleArtwork() throws Exception {
        TitleDetailActivity detail = detail(false, IMDB);
        TvSeriesSelectionView selector = selector(detail);
        ((ViewGroup) selector.getParent()).removeView(selector);
        // A plain visible Activity supplies a real attached viewport without title-info HTTP calls.
        ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            host.get().setContentView(selector);
            List<TvSeriesMetadata.Episode> episodes = new ArrayList<>();
            for (int number = 1; number <= 40; number++) episodes.add(episode(1, number, NOW - 1000));
            // Empty still URLs ensure even an accidentally repeated load cannot perform HTTP.
            selector.bind(new TvSeriesMetadata(IMDB, "TVmaze (CC BY-SA)", "", Arrays.asList(
                    new TvSeriesMetadata.Season(1, episodes))));
            layout(host.get());
            assertTrue(selector.isAttachedToWindow());
            TvEpisodeCard first = (TvEpisodeCard) tagged(selector, "episode:1:1");
            TvEpisodeCard last = (TvEpisodeCard) tagged(selector, "episode:1:40");
            assertTrue(first.getLocalVisibleRect(new Rect()));
            assertFalse(last.getLocalVisibleRect(new Rect()));
            Bitmap bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
            first.setStill(bitmap);
            last.setStill(bitmap);
            Field markers = TvSeriesSelectionView.class.getDeclaredField("requestedStills");
            markers.setAccessible(true);
            @SuppressWarnings("unchecked") Set<TvEpisodeCard> requested = (Set<TvEpisodeCard>) markers.get(selector);
            requested.add(first); requested.add(last);
            Method loadVisible = TvSeriesSelectionView.class.getDeclaredMethod("loadVisibleStills");
            loadVisible.setAccessible(true);
            loadVisible.invoke(selector);
            assertTrue(first.hasStill());
            assertFalse(last.hasStill());
            assertTrue(requested.contains(first));
            assertFalse(requested.contains(last));

            HorizontalScrollView viewport = (HorizontalScrollView) tagged(selector, "episode-scroll");
            assertNotNull(viewport);
            viewport.scrollTo(viewport.getChildAt(0).getWidth(), 0);
            assertFalse(first.getLocalVisibleRect(new Rect()));
            assertTrue(last.getLocalVisibleRect(new Rect()));
            last.setStill(bitmap);
            requested.add(last);
            loadVisible.invoke(selector);
            assertFalse(first.hasStill());
            assertTrue(last.hasStill());
            assertFalse(requested.contains(first));
            assertTrue(requested.contains(last));
        } finally {
            host.pause().stop().destroy();
        }
    }

    @Test public void bulgarianRowsAreChronologicalAndKeepAdjacentRemoteNavigation() throws Exception {
        assertChronologicalRows(fun.nyama.tv.Config.LANG_BG);
    }

    @Test public void englishRowsAreChronologicalAndKeepAdjacentRemoteNavigation() throws Exception {
        assertChronologicalRows(fun.nyama.tv.Config.LANG_EN);
    }

    private void assertChronologicalRows(String language) {
        ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            fun.nyama.tv.Config config = new fun.nyama.tv.Config(host.get());
            config.setDeviceMode(fun.nyama.tv.Config.MODE_TV);
            config.setLanguage(language);
            TvSeriesSelectionView selector = new TvSeriesSelectionView(host.get(), IMDB, noOpListener());
            host.get().setContentView(selector);
            List<TvSeriesMetadata.Season> unsorted = new ArrayList<>();
            for (int number : new int[] {5, 3, 0, 2, 1, 4}) unsorted.add(new TvSeriesMetadata.Season(number,
                    Arrays.asList(episode(number, 4, NOW - 1000), episode(number, 2, NOW - 1000),
                            episode(number, 1, NOW - 1000), episode(number, 3, NOW - 1000))));
            selector.bind(new TvSeriesMetadata(IMDB, "TVmaze (CC BY-SA)", "", unsorted));
            layout(host.get());
            LinearLayout seasonRow = (LinearLayout) tagged(selector, "season-row");
            for (int index = 0; index < 5; index++) {
                View season = seasonRow.getChildAt(index);
                assertEquals("season:" + (index + 1), season.getTag());
                if (index > 0) assertTrue(seasonRow.getChildAt(index - 1).getLeft() < season.getLeft());
            }
            assertEquals("season:0", seasonRow.getChildAt(5).getTag());
            LinearLayout episodeRow = (LinearLayout) tagged(selector, "episode-row");
            for (int index = 0; index < 4; index++) assertEquals("episode:1:" + (index + 1), episodeRow.getChildAt(index).getTag());
            View season1 = tagged(selector, "season:1");
            View season2 = tagged(selector, "season:2");
            View episode1 = tagged(selector, "episode:1:1");
            View episode2 = tagged(selector, "episode:1:2");
            assertTrue(episode1.requestFocus());
            key(episode1, KeyEvent.KEYCODE_DPAD_LEFT);
            assertTrue(episode1.hasFocus());
            key(episode1, KeyEvent.KEYCODE_DPAD_RIGHT);
            assertTrue(episode2.hasFocus());
            assertEquals(2, selector.selectedEpisode().number);
            key(episode2, KeyEvent.KEYCODE_DPAD_LEFT);
            assertTrue(episode1.hasFocus());
            key(episode1, KeyEvent.KEYCODE_DPAD_UP);
            assertTrue(season1.hasFocus());
            key(season1, KeyEvent.KEYCODE_DPAD_RIGHT);
            assertTrue(season2.hasFocus());
            key(season2, KeyEvent.KEYCODE_DPAD_DOWN);
            layout(host.get());
            assertTrue(tagged(selector, "episode:2:1").hasFocus());
            key(tagged(selector, "episode:2:1"), KeyEvent.KEYCODE_DPAD_UP);
            assertTrue(season2.hasFocus());
        } finally { host.pause().stop().destroy(); }
    }

    @Test public void episodeFocusAndGenuineHoverNotifyArtworkWithoutStartingPlayback() {
        ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            new fun.nyama.tv.Config(host.get()).setLanguage(fun.nyama.tv.Config.LANG_EN);
            List<Integer> focused = new ArrayList<>();
            List<Integer> played = new ArrayList<>();
            TvSeriesSelectionView selector = new TvSeriesSelectionView(host.get(), IMDB, new TvSeriesSelectionView.Listener() {
                @Override public void onSelectionChanged(TvSeriesMetadata.Episode episode, boolean present) { }
                @Override public void onPlay(TvSeriesMetadata.Episode episode) { played.add(episode.number); }
                @Override public void onSource(String url) { fail("No source button is part of the selector"); }
                @Override public void onEpisodeFocused(TvSeriesMetadata.Episode episode) { focused.add(episode.number); }
            });
            host.get().setContentView(selector);
            selector.bind(metadata(IMDB));
            layout(host.get());
            View first = tagged(selector, "episode:1:1");
            View fourth = tagged(selector, "episode:1:4");
            assertTrue(first.requestFocus());
            key(first, KeyEvent.KEYCODE_DPAD_RIGHT);
            assertTrue(fourth.hasFocus());
            assertEquals(Integer.valueOf(4), focused.get(focused.size() - 1));
            MotionEvent hover = MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_ENTER, 1, 1, 0);
            hover.setSource(InputDevice.SOURCE_MOUSE);
            first.dispatchGenericMotionEvent(hover);
            hover.recycle();
            assertEquals(Integer.valueOf(1), focused.get(focused.size() - 1));
            MotionEvent exit = MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_EXIT, 1, 1, 0);
            exit.setSource(InputDevice.SOURCE_MOUSE);
            first.dispatchGenericMotionEvent(exit);
            exit.recycle();
            assertEquals(Integer.valueOf(4), focused.get(focused.size() - 1));
            assertTrue(fourth.hasFocus());
            assertTrue(played.isEmpty());
        } finally { host.pause().stop().destroy(); }
    }

    @Test public void eachCardContainsFullTitleSanitizedSummaryAndOnlyVerifiedMetadata() {
        ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            new fun.nyama.tv.Config(host.get()).setLanguage(fun.nyama.tv.Config.LANG_EN);
            TvSeriesSelectionView selector = new TvSeriesSelectionView(host.get(), IMDB, noOpListener());
            host.get().setContentView(selector);
            String title = "The complete original episode title, including every word and its final ending";
            String summary = longSummary() + "Literal <not markup> and &lt; stay as supplied.";
            TvSeriesMetadata.Episode first = new TvSeriesMetadata.Episode(1, 1, title, 47,
                    "Concise preview", "", "", NOW - 1000, NOW, summary, "2011-04-17");
            TvSeriesMetadata.Episode second = new TvSeriesMetadata.Episode(1, 2, "Second complete title", -1,
                    "", "", "", NOW - 1000, NOW, "Second complete description", "");
            selector.bind(new TvSeriesMetadata(IMDB, "TVmaze (CC BY-SA)", "", Arrays.asList(
                    new TvSeriesMetadata.Season(1, Arrays.asList(first, second)))));
            layout(host.get());
            View firstCard = tagged(selector, "episode:1:1");
            View secondCard = tagged(selector, "episode:1:2");
            assertTrue(firstCard.requestFocus());
            TextView fullTitle = firstCard.findViewWithTag("episode-card-title");
            TextView fullSummary = firstCard.findViewWithTag("episode-card-summary");
            assertEquals(title, fullTitle.getText().toString());
            assertEquals(summary, fullSummary.getText().toString());
            assertNull(fullTitle.getEllipsize());
            assertNull(fullSummary.getEllipsize());
            assertTrue(fullTitle.getMaxLines() > 100 || fullTitle.getMaxLines() == -1);
            assertTrue(fullSummary.getMaxLines() > 100 || fullSummary.getMaxLines() == -1);
            assertTrue(text(firstCard).contains("Season 1  ·  Episode 1"));
            assertTrue(text(firstCard).contains("47 min"));
            assertTrue(text(firstCard).contains("2011-04-17"));
            assertNotNull(firstCard.findViewWithTag("episode-card-image"));
            key(firstCard, KeyEvent.KEYCODE_DPAD_RIGHT);
            assertTrue(secondCard.hasFocus());
            assertEquals("Second complete title", ((TextView) secondCard.findViewWithTag("episode-card-title")).getText().toString());
            assertEquals("Second complete description", ((TextView) secondCard.findViewWithTag("episode-card-summary")).getText().toString());
            assertFalse(text(secondCard).contains("min"));
            assertFalse(text(secondCard).contains("Aired:"));
            assertNull(tagged(selector, "episode-information"));
        } finally { host.pause().stop().destroy(); }
    }

    @Test public void longDescriptionScrollsInsideItsCardAndNeverTrapsHorizontalOrSeasonFocus() {
        ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            new fun.nyama.tv.Config(host.get()).setLanguage(fun.nyama.tv.Config.LANG_EN);
            TvSeriesSelectionView selector = new TvSeriesSelectionView(host.get(), IMDB, noOpListener());
            host.get().setContentView(selector);
            TvSeriesMetadata.Episode first = new TvSeriesMetadata.Episode(1, 1, "Long information", 44,
                    "Short preview", "", "", NOW - 1000, NOW, longSummary(), "2011-04-17");
            selector.bind(new TvSeriesMetadata(IMDB, "TVmaze (CC BY-SA)", "", Arrays.asList(
                    new TvSeriesMetadata.Season(1, Arrays.asList(first, episode(1, 2, NOW - 1000))))));
            layout(host.get());
            View firstCard = tagged(selector, "episode:1:1");
            ScrollView textScroll = firstCard.findViewWithTag("episode-card-text-scroll");
            View image = firstCard.findViewWithTag("episode-card-image");
            View identity = firstCard.findViewWithTag("episode-card-identity");
            int imageTop = image.getTop();
            int identityTop = identity.getTop();
            assertTrue(firstCard.requestFocus());
            key(firstCard, KeyEvent.KEYCODE_DPAD_DOWN);
            finishScroll();
            assertTrue(textScroll.getScrollY() > 0);
            assertTrue(firstCard.hasFocus());
            assertFalse(textScroll.isFocusable());
            assertEquals(imageTop, image.getTop());
            assertEquals(identityTop, identity.getTop());
            key(firstCard, KeyEvent.KEYCODE_DPAD_RIGHT);
            assertTrue(tagged(selector, "episode:1:2").hasFocus());
            key(tagged(selector, "episode:1:2"), KeyEvent.KEYCODE_DPAD_LEFT);
            assertTrue(firstCard.hasFocus());
            key(firstCard, KeyEvent.KEYCODE_DPAD_UP);
            finishScroll();
            assertEquals(0, textScroll.getScrollY());
            key(firstCard, KeyEvent.KEYCODE_DPAD_UP);
            assertTrue(tagged(selector, "season:1").hasFocus());
            key(tagged(selector, "season:1"), KeyEvent.KEYCODE_DPAD_DOWN);
            assertTrue(firstCard.hasFocus());
            key(firstCard, KeyEvent.KEYCODE_DPAD_DOWN);
            finishScroll();
            firstCard.dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_DPAD_UP, 1, 0, 0, 0, KeyEvent.FLAG_LONG_PRESS));
            assertTrue(tagged(selector, "season:1").hasFocus());
        } finally { host.pause().stop().destroy(); }
    }

    private static String longSummary() {
        StringBuilder summary = new StringBuilder("Opening paragraph.\n\n");
        for (int index = 0; index < 100; index++) summary.append("Complete verified paragraph ").append(index)
                .append(" with readable words and facts.\n\n");
        return summary.append("The final complete paragraph remains accessible.").toString();
    }

    private static void finishScroll() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350));
    }

    private static long visibleCardCount(View root) {
        return views(root).stream().filter(view -> view instanceof TvEpisodeCard).count();
    }

    @Test public void unknownAirDateMetadataRemainsReadableButCannotLaunchPlayback() throws Exception {
        TitleDetailActivity activity = detail(false, IMDB);
        TvSeriesSelectionView selector = selector(activity);
        selector.bind(new TvSeriesMetadata(IMDB, "TVmaze (CC BY-SA)", "", Arrays.asList(
                new TvSeriesMetadata.Season(1, Arrays.asList(episode(1, 1, NOW - 1000), episode(1, 2, -1))))));
        layout(activity);
        View known = tagged(selector, "episode:1:1");
        View unknown = tagged(selector, "episode:1:2");
        assertTrue(known.requestFocus());
        key(known, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertTrue(unknown.hasFocus());
        assertEquals("Actual title 2", ((TextView) unknown.findViewWithTag("episode-card-title")).getText().toString());
        assertNull(watch(activity));
        assertNull(selector.selectedEpisode());
        unknown.performClick();
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void focusedCardIsFullyRevealedAfterChangingSeasonAndAtFarHorizontalEdges() {
        ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            new fun.nyama.tv.Config(host.get()).setLanguage(fun.nyama.tv.Config.LANG_EN);
            TvSeriesSelectionView selector = new TvSeriesSelectionView(host.get(), IMDB, noOpListener());
            host.get().setContentView(selector);
            List<TvSeriesMetadata.Episode> episodes = new ArrayList<>();
            for (int number = 1; number <= 18; number++) episodes.add(episode(2, number, NOW - 1000));
            selector.bind(new TvSeriesMetadata(IMDB, "TVmaze (CC BY-SA)", "", Arrays.asList(
                    new TvSeriesMetadata.Season(1, Arrays.asList(episode(1, 1, NOW - 1000))),
                    new TvSeriesMetadata.Season(2, episodes))));
            layout(host.get());
            View season = tagged(selector, "season:2");
            assertTrue(season.requestFocus());
            key(season, KeyEvent.KEYCODE_DPAD_DOWN);
            layout(host.get());
            for (int number = 1; number < 18; number++) key(tagged(selector, "episode:2:" + number), KeyEvent.KEYCODE_DPAD_RIGHT);
            View last = tagged(selector, "episode:2:18");
            assertTrue(last.hasFocus());
            Rect visible = new Rect();
            assertTrue(last.getLocalVisibleRect(visible));
            assertEquals(0, visible.left);
            assertEquals(last.getWidth(), visible.right);
            for (int number = 18; number > 1; number--) key(tagged(selector, "episode:2:" + number), KeyEvent.KEYCODE_DPAD_LEFT);
            View first = tagged(selector, "episode:2:1");
            assertTrue(first.hasFocus());
            assertTrue(first.getLocalVisibleRect(visible));
            assertEquals(0, visible.left);
            assertEquals(first.getWidth(), visible.right);
        } finally { host.pause().stop().destroy(); }
    }

    @Test public void verifiedProgressFlagsRefreshImmediatelyAndRemainIsolatedAcrossSeasons() throws Exception {
        TitleDetailActivity activity = detail(false, IMDB);
        TvSeriesSelectionView selector = selector(activity);
        selector.bind(metadata(IMDB));
        View first = tagged(selector, "episode:1:1");
        View fourth = tagged(selector, "episode:1:4");
        assertEquals(View.GONE, first.findViewWithTag("watch-status").getVisibility());
        assertEquals(View.GONE, fourth.findViewWithTag("watch-status").getVisibility());
        seedProgress(activity, 1, 1, 10);
        seedProgress(activity, 1, 4, 90);
        selector.refreshWatchProgress();
        TextView partial = first.findViewWithTag("watch-status");
        TextView completed = fourth.findViewWithTag("watch-status");
        assertEquals(View.VISIBLE, partial.getVisibility());
        assertEquals("▶ Started", partial.getText().toString());
        assertEquals(View.VISIBLE, completed.getVisibility());
        assertEquals("✓ Watched", completed.getText().toString());
        tagged(selector, "season:3").performClick();
        assertEquals(View.GONE, tagged(selector, "episode:3:4").findViewWithTag("watch-status").getVisibility());
        tagged(selector, "season:1").performClick();
        assertEquals("✓ Watched", ((TextView) tagged(selector, "episode:1:4").findViewWithTag("watch-status")).getText().toString());
    }

    @Test public void bulgarianProgressUsesStartedLabelWithoutChangingCompletedLabel() {
        Application context = RuntimeEnvironment.getApplication();
        seedProgress(context, 1, 1, 10);
        seedProgress(context, 1, 2, 90);
        TvEpisodeProgressStore store = new TvEpisodeProgressStore(context);
        TvEpisodeCard started = new TvEpisodeCard(context, episode(1, 1, NOW - 1000), true);
        TvEpisodeCard watched = new TvEpisodeCard(context, episode(1, 2, NOW - 1000), true);
        started.updateWatchStatus(store.get(IMDB, 1, 1));
        watched.updateWatchStatus(store.get(IMDB, 1, 2));
        assertEquals("▶ Започнат", ((TextView) started.findViewWithTag("watch-status")).getText().toString());
        assertEquals("✓ Гледан", ((TextView) watched.findViewWithTag("watch-status")).getText().toString());
    }

    private static void seedProgress(android.content.Context context, int season, int episode, int watchedSeconds) {
        TvEpisodeProgressStore store = new TvEpisodeProgressStore(context);
        TvEpisodeProgressTracker tracker = store.load(IMDB, season, episode);
        for (int seconds = 0; seconds <= watchedSeconds; seconds += 5) {
            tracker.accept(new TvPlayerProgressBridge.Event(IMDB, season, episode, "playing", seconds, 100), seconds * 1000L);
        }
        store.save(tracker);
    }

    private static TvSeriesSelectionView.Listener noOpListener() {
        return new TvSeriesSelectionView.Listener() {
            @Override public void onSelectionChanged(TvSeriesMetadata.Episode episode, boolean present) { }
            @Override public void onPlay(TvSeriesMetadata.Episode episode) { }
            @Override public void onSource(String url) { }
        };
    }

    @Test @Config(qualifiers = "w1280dp-h720dp")
    public void cardsHaveReadableBoundedDimensionsAt720p() {
        assertReadableCard();
    }

    @Test @Config(qualifiers = "w1920dp-h1080dp")
    public void cardsHaveReadableBoundedDimensionsAt1080p() {
        assertReadableCard();
    }

    @Test @Config(qualifiers = "w960dp-h540dp")
    public void fullCardInformationRemainsReadableAndRemoteReachableOn720pTelevisionDensity() throws Exception {
        assertResponsiveInformation(960, 540);
    }

    @Test @Config(qualifiers = "w1920dp-h1080dp")
    public void fullCardInformationRemainsReadableAndRemoteReachableAt1080p() throws Exception {
        assertResponsiveInformation(1920, 1080);
    }

    private void assertResponsiveInformation(int widthDp, int heightDp) throws Exception {
        TitleDetailActivity activity = detail(false, IMDB);
        TvSeriesSelectionView selector = selector(activity);
        StringBuilder fullSummary = new StringBuilder();
        for (int index = 0; index < 90; index++) fullSummary.append("A full paragraph with readable words and a complete ending.\n\n");
        TvSeriesMetadata.Episode episode = new TvSeriesMetadata.Episode(1, 1, "Full title visible in the information area", 46,
                "Short preview", "", "", NOW - 1000, NOW, fullSummary.toString(), "2011-04-17");
        selector.bind(new TvSeriesMetadata(IMDB, "TVmaze (CC BY-SA)", "", Arrays.asList(
                new TvSeriesMetadata.Season(1, Arrays.asList(episode)))));
        View root = activity.getWindow().getDecorView();
        float density = root.getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp * density);
        int height = Math.round(heightDp * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        View card = tagged(selector, "episode:1:1");
        ScrollView information = card.findViewWithTag("episode-card-text-scroll");
        TextView summary = card.findViewWithTag("episode-card-summary");
        assertEquals(fullSummary.toString(), summary.getText().toString());
        assertNull(summary.getEllipsize());
        assertTrue(card.getHeight() >= Math.round(320 * density));
        assertTrue(card.getHeight() <= Math.round(440 * density));
        assertTrue(summary.getTextSize() >= 17 * root.getResources().getDisplayMetrics().scaledDensity);
        assertNull(tagged(selector, "episode-information"));
        assertTrue(card.requestFocus());
        key(card, KeyEvent.KEYCODE_DPAD_DOWN);
        finishScroll();
        assertTrue(card.hasFocus());
        assertTrue(information.getScrollY() > 0);
        assertTrue(information.getChildAt(0).getHeight() > information.getHeight());
        card.dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_UP, 1, 0, 0, 0, KeyEvent.FLAG_LONG_PRESS));
        assertTrue(tagged(selector, "season:1").hasFocus());
    }

    private static void assertReadableCard() {
        TvEpisodeCard card = new TvEpisodeCard(RuntimeEnvironment.getApplication(), episode(1, 1, NOW - 1000), false);
        float density = card.getResources().getDisplayMetrics().density;
        assertTrue(card.preferredWidth() >= 350 * density);
        assertTrue(card.preferredWidth() <= 560 * density);
        assertTrue(card.preferredHeight() >= 320 * density);
        assertTrue(card.preferredHeight() <= 440 * density);
        TextView title = card.findViewWithTag("episode-card-title");
        TextView summary = card.findViewWithTag("episode-card-summary");
        assertEquals("Actual title 1", title.getText().toString());
        assertEquals("Actual summary 1", summary.getText().toString());
        assertTrue(title.getTextSize() >= 20 * card.getResources().getDisplayMetrics().scaledDensity);
        assertTrue(summary.getTextSize() >= 17 * card.getResources().getDisplayMetrics().scaledDensity);
        assertNull(title.getEllipsize());
        assertNull(summary.getEllipsize());
    }

    @Test public void phoneSeriesAddsMetadataAndKeepsGenericFallback() throws Exception {
        TitleDetailActivity activity = detail(true, IMDB);
        assertNotNull(selector(activity));
        List<ScrollView> scrolls = new ArrayList<>();
        for (View view : views(activity.getWindow().getDecorView())) if (view instanceof ScrollView) scrolls.add((ScrollView) view);
        assertEquals(1, scrolls.size());
        LinearLayout root = (LinearLayout) scrolls.get(0).getChildAt(0);
        assertEquals(LinearLayout.VERTICAL, ((LinearLayout) root.getChildAt(1)).getOrientation());
        watch(activity).performClick();
        Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(IMDB, intent.getStringExtra("id"));
        assertFalse(intent.hasExtra("season"));
        assertFalse(intent.hasExtra("return_to_series"));
    }
    @Test public void phoneEpisodesAreVerticalResponsiveAndLaunchTheExactSelection() throws Exception {
        for (int[] size : new int[][]{{360,800},{800,360}}) {
            TitleDetailActivity activity = detail(true, IMDB);
            TvSeriesSelectionView selector = selector(activity);
            selector.bind(metadata(IMDB));
            tagged(selector,"season:3").performClick();
            View root = activity.getWindow().getDecorView();
            root.measure(View.MeasureSpec.makeMeasureSpec(size[0],View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size[1],View.MeasureSpec.EXACTLY));
            root.layout(0,0,size[0],size[1]);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            LinearLayout rows = (LinearLayout)tagged(selector,"episode-row");
            assertEquals(LinearLayout.VERTICAL, rows.getOrientation());
            TvEpisodeCard card = (TvEpisodeCard)tagged(selector,"episode:3:4");
            assertTrue(card.getWidth() <= size[0]);assertTrue(card.getWidth()>0);
            assertNull(card.findViewWithTag("episode-card-text-scroll"));
            assertTrue(text(card).contains("Actual summary 4"));
            assertTrue(card.performClick());
            Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(3,intent.getIntExtra("season",-1));assertEquals(4,intent.getIntExtra("episode",-1));
            assertTrue(intent.getBooleanExtra("return_to_series",false));
            assertNotNull(tagged(selector,"series-metadata-source"));
            assertNotNull(root.findViewWithTag("mobile-bottom-navigation"));
            TvSeriesSelectionView restored = new TvSeriesSelectionView(activity,IMDB,new TvSeriesSelectionView.Listener(){
                public void onSelectionChanged(TvSeriesMetadata.Episode e,boolean present){} public void onPlay(TvSeriesMetadata.Episode e){} public void onSource(String url){}
            });
            restored.bind(metadata(IMDB));assertEquals(3,restored.selectedEpisode().season);assertEquals(4,restored.selectedEpisode().number);
        }
    }

    @Test public void phoneDescriptionsExpandFullyAndUnknownEpisodesCannotPlay() throws Exception {
        TitleDetailActivity activity=detail(true,IMDB);
        TvSeriesSelectionView selector=selector(activity);
        String full="Complete paragraph. ".repeat(120);
        TvSeriesMetadata.Episode longEpisode=new TvSeriesMetadata.Episode(1,2,"A complete episode title",45,"preview","","",NOW-1000,NOW,full,"2026-10-01");
        selector.bind(new TvSeriesMetadata(IMDB,"TVmaze (CC BY-SA)","https://www.tvmaze.com/shows/82/game-of-thrones",Arrays.asList(new TvSeriesMetadata.Season(1,Arrays.asList(longEpisode,episode(1,7,-1))))));
        TvEpisodeCard card=(TvEpisodeCard)tagged(selector,"episode:1:2");
        assertEquals(full,((TextView)card.findViewWithTag("episode-card-summary")).getText().toString());
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT,card.getLayoutParams().height);
        assertNull(((TextView)card.findViewWithTag("episode-card-summary")).getEllipsize());
        assertFalse(tagged(selector,"episode:1:7").isClickable());
        selector.showUnavailable();assertNull(selector.selectedEpisode());assertNotNull(watch(activity));
    }

}
