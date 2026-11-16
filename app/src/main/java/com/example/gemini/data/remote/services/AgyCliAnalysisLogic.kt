package com.example.gemini.data.remote.services

import com.example.gemini.domain.model.AiModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure, side-effect-free analysis helpers for the Antigravity CLI update tracker.
 * Kept separate from [AgyCliUpdateTracker] so they can be unit tested without
 * network, Android context or a running Antigravity daemon.
 */
object AgyCliAnalysisLogic {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Status parsed from `~/.gemini/antigravity-cli/updater/update_status.json`. */
    data class LocalUpdateStatus(val success: Boolean, val message: String)

    /**
     * True when [candidate] is a strictly newer dotted version than [current].
     * Missing segments count as 0, so "2.32" < "2.32.0" is false and "2.32.1" > "2.32" is true.
     */
    fun isNewerVersion(candidate: String, current: String): Boolean {
        if (candidate.isBlank() || current.isBlank()) return false
        val candidateParts = candidate.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        if (candidateParts.isEmpty() || currentParts.isEmpty()) return false

        for (i in 0 until maxOf(candidateParts.size, currentParts.size)) {
            val c = candidateParts.getOrElse(i) { 0 }
            val cur = currentParts.getOrElse(i) { 0 }
            if (c > cur) return true
            if (c < cur) return false
        }
        return false
    }

    /** Extracts a human readable version out of a daemon log line, e.g. "Language server version: 2.32.0". */
    fun parseVersionFromLogLine(line: String): String? {
        if (!line.contains("version:", ignoreCase = true)) return null
        return Regex("version:\\s*v?([0-9]+(?:\\.[0-9]+)+)", RegexOption.IGNORE_CASE)
            .find(line)
            ?.groupValues
            ?.getOrNull(1)
    }

    fun parseUpdateStatus(rawJson: String): LocalUpdateStatus? = runCatching {
        val obj = json.parseToJsonElement(rawJson).jsonObject
        LocalUpdateStatus(
            success = obj["success"]?.jsonPrimitive?.booleanOrNull ?: false,
            message = obj["message"]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }.getOrNull()

    fun parseGitHubRelease(rawJson: String): AgyCliReleaseInfo? = runCatching {
        val obj = json.parseToJsonElement(rawJson).jsonObject
        val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull?.removePrefix("v") ?: ""
        if (tag.isBlank()) return@runCatching null
        AgyCliReleaseInfo(
            version = tag,
            publishedAt = obj["published_at"]?.jsonPrimitive?.contentOrNull ?: "",
            releaseNotes = obj["body"]?.jsonPrimitive?.contentOrNull ?: "",
            htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }.getOrNull()

    /**
     * Derives the thinking/reasoning tiers a model exposes, based on its id,
     * display name and tier marker.
     */
    fun extractThinkingLevels(
        id: String,
        displayName: String,
        tier: String?,
        supportsThinking: Boolean
    ): List<String> {
        val haystack = "$id $displayName ${tier ?: ""}".lowercase()
        val levels = mutableListOf<String>()
        if (haystack.contains("extreme") || haystack.contains("xhigh")) levels.add("Extreme")
        if (haystack.contains("high")) levels.add("High")
        if (haystack.contains("med")) levels.add("Medium")
        if (Regex("(^|[^a-z])low([^a-z]|$)").containsMatchIn(haystack)) levels.add("Low")

        if (levels.isEmpty() && supportsThinking) {
            return listOf("Low", "Medium", "High")
        }
        return levels.distinct().sortedBy { orderOf(it) }
    }

    private fun orderOf(level: String): Int = when (level) {
        "Low" -> 0
        "Medium" -> 1
        "High" -> 2
        else -> 3
    }

    /**
     * Builds capability records for every active model and flags the ones that
     * were not present in the previously persisted [seenModelIds] set.
     */
    fun buildCapabilities(
        models: List<AiModel>,
        seenModelIds: Set<String>,
        currentCliVersion: String
    ): List<AgyModelCapability> = models.map { m ->
        AgyModelCapability(
            id = m.id,
            displayName = m.displayName.ifBlank { m.id },
            baseName = m.baseName.ifBlank { m.displayName.ifBlank { m.id } },
            tier = m.tier,
            thinkingLevels = extractThinkingLevels(
                id = m.id,
                displayName = m.displayName,
                tier = m.tier,
                supportsThinking = m.supportsThinking
            ),
            supportsTools = true,
            quotaGroup = when {
                m.tier?.contains("pro", ignoreCase = true) == true -> "Pro"
                m.tier?.contains("flash", ignoreCase = true) == true -> "Flash"
                else -> "Standard"
            },
            description = m.description.ifBlank {
                "Antigravity model with tool use and configurable reasoning depth"
            },
            // A first run (empty seen set) must not flag every model as new.
            isNew = seenModelIds.isNotEmpty() && m.id !in seenModelIds,
            firstSeenVersion = currentCliVersion
        )
    }

    /**
     * Extracts likely model names mentioned in release notes so the analysis can
     * point at the specific models a CLI update introduced.
     */
    fun extractModelMentions(releaseNotes: String): List<String> {
        if (releaseNotes.isBlank()) return emptyList()
        val patterns = listOf(
            Regex("(?i)\\bgemini[- ]?[0-9][a-z0-9.\\- ]*", RegexOption.IGNORE_CASE),
            Regex("(?i)\\bgemma[- ]?[0-9][a-z0-9.\\- ]*", RegexOption.IGNORE_CASE),
            Regex("(?i)\\bclaude[- ]?[0-9][a-z0-9.\\- ]*", RegexOption.IGNORE_CASE)
        )
        return patterns
            .flatMap { it.findAll(releaseNotes).map { m -> m.value.trim().trimEnd('.', ',', ':', ';', ')', '*') } }
            .map { it.replace(Regex("\\s+"), " ") }
            .filter { it.length in 4..60 }
            .distinct()
            .take(12)
    }
}
