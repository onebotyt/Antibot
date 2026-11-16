package com.example.gemini.data.remote.services

import com.example.gemini.domain.model.AiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgyCliAnalysisLogicTest {

    @Test
    fun `isNewerVersion compares dotted versions correctly`() {
        assertTrue(AgyCliAnalysisLogic.isNewerVersion("2.32.0", "2.19.1"))
        assertTrue(AgyCliAnalysisLogic.isNewerVersion("2.32.1", "2.32"))
        assertTrue(AgyCliAnalysisLogic.isNewerVersion("3.0.0", "2.99.99"))
        assertFalse(AgyCliAnalysisLogic.isNewerVersion("2.19.1", "2.32.0"))
        assertFalse(AgyCliAnalysisLogic.isNewerVersion("2.32.0", "2.32.0"))
        assertFalse(AgyCliAnalysisLogic.isNewerVersion("", "2.32.0"))
        assertFalse(AgyCliAnalysisLogic.isNewerVersion("2.32.0", ""))
    }

    @Test
    fun `parseVersionFromLogLine extracts daemon version`() {
        val line = "I1115 19:15:47.996137      36 server.go:2602] Language server version: 2.32.0"
        assertEquals("2.32.0", AgyCliAnalysisLogic.parseVersionFromLogLine(line))
        assertEquals("2.19.1", AgyCliAnalysisLogic.parseVersionFromLogLine("version: 2.19.1"))
        assertNull(AgyCliAnalysisLogic.parseVersionFromLogLine("no version here"))
    }

    @Test
    fun `parseUpdateStatus reads success and message`() {
        val status = AgyCliAnalysisLogic.parseUpdateStatus(
            """{"success":true,"message":"Update successful, restart CLI to use"}"""
        )
        assertEquals(true, status?.success)
        assertEquals("Update successful, restart CLI to use", status?.message)
        assertNull(AgyCliAnalysisLogic.parseUpdateStatus("not json"))
    }

    @Test
    fun `parseGitHubRelease reads tag body and url`() {
        val release = AgyCliAnalysisLogic.parseGitHubRelease(
            """
            {
              "tag_name": "v1.5.9",
              "published_at": "2026-11-14T10:00:00Z",
              "body": "Adds Gemini 4 Hydrogen (High/Medium/Low).",
              "html_url": "https://github.com/google-antigravity/antigravity-cli/releases/tag/v1.5.9"
            }
            """.trimIndent()
        )
        assertEquals("1.5.9", release?.version)
        assertTrue(release?.releaseNotes?.contains("Hydrogen") == true)
        assertTrue(release?.htmlUrl?.endsWith("v1.5.9") == true)

        assertNull(AgyCliAnalysisLogic.parseGitHubRelease("""{"body":"no tag"}"""))
    }

    @Test
    fun `extractThinkingLevels reads tiers from model names`() {
        assertEquals(
            listOf("High"),
            AgyCliAnalysisLogic.extractThinkingLevels(
                id = "gemini-4-hydrogen-high",
                displayName = "Gemini 4 Hydrogen",
                tier = "high",
                supportsThinking = true
            )
        )

        assertEquals(
            listOf("Low", "Medium", "High"),
            AgyCliAnalysisLogic.extractThinkingLevels(
                id = "gemini-3-pro",
                displayName = "Gemini 3 Pro",
                tier = null,
                supportsThinking = true
            )
        )

        assertEquals(
            emptyList<String>(),
            AgyCliAnalysisLogic.extractThinkingLevels(
                id = "claude-sonnet",
                displayName = "Claude Sonnet",
                tier = null,
                supportsThinking = false
            )
        )
    }

    @Test
    fun `buildCapabilities flags only models unseen before`() {
        val models = listOf(
            AiModel(id = "gemini-3-pro-high", displayName = "Gemini 3 Pro", baseName = "Gemini 3 Pro", tier = "high"),
            AiModel(id = "gemini-4-hydrogen-high", displayName = "Gemini 4 Hydrogen", baseName = "Gemini 4 Hydrogen", tier = "high")
        )

        val firstRun = AgyCliAnalysisLogic.buildCapabilities(models, emptySet(), "2.32.0")
        assertEquals(2, firstRun.size)
        // A cold start must not mark the whole catalogue as new.
        assertTrue(firstRun.none { it.isNew })

        val afterUpdate = AgyCliAnalysisLogic.buildCapabilities(
            models,
            seenModelIds = setOf("gemini-3-pro-high"),
            currentCliVersion = "2.32.0"
        )
        val newModels = afterUpdate.filter { it.isNew }
        assertEquals(1, newModels.size)
        assertEquals("gemini-4-hydrogen-high", newModels.first().id)
    }

    @Test
    fun `extractModelMentions finds model names in release notes`() {
        val notes = """
            - Added Gemini 4 Hydrogen with Low, Medium and High reasoning.
            - Gemma 4 26B now runs on device via LiteRT.
            - Improved Claude Sonnet 5 tool routing.
        """.trimIndent()

        val mentions = AgyCliAnalysisLogic.extractModelMentions(notes)
        assertTrue(mentions.any { it.contains("Gemini 4 Hydrogen", ignoreCase = true) })
        assertTrue(mentions.any { it.contains("Gemma 4", ignoreCase = true) })
        assertTrue(mentions.any { it.contains("Claude Sonnet 5", ignoreCase = true) })
        assertTrue(AgyCliAnalysisLogic.extractModelMentions("").isEmpty())
    }
}
