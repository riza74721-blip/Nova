package com.nova.assistant.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

enum class OutputStatus {
    INITIALIZING, READY, SPEAKING, DISABLED, MISSING_ENGINE, MISSING_LANGUAGE, ERROR
}

data class OutputState(val status: OutputStatus, val message: String)

data class VoiceOption(val id: String, val label: String, val requiresNetwork: Boolean)

/** A narrow native-engine boundary. Production always uses [AndroidTtsFactory]. */
interface TtsFactory {
    fun create(context: Context, onInitialized: (Int) -> Unit): TtsSession
}

interface TtsSession {
    val engines: List<String>
    val defaultEngine: String?
    fun isLanguageAvailable(locale: Locale): Int
    fun setLanguage(locale: Locale): Int
    fun voices(): List<Voice>
    fun setVoice(voice: Voice): Int
    fun setRate(rate: Float): Int
    fun setPitch(pitch: Float): Int
    fun setAudioAttributes(attributes: AudioAttributes): Int
    fun setProgressListener(listener: UtteranceProgressListener)
    fun speak(text: String, params: Bundle, utteranceId: String): Int
    fun stop(): Int
    fun shutdown()
}

class AndroidTtsFactory : TtsFactory {
    override fun create(context: Context, onInitialized: (Int) -> Unit): TtsSession {
        val native = TextToSpeech(context.applicationContext, onInitialized)
        return object : TtsSession {
            override val engines: List<String> get() = native.engines.orEmpty().map { it.name }
            override val defaultEngine: String? get() = native.defaultEngine
            override fun isLanguageAvailable(locale: Locale) = native.isLanguageAvailable(locale)
            override fun setLanguage(locale: Locale) = native.setLanguage(locale)
            override fun voices(): List<Voice> = native.voices.orEmpty().toList()
            override fun setVoice(voice: Voice) = native.setVoice(voice)
            override fun setRate(rate: Float) = native.setSpeechRate(rate)
            override fun setPitch(pitch: Float) = native.setPitch(pitch)
            override fun setAudioAttributes(attributes: AudioAttributes) = native.setAudioAttributes(attributes)
            override fun setProgressListener(listener: UtteranceProgressListener) {
                native.setOnUtteranceProgressListener(listener)
            }
            override fun speak(text: String, params: Bundle, utteranceId: String) =
                native.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            override fun stop() = native.stop()
            override fun shutdown() = native.shutdown()
        }
    }
}

/**
 * Owns the foreground app's Turkish speech output. A request made during initialization is
 * retained until the engine is ready. SPEAKING is emitted only after the native engine's
 * utterance-start callback; accepting a queued request never means it was heard.
 */
