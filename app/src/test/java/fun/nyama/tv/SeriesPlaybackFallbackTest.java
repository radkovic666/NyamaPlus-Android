package fun.nyama.tv;

import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.*;

/** Metadata-unavailable fallback checks; no provider HTTP calls or hardware playback. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SeriesPlaybackFallbackTest {
    private final List<TitleDetailActivity> activities = new ArrayList<>();

    @Before public void before() {
        RuntimeEnvironment.getApplication().getSharedPreferences("nyama_tv_private", 0).edit().clear().commit();
        RuntimeEnvironment.getApplication().getSharedPreferences("nyama_plus_media_favorites", 0).edit().clear().commit();
    }

    @After public void after() throws Exception {
        for (TitleDetailActivity activity : activities) {
            TvCinematicBackground background=(TvCinematicBackground)field("cinematicBackground").get(activity);
            if(background!=null)background.release();
            ((ExecutorService) field("io").get(activity)).shutdownNow();
        }
        posterCache().evictAll();
    }

    private TitleDetailActivity detail(boolean phone, String id, String title, boolean counts) throws Exception {
        return detail(phone, "series", id, title, counts);
    }

    private TitleDetailActivity detail(boolean phone, String type, String id, String title, boolean counts) throws Exception {
        Intent intent = new Intent().putExtra("id", id).putExtra("type", type).putExtra("title", title);
        if (counts) intent.putExtra("seasons", "8").putExtra("episodeCount", "73");
        // Build only the native screen: onCreate would initiate title-info HTTP requests.
        TitleDetailActivity activity = Robolectric.buildActivity(TitleDetailActivity.class, intent).get();
        activities.add(activity);
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(activity);
        config.saveUrls("https://example.invalid/test-playlist.m3u", "https://example.invalid/test-epg.xml");
        config.setPin("123456");
        config.setDeviceMode(phone ? fun.nyama.tv.Config.MODE_PHONE : fun.nyama.tv.Config.MODE_TV);
        config.setLanguage(fun.nyama.tv.Config.LANG_EN);
        field("config").set(activity, config);
        field("favorites").set(activity, new MovieFavorites(activity));
        field("id").set(activity, id);
        field("section").set(activity, type);
        field("title").set(activity, title);
        // Seed the existing in-memory poster cache so buildUi never starts a network request.
        posterCache().put(config.pukankiBaseUrl() + "|" + id,
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
        Method build = TitleDetailActivity.class.getDeclaredMethod("buildUi");
        build.setAccessible(true);
        activity.setContentView((View) build.invoke(activity));
        return activity;
    }

    @SuppressWarnings("unchecked")
    private static LruCache<String, Bitmap> posterCache() throws Exception {
        Field cache = PosterLoader.class.getDeclaredField("MEMORY");
        cache.setAccessible(true);
        return (LruCache<String, Bitmap>) cache.get(null);
    }

    private static Field field(String name) throws Exception {
        Field field = TitleDetailActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static List<View> views(View root) {
        List<View> result = new ArrayList<>();
        result.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) result.addAll(views(group.getChildAt(i)));
        }
        return result;
    }

    private static List<Button> buttons(TitleDetailActivity activity) {
        List<Button> buttons = new ArrayList<>();
        for (View view : views(activity.getWindow().getDecorView())) {
            if (view instanceof Button) buttons.add((Button) view);
        }
        return buttons;
    }

    private static Button watch(TitleDetailActivity activity) {
        for (Button button : buttons(activity)) {
            if (button.getText().toString().equals(activity.getString(R.string.watch_now))) return button;
        }
        throw new AssertionError("Watch action missing");
    }

    private static void render(TitleDetailActivity activity, JSONObject info) throws Exception {
        Method render = TitleDetailActivity.class.getDeclaredMethod("renderInfo", JSONObject.class);
        render.setAccessible(true);
        render.invoke(activity, info);
    }

    private static Intent launchEpisode(TitleDetailActivity activity) throws Exception {
        TvSeriesSelectionView selector=(TvSeriesSelectionView)field("seriesSelection").get(activity);
        String imdb=(String)field("id").get(activity);long now=System.currentTimeMillis();
        selector.bind(new TvSeriesMetadata(imdb,"TVmaze","",java.util.Arrays.asList(new TvSeriesMetadata.Season(3,java.util.Arrays.asList(
                new TvSeriesMetadata.Episode(3,4,"Exact episode",42,"Description","",now-1000,now))))));
        assertNull(field("watchButton").get(activity));
        assertTrue(selector.findViewWithTag("episode:3:4").performClick());
        Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(MoviePlayerActivity.class.getName(), intent.getComponent().getClassName());
        return intent;
    }

    @Test public void aggregateCountsDoNotInventAvailableEpisodeSelectors() throws Exception {
        TitleDetailActivity activity = detail(false, "tt0944947", "Game of Thrones", true);
        render(activity, new JSONObject());
        // Counts describe the catalogue title; they do not establish playable episode availability.
        assertEquals(2, buttons(activity).size()); // Back and Favorite; no generic Watch fallback.
        assertNull(field("watchButton").get(activity));
        assertFalse(((TvSeriesSelectionView) field("seriesSelection").get(activity)).hasMetadata());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void missingTvEpisodeDetailsOfferGuidanceAndKeepFavoritesWorkingWithoutWatch() throws Exception {
        TitleDetailActivity activity = detail(false, "tt0903747", "Breaking Bad", false);
        render(activity, new JSONObject());
        assertEquals(activity.getString(R.string.info_unavailable),
                ((TextView) field("details").get(activity)).getText().toString());
        TvSeriesSelectionView selector = (TvSeriesSelectionView) field("seriesSelection").get(activity);
        selector.showUnavailable();
        assertNull(field("watchButton").get(activity));
        assertTrue(views(selector).stream().anyMatch(view -> view instanceof TextView
                && ((TextView) view).getText().toString().contains("Reopen this series")));
        Button favorite = (Button) field("favoriteButton").get(activity);
        assertFalse(new MovieFavorites(activity).isFavorite("tt0903747"));
        favorite.performClick();
        assertTrue(new MovieFavorites(activity).isFavorite("tt0903747"));
        favorite.performClick();
        assertFalse(new MovieFavorites(activity).isFavorite("tt0903747"));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void unrelatedReturnedMetadataNeverChangesPlaybackTitleIdentity() throws Exception {
        TitleDetailActivity first = detail(true, "tt0944947", "Game of Thrones", true);
        render(first, new JSONObject().put("imdbID", "tt0903747").put("tmdbID", "1396")
                .put("Type", "movie").put("Plot", "Unrelated metadata"));
        TitleDetailActivity second = detail(true, "tt0903747", "Breaking Bad", false);
        Intent firstIntent = launchEpisode(first);
        Intent secondIntent = launchEpisode(second);
        assertEquals("tt0944947", firstIntent.getStringExtra("id"));
        assertEquals("Game of Thrones", firstIntent.getStringExtra("title"));
        assertEquals("series", firstIntent.getStringExtra("type"));
        assertEquals("tt0903747", secondIntent.getStringExtra("id"));
        assertEquals("Breaking Bad", secondIntent.getStringExtra("title"));
        assertEquals("series", secondIntent.getStringExtra("type"));
        assertEquals(3,firstIntent.getIntExtra("season",-1));assertEquals(4,firstIntent.getIntExtra("episode",-1));
        assertEquals(3,secondIntent.getIntExtra("season",-1));assertEquals(4,secondIntent.getIntExtra("episode",-1));
    }

    @Test public void phoneSeriesRetainsVerticalDetailsAndFullWidthFavoritesWithoutGenericWatch() throws Exception {
        TitleDetailActivity activity = detail(true, "tt0944947", "Game of Thrones", true);
        ScrollView scroll = null;
        for (View view : views(activity.getWindow().getDecorView())) {
            if (view instanceof ScrollView) { scroll = (ScrollView) view; break; }
        }
        assertNotNull(scroll);
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);
        LinearLayout body = (LinearLayout) root.getChildAt(1);
        assertEquals(LinearLayout.VERTICAL, body.getOrientation());
        LinearLayout poster = (LinearLayout) body.getChildAt(0);
        assertEquals(2, poster.getChildCount()); // Poster and existing phone title underneath it.
        assertEquals("Game of Thrones", ((TextView) poster.getChildAt(1)).getText().toString());
        assertNull(field("watchButton").get(activity));
        Button favorite=(Button)field("favoriteButton").get(activity);
        LinearLayout actions=(LinearLayout)favorite.getParent();assertEquals(1,actions.getChildCount());
        LinearLayout.LayoutParams layout=(LinearLayout.LayoutParams)favorite.getLayoutParams();
        assertEquals(0,layout.width);assertEquals(1f,layout.weight,0f);assertEquals(0,layout.leftMargin);
        assertEquals(2,buttons(activity).size());
        favorite.performClick();assertTrue(new MovieFavorites(activity).isFavorite("tt0944947"));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void tvMovieRetainsWatchAndRequestsReturnThroughItsExistingDetail() throws Exception {
        TitleDetailActivity activity = detail(false, "movies", "tt0111161", "A movie", false);
        assertNull(field("seriesSelection").get(activity));
        assertTrue(watch(activity).isEnabled());
        watch(activity).performClick();
        org.robolectric.shadows.ShadowActivity.IntentForResult request =
                Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull(request);
        assertSame(request.intent, Shadows.shadowOf(activity).getNextStartedActivity());
        assertEquals(MoviePlayerActivity.class.getName(), request.intent.getComponent().getClassName());
        assertEquals("movies", request.intent.getStringExtra("origin_section"));
        assertTrue(request.intent.getBooleanExtra(MoviePlayerActivity.EXTRA_RETURN_TO_CATALOG, false));
        assertTrue(request.requestCode >= 0);
        assertFalse(activity.isFinishing());
        activity.onActivityResult(request.requestCode, android.app.Activity.RESULT_CANCELED, null);
        assertFalse(activity.isFinishing());
        activity.onActivityResult(request.requestCode, android.app.Activity.RESULT_OK,
                new Intent().putExtra(MoviePlayerActivity.EXTRA_CONFIRMED_TV_EXIT, true));
        assertTrue(activity.isFinishing());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void movieExitResultDoesNotFinishSeriesOrPhoneDetail() throws Exception {
        TitleDetailActivity series = detail(false, "tt0903747", "Breaking Bad", false);
        TitleDetailActivity phone = detail(true, "movies", "tt0111161", "A movie", false);
        Field requestField = TitleDetailActivity.class.getDeclaredField("TV_MOVIE_PLAYBACK_REQUEST");
        requestField.setAccessible(true);
        int request = requestField.getInt(null);
        Intent result = new Intent().putExtra(MoviePlayerActivity.EXTRA_CONFIRMED_TV_EXIT, true);
        series.onActivityResult(request, android.app.Activity.RESULT_OK, result);
        phone.onActivityResult(request, android.app.Activity.RESULT_OK, result);
        assertFalse(series.isFinishing());
        assertFalse(phone.isFinishing());
        assertNull(Shadows.shadowOf(series).getNextStartedActivity());
        assertNull(Shadows.shadowOf(phone).getNextStartedActivity());
    }

    @Test public void seriesDetailBackFinishesToItsExistingSeriesCatalogWithoutStartingAnotherActivity() throws Exception {
        TitleDetailActivity activity = detail(false, "tt0903747", "Breaking Bad", false);
        assertEquals("series", activity.getIntent().getStringExtra("type"));
        for (Button button : buttons(activity)) if ("‹".contentEquals(button.getText())) button.performClick();
        assertTrue(activity.isFinishing());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }
}
