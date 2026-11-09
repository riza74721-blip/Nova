package com.nova.assistant.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nova.assistant.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** The conversation surface. Voice and assistant behavior are supplied by the activity. */
class NovaScreen(context: Context, private val callbacks: Callbacks) : FrameLayout(context) {
    interface Callbacks {
        fun onListen()
        fun onStop()
        fun onSend(text: String)
        fun onSettings()
        fun onClearHistory()
        fun onQuickAction(text: String)
    }

    data class Message(val id: Long, val fromUser: Boolean, val text: String, val time: Long)
    enum class State { READY, LISTENING, PROCESSING, SPEAKING, ERROR }

    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        id = R.id.nova_scroll
        isFillViewport = true
        clipToPadding = false
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
    }
    private val scrollContent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val hero = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val heroGreeting = TextView(context)
    private val messages = LinearLayout(context).apply {
        id = R.id.nova_messages
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(10), dp(22), dp(18))
    }
    private val statePill = TextView(context)
    private val stateDetail = TextView(context)
    private val partial = TextView(context)
    private val voiceStatus = TextView(context)
    private val input = EditText(context)
    private val send = IconControl(context, R.drawable.ic_nova_send, R.string.nova_send)
    private val microphone = IconControl(context, R.drawable.ic_nova_microphone, R.string.nova_start_listening)
    private val orb = OrbView(context)
    private var state = State.READY
    private var displayedMessages: List<Message> = emptyList()

    init {
        id = R.id.nova_screen
        isFocusableInTouchMode = true
        background = ScreenBackground()
        clipToPadding = false
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        buildHeader()
        buildConversation()
        buildComposer()
        configureWindowInsets()
        renderState(State.READY, context.getString(R.string.nova_ready_detail))
        renderMessages(emptyList())
    }

    private fun buildHeader() {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(10), dp(8))
        }
        val mark = ImageView(context).apply {
            setImageResource(R.drawable.ic_nova_mark)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(26), dp(26)))
        val brand = text(R.string.nova_app_name, 17f, Palette.text, "sans-serif-medium").apply {
            letterSpacing = 0.13f
            maxWidth = dp(62)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        header.addView(brand, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(7)
            marginEnd = dp(8)
        })
        statePill.apply {
            id = R.id.nova_state
            textSize = 9f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(6), dp(9), dp(6))
            maxLines = 1
            maxWidth = dp(72)
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(statePill)
        header.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        val clear = IconControl(context, R.drawable.ic_nova_clear, R.string.nova_clear_history).apply {
            id = R.id.nova_clear_history
            setOnClickListener { callbacks.onClearHistory() }
            setSurface(Color.TRANSPARENT, Palette.muted)
        }
        val settings = IconControl(context, R.drawable.ic_nova_settings, R.string.nova_settings).apply {
            id = R.id.nova_settings
            setOnClickListener { callbacks.onSettings() }
            setSurface(Color.TRANSPARENT, Palette.muted)
        }
        header.addView(clear, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(settings, LinearLayout.LayoutParams(dp(48), dp(48)))
        column.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun buildConversation() {
        hero.apply { setPadding(dp(24), dp(14), dp(24), dp(16)) }
        hero.addView(text(R.string.nova_eyebrow, 10f, Palette.mint, "sans-serif-medium").apply { letterSpacing = 0.18f })
        heroGreeting.apply {
            setText(R.string.nova_greeting)
            textSize = 28f
            setTextColor(Palette.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(1).toFloat(), 1f)
        }
        hero.addView(heroGreeting, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        hero.addView(text(R.string.nova_intro, 14f, Palette.muted).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        val orbArea = FrameLayout(context)
        orb.apply {
            id = R.id.nova_orb
            contentDescription = context.getString(R.string.nova_start_listening)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleVoice() }
        }
        orbArea.addView(orb, LayoutParams(dp(142), dp(116), Gravity.CENTER))
        hero.addView(orbArea, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(116)))
        hero.addView(text(R.string.nova_mic_hint, 12f, Palette.muted).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        val suggestions = listOf(
            Suggestion(R.id.nova_suggestion_time, R.drawable.ic_nova_clock, R.string.nova_suggest_time, R.string.nova_suggest_time_query),
            Suggestion(R.id.nova_suggestion_help, R.drawable.ic_nova_sparkles, R.string.nova_suggest_help, R.string.nova_suggest_help_query),
            Suggestion(R.id.nova_suggestion_note, R.drawable.ic_nova_note, R.string.nova_suggest_note, R.string.nova_suggest_note_query),
            Suggestion(R.id.nova_suggestion_math, R.drawable.ic_nova_calculator, R.string.nova_suggest_math, R.string.nova_suggest_math_query)
        )
        for (rowIndex in 0..1) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (columnIndex in 0..1) {
                val item = suggestions[rowIndex * 2 + columnIndex]
                row.addView(suggestion(item), LinearLayout.LayoutParams(0, dp(60), 1f).apply {
                    if (columnIndex == 0) marginEnd = dp(10)
                })
            }
            hero.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (rowIndex > 0) topMargin = dp(10)
            })
        }
        scrollContent.addView(hero)
        scrollContent.addView(messages)
        scroll.addView(scrollContent, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        column.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private data class Suggestion(val id: Int, val icon: Int, val title: Int, val query: Int)

    private fun suggestion(item: Suggestion): View = LinearLayout(context).apply {
        id = item.id
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(10), dp(8))
        background = ripple(Palette.surface, Palette.border, 17f)
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(item.title)
        setOnClickListener { callbacks.onQuickAction(context.getString(item.query)) }
        addView(ImageView(context).apply {
            setImageResource(item.icon)
            imageTintList = ColorStateList.valueOf(Palette.mint)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(21), dp(21)).apply { marginEnd = dp(10) })
        addView(text(item.title, 13f, Palette.text, "sans-serif-medium").apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun buildComposer() {
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Palette.background, Palette.background))
        }
        partial.apply {
            id = R.id.nova_partial
            textSize = 14f
            setTextColor(Palette.text)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(5), 0, dp(5), dp(9))
            visibility = GONE
        }
        footer.addView(partial)
        stateDetail.apply {
            id = R.id.nova_state_detail
            textSize = 12f
            setTextColor(Palette.muted)
            maxLines = 3
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(4), 0, dp(4), dp(9))
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        footer.addView(stateDetail)
        voiceStatus.apply {
            id = R.id.nova_voice_status
            textSize = 12f
            setTextColor(Palette.muted)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            maxLines = 3
            visibility = GONE
            isFocusable = true
            setOnClickListener { callbacks.onSettings() }
        }
        footer.addView(voiceStatus, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val inputArea = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Palette.surface, 19f, Palette.border)
            setPadding(dp(2), dp(2), dp(5), dp(2))
        }
        input.apply {
            id = R.id.nova_input
            setHint(R.string.nova_input_hint)
            contentDescription = context.getString(R.string.nova_input_description)
            textSize = 15f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(Palette.text)
            setHintTextColor(Palette.hint)
            setPadding(dp(13), dp(13), dp(6), dp(13))
            background = null
            gravity = Gravity.CENTER_VERTICAL
            minLines = 1
            maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP && !event.isShiftPressed
                if (actionId == EditorInfo.IME_ACTION_SEND || enter) { submit(); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    send.isEnabled = !s.isNullOrBlank()
                    send.alpha = if (send.isEnabled) 1f else 0.35f
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        send.apply {
            id = R.id.nova_send
            setSurface(Palette.mintDim, Palette.mint)
            setOnClickListener { submit() }
            isEnabled = false
            alpha = 0.35f
        }
        inputArea.addView(input, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        inputArea.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)))
        controls.addView(inputArea, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        microphone.apply {
            id = R.id.nova_microphone
            setSurface(Palette.mint, Palette.background, 20f)
            elevation = dp(5).toFloat()
            setOnClickListener { toggleVoice() }
        }
        controls.addView(microphone, LinearLayout.LayoutParams(dp(58), dp(58)).apply { marginStart = dp(10) })
        footer.addView(controls)
        column.addView(footer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun submit() {
        val value = input.text?.toString()?.trim().orEmpty()
        if (value.isBlank()) return
        input.text?.clear()
        callbacks.onSend(value)
    }

    private fun toggleVoice() {
        if (state == State.LISTENING || state == State.PROCESSING || state == State.SPEAKING) callbacks.onStop()
        else callbacks.onListen()
    }

    fun renderState(state: State, detail: String) {
        this.state = state
        val color = when (state) {
            State.READY, State.LISTENING -> Palette.mint
            State.PROCESSING -> Palette.violet
            State.SPEAKING -> Palette.blue
            State.ERROR -> Palette.amber
        }
        val label = when (state) {
            State.READY -> R.string.nova_state_ready
            State.LISTENING -> R.string.nova_state_listening
            State.PROCESSING -> R.string.nova_state_processing
            State.SPEAKING -> R.string.nova_state_speaking
            State.ERROR -> R.string.nova_state_error
        }
        statePill.text = context.getString(label)
        statePill.setTextColor(color)
        statePill.background = rounded(alpha(color, 20), 20f, alpha(color, 40))
        stateDetail.text = detail.ifBlank { context.getString(R.string.nova_ready_detail) }
        stateDetail.setTextColor(if (state == State.ERROR) Palette.amber else Palette.muted)
        val active = state == State.LISTENING || state == State.PROCESSING || state == State.SPEAKING
        microphone.setIcon(if (active) R.drawable.ic_nova_stop else R.drawable.ic_nova_microphone)
        val description = context.getString(if (active) R.string.nova_stop else R.string.nova_start_listening)
        microphone.contentDescription = description
        orb.contentDescription = description
        microphone.setSurface(if (state == State.SPEAKING) Palette.blue else Palette.mint, Palette.background, 20f)
        orb.setState(state)
        if (state != State.LISTENING) showPartial("")
    }

    fun renderMessages(messages: List<Message>) {
        if (displayedMessages == messages && this.messages.childCount > 0) return
        val wasEmpty = displayedMessages.isEmpty()
        val newUserMessage = messages.lastOrNull()?.let { it.fromUser && it.id != displayedMessages.lastOrNull()?.id } == true
        val shouldFollow = wasEmpty || newUserMessage || scroll.getChildAt(0)?.let { it.bottom - (scroll.scrollY + scroll.height) < dp(100) } != false
        displayedMessages = messages.toList()
        hero.visibility = if (messages.isEmpty()) VISIBLE else GONE
        this.messages.visibility = if (messages.isEmpty()) GONE else VISIBLE
        this.messages.removeAllViews()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.forLanguageTag("tr-TR"))
        messages.forEach { message ->
            val bubble = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(13), dp(16), dp(12))
                background = rounded(if (message.fromUser) Palette.userBubble else Palette.surface, 19f,
                    if (message.fromUser) Palette.userBorder else Palette.border)
            }
            val meta = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            meta.addView(text(if (message.fromUser) R.string.nova_you else R.string.nova_app_name,
                10f, if (message.fromUser) Palette.blue else Palette.mint, "sans-serif-medium").apply { letterSpacing = 0.12f },
                LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            meta.addView(TextView(context).apply {
                text = timeFormat.format(Date(message.time))
                textSize = 10f
                setTextColor(Palette.hint)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            bubble.addView(meta)
            bubble.addView(TextView(context).apply {
                tag = "nova_message_text"
                text = message.text
                textSize = 16f
                setTextColor(Palette.text)
                setLineSpacing(dp(4).toFloat(), 1f)
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
            this.messages.addView(bubble, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(11)
                if (message.fromUser) marginStart = dp(36) else marginEnd = dp(22)
            })
        }
        if (shouldFollow) scroll.post { scroll.scrollTo(0, scrollContent.height) }
    }

    fun showPartial(text: String) {
        partial.text = text
        partial.visibility = if (text.isBlank()) GONE else VISIBLE
    }

    fun setVoiceStatus(text: String, needsSetup: Boolean) {
        voiceStatus.text = if (needsSetup && text.isNotBlank()) {
            SpannableStringBuilder(text).apply {
                append('\n')
                val actionStart = length
                append(context.getString(R.string.nova_voice_setup_action))
                setSpan(StyleSpan(Typeface.BOLD), actionStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(Palette.mint), actionStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else text
        voiceStatus.maxLines = if (needsSetup) 5 else 3
        voiceStatus.minHeight = if (needsSetup) dp(48) else 0
        voiceStatus.visibility = if (text.isBlank()) GONE else VISIBLE
        voiceStatus.isClickable = needsSetup
        voiceStatus.isFocusable = needsSetup
        voiceStatus.setTextColor(if (needsSetup) Palette.amber else Palette.muted)
        voiceStatus.background = if (needsSetup) ripple(Palette.warningSurface, alpha(Palette.amber, 55), 13f) else null
        voiceStatus.setPadding(if (needsSetup) dp(12) else dp(4), if (needsSetup) dp(10) else 0,
            if (needsSetup) dp(12) else dp(4), if (needsSetup) dp(10) else 0)
        voiceStatus.contentDescription = if (needsSetup) context.getString(R.string.nova_voice_setup_description, text) else text
    }

    fun setInputText(text: String) {
        input.setText(text)
        input.setSelection(input.text?.length ?: 0)
        input.requestFocus()
    }

    fun setAssistantName(name: String) {
        heroGreeting.text = context.getString(R.string.nova_greeting_named, name.trim().take(24).ifBlank { "Nova" })
    }

    fun hideKeyboard() {
        input.clearFocus()
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(windowToken, 0)
    }

    @Suppress("DEPRECATION")
    private fun configureWindowInsets() {
        var current: Context? = context
        var activity: Activity? = null
        while (current != null) {
            if (current is Activity) { activity = current; break }
            current = if (current is ContextWrapper && current.baseContext !== current) current.baseContext else null
        }
        activity?.window?.apply {
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(false)
            else decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (Build.VERSION.SDK_INT >= 29) {
                isNavigationBarContrastEnforced = false
                isStatusBarContrastEnforced = false
            }
        }
        setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)
            } else {
                top = insets.systemWindowInsetTop
                // On older Android, adjustResize already removes the keyboard from the window.
                bottom = min(insets.systemWindowInsetBottom, dp(64))
            }
            column.setPadding(0, top, 0, bottom)
            insets
        }
        requestApplyInsets()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val target = min(w, dp(640))
        if (column.layoutParams.width != target) {
            column.layoutParams = LayoutParams(target, LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        }
    }

    private fun text(resource: Int, size: Float, color: Int, family: String = "sans-serif") = TextView(context).apply {
        setText(resource)
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(family, Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun rounded(color: Int, radius: Float, border: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (border != Color.TRANSPARENT) setStroke(dp(1), border)
    }

    private fun ripple(color: Int, border: Int, radius: Float): Drawable = RippleDrawable(
        ColorStateList.valueOf(alpha(Palette.mint, 45)), rounded(color, radius, border), rounded(Color.WHITE, radius)
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private class IconControl(context: Context, icon: Int, description: Int) : FrameLayout(context) {
        private val image = ImageView(context)
        init {
            contentDescription = context.getString(description)
            isClickable = true
            isFocusable = true
            minimumWidth = dip(48f)
            minimumHeight = dip(48f)
            image.setImageResource(icon)
            image.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(image, LayoutParams(dip(23f), dip(23f), Gravity.CENTER))
            setSurface(Palette.surface, Palette.text)
        }
        fun setIcon(icon: Int) { image.setImageResource(icon) }
        fun setSurface(color: Int, tint: Int, radius: Float = 15f) {
            val shape = GradientDrawable().apply { setColor(color); cornerRadius = dip(radius).toFloat() }
            val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dip(radius).toFloat() }
            background = RippleDrawable(ColorStateList.valueOf(alpha(Palette.mint, 60)), shape, mask)
            image.imageTintList = ColorStateList.valueOf(tint)
        }
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.Button"
        }
        private fun dip(value: Float) = (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private class OrbView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var state = State.READY
        private var phase = 0f
        private var animator: ValueAnimator? = null

        fun setState(value: State) {
            state = value
            updateAnimation()
            invalidate()
        }
        private fun updateAnimation() {
            val animate = isAttachedToWindow && state != State.READY && state != State.ERROR && ValueAnimator.areAnimatorsEnabled()
            if (animate && animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1900
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                    start()
                }
            } else if (!animate) {
                animator?.cancel()
                animator = null
                phase = 0f
            }
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation() }
        override fun onDetachedFromWindow() { animator?.cancel(); animator = null; super.onDetachedFromWindow() }
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.Button"
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = min(width, height) / 150f
            canvas.save()
            canvas.translate(width / 2f, height / 2f)
            canvas.scale(scale, scale)
            val active = state != State.READY && state != State.ERROR
            val glowRadius = 68f + phase * 5f
            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(0f, 0f, glowRadius,
                intArrayOf(alpha(Palette.mint, if (active) 60 else 34), alpha(Palette.blue, 14), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(0f, 0f, glowRadius, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.8f
            paint.color = alpha(Palette.mint, 36)
            canvas.drawCircle(0f, 0f, 58f + phase * 3f, paint)
            paint.color = alpha(Palette.violet, 40)
            canvas.drawArc(RectF(-65f, -65f, 65f, 65f), -75f, 115f, false, paint)
            canvas.drawArc(RectF(-65f, -65f, 65f, 65f), 110f, 55f, false, paint)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(-35f, -42f, 38f, 45f,
                intArrayOf(Color.rgb(54, 117, 133), Color.rgb(26, 64, 89), Color.rgb(43, 45, 91)), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(0f, 0f, 45f + phase * 1.5f, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = alpha(Palette.mint, 110)
            canvas.drawCircle(0f, 0f, 45f + phase * 1.5f, paint)
            paint.strokeWidth = 2.6f
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Palette.text
            if (state == State.LISTENING || state == State.SPEAKING) {
                for (index in -2..2) {
                    val magnitude = when (kotlin.math.abs(index)) { 0 -> 16f; 1 -> 11f; else -> 6f }
                    val length = magnitude * (0.75f + phase * 0.45f)
                    canvas.drawLine(index * 7f, -length, index * 7f, length, paint)
                }
            } else {
                canvas.drawRoundRect(RectF(-6f, -16f, 6f, 6f), 6f, 6f, paint)
                canvas.drawArc(RectF(-12f, -8f, 12f, 14f), 0f, 180f, false, paint)
                canvas.drawLine(0f, 14f, 0f, 20f, paint)
                canvas.drawLine(-6f, 20f, 6f, 20f, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = Palette.mint
            canvas.drawCircle(45f, -34f, 3f, paint)
            paint.color = Palette.violet
            canvas.drawCircle(-51f, 31f, 2f, paint)
            canvas.restore()
        }
    }

    private class ScreenBackground : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            canvas.drawColor(Palette.background)
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()
            if (width <= 0f || height <= 0f) return
            paint.shader = RadialGradient(width * 0.04f, height * 0.15f, width * 0.85f,
                intArrayOf(Color.rgb(13, 43, 51), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawRect(bounds, paint)
            paint.shader = RadialGradient(width * 1.05f, height * 0.47f, width * 0.85f,
                intArrayOf(Color.argb(65, 52, 39, 96), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawRect(bounds, paint)
            paint.shader = null
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Drawable opacity is no longer used")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    private object Palette {
        val background = Color.rgb(7, 17, 29)
        val surface = Color.rgb(17, 32, 48)
        val border = Color.rgb(31, 49, 68)
        val text = Color.rgb(234, 243, 251)
        val muted = Color.rgb(151, 174, 193)
        val hint = Color.rgb(103, 130, 152)
        val mint = Color.rgb(99, 230, 192)
        val mintDim = Color.rgb(24, 62, 61)
        val violet = Color.rgb(184, 166, 255)
        val blue = Color.rgb(142, 213, 244)
        val amber = Color.rgb(246, 202, 130)
        val warningSurface = Color.rgb(42, 37, 29)
        val userBubble = Color.rgb(22, 49, 66)
        val userBorder = Color.rgb(39, 80, 96)
    }

    companion object {
        private fun alpha(color: Int, value: Int) = (color and 0x00ffffff) or (value.coerceIn(0, 255) shl 24)
    }
}
