package com.nova.assistant

import com.nova.assistant.voice.RecognizerFactory
import com.nova.assistant.voice.TtsFactory

/** Production uses Android services. Instrumentation can replace only the service boundary. */
object NovaDependencies {
    var recognizerFactory: RecognizerFactory? = null
    var ttsFactory: TtsFactory? = null

    fun reset() {
        recognizerFactory = null
        ttsFactory = null
    }
}
