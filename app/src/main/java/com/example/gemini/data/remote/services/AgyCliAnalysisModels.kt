package com.example.gemini.data.remote.services

import kotlinx.serialization.Serializable

@Serializable
data class AgyCliReleaseInfo(
    val version: String,
    val publishedAt: String = "",
    val releaseNotes: String = "",
    val downloadUrl: String = "",
    val htmlUrl: String = ""
)

@Serializable
data class AgyModelCapability(
    val id: String,
    val displayName: String,
    val baseName: String,
    val tier: String? = null,
    val thinkingLevels: List<String> = emptyList(),
    val supportsTools: Boolean = true,
    val quotaGroup: String = "Standard",
    val description: String = "",
    val isNew: Boolean = false,
    val firstSeenVersion: String? = null
)

@Serializable
data class AgyCliAnalysisReport(
    val currentCliVersion: String,
    val latestCliVersion: String? = null,
    val hasCliUpdate: Boolean = false,
    val updateMessage: String? = null,
    val releaseNotesSummary: String? = null,
    val mentionedModels: List<String> = emptyList(),
    val releaseUrl: String? = null,
    val newModelsDetected: List<AgyModelCapability> = emptyList(),
    val allActiveModels: List<AgyModelCapability> = emptyList(),
    val analysisTimestamp: Long = System.currentTimeMillis()
) {
    val hasAnythingToShow: Boolean
        get() = hasCliUpdate || newModelsDetected.isNotEmpty() || !releaseNotesSummary.isNullOrBlank()
}
