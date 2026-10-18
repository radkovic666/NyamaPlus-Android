package fun.nyama.tv;
import android.app.Application;
import android.content.Context;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class TvDailyAndMetadataTest {
    private Context context;
    @Before public void setup() { context=RuntimeEnvironment.getApplication(); context.getSharedPreferences("nyama_daily_titles",0).edit().clear().commit(); }
    private CatalogItem title(int i,String rating) { return new CatalogItem("tt"+(1000000+i),"movie","Title "+i,"","2020","","60","Drama",rating,"","",""); }
    private List<String> ids(List<CatalogItem> items) { List<String> ids=new ArrayList<>(); for(CatalogItem i:items)ids.add(i.id); return ids; }
    @Test public void onlyReliableSevenPlusRatingsQualify() {
        for(String rating:new String[]{"", "N/A", "6.9", "NaN", "11", "7/10"}) assertFalse(DailyTitles.eligible(title(1,rating)));
        assertTrue(DailyTitles.eligible(title(1,"7.0"))); assertTrue(DailyTitles.eligible(title(1,"10")));
    }
    @Test public void selectedIdsAreStableWithinDaySeparateBySectionAndRotate() {
        long now=1704110400000L; List<CatalogItem> pool=new ArrayList<>();
        for(int i=0;i<20;i++)pool.add(title(i,"8.1"));
        DailyTitles.observe(context,"movies",pool,now);
        List<String> first=ids(DailyTitles.read(context,"movies",now)); assertEquals(3,first.size());
        DailyTitles.observe(context,"movies",Arrays.asList(title(99,"9.9")),now+1000);
        assertEquals(first,ids(DailyTitles.read(context,"movies",now+2000)));
        assertTrue(DailyTitles.read(context,"series",now).isEmpty());
        DailyTitles.observe(context,"movies",pool,now+86400000L);
        assertNotEquals(first,ids(DailyTitles.read(context,"movies",now+86400000L)));
    }
    @Test public void candidatePoolIsBoundedAndOldEvidenceExpires() throws Exception {
        List<CatalogItem> pool=new ArrayList<>(); for(int i=0;i<300;i++)pool.add(title(i,"8"));
        DailyTitles.observe(context,"movies",pool,1000);
        String key=new fun.nyama.tv.Config(context).pukankiBaseUrl()+"|movies:pool";
        assertEquals(240,new JSONObject(context.getSharedPreferences("nyama_daily_titles",0).getString(key,"{}")).length());
        assertTrue(DailyTitles.read(context,"movies",1000+86400001L).isEmpty());
    }
    @Test public void tmdbStyleMetadataIsReusedWithoutInventingMissingValues() throws Exception {
        JSONObject normalized=TvTitleInfo.normalize(new JSONObject().put("overview","Actual overview").put("first_air_date","2019-05-20")
                .put("number_of_seasons",3).put("number_of_episodes",24).put("vote_average",8.2)
                .put("episode_run_time",new JSONArray().put(66)).put("genres",new JSONArray().put(new JSONObject().put("name","Drama"))));
        assertEquals("Actual overview",normalized.getString("Plot")); assertEquals("Drama",normalized.getString("Genre"));
        assertEquals("66 min",normalized.getString("Runtime")); assertEquals(3,normalized.getInt("totalSeasons"));
        assertEquals("2019-05-20",normalized.getString("Released")); assertEquals("2019",normalized.getString("Year"));
        assertFalse(TvTitleInfo.normalize(new JSONObject().put("number_of_seasons",0)).has("totalSeasons"));
        assertEquals("",TvTitleInfo.fallback("",new JSONObject(),"Genre"));
        assertEquals("Original",TvTitleInfo.fallback("Original",normalized,"Plot"));
    }
}
