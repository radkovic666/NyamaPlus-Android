package fun.nyama.tv;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

public final class TvDeviceGuard {
    private TvDeviceGuard() {}

    /** Hardware/profile detection used only to choose a sensible default on first setup. */
    public static boolean hardwareLooksTvLike(Context context) {
        PackageManager pm = context.getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true;

        UiModeManager ui = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        if (ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;

        // Generic Android boxes often do not expose Leanback but also have no touchscreen.
        return !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    /** Kept for backwards compatibility with older code; v3.0.1 intentionally supports phones too. */
    public static boolean isTvLike(Context context) {
        return hardwareLooksTvLike(context) || isDebuggable(context);
    }

    private static boolean isDebuggable(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }
}
