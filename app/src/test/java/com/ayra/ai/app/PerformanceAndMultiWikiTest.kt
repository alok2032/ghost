package com.ayra.ai.app

import com.ayra.ai.app.ai.AiMediaPromptSynthesizer
import com.ayra.ai.app.ai.AutonomousKnowledgeEngine
import com.ayra.ai.app.ai.agents.AgentDomain
import com.ayra.ai.app.ai.agents.AyraAgentOrchestrator
import com.ayra.ai.app.ai.higgsfield.OpenHiggsfieldEngine
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies Speed, Quorum Acceleration, Live Consensus Streaming,
 * Multi-Layer Wiki (Surface + Deep Web Academic + Dark Web Technical),
 * Expanded Free AI Specialists, and Complete Media Craft Payloads.
 */
class PerformanceAndMultiWikiTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testEnsembleQuorumFastExitCancelsSlowStragglerAndStreamsTokens() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val rawBody = request.body.readUtf8()
                val json = JSONObject(rawBody)

                if (path.contains("v1beta/models/")) {
                    val isSynthesis = rawBody.contains("Principal Consensus Synthesizer")
                    val text = if (isSynthesis) {
                        "Streamed Consensus Answer: Verified fast quorum output."
                    } else {
                        "Gemini Flash fast draft."
                    }
                    val resp = JSONObject().put(
                        "candidates",
                        JSONArray().put(
                            JSONObject().put(
                                "content",
                                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))
                            )
                        )
                    )
                    return MockResponse().setResponseCode(200).setBody(resp.toString())
                }

                val model = json.optString("model")
                if (model == ExternalAiProviders.NVIDIA_GLM_MODEL) {
                    // Simulate a 4-second stalled upstream channel that must NOT block fast quorum
                    Thread.sleep(4000L)
                }

                val replyJson = JSONObject().put(
                    "choices",
                    JSONArray().put(
                        JSONObject().put(
                            "message",
                            JSONObject()
                                .put("role", "assistant")
                                .put("content", "$model fast draft.")
                        )
                    )
                )
                return MockResponse().setResponseCode(200).setBody(replyJson.toString())
            }
        }

        val providers = ExternalAiProviders(
            context = null,
            baseUrlOverrides = mapOf(
                AiProviderType.GEMINI to server.url("/v1beta/").toString(),
                AiProviderType.NVIDIA_NIM to server.url("/nim/v1/").toString()
            ),
            customHttpClient = OkHttpClient(),
            channelKeyOverrides = mapOf(
                "gemini_3_8_flash" to "gem-key",
                "nvidia_minimax_m3_1" to "nim-minimax",
                "nvidia_glm_5_3" to "nim-glm"
            )
        )

        val pool = ProviderPool(context = null, externalProviders = providers)
        val tokensObserved = mutableListOf<String>()
        var finalText = ""

        val startMs = System.currentTimeMillis()
        pool.streamChat(
            ChatRequest(
                prompt = "Explain how HTTP/2 multiplexing improves mobile API throughput",
                mode = ProviderPoolMode.AUTO
            )
        ).collect { ev ->
            when (ev) {
                is AyraStreamEvent.Token -> tokensObserved.add(ev.text)
                is AyraStreamEvent.Complete -> finalText = ev.response.text
                is AyraStreamEvent.Error -> throw AssertionError("Unexpected error: ${ev.error}")
                else -> {}
            }
        }
        val elapsedMs = System.currentTimeMillis() - startMs

        assertTrue("Quorum early exit should finish well before 4s straggler (took ${elapsedMs}ms)", elapsedMs < 2600L)
        assertTrue("Live consensus synthesis should emit tokens to UI", tokensObserved.isNotEmpty())
        assertEquals("Streamed Consensus Answer: Verified fast quorum output.", finalText)
    }

    @Test
    fun testMultiLayerWikiCombinesSurfaceDeepAcademicAndDarkWebWiki() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/wiki/") -> {
                        val wikiJson = """
                            {
                              "query": {
                                "pages": {
                                  "42": {
                                    "title": "Tor (network)",
                                    "extract": "Tor, short for The Onion Router, is free and open-source software for enabling anonymous communication. It directs Internet traffic through a free, worldwide, volunteer overlay network consisting of more than seven thousand relays."
                                  }
                                }
                              }
                            }
                        """.trimIndent()
                        MockResponse().setResponseCode(200).setBody(wikiJson)
                    }
                    path.startsWith("/ddg/") -> {
                        val ddgJson = """
                            {
                              "Heading": "Onion routing",
                              "AbstractText": "Onion routing is a technique for anonymous communication over a computer network using layered encryption.",
                              "AbstractURL": "https://en.wikipedia.org/wiki/Onion_routing"
                            }
                        """.trimIndent()
                        MockResponse().setResponseCode(200).setBody(ddgJson)
                    }
                    path.startsWith("/openalex/") -> {
                        val oaJson = """
                            {
                              "results": [
                                {
                                  "title": "Tor: The Second-Generation Onion Router",
                                  "publication_year": 2004,
                                  "doi": "10.21236/ada465464",
                                  "abstract_inverted_index": {
                                    "We": [0],
                                    "present": [1],
                                    "a": [2],
                                    "circuit-based": [3],
                                    "low-latency": [4],
                                    "anonymous": [5],
                                    "communication": [6],
                                    "service.": [7]
                                  }
                                }
                              ]
                            }
                        """.trimIndent()
                        MockResponse().setResponseCode(200).setBody(oaJson)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val combined = AutonomousKnowledgeEngine.queryMultiLayerWiki(
            prompt = "Explain Tor onion routing and dark web encryption architecture",
            isBengali = false,
            httpClient = OkHttpClient(),
            wikipediaBaseUrl = server.url("/wiki/").toString(),
            ddgBaseUrl = server.url("/ddg/").toString(),
            openAlexBaseUrl = server.url("/openalex/").toString(),
            arxivBaseUrl = server.url("/arxiv/").toString()
        )

        assertTrue("Should contain Surface Wiki Wikipedia section", combined.contains("[SURFACE WIKI — WIKIPEDIA]"))
        assertTrue("Should contain Wikipedia Tor extract", combined.contains("The Onion Router"))
        assertTrue("Should contain DuckDuckGo Instant Knowledge section", combined.contains("[LIVE INSTANT KNOWLEDGE — DUCKDUCKGO]"))
        assertTrue("Should contain Deep Web Academic Wiki section", combined.contains("[DEEP WEB ACADEMIC WIKI — OPENALEX / ARXIV]"))
        assertTrue("Should reconstruct OpenAlex inverted abstract", combined.contains("circuit-based low-latency anonymous communication service."))
        assertTrue("Should contain Dark Web & Overlay Technical Wiki section", combined.contains("[DARK WEB & OVERLAY NETWORK TECHNICAL WIKI]"))
        assertTrue("Should explain triple-layer circuit mechanics", combined.contains("Guard Relay"))
    }

    @Test
    fun testOpenHiggsfieldEngineProducesCompleteVideoPlayerPayload() {
        val payload = OpenHiggsfieldEngine(context = null).synthesizeVideoCraft("কসমিক সাইবার ড্রোন শহরের ভিডিও বানাও")
        val json = JSONObject(payload.jsonPayload)

        assertTrue("Video JSON must include title for VideoPlayerCard", json.optString("title").isNotBlank())
        assertTrue("Video JSON must include previewUrl for VideoPlayerCard", json.optString("previewUrl").contains("pollinations.ai"))
        assertTrue("Video JSON previewUrl must use fast flux rendering without enhance=true", json.optString("previewUrl").contains("flux") && !json.optString("previewUrl").contains("enhance=true"))
        assertEquals("0:15", json.optString("duration"))
        assertTrue("Video JSON must preserve cameraMotion", json.optString("cameraMotion").isNotBlank())

        val imgUrl = AiMediaPromptSynthesizer.buildImageUrl("futuristic neon bridge", seed = 123456L)
        assertTrue(imgUrl.contains("width=768&height=768"))
        assertFalse(imgUrl.contains("enhance=true"))
    }

    @Test
    fun testFreeAiPoolSpecialistChannelsExpandedAndRanked() {
        val providers = ExternalAiProviders(
            context = null,
            channelKeyOverrides = mapOf(
                "pollinations_free_openai" to "free",
                "pollinations_free_openai_large" to "free",
                "pollinations_free_qwen_coder" to "free",
                "pollinations_free_deepseek" to "free"
            )
        )

        val codingChannels = providers.getActiveChannels(domainName = "CODING_AGENT", hasImage = false)
        assertEquals(4, codingChannels.size)
        assertEquals("pollinations_free_qwen_coder", codingChannels.first().id)

        val mathChannels = providers.getActiveChannels(domainName = "MATHEMATICS_AGENT", hasImage = false)
        assertEquals("pollinations_free_deepseek", mathChannels.first().id)
    }

    @Test
    fun testWikiGroundingClassifierAndPromptStandards() {
        assertFalse(AyraAgentOrchestrator.needsLiveWikiGrounding("hi", AgentDomain.GENERAL_ASSISTANT))
        assertFalse(AyraAgentOrchestrator.needsLiveWikiGrounding("25 * 4", AgentDomain.MATHEMATICS_AGENT))
        assertTrue(AyraAgentOrchestrator.needsLiveWikiGrounding("What is The Hidden Wiki and Tor onion routing?", AgentDomain.CYBER_DEFENSE_AGENT))
        assertTrue(AyraAgentOrchestrator.needsLiveWikiGrounding("কোয়ান্টাম কম্পিউটিং কীভাবে কাজ করে?", AgentDomain.RESEARCH_AGENT))

        val sysPrompt = AyraAgentOrchestrator.buildSystemPrompt(
            domain = AgentDomain.CODING_AGENT,
            isBengali = false,
            isGfMode = false
        )
        assertTrue(sysPrompt.contains("SURFACE WIKI"))
        assertTrue(sysPrompt.contains("PRODUCTION-READY"))
    }
}
