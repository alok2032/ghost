package com.ayra.ai.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manages real microphone audio capture (AAC/M4A) for Gemini multimodal audio transcription
 * and hands-free voice queries, keeping recorded clips compact (<20 MB).
 */
class AudioCaptureManager(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var meterJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _amplitudeLevel = MutableStateFlow(0f)
    val amplitudeLevel: StateFlow<Float> = _amplitudeLevel.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    fun startRecording(): Result<File> {
        if (_isRecording.value) {
            return Result.failure(IllegalStateException("Recording is already in progress."))
        }
        return runCatching {
            val dir = File(context.applicationContext.cacheDir, "voice_notes").apply {
                if (!exists()) mkdirs()
            }
            val file = File(dir, "ghost_voice_${System.currentTimeMillis()}.m4a")
            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context.applicationContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(64_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            recorder = mediaRecorder
            outputFile = file
            _isRecording.value = true
            _elapsedSeconds.value = 0

            meterJob?.cancel()
            meterJob = scope.launch {
                val startedAt = System.currentTimeMillis()
                while (isActive && _isRecording.value) {
                    val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    _amplitudeLevel.value = (amp / 32767f).coerceIn(0f, 1f)
                    _elapsedSeconds.value = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
                    delay(120L)
                }
            }

            file
        }.onFailure {
            cleanupRecorder()
        }
    }

    fun stopRecording(): File? {
        val file = outputFile
        if (!_isRecording.value) {
            cleanupRecorder()
            return null
        }
        runCatching {
            recorder?.stop()
        }
        cleanupRecorder()
        return file?.takeIf { it.exists() && it.length() > 0L }
    }

    fun cancelRecording() {
        val file = outputFile
        runCatching {
            recorder?.stop()
        }
        cleanupRecorder()
        runCatching { file?.delete() }
    }

    fun destroy() {
        cancelRecording()
    }

    private fun cleanupRecorder() {
        meterJob?.cancel()
        meterJob = null
        runCatching { recorder?.release() }
        recorder = null
        _isRecording.value = false
        _amplitudeLevel.value = 0f
    }
}
