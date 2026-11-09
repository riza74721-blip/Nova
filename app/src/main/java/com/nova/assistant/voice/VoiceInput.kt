package com.nova.assistant.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

enum class InputStatus {
    IDLE, READY, STARTING, LISTENING, PROCESSING, PERMISSION_REQUIRED, UNAVAILABLE, ERROR
}

data class InputState(val status: InputStatus, val message: String, val errorCode: Int? = null)

/** A small seam around the platform service; production always uses [AndroidRecognizerFactory]. */
interface RecognitionSession {
    fun setListener(listener: RecognitionListener)
    fun startListening(intent: Intent)
    fun stopListening()
    fun cancel()
    fun destroy()
}

interface RecognizerFactory {
    fun isRecognitionAvailable(context: Context): Boolean
    fun isOnDeviceRecognitionAvailable(context: Context): Boolean
    fun create(context: Context, onDevice: Boolean): RecognitionSession
}

class AndroidRecognizerFactory : RecognizerFactory {
    override fun isRecognitionAvailable(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)

    override fun isOnDeviceRecognitionAvailable(context: Context) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun create(context: Context, onDevice: Boolean): RecognitionSession {
        val native = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        return object : RecognitionSession {
            override fun setListener(listener: RecognitionListener) = native.setRecognitionListener(listener)
            override fun startListening(intent: Intent) = native.startListening(intent)
            override fun stopListening() = native.stopListening()
            override fun cancel() = native.cancel()
            override fun destroy() = native.destroy()
        }
    }
}

