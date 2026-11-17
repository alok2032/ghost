package com.ayra.ai.app.ai.superpowers

import android.content.Context
import java.io.InputStreamReader
import java.util.Locale

/**
 * Enterprise AI Superpowers Engine for Ayra AI.
 * Directly integrates the agentic engineering methodologies from obra/superpowers (github.com/obra/superpowers):
 * - Systematic Debugging (4-phase root cause isolation before modifying code)
 * - Test-Driven Development (TDD red-green-refactor cycle)
 * - Interactive Brainstorming & Requirements Architecture
 * - Subagent Orchestration & Parallel Task Dispatching
 * - Autonomous Senior Code Review & Security Gate
 * - Pre-Completion Verification Checklist
 */
object AyraSuperpowersEngine {

    enum class SuperpowerType(
        val id: String,
        val displayName: String,
        val icon: String,
        val assetDir: String,
        val description: String
    ) {
        SYSTEMATIC_DEBUGGING(
            id = "systematic_debugging",
            displayName = "Systematic Debugger 🔬",
            icon = "🔬",
            assetDir = "systematic-debugging",
            description = "4-phase root cause diagnosis, log analysis & surgical regression-tested fix"
        ),
        TEST_DRIVEN_DEVELOPMENT(
            id = "tdd",
            displayName = "TDD Engine ⚡",
            icon = "⚡",
            assetDir = "test-driven-development",
            description = "Test-First engineering: automated unit tests, mocking & edge-case matrices"
        ),
        BRAINSTORMING(
            id = "brainstorming",
            displayName = "Architectural Brainstorming 🧠",
            icon = "🧠",
            assetDir = "brainstorming",
            description = "Deep requirements exploration, system architecture & execution blueprints"
        ),
        SUBAGENT_DISPATCHER(
            id = "subagents",
            displayName = "Subagent Orchestrator 🤖",
            icon = "🤖",
            assetDir = "subagent-driven-development",
            description = "Parallel subagent task decomposition, coordination & validation gates"
        ),
        CODE_REVIEW_GATE(
            id = "code_review",
            displayName = "Code Review & Security 🛡️",
            icon = "🛡️",
            assetDir = "requesting-code-review",
            description = "Senior code review: OWASP security, memory leaks, concurrency & clean code"
        ),
        VERIFICATION_BEFORE_COMPLETION(
            id = "verification",
            displayName = "Pre-Completion Verifier ✅",
            icon = "✅",
            assetDir = "verification-before-completion",
            description = "Strict verification checklist ensuring zero untested claims or mock fallbacks"
        )
    }

    /**
     * Detects if user prompt intends to invoke a Superpower.
     */
    fun detectSuperpower(prompt: String): SuperpowerType? {
        val lower = prompt.lowercase(Locale.ROOT).trim()

        // 1. Explicit superpower prefixes
        if (lower.startsWith("superpower:") || lower.startsWith("সুপারপাওয়ার:")) {
            return when {
                lower.contains("debug") || lower.contains("ডিবাগ") -> SuperpowerType.SYSTEMATIC_DEBUGGING
                lower.contains("tdd") || lower.contains("test") || lower.contains("টেস্ট") -> SuperpowerType.TEST_DRIVEN_DEVELOPMENT
                lower.contains("brainstorm") || lower.contains("প্ল্যান") || lower.contains("plan") -> SuperpowerType.BRAINSTORMING
                lower.contains("subagent") || lower.contains("এজেন্ট") -> SuperpowerType.SUBAGENT_DISPATCHER
                lower.contains("review") || lower.contains("রিভিউ") || lower.contains("audit") -> SuperpowerType.CODE_REVIEW_GATE
                else -> SuperpowerType.SYSTEMATIC_DEBUGGING
            }
        }

        // 2. Debugging & Error Investigation
        if ((lower.contains("fix this bug") || lower.contains("debug") || lower.contains("ডিবাগ") ||
                    lower.contains("crash") || lower.contains("exception") || lower.contains("error:") ||
                    lower.contains("nullpointer") || lower.contains("fatal error")) &&
            (lower.contains("code") || lower.contains("stacktrace") || lower.contains("log") || lower.contains("{"))
        ) {
            return SuperpowerType.SYSTEMATIC_DEBUGGING
        }

        // 3. Test Driven Development
        if (lower.contains("tdd") || lower.contains("unit test") || lower.contains("টেস্ট লেখো") ||
            lower.contains("write tests") || lower.contains("test cases") || lower.contains("test suite")
        ) {
            return SuperpowerType.TEST_DRIVEN_DEVELOPMENT
        }

        // 4. Code Review & Security Audit
        if (lower.contains("code review") || lower.contains("review this code") || lower.contains("কোড রিভিউ") ||
            lower.contains("security audit") || lower.contains("audit this code") || lower.contains("vulnerability check")
        ) {
            return SuperpowerType.CODE_REVIEW_GATE
        }

        // 5. Brainstorming & Architecture Blueprint
        if (lower.contains("brainstorm") || lower.contains("architecture design") || lower.contains("আর্কিটেকচার") ||
            lower.contains("system design") || lower.contains("প্ল্যান বানাও") || lower.contains("execution plan")
        ) {
            return SuperpowerType.BRAINSTORMING
        }

        return null
    }

