package com.nova.assistant

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.nova.assistant.voice.VoiceInput
import com.nova.assistant.voice.VoiceOutput
import java.util.Locale

/** Foreground, user-controlled voice setup; no background microphone or hidden provider install. */
@SuppressLint("SetTextI18n")
class SettingsSheet(
    private val activity: Activity,
    private val settings: NovaSettings,
    private val output: VoiceOutput,
    private val input: VoiceInput,
    private val onChanged: () -> Unit
) : Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar) {
    private val white = Color.rgb(236, 245, 251)
    private val muted = Color.rgb(151, 173, 195)
    private val mint = Color.rgb(105, 237, 204)
    private lateinit var name: EditText
    private lateinit var state: TextView
    private lateinit var enabled: Switch
    private lateinit var voices: Spinner
    private var voiceIds: List<String> = emptyList()

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(14, 28, 45))
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.rgb(40, 65, 82))
            }
        }
        root.addView(label("Ses ve kişiselleştirme", 23f, white))
        root.addView(label("Nova’yı kendine göre ayarla.", 14f, muted).apply {
            setPadding(0, dp(6), 0, dp(16))
        })
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            addView(body)
            clipToPadding = false
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        body.addView(label("ASİSTANIN ADI", 11f, muted))
        name = EditText(activity).apply {
            id = R.id.nova_name
            setText(settings.assistantName)
            hint = "Nova"
            setTextColor(white)
            setHintTextColor(muted)
            textSize = 17f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(InputFilter.LengthFilter(24))
            backgroundTintList = ColorStateList.valueOf(mint)
            setPadding(0, 0, 0, dp(8))
        }
        body.addView(name, row(52))
        enabled = Switch(activity).apply {
            id = R.id.nova_voice_enabled
            text = "Sesli yanıtlar"
            textSize = 16f
            setTextColor(white)
            isChecked = settings.voiceEnabled
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                settings.voiceEnabled = checked
                onChanged()
                refreshStatus()
            }
        }
        body.addView(enabled, row(58))
        state = label("", 13f, mint).apply { setPadding(0, 0, 0, dp(14)) }
        body.addView(state)

        val rateLabel = label("", 14f, white)
        fun showRate() { rateLabel.text = "Konuşma hızı · " + String.format(TURKISH, "%.1f×", settings.rate) }
        showRate()
        body.addView(rateLabel)
        body.addView(SeekBar(activity).apply {
            id = R.id.nova_voice_rate
            max = 15
            progress = ((settings.rate - .5f) * 10).toInt()
            progressTintList = ColorStateList.valueOf(mint)
            thumbTintList = ColorStateList.valueOf(mint)
            setOnSeekBarChangeListener(changes { value ->
                settings.rate = .5f + value / 10f
                output.setRate(settings.rate)
                showRate()
            })
        }, row(44))

        val pitchLabel = label("", 14f, white)
        fun showPitch() { pitchLabel.text = "Ses tonu · " + String.format(TURKISH, "%.1f×", settings.pitch) }
        showPitch()
        body.addView(pitchLabel)
        body.addView(SeekBar(activity).apply {
            id = R.id.nova_voice_pitch
            max = 8
            progress = ((settings.pitch - .7f) * 10).toInt()
            progressTintList = ColorStateList.valueOf(mint)
            thumbTintList = ColorStateList.valueOf(mint)
            setOnSeekBarChangeListener(changes { value ->
                settings.pitch = .7f + value / 10f
                output.setPitch(settings.pitch)
                showPitch()
            })
        }, row(44))

        body.addView(label("TÜRKÇE SES", 11f, muted).apply { setPadding(0, dp(8), 0, 0) })
        voices = Spinner(activity).apply {
            contentDescription = "Türkçe ses seçimi"
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = voiceIds.getOrNull(position) ?: return
                    if (selected != settings.voiceId) {
                        settings.voiceId = selected
                        output.setVoice(selected.ifBlank { null })
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        body.addView(voices, row(48))
        body.addView(button("Sesi dene", R.id.nova_voice_test) {
            settings.assistantName = name.text.toString()
            if (!enabled.isChecked) enabled.isChecked = true
            output.speak("Merhaba, ben " + settings.assistantName + ". Türkçe sesli yanıtı birlikte deneyelim.")
        }, row(52))
        body.addView(button("Türkçe ses paketi / ses motoru", R.id.nova_voice_setup) {
            launch(output.setupIntents())
        }, row(52))
        body.addView(button("Konuşma tanıma hizmeti", R.id.nova_recognition_setup) {
            input.initialize()
            launch(input.setupIntents())
        }, row(52))
        body.addView(button("Medya sesini ayarla") {
            activity.getSystemService(AudioManager::class.java)
                .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
        }, row(52))
        body.addView(button("Mikrofon izni ayarları") {
            launch(listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.packageName))))
        }, row(52))
        body.addView(label(
            "Konuşma, telefonundaki Android ses hizmetiyle algılanır. Bazı hizmetler internet kullanabilir. Türkçe ses paketi eksikse üstteki kurulum düğmelerini kullan. Yazılı sohbet, notlar ve hesaplama ses hizmeti olmadan da çalışır.",
            12f, muted
        ).apply { setPadding(0, dp(12), 0, dp(12)) })
        body.addView(label("Nova 0.2.0 · Yerel komutlar ve cihazdaki sohbet geçmişi", 11f, muted))
        root.addView(button("Kaydet ve kapat", R.id.nova_close_settings) { dismiss() }.apply {
            setTextColor(Color.rgb(5, 26, 28))
            backgroundTintList = ColorStateList.valueOf(mint)
        }, row(54).apply { topMargin = dp(12) })
        setContentView(root)
        root.requestFocus()
        setOnDismissListener {
            settings.assistantName = name.text.toString()
            onChanged()
        }
    }

    override fun show() {
        super.show()
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(.62f)
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val metrics = activity.resources.displayMetrics
            setLayout(minOf(metrics.widthPixels - dp(24), dp(560)), (metrics.heightPixels * .86f).toInt())
        }
        refreshStatus()
    }

    fun refreshStatus() {
        if (!::state.isInitialized) return
        state.text = output.currentState.message
        val options = output.availableVoices()
        voices.isEnabled = options.isNotEmpty()
        voices.alpha = if (options.isEmpty()) .55f else 1f
        val ids = listOf("") + options.map { it.id }
        if (ids != voiceIds) {
            voiceIds = ids
            val labels = listOf(if (options.isEmpty()) "Önce Türkçe ses paketi kur" else "Önerilen Türkçe ses") + options.mapIndexed { index, voice ->
                "Türkçe ses " + (index + 1) + if (voice.requiresNetwork) " · internet" else " · cihazda"
            }
            voices.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels)
            voices.setSelection(ids.indexOf(settings.voiceId).coerceAtLeast(0), false)
        }
    }

    private fun launch(intents: List<Intent>) {
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // OEM devices offer different setup activities.
            } catch (_: SecurityException) {
                // Try the public settings alternative.
            }
        }
        Toast.makeText(activity, "Bu ayar açılamadı. Telefonunun ses ayarlarından bir hizmet seçebilirsin.", Toast.LENGTH_LONG).show()
    }

    private fun label(value: String, size: Float, color: Int) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun button(value: String, stableId: Int = View.NO_ID, action: () -> Unit) = Button(activity).apply {
        id = stableId
        text = value
        isAllCaps = false
        textSize = 14f
        setTextColor(white)
        minHeight = dp(48)
        setOnClickListener { action() }
    }

    private fun changes(change: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) change(progress)
        }
        override fun onStartTrackingTouch(bar: SeekBar?) = Unit
        override fun onStopTrackingTouch(bar: SeekBar?) = Unit
    }

    private fun row(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height))
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + .5f).toInt()

    companion object { private val TURKISH = Locale.forLanguageTag("tr-TR") }
}
