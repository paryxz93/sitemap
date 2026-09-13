package it.webfuturo.airmouse.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferenze condivise tra l'activity di configurazione e l'AccessibilityService,
 * che vivono in processi logicamente distinti e non possono passarsi oggetti.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var sensitivity: Float
        get() = prefs.getFloat(KEY_SENSITIVITY, 2600f)
        set(value) = prefs.edit().putFloat(KEY_SENSITIVITY, value).apply()

    var deadZone: Float
        get() = prefs.getFloat(KEY_DEAD_ZONE, 0.004f)
        set(value) = prefs.edit().putFloat(KEY_DEAD_ZONE, value).apply()

    var accelerationGain: Float
        get() = prefs.getFloat(KEY_ACCELERATION, 1.9f)
        set(value) = prefs.edit().putFloat(KEY_ACCELERATION, value).apply()

    var filterMinCutoff: Float
        get() = prefs.getFloat(KEY_MIN_CUTOFF, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_MIN_CUTOFF, value).apply()

    var filterBeta: Float
        get() = prefs.getFloat(KEY_BETA, 0.007f)
        set(value) = prefs.edit().putFloat(KEY_BETA, value).apply()

    var doubleClickEnabled: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_CLICK, true)
        set(value) = prefs.edit().putBoolean(KEY_DOUBLE_CLICK, value).apply()

    /** Mostra il touchpad a schermo come sorgente di input alternativa. */
    var touchpadEnabled: Boolean
        get() = prefs.getBoolean(KEY_TOUCHPAD, true)
        set(value) = prefs.edit().putBoolean(KEY_TOUCHPAD, value).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        private const val FILE = "airmouse_settings"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_DEAD_ZONE = "dead_zone"
        private const val KEY_ACCELERATION = "acceleration"
        private const val KEY_MIN_CUTOFF = "min_cutoff"
        private const val KEY_BETA = "beta"
        private const val KEY_DOUBLE_CLICK = "double_click"
        private const val KEY_TOUCHPAD = "touchpad"
    }
}
