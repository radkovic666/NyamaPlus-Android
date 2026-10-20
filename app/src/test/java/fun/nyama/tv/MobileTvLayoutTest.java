package fun.nyama.tv;

import android.app.Activity;
import android.app.Application;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.core.widget.NestedScrollView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.Assert.*;

/** Native measure/scroll checks with long synthetic guide data, never a guide/network refresh. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class MobileTvLayoutTest {
    private Activity activity;
    private Channel channel;
    private final List<ChannelBrowserOverlay> browsers = new ArrayList<>();
    @SuppressWarnings("unchecked") private Map<String,List<EpgEvent>> guide() throws Exception {
        Field field = EpgRepository.class.getDeclaredField("EVENTS_BY_ID"); field.setAccessible(true);
        return (Map<String,List<EpgEvent>>) field.get(null);
    }
    @Before public void before() throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("nyama_tv_private", 0).edit().clear().commit();
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(RuntimeEnvironment.getApplication());
        config.setDeviceMode("phone"); config.setLanguage("bg"); config.setTextScale(1.4f);
        activity = Robolectric.buildActivity(Activity.class).get();
        channel = new Channel(1, "Дълго име на избрания телевизионен канал", "synthetic", "", "", "Документални и образователни програми", "https://example.invalid/live", null, null);
        long now = System.currentTimeMillis();
        guide().clear(); guide().put("synthetic", Arrays.asList(new EpgEvent(now-100000, now+100000, "СЕГА "+"Дълга програма ".repeat(12), "Описание ".repeat(80)),
                new EpgEvent(now+100000, now+200000, "СЛЕДВА "+"Следваща програма ".repeat(10)+"КРАЙ", "Next description")));
    }
    @After public void after() throws Exception { for(ChannelBrowserOverlay browser:browsers)browser.hide();guide().clear(); }
    private Object get(Object object, String name) throws Exception { Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object); }
    private void layout(View view, int width, int height) {
        for(int i=0;i<3;i++) { view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height); }
    }
    private ChannelBrowserOverlay browser() {
        ChannelBrowserOverlay view = new ChannelBrowserOverlay(activity,null);browsers.add(view);view.show(Collections.singletonList(channel),0);return view;
    }
    private MobileBottomNav nav(View root) {return root.findViewWithTag("mobile-bottom-navigation");}
    private void assertFullText(TextView view) {
        assertNotNull(view.getLayout()); assertTrue(view.getLineCount()>0);
        assertTrue("Text height clipped: "+view.getText(),view.getLayout().getLineBottom(view.getLineCount()-1) <= view.getHeight()-view.getTotalPaddingBottom()-view.getTotalPaddingTop());
    }
    @Test @Config(qualifiers="w320dp-h640dp-port-mdpi") public void portraitBrowserReclaimsDetailCardSpaceAboveNavigation() throws Exception {
        ChannelBrowserOverlay browser=browser();
        View root=MobileBottomNav.wrap(activity,browser,"tv",MobileBottomNav.destinations(activity,"tv"));activity.setContentView(root);layout(root,320,640);
        assertNull(((View)get(browser,"detailPane")).getParent());
        assertEquals(View.GONE,((View)get(browser,"detailPane")).getVisibility());
        assertTrue("Channel viewport="+((View)get(browser,"channelList")).getHeight(),((View)get(browser,"channelList")).getHeight()>=250);
        assertTrue(((View)get(browser,"categoryList")).getHeight()>40);
        assertTrue(browser.getBottom()<=nav(root).getTop());
    }
    @Test @Config(qualifiers="w640dp-h320dp-land-mdpi") public void landscapeBrowserGivesDetailWidthBackToReadableChannelRows() throws Exception {
        ChannelBrowserOverlay browser=browser();View root=MobileBottomNav.wrap(activity,browser,"tv",MobileBottomNav.destinations(activity,"tv"));activity.setContentView(root);layout(root,640,320);
        assertNull(browser.findViewWithTag("mobile-programme-detail-scroll"));
        assertNull(((View)get(browser,"detailPane")).getParent());
        ListView list=(ListView)get(browser,"channelList");assertTrue(list.getWidth()>260);
        View row=list.getAdapter().getView(0,null,list);
        row.measure(View.MeasureSpec.makeMeasureSpec(list.getWidth(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));row.layout(0,0,list.getWidth(),row.getMeasuredHeight());
        LinearLayout labels=(LinearLayout)((ViewGroup)row).getChildAt(2);assertFullText((TextView)labels.getChildAt(0));assertFullText((TextView)labels.getChildAt(1));
        assertTrue(browser.getBottom()<=nav(root).getTop());
    }
    @Test @Config(qualifiers="w640dp-h320dp-land-mdpi") public void epgFullDescriptionCanScrollToItsEndAtLargeTextSize() throws Exception {
        EpgGuideOverlay epg=new EpgGuideOverlay(activity,null);epg.show(channel);layout(epg,640,220);
        NestedScrollView scroll=epg.findViewWithTag("mobile-epg-detail-scroll");assertNotNull(scroll);
        TextView description=(TextView)get(epg,"description");assertFullText(description);
        assertTrue(scroll.getChildAt(0).getHeight()>scroll.getHeight());
        scroll.scrollTo(0,scroll.getChildAt(0).getHeight());assertTrue(scroll.getScrollY()>0);
    }
    @Test @Config(qualifiers="w320dp-h480dp-port-mdpi") public void settingsRadioAndSwitchRowsWrapAtReadableFontSize() throws Exception {
        SettingsActivity settings=Robolectric.buildActivity(SettingsActivity.class).get();
        Field config=SettingsActivity.class.getDeclaredField("config");config.setAccessible(true);config.set(settings,new fun.nyama.tv.Config(settings));
        java.lang.reflect.Method build=SettingsActivity.class.getDeclaredMethod("buildUi");build.setAccessible(true);
        View root=(View)build.invoke(settings);settings.setContentView(root);layout(root,320,480);
        int checked=0;
        for(View view:views(root)) if(view instanceof android.widget.RadioButton || view instanceof android.widget.Switch) {
            assertFullText((TextView)view);assertTrue(view.getHeight()>=52);checked++;
        }
        assertTrue(checked>=10);
        MobileBottomNav nav=nav(root);assertTrue(nav.getHeight()>=56);assertTrue(nav.getTop()<480);
    }
    private List<View> views(View root) {
        List<View> result=new ArrayList<>();result.add(root);
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++)result.addAll(views(((ViewGroup)root).getChildAt(i)));
        return result;
    }
    @Test @Config(qualifiers="w1280dp-h720dp-land-mdpi") public void tvStillUsesUnchangedFixedPanelsAndNoMobileScrollContainers() throws Exception {
        new fun.nyama.tv.Config(activity).setDeviceMode("tv");new fun.nyama.tv.Config(activity).setTextScale(1f);
        ChannelBrowserOverlay browser=browser();layout(browser,1280,720);
        assertNull(browser.findViewWithTag("mobile-programme-detail-scroll"));assertNull(browser.findViewWithTag("mobile-channel-browser-scroll"));
        assertNotNull(((View)get(browser,"detailPane")).getParent());
        assertEquals(View.VISIBLE,((View)get(browser,"detailPane")).getVisibility());
        assertEquals(View.GONE,((View)get(browser,"nowDescription")).getVisibility());assertEquals(View.GONE,((View)get(browser,"detailName")).getVisibility());
        EpgGuideOverlay epg=new EpgGuideOverlay(activity,null);assertNull(epg.findViewWithTag("mobile-epg-detail-scroll"));
        LinearLayout.LayoutParams description = (LinearLayout.LayoutParams)((TextView)get(epg,"description")).getLayoutParams();
        assertEquals(0, description.height); assertEquals(1f, description.weight, 0f);
    }
}
