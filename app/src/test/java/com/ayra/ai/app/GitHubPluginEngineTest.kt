package com.ayra.ai.app

import com.ayra.ai.app.ai.AyraIntentClassifier
import com.ayra.ai.app.ai.AyraToolIntent
import com.ayra.ai.app.ai.plugins.GitHubPluginEngine
import com.ayra.ai.app.ai.plugins.GitHubPluginRegistry
import com.ayra.ai.app.ai.providers.AiProviderType
import com.ayra.ai.app.ai.providers.AyraStreamEvent
import com.ayra.ai.app.ai.providers.ChatRequest
import com.ayra.ai.app.ai.providers.ExternalAiProviders
import com.ayra.ai.app.ai.providers.ProviderPool
import com.ayra.ai.app.ai.providers.ProviderPoolMode
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

/**
 * Verifies real ChatGPT-Plugin & Claude-Skill/MCP style GitHub repository installation,
 * live OpenAPI & SKILL.md extraction, intent routing, on-demand raw file inspection,
 * SSRF URL protection, and ProviderPool prompt injection.
 */
class GitHubPluginEngineTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        GitHubPluginRegistry.clearForTesting()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        GitHubPluginRegistry.clearForTesting()
        server.shutdown()
    }

    @Test
    fun testParseRepoSpecifierHandlesUrlsSlugsAndIgnoresNonRepos() {
        val fromHttps = GitHubPluginEngine.parseRepoSpecifier("Please install https://github.com/anthropics/skills.git")
        assertNotNull(fromHttps)
        assertEquals("anthropics/skills", fromHttps?.fullName)

        val fromTreeUrl = GitHubPluginEngine.parseRepoSpecifier("https://github.com/obra/superpowers/tree/develop/skills")
        assertEquals("obra/superpowers", fromTreeUrl?.fullName)
        assertEquals("develop", fromTreeUrl?.explicitBranch)

        val fromSlug = GitHubPluginEngine.parseRepoSpecifier("install modelcontextprotocol/servers")
        assertEquals("modelcontextprotocol/servers", fromSlug?.fullName)

        assertNull(GitHubPluginEngine.parseRepoSpecifier("explain ui/ux and http/2 principles"))
        assertNull(GitHubPluginEngine.parseRepoSpecifier("https://example.com/user/repo"))
    }

    @Test
    fun testIntentClassifierRoutesPluginInstallWithoutHijackingGithubQuestions() {
        assertEquals(AyraToolIntent.GITHUB_PLUGIN, AyraIntentClassifier.classifyIntent("/plugin anthropics/skills"))
        assertEquals(AyraToolIntent.GITHUB_PLUGIN, AyraIntentClassifier.classifyIntent("https://github.com/obra/superpowers"))
        assertEquals(
            AyraToolIntent.GITHUB_PLUGIN,
            AyraIntentClassifier.classifyIntent("ei repo install kore dao: https://github.com/public-apis/public-apis")
        )
        assertEquals(
            AyraToolIntent.GITHUB_PLUGIN,
            AyraIntentClassifier.classifyIntent("install modelcontextprotocol/servers as plugin")
        )

        // Educational GitHub questions MUST remain CHAT
        assertEquals(
            AyraToolIntent.CHAT,
            AyraIntentClassifier.classifyIntent("গিটহাব কী এবং প্রোগ্রামাররা কেন এটি ব্যবহার করে?")
        )
        assertEquals(
            AyraToolIntent.CHAT,
            AyraIntentClassifier.classifyIntent("ci cd pipeline github actions diye kivabe set kore?")
        )
        assertEquals(
            AyraToolIntent.CHAT,
            AyraIntentClassifier.classifyIntent("what is github.com/torvalds/linux?")
        )
    }

    @Test
    fun testInstallPluginExtractsSkillPackAndOpenApiEndpointsViaMockWebServer() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when (path) {
                    "/api/repos/acme/weather-plugin" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "name": "weather-plugin",
                          "description": "Real-time weather & forecast OpenAPI + Claude Skill plugin",
                          "default_branch": "main",
                          "stargazers_count": 1420,
                          "language": "TypeScript",
                          "topics": ["openapi", "claude-skill", "weather"]
                        }
                        """.trimIndent()
                    )
                    "/api/repos/acme/weather-plugin/git/trees/main?recursive=1" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "tree": [
                            {"path": "SKILL.md", "type": "blob"},
                            {"path": "openapi.json", "type": "blob"},
                            {"path": "README.md", "type": "blob"},
                            {"path": "src/index.ts", "type": "blob"}
                          ]
                        }
                        """.trimIndent()
                    )
                    "/raw/acme/weather-plugin/main/SKILL.md" -> MockResponse().setResponseCode(200).setBody(
                        "# Weather Skill\nAlways include metric Celsius + wind speed and call `/forecast`."
                    )
                    "/raw/acme/weather-plugin/main/openapi.json" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "openapi": "3.0.1",
                          "servers": [{"url": "https://api.weather.example.com"}],
                          "paths": {
                            "/forecast": {
                              "get": {
                                "summary": "Get 7-day forecast for a city",
                                "parameters": [{"name": "city", "in": "query"}]
                              }
                            }
                          }
                        }
                        """.trimIndent()
                    )
                    "/raw/acme/weather-plugin/main/README.md" -> MockResponse().setResponseCode(200).setBody(
                        "# Weather Plugin\nProvides global forecasts."
                    )
                    "/raw/acme/weather-plugin/main/src/index.ts" -> MockResponse().setResponseCode(200).setBody(
                        "export async function getForecast(city: string) { return fetch(`/forecast?city=${'$'}{city}`); }"
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val engine = GitHubPluginEngine(
            context = null,
            githubApiBaseUrl = server.url("/api").toString(),
            githubRawBaseUrl = server.url("/raw").toString(),
            customHttpClient = OkHttpClient()
        )

        val result = engine.installPlugin("https://github.com/acme/weather-plugin")
        assertTrue("Plugin install should succeed: ${result.errorMessage}", result.success)
        val plugin = result.plugin!!
        assertEquals("acme/weather-plugin", plugin.repoFullName)
        assertEquals("OPENAPI_PLUGIN", plugin.pluginType)
        assertEquals(1420, plugin.stars)
        assertEquals("TypeScript", plugin.primaryLanguage)
        assertTrue(plugin.skillPromptContext.contains("Weather Skill"))
        assertTrue(plugin.skillPromptContext.contains("getForecast"))

        val endpoints = GitHubPluginEngine.parseOpenApiEndpoints(plugin.openApiSpecJson)
        assertEquals(1, endpoints.size)
        assertEquals("/forecast", endpoints.first().path)
        assertEquals(listOf("city"), endpoints.first().queryParams)

        val commands = GitHubPluginEngine.parseCommands(plugin.commandsJson)
        assertTrue(commands.isNotEmpty())

        // Registry toggle & uninstall
        assertEquals(1, GitHubPluginRegistry.getEnabledPlugins(null).size)
        GitHubPluginRegistry.setPluginEnabled(null, "acme/weather-plugin", false)
        assertEquals(0, GitHubPluginRegistry.getEnabledPlugins(null).size)
        GitHubPluginRegistry.setPluginEnabled(null, "acme/weather-plugin", true)
        assertEquals(1, GitHubPluginRegistry.getEnabledPlugins(null).size)
    }

    @Test
    fun testActivePluginInjectionAndLiveOnDemandFileReadInProviderPool() = runBlocking {
        var capturedSystemPrompt = ""

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                if (path == "/raw/obra/superpowers/main/src/debug.py") {
                    return MockResponse().setResponseCode(200).setBody(
                        "def isolate_root_cause(logs):\n    return [line for line in logs if 'ERROR' in line]"
                    )
                }
                val rawBody = request.body.readUtf8()
                capturedSystemPrompt = rawBody
                val resp = JSONObject().put(
                    "candidates",
                    JSONArray().put(
                        JSONObject().put(
                            "content",
                            JSONObject().put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", "Applied plugin workflow cleanly."))
                            )
                        )
                    )
                )
                return MockResponse().setResponseCode(200).setBody(resp.toString())
            }
        }

        val engine = GitHubPluginEngine(
            context = null,
            githubApiBaseUrl = server.url("/api").toString(),
            githubRawBaseUrl = server.url("/raw").toString(),
            customHttpClient = OkHttpClient()
        )

        val installed = com.ayra.ai.app.data.InstalledGitHubPlugin(
            repoFullName = "obra/superpowers",
            repoUrl = "https://github.com/obra/superpowers",
            name = "superpowers",
            owner = "obra",
            description = "Agentic engineering skills",
            defaultBranch = "main",
            pluginType = "SKILL_PACK",
            skillPromptContext = "=== PLUGIN: obra/superpowers ===\nAlways isolate root cause before writing fixes.",
            fileTreeJson = JSONArray(listOf("SKILL.md", "src/debug.py")).toString(),
            isEnabled = true
        )

        val activeCtx = engine.buildActiveContextForPrompt(
            prompt = "@superpowers read debug.py and explain how it filters logs",
            enabledPlugins = listOf(installed)
        )
        assertTrue(activeCtx.systemPromptInjection.contains("isolate_root_cause"))
        assertTrue(activeCtx.liveToolOutput.contains("def isolate_root_cause"))

        val providers = ExternalAiProviders(
            context = null,
            baseUrlOverrides = mapOf(AiProviderType.GEMINI to server.url("/v1beta/").toString()),
            customHttpClient = OkHttpClient(),
            channelKeyOverrides = mapOf("gemini_3_8_flash" to "gem-test")
        )
        val pool = ProviderPool(context = null, externalProviders = providers)
        var completed = ""
        pool.streamChat(
            ChatRequest(
                prompt = "Apply @superpowers",
                mode = ProviderPoolMode.FAST,
                activePluginContext = activeCtx.systemPromptInjection,
                activePluginNames = activeCtx.activePluginNames
            )
        ).collect { ev ->
            if (ev is AyraStreamEvent.Complete) completed = ev.response.text
        }

        assertEquals("Applied plugin workflow cleanly.", completed)
        assertTrue("Model call must include active plugin instructions & live repo file", capturedSystemPrompt.contains("isolate_root_cause"))
    }

    @Test
    fun testSsrfGuardBlocksLocalAndPrivateOpenApiUrls() {
        assertFalse(GitHubPluginEngine.isSafePublicHttpsUrl("http://api.example.com/data"))
        assertFalse(GitHubPluginEngine.isSafePublicHttpsUrl("https://localhost:8080/secret"))
        assertFalse(GitHubPluginEngine.isSafePublicHttpsUrl("https://127.0.0.1/admin"))
        assertFalse(GitHubPluginEngine.isSafePublicHttpsUrl("https://169.254.169.254/latest/meta-data/"))
        assertFalse(GitHubPluginEngine.isSafePublicHttpsUrl("https://192.168.1.50/router"))
        assertFalse(GitHubPluginEngine.isSafePublicHttpsUrl("https://10.0.2.2:11434/api"))
        assertTrue(GitHubPluginEngine.isSafePublicHttpsUrl("https://api.github.com/repos/obra/superpowers"))
    }
}
