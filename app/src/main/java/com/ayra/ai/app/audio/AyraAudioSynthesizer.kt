package com.ayra.ai.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Real-time software polyphonic audio synthesizer generating chord progressions,
 * arpeggiated melody lines, and sub-bass tailored to each generated song's chords and BPM.
 */
object AyraAudioSynthesizer {

    private var audioTrack: AudioTrack? = null
    private var synthJob: Job? = null
    private var activeAudioManager: AudioManager? = null
    private var activeFocusRequest: AudioFocusRequest? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            stop()
        }
    }

    private fun requestFocus(context: Context?) {
        val ctx = context ?: return
        val am = runCatching { ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull() ?: return
        activeAudioManager = am
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = activeFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                    .also { activeFocusRequest = it }
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        }
    }

    private fun abandonFocus() {
        val am = activeAudioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusListener)
            }
        }
    }

    private val noteFreqMap = mapOf(
        "C" to 261.63, "C#" to 277.18, "Db" to 277.18,
        "D" to 293.66, "D#" to 311.13, "Eb" to 311.13,
        "E" to 329.63,
        "F" to 349.23, "F#" to 369.99, "Gb" to 369.99,
        "G" to 392.00, "G#" to 415.30, "Ab" to 415.30,
        "A" to 440.00, "A#" to 466.16, "Bb" to 466.16,
        "B" to 493.88
    )

    private val defaultProgression = listOf("Am", "F", "C", "G")

    fun isPlaying(): Boolean = audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING

    fun playSynthSong(
        chordsText: String = "Am - F - C - G",
        bpm: Int = 110,
        context: Context? = null,
        onPlaybackStateChange: ((Boolean) -> Unit)? = null
    ) {
        stop()
        requestFocus(context)

        val parsedChords = parseChordProgression(chordsText).ifEmpty { defaultProgression }
        val clampedBpm = bpm.coerceIn(60, 170)
        val beatDurationSec = 60.0 / clampedBpm

        synthJob = scope.launch {
            val sampleRate = 44100
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufSize.coerceAtLeast(8192))
                .build()

            audioTrack = track
            track.play()
            onPlaybackStateChange?.invoke(true)

            try {
                // Play 16 chord bars (~18 seconds) with warm pad + arpeggio + sub-bass
                val stepDurationSec = beatDurationSec * 0.5 // eighth notes
                val stepSamples = (sampleRate * stepDurationSec).toInt().coerceAtLeast(2048)
                val buffer = ShortArray(stepSamples)
                val totalSteps = 48

                for (step in 0 until totalSteps) {
                    if (!isActive) break

                    val chordIndex = (step / 4) % parsedChords.size
                    val chordName = parsedChords[chordIndex]
                    val triad = chordFrequencies(chordName)
                    val arpeggioNote = triad[step % triad.size] * if (step % 4 == 3) 2.0 else 1.0
                    val rootBass = triad[0] / 2.0
                    val fifthPad = triad[2]

                    for (i in 0 until stepSamples) {
                        val t = i.toDouble() / sampleRate
                        val norm = i.toDouble() / stepSamples
                        val arpEnv = exp(-3.5 * norm)
                        val padEnv = if (norm < 0.08) norm / 0.08 else 1.0 - 0.15 * norm

                        val melody = 0.42 * sin(2.0 * PI * arpeggioNote * t) * arpEnv
                        val harmony = 0.25 * (sin(2.0 * PI * triad[1] * t) + sin(2.0 * PI * fifthPad * t)) * 0.5 * padEnv
                        val bass = 0.28 * sin(2.0 * PI * rootBass * t) * padEnv
                        val overtone = 0.08 * sin(2.0 * PI * (arpeggioNote * 2.0) * t) * arpEnv

                        val mixed = (melody + harmony + bass + overtone).coerceIn(-0.98, 0.98)
                        buffer[i] = (mixed * Short.MAX_VALUE * 0.72).toInt().toShort()
                    }

                    track.write(buffer, 0, buffer.size)
                }
            } catch (_: Exception) {
            } finally {
                runCatching {
                    track.stop()
                    track.release()
                }
                audioTrack = null
                abandonFocus()
                onPlaybackStateChange?.invoke(false)
            }
        }
    }

    fun playSynthSong(onPlaybackStateChange: ((Boolean) -> Unit)? = null) {
        playSynthSong(chordsText = "Am - F - C - G", bpm = 110, context = null, onPlaybackStateChange = onPlaybackStateChange)
    }

    fun stop() {
        synthJob?.cancel()
        synthJob = null
        runCatching {
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null
        abandonFocus()
    }

    internal fun parseChordProgression(chordsText: String): List<String> {
        val tokens = chordsText.split(Regex("[\\s,|\\-/]+"))
            .map { it.trim() }
            .filter { it.matches(Regex("^[A-G][#b]?(m|maj|min|dim|sus[24]?|7|m7|maj7)?$", RegexOption.IGNORE_CASE)) }
        return tokens.ifEmpty { defaultProgression }
    }

    private fun chordFrequencies(chord: String): DoubleArray {
        val clean = chord.trim()
        val rootKey = if (clean.length >= 2 && (clean[1] == '#' || clean[1] == 'b')) {
            clean.substring(0, 2).replaceFirstChar { it.uppercase() }
        } else {
            clean.take(1).uppercase()
        }
        val root = noteFreqMap[rootKey] ?: 261.63
        val isMinor = clean.contains("m", ignoreCase = true) && !clean.contains("maj", ignoreCase = true)
        val thirdRatio = if (isMinor) 1.189207 else 1.259921 // 3 or 4 semitones
        val fifthRatio = 1.498307 // 7 semitones
        return doubleArrayOf(root, root * thirdRatio, root * fifthRatio)
    }

    /**
     * Renders the polyphonic chord progression + arpeggio + sub-bass to a real
     * 16-bit PCM 44.1 kHz mono RIFF/WAVE file stored in filesDir/generated_audio/.
     */
    fun exportSynthToWav(
        context: android.content.Context,
        title: String,
        chordsText: String = "Am - F - C - G",
        bpm: Int = 110
    ): java.io.File {
        val parsedChords = parseChordProgression(chordsText).ifEmpty { defaultProgression }
        val clampedBpm = bpm.coerceIn(60, 170)
        val beatDurationSec = 60.0 / clampedBpm
        val sampleRate = 44100
        val stepDurationSec = beatDurationSec * 0.5
        val stepSamples = (sampleRate * stepDurationSec).toInt().coerceAtLeast(2048)
        val totalSteps = 24
        val totalSamples = stepSamples * totalSteps
        val dataByteCount = totalSamples * 2

        val dir = java.io.File(context.filesDir, "generated_audio").apply { if (!exists()) mkdirs() }
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32).ifBlank { "ghost_track" }
        val outFile = java.io.File(dir, "${safeTitle}_${System.currentTimeMillis()}.wav")

        java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(outFile))).use { dos ->
            fun writeLittleEndianInt(v: Int) {
                dos.writeByte(v and 0xFF)
                dos.writeByte((v shr 8) and 0xFF)
                dos.writeByte((v shr 16) and 0xFF)
                dos.writeByte((v shr 24) and 0xFF)
            }
            fun writeLittleEndianShort(v: Int) {
                dos.writeByte(v and 0xFF)
                dos.writeByte((v shr 8) and 0xFF)
            }

            // RIFF Header
            dos.writeBytes("RIFF")
            writeLittleEndianInt(36 + dataByteCount)
            dos.writeBytes("WAVE")
            // fmt chunk
            dos.writeBytes("fmt ")
            writeLittleEndianInt(16) // PCM chunk size
            writeLittleEndianShort(1) // PCM format
            writeLittleEndianShort(1) // Mono
            writeLittleEndianInt(sampleRate)
            writeLittleEndianInt(sampleRate * 2) // Byte rate
            writeLittleEndianShort(2) // Block align
            writeLittleEndianShort(16) // Bits per sample
            // data chunk
            dos.writeBytes("data")
            writeLittleEndianInt(dataByteCount)

            for (step in 0 until totalSteps) {
                val chordIndex = (step / 4) % parsedChords.size
                val triad = chordFrequencies(parsedChords[chordIndex])
                val arpeggioNote = triad[step % triad.size] * if (step % 4 == 3) 2.0 else 1.0
                val rootBass = triad[0] / 2.0
                val fifthPad = triad[2]

                for (i in 0 until stepSamples) {
                    val t = i.toDouble() / sampleRate
                    val norm = i.toDouble() / stepSamples
                    val arpEnv = exp(-3.5 * norm)
                    val padEnv = if (norm < 0.08) norm / 0.08 else 1.0 - 0.15 * norm

                    val melody = 0.42 * sin(2.0 * PI * arpeggioNote * t) * arpEnv
                    val harmony = 0.25 * (sin(2.0 * PI * triad[1] * t) + sin(2.0 * PI * fifthPad * t)) * 0.5 * padEnv
                    val bass = 0.28 * sin(2.0 * PI * rootBass * t) * padEnv
                    val overtone = 0.08 * sin(2.0 * PI * (arpeggioNote * 2.0) * t) * arpEnv

                    val mixed = (melody + harmony + bass + overtone).coerceIn(-0.98, 0.98)
                    val pcm = (mixed * Short.MAX_VALUE * 0.72).toInt().toShort().toInt()
                    writeLittleEndianShort(pcm)
                }
            }
        }
        return outFile
    }
}
