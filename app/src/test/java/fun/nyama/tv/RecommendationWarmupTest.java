package fun.nyama.tv;
import android.app.Application;
import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=Application.class)
public class RecommendationWarmupTest {
    Context context;
    @Before public void setup()throws Exception {
        context=RuntimeEnvironment.getApplication();context.getSharedPreferences("nyama_recommendation_warmup",0).edit().clear().commit();
        java.lang.reflect.Field f=PukankiApi.class.getDeclaredField("RAW_CATALOG_CACHE");f.setAccessible(true);((Map<?,?>)f.get(null)).clear();
    }
    List<CatalogItem> page(int page){List<CatalogItem> list=new ArrayList<>();for(int i=0;i<30;i++)list.add(new CatalogItem("tt"+(1000000+page*30+i),"movie","Title","","2024","","90","Animation",i%2==0?"6.5":"6.4","100","",""));return list;}
    @Test public void boundedDiversePagesAndPosterChecksRunOncePerLocalDay()throws Exception {
        List<Integer> pages=Collections.synchronizedList(new ArrayList<>());AtomicInteger probes=new AtomicInteger();
        PukankiApi.RecommendationPage fetch=p->{pages.add(p);return page(p);};
        PukankiApi.RecommendationPosters verify=list->{probes.addAndGet(list.size());return list;};
        long now=1704110400000L;
        List<CatalogItem> result=PukankiApi.recommendationCandidates(context,"movies",10000,now,fetch,verify);
        assertEquals(3,pages.size());assertEquals(3,new HashSet<>(pages).size());for(int p:pages)assertTrue(p>1&&p<=10000);
        assertEquals(24,probes.get());assertEquals(24,result.size());for(CatalogItem i:result)assertEquals("6.5",i.rating);
        assertTrue(PukankiApi.recommendationCandidates(context,"movies",10000,now+1000,fetch,verify).isEmpty());
        assertEquals(3,pages.size());assertEquals(24,probes.get());
        PukankiApi.recommendationCandidates(context,"movies",10000,now+86400000L,fetch,verify);assertEquals(6,pages.size());assertEquals(48,probes.get());
    }
    @Test public void onePageAndOfflineFailureDoNotProduceExtraStartupRequestsOrRetryStorms()throws Exception {
        AtomicInteger requests=new AtomicInteger();PukankiApi.RecommendationPage failed=p->{requests.incrementAndGet();throw new java.io.IOException("fixture offline");};
        long now=1704110400000L;
        assertTrue(PukankiApi.recommendationCandidates(context,"movies",1,now,failed,list->list).isEmpty());assertEquals(0,requests.get());
        assertTrue(PukankiApi.recommendationCandidates(context,"series",50,now,failed,list->list).isEmpty());assertEquals(3,requests.get());
        PukankiApi.recommendationCandidates(context,"series",50,now+1000,failed,list->list);assertEquals(3,requests.get());
    }
}
