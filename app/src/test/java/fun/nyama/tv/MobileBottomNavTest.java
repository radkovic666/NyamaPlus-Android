package fun.nyama.tv;

import android.app.Activity;
import android.app.Application;
import android.view.View;
import android.view.MotionEvent;
import android.os.Looper;
import org.robolectric.Shadows;
import org.robolectric.annotation.LooperMode;
import java.time.Duration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class MobileBottomNavTest {
    @Before public void before() {
        RuntimeEnvironment.getApplication().getSharedPreferences("nyama_tv_private", 0).edit().clear().commit();
    }
    private static final class Actions implements AppSidebar.Listener {
        int tv, movies, series, settings;
        public void onTv() { tv++; } public void onMovies() { movies++; }
        public void onSeries() { series++; } public void onSettings() { settings++; }
    }
    @Test public void portraitAndLandscapeReserveContentSpaceAndKeepFourReadableTouchTargets() {
        Activity activity = Robolectric.buildActivity(Activity.class).get();
        new fun.nyama.tv.Config(activity).setLanguage(fun.nyama.tv.Config.LANG_BG);
        Actions actions = new Actions();
        View content = new FrameLayout(activity);
        View screen = MobileBottomNav.wrap(activity, content, "series", actions);
        MobileBottomNav bar = screen.findViewWithTag("mobile-bottom-navigation");
        String[] labels = {"ТВ", "ФИЛМИ", "СЕРИАЛИ", "НАСТРОЙКИ"};
        for (int[] size : new int[][]{{320, 640}, {640, 320}}) {
            screen.measure(View.MeasureSpec.makeMeasureSpec(size[0], View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size[1], View.MeasureSpec.EXACTLY));
            screen.layout(0, 0, size[0], size[1]);
            assertEquals(size[1], bar.getBottom());
            assertEquals(bar.getTop(), content.getBottom());
            assertTrue(content.getHeight() > 0);
            assertEquals(4, bar.getChildCount());
            for (int i = 0; i < 4; i++) {
                LinearLayout item = (LinearLayout) bar.getChildAt(i);
                assertTrue(item.getWidth() >= 64); assertTrue(item.getHeight() >= 56);
                assertTrue(item.isClickable()); assertEquals(i == 2, item.isSelected());
                assertTrue(item.getChildAt(0) instanceof ImageView);
                assertEquals(24, item.getChildAt(0).getWidth());
                TextView label = (TextView) item.getChildAt(1);
                assertEquals(labels[i], label.getText().toString());
                assertEquals(12f, label.getTextSize(), 0.01f);
                assertTrue(label.getLineCount() <= 2);
                assertNotNull(label.getLayout());
                assertTrue(label.getLayout().getLineBottom(label.getLineCount() - 1) <= label.getHeight());
                assertTrue(label.getBottom() <= item.getHeight());
            }
        }
        for (int i = 0; i < 4; i++) bar.getChildAt(i).performClick();
        assertEquals(1, actions.tv); assertEquals(1, actions.movies);
        assertEquals(1, actions.series); assertEquals(1, actions.settings);
    }
    @Test @Config(sdk = {28, 30}) public void systemNavigationAndSideInsetsAreReservedWithoutAccumulating() {
        Activity activity = Robolectric.buildActivity(Activity.class).get();
        View content = new FrameLayout(activity);
        View screen = MobileBottomNav.wrap(activity, content, "tv", new Actions());
        MobileBottomNav bar = screen.findViewWithTag("mobile-bottom-navigation");
        activity.setContentView(screen);
        WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
                .setSystemWindowInsets(Insets.of(24, 30, 36, 20))
                .setStableInsets(Insets.of(24, 30, 36, 20))
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(24, 30, 36, 20))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars(), Insets.of(24, 30, 36, 20)).build();
        for (int i = 0; i < 2; i++) {
            ViewCompat.dispatchApplyWindowInsets(screen, insets);
            assertEquals(24, screen.getPaddingLeft()); assertEquals(30, screen.getPaddingTop());
            assertEquals(36, screen.getPaddingRight()); assertEquals(20, screen.getPaddingBottom());
            assertEquals(4, bar.getPaddingBottom());
        }
    }
    @Test public void liveTvOptInStartsHiddenResetsOnTouchFocusAndCancelsOnExit() {
        Activity activity = Robolectric.buildActivity(Activity.class).get();
        View screen = MobileBottomNav.wrap(activity, new FrameLayout(activity), "tv", new Actions());
        activity.setContentView(screen);
        MobileBottomNav bar = screen.findViewWithTag("mobile-bottom-navigation");
        bar.enableAutoHide(); assertEquals(View.GONE, bar.getVisibility());
        bar.revealTemporarily(); assertEquals(View.VISIBLE, bar.getVisibility());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2500));
        MotionEvent touch = MotionEvent.obtain(0, 2500, MotionEvent.ACTION_DOWN, 4, 4, 0);
        bar.dispatchTouchEvent(touch); touch.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2500));
        assertEquals(View.VISIBLE, bar.getVisibility());
        bar.getChildAt(1).requestFocus();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2500));
        assertEquals(View.VISIBLE, bar.getVisibility());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501));
        assertEquals(View.GONE, bar.getVisibility());
        bar.revealTemporarily(); bar.hideNow();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        assertEquals(View.GONE, bar.getVisibility());
    }
    @Test @Config(sdk = 30) public void liveTvHiddenBarsDoNotLeaveTopOffsetsAndCutoutSafetySurvivesHiddenNavigation() {
        Activity activity = Robolectric.buildActivity(Activity.class).get(); View content=new FrameLayout(activity);
        View screen=MobileBottomNav.wrap(activity,content,"tv",new Actions());activity.setContentView(screen);
        MobileBottomNav bar=screen.findViewWithTag("mobile-bottom-navigation");bar.enableAutoHide();
        WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(),Insets.NONE)
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars(),Insets.of(0,30,0,24))
                .setVisible(WindowInsetsCompat.Type.systemBars(),false)
                .setInsets(WindowInsetsCompat.Type.displayCutout(),Insets.of(28,0,0,12))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout(),Insets.of(28,0,0,12)).build();
        ViewCompat.dispatchApplyWindowInsets(screen,insets);
        screen.measure(View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY));screen.layout(0,0,800,360);
        assertEquals(View.GONE,bar.getVisibility());assertEquals(28,content.getLeft());assertEquals(0,content.getTop());
        assertEquals(348,content.getBottom());assertEquals(0,screen.getPaddingTop());
    }
    @Test public void catalogsAndSettingsNeverOptIntoAutoHide() {
        Activity activity = Robolectric.buildActivity(Activity.class).get();
        for (String section : new String[]{"movies", "series", "settings"}) {
            View screen = MobileBottomNav.wrap(activity, new FrameLayout(activity), section, new Actions());
            MobileBottomNav bar = screen.findViewWithTag("mobile-bottom-navigation");
            bar.revealTemporarily(); bar.hideNow();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));
            assertEquals(View.VISIBLE, bar.getVisibility());
        }
    }
}
