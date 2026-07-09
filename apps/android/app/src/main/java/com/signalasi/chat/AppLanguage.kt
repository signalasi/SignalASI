package com.signalasi.chat

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

object AppLanguage {
    private const val PREFS = "signalasi_language"
    private const val KEY = "language"
    const val ZH_CN = "zh-CN"
    const val EN = "en"

    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, EN) ?: EN

    fun set(context: Context, language: String) {
        val normalized = if (language == EN) EN else ZH_CN
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, normalized)
            .commit()
    }

    fun wrap(context: Context): Context {
        val locale = localeFor(current(context))
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(android.os.LocaleList(locale))
        }
        return context.createConfigurationContext(config)
    }

    @Suppress("DEPRECATION")
    fun applyToResources(context: Context) {
        val locale = localeFor(current(context))
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(android.os.LocaleList(locale))
        }
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    fun displayName(context: Context): String =
        if (current(context) == EN) context.getString(R.string.settings_language_en) else context.getString(R.string.settings_language_zh)

    private fun localeFor(language: String): Locale =
        if (language == EN) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
}
