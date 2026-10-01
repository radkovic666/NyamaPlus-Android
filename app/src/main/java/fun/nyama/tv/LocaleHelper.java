package fun.nyama.tv;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

public final class LocaleHelper {
    private LocaleHelper() {}

    public static Context wrap(Context base) {
        Config config = new Config(base);
        Locale locale = new Locale(config.language());
        Locale.setDefault(locale);
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);
        configuration.setLayoutDirection(locale);
        return base.createConfigurationContext(configuration);
    }
}
