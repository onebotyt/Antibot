package com.example.gemini.assistant.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class CaptionState(
    val isSpeaking: Boolean = false,
    val text: String = "",
    val activeWord: String = "",
    val progress: Float = 0f
)

/**
 * High-quality human-like conversational voice engine.
 * Selects the best neural voice available on the device, optimizes pitch and speech rate,
 * strips markdown and artifacts, and streams live caption updates.
 */
class HumanVoiceEngine private constructor(private val appContext: Context) {

    companion object {
        @Volatile
        private var instance: HumanVoiceEngine? = null

        fun getInstance(context: Context): HumanVoiceEngine {
            return instance ?: synchronized(this) {
                instance ?: HumanVoiceEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _captionState = MutableStateFlow(CaptionState())
    val captionState: StateFlow<CaptionState> = _captionState.asStateFlow()

    private var currentUtteranceId: String? = null
    private var currentSpokenText: String = ""

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                configureNaturalVoice()
                setupProgressListener()
            }
        }
    }

    private fun configureNaturalVoice() {
        val engine = tts ?: return

        // Set locale
        val locale = Locale.getDefault()
        engine.language = if (locale.language.isNotBlank()) locale else Locale.US

        // Set natural human cadence
        engine.setPitch(1.02f) // Slightly warm pitch
        engine.setSpeechRate(1.0f) // Natural talking speed

        // Try to pick the most natural sounding voice available
        try {
            val availableVoices = engine.voices ?: emptySet()
            if (availableVoices.isNotEmpty()) {
                val preferredVoice = availableVoices
                    .filter { voice ->
                        voice.locale.language == locale.language &&
                                !voice.isNetworkConnectionRequired &&
                                voice.features?.contains(Voice.KEY_FEATURE_NOT_INSTALLED) != true
                    }
                    .maxByOrNull { voice ->
                        var score = 0
                        if (voice.quality >= Voice.QUALITY_HIGH) score += 10
                        if (voice.quality >= Voice.QUALITY_VERY_HIGH) score += 20
                        if (voice.latency <= Voice.LATENCY_LOW) score += 5
                        if (voice.name.contains("natural", ignoreCase = true) ||
                            voice.name.contains("neural", ignoreCase = true) ||
                            voice.name.contains("wavenet", ignoreCase = true)) score += 30
                        score
                    }

                preferredVoice?.let {
                    engine.voice = it
                }
            }
        } catch (_: Exception) {
            // Fallback to default engine voice
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _captionState.value = CaptionState(
                    isSpeaking = true,
                    text = currentSpokenText,
                    activeWord = "",
                    progress = 0.05f
                )
            }

            override fun onDone(utteranceId: String?) {
                _captionState.value = CaptionState(
                    isSpeaking = false,
                    text = currentSpokenText,
                    activeWord = "",
                    progress = 1.0f
                )
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _captionState.value = CaptionState(isSpeaking = false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _captionState.value = CaptionState(isSpeaking = false)
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                super.onRangeStart(utteranceId, start, end, frame)
                val full = currentSpokenText
                if (start in 0 until full.length && end <= full.length && start < end) {
                    val word = full.substring(start, end).trim()
                    val progress = (end.toFloat() / full.length.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                    _captionState.value = CaptionState(
                        isSpeaking = true,
                        text = full,
                        activeWord = word,
                        progress = progress
                    )
                }
            }
        })
    }

    /**
     * Converts raw text into smooth conversational prose by removing code blocks,
     * URLs, markdown symbols, and technical formatting.
     */
    fun cleanTextForSpeech(raw: String): String {
        var clean = raw
        // Remove markdown code blocks ``` ... ```
        clean = clean.replace(Regex("```[\\s\\S]*?```"), " [code omitted] ")
        // Remove inline code `...`
        clean = clean.replace(Regex("`([^`]+)`"), "$1")
        // Remove markdown images and links ![...](...) and [...](...)
        clean = clean.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
        clean = clean.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        // Remove bold/italics symbols
        clean = clean.replace(Regex("[*_~#]"), "")
        // Remove URLs
        clean = clean.replace(Regex("https?://\\S+"), " web link ")
        // Collapse excess whitespace
        clean = clean.replace(Regex("\\s+"), " ").trim()
        return clean
    }

    /**
     * Speaks the given text using human voice and streams live caption updates.
     */
    fun speak(text: String, flushQueue: Boolean = true) {
        val cleaned = cleanTextForSpeech(text)
        if (cleaned.isBlank()) return

        currentSpokenText = cleaned
        val utteranceId = "speech_${System.currentTimeMillis()}"
        currentUtteranceId = utteranceId

        val queueMode = if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        if (isInitialized && tts != null) {
            tts?.speak(cleaned, queueMode, params, utteranceId)
        } else {
            // Re-initialize if not ready
            initTts()
        }
    }

    /**
     * Stops speaking immediately and resets captions.
     */
    fun stop() {
        tts?.stop()
        _captionState.value = CaptionState(isSpeaking = false)
    }

    /**
     * Adjust speech characteristics.
     */
    fun configureTone(pitch: Float = 1.02f, rate: Float = 1.0f) {
        tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
