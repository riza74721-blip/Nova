package com.nova.assistant

import android.app.*
import android.content.Intent
import android.os.*
import android.provider.Settings
import android.speech.*
import android.speech.tts.TextToSpeech
import java.text.SimpleDateFormat
import java.util.*

class NovaService : Service(), RecognitionListener {
 private val handler = Handler(Looper.getMainLooper())
 private var recognizer: SpeechRecognizer? = null
 private var tts: TextToSpeech? = null
 private var active = false
 private var listening = false
 private var retryCount = 0
 private val channelId = "nova_listening"
 override fun onCreate() {
  super.onCreate()
  getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channelId, "Nova mikrofon", NotificationManager.IMPORTANCE_LOW))
  tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) {
   tts?.language = Locale.forLanguageTag("tr-TR")
   val prefs = getSharedPreferences("nova_settings", MODE_PRIVATE)
   val voiceId = prefs.getString("voice", "")
   tts?.voices?.firstOrNull { it.name == voiceId }?.let { tts?.voice = it }
   tts?.setSpeechRate(prefs.getFloat("speed", 1f))
   tts?.setPitch(prefs.getFloat("pitch", 1f))
  } }
 }
 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
  if (active) return START_NOT_STICKY
  val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val notification = Notification.Builder(this, channelId).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Nova dinlemeyi deniyor").setContentText("Durdurmak için uygulamayı açın").setContentIntent(launch).setOngoing(true).build()
  startForeground(1, notification)
  active = true
  if (!SpeechRecognizer.isRecognitionAvailable(this)) { speak("Bu cihazda konuşma tanıma kullanılamıyor"); stopSelf(); return START_NOT_STICKY }
  recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
  scheduleListen(300)
  return START_NOT_STICKY
 }
 private fun scheduleListen(delay: Long) { handler.postDelayed({ if (active && !listening) listen() }, delay) }
 private fun listen() {
  try {
   val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
   }
   listening = true
   recognizer?.startListening(request)
  } catch (_: Exception) { listening = false; scheduleListen(3000) }
 }
 override fun onResults(results: Bundle?) {
  listening = false; retryCount = 0
  val speech = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase(Locale.forLanguageTag("tr-TR")) ?: ""
  val assistantName = getSharedPreferences("nova_settings", MODE_PRIVATE).getString("name", "Nova")!!.lowercase(Locale.forLanguageTag("tr-TR"))
  if (speech.contains(assistantName)) {
   val command = speech.substringAfter(assistantName).trim()
   when {
    command.contains("saat") -> speak("Saat " + SimpleDateFormat("HH:mm", Locale.forLanguageTag("tr-TR")).format(Date()))
    command.contains("ayar") -> {
     speak("Ayarları açıyorum")
     val settings = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
     // Android blocks starting activities from a background service on many devices.
     try { startActivity(settings) } catch (_: Exception) { speak("Ayarları uygulamadan açmanız gerekiyor") }
    }
    command.isBlank() -> speak("Dinliyorum. Komutunu asistanın adıyla birlikte söyle")
    else -> speak("Bu komut henüz eklenmedi")
   }
  }
  scheduleListen(1200)
 }
 private fun speak(message: String) { tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "nova_reply") }
 override fun onError(error: Int) {
  listening = false
  if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || error == SpeechRecognizer.ERROR_CLIENT) { stopSelf(); return }
  retryCount = (retryCount + 1).coerceAtMost(6)
  scheduleListen((1000L * retryCount).coerceAtMost(6000L))
 }
 override fun onReadyForSpeech(params: Bundle?) {}
 override fun onBeginningOfSpeech() {}
 override fun onRmsChanged(rmsdB: Float) {}
 override fun onBufferReceived(buffer: ByteArray?) {}
 override fun onEndOfSpeech() {}
 override fun onPartialResults(partialResults: Bundle?) {}
 override fun onEvent(eventType: Int, params: Bundle?) {}
 override fun onDestroy() { active = false; handler.removeCallbacksAndMessages(null); recognizer?.cancel(); recognizer?.destroy(); tts?.stop(); tts?.shutdown(); super.onDestroy() }
 override fun onBind(intent: Intent?) = null
}
