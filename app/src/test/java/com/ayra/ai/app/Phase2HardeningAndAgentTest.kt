package com.ayra.ai.app

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.agent.AgentCitation
import com.ayra.ai.app.ai.agent.AgentEvent
import com.ayra.ai.app.ai.agent.AgentRequest
import com.ayra.ai.app.ai.agent.AgentTool
import com.ayra.ai.app.ai.agent.AgentToolOutput
import com.ayra.ai.app.ai.agent.AgentToolRegistry
import com.ayra.ai.app.ai.agent.DefaultAgentCore
import com.ayra.ai.app.ai.agent.DefaultAgentToolRegistry
import com.ayra.ai.app.ai.agent.StepStatus
import com.ayra.ai.app.ai.agents.AyraAgentOrchestrator
import com.ayra.ai.app.ai.providers.AiModelRegistry
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ModelVerificationStatus
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.automation.ProposedAction
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.data.ChatSession
import com.ayra.ai.app.data.StorageHelper
import com.ayra.ai.app.security.AndroidHardeningHelper
import com.ayra.ai.app.security.DatabaseEncryptionState
import com.ayra.ai.app.security.SecureKeyStore
import com.ayra.ai.app.security.VaultDatabaseKeyManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase2HardeningAndAgentTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ayra_user_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ayra_vault_db_key_meta", Context.MODE_PRIVATE).edit().clear().commit()
        SecureKeyStore.clearAllSecrets(context)
        VaultDatabaseKeyManager.destroyDatabaseKeyMetadata(context)
        AppCheckStatusRegistry.resetForTests()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        SecureKeyStore.clearAllSecrets(context)
        VaultDatabaseKeyManager.destroyDatabaseKeyMetadata(context)
        AppCheckStatusRegistry.resetForTests()
    }

    private fun ensureTestFirebaseApp() {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("1:123456789012:android:phase2test0001")
                    .setApiKey("AIzaSyPhase2LocalTestPlaceholderKey00000")
                    .setProjectId("demo-no-project")
                    .build()
            )
        }
    }

    private fun openAiToolCallJson(vararg calls: Pair<String, JSONObject>): String {
        val toolCalls = JSONArray()
        calls.forEachIndexed { idx, (name, args) ->
            toolCalls.put(
                JSONObject().apply {
                    put("id", "call_$idx")
                    put("type", "function")
                    put(
                        "function",
                        JSONObject().apply {
                            put("name", name)
                            put("arguments", args.toString())
                        }
                    )
                }
            )
        }
        return JSONObject().apply {
            put(
                "choices",
                JSONArray().put(
                    JSONObject().apply {
                        put(
                            "message",
                            JSONObject().apply {
                                put("role", "assistant")
                                put("content", "")
                                put("tool_calls", toolCalls)
                            }
                        )
                    }
                )
            )
        }.toString()
    }

    private fun openAiTextJson(content: String): String {
        return JSONObject().apply {
            put(
                "choices",
                JSONArray().put(
                    JSONObject().apply {
                        put(
                            "message",
                            JSONObject().apply {
                                put("role", "assistant")
                                put("content", content)
                            }
                        )
                    }
                )
            )
        }.toString()
    }

    @Test
    fun agentConfirmationToolRemainsPendingAndNeverClaimsExecution() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                openAiToolCallJson("alarm_set" to JSONObject().put("hour", 6).put("minute", 45).put("label", "Morning Run"))
            )
        )

        val ext = ExternalAiProviders(
            context = context,
            baseUrlOverrides = mapOf(AiProviderType.OPENAI to server.url("/").toString()),
            customHttpClient = OkHttpClient(),
            channelKeyOverrides = mapOf(AiProviderType.OPENAI.id to "sk-phase2-test-key")
        )
        val pool = ProviderPool(context, ext)
        val agent = DefaultAgentCore(context, pool, ext, DefaultAgentToolRegistry(context))

        val events = agent.run(AgentRequest(prompt = "Set an alarm for 6:45 AM", tier = "agent")).toList()

        val proposed = events.filterIsInstance<AgentEvent.ToolCallProposed>().firstOrNull()
        assertNotNull("Expected ToolCallProposed event for confirmation-required alarm_set", proposed)
        assertEquals("alarm_set", proposed!!.toolName)
        assertTrue(proposed.action is ProposedAction.SetAlarm)

        val completeEvents = events.filterIsInstance<AgentEvent.Complete>()
        assertTrue("Expected no Complete event before user confirmation", completeEvents.isEmpty())

        val lastPlan = events.filterIsInstance<AgentEvent.PlanUpdated>().last().steps
        val alarmStep = lastPlan.firstOrNull { it.toolName == "alarm_set" }
        assertNotNull(alarmStep)
        assertEquals("Confirmation-required step must stay PENDING", StepStatus.PENDING, alarmStep!!.status)
    }

    @Test
    fun agentExecutesReadOnlyToolsInBoundedParallelAndPropagatesCancellation() = runBlocking {
        val activeConcurrency = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)

        fun readOnlyTool(toolName: String, reply: String) = object : AgentTool {
            override val name = toolName
            override val description = "Read-only $toolName"
            override val parametersSchemaJson = """{"type":"object","properties":{}}"""
            override val isExternal = false
            override val requiresConfirmation = false
            override val isReadOnly = true
            override suspend fun execute(args: JSONObject): AgentToolOutput {
                val now = activeConcurrency.incrementAndGet()
                maxObservedConcurrency.updateAndGet { prev -> maxOf(prev, now) }
                delay(80)
                activeConcurrency.decrementAndGet()
                return AgentToolOutput(
                    success = true,
                    output = reply,
                    citations = listOf(AgentCitation(toolName, "https://example.org/$toolName"))
                )
            }
        }

        val cancelingTool = object : AgentTool {
            override val name = "cancel_tool"
            override val description = "Throws CancellationException"
            override val parametersSchemaJson = """{"type":"object","properties":{}}"""
            override val isExternal = false
            override val requiresConfirmation = false
            override val isReadOnly = true
            override suspend fun execute(args: JSONObject): AgentToolOutput {
                throw CancellationException("caller-cancelled")
            }
        }

        val customRegistry = object : AgentToolRegistry {
            private val map = listOf(
                readOnlyTool("probe_alpha", "alpha-verified"),
                readOnlyTool("probe_beta", "beta-verified"),
                cancelingTool
            ).associateBy { it.name }

            override fun listTools(): List<AgentTool> = map.values.toList()
            override fun getTool(name: String): AgentTool? = map[name]
            override fun toOpenAiToolsJsonArray(localOnlyMode: Boolean): JSONArray = JSONArray()
        }

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                openAiToolCallJson(
                    "probe_alpha" to JSONObject(),
                    "probe_beta" to JSONObject()
                )
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                openAiTextJson("""{"done":true,"answer":"Combined result from probe_alpha and probe_beta"}""")
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                openAiTextJson("Combined result with https://example.org/probe_alpha")
            )
        )

        val ext = ExternalAiProviders(
            context = context,
            baseUrlOverrides = mapOf(AiProviderType.OPENAI to server.url("/").toString()),
            customHttpClient = OkHttpClient(),
            channelKeyOverrides = mapOf(AiProviderType.OPENAI.id to "sk-phase2-test-key")
        )
        val pool = ProviderPool(context, ext)
        val agent = DefaultAgentCore(context, pool, ext, customRegistry)

        val events = agent.run(AgentRequest(prompt = "Run probe alpha and probe beta", tier = "agent")).toList()
        val complete = events.filterIsInstance<AgentEvent.Complete>().singleOrNull()
        assertNotNull(complete)
        assertEquals(2, complete!!.usedTools.size)
        assertTrue(complete.usedTools.containsAll(listOf("probe_alpha", "probe_beta")))
        assertEquals("Expected parallel execution overlap == 2", 2, maxObservedConcurrency.get())

        // Verify CancellationException is rethrown cleanly (never swallowed into ToolResult(false, ...))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                openAiToolCallJson("cancel_tool" to JSONObject())
            )
        )
        var cancellationPropagated = false
        try {
            agent.run(AgentRequest(prompt = "Trigger cancel tool", tier = "agent")).toList()
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }
        assertTrue("Expected CancellationException to propagate", cancellationPropagated)
    }

    @Test
    fun verifiedConfidenceIsNullWithoutEvidenceAndBoundedWithEvidence() {
        val (unverifiedScore, unverifiedLabel) = AyraAgentOrchestrator.computeVerifiedConfidence(
            verifiedCitationsCount = 0,
            completedToolSteps = 0,
            failedToolSteps = 0,
            quorumProvidersCount = 1
        )
        assertNull(unverifiedScore)
        assertTrue(unverifiedLabel.startsWith("Unverified"))

        val (highScore, highLabel) = AyraAgentOrchestrator.computeVerifiedConfidence(
            verifiedCitationsCount = 3,
            completedToolSteps = 2,
            failedToolSteps = 0,
            quorumProvidersCount = 2
        )
        assertNotNull(highScore)
        assertTrue(highScore!! in 0.80f..0.96f)
        assertTrue(highLabel.startsWith("High"))
    }

    @Test
    fun appCheckReportsReleaseRecaptchaMissingConfigAndDebugActiveAccurately() {
        ensureTestFirebaseApp()

        // 1. Default release path reads configured recaptcha_site_key from firebase_applet_config.xml
        configureAppCheck(context, intent = null, isDebugBuild = false)
        assertEquals(AppCheckState.RELEASE_RECAPTCHA_ACTIVE, AppCheckStatusRegistry.currentState)
        assertTrue(AppCheckStatusRegistry.isReadyForVerifiedCloudCalls())

        // 2. When recaptcha_site_key is absent/blank in a misconfigured release, fail-safe state records MISSING_RELEASE_CONFIG
        configureAppCheck(context, intent = null, isDebugBuild = false, recaptchaSiteKeyOverride = "")
        assertEquals(AppCheckState.MISSING_RELEASE_CONFIG, AppCheckStatusRegistry.currentState)
        assertFalse(AppCheckStatusRegistry.isReadyForVerifiedCloudCalls())

        // 3. Debug builds install DebugAppCheckProviderFactory cleanly
        configureAppCheck(context, intent = null, isDebugBuild = true)
        assertEquals(AppCheckState.DEBUG_ACTIVE, AppCheckStatusRegistry.currentState)
        assertTrue(AppCheckStatusRegistry.isReadyForVerifiedCloudCalls())
    }

    @Test
    fun vaultDatabaseKeyManagerFailsClosedOnCorruptedEnvelope() {
        val prefs = context.getSharedPreferences("ayra_vault_db_key_meta", Context.MODE_PRIVATE)
        prefs.edit().putString("wrapped_db_passphrase_v1", "{corrupt_not_valid_cipher_envelope}").commit()

        var threw = false
        try {
            VaultDatabaseKeyManager.getOrCreateDatabasePassphrase(context)
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue("Expected IllegalStateException on corrupted wrapped DB KEK", threw)
        assertEquals(DatabaseEncryptionState.ERROR, VaultDatabaseKeyManager.currentEncryptionState)
    }

    @Test
    fun safeBackupJsonExcludesSecretsAndRoundTripsSessionsAndMessages() {
        assertTrue(SecureKeyStore.putSecret(context, SecureKeyStore.KEY_OPENAI, "sk-super-secret-do-not-export-12345"))
        assertTrue(SecureKeyStore.putSecret(context, SecureKeyStore.KEY_GEMINI, "AIzaSySecretKeyNeverExported999999"))

        val json = StorageHelper.exportSafeWorkspaceBackupJson(
            context = context,
            sessions = listOf(ChatSession(id = 7L, title = "Security Audit", personaMode = "AI_ASSISTANT", modelTier = "auto")),
            messages = listOf(ChatMessage(id = 42L, sessionId = 7L, role = "user", content = "Audit backup payload", timestamp = 12345L)),
            plugins = emptyList()
        )

        assertFalse("Secret OpenAI key leaked into export", json.contains("sk-super-secret-do-not-export"))
        assertFalse("Secret Gemini key leaked into export", json.contains("AIzaSySecretKeyNeverExported"))

        val imported = StorageHelper.importSafeWorkspaceBackupJson(context, json)
        assertEquals(1, imported.sessions.size)
        assertEquals("Security Audit", imported.sessions.first().title)
        assertEquals(1, imported.messages.size)
        assertEquals("Audit backup payload", imported.messages.first().content)
    }

    @Test
    fun clipboardHelperFlagsSensitiveClipsAndReadsPlainText() {
        AndroidHardeningHelper.copyWithSensitivityProtection(
            context = context,
            label = "Phase2 Test Token",
            text = "verified-token-value",
            isSensitive = true,
            autoClearMs = 0L
        )
        assertEquals("verified-token-value", AndroidHardeningHelper.readPlainTextFromClipboard(context))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val extras = cm.primaryClipDescription?.extras
            assertTrue(extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true)
        }
    }

    @Test
    fun modelRegistrySurfacesUnverifiedCatalogEntriesHonestly() {
        AiModelRegistry.refreshConfigurationStates(context)
        val entries = AiModelRegistry.entries.value
        assertTrue(entries.isNotEmpty())

        val experimental = entries.filter { it.verificationStatus == ModelVerificationStatus.UNVERIFIED }
        assertTrue("Expected experimental/unverified NIM models to stay visibly labeled UNVERIFIED", experimental.isNotEmpty())
    }

    @Test
    fun phase2ModularizationKeptFilesCompact() {
        val mainDir = File("src/main/java/com/ayra/ai/app").takeIf { it.exists() }
            ?: File("app/src/main/java/com/ayra/ai/app")
        assertTrue(mainDir.exists())

        val controlSheet = File(mainDir, "ui/components/ControlCenterSheet.kt")
        val researchScreen = File(mainDir, "ui/screens/ResearchDocsScreen.kt")
        val studiosScreen = File(mainDir, "ui/screens/StudiosScreen.kt")
        val storageHelper = File(mainDir, "data/StorageHelper.kt")
        val chatScreen = File(mainDir, "ui/screens/ChatScreen.kt")

        assertTrue(controlSheet.readLines().size < 300)
        assertTrue(researchScreen.readLines().size < 200)
        assertFalse("Standalone StudiosScreen should remain removed", studiosScreen.exists())
        assertTrue(storageHelper.readLines().size < 300)
        assertTrue("Expected ChatScreen.kt < 800 lines after modularization", chatScreen.readLines().size < 800)
    }
}
