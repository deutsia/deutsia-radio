package com.opensource.i2pradio.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * Helper object for managing app language/locale settings.
 *
 * Provides functionality to:
 * - Set app language programmatically
 * - Apply saved language preferences on app startup
 * - Handle system default language
 */
object LocaleHelper {

    /**
     * Set the app's locale based on the language code.
     *
     * @param context The application context
     * @param languageCode The language code (e.g., "en", "es", "fr") or "system" for device default
     */
    fun setLocale(context: Context, languageCode: String): Context {
        val locale = if (languageCode == "system") {
            // Use system default locale
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.resources.configuration.locales[0]
            } else {
                @Suppress("DEPRECATION")
                context.resources.configuration.locale
            }
        } else {
            Locale(languageCode)
        }

        return updateResources(context, locale)
    }

    /**
     * Apply the saved language preference to the context.
     *
     * @param context The application context
     * @return Updated context with the applied locale
     */
    fun applyLanguage(context: Context): Context {
        val languageCode = PreferencesHelper.getAppLanguage(context)
        return setLocale(context, languageCode)
    }

    /**
     * Update the resources configuration with the new locale.
     *
     * @param context The application context
     * @param locale The locale to apply
     * @return Updated context with the new locale
     */
    private fun updateResources(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)

        val resources = context.resources
        val configuration = Configuration(resources.configuration)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocale(locale)
            return context.createConfigurationContext(configuration)
        } else {
            @Suppress("DEPRECATION")
            configuration.locale = locale
            @Suppress("DEPRECATION")
            resources.updateConfiguration(configuration, resources.displayMetrics)
            return context
        }
    }
}
