package com.nova.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.nova.assistant.voice.RecognitionSession
import com.nova.assistant.voice.RecognizerFactory
import com.nova.assistant.voice.TtsFactory
import com.nova.assistant.voice.TtsSession
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Engine-boundary fixtures only: these do not demonstrate audible output or microphone quality. */
internal class FixtureRecognizerFactory : RecognizerFactory {
    val sessions = CopyOnWriteArrayList<FixtureRecognitionSession>()
    val latest: FixtureRecognitionSession? get() = sessions.lastOrNull()

    override fun isRecognitionAvailable(context: Context) = true
    override fun isOnDeviceRecognitionAvailable(context: Context) = false
    override fun create(context: Context, onDevice: Boolean): RecognitionSession =
        FixtureRecognitionSession().also(sessions::add)
}

internal class FixtureRecognitionSession : RecognitionSession {
    private lateinit var events: RecognitionListener
    @Volatile var started = false
        private set
    @Volatile var request: Intent? = null
        private set

    override fun setListener(listener: RecognitionListener) { events = listener }
    override fun startListening(intent: Intent) {
        request = intent
        started = true
        events.onReadyForSpeech(Bundle())
    }
    fun deliver(text: String) {
        events.onResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        })
    }
    override fun stopListening() = Unit
    override fun cancel() = Unit
    override fun destroy() = Unit
}

internal class FixtureTtsFactory(
    private val languageStatus: Int = TextToSpeech.LANG_COUNTRY_AVAILABLE,
    private val automaticCallbacks: Boolean = true
) : TtsFactory {
    val sessions = CopyOnWriteArrayList<FixtureTtsSession>()
    val latest: FixtureTtsSession? get() = sessions.lastOrNull()

    override fun create(context: Context, onInitialized: (Int) -> Unit): TtsSession {
        val session = FixtureTtsSession(languageStatus, automaticCallbacks)
        sessions.add(session)
        // VoiceOutput posts initialization callbacks, including synchronously initialized engines.
        onInitialized(TextToSpeech.SUCCESS)
        return session
    }
}

internal data class SpeechRequest(val text: String, val id: String, val params: Bundle)

internal class FixtureTtsSession(
    private val languageStatus: Int,
    private val automaticCallbacks: Boolean
) : TtsSession {
    override val engines = listOf("fixture.tts")
    override val defaultEngine = "fixture.tts"
    val requests = CopyOnWriteArrayList<SpeechRequest>()
    private var progress: UtteranceProgressListener? = null
    @Volatile var configuredLanguage: Locale? = null
        private set
    @Volatile var appliedRate = 1f
        private set
    @Volatile var appliedPitch = 1f
        private set

    override fun isLanguageAvailable(locale: Locale) = languageStatus
    override fun setLanguage(locale: Locale): Int {
        configuredLanguage = locale
        return languageStatus
    }
    override fun voices(): List<Voice> = if (languageStatus >= TextToSpeech.LANG_AVAILABLE) {
        listOf(Voice("fixture-tr", Locale.forLanguageTag("tr-TR"), Voice.QUALITY_NORMAL,
            Voice.LATENCY_NORMAL, false, emptySet()))
    } else emptyList()
    override fun setVoice(voice: Voice) = TextToSpeech.SUCCESS
    override fun setRate(rate: Float): Int {
        appliedRate = rate
        return TextToSpeech.SUCCESS
    }
    override fun setPitch(pitch: Float): Int {
        appliedPitch = pitch
        return TextToSpeech.SUCCESS
    }
    override fun setAudioAttributes(attributes: AudioAttributes) = TextToSpeech.SUCCESS
    override fun setProgressListener(listener: UtteranceProgressListener) { progress = listener }
    override fun speak(text: String, params: Bundle, utteranceId: String): Int {
        requests.add(SpeechRequest(text, utteranceId, Bundle(params)))
        if (automaticCallbacks) {
            progress?.onStart(utteranceId)
            Handler(Looper.getMainLooper()).post { progress?.onDone(utteranceId) }
        }
        return TextToSpeech.SUCCESS
    }
    fun started(id: String) { progress?.onStart(id) }
    fun completed(id: String) { progress?.onDone(id) }
    override fun stop() = TextToSpeech.SUCCESS
    override fun shutdown() = Unit
}
