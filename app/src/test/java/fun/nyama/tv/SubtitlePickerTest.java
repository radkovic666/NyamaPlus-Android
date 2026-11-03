package fun.nyama.tv;

import android.app.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=Application.class) @LooperMode(LooperMode.Mode.PAUSED)
public class SubtitlePickerTest {
    ActivityController<Activity> lifecycle;Activity activity;SubtitlePicker picker;
    String url=TvPlaybackSource.movie("tt0111161","en",1);int applied;
    @Before public void setup(){lifecycle=Robolectric.buildActivity(Activity.class).setup();activity=lifecycle.get();new fun.nyama.tv.Config(activity).setLanguage("en");}
    @After public void cleanup(){if(picker!=null)picker.close();lifecycle.pause().stop().destroy();}
    SubtitlePicker.Host host(){return new SubtitlePicker.Host(){public String url(){return url;}
        public void apply(SubtitleApi.Selection selection,boolean timing,Runnable done){applied++;done.run();}public void focusPlayer(){}};}
    List<Button> buttons(View view){List<Button> found=new ArrayList<>();if(view instanceof Button)found.add((Button)view);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)found.addAll(buttons(group.getChildAt(i)));}return found;}
    Button button(String part){return buttons(ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView()).stream().filter(b->b.getText().toString().contains(part)).findFirst().orElseThrow();}
    void settle()throws Exception { java.lang.reflect.Field f=SubtitlePicker.class.getDeclaredField("worker");f.setAccessible(true);((ExecutorService)f.get(picker)).submit(()->{}).get(3,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    @Test public void disabledBackendIsNotContactedAtStartupAndAutomaticOptionAlwaysWorks()throws Exception{
        int[] reads={0};picker=new SubtitlePicker(activity,host(),()->new SubtitleApi("",()->{reads[0]++;return null;},u->{fail();return null;}));
        picker.show();assertEquals(0,reads[0]);button("Search OpenSubtitles").performClick();settle();assertEquals(0,reads[0]);
        assertTrue(picker.showing());Button auto=button("Automatic Bulgarian");assertTrue(auto.isEnabled());auto.performClick();assertEquals(1,applied);assertFalse(picker.showing());
    }
    @Test public void deliberateSearchShowsActualResultAndOneSelectionCallsHostOnce()throws Exception{
        String base="https://nyama.fun/api/subtitles";SubtitleMedia media=SubtitleMedia.fromUrl(url);int[] calls={0};
        picker=new SubtitlePicker(activity,host(),()->new SubtitleApi(base,()->new String[]{"a","b"},u->{calls[0]++;
            JSONObject response=new JSONObject().put("ok",true).put("media",media.json());
            if(u.getPath().endsWith("search")) response.put("results",new JSONArray().put(new JSONObject().put("file_id",123).put("language","bg").put("release","Real release label")));
            else {long expiry=System.currentTimeMillis()/1000+900;response.put("file_id",123).put("offset_seconds",0).put("release","Real release label").put("expires",expiry)
                    .put("url",base+"/file/"+"a".repeat(64)+"?expires="+expiry+"&sig="+"b".repeat(64));}
            return new SubtitleIntegrationTest.Fake(u,200,response.toString());}));
        picker.show();assertEquals(0,calls[0]);button("Search OpenSubtitles").performClick();settle();assertEquals(1,calls[0]);
        button("Real release label").performClick();settle();assertEquals(2,calls[0]);assertEquals(1,applied);assertFalse(picker.showing());
    }
    @Test public void dismissedRequestCannotApplyOrReplaceANewerMediaSession()throws Exception{
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        picker=new SubtitlePicker(activity,host(),()->new SubtitleApi("https://nyama.fun/api/subtitles",()->new String[]{"a","b"},u->{started.countDown();release.await(2,TimeUnit.SECONDS);return new SubtitleIntegrationTest.Fake(u,200,"{\"ok\":true}");}));
        picker.show();button("Search OpenSubtitles").performClick();assertTrue(started.await(2,TimeUnit.SECONDS));picker.pause();
        url=TvPlaybackSource.movie("tt0133093","en",1);release.countDown();settle();assertEquals(0,applied);assertFalse(picker.showing());
    }
}
