package com.ayra.ai.app.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.ayra.ai.app.data.AyraPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

sealed class VoiceUiState {
    data object Idle : VoiceUiState()
    data object Connecting : VoiceUiState()
    data class Listening(val isManual: Boolean) : VoiceUiState()
    data object Processing : VoiceUiState()
    data class Error(val message: String, val code: Int = -1) : VoiceUiState()
}

/**
 * Advanced Voice Assistant Manager for Ayra AI.
 * Features:
 * - Real-time Speech-to-Text with live partial transcription, acoustic RMS metering, and BN/HI/EN support.
 * - Dynamic Voice Modulation: alters pitch, speech rate, and emotional tone based on detected user sentiment in GF Mode.
 * - Always-on background wake-word listening ("Ayra" / "আয়রা").
 * - Multi-language speech recognition and natural female TTS (Bengali, Hindi, English).
 */
class VoiceAssistantManager(
    private val context: Context,
    private val onCommandReceived: (String) -> Unit
) : TextToSpeech.OnInitListener, RecognitionListener {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _liveSpokenText = MutableStateFlow("")
    val liveSpokenText: StateFlow<String> = _liveSpokenText.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _voiceState = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val voiceState: StateFlow<VoiceUiState> = _voiceState.asStateFlow()

    private val _selectedLanguageTag = MutableStateFlow("auto")
    val selectedLanguageTag: StateFlow<String> = _selectedLanguageTag.asStateFlow()

    private val _speechRecognizerAvailable = MutableStateFlow(
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
    )
    val speechRecognizerAvailable: StateFlow<Boolean> = _speechRecognizerAvailable.asStateFlow()

    // Dynamic Voice Modulation & Sentiment State
    private val _currentSentiment = MutableStateFlow(UserSentiment.NEUTRAL_CARING)
    val currentSentiment: StateFlow<UserSentiment> = _currentSentiment.asStateFlow()

    var isDynamicModulationEnabled: Boolean = true
    var manualSentimentLock: UserSentiment? = null

    var onWakeWordTriggered: ((greeting: String) -> Unit)? = null
    var onSpeechTranscribed: ((text: String, isFinal: Boolean) -> Unit)? = null

    var personaMode: String = "AI_ASSISTANT" // "AI_ASSISTANT", "PROFESSIONAL", or "GF_COMPANION" (18+ gated)
    var isAlwaysOnWakeWordEnabled: Boolean = false
    var isManualSession: Boolean = false

    private var isTtsReady = false
    private var isDestroyed = false
    private val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var neuralVoiceJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var activeFocusRequest: AudioFocusRequest? = null
    private val audioManager: AudioManager? by lazy {
        runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()
    }
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            stopSpeaking()
        }
    }

    private fun requestTtsAudioFocus() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = activeFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
                    .also { activeFocusRequest = it }
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        }
    }

    private fun abandonTtsAudioFocus() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusChangeListener)
            }
        }
    }

    init {
        // Defer Binder IPC for TTS & SpeechRecognizer until after Frame 1 renders cleanly
        mainHandler.postDelayed({
            if (!isDestroyed) {
                ensureTtsInitialized()
                if (speechRecognizer == null) {
                    initRecognizer()
                }
            }
        }, 600L)
    }

    private fun ensureTtsInitialized() {
        if (isDestroyed || tts != null) return
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (_: Exception) {}
    }

    private fun initRecognizer() {
        if (isDestroyed) return
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}

        try {
            val available = SpeechRecognizer.isRecognitionAvailable(context)
            _speechRecognizerAvailable.value = available
            if (available) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(this@VoiceAssistantManager)
                }
            }
        } catch (_: Exception) {
            _speechRecognizerAvailable.value = false
        }
    }

    fun setLanguageTag(tag: String) {
        _selectedLanguageTag.value = tag
    }

    fun dismissVoiceError() {
        if (_voiceState.value is VoiceUiState.Error) {
            _voiceState.value = VoiceUiState.Idle
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            setupVoiceModulation(Locale.US, _currentSentiment.value)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                    abandonTtsAudioFocus()
                    // Once done speaking, immediately re-listen if wake word is active
                    if (isAlwaysOnWakeWordEnabled && !isDestroyed) {
                        mainHandler.postDelayed({
                            startListening()
                        }, 400)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                    abandonTtsAudioFocus()
                    if (isAlwaysOnWakeWordEnabled && !isDestroyed) {
                        mainHandler.postDelayed({
                            startListening()
                        }, 500)
                    }
                }
            })
        }
    }

    /**
     * Dynamically configures the TTS engine pitch, speech rate, and female voice
     * according to the persona and detected user sentiment.
     */
    private fun setupVoiceModulation(targetLocale: Locale, sentiment: UserSentiment) {
        tts?.let { engine ->
            engine.language = targetLocale

            if (personaMode == "GF_COMPANION") {
                // Apply dynamic emotional modulation
                val activeSentiment = manualSentimentLock ?: sentiment
                engine.setPitch(activeSentiment.pitch)
                engine.setSpeechRate(activeSentiment.speechRate)
            } else {
                // Professional neutral voice
                engine.setPitch(1.00f)
                engine.setSpeechRate(1.00f)
            }

            try {
                val voices = engine.voices
                if (!voices.isNullOrEmpty()) {
                    val sweetFemaleVoice = voices.find { v ->
                        v.locale.language == targetLocale.language &&
                                (v.name.contains("female", ignoreCase = true) ||
                                        v.name.contains("woman", ignoreCase = true) ||
                                        v.name.contains("#female", ignoreCase = true) ||
                                        v.name.contains("sweet", ignoreCase = true) ||
                                        v.quality == Voice.QUALITY_VERY_HIGH)
                    } ?: voices.find { it.locale.language == targetLocale.language }
                    if (sweetFemaleVoice != null) {
                        engine.voice = sweetFemaleVoice
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Analyzes user prompt and updates active emotional state.
     */
    fun detectAndApplySentiment(userPrompt: String): UserSentiment {
        if (!isDynamicModulationEnabled || manualSentimentLock != null) {
            return manualSentimentLock ?: _currentSentiment.value
        }
        val detected = SentimentDetector.detectSentiment(userPrompt)
        _currentSentiment.value = detected
        return detected
    }

    /**
     * Speaks text using ElevenLabs neural voice (when configured) with seamless fallback
     * to dynamic sentiment-modulated Android TTS.
     */
    fun speak(text: String, overrideSentiment: UserSentiment? = null) {
        if (isDestroyed || text.isBlank()) return
        stopListening()
        requestTtsAudioFocus()

        // Determine active sentiment profile
        val targetSentiment = when {
            manualSentimentLock != null -> manualSentimentLock!!
            overrideSentiment != null -> overrideSentiment
            isDynamicModulationEnabled -> {
                val detected = SentimentDetector.detectSentiment(text)
                if (detected != UserSentiment.NEUTRAL_CARING) detected else _currentSentiment.value
            }
            else -> _currentSentiment.value
        }
        _currentSentiment.value = targetSentiment

        // Clean markdown tags & symbols for natural voice
        val cleaned = text
            .replace(Regex("```[\\s\\S]*?```"), "Code snippet provided in chat.")
            .replace(Regex("[*#`_~>|\\[\\]()]"), " ")
            .trim()
        if (cleaned.isBlank()) return

        neuralVoiceJob?.cancel()
        releaseMediaPlayer()

        val elevenKey = runCatching { AyraPreferences.getElevenLabsKey(context) }.getOrDefault("").trim()
        if (elevenKey.isNotBlank()) {
            neuralVoiceJob = voiceScope.launch {
                val audioBytes = runCatching {
                    ElevenLabsVoiceEngine(context).synthesizeSpeech(cleaned)
                }.getOrNull()

                if (audioBytes != null && audioBytes.isNotEmpty() && !isDestroyed) {
                    val tempFile = runCatching {
                        File(context.cacheDir, "ayra_voice_${System.currentTimeMillis()}.mp3").apply {
                            writeBytes(audioBytes)
                        }
                    }.getOrNull()

                    if (tempFile != null) {
                        val started = withContext(Dispatchers.Main) {
                            playNeuralAudioFile(tempFile)
                        }
                        if (started) return@launch
                        runCatching { tempFile.delete() }
                    }
                }

                if (!isDestroyed) {
                    withContext(Dispatchers.Main) {
                        speakViaSystemTts(cleaned, targetSentiment)
                    }
                }
            }
            return
        }

        speakViaSystemTts(cleaned, targetSentiment)
    }

    private fun playNeuralAudioFile(file: File): Boolean {
        return try {
            tts?.stop()
            releaseMediaPlayer()
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnPreparedListener { player ->
                    if (isDestroyed) {
                        releaseMediaPlayer()
                        return@setOnPreparedListener
                    }
                    _isSpeaking.value = true
                    player.start()
                }
                setOnCompletionListener {
                    _isSpeaking.value = false
                    abandonTtsAudioFocus()
                    releaseMediaPlayer()
                    runCatching { file.delete() }
                    if (isAlwaysOnWakeWordEnabled && !isDestroyed) {
                        mainHandler.postDelayed({ startListening() }, 400)
                    }
                }
                setOnErrorListener { _, _, _ ->
                    _isSpeaking.value = false
                    abandonTtsAudioFocus()
                    releaseMediaPlayer()
                    runCatching { file.delete() }
                    false
                }
                prepareAsync()
            }
            mediaPlayer = mp
            true
        } catch (_: Exception) {
            releaseMediaPlayer()
            false
        }
    }

    private fun releaseMediaPlayer() {
        runCatching {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.reset()
                mp.release()
            }
        }
        mediaPlayer = null
    }

    private fun speakViaSystemTts(cleaned: String, targetSentiment: UserSentiment) {
        if (tts == null) ensureTtsInitialized()
        if (!isTtsReady || cleaned.isBlank()) {
            _isSpeaking.value = false
            abandonTtsAudioFocus()
            return
        }

        val isBengali = cleaned.any { it in '\u0980'..'\u09FF' }
        val isHindi = cleaned.any { it in '\u0900'..'\u097F' }

        val locale = when {
            isBengali -> Locale.forLanguageTag("bn-IN")
            isHindi -> Locale.forLanguageTag("hi-IN")
            else -> Locale.US
        }

        setupVoiceModulation(locale, targetSentiment)

        val sentenceDelimiters = Regex("(?<=[।!?\\n])\\s+|(?<=\\.)\\s+")
        val chunks = cleaned.split(sentenceDelimiters).filter { it.isNotBlank() }

        if (chunks.isEmpty()) {
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "AyraSweetVoice_${System.currentTimeMillis()}")
            }
            tts?.speak(cleaned, TextToSpeech.QUEUE_FLUSH, params, "AyraSweetVoice")
        } else {
            for ((index, chunk) in chunks.withIndex()) {
                val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "AyraSweetVoice_${System.currentTimeMillis()}_$index")
                }
                tts?.speak(chunk.trim(), queueMode, params, "AyraSweetVoice_$index")
            }
        }
    }

    /**
     * Triggers a live demonstration of any sentiment tone.
     */
    fun testSentimentVoice(sentiment: UserSentiment, isBengali: Boolean = true) {
        val sample = if (isBengali) sentiment.sampleGreetingBn else sentiment.sampleGreetingEn
        speak(sample, overrideSentiment = sentiment)
    }

    fun stopSpeaking() {
        neuralVoiceJob?.cancel()
        releaseMediaPlayer()
        tts?.stop()
        _isSpeaking.value = false
        abandonTtsAudioFocus()
    }

    fun startManualListening() {
        startManualVoiceCommand(null)
    }

    fun startManualVoiceCommand(languageTag: String? = null) {
        if (!languageTag.isNullOrBlank()) {
            _selectedLanguageTag.value = languageTag
        }
        if (_isSpeaking.value) {
            stopSpeaking()
        }
        _liveSpokenText.value = ""
        _rmsLevel.value = 0f
        isManualSession = true
        _voiceState.value = VoiceUiState.Connecting
        startListening()
    }

    fun startListening() {
        if (isDestroyed || _isSpeaking.value) return
        if (speechRecognizer == null) initRecognizer()

        val recognizer = speechRecognizer
        if (recognizer == null) {
            _isListening.value = false
            _rmsLevel.value = 0f
            if (isManualSession) {
                _voiceState.value = VoiceUiState.Error(
                    "Live system speech recognition is unavailable on this device. Switching to Gemini Audio Capture."
                )
            }
            isManualSession = false
            return
        }

        val effectiveLanguage = when (val selected = _selectedLanguageTag.value) {
            "auto", "" -> Locale.getDefault().toLanguageTag()
            else -> selected
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, effectiveLanguage)
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("bn-IN", "hi-IN", "en-US"))
        }

        try {
            recognizer.startListening(intent)
            _isListening.value = true
            _voiceState.value = VoiceUiState.Listening(isManual = isManualSession)
        } catch (e: Exception) {
            _isListening.value = false
            _rmsLevel.value = 0f
            if (isManualSession) {
                _voiceState.value = VoiceUiState.Error(
                    "Could not start microphone listener: ${e.localizedMessage ?: "Unknown error"}"
                )
                isManualSession = false
            } else {
                scheduleWakeWordRestart(1200)
            }
        }
    }

    fun stopListening() {
        val wasManual = isManualSession
        isManualSession = false
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        _isListening.value = false
        _rmsLevel.value = 0f
        if (!wasManual || _voiceState.value !is VoiceUiState.Error) {
            _voiceState.value = VoiceUiState.Idle
        }
    }

    /**
     * Stops active listening while preserving isManualSession so RecognitionListener.onResults
     * delivers the finalized spoken text straight into AI inference; if live partial text is
     * already buffered and recognizer had finished, dispatches immediately.
     */
    fun finishAndSubmitManualListening() {
        val buffered = _liveSpokenText.value.trim()
        if (_isListening.value) {
            _voiceState.value = VoiceUiState.Processing
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {
                isManualSession = false
                _isListening.value = false
                _rmsLevel.value = 0f
                _voiceState.value = VoiceUiState.Idle
                if (buffered.isNotEmpty()) {
                    onSpeechTranscribed?.invoke(buffered, true)
                    onCommandReceived(buffered)
                }
            }
        } else if (buffered.isNotEmpty()) {
            isManualSession = false
            _voiceState.value = VoiceUiState.Idle
            onSpeechTranscribed?.invoke(buffered, true)
            onCommandReceived(buffered)
        }
    }

    /**
     * Aborts the active voice-to-text session without triggering any AI command.
     */
    fun cancelListening() {
        isManualSession = false
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        _isListening.value = false
        _rmsLevel.value = 0f
        _liveSpokenText.value = ""
        _voiceState.value = VoiceUiState.Idle
    }

    private fun scheduleWakeWordRestart(delayMs: Long = 800) {
        if (isAlwaysOnWakeWordEnabled && !isDestroyed && !_isSpeaking.value && !isManualSession) {
            mainHandler.removeCallbacksAndMessages(null)
            mainHandler.postDelayed({
                if (isAlwaysOnWakeWordEnabled && !isDestroyed && !_isSpeaking.value && !isManualSession) {
                    startListening()
                }
            }, delayMs)
        }
    }

    fun destroy() {
        isDestroyed = true
        neuralVoiceJob?.cancel()
        voiceScope.cancel()
        releaseMediaPlayer()
        mainHandler.removeCallbacksAndMessages(null)
        abandonTtsAudioFocus()
        tts?.stop()
        tts?.shutdown()
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
    }

    // RecognitionListener Callbacks
    override fun onReadyForSpeech(params: Bundle?) {
        _isListening.value = true
        _voiceState.value = VoiceUiState.Listening(isManual = isManualSession)
    }

    override fun onBeginningOfSpeech() {
        _voiceState.value = VoiceUiState.Listening(isManual = isManualSession)
    }

    override fun onRmsChanged(rmsdB: Float) {
        _rmsLevel.value = normalizeRmsDb(rmsdB)
    }

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        _isListening.value = false
        _rmsLevel.value = 0f
        if (isManualSession) {
            _voiceState.value = VoiceUiState.Processing
        }
    }

    override fun onError(error: Int) {
        val wasManual = isManualSession
        _isListening.value = false
        _rmsLevel.value = 0f
        isManualSession = false
        if (wasManual) {
            val buffered = _liveSpokenText.value.trim()
            if (error == SpeechRecognizer.ERROR_NO_MATCH && buffered.isNotEmpty()) {
                // Submit buffered partial result if recognizer timed out right at the end
                _voiceState.value = VoiceUiState.Idle
                onSpeechTranscribed?.invoke(buffered, true)
                onCommandReceived(buffered)
            } else {
                _voiceState.value = VoiceUiState.Error(
                    message = mapRecognitionError(error),
                    code = error
                )
            }
        } else {
            _voiceState.value = VoiceUiState.Idle
            if (isAlwaysOnWakeWordEnabled && !isDestroyed) {
                val backoffMs = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> 2500L
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 3500L
                    SpeechRecognizer.ERROR_CLIENT -> 4500L
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 10000L
                    else -> 1500L
                }
                scheduleWakeWordRestart(backoffMs)
            }
        }
    }

    override fun onResults(results: Bundle?) {
        val wasManual = isManualSession
        isManualSession = false
        _isListening.value = false
        _rmsLevel.value = 0f
        _voiceState.value = VoiceUiState.Idle

        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val spoken = matches[0].trim()
            _liveSpokenText.value = spoken

            if (wasManual) {
                // User explicitly pressed the Mic Button: Transcribe directly to chat & trigger AI!
                onSpeechTranscribed?.invoke(spoken, true)
                onCommandReceived(spoken)
                return
            }

            // BACKGROUND WAKE-WORD MODE: STRICT FILTERING
            // User did not press mic button; assistant MUST NOT respond unless "Ghost" was explicitly spoken!
            val lower = spoken.lowercase()
                .replace(Regex("[.,?!।]"), "")
                .trim()

            val hasWakeWord = lower.contains("ghost") ||
                    lower.contains("ঘোস্ট") || lower.contains("গোস্ট") ||
                    lower.contains("घोस्ट")

            if (!hasWakeWord) {
                // Background conversation or ambient TV/noise: IGNORE completely!
                scheduleWakeWordRestart(800)
                return
            }

            val isWakeWordOnly = lower in listOf(
                "ghost", "ghost ai", "hey ghost", "hi ghost", "hello ghost", "ok ghost", "okay ghost",
                "hey ghost ai", "hi ghost ai", "hello ghost ai", "ok ghost ai", "okay ghost ai",
                "ঘোস্ট", "গোস্ট", "হেই ঘোস্ট", "হেই গোস্ট", "হ্যালো ঘোস্ট",
                "ঘোস্ট এআই", "হেই ঘোস্ট এআই",
                "घोस्ट", "हे घोस्ट", "हैलो घोस्ट"
            ) || ((lower.startsWith("ghost") || lower.startsWith("ঘোস্ট") || lower.startsWith("গোস্ট")) && lower.length <= 9)

            if (isWakeWordOnly) {
                // Wake word detected! Mode-specific friendly greeting
                val isBengali = spoken.any { it in '\u0980'..'\u09FF' }
                val isHindi = spoken.any { it in '\u0900'..'\u097F' }

                val greeting = if (personaMode == "GF_COMPANION") {
                    when {
                        isBengali -> "হ্যাঁ সোনা, বলো শুনছি... তোমার জন্যই তো আছি ❤️"
                        isHindi -> "हाँ जानू, बोलो मैं सुन रही हूँ... ❤️"
                        else -> "Hey sweetie! I'm right here listening to you ❤️"
                    }
                } else {
                    when {
                        isBengali -> "হ্যাঁ, বলুন। আমি আপনাকে কীভাবে সাহায্য করতে পারি?"
                        isHindi -> "हाँ, बताइए। मैं आपकी क्या सहायता कर सकता हूँ?"
                        else -> "Yes, I am listening. How can I assist you today?"
                    }
                }

                val targetSentiment = if (personaMode == "GF_COMPANION") UserSentiment.AFFECTIONATE else UserSentiment.NEUTRAL_CARING
                speak(greeting, overrideSentiment = targetSentiment)
                onWakeWordTriggered?.invoke(greeting)
                return
            }

            // Clean wake word prefix from explicit command (e.g. "Hey Ghost open YouTube" -> "open YouTube")
            val cleanCommand = spoken
                .replace(Regex("(?i)^(hey\\s+|hi\\s+|hello\\s+|ok\\s+|okay\\s+)?ghost(\\s+ai)?[,\\s]*"), "")
                .replace(Regex("^(হেই\\s+|এই\\s+|ওগো\\s+|হ্যালো\\s+)?(ঘোস্ট|গোস্ট)(\\s+এআই)?[,\\s]*"), "")
                .trim()

            val commandToRun = if (cleanCommand.isNotBlank()) cleanCommand else spoken
            onCommandReceived(commandToRun)
        } else {
            if (!wasManual) {
                scheduleWakeWordRestart(600)
            }
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val partial = matches[0]
            _liveSpokenText.value = partial
            if (isManualSession) {
                onSpeechTranscribed?.invoke(partial, false)
            }
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    companion object {
        fun normalizeRmsDb(rmsdB: Float): Float {
            if (rmsdB.isNaN()) return 0f
            return ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        fun mapRecognitionError(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Microphone audio capture error. Check mic hardware."
            SpeechRecognizer.ERROR_CLIENT -> "Speech client interrupted."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for voice commands."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Speech service network unavailable. Check connection or use Audio Recording."
            SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that clearly — tap Retry and speak again."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Wait a moment and retry."
            SpeechRecognizer.ERROR_SERVER -> "Speech recognition server error."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Tap Retry when ready to speak."
            else -> "Voice recognition stopped (code $error)."
        }
    }
}
