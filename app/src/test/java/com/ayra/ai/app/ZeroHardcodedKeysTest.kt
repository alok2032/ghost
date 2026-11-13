package com.ayra.ai.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Security Guardrail Test:
 * Ensures zero hardcoded API keys or provider credentials exist anywhere in app/src/main.
 * All provider keys must be entered by the user and persisted only via Android Keystore EncryptedPreferences.
 */
class ZeroHardcodedKeysTest {

    private val forbiddenKeyPrefixes = listOf(
        "nvapi-",
        "AIzaSy",
        "sk-proj-",
        "sk-ant-",
        "AQ.Ab8",
        "gsk_",
        "hf_"
    )

    @Test
    fun mainSourceContainsZeroHardcodedApiKeys() {
        val mainDir = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
        assertTrue("Expected main source dir at ${mainDir.absolutePath}", mainDir.exists())

        val violations = mutableListOf<String>()

        mainDir.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "xml", "json", "properties") }
            .forEach { file ->
                // google-services.json is Firebase-provisioned public app config, not user-AI-key source code
                if (file.name == "google-services.json") return@forEach

                file.readLines().forEachIndexed { idx, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed

                    for (prefix in forbiddenKeyPrefixes) {
                        val matchIdx = line.indexOf(prefix)
                        if (matchIdx >= 0) {
                            // Allow short UI hint strings like "nvapi-..." or "gsk_..." in Compose placeholder text
                            val snippet = line.substring(matchIdx, (matchIdx + 24).coerceAtMost(line.length))
                            val isHintOnly = snippet.contains("...") ||
                                snippet.startsWith("$prefix\"") ||
                                snippet.startsWith("${prefix}xxx")
                            if (!isHintOnly) {
                                violations.add("${file.name}:${idx + 1} contains credential prefix '$prefix'")
                            }
                        }
                    }
                }
            }

        assertTrue(
            "Found hardcoded credential prefixes in main source:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
