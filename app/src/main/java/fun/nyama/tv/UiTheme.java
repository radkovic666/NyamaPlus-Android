package fun.nyama.tv;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.widget.ProgressBar;

public final class UiTheme {
    private UiTheme() {}

    public static int accent(Context context) {
        String theme = new Config(context).colorTheme();
        if (Config.THEME_ORANGE.equals(theme)) return Color.rgb(255, 138, 0);
        if (Config.THEME_GREEN.equals(theme)) return Color.rgb(76, 175, 80);
        if (Config.THEME_BLUE.equals(theme)) return Color.rgb(33, 150, 243);
        return Color.rgb(40, 196, 164);
    }

    public static float sp(Context context, float baseSp) {
        return baseSp * new Config(context).textScale();
    }

    public static void tintProgress(Context context, ProgressBar bar) {
        bar.setProgressTintList(ColorStateList.valueOf(accent(context)));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF4B5059));
    }
}
