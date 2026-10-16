package fun.nyama.tv;

import android.app.Application;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
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
        for (String preferences : new String[] { "nyama_tv_private", "nyama_tv_series_selection", "nyama_plus_media_favorites" }) {
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
                "<p>Actual summary " + number + "</p>", "", airStamp, NOW);
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

    private static Button tagged(TvSeriesSelectionView selector, String tag) {
        for (View view : views(selector)) if (view instanceof Button && tag.equals(view.getTag())) return (Button) view;
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
        assertFalse(tagged(selector, "episode:3:7").isEnabled());
        assertNull(tagged(selector, "episode:3:9"));
        assertTrue(text(selector).contains("Air date is unconfirmed"));
        assertTrue(text(selector).contains("Provider availability is not confirmed"));
        assertTrue(text(selector).contains("Actual title 4"));
        assertTrue(text(selector).contains("Actual summary 4"));
        assertTrue(text(selector).contains("42 min"));
        assertFalse(text(selector).contains("<p>"));
    }

    @Test public void episodeOkAndWatchLaunchExactEpisodeWithBoundParentIdentity() throws Exception {
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
        watch(activity).performClick();
        Intent watch = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(3, watch.getIntExtra("season", -1));
        assertEquals(4, watch.getIntExtra("episode", -1));
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
        Button season = tagged(selector, "season:3");
        assertTrue(season.requestFocus());
        key(season, KeyEvent.KEYCODE_DPAD_DOWN);
        Button first = tagged(selector, "episode:3:2");
        assertTrue(first.hasFocus());
        assertEquals(UiTheme.accent(activity), ((GradientDrawable) first.getBackground()).getColor().getDefaultColor());
        assertEquals(UiTheme.contrastingTextColor(UiTheme.accent(activity)), first.getCurrentTextColor());
        key(first, KeyEvent.KEYCODE_DPAD_UP);
        assertTrue(season.hasFocus());
        assertTrue(text(season).contains("✓"));
        assertTrue(views(selector).stream().anyMatch(view -> view instanceof HorizontalScrollView));
        assertTrue(views(selector).stream().anyMatch(view -> view instanceof ScrollView));
    }

    @Test public void unknownDatesDisableWatchAndFutureOnlyMetadataDoesNotUseGenericFallback() throws Exception {
        TitleDetailActivity activity = detail(false, IMDB);
        TvSeriesSelectionView selector = selector(activity);
        selector.bind(new TvSeriesMetadata(IMDB, "TVmaze (CC BY-SA)", "", Arrays.asList(
                new TvSeriesMetadata.Season(2, Arrays.asList(episode(2, 1, -1))))));
        assertFalse(watch(activity).isEnabled());
        assertFalse(tagged(selector, "episode:2:1").isEnabled());
        assertNull(selector.selectedEpisode());
        selector.bind(new TvSeriesMetadata(IMDB, "TVmaze (CC BY-SA)", "", Arrays.asList(
                new TvSeriesMetadata.Season(2, Arrays.asList(episode(2, 1, NOW + 1000))))));
        assertFalse(watch(activity).isEnabled());
        assertNull(tagged(selector, "season:2"));
        assertTrue(text(selector).contains("No confirmed aired episodes"));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void mismatchOrUnavailableMetadataPreservesGenericSeriesFallback() throws Exception {
        TitleDetailActivity activity = detail(false, IMDB);
        TvSeriesSelectionView selector = selector(activity);
        selector.bind(metadata("tt0903747"));
        assertFalse(selector.hasMetadata());
        assertTrue(watch(activity).isEnabled());
        assertTrue(text(selector).contains("could not be loaded"));
        watch(activity).performClick();
        Intent fallback = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(IMDB, fallback.getStringExtra("id"));
        assertEquals("series", fallback.getStringExtra("type"));
        assertFalse(fallback.hasExtra("season"));
        assertFalse(fallback.hasExtra("episode"));
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

    @Test public void attributionOnlyOpensValidatedTvmazeShowLinks() throws Exception {
        assertEquals("", TvSeriesSelectionView.safeSourceUrl("https://evil.invalid/shows/82"));
        assertEquals("", TvSeriesSelectionView.safeSourceUrl("javascript:alert(1)"));
        assertEquals("", TvSeriesSelectionView.safeSourceUrl("https://www.tvmaze.com@evil.invalid/shows/82"));
        assertEquals("", TvSeriesSelectionView.safeSourceUrl("https://www.tvmaze.com/shows/82?next=https://evil.invalid"));
        TitleDetailActivity activity = detail(false, IMDB);
        TvSeriesSelectionView selector = selector(activity);
        selector.bind(metadata(IMDB));
        Button source = null;
        for (View view : views(selector)) {
            if (view instanceof Button && ((Button) view).getText().toString().contains("CC BY-SA")) source = (Button) view;
        }
        assertNotNull(source);
        source.performClick();
        Intent link = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, link.getAction());
        assertEquals("https://www.tvmaze.com/shows/82/game-of-thrones", link.getDataString());
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
            Button first = tagged(selector, "episode:1:1");
            Button last = tagged(selector, "episode:1:40");
            assertTrue(first.getLocalVisibleRect(new Rect()));
            assertFalse(last.getLocalVisibleRect(new Rect()));
            Bitmap bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
            BitmapDrawable firstImage = new BitmapDrawable(selector.getResources(), bitmap);
            BitmapDrawable lastImage = new BitmapDrawable(selector.getResources(), bitmap);
            firstImage.setBounds(0, 0, 32, 18);
            lastImage.setBounds(0, 0, 32, 18);
            first.setCompoundDrawablesRelative(firstImage, null, null, null);
            last.setCompoundDrawablesRelative(lastImage, null, null, null);
            Field markers = TvSeriesSelectionView.class.getDeclaredField("requestedStills");
            markers.setAccessible(true);
            @SuppressWarnings("unchecked") Set<Button> requested = (Set<Button>) markers.get(selector);
            requested.add(first); requested.add(last);
            Method loadVisible = TvSeriesSelectionView.class.getDeclaredMethod("loadVisibleStills");
            loadVisible.setAccessible(true);
            loadVisible.invoke(selector);
            assertSame(firstImage, first.getCompoundDrawablesRelative()[0]);
            assertNull(last.getCompoundDrawablesRelative()[0]);
            assertTrue(requested.contains(first));
            assertFalse(requested.contains(last));

            ScrollView viewport = null;
            for (View view : views(selector)) if (view instanceof ScrollView) viewport = (ScrollView) view;
            assertNotNull(viewport);
            viewport.scrollTo(0, viewport.getChildAt(0).getHeight());
            assertFalse(first.getLocalVisibleRect(new Rect()));
            assertTrue(last.getLocalVisibleRect(new Rect()));
            last.setCompoundDrawablesRelative(lastImage, null, null, null);
            requested.add(last);
            loadVisible.invoke(selector);
            assertNull(first.getCompoundDrawablesRelative()[0]);
            assertSame(lastImage, last.getCompoundDrawablesRelative()[0]);
            assertFalse(requested.contains(first));
            assertTrue(requested.contains(last));
        } finally {
            host.pause().stop().destroy();
        }
    }

    @Test public void phoneDetailsKeepOriginalVerticalLayoutAndGenericPlayer() throws Exception {
        TitleDetailActivity activity = detail(true, IMDB);
        assertNull(selector(activity));
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
}
