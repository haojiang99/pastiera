package it.neuralrad.coolwulf

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Helper class for managing app locale/language settings.
 */
object LocaleHelper {

    /**
     * Gets the current locale based on settings.
     * @param context Application context
     * @return Current locale
     */
    fun getLocale(context: Context): Locale {
        val languageCode = SettingsManager.getAppLanguage(context)
        return when (languageCode) {
            "en" -> Locale.ENGLISH
            "zh" -> Locale.CHINESE
            else -> Locale.getDefault()
        }
    }

    /**
     * Applies the saved locale to the context's resources.
     * Should be called in Activity.onCreate() before super.onCreate()
     * @param context Activity context
     */
    fun applyLocale(context: Context) {
        val languageCode = SettingsManager.getAppLanguage(context)

        // If system, don't modify
        if (languageCode == "system") {
            return
        }

        val locale = when (languageCode) {
            "en" -> Locale.ENGLISH
            "zh" -> Locale.CHINESE
            else -> return
        }

        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    /**
     * Sets and applies the locale, then triggers activity recreation.
     * @param context Activity context
     * @param languageCode "system", "en", or "zh"
     */
    fun setLocale(context: Context, languageCode: String) {
        SettingsManager.setAppLanguage(context, languageCode)
        applyLocale(context)
    }
}
