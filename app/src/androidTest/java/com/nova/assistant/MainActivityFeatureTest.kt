package com.nova.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.regex.Pattern

/**
 * Exercises real UI -> AssistantBrain -> persistence/output routing. Recognition/TTS fixtures
 * replace only platform engine boundaries; they do not claim a real microphone or audible voice.
 * Permission denial runs before the one runtime grant, on a fresh emulator installation.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MainActivityFeatureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var recognizer: FixtureRecognizerFactory
    private lateinit var tts: FixtureTtsFactory
    private var originalVolume = 0
    private var originallyMuted = false

    @Before fun isolateAppDataAndEngineBoundaries() {
        assertTrue(context.getSharedPreferences("nova_v2", Context.MODE_PRIVATE).edit().clear().commit())
        NovaDependencies.reset()
        recognizer = FixtureRecognizerFactory()
        tts = FixtureTtsFactory()
        NovaDependencies.recognizerFactory = recognizer
        NovaDependencies.ttsFactory = tts
        val audio = context.getSystemService(AudioManager::class.java)
        originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        originallyMuted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,
            (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2).coerceAtLeast(1), 0)
        assertTrue("Voice routing tests require nonzero media volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0)
        assertFalse("Voice routing tests require unmuted media", audio.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    @After fun releaseEngineOverrides() {
        NovaDependencies.reset()
        context.getSystemService(AudioManager::class.java).apply {
            setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (originallyMuted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        }
    }

    @Test fun aTypedConversationAndNotesSurviveActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            send(scenario, "hesapla 12 * 7")
            awaitMessage(scenario, "Sonuç: 84")
            send(scenario, "not al")
            awaitMessage(scenario, "Ne not almamı istersin? Sonraki mesajını not olarak kaydedeceğim. Vazgeçmek için iptal yazabilirsin.")
            send(scenario, "Yarın kitap al")
            awaitMessage(scenario, "Notunu kaydettim: Yarın kitap al")

            scenario.recreate()
            awaitMessage(scenario, "Sonuç: 84")
            awaitMessage(scenario, "Notunu kaydettim: Yarın kitap al")
            scenario.onActivity { activity ->
                assertEquals("Restoring history must not duplicate user turns", 1,
                    messageTexts(activity).count { it == "hesapla 12 * 7" })
            }
            send(scenario, "notlarımı göster")
            awaitMessage(scenario, "Notların:\n1. Yarın kitap al")
        }
    }

    @Test fun bVoiceControlsPersistAndDisabledOutputDoesNotSpeak() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitConfiguredFixture(scenario)
            openSettings(scenario)
            for (id in listOf("nova_voice_rate", "nova_voice_pitch", "nova_voice_test",
                "nova_voice_setup", "nova_recognition_setup", "nova_name")) {
                assertNotNull("Settings should expose $id", setting(id))
            }
            changeSlider("nova_voice_rate")
            await(scenario, "The rate control must update the saved value and actual engine configuration") {
                val saved = context.getSharedPreferences("nova_v2", Context.MODE_PRIVATE).getFloat("voice_rate", 1f)
                saved > 1f && tts.latest?.appliedRate == saved
            }
            changeSlider("nova_voice_pitch")
            await(scenario, "The pitch control must update the saved value and actual engine configuration") {
                val saved = context.getSharedPreferences("nova_v2", Context.MODE_PRIVATE).getFloat("voice_pitch", 1f)
                saved > 1f && tts.latest?.appliedPitch == saved
            }
            val enabled = setting("nova_voice_enabled")
            assertTrue(enabled.isChecked)
            enabled.click()
            closeSettings()
            await(scenario, "The voice preference should be persisted") {
                !context.getSharedPreferences("nova_v2", Context.MODE_PRIVATE).getBoolean("voice_enabled", true)
            }
            val requestsBefore = allSpeechRequests().size
            send(scenario, "hesapla 12 * 7")
            awaitMessage(scenario, "Sonuç: 84")
            instrumentation.waitForIdleSync()
            assertEquals("Disabling voice must suppress engine requests", requestsBefore, allSpeechRequests().size)

            scenario.recreate()
            openSettings(scenario)
            val restored = setting("nova_voice_enabled")
            assertFalse("Voice setting must survive recreation", restored.isChecked)
            closeSettings()
        }
    }

    @Test fun cRealMicrophoneDenialStillAllowsTypedConversation() {
        assertEquals("Run the suite on a fresh install before microphone is granted",
            PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitConfiguredFixture(scenario)
            scenario.onActivity { activity -> activity.findViewById<View>(R.id.nova_microphone).performClick() }
            val deny = device.wait(Until.findObject(By.res(Pattern.compile(".*:id/permission_deny_button"))), 60_000)
            assertNotNull("The real Android microphone permission dialog should appear", deny)
            deny!!.click()
            await(scenario, "Permission denial must explain that typed chat remains available") { activity ->
                texts(activity.window.decorView).any { it.contains("Mikrofon izni verilmedi") }
            }
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            assertTrue("Denying permission must not create a recognizer session", recognizer.sessions.isEmpty())
            send(scenario, "hesapla 12 * 7")
            awaitMessage(scenario, "Sonuç: 84")
        }
    }

    @Test fun dRecognizedTextUsesTheRealBrainAndRequestsTheSameSpokenReply() {
        tts = FixtureTtsFactory(automaticCallbacks = false)
        NovaDependencies.ttsFactory = tts
        shell("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await(scenario, "The Turkish fixture engine should be configured") {
                tts.latest?.configuredLanguage?.language == "tr"
            }
            // Complete an initial welcome, if queued, before holding callbacks for the answer.
            scenario.onActivity {
                val engine = tts.latest!!
                engine.requests.lastOrNull()?.let { welcome ->
                    engine.started(welcome.id)
                    engine.completed(welcome.id)
                }
            }
            awaitConfiguredFixture(scenario)
            scenario.onActivity { activity -> activity.findViewById<View>(R.id.nova_microphone).performClick() }
            await(scenario, "The microphone button must start the recognizer engine") { recognizer.latest?.started == true }
            assertEquals("tr-TR", recognizer.latest!!.request!!.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
            scenario.onActivity { recognizer.latest!!.deliver("  hesapla 12 * 7  ") }
            awaitMessage(scenario, "hesapla 12 * 7")
            awaitMessage(scenario, "Sonuç: 84")
            await(scenario, "The generated answer must reach TTS.speak") {
                allSpeechRequests().any { it.text == "Sonuç: 84" }
            }
            val answer = allSpeechRequests().last { it.text == "Sonuç: 84" }
            assertEquals("Speech requests should use full engine gain", 1f,
                answer.params.getFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME), 0f)
            assertEquals(AudioManager.STREAM_MUSIC, answer.params.getInt(TextToSpeech.Engine.KEY_PARAM_STREAM))
            scenario.onActivity { activity ->
                assertFalse("An accepted queue request must not claim audible speech started",
                    activity.findViewById<TextView>(R.id.nova_voice_status).text.contains("konuşuyor", true))
                tts.latest!!.started(answer.id)
            }
            await(scenario, "Speaking state should follow the native start callback") { activity ->
                activity.findViewById<TextView>(R.id.nova_voice_status).text.contains("konuşuyor", true)
            }
            scenario.onActivity { tts.latest!!.completed(answer.id) }
            await(scenario, "Completion should release the speaking state") { activity ->
                activity.findViewById<TextView>(R.id.nova_voice_status).text.contains("hazır", true)
            }
        }
    }

    @Test fun eUnavailableTurkishVoiceStillReturnsTextAndExplainsTheProblem() {
        tts = FixtureTtsFactory(languageStatus = TextToSpeech.LANG_MISSING_DATA)
        NovaDependencies.ttsFactory = tts
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await(scenario, "Missing Turkish voice data must be reported honestly") { activity ->
                activity.findViewById<TextView>(R.id.nova_voice_status).text.contains("Türkçe ses paketi eksik")
            }
            send(scenario, "hesapla 12 * 7")
            awaitMessage(scenario, "Sonuç: 84")
            assertTrue("Unavailable voices must not receive pretend successful speech requests", allSpeechRequests().isEmpty())
        }
    }

    @Test fun zNativeProviderUiAndScreenshots() {
        // Visual evidence uses real installed providers, never a fixture claiming a ready voice.
        NovaDependencies.reset()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitNativeVoiceInitialization(scenario)
            capture("home-native-providers.png")
            send(scenario, "hesapla 12 * 7")
            awaitMessage(scenario, "Sonuç: 84")
            awaitNativeVoiceInitialization(scenario)
            capture("conversation-native-providers.png")
            openSettings(scenario)
            setting("nova_voice_test")
            capture("settings-native-providers.png")
            closeSettings()
        }
    }

    private fun send(scenario: ActivityScenario<MainActivity>, input: String) {
        scenario.onActivity { activity ->
            activity.findViewById<EditText>(R.id.nova_input).setText(input)
            activity.findViewById<View>(R.id.nova_send).performClick()
        }
    }

    private fun openSettings(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity -> activity.findViewById<View>(R.id.nova_settings).performClick() }
        setting("nova_voice_enabled")
    }

    private fun closeSettings() {
        setting("nova_close_settings").click()
        assertTrue(device.wait(Until.gone(By.res(context.packageName, "nova_close_settings")), 10_000))
    }

    private fun awaitConfiguredFixture(scenario: ActivityScenario<MainActivity>) = await(scenario, "Turkish fixture should initialize") { activity ->
        tts.latest?.configuredLanguage?.language == "tr" &&
            activity.findViewById<TextView>(R.id.nova_voice_status).text.contains("hazır", true)
    }

    private fun awaitNativeVoiceInitialization(scenario: ActivityScenario<MainActivity>) =
        await(scenario, "The native voice engine should finish initialization", timeoutMs = 30_000) { activity ->
            val status = activity.findViewById<TextView>(R.id.nova_voice_status).text.toString()
            status.isNotBlank() && !status.contains("hazırlanıyor")
        }

    private fun awaitMessage(scenario: ActivityScenario<MainActivity>, expected: String) =
        await(scenario, "Conversation should contain: $expected") { activity -> expected in messageTexts(activity) }

    private fun messageTexts(activity: MainActivity) = texts(activity.findViewById(R.id.nova_messages))

    private fun texts(view: View): List<String> = buildList {
        if (view is TextView) add(view.text.toString())
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(texts(view.getChildAt(index)))
    }

    private fun allSpeechRequests() = tts.sessions.flatMap { it.requests.toList() }

    private fun changeSlider(id: String) {
        val bounds = setting(id).visibleBounds
        assertTrue("The slider must have a visible interactive area", bounds.width() > 0 && bounds.height() > 0)
        assertTrue(device.click(bounds.left + (bounds.width() * .8f).toInt(), bounds.centerY()))
    }

    private fun setting(id: String): UiObject2 {
        val selector = By.res(context.packageName, id)
        device.findObject(selector)?.let { return it }
        val scroll = UiScrollable(UiSelector().scrollable(true))
        if (scroll.exists()) scroll.scrollIntoView(UiSelector().resourceId("${context.packageName}:id/$id"))
        return device.wait(Until.findObject(selector), 10_000)
            ?: throw AssertionError("Settings control should be reachable: $id")
    }

    private fun await(
        scenario: ActivityScenario<MainActivity>,
        message: String,
        timeoutMs: Long = 30_000,
        condition: (MainActivity) -> Boolean
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            var done = false
            scenario.onActivity { activity -> done = condition(activity) }
            if (done) return
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError(message)
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                val result = reader.readText()
                assertFalse("Shell command failed: $result", result.contains("Exception") || result.contains("Error:"))
                result
            }
        }

    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("A screenshot should be available", bitmap)
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val file = File(directory, name)
        file.outputStream().use { output ->
            assertTrue(bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap!!.recycle()
        // AGP may uninstall the target after connected tests; retain CI evidence outside its data.
        require(file.absolutePath.matches(Regex("[A-Za-z0-9_./-]+")))
        require(name.matches(Regex("[A-Za-z0-9_.-]+")))
        val copied = shell("mkdir -p /data/local/tmp/nova-screenshots && " +
            "cp ${file.absolutePath} /data/local/tmp/nova-screenshots/$name && " +
            "chmod 644 /data/local/tmp/nova-screenshots/$name && echo NOVA_SCREENSHOT_COPIED")
        assertTrue("CI screenshots should survive app uninstall: $copied", copied.contains("NOVA_SCREENSHOT_COPIED"))
    }
}