    /**
     * Loads the actual SKILL.md definition from app assets.
     */
    fun loadSkillGuide(context: Context?, superpower: SuperpowerType): String {
        if (context == null) return ""
        return try {
            val assetPath = "superpowers/${superpower.assetDir}/SKILL.md"
            context.assets.open(assetPath).use { input ->
                InputStreamReader(input, Charsets.UTF_8).readText()
            }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Constructs the enhanced Superpower System Prompt for LLM models.
     */
    fun buildSuperpowerSystemPrompt(
        superpower: SuperpowerType,
        isBengali: Boolean,
        context: Context?
    ): String {
        val skillDoc = loadSkillGuide(context, superpower)
        val lang = if (isBengali) "Bengali (বাংলা)" else "English"

        return """
            You are Ghost AI operating under the official Superpowers Engineering Protocol (derived from obra/superpowers).
            Active Superpower: ${superpower.displayName} (${superpower.description}).
            
            RULES OF ENGAGEMENT:
            1. Respond in natural, professional $lang.
            2. Never write pseudo-code or simulated placeholders. Provide complete, pristine, production-grade solutions.
            3. Follow the 4-phase Superpower methodology:
               - Phase 1: Deep Root Cause & Architectural Diagnosis
               - Phase 2: Hypothesis & Specification Checklist
               - Phase 3: Production Code / Test Suite / Blueprint
               - Phase 4: Pre-Completion Verification Checklist
               
            Superpower Skill Guidelines:
            $skillDoc
        """.trimIndent()
    }

    /**
     * Honest offline notice for Superpowers protocol when no live LLM provider is reachable.
     */
    fun generateOfflineSuperpowerResponse(
        superpower: SuperpowerType,
        query: String,
        isBengali: Boolean
    ): String {
        val safeQuery = query.take(80).ifBlank { "(empty)" }
        return if (isBengali) {
            """
            # ⚡ Ghost AI সুপারপাওয়ার ইঞ্জিন: ${superpower.displayName}
            *Superpowers Protocol (obra/superpowers) — অফলাইন স্ট্যাটাস*

            ⚠️ **লাইভ এআই এক্সিকিউশন চলেনি (অফলাইন / API কী কনফিগার করা নেই):** আপনার অনুরোধ **"$safeQuery"**-এর ওপর রিয়েল এআই বিশ্লেষণ চালাতে ইন্টারনেট সংযোগ ও Gemini / Groq / Cerebras / SambaNova / GitHub Models / OpenRouter-এর মতো যেকোনো ফ্রি-টিয়ার প্রোভাইডার কী সেট করুন।

            ## 📋 সুপারপাওয়ার এক্সিকিউশন চেকলিস্ট (অমীমাংসিত)
            - [ ] **ইনভেস্টিগেশন:** এরর লগ ও ইনপুট প্যারামিটার লাইভ মডেলে যাচাই করা বাকি
            - [ ] **আইসোলেশন:** পার্শ্বপ্রতিক্রিয়া ছাড়া মূল লজিক পৃথকীকরণ বাকি
            - [ ] **ইমপ্লিমেন্টেশন:** প্রোডাকশন কোড/টেস্ট জেনারেশন বাকি
            - [ ] **ভেরিফিকেশন:** ইউনিট টেস্ট ও বিল্ড যাচাইকরণ বাকি
            """.trimIndent()
        } else {
            """
            # ⚡ Ghost AI Superpower Engine: ${superpower.displayName}
            *Superpowers Protocol (obra/superpowers) — Offline Notice*

            ⚠️ **Live Execution Did Not Run (Offline / No Provider Configured):** Real diagnostic synthesis for **"$safeQuery"** requires an active network connection and at least one configured provider (Firebase Gemini AI, Groq, Cerebras, SambaNova, GitHub Models, or OpenRouter) in Settings → AI Provider Keys.

            ## 📋 Superpowers Execution Checklist (Pending)
            - [ ] **Phase 1 (Diagnosis):** Root cause isolation not yet executed.
            - [ ] **Phase 2 (Specification):** Architecture contracts pending live review.
            - [ ] **Phase 3 (Implementation):** Production code generation awaiting LLM provider.
            - [ ] **Phase 4 (Verification):** Pre-completion build and unit test verification pending.
            """.trimIndent()
        }
    }
}
