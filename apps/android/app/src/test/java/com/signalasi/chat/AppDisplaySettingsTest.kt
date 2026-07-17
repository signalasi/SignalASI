package com.signalasi.chat

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class AppDisplaySettingsTest {
    @Test
    fun textScaleDefaultsToComfortableAndParsesKnownValues() {
        assertEquals(
            AppDisplaySettings.TextScaleMode.COMFORTABLE,
            AppDisplaySettings.TextScaleMode.fromWireValue(null)
        )
        assertEquals(
            AppDisplaySettings.TextScaleMode.EXTRA_LARGE,
            AppDisplaySettings.TextScaleMode.fromWireValue("EXTRA_LARGE")
        )
    }

    @Test
    fun fixedAndSystemTextScalesResolvePredictably() {
        assertEquals(
            1.10f,
            AppDisplaySettings.resolvedFontScale(AppDisplaySettings.TextScaleMode.COMFORTABLE, 1.45f),
            0.001f
        )
        assertEquals(
            1.45f,
            AppDisplaySettings.resolvedFontScale(AppDisplaySettings.TextScaleMode.SYSTEM, 1.45f),
            0.001f
        )
        assertEquals(
            2.0f,
            AppDisplaySettings.resolvedFontScale(AppDisplaySettings.TextScaleMode.SYSTEM, 2.0f),
            0.001f
        )
    }

    @Test
    fun systemNightModeOverridesLegacyApplicationMode() {
        val staleApplicationMode = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES
        val systemMode = android.app.UiModeManager.MODE_NIGHT_NO

        val resolved = AppDisplaySettings.resolvedUiMode(staleApplicationMode, systemMode)

        assertEquals(
            Configuration.UI_MODE_NIGHT_NO,
            resolved and Configuration.UI_MODE_NIGHT_MASK
        )
        assertEquals(
            Configuration.UI_MODE_TYPE_NORMAL,
            resolved and Configuration.UI_MODE_TYPE_MASK
        )
    }

    @Test
    fun automaticSystemModePreservesResolvedNightMask() {
        val current = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES

        val resolved = AppDisplaySettings.resolvedUiMode(
            current,
            android.app.UiModeManager.MODE_NIGHT_AUTO
        )

        assertEquals(
            Configuration.UI_MODE_NIGHT_YES,
            resolved and Configuration.UI_MODE_NIGHT_MASK
        )
    }
}