/** Button-operated recognition inside Nova, without an external recognition activity. */
class VoiceInput(
    context: Context,
    private val listener: Listener,
    private val factory: RecognizerFactory = AndroidRecognizerFactory()
) {
    interface Listener {
        fun onState(state: InputState)
        fun onPartial(text: String) {}
        fun onResult(text: String)
        fun onLevel(level: Float) {}
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var session: RecognitionSession? = null
    private var destroyed = false
    private var active = false
    private var generation = 0
    private var systemAvailable = false
    private var deviceAvailable = false
    private var fallbackAttempted = false
    private var watchdog: Runnable? = null

    var currentState = InputState(InputStatus.IDLE, "Mikrofona dokunarak konuşabilirsin.")
        private set
    var available: Boolean = false
        private set
    var usesOnDeviceRecognition: Boolean = false
        private set

    fun initialize() = onMain {
        if (destroyed) return@onMain
        updateAvailability()
        if (!active) {
            if (available) transition(InputStatus.READY, "Mikrofona dokunarak konuşabilirsin.")
            else transition(InputStatus.UNAVAILABLE, MISSING_PROVIDER_MESSAGE)
        }
    }

    /** Permission must be granted by the activity; this method never opens a permission dialog. */
    fun start() = onMain {
        if (destroyed) return@onMain
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            releaseSession()
            transition(InputStatus.PERMISSION_REQUIRED, "Konuşmak için mikrofon izni gerekli.")
            return@onMain
        }
        updateAvailability()
        if (!available) {
            releaseSession()
            transition(InputStatus.UNAVAILABLE, MISSING_PROVIDER_MESSAGE)
            return@onMain
        }
        fallbackAttempted = false
        beginSession(preferOnDevice = deviceAvailable)
    }

    /** Finish the current utterance and wait for its final recognition result. */
    fun stop() = onMain {
        if (destroyed || !active) return@onMain
        try {
            val token = generation
            session?.stopListening()
            if (active && token == generation) {
                transition(InputStatus.PROCESSING, "Söylediklerini çözümlüyorum…")
                armWatchdog(RESULT_TIMEOUT_MS, "Konuşma hizmetinden sonuç gelmedi. Tekrar deneyebilir veya yazabilirsin.")
            }
        } catch (_: RuntimeException) {
            finishWithError(InputStatus.ERROR, "Dinleme sonlandırılamadı. Tekrar deneyebilirsin.")
        }
    }

    /** Cancel deliberately, without turning the recognizer's cancellation callback into an error. */
    fun cancel() = onMain {
        if (destroyed) return@onMain
        releaseSession()
        listener.onLevel(0f)
        transition(if (available) InputStatus.READY else InputStatus.UNAVAILABLE,
            if (available) "Dinleme durduruldu." else MISSING_PROVIDER_MESSAGE)
    }

    fun destroy() = onMain {
        if (destroyed) return@onMain
        releaseSession()
        destroyed = true
    }

    /** User-facing setup actions. The activity decides which resolved intent to open. */
    fun setupIntents(): List<Intent> {
        val voiceSettings = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        val systemSettings = Intent(Settings.ACTION_SETTINGS)
        return if (currentState.status == InputStatus.UNAVAILABLE) {
            listOf(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.googlequicksearchbox")),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.googlequicksearchbox")),
                voiceSettings,
                systemSettings
            )
        } else listOf(voiceSettings, systemSettings)
    }

    private fun updateAvailability() {
        systemAvailable = try { factory.isRecognitionAvailable(appContext) } catch (_: RuntimeException) { false }
        deviceAvailable = try { factory.isOnDeviceRecognitionAvailable(appContext) } catch (_: RuntimeException) { false }
        available = systemAvailable || deviceAvailable
    }

    private fun beginSession(preferOnDevice: Boolean) {
        releaseSession()
        if (destroyed) return
        active = true
        usesOnDeviceRecognition = preferOnDevice
        val token = generation
        transition(InputStatus.STARTING, "Mikrofon açılıyor…")
        try {
            val created = factory.create(appContext, preferOnDevice)
            session = created
            created.setListener(events(token))
            val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOnDevice)
            }
            armWatchdog(START_TIMEOUT_MS, "Mikrofon açılamadı. Ses hizmetini kontrol et veya yazarak devam et.")
            created.startListening(request)
        } catch (_: SecurityException) {
            finishWithError(InputStatus.PERMISSION_REQUIRED, "Mikrofon izni verilmedi. Ayarlardan mikrofon iznini açabilirsin.")
        } catch (_: RuntimeException) {
            if (preferOnDevice && systemAvailable && !fallbackAttempted) {
                fallbackAttempted = true
                beginSession(preferOnDevice = false)
            } else {
                finishWithError(InputStatus.UNAVAILABLE, MISSING_PROVIDER_MESSAGE)
            }
        }
    }

    private fun events(token: Int) = object : RecognitionListener {
        private fun valid() = !destroyed && active && token == generation

        override fun onReadyForSpeech(params: Bundle?) {
            if (!valid()) return
            transition(InputStatus.LISTENING, "Dinliyorum. Şimdi konuşabilirsin.")
            armWatchdog(LISTEN_TIMEOUT_MS, "Dinleme süresi doldu. Yeniden mikrofona dokunabilirsin.")
        }

        override fun onBeginningOfSpeech() {
            if (!valid()) return
            transition(InputStatus.LISTENING, "Seni dinliyorum…")
            armWatchdog(LISTEN_TIMEOUT_MS, "Dinleme süresi doldu. Yeniden mikrofona dokunabilirsin.")
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (valid()) {
                val level = if (rmsdB.isFinite()) ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) else 0f
                listener.onLevel(level)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!valid()) return
            if (currentState.status == InputStatus.STARTING) {
                transition(InputStatus.LISTENING, "Seni dinliyorum…")
                armWatchdog(LISTEN_TIMEOUT_MS, "Dinleme süresi doldu. Yeniden mikrofona dokunabilirsin.")
            }
            bestText(partialResults)?.let(listener::onPartial)
        }

        override fun onEndOfSpeech() {
            if (!valid()) return
            listener.onLevel(0f)
            transition(InputStatus.PROCESSING, "Söylediklerini çözümlüyorum…")
            armWatchdog(RESULT_TIMEOUT_MS, "Konuşma hizmetinden sonuç gelmedi. Tekrar deneyebilir veya yazabilirsin.")
        }

        override fun onResults(results: Bundle?) {
            if (!valid()) return
            val text = bestText(results)
            releaseSession()
            listener.onLevel(0f)
            if (text == null) {
                transition(InputStatus.READY, "Konuşma algılanamadı. Tekrar deneyebilirsin.")
            } else {
                transition(InputStatus.READY, "Seni duydum.")
                listener.onResult(text)
            }
        }

        override fun onError(error: Int) {
            if (!valid()) return
            // A device can advertise on-device recognition without an installed Turkish model.
            // Retry the normal system provider once, allowing its configured online service.
            if (usesOnDeviceRecognition && systemAvailable && !fallbackAttempted && error in FALLBACK_ERRORS) {
                fallbackAttempted = true
                beginSession(preferOnDevice = false)
                return
            }
            val state = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> InputState(InputStatus.PERMISSION_REQUIRED, "Konuşmak için mikrofon izni gerekli.", error)
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> InputState(InputStatus.READY, "Seni duyamadım. Mikrofona yaklaşıp tekrar deneyebilirsin.", error)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> InputState(InputStatus.ERROR, "Konuşma hizmeti internete ulaşamadı. Bağlantıyı kontrol et veya yazarak devam et.", error)
                SpeechRecognizer.ERROR_AUDIO -> InputState(InputStatus.ERROR, "Mikrofona erişilemedi. Mikrofonu kullanan diğer uygulamaları kapatıp tekrar dene.", error)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> InputState(InputStatus.ERROR, "Ses hizmeti meşgul. Biraz bekleyip yeniden mikrofona dokun.", error)
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> InputState(InputStatus.ERROR, "Seçili ses hizmeti Türkçe tanımayı desteklemiyor. Ses hizmetini değiştirebilir veya yazarak devam edebilirsin.", error)
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> InputState(InputStatus.ERROR, "Türkçe tanıma paketi indirilmemiş. Ses hizmetinin ayarlarından Türkçe paketini indir veya yazarak devam et.", error)
                SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> InputState(InputStatus.ERROR, "Konuşma hizmeti yanıt vermedi. Tekrar deneyebilir veya yazabilirsin.", error)
                else -> InputState(InputStatus.ERROR, "Dinleme kesildi. Tekrar mikrofona dokunabilirsin.", error)
            }
            finishWithError(state.status, state.message, state.errorCode)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun bestText(results: Bundle?): String? = results
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()

    private fun finishWithError(status: InputStatus, message: String, errorCode: Int? = null) {
        releaseSession()
        listener.onLevel(0f)
        transition(status, message, errorCode)
    }

    private fun armWatchdog(delay: Long, message: String) {
        watchdog?.let(handler::removeCallbacks)
        val token = generation
        watchdog = Runnable {
            if (!destroyed && active && token == generation) {
                finishWithError(InputStatus.ERROR, message)
            }
        }.also { handler.postDelayed(it, delay) }
    }

    private fun releaseSession() {
        active = false
        generation++
        watchdog?.let(handler::removeCallbacks)
        watchdog = null
        val old = session
        session = null
        try { old?.cancel() } catch (_: RuntimeException) {}
        try { old?.destroy() } catch (_: RuntimeException) {}
    }

    private fun transition(status: InputStatus, message: String, errorCode: Int? = null) {
        if (destroyed) return
        currentState = InputState(status, message, errorCode)
        listener.onState(currentState)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
    }

    companion object {
        const val MISSING_PROVIDER_MESSAGE = "Bu cihazda ses tanıma hizmeti bulunamadı. Ses hizmetini kurabilir veya mesaj yazarak devam edebilirsin."
        private const val START_TIMEOUT_MS = 15_000L
        private const val RESULT_TIMEOUT_MS = 20_000L
        private const val LISTEN_TIMEOUT_MS = 60_000L
        private val FALLBACK_ERRORS = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED
        )
    }
}
