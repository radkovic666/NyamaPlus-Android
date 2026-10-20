package fun.nyama.tv;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class,qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ClockRenderingTest {
    @Test public void liveClockActuallyDrawsGlyphsAfterPreDrawInEveryCornerOnPhoneAndTv() throws Exception {
        for (boolean phone : new boolean[]{true,false}) for (String corner : new String[]{"top_left","top_right","bottom_left","bottom_right"}) {
            org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class);
            MainActivity activity=controller.get();
            fun.nyama.tv.Config config=new fun.nyama.tv.Config(activity);config.setDeviceMode(phone ? "phone" : "tv");
            config.setShowClock(true);config.setClockPosition(corner);
            for (String name : new String[]{"config","compactUi","resumed","accent"}) {
                java.lang.reflect.Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);
                f.set(activity,name.equals("config")?config:name.equals("accent")?UiTheme.accent(activity):phone || name.equals("resumed"));
            }
            java.lang.reflect.Method build=MainActivity.class.getDeclaredMethod("buildUi");build.setAccessible(true);build.invoke(activity);controller.visible();
            ViewGroup root=activity.findViewById(android.R.id.content);
            root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,360,800);
            java.lang.reflect.Field f=MainActivity.class.getDeclaredField("cornerClock");f.setAccessible(true);OutlineTextView clock=(OutlineTextView)f.get(activity);
            clock.onPreDraw();
            Bitmap bitmap=Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));
            android.graphics.Rect bounds=new android.graphics.Rect(0,0,clock.getWidth(),clock.getHeight());
            View cursor=clock;
            while(cursor!=root) { bounds.offset(cursor.getLeft(),cursor.getTop());cursor=(View)cursor.getParent(); }
            assertTrue("Clock must have visible dimensions",bounds.width()>0 && bounds.height()>0);
            int pixels=0;for(int y=bounds.top;y<bounds.bottom;y++)for(int x=bounds.left;x<bounds.right;x++) {
                int color=bitmap.getPixel(x,y);if(Color.red(color)>180 && Color.green(color)>180 && Color.blue(color)>180)pixels++;
            }
            assertEquals("Custom clock must not inherit TextView's huge horizontal scroll",0,clock.getScrollX());
            assertTrue("No clock glyphs in "+corner+" phone="+phone+" pixels="+pixels,pixels>30);
            bitmap.recycle();
            java.lang.reflect.Method destroy=MainActivity.class.getDeclaredMethod("onDestroy");destroy.setAccessible(true);destroy.invoke(activity);
        }
    }
}
