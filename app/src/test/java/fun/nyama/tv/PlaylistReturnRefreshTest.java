package fun.nyama.tv;
import android.app.Application;
import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class PlaylistReturnRefreshTest {
    Context context; Executor oldIo; PlaylistReturnRefresh.Refresher oldRefresh; PlaylistRepository.Downloader oldDownload;
    private static String m3u(String name){return "#EXTM3U\n#EXTINF:-1,"+name+"\nhttps://example.invalid/live.m3u8\n";}
    @Before public void before(){
        context=RuntimeEnvironment.getApplication();ActivationAccess.deny();
        context.getSharedPreferences("nyama_activation_mode",0).edit().clear().commit();
        context.getSharedPreferences("nyama_tv_private",0).edit().clear().commit();
        fun.nyama.tv.Config config=new fun.nyama.tv.Config(context);config.setDeviceMode(fun.nyama.tv.Config.MODE_TV);
        config.saveUrls("https://example.invalid/playlist.m3u","https://example.invalid/epg.xml");
        oldIo=PlaylistReturnRefresh.io;oldRefresh=PlaylistReturnRefresh.refresher;oldDownload=PlaylistRepository.downloader;
    }
    @After public void after(){PlaylistReturnRefresh.io=oldIo;PlaylistReturnRefresh.refresher=oldRefresh;PlaylistRepository.downloader=oldDownload;}
    @Test public void returnsCoalesceInFlightAndNeverQueueAResumeLoop(){
        ArrayDeque<Runnable> jobs=new ArrayDeque<>();AtomicInteger calls=new AtomicInteger();
        PlaylistReturnRefresh.io=jobs::add;PlaylistReturnRefresh.refresher=c->{ calls.incrementAndGet(); return java.util.Collections.emptyList(); };
        PlaylistReturnRefresh.request(context);PlaylistReturnRefresh.request(context);
        assertEquals(1,jobs.size());assertEquals(0,calls.get());jobs.remove().run();assertEquals(1,calls.get());
        assertTrue(jobs.isEmpty());
        PlaylistReturnRefresh.request(context);jobs.remove().run();assertEquals(2,calls.get());
    }
    @Test public void networkAndInvalidPlaylistKeepLastKnownGoodCache() throws Exception {
        Files.write(PlaylistRepository.cacheFile(context).toPath(),m3u("Original").getBytes(StandardCharsets.UTF_8));
        PlaylistRepository.downloader=(c,u,f)->{throw new java.io.IOException("synthetic");};
        try{PlaylistRepository.refresh(context);fail();}catch(java.io.IOException expected){}
        assertEquals(m3u("Original"),new String(Files.readAllBytes(PlaylistRepository.cacheFile(context).toPath()), StandardCharsets.UTF_8));
        PlaylistRepository.downloader=(c,u,f)->Files.write(f.toPath(),"invalid".getBytes(StandardCharsets.UTF_8));
        try{PlaylistRepository.refresh(context);fail();}catch(IllegalStateException expected){}
        assertEquals(m3u("Original"),new String(Files.readAllBytes(PlaylistRepository.cacheFile(context).toPath()), StandardCharsets.UTF_8));
        assertEquals(1,PlaylistRepository.loadCached(context).size());
    }
    @Test public void successfulRefreshPublishesNewCachedChannelsWithoutEpgDependency() throws Exception {
        PlaylistRepository.downloader=(c,u,f)->Files.write(f.toPath(),m3u("Fresh").getBytes(StandardCharsets.UTF_8));
        assertEquals(1,PlaylistRepository.refresh(context).size());
        assertEquals(m3u("Fresh"),new String(Files.readAllBytes(PlaylistRepository.cacheFile(context).toPath()), StandardCharsets.UTF_8));
        assertTrue(new fun.nyama.tv.Config(context).playlistLastRefresh()>0);
    }
    @Test public void phoneModeDoesNotStartTvReturnRefresh(){
        new fun.nyama.tv.Config(context).setDeviceMode(fun.nyama.tv.Config.MODE_PHONE);
        ArrayDeque<Runnable> jobs=new ArrayDeque<>();PlaylistReturnRefresh.io=jobs::add;
        PlaylistReturnRefresh.request(context);assertTrue(jobs.isEmpty());
    }
}
