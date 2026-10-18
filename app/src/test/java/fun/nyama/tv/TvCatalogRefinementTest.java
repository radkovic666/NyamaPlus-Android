package fun.nyama.tv;

import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.os.Handler;
import android.util.LruCache;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;

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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Native TV focus/reset tests with synthetic poster caches; no provider or hardware access. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TvCatalogRefinementTest {
    private CatalogActivity activity;
    private GridView grid;
    private View root;
    private QueueExecutor io;
    private ActivityController<CatalogActivity> controller;
    private final List<TvCinematicBackground> artwork = new ArrayList<>();

    @Before public void before() throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("nyama_tv_private", 0).edit().clear().commit();
        RuntimeEnvironment.getApplication().getSharedPreferences("nyama_plus_catalog_state", 0).edit().clear().commit();
        RuntimeEnvironment.getApplication().getSharedPreferences("nyama_plus_media_favorites", 0).edit().clear().commit();
        clearApiCaches();
    }

    @After public void after() throws Exception {
        for (TvCinematicBackground background : artwork) background.release();
        if (activity != null) invoke("onDestroy");
        clearApiCaches();
    }

    private void catalog(String section, boolean phone) throws Exception {
        controller = Robolectric.buildActivity(CatalogActivity.class);
        activity = controller.get();
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(activity);
        config.setDeviceMode(phone ? fun.nyama.tv.Config.MODE_PHONE : fun.nyama.tv.Config.MODE_TV);
        set("config", config);
        set("favorites", new MovieFavorites(activity));
        set("section", section);
        io = new QueueExecutor();
        set("io", io);
        root = (View) invoke("buildUi");
        activity.setContentView(root);
        controller.visible();
        grid = (GridView) get("grid");
        // Use a deterministic bitmap decoder instead of downloading artwork in focus tests.
        TvCinematicBackground original = (TvCinematicBackground) get("cinematicBackground");
        if (original != null) {
            original.release();
            Bitmap image = Bitmap.createBitmap(32, 18, Bitmap.Config.RGB_565);
            TvCinematicBackground fake = new TvCinematicBackground(activity,
                    new TvArtworkLoader(activity, (url, portrait, width, height) -> image),
                    new TvArtworkLoader(activity, (url, portrait, width, height) -> image));
            artwork.add(fake);
            set("cinematicBackground", fake);
        }
        layout();
    }

    private static void clearApiCaches() throws Exception {
        for (String name : new String[]{"RAW_CATALOG_CACHE", "POSTER_CATALOG_CACHE", "TV_POSTER_AVAILABILITY", "POSTER_AVAILABILITY"}) {
            Field field = PukankiApi.class.getDeclaredField(name);
            field.setAccessible(true);
            ((Map<?, ?>) field.get(null)).clear();
        }
        Field posters = PosterLoader.class.getDeclaredField("MEMORY");
        posters.setAccessible(true);
        ((LruCache<?, ?>) posters.get(null)).evictAll();
    }

    private List<CatalogItem> titles(int count) throws Exception {
        List<CatalogItem> items = new ArrayList<>();
        Bitmap thumbnail = Bitmap.createBitmap(8, 12, Bitmap.Config.RGB_565);
        Field posterMemory = PosterLoader.class.getDeclaredField("MEMORY");
        posterMemory.setAccessible(true);
        @SuppressWarnings("unchecked") LruCache<String, Bitmap> memory = (LruCache<String, Bitmap>) posterMemory.get(null);
        Field available = PukankiApi.class.getDeclaredField("TV_POSTER_AVAILABILITY");
        available.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Boolean> availability = (Map<String, Boolean>) available.get(null);
        Field fastAvailable = PukankiApi.class.getDeclaredField("POSTER_AVAILABILITY");
        fastAvailable.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Boolean> fastAvailability = (Map<String, Boolean>) fastAvailable.get(null);
        String base = new fun.nyama.tv.Config(activity).pukankiBaseUrl();
        for (int i = 0; i < count; i++) {
            String id = "tt" + (1000000 + i);
            items.add(new CatalogItem(id, "movie", "Movie " + i, "", "2024", "", "", "", "", "", "", ""));
            memory.put(base + "|" + id, thumbnail);
            availability.put(base + "|" + id, true);
            fastAvailability.put(base + "|" + id, true);
        }
        return items;
    }

    private void cacheVisible(String section, List<CatalogItem> items) {
        PukankiApi.cacheVisibleTvCatalog(activity, section, Collections.emptyList(), "reviews",
                false, new MovieFavorites(activity).csv(), items);
    }

    private void cacheRaw(String section, List<CatalogItem> items) throws Exception {
        Method key = PukankiApi.class.getDeclaredMethod("rawCatalogKey", android.content.Context.class,
                String.class, List.class, String.class, boolean.class, String.class);
        key.setAccessible(true);
        String cacheKey = (String) key.invoke(null, activity, section, Collections.emptyList(), "reviews", false, "");
        Class<?> snapshot = Class.forName("fun.nyama.tv.PukankiApi$PosterCatalogSnapshot");
        Constructor<?> constructor = snapshot.getDeclaredConstructor(List.class, long.class);
        constructor.setAccessible(true);
        Field cache = PukankiApi.class.getDeclaredField("RAW_CATALOG_CACHE");
        cache.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Object> raw = (Map<String, Object>) cache.get(null);
        raw.put(cacheKey, constructor.newInstance(items, System.currentTimeMillis()));
    }

    private void adapterItems(List<CatalogItem> items) throws Exception {
        BaseAdapter adapter = (BaseAdapter) get("adapter");
        Method setItems = adapter.getClass().getDeclaredMethod("setItems", List.class);
        setItems.setAccessible(true);
        setItems.invoke(adapter, items);
    }

    private Field field(String name) throws Exception {
        Field field = CatalogActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private Object get(String name) throws Exception { return field(name).get(activity); }
    private void set(String name, Object value) throws Exception { field(name).set(activity, value); }
    private Object invoke(String name) throws Exception {
        Method method = CatalogActivity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(activity);
    }
    private void layout() {
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1920, 1080);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private EditText search() throws Exception { return (EditText) get("search"); }
    @SuppressWarnings("unchecked") private List<String> genres() throws Exception { return (List<String>) get("selectedGenres"); }
    private void reset() throws Exception { assertTrue(((Button) get("searchClearButton")).performClick()); }

    @Test public void horizontalRowsReuseCardStyleKeepSidebarNavigationAndOpenExactTitle() throws Exception {
        catalog("movies", false);
        List<CatalogItem> cached = titles(18);
        List<CatalogItem> observed = new ArrayList<>();
        for (int i = 0; i < cached.size(); i++) {
            CatalogItem item = cached.get(i);
            observed.add(new CatalogItem(item.id, "movie", item.title, "Original", "2024", "", "90",
                    i < 6 ? "Action" : i < 12 ? "Comedy" : "Drama", "8.1", "200", "", ""));
        }
        activity.getSharedPreferences("nyama_catalog_rows", 0).edit().clear().commit();
        List<CatalogRows.Row> rows = CatalogRows.observeAndRead(activity, "movies", observed, System.currentTimeMillis());
        Method render = CatalogActivity.class.getDeclaredMethod("renderRows", List.class); render.setAccessible(true);
        render.invoke(activity, rows); layout();
        View home = (View) get("homeRows");
        assertEquals(View.VISIBLE, home.getVisibility()); assertEquals(View.GONE, grid.getVisibility());
        @SuppressWarnings("unchecked") List<View> cards = (List<View>) get("rowCards");
        assertTrue(cards.size() > 6 && cards.size() <= 42);
        View first = cards.get(0); assertTrue(first.requestFocus()); layout();
        int accent = UiTheme.accent(activity);
        assertEquals(accent, ((android.graphics.drawable.GradientDrawable) first.getBackground()).getColor().getDefaultColor());
        assertEquals(UiTheme.contrastingTextColor(accent), ((android.widget.TextView) ((LinearLayout) first).getChildAt(1)).getCurrentTextColor());
        assertTrue(first.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT)));
        AppSidebar sidebar = (AppSidebar) get("sidebar"); assertTrue(sidebar.hasMenuFocus());
        activity.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
        assertTrue(first.hasFocus());
        assertEquals(0, io.jobs.size()); // Focus/navigation must never fetch catalog data.
        assertTrue(first.performClick());
        Intent detail = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(rows.get(0).items.get(0).id, detail.getStringExtra("id"));
        assertEquals("movies", detail.getStringExtra("type")); assertEquals("Original", detail.getStringExtra("originalTitle"));
        set("page", 2); invoke("updateDailyRow");
        assertEquals(View.GONE, home.getVisibility()); assertEquals(View.VISIBLE, grid.getVisibility());
    }

    @Test public void tvXResetsAllFiltersAndUsesCompleteValidatedCacheWithoutNetwork() throws Exception {
        catalog("movies", false);
        List<CatalogItem> all = titles(35);
        cacheVisible("movies", all);
        search().setText("old search");
        genres().add("Drama"); set("favoritesOnly", true); set("sortMode", "rating"); set("page", 8);
        reset(); layout();
        assertEquals("", search().getText().toString());
        assertTrue(genres().isEmpty()); assertEquals(false, get("favoritesOnly"));
        assertEquals("reviews", get("sortMode")); assertEquals(1, get("page"));
        assertEquals(35, get("total")); assertEquals(2, get("totalPages"));
        assertEquals(30, grid.getAdapter().getCount());
        assertEquals(0, grid.getSelectedItemPosition()); assertTrue(grid.hasFocus());
        assertEquals(0, io.jobs.size());
        assertEquals(View.VISIBLE, ((Button) get("searchClearButton")).getVisibility());
        assertTrue(((Button) get("searchClearButton")).isFocusable());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void seriesXResetsCurrentSectionOnlyAndRemainsAvailableWithEmptySearch() throws Exception {
        catalog("series", false);
        List<CatalogItem> series = titles(4);
        cacheVisible("series", series);
        activity.getSharedPreferences("nyama_plus_catalog_state", 0).edit()
                .putString("movies:query", "keep movie query").putString("movies:sort", "title").commit();
        genres().add("Comedy"); set("favoritesOnly", true); set("sortMode", "year_asc");
        reset();
        assertEquals("series", get("section")); assertEquals(4, get("total"));
        assertEquals("keep movie query", activity.getSharedPreferences("nyama_plus_catalog_state", 0)
                .getString("movies:query", ""));
        assertEquals("title", activity.getSharedPreferences("nyama_plus_catalog_state", 0).getString("movies:sort", ""));
        assertNotNull(get("cinematicBackground")); assertEquals(0, io.jobs.size());
        reset();
        assertEquals(4, get("total")); assertEquals(0, io.jobs.size());
    }

    @Test public void resetLoadsOnlyVisiblePageWithoutFullCatalogSweep() throws Exception {
        catalog("movies", false);
        List<CatalogItem> all = titles(35);
        cacheRaw("movies", all);
        Field available = PukankiApi.class.getDeclaredField("TV_POSTER_AVAILABILITY");
        available.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Boolean> availability = (Map<String, Boolean>) available.get(null);
        availability.put(new fun.nyama.tv.Config(activity).pukankiBaseUrl() + "|" + all.get(34).id, false);
        search().setText("no results"); set("favoritesOnly", true); set("sortMode", "title");
        reset();
        assertEquals(1, io.jobs.size());
        io.runNext(); // Existing first-page validation, with no catalogue request.
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(30, grid.getAdapter().getCount());
        assertTrue(io.jobs.isEmpty()); // No catalog-wide poster sweep.
        assertEquals(35, get("total")); assertEquals(2, get("totalPages")); assertEquals(1, get("page"));
        assertEquals(View.GONE, ((View) get("loading")).getVisibility());
    }

    @Test public void delayedLocalSearchCannotReplaceResetResults() throws Exception {
        catalog("movies", false);
        List<CatalogItem> all = titles(5);
        cacheRaw("movies", all);
        @SuppressWarnings("unchecked") List<CatalogItem> raw = (List<CatalogItem>) get("tvRawCatalog");
        raw.addAll(all);
        search().setText("Movie 4");
        invoke("handleSearchChanged");
        assertEquals(1, io.jobs.size());
        cacheVisible("movies", all);
        reset();
        assertEquals(5, get("total"));
        io.runNext(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(5, get("total")); assertEquals(5, grid.getAdapter().getCount());
    }

    @Test public void clearingQueryDuringWarmupRestoresStableFirstPageImmediately() throws Exception {
        catalog("series", false);
        List<CatalogItem> all = titles(3);
        @SuppressWarnings("unchecked") List<CatalogItem> raw = (List<CatalogItem>) get("tvRawCatalog");
        raw.addAll(all);
        cacheRaw("series", all);
        @SuppressWarnings("unchecked") List<CatalogItem> initial = (List<CatalogItem>) get("tvInitialVisiblePage");
        initial.addAll(all);
        adapterItems(Collections.emptyList());
        invoke("handleSearchChanged");
        assertEquals(3, grid.getAdapter().getCount()); assertEquals(3, get("total"));
        assertEquals(1, io.jobs.size());
        io.runNext(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(3, grid.getAdapter().getCount());
    }

    @Test public void oldWarmupDoesNotOverwriteNewerCatalogOrCacheUnderChangedFilterState() throws Exception {
        catalog("movies", false);
        List<CatalogItem> original = titles(4);
        cacheRaw("movies", original);
        reset();
        assertEquals(1, io.jobs.size());
        // Change the current fields before the queued worker starts: its captured request stays default.
        set("sortMode", "rating"); set("favoritesOnly", true); genres().add("Drama"); set("page", 5);
        io.runNext(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        List<CatalogItem> cached = PukankiApi.getCachedVisibleTvCatalog(activity, "movies", Collections.emptyList(),
                "reviews", false, "");
        assertNull("A page fetch must not claim to have a complete validated catalog", cached);
        assertEquals(4, grid.getAdapter().getCount());
        assertNull(PukankiApi.getCachedVisibleTvCatalog(activity, "movies", Arrays.asList("Drama"),
                "rating", true, ""));
        // A later load advances the generation; already queued old search callbacks cannot repaint it.
        cacheVisible("movies", original.subList(0, 2));
        set("sortMode", "reviews"); set("favoritesOnly", false); genres().clear();
        set("tvDataKey", "obsolete-filter"); invoke("loadTvPage");
        assertEquals(2, get("total"));
    }

    @Test public void queuedWarmupCannotReplaceAChangedCatalogGeneration() throws Exception {
        catalog("movies", false);
        List<CatalogItem> original = titles(4);
        cacheRaw("movies", original);
        reset();
        assertEquals(1, io.jobs.size());
        set("sortMode", "rating"); set("favoritesOnly", true); genres().add("Drama");
        PukankiApi.cacheVisibleTvCatalog(activity, "movies", genres(), "rating", true, "",
                original.subList(0, 2));
        invoke("loadTvPage");
        assertEquals(2, get("total"));
        io.runNext(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, get("total")); assertEquals(2, grid.getAdapter().getCount());
        assertEquals("rating", get("sortMode")); assertEquals(true, get("favoritesOnly"));
    }

    @Test public void rapidTypingDebouncesAndOnlyLatestQueryIsLoaded() throws Exception {
        for (String section : new String[]{"movies", "series"}) {
            if (activity != null) { invoke("onDestroy"); activity = null; }
            catalog(section, false); cacheRaw(section, titles(8));
            search().setText("M");
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
            search().setText("Movie 4");
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(319));
            assertTrue(io.jobs.isEmpty());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1));
            assertEquals(1, io.jobs.size());
            io.runNext(); Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, grid.getAdapter().getCount());
            assertEquals("Movie 4", ((CatalogItem)grid.getAdapter().getItem(0)).title);
        }
    }

    @Test public void responseQueuedBeforeNextKeystrokeCannotReplaceVisibleQuery() throws Exception {
        catalog("movies", false); cacheRaw("movies", titles(8));
        search().setText("Movie 1"); invoke("handleSearchChanged");
        io.runNext(); // Response is queued on main, not applied yet.
        search().setText("Movie 4");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, grid.getAdapter().getCount());
        invoke("handleSearchChanged"); io.runNext(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Movie 4", ((CatalogItem)grid.getAdapter().getItem(0)).title);
    }

    @Test public void stalledRequestShowsFocusableRetryInsteadOfEndlessLoading() throws Exception {
        catalog("movies", false); cacheRaw("movies", titles(8)); invoke("loadTvPage");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(24));
        assertEquals(View.GONE, ((View)get("loading")).getVisibility());
        assertEquals(View.VISIBLE, ((View)get("catalogRetry")).getVisibility());
        assertTrue(((View)get("catalogRetry")).isFocusable());
        io.runNext(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.VISIBLE, ((View)get("catalogRetry")).getVisibility());
    }

    @Test public void movieSelectionAndRecycledHoverChooseExactArtworkAndPassItToDetail() throws Exception {
        catalog("movies", false);
        List<CatalogItem> all = assertFocusedAndHoveredArtwork();
        Method open = CatalogActivity.class.getDeclaredMethod("openDetail", CatalogItem.class);
        open.setAccessible(true); open.invoke(activity, all.get(1));
        Intent detail = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(PukankiApi.posterUrl(activity, all.get(1).id), detail.getStringExtra("tv_artwork_url"));
        assertTrue(detail.getBooleanExtra("tv_artwork_portrait", false));
        assertEquals(TitleDetailActivity.class.getName(), detail.getComponent().getClassName());
    }

    @Test public void seriesSelectionAndRecycledHoverReuseMoviesArtworkAndForeground() throws Exception {
        catalog("series", false);
        assertFocusedAndHoveredArtwork();
        // The new layer remains visual only; no detail/player activity or catalogue load is started.
        assertEquals(0, io.jobs.size());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void sameSectionReturnKeepsExactCatalogStateAndRefocusesOpenedPosterWithoutLoading() throws Exception {
        for (String section : new String[]{"movies", "series"}) {
            catalog(section, false);
            List<CatalogItem> all = titles(15);
            adapterItems(all); layout();
            search().setText("keep this query");
            ((Handler) get("main")).removeCallbacks((Runnable) get("liveSearch"));
            genres().add("Drama"); set("favoritesOnly", true); set("sortMode", "rating");
            set("page", 3); set("totalPages", 7);
            Object originalAdapter = grid.getAdapter();
            grid.setSelection(9); grid.requestFocus(); layout();
            Method open = CatalogActivity.class.getDeclaredMethod("openDetail", CatalogItem.class);
            open.setAccessible(true); open.invoke(activity, all.get(9));
            assertEquals(9, get("restorePosition"));
            Intent detail = Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(section, detail.getStringExtra("type"));
            invoke("onPause");
            assertEquals(9, activity.getSharedPreferences("nyama_plus_catalog_state", 0)
                    .getInt(section + ":grid_pos", -1));

            Intent returned = new Intent(activity, CatalogActivity.class)
                    .putExtra(CatalogActivity.EXTRA_SECTION, section)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            Method newIntent = CatalogActivity.class.getDeclaredMethod("onNewIntent", Intent.class);
            newIntent.setAccessible(true); newIntent.invoke(activity, returned);
            layout();
            assertSame(originalAdapter, grid.getAdapter());
            assertEquals(9, grid.getSelectedItemPosition()); assertTrue(grid.hasFocus());
            assertEquals("keep this query", search().getText().toString());
            assertEquals(Arrays.asList("Drama"), genres());
            assertEquals(true, get("favoritesOnly")); assertEquals("rating", get("sortMode"));
            assertEquals(3, get("page")); assertEquals(7, get("totalPages"));
            assertEquals(0, io.jobs.size());
            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        }
    }

    private List<CatalogItem> assertFocusedAndHoveredArtwork() throws Exception {
        assertTrue(root instanceof FrameLayout);
        assertEquals(2, ((FrameLayout) root).getChildCount());
        LinearLayout shell = (LinearLayout) ((FrameLayout) root).getChildAt(1);
        assertEquals(0x770A0C0F, ((ColorDrawable) shell.getChildAt(1).getBackground()).getColor());
        List<CatalogItem> all = titles(2); adapterItems(all); layout();
        TvCinematicBackground background = (TvCinematicBackground) get("cinematicBackground");
        grid.getOnItemSelectedListener().onItemSelected(grid, null, 1, 1);
        assertEquals(PukankiApi.posterUrl(activity, all.get(1).id), background.currentRequestedUrl());
        View reused = grid.getAdapter().getView(1, grid.getAdapter().getView(0, null, grid), grid);
        MotionEvent hover = MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_ENTER, 10, 10, 0);
        hover.setSource(InputDevice.SOURCE_MOUSE); reused.dispatchGenericMotionEvent(hover); hover.recycle();
        assertEquals(PukankiApi.posterUrl(activity, all.get(1).id), background.currentRequestedUrl());
        return all;
    }

    @Test public void phoneHasOriginalShellAndSearchBehaviorWithoutTvArtworkOrReset() throws Exception {
        catalog("movies", true);
        assertOriginalPhoneBehavior();
    }

    @Test public void phoneSeriesHasOriginalShellAndSearchBehaviorWithoutTvArtworkOrReset() throws Exception {
        catalog("series", true);
        assertOriginalPhoneBehavior();
    }

    private void assertOriginalPhoneBehavior() throws Exception {
        assertTrue(root instanceof LinearLayout); assertNull(get("cinematicBackground"));
        assertNull(get("searchClearButton"));
        search().setText("retain mobile query"); genres().add("Drama"); set("favoritesOnly", true); set("sortMode", "rating");
        invoke("resetTvCatalog");
        assertEquals("retain mobile query", search().getText().toString());
        assertEquals(Arrays.asList("Drama"), genres()); assertEquals(true, get("favoritesOnly"));
        assertEquals("rating", get("sortMode"));
        CatalogItem item = titles(1).get(0);
        Method open = CatalogActivity.class.getDeclaredMethod("openDetail", CatalogItem.class);
        open.setAccessible(true); open.invoke(activity, item);
        assertFalse(Shadows.shadowOf(activity).getNextStartedActivity().hasExtra("tv_artwork_url"));
    }

    private static final class QueueExecutor extends AbstractExecutorService {
        final List<Runnable> jobs = new ArrayList<>();
        private boolean shutdown;
        @Override public void execute(Runnable command) { jobs.add(command); }
        void runNext() { jobs.remove(0).run(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; List<Runnable> pending = new ArrayList<>(jobs); jobs.clear(); return pending; }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    }
}
