package com.ayra.ai.app

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.AiModelTier
import com.ayra.ai.app.ai.UnifiedAiEngine
import com.ayra.ai.app.ai.WorkspaceContextBuilder
import com.ayra.ai.app.ai.providers.AiRoutingMode
import com.ayra.ai.app.ai.providers.ModelTier
import com.ayra.ai.app.automation.AyraTaskScheduler
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatSession
import com.ayra.ai.app.voice.VoiceAssistantManager
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Phase1StabilityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ayra_user_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun resolveMainSrcDir(): File =
        File("src/main").takeIf { it.exists() } ?: File("app/src/main")

    @Test
    fun singleOkHttpFactoryInMainSource() {
        val javaRoot = File(resolveMainSrcDir(), "java")
        assertTrue("Expected java source dir", javaRoot.exists())

        val violations = mutableListOf<String>()
        val directOkHttpRegex = Regex("\\bOkHttpClient(?:\\.Builder)?\\s*\\(")
        javaRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                if (file.name == "NetworkSecurityHelper.kt") return@forEach
                file.readLines().forEachIndexed { idx, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                    if (directOkHttpRegex.containsMatchIn(line)) {
                        violations.add("${file.name}:${idx + 1}: $trimmed")
                    }
                }
            }

        assertTrue(
            "Found stray OkHttpClient() / OkHttpClient.Builder() calls outside NetworkSecurityHelper:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun composeScreensUseLifecycleAwareCollectionAndKeyedLazyColumns() {
        val uiRoot = File(resolveMainSrcDir(), "java/com/ayra/ai/app/ui")
        assertTrue("Expected ui source dir", uiRoot.exists())

        val collectViolations = mutableListOf<String>()
        val unkeyedItemsViolations = mutableListOf<String>()
        val unkeyedItemsRegex = Regex("\\bitems\\s*\\((?![^)]*\\bkey\\s*=)")

        uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { idx, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                    if (line.contains(".collectAsState(")) {
                        collectViolations.add("${file.name}:${idx + 1}: $trimmed")
                    }
                    if (unkeyedItemsRegex.containsMatchIn(line)) {
                        unkeyedItemsViolations.add("${file.name}:${idx + 1}: $trimmed")
                    }
                }
            }

        assertTrue(
            "Found non-lifecycle collectAsState calls in UI:\n" + collectViolations.joinToString("\n"),
            collectViolations.isEmpty()
        )
        assertTrue(
            "Found unkeyed LazyColumn items() calls in UI:\n" + unkeyedItemsViolations.joinToString("\n"),
            unkeyedItemsViolations.isEmpty()
        )
    }

    @Test
    fun manifestDeclaresExactAlarmsAndOmitsUnusedWifiPermission() {
        val manifestFile = File(resolveMainSrcDir(), "AndroidManifest.xml")
        assertTrue(manifestFile.exists())
        val xml = manifestFile.readText()
        assertTrue(
            "Expected SCHEDULE_EXACT_ALARM permission in AndroidManifest.xml",
            xml.contains("android.permission.SCHEDULE_EXACT_ALARM")
        )
        assertFalse(
            "Unused ACCESS_WIFI_STATE permission should be removed",
            xml.contains("android.permission.ACCESS_WIFI_STATE")
        )
    }

    @Test
    fun modelTierIsSingleSourceOfTruthAcrossEnginesAndLegacyAliases() {
        val aliasPairs = mapOf(
            "security_research" to ModelTier.SECURITY_RESEARCH,
            "open_weights" to ModelTier.OPEN_WEIGHTS,
            "local" to ModelTier.OPEN_WEIGHTS,
            "balanced" to ModelTier.AUTO,
            "fast" to ModelTier.GEMINI_FLASH,
            "quality" to ModelTier.GEMINI_PRO,
            "unknown_tier_xyz" to ModelTier.AUTO
        )

        aliasPairs.forEach { (raw, expected) ->
            assertEquals("ModelTier.fromId($raw)", expected, ModelTier.fromId(raw))
            assertEquals("AiModelTier round-trip($raw)", expected, AiModelTier.fromModelTier(expected).toModelTier())
        }

        // Legacy unsafe tier strings must be migrated in AyraPreferences cleanly
        AyraPreferences.saveModelTier(context, "open_weights")
        context.getSharedPreferences("ayra_ai_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("selected_model_tier", "godmode").commit()
        assertEquals("security_research", AyraPreferences.getSavedModelTier(context))

        assertEquals(AiRoutingMode.OPEN_WEIGHTS, AiRoutingMode.fromId("open_weights"))
        assertEquals(AiRoutingMode.AUTO, AiRoutingMode.fromId("balanced"))

        ModelTier.entries.forEach { canonical ->
            assertEquals(canonical.id, ModelTier.canonicalizeId(canonical.id))
        }
    }

    @Test
    fun defaultPersonaIsNeutralAndCompanionRequires18PlusConfirmationGate() = runTest {
        val freshSession = ChatSession(title = "Fresh Session")
        assertEquals("AI_ASSISTANT", freshSession.personaMode)
        assertEquals("AI_ASSISTANT", UnifiedAiEngine(context).currentPersona)
        assertEquals("AI_ASSISTANT", VoiceAssistantManager(context) {}.personaMode)
        assertEquals("AI_ASSISTANT", AyraPreferences.getSavedPersona(context))
        assertFalse(AyraPreferences.isCompanionAgeVerified(context))

        // Attempting to save GF_COMPANION before 18+ confirmation must fail closed to AI_ASSISTANT
        AyraPreferences.savePersona(context, "GF_COMPANION")
        assertEquals("AI_ASSISTANT", AyraPreferences.getSavedPersona(context))

        val blockedWorkspaceCtx = WorkspaceContextBuilder.resolve(
            context = context,
            requestedPersona = "GF_COMPANION",
            requestedTier = "auto",
            prompt = "Hello"
        )
        assertEquals("AI_ASSISTANT", blockedWorkspaceCtx.effectivePersona)

        // Confirming 18+ unlocks GF_COMPANION cleanly
        AyraPreferences.setCompanionAgeVerified(context, true)
        AyraPreferences.savePersona(context, "GF_COMPANION")
        assertEquals("GF_COMPANION", AyraPreferences.getSavedPersona(context))

        val unlockedWorkspaceCtx = WorkspaceContextBuilder.resolve(
            context = context,
            requestedPersona = "GF_COMPANION",
            requestedTier = "auto",
            prompt = "Hello"
        )
        assertEquals("GF_COMPANION", unlockedWorkspaceCtx.effectivePersona)
    }

    @Test
    fun schedulerReportsExplicitFallbackNoticeWhenExactAlarmsAreDisabled() = runTest {
        val scheduler = AyraTaskScheduler(context)
        val settingsIntent = scheduler.buildExactAlarmSettingsIntent()
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, settingsIntent.action)

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(scheduler.canScheduleExactAlarms())

        val res = scheduler.scheduleTaskFromPrompt("Remind me in 10 minutes to take medicine")
        assertTrue(res.executed)
        val payload = JSONObject(res.extraData.orEmpty())
        assertFalse("Expected isExact=false when permission disabled", payload.getBoolean("isExact"))
        assertTrue("Expected needsExactPermissionPrompt=true", payload.getBoolean("needsExactPermissionPrompt"))
        assertTrue(
            "Expected visible fallback notice in reply",
            res.feedbackMessage.contains("exact alarm", ignoreCase = true) ||
                res.feedbackMessage.contains("approximate", ignoreCase = true)
        )

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(scheduler.canScheduleExactAlarms())
        val exactRes = scheduler.scheduleTaskFromPrompt("Remind me in 10 minutes to call mom")
        val exactPayload = JSONObject(exactRes.extraData.orEmpty())
        assertTrue(exactPayload.getBoolean("isExact"))
        assertFalse(exactPayload.getBoolean("needsExactPermissionPrompt"))
    }

    @Test
    fun brandingMetadataAndStringsMatchAyraAi() {
        val mainDir = resolveMainSrcDir()
        val stringsXml = File(mainDir, "res/values/strings.xml")
        assertTrue("Expected strings.xml", stringsXml.exists())
        val stringsText = stringsXml.readText()
        assertTrue(
            "Expected app_name to be Ayra AI",
            stringsText.contains("<string name=\"app_name\">Ayra AI</string>")
        )

        val repoRoot = if (File("metadata.json").exists()) File(".") else File("..")
        val metadataFile = File(repoRoot, "metadata.json")
        if (metadataFile.exists()) {
            val metaJson = JSONObject(metadataFile.readText())
            assertEquals("Ayra AI", metaJson.getString("name"))
        }
    }

    @Test
    fun chatScreenExposesJustInTimePermissionRationaleAndExactAlarmBanner() {
        val mainDir = resolveMainSrcDir()
        val chatScreenFile = File(mainDir, "java/com/ayra/ai/app/ui/screens/ChatScreen.kt")
        assertTrue("Expected ChatScreen.kt", chatScreenFile.exists())
        val chatComponentsDir = File(mainDir, "java/com/ayra/ai/app/ui/components/chat")
        val combinedSource = buildString {
            append(chatScreenFile.readText())
            chatComponentsDir.listFiles { f -> f.isFile && f.extension == "kt" }?.forEach {
                append('\n').append(it.readText())
            }
        }
        assertTrue(
            "Expected RequestMultiplePermissions for location permissions in ChatScreen.kt",
            combinedSource.contains("RequestMultiplePermissions")
        )
        assertTrue(
            "Expected persistent exact_alarm_warning_banner in Chat UI",
            combinedSource.contains("exact_alarm_warning_banner")
        )
        assertTrue(
            "Expected permission_rationale_confirm dialog in Chat UI",
            combinedSource.contains("permission_rationale_confirm")
        )
    }
}
