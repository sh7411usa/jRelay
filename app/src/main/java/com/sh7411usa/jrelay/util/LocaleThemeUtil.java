package com.sh7411usa.jrelay.util;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

public final class LocaleThemeUtil {
    private LocaleThemeUtil() {}

    public static Context wrap(Context base) {
        Prefs prefs = new Prefs(base);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        String languageCode = prefs.getLanguageCode();
        if (!Prefs.LANGUAGE_SYSTEM.equals(languageCode)) {
            Locale locale = new Locale(languageCode);
            Locale.setDefault(locale);
            config.setLocale(locale);
        }
        Prefs.ThemeChoice theme = prefs.getThemeChoice();
        int night = theme == Prefs.ThemeChoice.LIGHT ? Configuration.UI_MODE_NIGHT_NO
                : theme == Prefs.ThemeChoice.DARK ? Configuration.UI_MODE_NIGHT_YES : -1;
        if (night != -1) config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return base.createConfigurationContext(config);
    }
}
