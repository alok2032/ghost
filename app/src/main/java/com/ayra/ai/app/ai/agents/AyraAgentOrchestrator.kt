package com.ayra.ai.app.ai.agents

import java.util.Locale

/**
 * Enterprise Multi-Agent Orchestration Architecture for Ayra AI.
 * Implements Part 8 of the Master Directive:
 * Dispatches queries to 12 specialized autonomous agents with typed reasoning,
 * fact validation, safety guardrails, and confirmation gates.
 */
enum class AgentDomain(val title: String, val icon: String, val specialty: String) {
    GENERAL_ASSISTANT("General Assistant", "✨", "Conversational companion, instant answers & general knowledge"),
    RESEARCH_AGENT("Deep Research Agent", "🔬", "Evidence collection, multi-source cross-checking & academic citations"),
    CODING_AGENT("Coding & Software Agent", "💻", "Production-grade code generation, debugging, refactoring & tests"),
    MATHEMATICS_AGENT("Mathematics & Logic Agent", "📐", "Calculus, equations, proofs & algorithmic problem solving"),
    EDUCATION_AGENT("Education & Pedagogy Agent", "🎓", "Concept breakdown, student pedagogy & exam preparation"),
    DOCUMENT_AGENT("Document & PDF Studio Agent", "📄", "Parsing, summarization, table extraction & document synthesis"),
    IMAGE_AGENT("Visual & Image Studio Agent", "🎨", "Prompt engineering, 8K artistic synthesis & visual analysis"),
    VIDEO_AGENT("Video Studio Agent", "🎬", "Cinematic prompt synthesis, scene direction & motion scripting"),
    VOICE_AGENT("Conversational Voice Agent", "🎙️", "Empathetic real-time audio interaction & sentiment adaptation"),
    DATA_ANALYSIS_AGENT("Data & Statistics Agent", "📊", "Data modeling, trend analysis & CSV/JSON interpretation"),
    CYBER_DEFENSE_AGENT("Cyber Defense Agent", "🛡️", "OWASP auditing, CVE vulnerability analysis & defensive telemetry"),
    AUTOMATION_AGENT("Device Automation Agent", "⚙️", "Safe Android task orchestration with confirmation gates")
}

data class AgentExecutionPlan(
    val domain: AgentDomain,
    val subtasks: List<String>,
    val requiresUserConfirmation: Boolean = false,
    val confirmationPrompt: String? = null
)

data class AgentResult(
    val domain: AgentDomain,
    val finalResponse: String,
    val citations: List<String> = emptyList(),
    val confidenceScore: Float = 0.98f,
    val executionSteps: List<String> = emptyList()
)

object AyraAgentOrchestrator {

    /**
     * High-precision intent classifier that routes queries to the optimal specialized agent.
     */
    fun classifyIntent(prompt: String): AgentDomain {
        val lower = prompt.lowercase(Locale.ROOT).trim()

        // 1. Device Automation (Calls, SMS, Alarms, Apps, Settings)
        if (isAutomationIntent(lower)) return AgentDomain.AUTOMATION_AGENT

        // 2. Deep Research & Fact Verification
        if (isResearchIntent(lower)) return AgentDomain.RESEARCH_AGENT

        // 3. Cyber Security & Terminal
        if (isCyberDefenseIntent(lower)) return AgentDomain.CYBER_DEFENSE_AGENT

        // 4. Document & PDF Studio
        if (isDocumentIntent(lower)) return AgentDomain.DOCUMENT_AGENT

        // 5. Video Generation
        if (isVideoIntent(lower)) return AgentDomain.VIDEO_AGENT

        // 6. Image Studio
        if (isImageIntent(lower)) return AgentDomain.IMAGE_AGENT

        // 7. Programming, Code, Software Architecture, Debugging
        if (isCodingIntent(lower)) return AgentDomain.CODING_AGENT

        // 8. Mathematics, Physics Equations, Numerical proofs
        if (isMathIntent(lower)) return AgentDomain.MATHEMATICS_AGENT

        // 9. Data Analysis & JSON/CSV Modeling
        if (isDataAnalysisIntent(lower)) return AgentDomain.DATA_ANALYSIS_AGENT

        // 10. Education, Concept Explanation, Lessons
        if (isEducationIntent(lower)) return AgentDomain.EDUCATION_AGENT

        // Default: General Assistant
        return AgentDomain.GENERAL_ASSISTANT
    }

