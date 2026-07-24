package com.signalasi.chat

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build

object AppDisplaySettings {
    private const val PREFS = "signalasi_display"
    private const val KEY_TEXT_SCALE = "text_scale"

    enum class TextScaleMode(val wireValue: String, val fixedScale: Float?) {
        SYSTEM("system", null),
        STANDARD("standard", 1.0f),
        COMFORTABLE("comfortable", 1.10f),
        LARGE("large", 1.20f),
        EXTRA_LARGE("extra_large", 1.32f);

        companion object {
            fun fromWireValue(value: String?): TextScaleMode = entries.firstOrNull {
                it.wireValue == value?.trim()?.lowercase()
            } ?: COMFORTABLE
        }
    }

    fun textScale(context: Context): TextScaleMode {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return TextScaleMode.fromWireValue(preferences.getString(KEY_TEXT_SCALE, null))
    }

    fun setTextScale(context: Context, mode: TextScaleMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TEXT_SCALE, mode.wireValue)
            .commit()
    }

    fun overrideConfiguration(context: Context): Configuration {
        synchronizeNightMode(context)
        return Configuration().also { configuration ->
            configuration.fontScale = resolvedFontScale(
                textScale(context),
                context.resources.configuration.fontScale
            )
            configuration.uiMode = resolvedUiMode(
                context.resources.configuration.uiMode,
                (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.nightMode
            ) and Configuration.UI_MODE_NIGHT_MASK
        }
    }

    @Suppress("DEPRECATION")
    fun applyToResources(context: Context) {
        val resources = context.resources
        val configuration = Configuration(resources.configuration)
        val override = overrideConfiguration(context)
        configuration.fontScale = override.fontScale
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (override.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        val displayMetrics = resources.displayMetrics
        if (textScale(context).fixedScale != null) {
            displayMetrics.scaledDensity = displayMetrics.density * configuration.fontScale
        }
        resources.updateConfiguration(configuration, displayMetrics)
    }

    fun synchronizeNightMode(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val manager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager ?: return false
        val systemMode = manager.nightMode
        val currentNightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val expectedNightMask = when (systemMode) {
            UiModeManager.MODE_NIGHT_NO -> Configuration.UI_MODE_NIGHT_NO
            UiModeManager.MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
            else -> currentNightMask
        }
        manager.setApplicationNightMode(systemMode)
        return currentNightMask != expectedNightMask
    }

    internal fun resolvedFontScale(mode: TextScaleMode, systemScale: Float): Float =
        mode.fixedScale ?: systemScale

    internal fun resolvedUiMode(currentUiMode: Int, systemNightMode: Int?): Int {
        val nightMask = when (systemNightMode) {
            UiModeManager.MODE_NIGHT_NO -> Configuration.UI_MODE_NIGHT_NO
            UiModeManager.MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
            else -> currentUiMode and Configuration.UI_MODE_NIGHT_MASK
        }
        return (currentUiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMask
    }

}
