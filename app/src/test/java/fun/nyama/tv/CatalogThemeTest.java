package fun.nyama.tv;

import android.app.Application;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Simulated native card focus/hover checks; no provider or Android TV hardware access. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class CatalogThemeTest {
    private CatalogActivity activity;
    private View root;
    private GridView grid;

    @Before public void before() {
        RuntimeEnvironment.getApplication().getSharedPreferences("nyama_tv_private", 0).edit().clear().commit();
    }

    private void catalog(String theme, boolean phone) throws Exception {
        // Build only the native UI, without onCreate's catalogue HTTP calls.
        ActivityController<CatalogActivity> controller = Robolectric.buildActivity(CatalogActivity.class);
        activity = controller.get();
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(activity);
        config.setDeviceMode(phone ? fun.nyama.tv.Config.MODE_PHONE : fun.nyama.tv.Config.MODE_TV);
        config.setColorTheme(theme);
        field("config").set(activity, config);
        field("section").set(activity, "movies");
        Method build = CatalogActivity.class.getDeclaredMethod("buildUi");
        build.setAccessible(true);
        root = (View) build.invoke(activity);
        activity.setContentView(root);
        // Attach the native window so GridView's posted selection notifications are delivered.
        controller.visible();
        grid = (GridView) field("grid").get(activity);
        BaseAdapter adapter = (BaseAdapter) grid.getAdapter();
        Method setItems = adapter.getClass().getDeclaredMethod("setItems", List.class);
        setItems.setAccessible(true);
        // Empty IDs intentionally avoid poster HTTP calls.
        setItems.invoke(adapter, Arrays.asList(item("First movie"), item("Second movie")));
        layout();
    }

    private static CatalogItem item(String title) {
        return new CatalogItem("", "movie", title, "", "2024", "", "", "", "", "", "", "");
    }

    private static Field field(String name) throws Exception {
        Field field = CatalogActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void layout() {
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1920, 1080);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private LinearLayout card(int position) { return (LinearLayout) grid.getChildAt(position); }

    private static int background(View view) {
        return ((GradientDrawable) view.getBackground()).getColor().getDefaultColor();
    }

    private static TextView title(LinearLayout card) { return (TextView) card.getChildAt(1); }
    private static TextView year(LinearLayout card) { return (TextView) card.getChildAt(2); }

    private static void hover(View view, int action) {
        MotionEvent event = MotionEvent.obtain(0L, 0L, action, 10f, 10f, 0);
        event.setSource(InputDevice.SOURCE_MOUSE);
        view.dispatchGenericMotionEvent(event);
        event.recycle();
    }

    @Test public void supportedAccentThemesChooseAtLeastWcagAaTextContrast() {
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(RuntimeEnvironment.getApplication());
        for (String theme : new String[] { fun.nyama.tv.Config.THEME_DEFAULT, fun.nyama.tv.Config.THEME_ORANGE,
                fun.nyama.tv.Config.THEME_GREEN, fun.nyama.tv.Config.THEME_BLUE }) {
            config.setColorTheme(theme);
            int accent = UiTheme.accent(RuntimeEnvironment.getApplication());
            int text = UiTheme.contrastingTextColor(accent);
            assertTrue(text == Color.BLACK || text == Color.WHITE);
            assertTrue(theme + " contrast", ColorUtils.calculateContrast(text, accent) >= 4.5);
        }
    }

    @Test public void darkAndLightBackgroundsChooseWhiteAndBlackRespectively() {
        assertEquals(Color.WHITE, UiTheme.contrastingTextColor(0xFF101820));
        assertEquals(Color.BLACK, UiTheme.contrastingTextColor(0xFFF0F0F0));
        // The old common brightness heuristic selects white here, below 4.5:1.
        assertEquals(Color.BLACK, UiTheme.contrastingTextColor(0xFF2196F3));
    }

    @Test public void remoteFocusUsesExactAccentAndRestoresPreviousAndUnfocusedCards() throws Exception {
        catalog(fun.nyama.tv.Config.THEME_BLUE, false);
        grid.requestFocus();
        grid.setSelection(0);
        layout();
        int accent = UiTheme.accent(activity);
        assertEquals(accent, background(card(0)));
        assertEquals(UiTheme.contrastingTextColor(accent), title(card(0)).getCurrentTextColor());
        assertEquals(title(card(0)).getCurrentTextColor(), year(card(0)).getCurrentTextColor());
        assertTrue(card(0).getElevation() > 0f);
        assertEquals(0xFF11161C, ((android.graphics.drawable.ColorDrawable) ((ImageView) card(0).getChildAt(0)).getBackground()).getColor());
        // Exercise Android's actual remote path instead of directly assigning selection.
        assertTrue(grid.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));
        grid.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
        layout();
        assertEquals(1, grid.getSelectedItemPosition());
        assertEquals(0xFF11161C, background(card(0)));
        assertEquals(Color.WHITE, title(card(0)).getCurrentTextColor());
        assertEquals(accent, background(card(1)));
        EditText search = (EditText) field("search").get(activity);
        assertTrue(search.requestFocus());
        layout();
        assertTrue(search.hasFocus());
        assertFalse(grid.hasFocus());
        assertEquals(0xFF11161C, background(card(1)));
        assertEquals(0xFF9CA6B1, year(card(1)).getCurrentTextColor());
        assertEquals(0f, card(1).getElevation(), 0f);
    }

    @Test public void hoverUsesEveryAccentAndRestoresNormalAppearanceOnLeave() throws Exception {
        for (String theme : new String[] { fun.nyama.tv.Config.THEME_DEFAULT, fun.nyama.tv.Config.THEME_ORANGE,
                fun.nyama.tv.Config.THEME_GREEN, fun.nyama.tv.Config.THEME_BLUE }) {
            catalog(theme, false);
            ((EditText) field("search").get(activity)).requestFocus();
            layout();
            LinearLayout card = card(1);
            hover(card, MotionEvent.ACTION_HOVER_ENTER);
            int accent = UiTheme.accent(activity);
            assertEquals(accent, background(card));
            assertTrue(ColorUtils.calculateContrast(title(card).getCurrentTextColor(), accent) >= 4.5);
            assertEquals("Second movie", title(card).getText().toString());
            assertEquals("2024", year(card).getText().toString());
            hover(card, MotionEvent.ACTION_HOVER_EXIT);
            assertEquals(0xFF11161C, background(card));
            assertEquals(Color.WHITE, title(card).getCurrentTextColor());
            assertEquals(0xFF9CA6B1, year(card).getCurrentTextColor());
        }
    }

    @Test public void hoverLeavingStillKeepsRemoteFocusedCardHighlighted() throws Exception {
        catalog(fun.nyama.tv.Config.THEME_ORANGE, false);
        grid.requestFocus(); grid.setSelection(0); layout();
        hover(card(0), MotionEvent.ACTION_HOVER_ENTER);
        hover(card(0), MotionEvent.ACTION_HOVER_EXIT);
        assertEquals(UiTheme.accent(activity), background(card(0)));
    }

    @Test public void recycledCardDoesNotKeepAnotherItemsHoverHighlight() throws Exception {
        catalog(fun.nyama.tv.Config.THEME_GREEN, false);
        ((EditText) field("search").get(activity)).requestFocus(); layout();
        LinearLayout card = card(0);
        hover(card, MotionEvent.ACTION_HOVER_ENTER);
        assertEquals(UiTheme.accent(activity), background(card));
        View rebound = grid.getAdapter().getView(1, card, grid);
        assertEquals(0xFF11161C, background(rebound));
        assertEquals("Second movie", title((LinearLayout) rebound).getText().toString());
    }

    @Test public void phoneCardLayoutAndColorsRemainUnchangedOnHover() throws Exception {
        catalog(fun.nyama.tv.Config.THEME_ORANGE, true);
        LinearLayout card = card(0);
        int height = card.getLayoutParams().height;
        hover(card, MotionEvent.ACTION_HOVER_ENTER);
        assertEquals(0xFF11161C, background(card));
        assertEquals(Color.WHITE, title(card).getCurrentTextColor());
        assertEquals(0xFF9CA6B1, year(card).getCurrentTextColor());
        assertEquals(height, card.getLayoutParams().height);
    }
}