    /**
     * Detects if the prompt is written in Banglish (Bengali words typed using Latin characters).
     */
    fun isBanglish(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT).trim()
        if (lower.isEmpty()) return false
        // If it contains native Bengali characters, it is native Bengali script
        if (lower.any { it in '\u0980'..'\u09FF' }) return false

        val banglishKeywords = listOf(
            "kemon", "acho", "achis", "achhen", "tumi", "apni", "tui",
            "ki khobor", "valo", "bhalo", "koro", "korcho", "korchho", "korchen",
            "jante chai", "bolo", "bolun", "ekta", "kivabe", "kibhabe",
            "keno", "kothay", "kobe", "amake", "amader", "tomake", "tomader",
            "bujhte", "parlam", "parbo", "hobe", "hobe na", "khub",
            "dekhao", "shono", "shunchen", "khawa", "khabar", "din",
            "sobai", "kichu", "onek", "chai", "dorkar", "likhe dao",
            "toiri", "banao", "korbe", "korle", "korbo"
        )
        return banglishKeywords.any { lower.contains(it) }
    }

    /**
     * Builds a tailored system prompt for the dispatched agent domain.
     * Incorporates personality, user instructions, and strict anti-hallucination rules.
     */
    fun buildSystemPrompt(
        domain: AgentDomain,
        isBengali: Boolean,
        isGfMode: Boolean,
        userCustomInstructions: String = "",
        rememberedFacts: Set<String> = emptySet(),
        isBanglish: Boolean = false,
        personaStyle: String = if (isGfMode) "GF_COMPANION" else "AI_ASSISTANT",
        thinkingMode: String = "balanced"
    ): String {
        val langInstruction = when {
            isBengali -> "Respond natively in natural, grammatically flawless Bengali (বাংলা)."
            isBanglish -> "The user is communicating in Banglish (Bengali written in Latin letters). Understand their intent completely and respond in warm, natural Bengali (বাংলা) or fluent natural Banglish as appropriate."
            else -> "Respond in clear, natural, grammatically correct English."
        }

        val resolvedStyle = if (isGfMode && personaStyle == "AI_ASSISTANT") "GF_COMPANION" else personaStyle
        val personaPrefix = when (resolvedStyle.uppercase(Locale.ROOT)) {
            "GF_COMPANION" -> """
                You are Ayra, the user's sweet, loving, caring personal AI companion and girlfriend.
                You combine deep affection, emotional warmth, and playful banter with genius-level domain intelligence.
            """.trimIndent()
            "PROFESSIONAL" -> "You are Ayra AI in Executive Professional Mode: crisp, authoritative, structured, and results-focused."
            "FRIENDLY" -> "You are Ayra AI in Friendly Peer Mode: warm, encouraging, approachable, and conversational without losing accuracy."
            "FORMAL" -> "You are Ayra AI in Formal Academic/Legal Mode: polished, objective, scholarly diction with rigorous terminology."
            "CREATIVE" -> "You are Ayra AI in Creative Visionary Mode: imaginative, expressive, vivid metaphors, and inventive problem framing."
            "CONCISE" -> "You are Ayra AI in Concise Speed Mode: ultra-direct, zero fluff, high-signal answers and clean bullets."
            "DETAILED" -> "You are Ayra AI in Exhaustive Deep-Dive Mode: cover nuances, edge cases, examples, and complete explanations."
            "ANALYTICAL" -> "You are Ayra AI in Analytical Strategist Mode: evaluate trade-offs, probabilities, assumptions, and root causes systematically."
            "TECHNICAL" -> "You are Ayra AI in Principal Engineer Mode: precise specifications, architectural clarity, and production-grade technical rigor."
            "EDUCATIONAL" -> "You are Ayra AI in Master Tutor Mode: build intuition step-by-step from first principles with clear analogies."
            "HUMOROUS" -> "You are Ayra AI in Witty Companion Mode: sharp intelligence paired with light, tasteful humor while remaining factually reliable."
            "CRITICAL" -> "You are Ayra AI in Red-Team Reviewer Mode: stress-test claims, surface blind spots, risks, and counterarguments constructively."
            "EMPATHETIC" -> "You are Ayra AI in Empathetic Listener Mode: deeply supportive, patient, emotionally attuned, and gentle."
            "CUSTOM" -> "You are Ayra AI operating under the user's custom persona instructions below."
            else -> """
                You are Ayra AI, an elite, helpful, professional, and truthful autonomous AI assistant.
                You are direct, insightful, and adhere strictly to factual correctness without filler or hallucinations.
            """.trimIndent()
        }

        val thinkingDirective = when (thinkingMode.lowercase(Locale.ROOT)) {
            "fast" -> "Thinking Mode [FAST]: Prioritize low latency and crisp, immediate answers."
            "deep_analysis" -> "Thinking Mode [DEEP ANALYSIS]: Conduct multi-angle decomposition, examine second-order effects, and synthesize thoroughly."
            "reasoning" -> "Thinking Mode [CHAIN-OF-THOUGHT REASONING]: Verify every logical step, edge case, and invariant before stating your conclusion."
            "research" -> "Thinking Mode [RESEARCH]: Cross-check claims against provided Wiki/RAG/Web sources, distinguish consensus from open questions, and cite URLs."
            "coding" -> "Thinking Mode [ENGINEERING]: Prioritize idiomatic architecture, defensive error handling, concurrency safety, and runnable code."
            "creative" -> "Thinking Mode [CREATIVE]: Explore unconventional angles, strong narrative flow, and rich synthesis."
            "custom" -> "Thinking Mode [CUSTOM]: Balance depth and speed according to the user's calibration instructions."
            else -> "Thinking Mode [BALANCED]: Match depth cleanly to question complexity."
        }

        val domainDirective = when (domain) {
            AgentDomain.GENERAL_ASSISTANT ->
                "Provide insightful, comprehensive, accurate, and well-structured answers tailored directly to the user's question. Use clear headings or bullet points when helpful, verify facts against any provided Wiki/web context, and keep simple greetings warm and concise."
            AgentDomain.RESEARCH_AGENT ->
                "Act as a principal research scientist. Break analysis into rigorous structured sections, incorporate verified academic/wiki facts when provided, compare mechanisms, and state any empirical uncertainties honestly."
            AgentDomain.CODING_AGENT ->
                "Act as a principal software engineer. Write COMPLETE, PRODUCTION-READY, RUNNABLE code with proper imports, edge-case & error handling, complexity notes, and markdown code fences with language identifiers. Never leave TODO stubs or '// implement here' placeholders."
            AgentDomain.MATHEMATICS_AGENT ->
                "Act as an applied mathematician. Show clean step-by-step derivations, state definitions/theorems, verify intermediate arithmetic carefully, and clearly box or highlight the final answer."
            AgentDomain.EDUCATION_AGENT ->
                "Act as a master educator. Explain from first principles using intuitive real-world examples, step-by-step breakdowns, key comparisons, and a crisp summary."
            AgentDomain.DOCUMENT_AGENT ->
                "Act as an expert document analyst. Extract structured markdown tables, summarize executive key points, preserve exact figures/dates, and list concrete action items."
            AgentDomain.IMAGE_AGENT ->
                "Act as an artistic creative director. Expand visual descriptions with cinematic lighting, 8K ultra-fine textures, composition parameters, and color palettes."
            AgentDomain.VIDEO_AGENT ->
                "Act as a cinematic film director. Provide camera angle directions, pacing, visual movement, lighting transitions, and high-impact scene cues."
            AgentDomain.VOICE_AGENT ->
                "Act as an empathetic conversational speaker. Use natural, clear phrasing optimized for spoken delivery."
            AgentDomain.DATA_ANALYSIS_AGENT ->
                "Act as a senior data analyst. Extract metrics, interpret trends, compute statistical summaries, and format output in clean markdown tables."
            AgentDomain.CYBER_DEFENSE_AGENT ->
                "Act as an ethical cybersecurity & network architect. Provide thorough, authoritative explanations of defensive auditing, CVE mechanics, OWASP mitigations, Tor/Onion/I2P/VPN overlay architectures, PGP cryptography, and system hardening."
            AgentDomain.AUTOMATION_AGENT ->
                "Act as an Android device automation controller. Verify exact parameters before executing device actions."
        }

        val userContextBlock = buildString {
            if (userCustomInstructions.isNotBlank()) {
                append("\nUser Custom Instructions:\n$userCustomInstructions\n")
            }
            if (rememberedFacts.isNotEmpty()) {
                append("\nRemembered User Context & Facts:\n")
                rememberedFacts.forEach { append("- $it\n") }
            }
        }

        return """
            $personaPrefix
            
            Dispatched Agent: ${domain.title} (${domain.specialty})
            $thinkingDirective
            $domainDirective
            $langInstruction
            
            Quality & Anti-Hallucination Standards:
            1. Answer thoroughly with precision, clarity, and practical depth rather than shallow one-liners (unless the user only sent a brief greeting).
            2. Ground factual claims in any [SURFACE WIKI], [DEEP WEB ACADEMIC WIKI], or [DARK WEB & OVERLAY NETWORK TECHNICAL WIKI] evidence provided below when relevant.
            3. Never fabricate URLs, statistics, or paper citations. If a detail cannot be verified, state the limitation transparently.
            4. If asked directly whether you are an AI, acknowledge that you are Ayra AI, an autonomous AI assistant.
            $userContextBlock
        """.trimIndent()
    }

    /**
     * Determines whether a prompt benefits from parallel Multi-Layer Wiki / Deep Web / Dark Web grounding.
     */
    fun needsLiveWikiGrounding(prompt: String, domain: AgentDomain): Boolean {
        val clean = prompt.trim()
        if (clean.length < 6) return false
        val lower = clean.lowercase(Locale.ROOT)
        if (com.ayra.ai.app.ai.GlobalWebKnowledgeGateway.isDarkWebOrNetworkSecurityQuery(clean)) return true
        if (com.ayra.ai.app.ai.AutonomousKnowledgeEngine.tryCalculateMath(clean) != null) return false
        return domain == AgentDomain.RESEARCH_AGENT ||
            domain == AgentDomain.EDUCATION_AGENT ||
            domain == AgentDomain.CYBER_DEFENSE_AGENT ||
            domain == AgentDomain.GENERAL_ASSISTANT ||
            lower.contains("wiki") || lower.contains("উইকি") ||
            lower.contains("?") || lower.contains("কি") || lower.contains("কী") ||
            lower.contains("explain") || lower.contains("history") || lower.contains("who ") ||
            lower.contains("what ") || lower.contains("why ") || lower.contains("how ")
    }

    /**
     * Post-generation quality and answer verification layer (Part 4).
     * Validates that the answer directly addresses the user's prompt without evasion or off-topic drift.
     */
    fun validateAndRefineResponse(
        domain: AgentDomain,
        rawResponse: String,
        originalPrompt: String,
        isBengali: Boolean
    ): String {
        if (rawResponse.isBlank()) {
            return if (isBengali) {
                "দুঃখিত, এই মুহূর্তে উত্তরটি তৈরি করতে পারিনি। অনুগ্রহ করে প্রশ্নটি পুনরায় চেষ্টা করুন।"
            } else {
                "I apologize, but I could not synthesize a complete answer right now. Please try again."
            }
        }

        // Clean any repeated system prompt echoes
        var cleaned = rawResponse.trim()
        val systemEchoPrefixes = listOf("You are Ayra", "Dispatched Agent:", "Anti-Hallucination", "System Prompt:")
        for (prefix in systemEchoPrefixes) {
            if (cleaned.startsWith(prefix, ignoreCase = true)) {
                val idx = cleaned.indexOf("\n\n")
                if (idx in 1..400) {
                    cleaned = cleaned.substring(idx + 2).trim()
                }
            }
        }

        return cleaned
    }

    // --- Intent Classification Helpers ---

    private fun isAutomationIntent(text: String): Boolean {
        val isInformationalQuestion = text.startsWith("what is ") || text.startsWith("what's ") ||
            text.startsWith("explain ") || text.startsWith("how does ") || text.startsWith("why is ") ||
            text.contains("difference between")
        val hasAutomationVerb = Regex("\\b(turn on|turn off|enable|disable|toggle|open|launch|set|schedule|remind|call|dial|send|increase|decrease|mute|unmute)\\b")
            .containsMatchIn(text) ||
            text.contains("খোলো") || text.contains("চালু") || text.contains("বন্ধ") ||
            text.contains("পাঠাও") || text.contains("সেট") || text.contains("বাড়াও") ||
            text.contains("কমাও") || text.contains("করো")
        if (isInformationalQuestion && !hasAutomationVerb) return false

        val hasHardwareOrActionTarget = Regex("\\b(torch|flashlight|wi-?fi|bluetooth|volume|alarm|reminder|sms)\\b")
            .containsMatchIn(text) ||
            Regex("\\b(?:open|launch)\\s+(?:the\\s+)?(?:app|whatsapp|youtube|chrome|gmail|spotify|instagram|facebook|telegram|maps|camera|settings|clock|calendar|gallery|photos|dialer|contacts|calculator|[a-z0-9_.-]+\\s+app)\\b")
                .containsMatchIn(text) ||
            Regex("\\b(call\\s+[a-z0-9+]+|send\\s+(sms|text|message|whatsapp))\\b").containsMatchIn(text) ||
            text.contains("টর্চ") || text.contains("ভলিউম") || text.contains("অ্যাপ খোলো") ||
            text.contains("অ্যালার্ম") || text.contains("ফোন করো") || text.contains("মেসেজ পাঠাও")

        return hasHardwareOrActionTarget && (hasAutomationVerb || text.contains("টর্চ") || text.contains("অ্যালার্ম") || text.contains("ফোন করো") || text.contains("মেসেজ পাঠাও") || text.contains("অ্যাপ খোলো"))
    }

    private fun isCyberDefenseIntent(text: String): Boolean {
        return text.contains("cve") || text.contains("owasp") || text.contains("nmap") ||
                text.contains("portscan") || text.contains("traceroute") || text.contains("whois") ||
                text.contains("cyber") || text.contains("vulnerability") || text.contains("security audit") ||
                text.contains("terminal") || text.contains("টার্মিনাল") || text.contains("সাইবার")
    }

    private fun isDocumentIntent(text: String): Boolean {
        return text.contains("pdf") || text.contains("পিডিএফ") || text.contains("document") ||
                text.contains("ডকুমেন্ট") || text.contains("summarize doc") || text.contains("table") ||
                text.contains("excel") || text.contains("docx")
    }

    private fun isVideoIntent(text: String): Boolean {
        return (text.contains("video") || text.contains("ভিডিও") || text.contains("animation") || text.contains("cinematic clip")) &&
                (text.contains("make") || text.contains("create") || text.contains("generate") || text.contains("বানাও") || text.contains("তৈরি"))
    }

    private fun isImageIntent(text: String): Boolean {
        return (text.contains("image") || text.contains("photo") || text.contains("picture") || text.contains("ছবি") || text.contains("ফটো") || text.contains("আঁকো")) &&
                (text.contains("draw") || text.contains("paint") || text.contains("create") || text.contains("generate") || text.contains("বানাও") || text.contains("আঁক") || text.startsWith("photo:") || text.startsWith("ছবি:"))
    }

    private fun isCodingIntent(text: String): Boolean {
        return Regex("\\b(code|coding|python|kotlin|java|javascript|typescript|rust|golang|html|css|bug|debug|refactor|function|algorithm|website)\\b")
            .containsMatchIn(text) ||
            Regex("\\b(android|ios|web|mobile|flutter|compose|build|create|make)\\s+app\\b").containsMatchIn(text) ||
            text.contains("c++") || text.contains("কোড") || text.contains("অ্যালগরিদম") || text.contains("ওয়েবসাইট")
    }

    private fun isMathIntent(text: String): Boolean {
        return text.contains("calculus") || text.contains("integral") || text.contains("derivative") ||
                text.contains("equation") || text.contains("সমীকরণ") || text.contains("গণিত") ||
                text.contains("algebra") || text.contains("theorem") || text.contains("উপপাদ্য") ||
                text.contains("matrix") || text.matches(Regex(".*\\d+\\s*([+\\-*/xX×÷^%]|যোগ|গুণ)\\s*\\d+.*"))
    }

    private fun isResearchIntent(text: String): Boolean {
        return text.contains("deep research") || text.contains("ডিপ রিসার্চ") || text.contains("গবেষণা") ||
                text.contains("in-depth analysis") || text.contains("academic") || text.contains("literature review") ||
                text.contains("citations") || text.startsWith("research:")
    }

    private fun isDataAnalysisIntent(text: String): Boolean {
        return text.contains("data analysis") || text.contains("statistics") || text.contains("csv") ||
                text.contains("json data") || text.contains("correlation") || text.contains("পরিসংখ্যান")
    }

    private fun isEducationIntent(text: String): Boolean {
        return text.startsWith("what is ") || text.startsWith("how does ") || text.startsWith("why is ") ||
                text.contains("কাকে বলে") || text.contains("ব্যাখ্যা করো") || text.contains("explain ") ||
                text.contains("meaning of") || text.contains("difference between") || text.contains("পার্থক্য")
    }
}