class VoiceOutput(
    context: Context,
    private val listener: Listener,
    private val factory: TtsFactory = AndroidTtsFactory()
) {
    interface Listener {
        fun onState(state: OutputState)
        fun onUtteranceStarted(id: String) {}
        fun onUtteranceDone(id: String) {}
        fun onUtteranceError(id: String, message: String) {}
        fun onVolumeMuted() {}
    }

    private data class PendingSpeech(val text: String, val id: String)

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(speechAttributes)
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                stop()
            }
        }, main)
        .build()

    private var session: TtsSession? = null
    private var configured = false
    private var destroyed = false
    private var generation = 0
    private var counter = 0L
    private var focusHeld = false
    private var pending: PendingSpeech? = null
    private var activeId: String? = null
    private var activeTextLength = 0
    private var rate = 1f
    private var pitch = 1f
    private var preferredVoice: String? = null
    private var cachedVoices: List<Voice> = emptyList()
    private var enginePackage: String? = null
    private var timeout: Runnable? = null
    private var utteranceWatchdog: Runnable? = null

    var currentState: OutputState = OutputState(OutputStatus.INITIALIZING, "Türkçe ses hazırlanıyor…")
        private set

    val isReady: Boolean get() = configured && enabled && !destroyed

    var enabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            onMain {
                if (destroyed) return@onMain
                if (!value) {
                    stopInternal()
                    publish(OutputStatus.DISABLED, "Sesli yanıt kapalı.")
                } else if (configured) {
                    publishReady()
                } else {
                    initialize()
                }
            }
        }

    fun initialize() = onMain {
        if (destroyed) return@onMain
        if (!enabled) {
            publish(OutputStatus.DISABLED, "Sesli yanıt kapalı.")
            return@onMain
        }
        if (currentState.status == OutputStatus.INITIALIZING && session != null) return@onMain
        timeout?.let(main::removeCallbacks)
        cancelUtteranceWatchdog()
        generation++
        val thisGeneration = generation
        configured = false
        activeId = null
        abandonFocus()
        runCatching { session?.stop() }
        runCatching { session?.shutdown() }
        session = null
        cachedVoices = emptyList()
        publish(OutputStatus.INITIALIZING, "Türkçe ses hazırlanıyor…")
        try {
            // Posting also handles engines/factories that invoke onInit inside their constructor:
            // session is assigned before the callback can inspect it.
            session = factory.create(appContext) { status ->
                main.post {
                    if (!destroyed && generation == thisGeneration) onInitialized(status, thisGeneration)
                }
            }
            timeout = Runnable {
                if (!destroyed && generation == thisGeneration && !configured) {
                    generation++
                    runCatching { session?.shutdown() }
                    session = null
                    fail(OutputStatus.ERROR, "Ses motoru yanıt vermedi. Ses ayarlarını kontrol edip yeniden deneyebilirsin.")
                }
            }.also { main.postDelayed(it, INITIALIZATION_TIMEOUT_MS) }
        } catch (_: RuntimeException) {
            fail(OutputStatus.ERROR, "Ses motoru başlatılamadı. Ses ayarlarını kontrol edebilirsin.")
        }
    }

    fun speak(text: String, utteranceId: String? = null) = onMain {
        if (destroyed || text.isBlank()) return@onMain
        val request = PendingSpeech(text.trim(), utteranceId ?: "nova_${++counter}")
        if (!enabled) {
            publish(OutputStatus.DISABLED, "Sesli yanıt kapalı.")
            return@onMain
        }
        pending = request
        if (configured) speakPending() else initialize()
    }

    fun stop() = onMain {
        if (destroyed) return@onMain
        stopInternal()
        if (!enabled) publish(OutputStatus.DISABLED, "Sesli yanıt kapalı.")
        else if (configured) publishReady()
    }

    fun destroy() = onMain {
        if (destroyed) return@onMain
        stopInternal()
        destroyed = true
        generation++
        timeout?.let(main::removeCallbacks)
        timeout = null
        runCatching { session?.shutdown() }
        session = null
        configured = false
        cachedVoices = emptyList()
    }

    fun setRate(value: Float) = onMain {
        rate = if (value.isFinite()) value.coerceIn(0.5f, 2f) else 1f
        try {
            if (configured && session?.setRate(rate) == TextToSpeech.ERROR) {
                fail(OutputStatus.ERROR, "Konuşma hızı uygulanamadı. Ses motorunu yeniden başlatabilirsin.")
            }
        } catch (_: RuntimeException) {
            fail(OutputStatus.ERROR, "Ses motoruyla bağlantı kesildi. Yeniden deneyebilirsin.")
        }
    }

    fun setPitch(value: Float) = onMain {
        pitch = if (value.isFinite()) value.coerceIn(0.5f, 2f) else 1f
        try {
            if (configured && session?.setPitch(pitch) == TextToSpeech.ERROR) {
                fail(OutputStatus.ERROR, "Ses tonu uygulanamadı. Ses motorunu yeniden başlatabilirsin.")
            }
        } catch (_: RuntimeException) {
            fail(OutputStatus.ERROR, "Ses motoruyla bağlantı kesildi. Yeniden deneyebilirsin.")
        }
    }

    fun setVoice(id: String?) = onMain {
        val selected = id?.takeIf(String::isNotBlank)
        if (selected == preferredVoice) return@onMain
        preferredVoice = selected
        if (configured) {
            stopInternal()
            configureTurkishVoice()
        }
    }

    fun availableVoices(): List<VoiceOption> = cachedVoices.map { voice ->
        VoiceOption(
            id = voice.name,
            label = "${voice.locale.getDisplayName(TURKISH)} · ${voice.name}",
            requiresNetwork = voice.isNetworkConnectionRequired
        )
    }

    /** User-initiated alternatives; the Activity should try the first resolvable intent. */
    fun setupIntents(): List<Intent> {
        val settings = Intent("com.android.settings.TTS_SETTINGS")
        val installData = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
            enginePackage?.let { setPackage(it) }
        }
        val checkData = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA).apply {
            enginePackage?.let { setPackage(it) }
        }
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts"))
        return when (currentState.status) {
            OutputStatus.MISSING_ENGINE -> listOf(market, web, settings, Intent(Settings.ACTION_SETTINGS))
            OutputStatus.MISSING_LANGUAGE -> listOf(installData, settings, checkData, Intent(Settings.ACTION_SETTINGS))
            else -> listOf(settings, checkData, installData, Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun onInitialized(status: Int, thisGeneration: Int) {
        timeout?.let(main::removeCallbacks)
        timeout = null
        val engine = session ?: return
        enginePackage = runCatching { engine.defaultEngine ?: engine.engines.firstOrNull() }.getOrNull()
        if (status != TextToSpeech.SUCCESS) {
            val absent = runCatching { engine.engines.isEmpty() }.getOrDefault(false)
            fail(
                if (absent) OutputStatus.MISSING_ENGINE else OutputStatus.ERROR,
                if (absent) "Telefonda seslendirme motoru bulunamadı. Türkçe destekleyen bir ses motoru kurabilirsin."
                else "Ses motoru açılamadı. Telefonun metin okuma ayarlarını kontrol edebilirsin."
            )
            return
        }
        engine.setProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = dispatchCurrent(utteranceId, thisGeneration) { id ->
                cancelUtteranceWatchdog()
                publish(OutputStatus.SPEAKING, "Nova konuşuyor…")
                listener.onUtteranceStarted(id)
                val expectedDuration = (activeTextLength * 120L / rate + 15_000L).toLong().coerceIn(30_000L, 180_000L)
                if (activeId == id && enabled && configured) {
                    armUtteranceWatchdog(id, expectedDuration, "Seslendirme tamamlanamadı. Ses motorunu yeniden deneyebilirsin.")
                }
            }
            override fun onDone(utteranceId: String?) = dispatchCurrent(utteranceId, thisGeneration) { id ->
                cancelUtteranceWatchdog()
                activeId = null
                abandonFocus()
                publishReady()
                listener.onUtteranceDone(id)
            }
            @Deprecated("Native compatibility callback")
            override fun onError(utteranceId: String?) = utteranceError(utteranceId, thisGeneration, TextToSpeech.ERROR)
            override fun onError(utteranceId: String?, errorCode: Int) = utteranceError(utteranceId, thisGeneration, errorCode)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = dispatchCurrent(utteranceId, thisGeneration) {
                cancelUtteranceWatchdog()
                activeId = null
                abandonFocus()
                if (enabled && configured) publishReady()
            }
        })
        configureTurkishVoice()
        if (configured) speakPending()
    }

    private fun configureTurkishVoice() {
        val engine = session ?: return
        try {
            val available = engine.isLanguageAvailable(TURKISH)
            if (available < TextToSpeech.LANG_AVAILABLE) {
                fail(
                    OutputStatus.MISSING_LANGUAGE,
                    if (available == TextToSpeech.LANG_MISSING_DATA) "Türkçe ses paketi eksik. Ses ayarlarından Türkçe ses verisini indirebilirsin."
                    else "Bu ses motoru Türkçeyi desteklemiyor. Türkçe destekleyen bir motor seçebilirsin."
                )
                return
            }
            val turkishVoices = engine.voices().filter { it.locale.language == TURKISH.language }
            cachedVoices = turkishVoices.filter { voice ->
                voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
            }.sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenByDescending { it.quality }.thenBy { it.name })
            if (turkishVoices.isNotEmpty() && cachedVoices.isEmpty()) {
                fail(OutputStatus.MISSING_LANGUAGE, "Türkçe ses paketi henüz kurulmamış. Ses ayarlarından indirebilirsin.")
                return
            }
            if (engine.setLanguage(TURKISH) < TextToSpeech.LANG_AVAILABLE) {
                fail(OutputStatus.MISSING_LANGUAGE, "Türkçe ses seçilemedi. Ses ayarlarından Türkçe ses paketini kontrol edebilirsin.")
                return
            }
            val selected = cachedVoices.firstOrNull { it.name == preferredVoice } ?: cachedVoices.firstOrNull()
            if (selected != null && engine.setVoice(selected) == TextToSpeech.ERROR) {
                fail(OutputStatus.ERROR, "Seçilen Türkçe ses açılamadı. Başka bir ses seçebilirsin.")
                return
            }
            if (engine.setRate(rate) == TextToSpeech.ERROR || engine.setPitch(pitch) == TextToSpeech.ERROR ||
                engine.setAudioAttributes(speechAttributes) == TextToSpeech.ERROR) {
                fail(OutputStatus.ERROR, "Ses motoru ayarlanamadı. Telefonun metin okuma ayarlarını kontrol edebilirsin.")
                return
            }
            configured = true
            publishReady()
        } catch (_: RuntimeException) {
            fail(OutputStatus.ERROR, "Türkçe ses hazırlanamadı. Ses motorunu yeniden deneyebilirsin.")
        }
    }

    private fun speakPending() {
        val request = pending ?: return
        val engine = session ?: return
        if (!enabled || !configured) return
        pending = null
        // Cancel the previous reply before checking the new request. Otherwise raising a muted
        // volume later could reveal a stale utterance that should already have been replaced.
        stopInternal()
        val audio = audioManager
        if (audio == null) {
            listener.onUtteranceError(request.id, "Telefonun ses çıkışına erişilemiyor.")
            publish(OutputStatus.ERROR, "Telefonun ses çıkışına erişilemiyor.")
            return
        }
        try {
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC) || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                publish(OutputStatus.READY, "Medya sesi kapalı. Telefonun medya sesini açıp yeniden deneyebilirsin.")
                listener.onVolumeMuted()
                listener.onUtteranceError(request.id, "Medya sesi kapalı.")
                return
            }
            if (!focusHeld) focusHeld = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: RuntimeException) {
            abandonFocus()
            publish(OutputStatus.ERROR, "Telefonun ses çıkışı yanıt vermedi. Yeniden deneyebilirsin.")
            listener.onUtteranceError(request.id, currentState.message)
            return
        }
        if (!focusHeld) {
            publish(OutputStatus.ERROR, "Ses çıkışı şu anda başka bir uygulama tarafından kullanılıyor. Yeniden deneyebilirsin.")
            listener.onUtteranceError(request.id, "Ses çıkışı kullanılamıyor.")
            return
        }
        cancelUtteranceWatchdog()
        activeId = request.id
        activeTextLength = request.text.length
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        try {
            if (engine.speak(request.text, params, request.id) == TextToSpeech.ERROR) {
                activeId = null
                abandonFocus()
                configured = false
                publish(OutputStatus.ERROR, "Yanıt seslendirilemedi. Ses motorunu kontrol edip yeniden deneyebilirsin.")
                listener.onUtteranceError(request.id, currentState.message)
            } else {
                publish(OutputStatus.READY, "Sesli yanıt başlatılıyor…")
                if (activeId == request.id && enabled && configured) {
                    armUtteranceWatchdog(request.id, UTTERANCE_START_TIMEOUT_MS, "Ses motoru yanıtı başlatmadı. Ses ayarlarını kontrol edip yeniden deneyebilirsin.")
                }
            }
        } catch (_: RuntimeException) {
            activeId = null
            abandonFocus()
            configured = false
            publish(OutputStatus.ERROR, "Ses motoruyla bağlantı kesildi. Yeniden deneyebilirsin.")
            listener.onUtteranceError(request.id, currentState.message)
        }
    }

    private fun utteranceError(id: String?, thisGeneration: Int, errorCode: Int) = dispatchCurrent(id, thisGeneration) { utteranceId ->
        cancelUtteranceWatchdog()
        activeId = null
        configured = false
        abandonFocus()
        val message = when (errorCode) {
            TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> "Ses motoru internete erişemedi. Bağlantını kontrol et veya cihazdaki bir Türkçe sesi seç."
            TextToSpeech.ERROR_NOT_INSTALLED_YET -> "Seçilen ses henüz indirilmemiş. Türkçe ses paketini kurabilirsin."
            TextToSpeech.ERROR_OUTPUT -> "Telefonun ses çıkışı yanıt vermedi. Medya sesini ve bağlı ses cihazlarını kontrol edebilirsin."
            else -> "Seslendirme tamamlanamadı. Ses motorunu kontrol edip yeniden deneyebilirsin."
        }
        publish(if (errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET) OutputStatus.MISSING_LANGUAGE else OutputStatus.ERROR, message)
        listener.onUtteranceError(utteranceId, message)
    }

    private fun stopInternal() {
        cancelUtteranceWatchdog()
        pending = null
        activeId = null
        runCatching { session?.stop() }
        abandonFocus()
    }

    private fun fail(status: OutputStatus, message: String) {
        cancelUtteranceWatchdog()
        configured = false
        activeId = null
        runCatching { session?.stop() }
        abandonFocus()
        publish(status, message)
        pending?.let { listener.onUtteranceError(it.id, message) }
        pending = null
    }

    private fun publishReady() {
        if (!enabled) publish(OutputStatus.DISABLED, "Sesli yanıt kapalı.")
        else if (configured) {
            val selected = cachedVoices.firstOrNull { it.name == preferredVoice } ?: cachedVoices.firstOrNull()
            publish(OutputStatus.READY, if (selected?.isNetworkConnectionRequired == true) "Türkçe ses hazır; bu ses internet kullanır." else "Türkçe sesli yanıt hazır.")
        }
    }

    private fun publish(status: OutputStatus, message: String) {
        if (destroyed) return
        currentState = OutputState(status, message)
        listener.onState(currentState)
    }

    private fun dispatchCurrent(id: String?, thisGeneration: Int, action: (String) -> Unit) {
        main.post {
            if (!destroyed && generation == thisGeneration && id != null && activeId == id) action(id)
        }
    }

    private fun abandonFocus() {
        if (focusHeld) runCatching { audioManager?.abandonAudioFocusRequest(focusRequest) }
        focusHeld = false
    }

    private fun cancelUtteranceWatchdog() {
        utteranceWatchdog?.let(main::removeCallbacks)
        utteranceWatchdog = null
    }

    private fun armUtteranceWatchdog(id: String, delayMs: Long, message: String) {
        cancelUtteranceWatchdog()
        val thisGeneration = generation
        utteranceWatchdog = Runnable {
            if (!destroyed && generation == thisGeneration && activeId == id) {
                activeId = null
                configured = false
                utteranceWatchdog = null
                runCatching { session?.stop() }
                abandonFocus()
                publish(OutputStatus.ERROR, message)
                listener.onUtteranceError(id, message)
            }
        }.also { main.postDelayed(it, delayMs) }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    companion object {
        private val TURKISH = Locale.forLanguageTag("tr-TR")
        private const val INITIALIZATION_TIMEOUT_MS = 10_000L
        private const val UTTERANCE_START_TIMEOUT_MS = 15_000L
    }
}
