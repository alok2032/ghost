package com.ayra.ai.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ayra.ai.app.ai.mcp.McpClientManager
import com.ayra.ai.app.automation.ProposedAction
import com.ayra.ai.app.data.StorageHelper
import com.ayra.ai.app.security.EncryptedVaultStorage
import com.ayra.ai.app.voice.CrisisDetector
import com.ayra.ai.app.voice.VoiceAssistantManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase3CapabilitiesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun exportRealDocxProducesValidEncryptedOoxmlZipArchive() {
        val file = StorageHelper.exportRealDocxDocument(
            context = context,
            title = "Architecture & <Audit>",
            content = "# Executive Summary\nOOXML pipeline verified."
        )
        assertTrue(file.exists())
        assertTrue(EncryptedVaultStorage.isVaultFile(file))

        val zipBytes = EncryptedVaultStorage.decryptVaultFileBytes(context, file)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entries[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        assertTrue(entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("_rels/.rels"))
        assertTrue(entries.containsKey("word/document.xml"))
        val docXml = entries["word/document.xml"].orEmpty()
        assertTrue(docXml.contains("Architecture &amp; &lt;Audit&gt;"))
        assertTrue(docXml.contains("OOXML pipeline verified."))
        file.delete()
    }

    @Test
    fun exportCsvSpreadsheetNeutralizesFormulaInjection() {
        val tableMarkdown = """
            | Metric | Formula |
            | --- | --- |
            | Profit | =CMD('calc') |
            | Growth | +15% |
            | Cost | -400 |
            | Handle | @ayra |
        """.trimIndent()

        val csvFile = StorageHelper.exportCsvSpreadsheet(
            context = context,
            title = "Financial <Metrics>",
            content = tableMarkdown
        )
        assertTrue(csvFile.exists())
        assertTrue(EncryptedVaultStorage.isVaultFile(csvFile))

        val csvText = EncryptedVaultStorage.decryptVaultFileBytes(context, csvFile).toString(Charsets.UTF_8)
        assertTrue(csvText.contains("\"Metric\",\"Formula\""))
        assertTrue("Leading '=' must be neutralized", csvText.contains("\"'=CMD('calc')\""))
        assertTrue("Leading '+' must be neutralized", csvText.contains("\"'+15%\""))
        assertTrue("Leading '-' must be neutralized", csvText.contains("\"'-400\""))
        assertTrue("Leading '@' must be neutralized", csvText.contains("\"'@ayra\""))
        csvFile.delete()
    }

    @Test
    fun crisisDetectorMatchesExactWordSuicideHindiAndRespectsRegion() {
        val singleWord = CrisisDetector.evaluate("suicide")
        assertTrue("Exact word 'suicide' without spaces must trigger", singleWord.isCrisis)

        val hindiDev = CrisisDetector.evaluate("मुझे आत्महत्या के विचार आ रहे हैं")
        assertTrue("Devanagari suicide phrase must trigger", hindiDev.isCrisis)

        val bdResp = CrisisDetector.evaluate("I want to kill myself", CrisisDetector.CrisisRegion.BANGLADESH)
        assertTrue(bdResp.isCrisis)
        assertTrue(bdResp.supportiveMessage.orEmpty().contains("Kaan Pete Roi"))

        val inResp = CrisisDetector.evaluate("I want to kill myself", CrisisDetector.CrisisRegion.INDIA)
        assertTrue(inResp.isCrisis)
        assertTrue(inResp.supportiveMessage.orEmpty().contains("Tele-MANAS"))
    }

    @Test
    fun voiceAssistantDetectsBanglishHindiBengaliAndFormatsActionSummaries() {
        assertEquals("bn-IN", VoiceAssistantManager.detectSpokenLanguageTag("হ্যালো আয়রা"))
        assertEquals("hi-IN", VoiceAssistantManager.detectSpokenLanguageTag("नमस्ते आयरा"))
        assertEquals("bn-IN", VoiceAssistantManager.detectSpokenLanguageTag("amar kaj ta ekhon korbo"))
        assertEquals("en-US", VoiceAssistantManager.detectSpokenLanguageTag("Open settings please"))

        val extSummary = VoiceAssistantManager.buildSpokenActionSummary(
            ProposedAction.ExecuteExternalTool(toolName = "fetch_weather", summary = "Run MCP weather tool")
        )
        assertTrue(extSummary.contains("fetch_weather"))
        assertTrue(VoiceAssistantManager.buildSpokenActionSummary(ProposedAction.ToggleTorch(true)).contains("flashlight on"))
    }

    @Test
    fun mcpClientBlocksUnsafeUrlsAndRevokesServerCleanly() {
        val blockedHttp = McpClientManager.addOrUpdateServer(
            context = context,
            name = "Insecure Remote",
            endpointUrl = "http://mcp.example.com/rpc"
        )
        assertTrue("Cleartext http remote MCP must be rejected", blockedHttp.isFailure)

        val blockedMetadata = McpClientManager.addOrUpdateServer(
            context = context,
            name = "SSRF Metadata",
            endpointUrl = "https://169.254.169.254/latest/meta-data"
        )
        assertTrue("Link-local SSRF MCP target must be rejected", blockedMetadata.isFailure)

        val valid = McpClientManager.addOrUpdateServer(
            context = context,
            name = "Trusted MCP",
            endpointUrl = "https://1.1.1.1/mcp",
            bearerToken = "secret-mcp-tok-123"
        )
        assertTrue("Valid public HTTPS endpoint should register cleanly", valid.isSuccess)
        val server = valid.getOrThrow()
        assertTrue(McpClientManager.listServers(context).any { it.id == server.id })

        McpClientManager.revokeAndRemoveServer(context, server.id)
        assertFalse(McpClientManager.listServers(context).any { it.id == server.id })
    }
}
