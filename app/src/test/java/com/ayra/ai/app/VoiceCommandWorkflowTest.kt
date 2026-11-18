package com.ayra.ai.app

import android.content.Context
import android.os.Bundle
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.voice.VoiceAssistantManager
import com.ayra.ai.app.voice.VoiceUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceCommandWorkflowTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun rmsNormalizationClampsAndScalesAcousticDecibels() {
        assertEquals(0f, VoiceAssistantManager.normalizeRmsDb(-2f), 0.001f)
        assertEquals(0f, VoiceAssistantManager.normalizeRmsDb(-15f), 0.001f)
        assertEquals(0.5f, VoiceAssistantManager.normalizeRmsDb(4f), 0.001f)
        assertEquals(1f, VoiceAssistantManager.normalizeRmsDb(10f), 0.001f)
        assertEquals(1f, VoiceAssistantManager.normalizeRmsDb(25f), 0.001f)
        assertEquals(0f, VoiceAssistantManager.normalizeRmsDb(Float.NaN), 0.001f)
    }

    @Test
    fun partialAndFinalResultsStreamLiveTextAndTriggerAiCommand() {
        val dispatchedCommands = mutableListOf<String>()
        val transcriptionEvents = mutableListOf<Pair<String, Boolean>>()
        val manager = VoiceAssistantManager(context) { cmd ->
            dispatchedCommands.add(cmd)
        }.apply {
            onSpeechTranscribed = { text, isFinal ->
                transcriptionEvents.add(text to isFinal)
            }
        }

        // Simulate manual voice session ready & receiving acoustic RMS + partial text
        manager.isManualSession = true
        manager.onReadyForSpeech(Bundle())
        assertTrue(manager.voiceState.value is VoiceUiState.Listening)

        manager.onRmsChanged(7f)
        assertTrue(manager.rmsLevel.value > 0.7f)

        val partialBundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Generate a 4K"))
        }
        manager.onPartialResults(partialBundle)

        assertEquals("Generate a 4K", manager.liveSpokenText.value)
        assertTrue(dispatchedCommands.isEmpty())
        assertEquals(listOf("Generate a 4K" to false), transcriptionEvents)

        // Final result completes speech-to-text and dispatches to AI inference callback
        val finalBundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Generate a 4K cyber dragon"))
        }
        manager.onResults(finalBundle)

        assertEquals(listOf("Generate a 4K cyber dragon"), dispatchedCommands)
        assertEquals("Generate a 4K cyber dragon" to true, transcriptionEvents.last())
        assertEquals(VoiceUiState.Idle, manager.voiceState.value)
        assertFalse(manager.isListening.value)
        manager.destroy()
    }

    @Test
    fun cancelListeningClearsStateWithoutTriggeringAiInference() {
        val dispatchedCommands = mutableListOf<String>()
        val manager = VoiceAssistantManager(context) { cmd ->
            dispatchedCommands.add(cmd)
        }

        manager.isManualSession = true
        manager.onReadyForSpeech(Bundle())
        manager.onRmsChanged(6f)
        manager.onPartialResults(
            Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Half spoken prompt"))
            }
        )

        manager.cancelListening()

        assertTrue(dispatchedCommands.isEmpty())
        assertEquals("", manager.liveSpokenText.value)
        assertEquals(0f, manager.rmsLevel.value, 0.001f)
        assertEquals(VoiceUiState.Idle, manager.voiceState.value)
        assertFalse(manager.isManualSession)
        manager.destroy()
    }

    @Test
    fun wakeWordModeFiltersAmbientSpeechAndStripsPrefixOnCommand() {
        val dispatchedCommands = mutableListOf<String>()
        val manager = VoiceAssistantManager(context) { cmd ->
            dispatchedCommands.add(cmd)
        }
        manager.isManualSession = false

        // Ambient speech without wake word -> ignored
        manager.onResults(
            Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("What is for dinner tonight"))
            }
        )
        assertTrue(dispatchedCommands.isEmpty())

        // Primary Ayra wake word + command -> strips prefix & invokes AI command
        manager.onResults(
            Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Hey Ayra explain quantum tunneling"))
            }
        )
        // Legacy Ghost alias + command -> remains compatible
        manager.onResults(
            Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Hey Ghost open calculator"))
            }
        )
        assertEquals(listOf("explain quantum tunneling", "open calculator"), dispatchedCommands)
        manager.destroy()
    }

    @Test
    fun recognitionErrorsMapToActionableUserMessages() {
        val manager = VoiceAssistantManager(context) {}
        manager.isManualSession = true
        manager.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)

        val state = manager.voiceState.value
        assertTrue(state is VoiceUiState.Error)
        assertTrue((state as VoiceUiState.Error).message.contains("permission", ignoreCase = true))

        manager.dismissVoiceError()
        assertEquals(VoiceUiState.Idle, manager.voiceState.value)
        manager.destroy()
    }
}
