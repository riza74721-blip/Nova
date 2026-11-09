package com.nova.assistant

import android.content.Context
import com.nova.assistant.brain.NOVA_PREFERENCES

class NovaSettings(context: Context) {
    private val preferences = context.getSharedPreferences(NOVA_PREFERENCES, Context.MODE_PRIVATE)

    init {
        if (!preferences.contains("voice_rate")) {
            val legacy = context.getSharedPreferences("nova", Context.MODE_PRIVATE)
            val rate = 0.5f + legacy.getInt("speed", 5) / 10f
            preferences.edit().putFloat("voice_rate", rate.coerceIn(0.5f, 2f)).apply()
        }
    }

    var assistantName: String
        get() = preferences.getString("assistant_name", "Nova").orEmpty().ifBlank { "Nova" }
        set(value) { preferences.edit().putString("assistant_name", value.trim().take(24).ifBlank { "Nova" }).apply() }

    var voiceEnabled: Boolean
        get() = preferences.getBoolean("voice_enabled", true)
        set(value) { preferences.edit().putBoolean("voice_enabled", value).apply() }

    var rate: Float
        get() = preferences.getFloat("voice_rate", 1f).coerceIn(0.5f, 2f)
        set(value) { preferences.edit().putFloat("voice_rate", value.coerceIn(0.5f, 2f)).apply() }

    var pitch: Float
        get() = preferences.getFloat("voice_pitch", 1f).coerceIn(0.7f, 1.5f)
        set(value) { preferences.edit().putFloat("voice_pitch", value.coerceIn(0.7f, 1.5f)).apply() }

    var voiceId: String
        get() = preferences.getString("voice_id", "").orEmpty()
        set(value) { preferences.edit().putString("voice_id", value).apply() }
}
