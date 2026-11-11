package com.example.gemini.domain.project

import java.util.Locale

sealed class VersionControlIntent {
    data class SaveCheckpoint(val message: String) : VersionControlIntent()
    data class Rollback(val targetHash: String? = null) : VersionControlIntent()
    object ShowHistory : VersionControlIntent()
}

object ChatVersionControlParser {

    /**
     * Parses user chat prompts to detect local version control intents
     * such as saving checkpoints, rolling back, or viewing version history.
     */
    fun parseIntent(prompt: String): VersionControlIntent? {
        val trimmed = prompt.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. Show history / commits
        val showHistoryPatterns = listOf(
            Regex("""^(?:show|view|display|list|get)\s+(?:all\s+)?(?:the\s+)?(?:local\s+)?(?:version\s+history|commits?|checkpoints?|commit\s+history)"""),
            Regex("""^version\s+history$"""),
            Regex("""^git\s+log$"""),
            Regex("""^commits$"""),
            Regex("""^checkpoints$""")
        )
        if (showHistoryPatterns.any { it.containsMatchIn(lower) }) {
            return VersionControlIntent.ShowHistory
        }

        // 2. Rollback / Revert to previous version or commit hash
        val rollbackHashRegex = Regex("""(?:rollback|revert)\s+(?:workspace\s+)?(?:to\s+)?(?:commit\s+)?([a-f0-9]{5,40})""")
        rollbackHashRegex.find(lower)?.let { match ->
            val hash = match.groupValues.getOrNull(1)?.trim()
            if (!hash.isNullOrBlank()) {
                return VersionControlIntent.Rollback(targetHash = hash)
            }
        }

        val rollbackPrevPatterns = listOf(
            Regex("""(?:rollback|revert|undo)\s+(?:workspace\s+)?(?:to\s+)?(?:the\s+)?(?:previous|last)\s*(?:version|checkpoint|commit)?"""),
            Regex("""^rollback$"""),
            Regex("""^revert\s+last$"""),
            Regex("""^undo\s+last\s+(?:changes?|version|commit)$"""),
            Regex("""^undo\s+changes$""")
        )
        if (rollbackPrevPatterns.any { it.containsMatchIn(lower) }) {
            return VersionControlIntent.Rollback(targetHash = null)
        }

        // 3. Save checkpoint / snapshot
        val saveCheckpointRegex = Regex(
            """^(?:save|create|make|record)\s+(?:a\s+)?(?:new\s+)?(?:local\s+)?(?:checkpoint|snapshot|version)(?:\s*[:\-–]?\s*(.*))?"""
        )
        saveCheckpointRegex.find(lower)?.let { match ->
            val rawMsg = match.groupValues.getOrNull(1)?.trim().orEmpty()
            val cleanMsg = rawMsg.removePrefix("\"").removeSuffix("\"").removePrefix("'").removeSuffix("'").trim()
            return VersionControlIntent.SaveCheckpoint(
                message = cleanMsg.ifBlank { "Local Checkpoint" }
            )
        }

        val quickCheckpointRegex = Regex("""^(?:checkpoint|snapshot)\s*[:\-–]?\s*(.*)""")
        quickCheckpointRegex.find(lower)?.let { match ->
            val rawMsg = match.groupValues.getOrNull(1)?.trim().orEmpty()
            val cleanMsg = rawMsg.removePrefix("\"").removeSuffix("\"").removePrefix("'").removeSuffix("'").trim()
            return VersionControlIntent.SaveCheckpoint(
                message = cleanMsg.ifBlank { "Local Checkpoint" }
            )
        }

        return null
    }
}
