package com.ayra.ai.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Autonomous Voice Announcer for background tasks, reminders, and scheduled call message announcements.
 */
object AyraVoiceAnnouncer {

    fun playAlarmChime(context: Context) {
        try {
            val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val ringtone = RingtoneManager.getRingtone(context.applicationContext, alertUri)
            ringtone?.play()
        } catch (_: Exception) {}
    }

    fun speakAloud(context: Context, textToSpeak: String, onComplete: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        val isBengali = textToSpeak.any { it in '\u0980'..'\u09FF' }
        val targetLocale = if (isBengali) Locale.forLanguageTag("bn-BD") else Locale.US

        var ttsInstance: TextToSpeech? = null
        val handler = Handler(Looper.getMainLooper())
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finishOnce(engine: TextToSpeech?) {
            if (completed.compareAndSet(false, true)) {
                onComplete?.invoke()
            }
            if (engine != null) {
                handler.postDelayed({
                    try {
                        engine.shutdown()
                    } catch (_: Exception) {}
                }, 1000)
            }
        }

        handler.post {
            ttsInstance = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = ttsInstance
                    if (engine == null) {
                        finishOnce(null)
                        return@TextToSpeech
                    }
                    engine.language = targetLocale
                    engine.setPitch(1.05f)
                    engine.setSpeechRate(0.95f)

                    // Route through stream notification or alarm
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    engine.setAudioAttributes(audioAttributes)

                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {
                            finishOnce(engine)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            finishOnce(engine)
                        }
                    })

                    val speakStatus = engine.speak(
                        textToSpeak,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "ayra_scheduled_announcement"
                    )
                    if (speakStatus == TextToSpeech.ERROR) {
                        finishOnce(engine)
                    }
                } else {
                    finishOnce(ttsInstance)
                }
            }
        }
    }
}
