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
                new Intent().putExtra("id", IMDB).putExtra("type", type).putExtra("title", "Synthetic title")
                        .putExtra("year", "2019").putExtra("rating", "8.4").putExtra("genres", "Drama")
                        .putExtra("seasons", "3").putExtra("episodeCount", "24")).get();
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

    @Test public void mainSeriesMetadataRemainsAboveEpisodesBeforeAndAfterEnrichment() throws Exception {
        TitleDetailActivity detail = detail(false, "series");
        TextView info = (TextView) get(detail, "details");
        assertTrue(info.getText().toString().contains("2019"));
        assertTrue(info.getText().toString().contains("8.4"));
        Method render = TitleDetailActivity.class.getDeclaredMethod("renderInfo", JSONObject.class); render.setAccessible(true);
        render.invoke(detail, new JSONObject().put("overview", "Existing series description")
                .put("first_air_date", "2019-05-20"));
        String enriched = info.getText().toString();
        assertTrue(enriched.contains("Existing series description")); assertTrue(enriched.contains("Drama"));
        assertTrue(enriched.contains("24"));
        TvSeriesSelectionView selector = (TvSeriesSelectionView) get(detail, "seriesSelection");
        long now = System.currentTimeMillis();
        selector.bind(new TvSeriesMetadata(IMDB, "TVmaze", "", Arrays.asList(new TvSeriesMetadata.Season(1, Arrays.asList(
                new TvSeriesMetadata.Episode(1, 1, "Episode title", 42, "Episode summary", "", now - 1, now))))));
        assertEquals(enriched, info.getText().toString()); assertEquals(View.VISIBLE, info.getVisibility());
        assertSame(info.getParent(), selector.getParent());
        ViewGroup parent = (ViewGroup) info.getParent();
        assertTrue(parent.indexOfChild(info) < parent.indexOfChild(selector));
        assertNotNull(tagged(selector, "episode:1:1"));
        selector.showUnavailable();
        assertEquals(enriched, info.getText().toString());
    }

    @Test public void phoneMovieBackdropKeepsSharpPosterWatchAndPersistentNavigation() throws Exception {
        TitleDetailActivity detail = detail(true, "movies");
        TvCinematicBackground background=(TvCinematicBackground)get(detail,"cinematicBackground");assertNotNull(background);
        assertEquals(PukankiApi.posterUrl(detail,IMDB),background.currentRequestedUrl());
        ViewGroup content = detail.findViewById(android.R.id.content);
        ViewGroup screen=(ViewGroup)content.getChildAt(0);
        assertSame(background,screen.getChildAt(0));
        ViewGroup foreground=(ViewGroup)screen.getChildAt(1);
        assertEquals(android.graphics.Color.TRANSPARENT,((android.graphics.drawable.ColorDrawable)foreground.getBackground()).getColor());
        assertTrue(foreground.getChildAt(0) instanceof ScrollView);
        assertEquals(0x80090B0E,((android.graphics.drawable.ColorDrawable)foreground.getChildAt(0).getBackground()).getColor());
        assertTrue(views(foreground).stream().anyMatch(v->v instanceof android.widget.ImageView && ((android.widget.ImageView)v).getScaleType()==android.widget.ImageView.ScaleType.FIT_CENTER));
        Button watch=(Button)get(detail,"watchButton");assertNotNull(watch);watch.performClick();
        Intent player=Shadows.shadowOf(detail).getNextStartedActivity();assertEquals("movies",player.getStringExtra("type"));assertEquals(IMDB,player.getStringExtra("id"));
        for(int[] size:new int[][]{{360,800},{800,360}}) {
            screen.measure(View.MeasureSpec.makeMeasureSpec(size[0],View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size[1],View.MeasureSpec.EXACTLY));screen.layout(0,0,size[0],size[1]);
            assertEquals(size[0],background.getWidth());assertEquals(size[1],background.getHeight());
            assertTrue(foreground.getChildAt(0).getBottom()<=foreground.getChildAt(1).getTop());
        }
        MobileBottomNav nav=screen.findViewWithTag("mobile-bottom-navigation");assertNotNull(nav);
        nav.revealTemporarily();nav.hideNow();assertEquals(View.VISIBLE,nav.getVisibility());
        nav.findViewWithTag("mobile-nav-movies").performClick();
        Intent destination=Shadows.shadowOf(detail).getNextStartedActivity();
        assertEquals(CatalogActivity.class.getName(),destination.getComponent().getClassName());
        assertEquals("movies",destination.getStringExtra(CatalogActivity.EXTRA_SECTION));
        assertTrue(views(content).stream().anyMatch(view -> view instanceof TvCinematicBackground));
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
    @Test public void phoneSeriesRetainsDarkDetailsAndNavigationWithoutMovieBackdropOrWatch() throws Exception {
        TitleDetailActivity detail=detail(true,"series");
        assertNull(get(detail,"cinematicBackground"));assertNull(get(detail,"watchButton"));
        assertNotNull(get(detail,"favoriteButton"));assertNotNull(detail.findViewById(android.R.id.content).findViewWithTag("mobile-bottom-navigation"));
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void mobileMovieRendersReadablePosterBackdropInPortraitAndLandscape() throws Exception {
        TitleDetailActivity detail=detail(true,"movies");
        TvCinematicBackground background=(TvCinematicBackground)get(detail,"cinematicBackground");
        FrameLayout screen=(FrameLayout)background.getParent();View foreground=screen.getChildAt(1);
        Bitmap poster=Bitmap.createBitmap(360,640,Bitmap.Config.RGB_565);
        android.graphics.Canvas paint=new android.graphics.Canvas(poster);android.graphics.Paint brush=new android.graphics.Paint();
        brush.setShader(new android.graphics.LinearGradient(0,0,360,640,0xFF0D77BB,0xFFED8A39,android.graphics.Shader.TileMode.CLAMP));paint.drawRect(0,0,360,640,brush);
        brush.setShader(null);brush.setColor(0xFFF1D7AD);paint.drawRect(70,130,290,510,brush);
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();poster.compress(Bitmap.CompressFormat.PNG,100,bytes);
        Bitmap blurred=TvArtworkLoader.decode(bytes.toByteArray(),true,360,640);
        android.widget.ImageView[] layers=(android.widget.ImageView[])field(TvCinematicBackground.class,"images").get(background);
        layers[0].setImageBitmap(blurred);layers[0].setAlpha(1f);
        for(View v:views(foreground))if(v instanceof android.widget.ImageView&&((android.widget.ImageView)v).getScaleType()==android.widget.ImageView.ScaleType.FIT_CENTER)((android.widget.ImageView)v).setImageBitmap(poster);
        Method render=TitleDetailActivity.class.getDeclaredMethod("renderInfo",JSONObject.class);render.setAccessible(true);
        render.invoke(detail,new JSONObject().put("Plot","A cinematic story, with the original movie details and readable controls.").put("Director","Sample director"));
        for(int[] size:new int[][]{{360,800},{800,360}}) {
            screen.measure(View.MeasureSpec.makeMeasureSpec(size[0],View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size[1],View.MeasureSpec.EXACTLY));screen.layout(0,0,size[0],size[1]);
            Bitmap image=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);screen.draw(new android.graphics.Canvas(image));
            assertEquals(android.widget.ImageView.ScaleType.CENTER_CROP,layers[0].getScaleType());
            int edge=image.getPixel(5,100);assertTrue(android.graphics.Color.red(edge)<130&&android.graphics.Color.green(edge)<130&&android.graphics.Color.blue(edge)<130);
            assertNotEquals(android.graphics.Color.BLACK,edge);
            String directory=System.getenv("NYAMA_UI_ARTIFACTS");
            if(directory!=null){java.io.File folder=new java.io.File(directory);assertTrue(folder.isDirectory()||folder.mkdirs());try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(folder,"mobile-movie-"+size[0]+"x"+size[1]+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));}}
            image.recycle();
        }
        background.release();blurred.recycle();poster.recycle();
    }

}
