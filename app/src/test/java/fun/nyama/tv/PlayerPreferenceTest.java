package fun.nyama.tv;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class PlayerPreferenceTest {
    private Context context;
    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("nyama_tv_private", 0).edit().clear().commit();
        context.getSharedPreferences("nyama_activation_mode", 0).edit().clear().commit();
    }
    @Test public void migrationDefaultsToPlayerTwoAndAddsOnlyOnePreference() {
        SharedPreferences prefs = context.getSharedPreferences("nyama_tv_private", 0);
        prefs.edit().putString("playlist_url", "https://legacy.invalid/live.m3u")
                .putString("device_mode", "phone").putString("ui_language", "bg")
                .putString("last_channel_key", "saved-channel").putString("activation_marker", "unchanged").commit();
        Map<String, ?> before = new HashMap<>(prefs.getAll());
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(context);
        assertEquals(fun.nyama.tv.Config.PLAYER_2, config.movieSeriesPlayer());
        assertEquals(before, prefs.getAll());
        String uiSignature = config.uiSignature();
        config.setMovieSeriesPlayer(fun.nyama.tv.Config.PLAYER_1);
        assertEquals(fun.nyama.tv.Config.PLAYER_1, new fun.nyama.tv.Config(context).movieSeriesPlayer());
        Map<String, Object> after = new HashMap<>(prefs.getAll());
        assertEquals("player_1", after.remove("movie_series_player")); assertEquals(before, after);
        assertEquals(uiSignature, config.uiSignature()); // No forced app recreation for this preference.
        config.setMovieSeriesPlayer(fun.nyama.tv.Config.PLAYER_2);
        assertEquals(fun.nyama.tv.Config.PLAYER_2, new fun.nyama.tv.Config(context).movieSeriesPlayer());
    }
    @Test public void malformedPreferencesCannotSupplyArbitraryHosts() {
        fun.nyama.tv.Config config = new fun.nyama.tv.Config(context);
        config.setMovieSeriesPlayer("https://attacker.invalid");
        assertEquals(fun.nyama.tv.Config.PLAYER_2, config.movieSeriesPlayer());
        context.getSharedPreferences("nyama_tv_private", 0).edit().putString("movie_series_player", "attacker.invalid").commit();
        assertEquals(fun.nyama.tv.Config.PLAYER_2, config.movieSeriesPlayer());
    }
    @Test public void settingsShowsOnlyTwoExclusiveLocalizedChoicesOnTvAndPhoneAndRestoresSavedChoice() throws Exception {
        for (boolean phone : new boolean[]{false, true}) for (String lang : new String[]{"bg", "en"}) {
            fun.nyama.tv.Config saved = new fun.nyama.tv.Config(context);
            saved.setDeviceMode(phone ? "phone" : "tv"); saved.setLanguage(lang);
            saved.setMovieSeriesPlayer(fun.nyama.tv.Config.PLAYER_2);
            SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).get();
            fun.nyama.tv.Config config = new fun.nyama.tv.Config(activity);
            Field field = SettingsActivity.class.getDeclaredField("config"); field.setAccessible(true); field.set(activity, config);
            Method build = SettingsActivity.class.getDeclaredMethod("buildUi"); build.setAccessible(true);
            View root = (View) build.invoke(activity);
            RadioGroup group = root.findViewWithTag("movie-series-player");
            assertNotNull(group); assertEquals(2, group.getChildCount());
            RadioButton one = (RadioButton) group.getChildAt(0), two = (RadioButton) group.getChildAt(1);
            assertEquals(activity.getString(R.string.player_1), one.getText().toString());
            assertEquals(activity.getString(R.string.player_2), two.getText().toString());
            assertEquals(lang.equals("en") ? "Player 1" : "Плеър 1", one.getText().toString());
            assertEquals(lang.equals("en") ? "Player 2" : "Плеър 2", two.getText().toString());
            assertFalse(one.isChecked()); assertTrue(two.isChecked());
            one.performClick(); assertTrue(one.isChecked()); assertFalse(two.isChecked());
            assertEquals("player_1", new fun.nyama.tv.Config(activity).movieSeriesPlayer());
            View reopened = (View) build.invoke(activity);
            assertTrue(((RadioButton) reopened.findViewWithTag("movie-series-player-1")).isChecked());
            String visible = text(root).toLowerCase(java.util.Locale.ROOT);
            assertFalse(visible.contains("vidrock")); assertFalse(visible.contains("vaplayer")); assertFalse(visible.contains("streamimdb"));
            if (phone) {
                reopened.findViewWithTag("mobile-nav-movies").performClick();
                Field leave = SettingsActivity.class.getDeclaredField("suppressNextUserLeaveHint"); leave.setAccessible(true);
                assertTrue(leave.getBoolean(activity));
            }
        }
    }
    private String text(View view) {
        StringBuilder out = new StringBuilder();
        if (view instanceof TextView) out.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) out.append(text(((ViewGroup) view).getChildAt(i)));
        return out.toString();
    }
}
