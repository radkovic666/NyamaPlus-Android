package fun.nyama.tv;

import android.app.Application;
import android.content.Context;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class CatalogRowsTest {
    private Context context;
    private final long now = 1704110400000L;
    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("nyama_catalog_rows", 0).edit().clear().commit();
        context.getSharedPreferences("nyama_tv_private", 0).edit().clear().commit();
    }
    private CatalogItem title(int n, String genre, String rating) {
        return new CatalogItem("tt" + (1000000 + n), "movie", "Title " + n, "Original " + n,
                "2024", "", "90", genre, rating, "200", "3", "24");
    }
    private List<CatalogItem> candidates() {
        List<CatalogItem> result = new ArrayList<>();
        int n = 0;
        for (String genre : new String[]{"Action", "Comedy", "Horror", "Family", "Thriller", "Drama", "Crime", "Science Fiction"})
            for (int i = 0; i < 12; i++) result.add(title(n++, genre, i < 8 ? "8.2" : "6.0"));
        return result;
    }
    private List<String> ids(List<CatalogRows.Row> rows) {
        List<String> ids = new ArrayList<>();
        for (CatalogRows.Row row : rows) for (CatalogItem item : row.items) ids.add(row.key + ":" + item.id);
        return ids;
    }
    @Test public void moviesAndSeriesUseRelevantGenresPreferSevenPlusAndAvoidRepeatsWhenAvailable() {
        for (String section : new String[]{"movies", "series"}) {
            List<CatalogRows.Row> rows = CatalogRows.observeAndRead(context, section, candidates(), now);
            assertEquals(7, rows.size()); assertEquals("featured", rows.get(0).key);
            Set<String> seen = new HashSet<>();
            for (CatalogRows.Row row : rows) {
                assertTrue(row.items.size() >= 2 && row.items.size() <= 6);
                for (CatalogItem item : row.items) {
                    assertTrue(CatalogRows.matches(item, row.key));
                    assertTrue("Repeated despite plentiful alternatives", seen.add(item.id));
                    if (row.key.equals("featured")) assertTrue(Double.parseDouble(item.rating) >= 7.0);
                }
            }
            Set<String> keys = new HashSet<>(); for (CatalogRows.Row row : rows) keys.add(row.key);
            assertEquals(section.equals("series"), keys.contains("crime"));
            assertEquals(section.equals("series"), keys.contains("scifi"));
        }
    }
    @Test public void stableDaySurvivesNewPagesAndReopeningAndRotatesNextDay() {
        List<CatalogItem> pool = candidates();
        List<String> first = ids(CatalogRows.observeAndRead(context, "movies", pool, now));
        assertEquals(first, ids(CatalogRows.observeAndRead(context, "movies", Collections.singletonList(title(999, "Drama", "9.0")), now + 1000)));
        assertEquals(first, ids(CatalogRows.observeAndRead(context, "movies", Collections.emptyList(), now + 2000)));
        assertNotEquals(first, ids(CatalogRows.observeAndRead(context, "movies", pool, now + 86400000L)));
    }
    @Test public void poolIsBoundedExpiresAndDoesNotInventMissingRatingsOrGenres() throws Exception {
        List<CatalogItem> pool = new ArrayList<>();
        for (int i = 0; i < 400; i++) pool.add(title(i, "Drama", "8.2"));
        CatalogRows.observeAndRead(context, "movies", pool, now);
        String key = new fun.nyama.tv.Config(context).pukankiBaseUrl() + "|movies:pool";
        assertEquals(240, new JSONObject(context.getSharedPreferences("nyama_catalog_rows", 0).getString(key, "{}")).length());
        assertTrue(CatalogRows.observeAndRead(context, "movies", Collections.emptyList(), now + CatalogRows.TTL_MS + 1).isEmpty());
        assertTrue(CatalogRows.observeAndRead(context, "series", Arrays.asList(title(1, "", "N/A"), title(2, "", "")), now).isEmpty());
    }
    @Test public void aliasesAndSparsePoolsDoNotProduceIdenticalRowsOrFabricateCandidates() {
        assertTrue(CatalogRows.matches(title(1, "Екшън, Комедия", ""), "action"));
        assertTrue(CatalogRows.matches(title(1, "Фантастика", ""), "scifi"));
        assertTrue(CatalogRows.matches(title(1, "Sci-Fi", ""), "scifi"));
        assertTrue(CatalogRows.matches(title(1, "Криминален", ""), "crime"));
        List<CatalogRows.Row> rows = CatalogRows.observeAndRead(context, "movies",
                Arrays.asList(title(1, "Drama, Comedy", "8.0"), title(2, "Drama, Comedy", "8.0")), now);
        assertEquals(1, rows.size()); assertEquals("featured", rows.get(0).key);
    }
    @Test public void newlyAvailableGenreCanBeAddedWithoutReshufflingExistingRowsAndBaseUrlIsIsolated() {
        List<CatalogItem> initial = new ArrayList<>();
        for (int i = 0; i < 2; i++) initial.add(title(i, "Drama", "8.2"));
        List<CatalogRows.Row> first = CatalogRows.observeAndRead(context, "movies", initial, now);
        assertEquals(1, first.size());
        List<String> featured = ids(Collections.singletonList(first.get(0)));
        List<CatalogRows.Row> later = CatalogRows.observeAndRead(context, "movies",
                Arrays.asList(title(20, "Comedy", "6.4"), title(21, "Comedy", "6.4")), now + 1000);
        assertEquals(2, later.size()); assertEquals("comedy", later.get(1).key);
        assertEquals(featured, ids(Collections.singletonList(later.get(0))));
        new fun.nyama.tv.Config(context).setPukankiBaseUrl("https://catalog.example.invalid");
        assertTrue(CatalogRows.observeAndRead(context, "movies", Collections.emptyList(), now).isEmpty());
    }
}
