package com.nova.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.nova.assistant.brain.Action
import com.nova.assistant.brain.AssistantBrain
import com.nova.assistant.brain.ChatRole
import com.nova.assistant.brain.ChatStore
import com.nova.assistant.brain.SettingsScreen
import com.nova.assistant.ui.NovaScreen
import com.nova.assistant.voice.AndroidRecognizerFactory
import com.nova.assistant.voice.AndroidTtsFactory
import com.nova.assistant.voice.InputState
import com.nova.assistant.voice.InputStatus
import com.nova.assistant.voice.OutputState
import com.nova.assistant.voice.OutputStatus
import com.nova.assistant.voice.VoiceInput
import com.nova.assistant.voice.VoiceOutput

class MainActivity : Activity() {
    private lateinit var screen: NovaScreen
    private lateinit var settings: NovaSettings
    private lateinit var chat: ChatStore
    private lateinit var brain: AssistantBrain
    private lateinit var voiceInput: VoiceInput
    private lateinit var voiceOutput: VoiceOutput
    private var sheet: SettingsSheet? = null
    private var currentUiState = NovaScreen.State.READY
    private var microphoneRequested = false
    private var missingProviderDialog: AlertDialog? = null
    private val main = Handler(Looper.getMainLooper())
    private var pendingHandoff: Pair<String, Action>? = null
    private var handoffTimeout: Runnable? = null
    private var reinitializeAfterStop = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        settings = NovaSettings(this)
        chat = ChatStore(this)
        brain = AssistantBrain(this)
        screen = NovaScreen(this, object : NovaScreen.Callbacks {
            override fun onListen() = beginListening()
            override fun onStop() = stopInteraction()
            override fun onSend(text: String) = submit(text)
            override fun onQuickAction(text: String) = submit(text)
            override fun onSettings() = showSettings()
            override fun onClearHistory() = confirmClearHistory()
        })
        screen.setAssistantName(settings.assistantName)
        setContentView(screen)

        voiceInput = VoiceInput(this, object : VoiceInput.Listener {
            override fun onState(state: InputState) = renderInput(state)
            override fun onPartial(text: String) = screen.showPartial(text)
            override fun onResult(text: String) {
                microphoneRequested = false
                screen.showPartial("")
                submit(text)
            }
        }, NovaDependencies.recognizerFactory ?: AndroidRecognizerFactory())

        voiceOutput = VoiceOutput(this, object : VoiceOutput.Listener {
            override fun onState(state: OutputState) {
                val needsSetup = state.status in setOf(
                    OutputStatus.MISSING_ENGINE, OutputStatus.MISSING_LANGUAGE, OutputStatus.ERROR
                )
                screen.setVoiceStatus(state.message, needsSetup)
                sheet?.refreshStatus()
                when (state.status) {
                    OutputStatus.SPEAKING -> render(NovaScreen.State.SPEAKING, "Sesli yanıt veriyorum…")
                    OutputStatus.READY, OutputStatus.DISABLED -> {
                        if (currentUiState == NovaScreen.State.SPEAKING) ready()
                        if (state.status == OutputStatus.DISABLED) finishHandoff()
                    }
                    OutputStatus.ERROR, OutputStatus.MISSING_ENGINE, OutputStatus.MISSING_LANGUAGE -> finishHandoff()
                    else -> Unit
                }
            }
            override fun onUtteranceDone(id: String) {
                finishHandoff(id)
                if (!listening()) ready()
            }
            override fun onUtteranceError(id: String, message: String) {
                finishHandoff(id)
                if (!listening()) render(NovaScreen.State.ERROR, message)
            }
            override fun onVolumeMuted() {
                screen.setVoiceStatus("Medya sesi kapalı. Ses ayarlarından sesi açabilirsin.", true)
            }
        }, NovaDependencies.ttsFactory ?: AndroidTtsFactory())

        voiceOutput.setRate(settings.rate)
        voiceOutput.setPitch(settings.pitch)
        voiceOutput.setVoice(settings.voiceId.ifBlank { null })
        voiceOutput.enabled = settings.voiceEnabled
        voiceOutput.initialize()
        voiceInput.initialize()

