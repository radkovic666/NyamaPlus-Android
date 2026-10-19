package fun.nyama.tv;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.media3.ui.PlayerView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collections;
import static org.junit.Assert.*;

/** Build native UI only: no Media3 player, playlist, EPG HTTP or provider navigation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class MobileLiveNavigationTest {
    private MainActivity activity;
    private ActivityController<MainActivity> controller;
    @Before public void before() {
        RuntimeEnvironment.getApplication().getSharedPreferences("nyama_tv_private", 0).edit().clear().commit();
        RuntimeEnvironment.getApplication().getSharedPreferences("nyama_activation_mode", 0).edit().clear().commit();
        ActivationAccess.deny();
    }
    @After public void after() throws Exception {
        if (activity != null) {
            ChannelBrowserOverlay browser = (ChannelBrowserOverlay) get("browser");
            if (browser != null && browser.isOpen()) browser.hide();
            Method destroy = MainActivity.class.getDeclaredMethod("onDestroy"); destroy.setAccessible(true); destroy.invoke(activity);
        }
    }
    private Object get(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity);
    }
    private void set(String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(activity, value);
    }
    private void build(boolean phone) throws Exception {
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(RuntimeEnvironment.getApplication());
        config.setDeviceMode(phone ? "phone" : "tv");
        controller = Robolectric.buildActivity(MainActivity.class); activity = controller.get();
        set("config", new fun.nyama.tv.Config(activity)); set("compactUi", phone); set("accent", UiTheme.accent(activity));
        set("channels", Collections.singletonList(new Channel(1, "Synthetic channel", "synthetic", "", "", "Group",
                "https://example.invalid/live.m3u8", null, null))); set("currentIndex", 0);
        Method ui = MainActivity.class.getDeclaredMethod("buildUi"); ui.setAccessible(true); ui.invoke(activity);
        controller.visible();
    }
    private View layout(int width, int height) {
        // Supply the real content parent's orientation bounds after queued platform traversals.
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ViewGroup content = activity.findViewById(android.R.id.content);
        View screen = content.getChildAt(0);
        screen.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        screen.layout(0, 0, width, height); return screen;
    }
    private void showBrowser() throws Exception {
        Method show = MainActivity.class.getDeclaredMethod("showChannelBrowser", boolean.class); show.setAccessible(true); show.invoke(activity, false);
    }
    private void assertLivePictureFits(boolean phone) throws Exception {
        build(phone);
        PlayerView video = (PlayerView) get("playerView");
        androidx.media3.ui.AspectRatioFrameLayout picture = video.findViewById(androidx.media3.ui.R.id.exo_content_frame);
        assertEquals(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT, video.getResizeMode());
        for (int[] size : new int[][]{{360, 800}, {800, 360}, {1280, 800}, {1920, 1080}, {1080, 1920}}) {
            for (float ratio : new float[]{16f / 9, 4f / 3, 2.35f, 2.39f}) {
                picture.setAspectRatio(ratio); layout(size[0], size[1]);
                assertTrue(picture.getWidth() <= video.getWidth()); assertTrue(picture.getHeight() <= video.getHeight());
                assertEquals(Math.min(video.getWidth(), video.getHeight() * ratio), picture.getWidth(), 2f);
                assertEquals(Math.min(video.getHeight(), video.getWidth() / ratio), picture.getHeight(), 2f);
                assertEquals(video.getWidth() / 2f, picture.getLeft() + picture.getWidth() / 2f, 1f);
                assertEquals(video.getHeight() / 2f, picture.getTop() + picture.getHeight() / 2f, 1f);
            }
        }
    }
    @Test public void mobileLivePictureContainsWideClassicAndCinemaFramesAfterResize() throws Exception { assertLivePictureFits(true); }
    @Test public void tvLivePictureContainsWideClassicAndCinemaFramesAfterResize() throws Exception { assertLivePictureFits(false); }
    private void assertClockFitsAtLargeSystemFont(boolean phone) throws Exception {
        RuntimeEnvironment.setFontScale(2.5f);
        try {
            new fun.nyama.tv.Config(RuntimeEnvironment.getApplication()).setClockScale(1.8f);build(phone);layout(800,360);
            assertClockBounds();
        } finally { RuntimeEnvironment.setFontScale(1f); }
    }
    private void assertClockBounds() throws Exception {
        OutlineTextView clock=(OutlineTextView)get("cornerClock");clock.setText("88:88");layout(800,360);
        android.graphics.Paint.FontMetrics metrics=clock.getPaint().getFontMetrics();
        assertTrue("Clock glyph height clipped",metrics.bottom-metrics.top<=clock.getHeight()-clock.getPaddingTop()-clock.getPaddingBottom());
        assertTrue("Clock width clipped",clock.getPaint().measureText("88:88")<=clock.getWidth()-clock.getPaddingLeft()-clock.getPaddingRight());
        assertTrue(clock.getTop()>=0);assertTrue(clock.getBottom()<=((View)clock.getParent()).getHeight());
    }
    @Test public void mobileClockGrowsToFitLargeSystemFontWithoutClipping() throws Exception { assertClockFitsAtLargeSystemFont(true); }
    @Test public void tvClockGrowsToFitLargeSystemFontWithoutClipping() throws Exception { assertClockFitsAtLargeSystemFont(false); }
    @Test public void portraitAndLandscapeHideWhileWatchingAndReserveSpaceOnlyWhenRevealed() throws Exception {
        build(true);
        MobileBottomNav nav = (MobileBottomNav) get("mobileNavigation");
        PlayerView video = (PlayerView) get("playerView"); int resizeMode = video.getResizeMode();
        for (int[] size : new int[][]{{320, 640}, {640, 320}}) {
            nav.hideNow(); layout(size[0], size[1]);
            assertEquals(View.GONE, nav.getVisibility());
            int watchingHeight = video.getHeight(); assertTrue(watchingHeight > 0);
            showBrowser(); layout(size[0], size[1]);
            assertEquals(View.VISIBLE, nav.getVisibility());
            assertTrue(video.getHeight() < watchingHeight);
            assertEquals(resizeMode, video.getResizeMode());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3001));
            layout(size[0], size[1]);
            assertEquals(View.GONE, nav.getVisibility()); assertEquals(watchingHeight, video.getHeight());
            assertEquals(resizeMode, video.getResizeMode());
        }
    }
    @Test public void existingSingleTapStillOpensBrowserAndMakesDestinationsReachable() throws Exception {
        build(true); layout(320, 640);
        PlayerView video = (PlayerView) get("playerView");
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 120, 200, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, 120, 200, 0);
        video.dispatchTouchEvent(down); video.dispatchTouchEvent(up); down.recycle(); up.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertTrue(((ChannelBrowserOverlay) get("browser")).isOpen());
        MobileBottomNav nav = (MobileBottomNav) get("mobileNavigation"); assertEquals(View.VISIBLE, nav.getVisibility());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3001));
        assertEquals(View.GONE, nav.getVisibility());
        now = SystemClock.uptimeMillis();
        MotionEvent menuTouch = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 120, 200, 0);
        activity.dispatchTouchEvent(menuTouch); menuTouch.recycle();
        assertEquals(View.VISIBLE, nav.getVisibility());
        for (String destination : new String[]{"movies", "series", "settings"}) {
            nav.findViewWithTag("mobile-nav-" + destination).performClick();
            Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(destination.equals("settings") ? SettingsActivity.class.getName() : CatalogActivity.class.getName(), intent.getComponent().getClassName());
            if (!destination.equals("settings")) assertEquals(destination, intent.getStringExtra(CatalogActivity.EXTRA_SECTION));
            assertEquals(true, get("suppressNextUserLeaveHint"));
        }
        activity.onClosed(); assertEquals(View.GONE, nav.getVisibility());
    }
    @Test public void mobileMultilineProgrammeDescriptionUsesScrollableNaturalHeight() throws Exception {
        build(true);
        android.widget.LinearLayout info=(android.widget.LinearLayout)get("programmeInfo");
        android.widget.TextView description=(android.widget.TextView)get("programmeInfoDescription");
        description.setText("Long programme description\n".repeat(100));
        ((android.widget.TextView)get("programmeInfoTitle")).setText("Programme title ".repeat(6));
        info.setVisibility(View.VISIBLE);layout(640,320);
        androidx.core.widget.NestedScrollView scroll=info.findViewWithTag("mobile-programme-info-scroll");
        assertNotNull(scroll);assertTrue("Scroll height="+scroll.getHeight()+" info visibility="+info.getVisibility(),scroll.getHeight()>0);
        assertTrue("Content="+scroll.getChildAt(0).getHeight()+" scroll="+scroll.getHeight(),scroll.getChildAt(0).getHeight()>scroll.getHeight());
        assertNull(description.getEllipsize());assertEquals(Integer.MAX_VALUE,description.getMaxLines());
        scroll.scrollTo(0,scroll.getChildAt(0).getHeight());assertTrue(scroll.getScrollY()>0);
    }

    @Test public void tvKeepsOriginalRootAndSidebarWithoutAutoHideNavigation() throws Exception {
        build(false);
        assertNull(get("mobileNavigation"));
        ViewGroup content = activity.findViewById(android.R.id.content);
        assertTrue(content.getChildAt(0) instanceof FrameLayout);
        assertNull(content.findViewWithTag("mobile-bottom-navigation"));
        ChannelBrowserOverlay browser = (ChannelBrowserOverlay) get("browser");
        Field sidebar = ChannelBrowserOverlay.class.getDeclaredField("appPane"); sidebar.setAccessible(true);
        assertNotNull(((View) sidebar.get(browser)).getParent());
    }
}
