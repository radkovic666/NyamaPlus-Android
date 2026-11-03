package fun.nyama.tv;

import android.app.Application;
import android.net.Uri;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import javax.net.ssl.*;
import java.net.*;
import java.io.*;
import java.security.cert.Certificate;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28, application=Application.class)
public class SubtitleIntegrationTest {
    final String base="https://nyama.fun/api/subtitles";
    final SubtitleMedia movie=new SubtitleMedia("tt0111161",-1,-1), episode=new SubtitleMedia("tt0944947",2,3);
    String route(boolean tv,String language) { return tv ? TvPlaybackSource.episode("tt0944947","tt0944947",2,3,language,1) : TvPlaybackSource.movie("tt0111161",language,1); }
    String file(long expiry) { return base+"/file/"+"a".repeat(64)+"?expires="+expiry+"&sig="+"b".repeat(64); }
    JSONObject selection(long expiry) throws Exception { return new JSONObject().put("ok",true).put("media",movie.json()).put("file_id",123).put("offset_seconds",.5).put("release","BG Test").put("expires",expiry).put("url",file(expiry)); }
    JSONObject response(SubtitleMedia m) throws Exception { return new JSONObject().put("ok",true).put("media",m.json()).put("results",new JSONArray().put(new JSONObject().put("file_id",123).put("language","bg").put("release","Actual Release").put("fps",23.976).put("downloads",100))); }
    @Test public void allVidapiRoutesForceOneBulgarianRegardlessOfUiLanguage() {
        for(String lang:new String[]{"en","bg"}) for(String url:new String[]{route(false,lang),route(true,lang),TvPlaybackSource.series("tt0944947",lang,1,fun.nyama.tv.Config.PLAYER_2)}) {
            assertEquals(Collections.singletonList("bg"),Uri.parse(url).getQueryParameters("ds_lang"));
        }
    }
    @Test public void customAndFallbackPreserveEveryExactIdentityAndOtherParametersWithoutDoubleEncoding() {
        for(boolean tv:new boolean[]{false,true}) {
            String url=route(tv,"en")+"&ds_lang=en&resumeAt=42&unknown=a%26b";
            String custom=TvPlaybackSource.subtitles(url,file(1800000000),"BG & release",123.9,true);
            Uri u=Uri.parse(custom);
            assertEquals(Uri.parse(url).getPath(),u.getPath()); assertEquals(file(1800000000),u.getQueryParameter("sub_url"));
            assertEquals("BG & release",u.getQueryParameter("sub_label")); assertEquals("bg",u.getQueryParameter("sub_lang"));
            assertEquals("true",u.getQueryParameter("sub_default")); assertEquals("a&b",u.getQueryParameter("unknown"));
            assertEquals("123",u.getQueryParameter("resumeAt")); assertEquals("0",u.getQueryParameter("autoplay"));
            assertEquals(Collections.singletonList("bg"),u.getQueryParameters("ds_lang"));
            Uri fallback=Uri.parse(TvPlaybackSource.subtitles(custom+"&sub_file=old",null,"",null,null));
            assertEquals(u.getPath(),fallback.getPath()); assertEquals("123",fallback.getQueryParameter("resumeAt"));
            for(String name:new String[]{"sub_url","sub_file","sub_label","sub_lang","sub_default"}) assertNull(fallback.getQueryParameter(name));
            assertEquals(Collections.singletonList("bg"),fallback.getQueryParameters("ds_lang"));
        }
    }
    @Test public void player1HasNoNewParameters() {
        String url=TvPlaybackSource.movie("tt0111161","en",1,fun.nyama.tv.Config.PLAYER_1);
        assertEquals("https://vidrock.to/embed/movie/tt0111161",url);
        assertThrows(IllegalArgumentException.class,()->TvPlaybackSource.subtitles(url,null,"",null,null));
    }
    @Test public void genericSeriesCannotChooseAnotherEpisodesFile() {
        String url=TvPlaybackSource.series("tt0944947","en",1,fun.nyama.tv.Config.PLAYER_2);
        assertThrows(IllegalArgumentException.class,()->SubtitleMedia.fromUrl(url));
        assertThrows(IllegalArgumentException.class,()->TvPlaybackSource.subtitles(url,file(1800000000),"BG",null,null));
        assertEquals("bg",Uri.parse(TvPlaybackSource.subtitles(url,null,"",null,null)).getQueryParameter("ds_lang"));
    }
    @Test public void wrongEpisodeWrongLanguageAndFractionalIdentityAreRejected() throws Exception {
        assertThrows(SubtitleApi.Failure.class,()->SubtitleApi.results(response(movie),episode));
        JSONObject r=response(episode);r.getJSONObject("media").put("episode",4);
        assertThrows(SubtitleApi.Failure.class,()->SubtitleApi.results(r,episode));
        r.getJSONObject("media").put("episode",3.9);assertFalse(episode.matches(r.getJSONObject("media")));
        JSONObject wrong=response(movie);wrong.getJSONArray("results").getJSONObject(0).put("language","en");
        assertThrows(SubtitleApi.Failure.class,()->SubtitleApi.results(wrong,movie));
    }
    @Test public void realReleaseMetadataIsRetainedAndFilesAreDeduped() throws Exception {
        JSONObject r=response(movie);r.getJSONArray("results").put(r.getJSONArray("results").get(0));
        List<SubtitleApi.Release> found=SubtitleApi.results(r,movie);assertEquals(1,found.size());
        assertEquals(123,found.get(0).fileId);assertEquals("Actual Release",found.get(0).label);
        assertTrue(found.get(0).display().contains("23.976"));assertEquals("",found.get(0).format);
    }
    @Test public void baseOriginCannotExfiltrateActivationCredentials() {
        for(String s:new String[]{"http://nyama.fun/api/subtitles","https://evil.invalid/api/subtitles","https://nyama.fun@evil.invalid/api/subtitles","https://nyama.fun/api/subtitles?x=1","https://nyama.fun:443/api/subtitles","https://nyama.fun/../api/subtitles"}) assertFalse(s,SubtitleApi.validBase(s));
        assertTrue(SubtitleApi.validBase(base));assertFalse(SubtitleApi.validBase(""));
    }
    @Test public void signedUrlExpiryOriginPathAndSignatureAreValidated() throws Exception {
        long now=System.currentTimeMillis()/1000, expiry=now+900;
        assertEquals(file(expiry),new SubtitleApi.Selection(selection(expiry),base,123,.5,now).url);
        for(String invalid:new String[]{"https://evil.invalid/a.vtt",file(expiry).replace("/file/","/other/"),file(expiry).replace("sig=","sig=wrong"),file(expiry)+"&expires="+expiry}) {
            JSONObject j=selection(expiry).put("url",invalid);
            assertThrows(SubtitleApi.Failure.class,()->new SubtitleApi.Selection(j,base,123,.5,now));
        }
        assertThrows(SubtitleApi.Failure.class,()->new SubtitleApi.Selection(selection(now-1),base,123,.5,now));
        assertThrows(SubtitleApi.Failure.class,()->new SubtitleApi.Selection(selection(now+7200),base,123,.5,now));
    }
    static final class Fake extends HttpsURLConnection {
        int status;String payload;ByteArrayOutputStream sent=new ByteArrayOutputStream();boolean disconnected;
        Fake(URL u,int status,String payload){super(u);this.status=status;this.payload=payload;}
        public void connect(){}public void disconnect(){disconnected=true;}public boolean usingProxy(){return false;}
        public String getCipherSuite(){return "test";}public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return null;}
        public int getResponseCode(){return status;}public InputStream getInputStream(){return new ByteArrayInputStream(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        public OutputStream getOutputStream(){return sent;}
    }
    @Test public void authenticatedSearchUsesExistingCredentialsAndNoCustomerPassword() throws Exception {
        Fake[] opened={null};
        SubtitleApi api=new SubtitleApi(base,()->new String[]{"a".repeat(64),"b".repeat(64)},url->opened[0]=new Fake(url,200,response(episode).toString()));
        assertEquals(1,api.search(episode).size());Fake c=opened[0];Uri q=Uri.parse(c.getURL().toString());
        assertEquals("2",q.getQueryParameter("season"));assertEquals("3",q.getQueryParameter("episode"));
        assertEquals("Bearer "+"b".repeat(64),c.getRequestProperty("Authorization"));assertEquals("a".repeat(64),c.getRequestProperty("X-Nyama-Activation-ID"));
        assertFalse(c.getInstanceFollowRedirects());assertTrue(c.disconnected);assertEquals(0,c.sent.size());
    }
    @Test public void unavailableUnconfiguredRevokedMalformedAndRateLimitFailWithoutRetries() throws Exception {
        for(int status:new int[]{401,403,429,500,503,302,200}) {
            int[] calls={0};SubtitleApi api=new SubtitleApi(base,()->new String[]{"a","b"},url->{calls[0]++;return new Fake(url,status,"not JSON");});
            assertThrows(SubtitleApi.Failure.class,()->api.search(movie));assertEquals(1,calls[0]);
        }
        SubtitleApi off=new SubtitleApi("",()->{fail("credentials must not be read when disabled");return null;},url->{fail("no request");return null;});
        assertThrows(SubtitleApi.Failure.class,()->off.search(movie));
        SubtitleApi timeout=new SubtitleApi(base,()->new String[]{"a","b"},url->{throw new java.net.SocketTimeoutException();});
        assertThrows(java.net.SocketTimeoutException.class,()->timeout.search(movie));
    }
    @Test public void cancellationNeverStartsAnotherRequest() {
        SubtitleApi api=new SubtitleApi(base,()->{fail("closed");return null;},url->{fail("closed");return null;});api.close();
        assertThrows(InterruptedIOException.class,()->api.search(movie));
    }
}