        if (chat.load().isEmpty()) {
            renderMessages()
            ready()
            if (savedInstanceState == null && settings.voiceEnabled) {
                voiceOutput.speak("Merhaba, ben " + settings.assistantName +
                    ". Sesle veya yazarak konuşabiliriz. Not alabilir, hesap yapabilir ve arama yapabilirim. Ne yapalım?",
                    "nova-welcome")
            }
        } else {
            renderMessages()
            ready()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::voiceInput.isInitialized) voiceInput.initialize()
        if (::voiceOutput.isInitialized && (reinitializeAfterStop || voiceOutput.currentState.status in setOf(
                OutputStatus.MISSING_ENGINE, OutputStatus.MISSING_LANGUAGE, OutputStatus.ERROR
            ))) {
            voiceOutput.initialize()
        }
        reinitializeAfterStop = false
        sheet?.refreshStatus()
    }

    private fun beginListening() {
        cancelHandoff()
        screen.hideKeyboard()
        voiceOutput.stop()
        screen.showPartial("")
        microphoneRequested = true
        voiceInput.initialize()
        if (!voiceInput.available) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            render(NovaScreen.State.READY, "Konuşmak için mikrofon izni gerekli.")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_PERMISSION)
            return
        }
        voiceInput.start()
    }

    private fun renderInput(state: InputState) {
        when (state.status) {
            InputStatus.STARTING, InputStatus.LISTENING ->
                render(NovaScreen.State.LISTENING, state.message)
            InputStatus.PROCESSING -> render(NovaScreen.State.PROCESSING, state.message)
            InputStatus.UNAVAILABLE -> {
                if (currentUiState != NovaScreen.State.SPEAKING) {
                    render(NovaScreen.State.READY, state.message)
                }
                if (microphoneRequested) {
                    microphoneRequested = false
                    explainMissingRecognizer()
                }
            }
            InputStatus.PERMISSION_REQUIRED -> {
                microphoneRequested = false
                render(NovaScreen.State.ERROR, state.message)
            }
            InputStatus.ERROR -> {
                microphoneRequested = false
                screen.showPartial("")
                render(NovaScreen.State.ERROR, state.message)
            }
            InputStatus.IDLE, InputStatus.READY -> {
                if (currentUiState != NovaScreen.State.SPEAKING) render(NovaScreen.State.READY, state.message)
            }
        }
    }

    private fun submit(value: String) {
        val text = value.trim()
        if (text.isEmpty()) return
        if (text.length > 4_000) {
            render(NovaScreen.State.ERROR, "Mesajını 4.000 karakterden kısa tutabilir misin?")
            return
        }
        microphoneRequested = false
        cancelHandoff()
        voiceInput.cancel()
        voiceOutput.stop()
        screen.showPartial("")
        screen.setInputText("")
        screen.hideKeyboard()
        chat.append(ChatRole.USER, text)
        renderMessages()
        render(NovaScreen.State.PROCESSING, "Yanıtını hazırlıyorum…")
        val reply = brain.respond(text)
        assistant(reply.text, action = reply.action)
    }

    private fun assistant(text: String, speak: Boolean = true, action: Action? = null) {
        val message = chat.append(ChatRole.ASSISTANT, text)
        renderMessages()
        ready()
        val waitForAnnouncement = speak && settings.voiceEnabled && voiceOutput.isReady &&
            action != null && action !is Action.ConfirmDeleteNotes
        if (waitForAnnouncement) {
            pendingHandoff = message.id to action
            handoffTimeout = Runnable { finishHandoff(message.id) }.also { main.postDelayed(it, 4_000) }
        }
        if (speak && settings.voiceEnabled) voiceOutput.speak(text, message.id)
        if (!waitForAnnouncement) execute(action)
    }

    private fun finishHandoff(id: String? = null) {
        val pending = pendingHandoff ?: return
        if (id != null && id != pending.first) return
        cancelHandoff()
        execute(pending.second)
    }

    private fun cancelHandoff() {
        handoffTimeout?.let(main::removeCallbacks)
        handoffTimeout = null
        pendingHandoff = null
    }

    private fun execute(action: Action?) {
        when (action) {
            null -> Unit
            is Action.OpenUrl -> {
                if (!open(listOf(Intent(Intent.ACTION_VIEW, Uri.parse(action.url))))) {
                    assistant("Bu bağlantıyı açacak bir tarayıcı bulunamadı. Bir tarayıcı kurup tekrar deneyebilirsin.")
                }
            }
            is Action.OpenApp -> {
                val launch = packageManager.getLaunchIntentForPackage(action.packageName)
                if (launch != null && open(listOf(launch))) return
                assistant(action.displayName + " bu telefonda yüklü değil. İstersen mağaza sayfasını açabilirim.")
                AlertDialog.Builder(this)
                    .setTitle(action.displayName + " yüklü değil")
                    .setMessage("Uygulamanın mağaza sayfasını açmak ister misin?")
                    .setPositiveButton("Mağazada aç") { _, _ ->
                        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + action.packageName))
                        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + action.packageName))
                        if (!open(listOf(market, web))) assistant("Mağaza veya tarayıcı açılamadı.")
                    }
                    .setNegativeButton("Vazgeç", null).show()
            }
            is Action.OpenSettings -> {
                val setting = when (action.screen) {
                    SettingsScreen.GENERAL -> Settings.ACTION_SETTINGS
                    SettingsScreen.WIFI -> Settings.ACTION_WIFI_SETTINGS
                    SettingsScreen.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
                }
                if (!open(listOf(Intent(setting), Intent(Settings.ACTION_SETTINGS)))) {
                    assistant("Telefon ayarları açılamadı.")
                }
            }
            is Action.ConfirmDeleteNotes -> AlertDialog.Builder(this)
                .setTitle("Notlar silinsin mi?")
                .setMessage(action.count.toString() + " kayıtlı not kalıcı olarak silinecek.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Notları sil") { _, _ -> assistant(brain.deleteAllNotes().text) }.show()
        }
    }

    private fun open(intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // Try the next user-visible setup route.
            } catch (_: SecurityException) {
                // An OEM may restrict its settings activity.
            }
        }
        return false
    }

    private fun showSettings() {
        cancelHandoff()
        if (sheet?.isShowing == true) return
        sheet = SettingsSheet(this, settings, voiceOutput, voiceInput) {
            screen.setAssistantName(settings.assistantName)
            voiceOutput.enabled = settings.voiceEnabled
            voiceOutput.setRate(settings.rate)
            voiceOutput.setPitch(settings.pitch)
            voiceOutput.setVoice(settings.voiceId.ifBlank { null })
        }.also { it.show() }
    }

    private fun explainMissingRecognizer() {
        if (missingProviderDialog?.isShowing == true) return
        missingProviderDialog = AlertDialog.Builder(this)
            .setTitle("Konuşma hizmeti kurulmalı")
            .setMessage("Bu telefonda kullanılabilir konuşma tanıma hizmeti bulunamadı. Ses ayarlarından bir hizmet seçebilirsin. Bu sırada aşağıdaki mesaj kutusundan Nova ile konuşabilirsin.")
            .setPositiveButton("Ses ayarları") { _, _ -> showSettings() }
            .setNegativeButton("Yazarak devam et", null)
            .show()
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("Sohbet temizlensin mi?")
            .setMessage("Bu cihazdaki konuşma geçmişi silinir. Kaydettiğin notlar korunur.")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Sohbeti temizle") { _, _ ->
                stopInteraction()
                chat.clear()
                renderMessages()
                ready()
            }.show()
    }

    private fun renderMessages() {
        screen.renderMessages(chat.load().map {
            NovaScreen.Message(it.id.hashCode().toLong(), it.role == ChatRole.USER, it.text, it.timestamp)
        })
    }

    private fun render(state: NovaScreen.State, detail: String) {
        if (isDestroyed) return
        currentUiState = state
        screen.renderState(state, detail)
    }

    private fun ready() {
        val hint = if (::voiceInput.isInitialized && voiceInput.available) {
            "Hazırım. Konuşmak için mikrofona dokun veya mesajını yaz."
        } else {
            "Yazarak konuşabilirsin. Mikrofon için ses hizmetini ayarlardan kur."
        }
        render(NovaScreen.State.READY, hint)
    }

    private fun listening() = ::voiceInput.isInitialized && voiceInput.currentState.status in setOf(
        InputStatus.STARTING, InputStatus.LISTENING, InputStatus.PROCESSING
    )

    private fun stopInteraction() {
        cancelHandoff()
        microphoneRequested = false
        voiceInput.cancel()
        voiceOutput.stop()
        screen.showPartial("")
        ready()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != MICROPHONE_PERMISSION) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            microphoneRequested = true
            voiceInput.start()
        } else {
            microphoneRequested = false
            assistant(MICROPHONE_DENIED, speak = false)
            render(NovaScreen.State.ERROR, MICROPHONE_DENIED)
        }
    }

    override fun onStop() {
        reinitializeAfterStop = true
        cancelHandoff()
        if (::voiceInput.isInitialized) {
            microphoneRequested = false
            voiceInput.cancel()
        }
        if (::voiceOutput.isInitialized) voiceOutput.stop()
        super.onStop()
    }

    override fun onDestroy() {
        cancelHandoff()
        sheet?.dismiss()
        missingProviderDialog?.dismiss()
        if (::voiceInput.isInitialized) voiceInput.destroy()
        if (::voiceOutput.isInitialized) voiceOutput.destroy()
        super.onDestroy()
    }

    companion object {
        private const val MICROPHONE_PERMISSION = 45
        const val MICROPHONE_DENIED = "Mikrofon izni verilmedi. Yazarak konuşmaya devam edebilirsin."
    }
}
