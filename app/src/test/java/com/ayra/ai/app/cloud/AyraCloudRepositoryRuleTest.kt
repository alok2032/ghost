package com.ayra.ai.app.cloud

import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.data.InstalledGitHubPlugin
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AyraCloudRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun ownerProfileAndMessagesCrud_succeedsAndStreams() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repo = AyraCloudRepository(firestore, auth)

        withTimeout(OP_TIMEOUT_MS) {
            repo.upsertUserProfile(
                displayName = "Alice",
                email = ALICE_EMAIL,
                photoUrl = "",
                preferredPersona = "GF_COMPANION",
                preferredModelTier = "auto"
            )
            repo.upsertUserProfile(
                displayName = "Alice Updated",
                email = ALICE_EMAIL,
                photoUrl = "https://example.com/p.png",
                preferredPersona = "PROFESSIONAL",
                preferredModelTier = "omniroute"
            )
        }

        val uniqueKey = "msg_${UUID.randomUUID()}"
        val msg = ChatMessage(
            id = 42L,
            role = "user",
            content = "Cloud synced test prompt ${UUID.randomUUID()}",
            timestamp = System.currentTimeMillis(),
            actionCommand = "gemini-3.8-flash"
        )

        withTimeout(OP_TIMEOUT_MS) {
            repo.syncMessageToCloud(msg, uniqueKey)
        }

        val observed = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeCloudMessages().first { list -> list.any { it.content == msg.content } }
        }
        assertTrue(observed.any { it.content == msg.content })

        val fetchedOnce = withTimeout(OP_TIMEOUT_MS) { repo.fetchCloudMessagesOnce() }
        assertTrue(fetchedOnce.any { it.content == msg.content })
        assertEquals(aliceUid, repo.requireUserId())
    }

    @Test
    fun ownerPluginAndCreationSync_succeedsAndDeletes() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = AyraCloudRepository(firestore, auth)

        val repoName = "owner/plugin-${UUID.randomUUID().toString().take(8)}"
        val plugin = InstalledGitHubPlugin(
            repoFullName = repoName,
            repoUrl = "https://github.com/$repoName",
            name = "test-plugin",
            owner = "owner",
            description = "Test plugin",
            defaultBranch = "main",
            stars = 123,
            primaryLanguage = "Kotlin",
            pluginType = "MCP_SERVER",
            capabilitiesSummary = "Tools and memory",
            skillPromptContext = "Use plugin",
            openApiSpecJson = "[]",
            commandsJson = "[]",
            fileTreeJson = "[]",
            isEnabled = true
        )

        withTimeout(OP_TIMEOUT_MS) {
            repo.syncPluginToCloud(plugin)
            repo.syncPluginToCloud(plugin.copy(isEnabled = false))
            val plugins = repo.fetchCloudPluginsOnce()
            val rawSnap = firestore.collection("users").document(auth.currentUser!!.uid)
                .collection("plugins").get().awaitResult()
            assertTrue(
                "Expected repo=$repoName disabled in plugins=$plugins (rawDocs=${rawSnap.documents.map { it.data }})",
                plugins.any { it.repoFullName == repoName && !it.isEnabled }
            )

            repo.logCreation(
                creationType = "IMAGE",
                title = "Neon Horizon",
                prompt = "Draw a neon horizon",
                summary = "Nano Banana 2.1",
                modelUsed = "gemini-nano-banana-2.1"
            )

            repo.deletePluginFromCloud(repoName)
            val afterDelete = repo.fetchCloudPluginsOnce()
            assertTrue(afterDelete.none { it.repoFullName == repoName })
        }
    }

    @Test
    fun crossUserReadAndWrite_deniedWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = AyraCloudRepository(firestore, auth)
        val msg = ChatMessage(
            id = 100L,
            role = "assistant",
            content = "Private secret note",
            timestamp = System.currentTimeMillis()
        )
        withTimeout(OP_TIMEOUT_MS) {
            aliceRepo.syncMessageToCloud(msg, "private_${UUID.randomUUID()}")
        }

        signInTestUser(BOB_EMAIL)
        var capturedError: Throwable? = null
        try {
            withTimeout(OP_TIMEOUT_MS) {
                firestore.collection("users").document(aliceUid)
                    .collection("messages")
                    .get()
                    .awaitResult()
            }
        } catch (t: Throwable) {
            capturedError = t
        }
        val fsEx = capturedError?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)

        var writeError: Throwable? = null
        try {
            withTimeout(OP_TIMEOUT_MS) {
                firestore.collection("users").document(aliceUid)
                    .collection("creations")
                    .document("forged_${UUID.randomUUID()}")
                    .set(mapOf("userId" to aliceUid))
                    .awaitResult()
            }
        } catch (t: Throwable) {
            writeError = t
        }
        val writeFsEx = writeError?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, writeFsEx?.code)
    }

    @Test
    fun unauthenticatedAccess_deniedByRulesAndRepository() = runBlocking {
        val targetUid = signInTestUser(ALICE_EMAIL)
        auth.signOut()

        val repo = AyraCloudRepository(firestore, auth)
        assertTrue(runCatching { repo.requireUserId() }.isFailure)

        var readError: Throwable? = null
        try {
            withTimeout(OP_TIMEOUT_MS) {
                firestore.collection("users").document(targetUid).get().awaitResult()
            }
        } catch (t: Throwable) {
            readError = t
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, readError?.unwrapFirestoreException()?.code)
    }

    private companion object {
        const val ALICE_EMAIL = "alice_rules@ayra.test"
        const val BOB_EMAIL = "bob_rules@ayra.test"
        const val OP_TIMEOUT_MS = 15000L
        const val FLOW_TIMEOUT_MS = 10000L
    }
}
