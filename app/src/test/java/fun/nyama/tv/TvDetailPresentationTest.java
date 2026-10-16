package fun.nyama.tv;

import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
import org.robolectric.annotation.Config;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.*;

/** Native integration only: no Activity onCreate, artwork HTTP, media or real account calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TvDetailPresentationTest {
    private static final String IMDB = "tt0944947";
    private final List<TitleDetailActivity> details = new ArrayList<>();
    private MainActivity live;

    @Before public void before() {
        for (String name : new String[]{"nyama_tv_private", "nyama_activation_mode", "nyama_tv_series_selection"})
            RuntimeEnvironment.getApplication().getSharedPreferences(name, 0).edit().clear().commit();
        ActivationAccess.deny();
    }

    @After public void after() throws Exception {
        for (TitleDetailActivity detail : details) {
            TvCinematicBackground background = (TvCinematicBackground) get(detail, "cinematicBackground");
            if (background != null) background.release();
            ((ExecutorService) get(detail, "io")).shutdownNow();
        }
        if (live != null) {
            ((ExecutorService) get(live, "io")).shutdownNow();
            ((ExecutorService) get(live, "scheduler")).shutdownNow();
        }
        setStatic(MainActivity.class, "activeInstance", new WeakReference<MainActivity>(null));
        posterCache().evictAll();
        ActivationAccess.deny();
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object get(Object object, String name) throws Exception { return field(object.getClass(), name).get(object); }
    private static void set(Object object, String name, Object value) throws Exception { field(object.getClass(), name).set(object, value); }
    private static void setStatic(Class<?> type, String name, Object value) throws Exception { field(type, name).set(null, value); }
    private static Object call(Object object, String name) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(object);
    }
    @SuppressWarnings("unchecked") private static LruCache<String, Bitmap> posterCache() throws Exception {
        return (LruCache<String, Bitmap>) field(PosterLoader.class, "MEMORY").get(null);
    }

    private TitleDetailActivity detail(boolean phone, String type) throws Exception {
        TitleDetailActivity detail = Robolectric.buildActivity(TitleDetailActivity.class,
                new Intent().putExtra("id", IMDB).putExtra("type", type).putExtra("title", "Synthetic title")).get();
        details.add(detail);
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(detail);
        config.setDeviceMode(phone ? fun.nyama.tv.Config.MODE_PHONE : fun.nyama.tv.Config.MODE_TV);
        config.setLanguage(fun.nyama.tv.Config.LANG_EN);
        set(detail, "config", config); set(detail, "favorites", new MovieFavorites(detail));
        set(detail, "id", IMDB); set(detail, "section", type); set(detail, "title", "Synthetic title");
        posterCache().put(config.pukankiBaseUrl() + "|" + IMDB, Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565));
        detail.setContentView((View) call(detail, "buildUi"));
        return detail;
    }

    private static List<View> views(View view) {
        List<View> all = new ArrayList<>(); all.add(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            all.addAll(views(((ViewGroup) view).getChildAt(i)));
        return all;
    }

    private static View tagged(View root, String tag) {
        for (View view : views(root)) if (tag.equals(view.getTag())) return view;
        throw new AssertionError("Missing " + tag);
    }

    @Test public void tvMoviePlacesTransparentForegroundOverSeparatePosterBackdrop() throws Exception {
        TitleDetailActivity detail = detail(false, "movies");
        TvCinematicBackground background = (TvCinematicBackground) get(detail, "cinematicBackground");
        assertNotNull(background);
        FrameLayout screen = (FrameLayout) background.getParent();
        assertSame(background, screen.getChildAt(0));
        assertTrue(screen.getChildAt(1) instanceof ScrollView);
        assertEquals(PukankiApi.posterUrl(detail, IMDB), background.currentRequestedUrl());
        assertFalse(background.isAttachedToWindow()); // This fixture must never initiate artwork HTTP.
        assertFalse(background.isFocusable());
        assertTrue(((Button) get(detail, "watchButton")).isEnabled());
    }

    @Test public void matchedExistingLandscapeMetadataIsUsedButDifferentTitleCannotReplaceIt() throws Exception {
        TitleDetailActivity detail = detail(false, "movies");
        TvCinematicBackground background = (TvCinematicBackground) get(detail, "cinematicBackground");
        Method render = TitleDetailActivity.class.getDeclaredMethod("renderInfo", JSONObject.class); render.setAccessible(true);
        render.invoke(detail, new JSONObject().put("imdbID", IMDB).put("backdrop", "https://images.example.com/landscape.jpg"));
        assertEquals("https://images.example.com/landscape.jpg", background.currentRequestedUrl());
        render.invoke(detail, new JSONObject().put("imdbID", "tt0903747").put("backdrop", "https://images.example.com/wrong.jpg"));
        assertEquals("https://images.example.com/landscape.jpg", background.currentRequestedUrl());
        render.invoke(detail, new JSONObject().put("imdbID", IMDB).put("backdrop", "file:///private/image.jpg"));
        assertEquals("https://images.example.com/landscape.jpg", background.currentRequestedUrl());
    }

    @Test public void episodeFocusUsesOriginalArtworkAndMissingArtRestoresPosterWithoutPausingLiveTv() throws Exception {
        live = Robolectric.buildActivity(MainActivity.class).get();
        setStatic(MainActivity.class, "activeInstance", new WeakReference<>(live));
        ExoPlayer player = (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(), new Class<?>[]{ExoPlayer.class},
                (proxy, method, args) -> { throw new AssertionError("Browsing touched Live TV: " + method.getName()); });
        set(live, "player", player);
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(live);
        config.setLastChannelKey("keep-current-channel");
        TitleDetailActivity detail = detail(false, "series");
        TvSeriesSelectionView selector = (TvSeriesSelectionView) get(detail, "seriesSelection");
        long now = 1_800_000_000_000L;
        selector.bind(new TvSeriesMetadata(IMDB, "TVmaze", "", Arrays.asList(new TvSeriesMetadata.Season(1, Arrays.asList(
                new TvSeriesMetadata.Episode(1, 1, "First", 42, "Summary", "https://static.tvmaze.com/medium.jpg",
                        "https://static.tvmaze.com/original.jpg", now - 1, now),
                new TvSeriesMetadata.Episode(1, 2, "Second", 42, "Summary", "", now - 1, now))))));
        View root = detail.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1920, 1080);
        assertTrue(tagged(selector, "episode:1:1").requestFocus());
        TvCinematicBackground background = (TvCinematicBackground) get(detail, "cinematicBackground");
        assertEquals("https://static.tvmaze.com/original.jpg", background.currentRequestedUrl());
        assertTrue(tagged(selector, "episode:1:2").requestFocus());
        assertEquals(PukankiApi.posterUrl(detail, IMDB), background.currentRequestedUrl());
        assertEquals("keep-current-channel", config.lastChannelKey());
        assertNull(Shadows.shadowOf(detail).getNextStartedActivity());
    }

    @Test public void phoneDetailKeepsOriginalScrollAndHasNoCinematicLayer() throws Exception {
        TitleDetailActivity detail = detail(true, "movies");
        assertNull(get(detail, "cinematicBackground"));
        ViewGroup content = detail.findViewById(android.R.id.content);
        assertTrue(content.getChildAt(0) instanceof ScrollView);
        assertFalse(views(content).stream().anyMatch(view -> view instanceof TvCinematicBackground));
    }

    @Test public void tvInformationRetainsWorkingAttributionLinksOutsideEpisodeSelector() throws Exception {
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).get();
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(settings);
        config.setDeviceMode(fun.nyama.tv.Config.MODE_TV); set(settings, "config", config);
        View ui = (View) call(settings, "buildUi");
        Button source = null, license = null;
        for (View view : views(ui)) if (view instanceof Button) {
            if ("TVmaze ↗".contentEquals(((Button) view).getText())) source = (Button) view;
            if ("CC BY-SA ↗".contentEquals(((Button) view).getText())) license = (Button) view;
        }
        assertNotNull(source); assertNotNull(license);
        source.performClick();
        assertEquals("https://www.tvmaze.com", Shadows.shadowOf(settings).getNextStartedActivity().getDataString());
        assertEquals(true, get(settings, "suppressNextUserLeaveHint"));
        license.performClick();
        assertEquals("https://creativecommons.org/licenses/by-sa/4.0/", Shadows.shadowOf(settings).getNextStartedActivity().getDataString());
        config.setDeviceMode(fun.nyama.tv.Config.MODE_PHONE);
        View phone = (View) call(settings, "buildUi");
        assertFalse(views(phone).stream().filter(view -> view instanceof TextView)
                .anyMatch(view -> ((TextView) view).getText().toString().contains("TVmaze")));
    }
}
