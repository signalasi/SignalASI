package com.signalasi.chat

import android.content.Context

data class VoiceAssistantConfig(
    val enabled: Boolean,
    val wakeWords: List<String>,
    val wakeProvider: String,
    val wakeModel: String,
    val wakeThreshold: Float,
    val asrProvider: String,
    val asrLanguage: String,
    val ttsProvider: String,
    val microsoftVoice: String,
    val welcomeText: String,
    val targetContactId: String,
    val speakReplies: Boolean
)

object VoiceAssistantSettings {
    private const val PREFS = "signalasi_voice_assistant"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WAKE_WORDS = "wake_words"
    private const val KEY_WAKE_PROVIDER = "wake_provider"
    private const val KEY_WAKE_MODEL = "wake_model"
    private const val KEY_WAKE_THRESHOLD = "wake_threshold"
    private const val KEY_ASR_PROVIDER = "asr_provider"
    private const val KEY_ASR_LANGUAGE = "asr_language"
    private const val KEY_TTS_PROVIDER = "tts_provider"
    private const val KEY_MICROSOFT_VOICE = "microsoft_voice"
    private const val KEY_WELCOME_TEXT = "welcome_text"
    private const val KEY_TARGET_CONTACT = "target_contact"
    private const val KEY_SPEAK_REPLIES = "speak_replies"

    const val PROVIDER_MICROSOFT_EDGE = "microsoft_edge"
    const val PROVIDER_ANDROID = "android"
    const val WAKE_PROVIDER_OPEN_WAKE_WORD = "openwakeword"
    const val WAKE_PROVIDER_ANDROID_ASR = "android_asr"
    const val ASR_PROVIDER_ANDROID = "android_asr"
    const val ASR_PROVIDER_PC_STT = "pc_stt"
    const val DEFAULT_WAKE_MODEL = "hello_world.onnx"
    val SUPPORTED_WAKE_MODELS = listOf(DEFAULT_WAKE_MODEL)

    fun get(context: Context): VoiceAssistantConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val defaultWakeWords = context.getString(R.string.voice_default_wake_words)
        val defaultWelcomeText = context.getString(R.string.voice_default_welcome_text)
        return VoiceAssistantConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            wakeWords = prefs.getString(KEY_WAKE_WORDS, defaultWakeWords)
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() },
            wakeProvider = prefs.getString(KEY_WAKE_PROVIDER, WAKE_PROVIDER_OPEN_WAKE_WORD).orEmpty()
                .ifBlank { WAKE_PROVIDER_OPEN_WAKE_WORD },
            wakeModel = prefs.getString(KEY_WAKE_MODEL, DEFAULT_WAKE_MODEL).orEmpty()
                .takeIf { it in SUPPORTED_WAKE_MODELS }
                ?: DEFAULT_WAKE_MODEL,
            wakeThreshold = prefs.getFloat(KEY_WAKE_THRESHOLD, 0.5f).coerceIn(0.01f, 0.99f),
            asrProvider = prefs.getString(KEY_ASR_PROVIDER, ASR_PROVIDER_PC_STT).orEmpty()
                .ifBlank { ASR_PROVIDER_PC_STT },
            asrLanguage = prefs.getString(KEY_ASR_LANGUAGE, "zh-CN").orEmpty().ifBlank { "zh-CN" },
            ttsProvider = prefs.getString(KEY_TTS_PROVIDER, PROVIDER_MICROSOFT_EDGE).orEmpty()
                .ifBlank { PROVIDER_MICROSOFT_EDGE },
            microsoftVoice = prefs.getString(KEY_MICROSOFT_VOICE, "zh-CN-XiaoxiaoNeural").orEmpty()
                .ifBlank { "zh-CN-XiaoxiaoNeural" },
            welcomeText = prefs.getString(KEY_WELCOME_TEXT, defaultWelcomeText).orEmpty()
                .ifBlank { defaultWelcomeText },
            targetContactId = prefs.getString(KEY_TARGET_CONTACT, "hermes").orEmpty().ifBlank { "hermes" },
            speakReplies = prefs.getBoolean(KEY_SPEAK_REPLIES, true)
        )
    }

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun setWakeWords(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_WAKE_WORDS, value).apply()
    }

    fun setWakeProvider(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_WAKE_PROVIDER, value).apply()
    }

    fun setWakeModel(context: Context, value: String) {
        val model = value.takeIf { it in SUPPORTED_WAKE_MODELS } ?: DEFAULT_WAKE_MODEL
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_WAKE_MODEL, model).apply()
    }

    fun setWakeThreshold(context: Context, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_WAKE_THRESHOLD, value.coerceIn(0.01f, 0.99f)).apply()
    }

    fun setAsrProvider(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ASR_PROVIDER, value).apply()
    }

    fun setAsrLanguage(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ASR_LANGUAGE, value).apply()
    }

    fun setTtsProvider(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TTS_PROVIDER, value).apply()
    }

    fun setMicrosoftVoice(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MICROSOFT_VOICE, value).apply()
    }

    fun setWelcomeText(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_WELCOME_TEXT, value).apply()
    }

    fun setTargetContact(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TARGET_CONTACT, value).apply()
    }

    fun setSpeakReplies(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SPEAK_REPLIES, value).apply()
    }
}
