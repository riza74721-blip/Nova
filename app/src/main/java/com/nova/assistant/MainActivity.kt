package com.nova.assistant

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.widget.*
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var voiceStatus: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val speechRequest = 44
    private val micRequest = 45
    private val prefs by lazy { getSharedPreferences("nova", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 70, 40, 30)
            setBackgroundColor(Color.rgb(16, 20, 33))
        }
        fun label(text: String, size: Float): TextView = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(Color.WHITE)
            setPadding(0, 10, 0, 18)
        }
        root.addView(label("NOVA", 34f))
        root.addView(label("Türkçe sesli asistan • İlk sürüm", 17f))
        status = label("Mikrofona dokunarak konuş.", 16f)
        root.addView(status)
        transcript = label("", 18f)
        root.addView(transcript)
        val listen = Button(this).apply { text = "🎙 Nova ile konuş"; textSize = 20f; setOnClickListener { startListening() } }
        root.addView(listen, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(label("Ses hızı", 16f))
        val speed = SeekBar(this).apply {
            max = 15; progress = prefs.getInt("speed", 5)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) { prefs.edit().putInt("speed", progress).apply(); tts?.setSpeechRate(0.5f + progress / 10f) } }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        root.addView(speed)
        root.addView(label("Ses motorunun sunduğu sesler telefon ayarlarından seçilebilir.", 13f))
        voiceStatus = label("Ses motoru hazırlanıyor...", 13f)
        root.addView(voiceStatus)
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        tts = TextToSpeech(this, this)
    }

    override fun onInit(result: Int) {
        if (isFinishing || isDestroyed) return
        if (result == TextToSpeech.SUCCESS) {
            val language = tts?.setLanguage(Locale.forLanguageTag("tr-TR"))
            ttsReady = language != null && language >= TextToSpeech.LANG_AVAILABLE
            tts?.setSpeechRate(0.5f + prefs.getInt("speed", 5) / 10f)
        }
        voiceStatus.text = if (ttsReady) "Türkçe sesli yanıt hazır."
            else "Türkçe ses bulunamadı. Yanıtlar ekranda gösterilecek."
    }
    private fun startListening() {
        tts?.stop()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), micRequest); return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nova dinliyor...")
        }
        try { startActivityForResult(intent, speechRequest); status.text = "Dinliyorum..." }
        catch (_: ActivityNotFoundException) { status.text = "Ses tanıma hizmeti bulunamadı. Google ses hizmetlerini kontrol et." }
        catch (_: SecurityException) { status.text = "Ses tanıma hizmetine erişilemiyor. Mikrofon iznini kontrol et." }
    }
    @Deprecated("Activity result API for minimal Android project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != speechRequest) return
        if (resultCode != RESULT_OK) { status.text = "Dinleme iptal edildi."; return }
        val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty().trim()
        if (spoken.isBlank()) {
            status.text = NovaCommands.reply(spoken)
            return
        }
        transcript.text = "Sen: $spoken"
        val answer = NovaCommands.reply(spoken)
        status.text = "Nova: $answer"
        if (ttsReady) tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "nova_reply")
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != micRequest) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
        else status.text = "Konuşmak için mikrofon izni gerekli."
    }
    override fun onDestroy() { tts?.stop(); tts?.shutdown(); super.onDestroy() }
}
