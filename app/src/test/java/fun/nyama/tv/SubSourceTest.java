package fun.nyama.tv;
import android.app.Application;
import android.net.Uri;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import java.util.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=Application.class)
public class SubSourceTest {
    final SubtitleMedia media=new SubtitleMedia("tt0944947",2,3);
    final String base=SubtitleApi.SUBSOURCE_BASE;
    JSONObject list(int page,int next)throws Exception{return new JSONObject().put("ok",true).put("provider","subsource").put("media",media.json()).put("page",page).put("next_page",next)
            .put("results",new JSONArray().put(new JSONObject().put("subtitle_id",321).put("release","Real.Show.S02E03").put("language","bg").put("uploader","#97").put("fps",25)));}
    @Test public void explicitPagedTvSearchUsesSameHostHeadersAndActualSubtitleIds()throws Exception{
        List<SubtitleIntegrationTest.Fake> calls=new ArrayList<>();
        SubtitleApi api=new SubtitleApi(base,()->new String[]{"a","b"},u->{SubtitleIntegrationTest.Fake c=new SubtitleIntegrationTest.Fake(u,200,list(2,3).toString());calls.add(c);return c;},true);
        SubtitleApi.Page result=api.searchPage(media,2);assertEquals(3,result.next);assertEquals(321,result.releases.get(0).fileId);
        assertEquals("Real.Show.S02E03",result.releases.get(0).label);assertEquals("#97",result.releases.get(0).uploader);
        assertEquals("subsource",calls.get(0).getRequestProperty("X-Nyama-Subtitle-Provider"));
        assertNull(calls.get(0).getRequestProperty("X-API-Key"));assertEquals("2",Uri.parse(calls.get(0).getURL().toString()).getQueryParameter("page"));
        assertEquals("3",Uri.parse(calls.get(0).getURL().toString()).getQueryParameter("episode"));assertEquals(1,calls.size());
    }
    @Test public void signedVttSelectionUsesSubtitleIdAndPreservesExactRouteAndBg()throws Exception{
        long expires=System.currentTimeMillis()/1000+900;String signed=base+"/file/"+"a".repeat(64)+".vtt?expires="+expires+"&sig="+"b".repeat(64);
        JSONObject selected=new JSONObject().put("ok",true).put("provider","subsource").put("media",media.json()).put("subtitle_id",321).put("offset_seconds",.5).put("release","Show.S02E03").put("expires",expires).put("url",signed);
        SubtitleIntegrationTest.Fake[] call={null};SubtitleApi api=new SubtitleApi(base,()->new String[]{"a","b"},u->call[0]=new SubtitleIntegrationTest.Fake(u,200,selected.toString()),true);
        SubtitleApi.Selection result=api.select(media,321,.5);assertEquals(signed,result.url);
        JSONObject body=new JSONObject(call[0].sent.toString("UTF-8"));assertEquals(321,body.getLong("subtitle_id"));assertFalse(body.has("file_id"));
        Uri uri=Uri.parse(TvPlaybackSource.subtitles(TvPlaybackSource.episode(media.imdb,media.imdb,2,3,"en",1),result.url,result.label,120d,true));
        assertEquals("/embed/tv/tt0944947/2/3",uri.getPath());assertEquals(Collections.singletonList("bg"),uri.getQueryParameters("ds_lang"));
        assertEquals(signed,uri.getQueryParameter("sub_url"));assertEquals("0",uri.getQueryParameter("autoplay"));
        assertThrows(SubtitleApi.Failure.class,()->new SubtitleApi.Selection(selected.put("url",signed.replace(".vtt?","?")),base,321,.5,System.currentTimeMillis()/1000,true));
    }
    @Test public void mixedProviderWrongPageAndOtherEpisodeCannotBeSelected()throws Exception{
        for(JSONObject reply:new JSONObject[]{list(1,0).put("provider","opensubtitles"),list(2,0),list(1,7),list(1,0).put("media",new SubtitleMedia(media.imdb,2,4).json())}){
            SubtitleApi api=new SubtitleApi(base,()->new String[]{"a","b"},u->new SubtitleIntegrationTest.Fake(u,200,reply.toString()),true);
            assertThrows(SubtitleApi.Failure.class,()->api.searchPage(media,1));
        }
        assertEquals("",new SubtitleApi("https://nyama.fun/other/subtitles",()->null,u->null,true).base);
    }
    @Test public void duplicateResultsAreDedupedAndEmptyPageIsHonest()throws Exception{
        JSONObject j=list(1,0);j.getJSONArray("results").put(j.getJSONArray("results").get(0));assertEquals(1,SubtitleApi.results(j,media,true).size());
        assertTrue(SubtitleApi.results(j.put("results",new JSONArray()),media,true).isEmpty());
    }
}
