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
        try {
            java.lang.reflect.Field f=PukankiApi.class.getDeclaredField("TV_POSTER_AVAILABILITY");f.setAccessible(true);
            ((Map<String,Boolean>)f.get(null)).put(new fun.nyama.tv.Config(context).pukankiBaseUrl()+"|tt"+(1000000+n),true);
        } catch(Exception e) { throw new AssertionError(e); }
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
    private List<CatalogItem> seriesCandidates() {
        List<CatalogItem> out=new ArrayList<>(); for(CatalogItem i:candidates()) out.add(new CatalogItem(i.id,"tvSeries",i.title,i.originalTitle,i.year,i.endYear,i.runtime,i.genres,i.rating,i.votes,i.seasons,i.episodeCount));return out;
    }
    private List<String> ids(List<CatalogRows.Row> rows) {
        List<String> ids = new ArrayList<>();
        for (CatalogRows.Row row : rows) for (CatalogItem item : row.items) ids.add(row.key + ":" + item.id);
        return ids;
    }
    @Test public void moviesAndSeriesUseRelevantDailyGenresAndAvoidRepeatsWhenAvailable() {
        for (String section : new String[]{"movies", "series"}) {
            List<CatalogRows.Row> rows = CatalogRows.observeAndRead(context, section, section.equals("series") ? seriesCandidates() : candidates(), now);
            assertEquals(section.equals("series") ? 7 : 9, rows.size()); assertEquals("featured", rows.get(0).key);
            Set<String> seen = new HashSet<>();
            for (CatalogRows.Row row : rows) {
                assertTrue(row.items.size() >= 2 && row.items.size() <= 6);
                for (CatalogItem item : row.items) {
                    assertTrue(CatalogRows.matches(item, row.key));
                    assertTrue("Repeated despite plentiful alternatives", seen.add(item.id));
                }
            }
            Set<String> keys = new HashSet<>(); for (CatalogRows.Row row : rows) keys.add(row.key);
            assertTrue(keys.contains("crime"));
            assertTrue(keys.contains("scifi"));
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
        List<CatalogRows.Row> unrated=CatalogRows.observeAndRead(context,"series",Arrays.asList(title(1,"","N/A"),title(2,"","")),now);
        assertTrue(unrated.isEmpty());
    }
    @Test public void verifiedFeaturedStaysStableAcrossPoolTurnover() {
        List<CatalogItem> pool=new ArrayList<>();
        for(int i=0;i<100;i++)pool.add(title(i,i%2==0?"Comedy":"Drama",i%2==0?"6.5":"8.0"));
        List<String> first=ids(CatalogRows.observeAndRead(context,"movies",pool,now));assertFalse(first.isEmpty());
        List<CatalogItem> replacements=new ArrayList<>();
        for(int i=200;i<600;i++)replacements.add(title(i,"Action, Horror, Family, Thriller","9.9"));
        List<CatalogRows.Row> after=CatalogRows.observeAndRead(context,"movies",replacements,now+1000);
        List<String> retained=new ArrayList<>();for(String id:ids(after))if(id.startsWith("featured:")||id.startsWith("comedy:")||id.startsWith("drama:"))retained.add(id);
        assertEquals(first,retained);
    }
    @Test public void aliasesAndSparsePoolsDoNotProduceIdenticalRowsOrFabricateCandidates() {
        assertTrue(CatalogRows.matches(title(1, "Екшън, Комедия", "8"), "action"));
        assertTrue(CatalogRows.matches(title(1, "Фантастика", "8"), "scifi"));
        assertTrue(CatalogRows.matches(title(1, "Sci-Fi", "8"), "scifi"));
        assertTrue(CatalogRows.matches(title(1, "Криминален", "8"), "crime"));
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
                Arrays.asList(title(20, "Comedy", "6.5"), title(21, "Comedy", "6.5")), now + 1000);
        assertEquals(2, later.size()); assertEquals("comedy", later.get(1).key);
        assertEquals(featured, ids(Collections.singletonList(later.get(0))));
        new fun.nyama.tv.Config(context).setPukankiBaseUrl("https://catalog.example.invalid");
        assertTrue(CatalogRows.observeAndRead(context, "movies", Collections.emptyList(), now).isEmpty());
    }
    @Test public void categoryMappingUsesOnlyActualMatchingCatalogGenreLabels() {
        assertEquals(java.util.Arrays.asList("Action","Екшън"),CatalogRows.catalogGenres("action",java.util.Arrays.asList("Drama","Action","Comedy","Екшън")));
        assertEquals(java.util.Arrays.asList("Sci-Fi","Фантастика"),CatalogRows.catalogGenres("scifi",java.util.Arrays.asList("Family","Sci-Fi","Фантастика")));
        assertTrue(CatalogRows.catalogGenres("crime",java.util.Arrays.asList("Drama","Action")).isEmpty());
        assertTrue(CatalogRows.catalogGenres("featured",java.util.Arrays.asList("Action")).isEmpty());
    }

    @Test public void animationAliasesAreDistinctFromFamilyAndAllExpandedGenresUseRealLabels() {
        for(String name:Arrays.asList("Animation","Animated","Анимация")) {
            assertTrue(CatalogRows.matches(title(1,name,"6.5"),"animation"));
            assertEquals(Arrays.asList(name),CatalogRows.catalogGenres("animation",Arrays.asList("Family",name,"Drama")));
        }
        assertFalse(CatalogRows.matches(title(1,"Family","9.0"),"animation"));
        for(String[] pair:new String[][]{{"adventure","Adventure"},{"fantasy","Fantasy"},{"mystery","Mystery"},{"romance","Romance"},{"documentary","Documentary"},{"history","History"},{"war","War"},{"music","Music"},{"biography","Biography"}})
            assertEquals(Arrays.asList(pair[1]),CatalogRows.catalogGenres(pair[0],Arrays.asList("Comedy",pair[1])));
    }
    @Test public void ratingBoundaryTypePosterProofAndEmptyPoolsAreEnforced() throws Exception {
        List<CatalogItem> list=Arrays.asList(title(1,"Animation","6.49"),title(2,"Animation","6.5"),title(3,"Animation","10"),title(4,"Animation","NaN"),title(5,"Animation","8"));
        java.lang.reflect.Field f=PukankiApi.class.getDeclaredField("TV_POSTER_AVAILABILITY");f.setAccessible(true);
        ((Map<?,?>)f.get(null)).remove(new fun.nyama.tv.Config(context).pukankiBaseUrl()+"|tt1000005");
        List<CatalogRows.Row> rows=CatalogRows.observeAndRead(context,"movies",list,now);
        Set<String> admitted=new HashSet<>();for(CatalogRows.Row r:rows)for(CatalogItem i:r.items)admitted.add(i.id);
        assertEquals(new HashSet<>(Arrays.asList("tt1000002","tt1000003")),admitted);
        assertTrue(CatalogRows.observeAndRead(context,"series",list,now).isEmpty());
    }
    @Test public void oneEligibleTitleStaysStableAndUniformSamplingIsNotRatingOrder() {
        List<CatalogRows.Row> first=CatalogRows.observeAndRead(context,"movies",Arrays.asList(title(1,"Drama","6.5")),now);
        assertEquals(1,first.get(0).items.size());
        assertEquals(first.get(0).items.get(0).id,CatalogRows.observeAndRead(context,"movies",Arrays.asList(title(2,"Drama","10")),now+1).get(0).items.get(0).id);
        List<Integer> pool=new ArrayList<>();for(int i=0;i<100;i++)pool.add(i);
        List<Integer> sample=DailyTitles.sample(pool,Collections.emptySet(),6,new Random(42));
        assertEquals(6,new HashSet<>(sample).size());assertNotEquals(pool.subList(0,6),sample);
        assertTrue(Collections.disjoint(sample,DailyTitles.sample(pool,new HashSet<>(sample),6,new Random(43))));
    }

}
