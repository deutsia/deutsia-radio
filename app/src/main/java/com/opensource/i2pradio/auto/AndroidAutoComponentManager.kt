package com.opensource.i2pradio.auto

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.opensource.i2pradio.ui.PreferencesHelper

/**
 * Runtime toggle for the Android Auto integration.
 *
 * Privacy model: Android Auto support is *off by default*. The
 * [DeutsiaMediaLibraryService] component is marked
 * `android:enabled="false"` in the manifest, so until the user explicitly
 * opts in, Android Auto cannot discover or bind to the app and the app
 * does not appear in the car's media source list at all.
 *
 * When the user flips the preference on, this class enables the component
 * at the [PackageManager] level, which causes the automotive descriptor
 * meta-data to become active and Android Auto to see the app. Flipping
 * the preference off disables the component again.
 *
 * The manifest entries (service declaration, automotive meta-data, intent
 * filter) stay in the APK either way — they are simply dormant until the
 * component is enabled.
 */
object AndroidAutoComponentManager {

    /**
     * Fully-qualified class name of the media library service. Keep this in
     * sync with the `<service>` entry in the manifest. We reference the
     * class by name rather than by `::class.java` so that component toggling
     * works even if media3 classes aren't available on the device.
     */
    private const val MEDIA_LIBRARY_SERVICE_CLASS =
        "com.opensource.i2pradio.auto.DeutsiaMediaLibraryService"

    /**
     * Apply the stored preference to the PackageManager component state.
     * Safe to call repeatedly (e.g. on app start) — it is a no-op when the
     * current component state already matches the desired state.
     */
    fun applyStoredPreference(context: Context) {
        setComponentEnabled(context, PreferencesHelper.isAndroidAutoEnabled(context))
    }

    /**
     * Persist the user's opt-in preference and immediately flip the
     * component state to match.
     */
    fun setAndroidAutoEnabled(context: Context, enabled: Boolean) {
        PreferencesHelper.setAndroidAutoEnabled(context, enabled)
        setComponentEnabled(context, enabled)
    }

    /**
     * Flip the [DeutsiaMediaLibraryService] component on or off via
     * [PackageManager.setComponentEnabledSetting]. Uses
     * [PackageManager.DONT_KILL_APP] so the running app isn't torn down by
     * the state change.
     */
    private fun setComponentEnabled(context: Context, enabled: Boolean) {
        val pm = context.packageManager
        val component = ComponentName(context.packageName, MEDIA_LIBRARY_SERVICE_CLASS)

        val desiredState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

        val currentState = try {
            pm.getComponentEnabledSetting(component)
        } catch (e: IllegalArgumentException) {
            // Component isn't declared in the manifest — nothing to do.
            return
        }

        if (currentState == desiredState) return

        try {
            pm.setComponentEnabledSetting(
                component,
                desiredState,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: SecurityException) {
            // Should not happen for a component in our own package, but don't
            // crash the app if the platform rejects the call for some reason.
        }
    }
}
