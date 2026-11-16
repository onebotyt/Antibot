package com.example.gemini.data.remote.services

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Monitors Antigravity CLI releases, local updater state and the daemon model
 * registry, then produces an in-app capability analysis report.
 *
 * Sources, in order of trust:
 *  1. Local daemon log  -> current CLI / language server version
 *  2. Local updater json -> whether an update was already staged
 *  3. GitHub releases    -> latest published version + release notes
 *  4. AgyModelService    -> live model list served by the Antigravity daemon
 */
class AgyCliUpdateTracker(
    private val context: Context? = null,
    private val modelService: AgyModelService = AgyModelService.instance,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val antigravityHome: File = File(
        "/data/user/0/com.termux/files/home/.gemini/antigravity-cli"
    )
) {
    companion object {
        private const val TAG = "AgyCliUpdateTracker"
        val instance by lazy { AgyCliUpdateTracker() }

        private const val PREFS_NAME = "antigravity_cli_tracker_prefs"
        private const val KEY_SEEN_MODELS = "seen_model_ids"
        private const val KEY_LAST_SEEN_CLI_VERSION = "last_seen_cli_version"
        private const val GITHUB_REPO = "google-antigravity/antigravity-cli"
    }

    private val _analysisReport = MutableStateFlow<AgyCliAnalysisReport?>(null)
    val analysisReport: StateFlow<AgyCliAnalysisReport?> = _analysisReport.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    /** Model ids introduced since the last successful scan; used to badge the picker. */
    private val _newModelIds = MutableStateFlow<Set<String>>(emptySet())
    val newModelIds: StateFlow<Set<String>> = _newModelIds.asStateFlow()

    /**
     * Runs a full update + model analysis pass and publishes the result on
     * [analysisReport]. Safe to call repeatedly; never throws.
     */
    suspend fun checkUpdatesAndAnalyzeModels(
        forceRefresh: Boolean = false
    ): AgyCliAnalysisReport = withContext(Dispatchers.IO) {
        _isChecking.value = true
        try {
            val currentVersion = detectLocalCliVersion()
            val updateStatus = readLocalUpdateStatus()
            val latestRelease = fetchLatestGitHubRelease()

            val latestVersion = latestRelease?.version?.takeIf { it.isNotBlank() }
                ?: currentVersion

            // A version-based update only counts when GitHub is strictly ahead.
            val versionUpdate = latestRelease?.version?.let {
                AgyCliAnalysisLogic.isNewerVersion(it, currentVersion)
            } ?: false

            // `update_status.json` persists across launches, so it is only meaningful
            // when it is newer than the newest daemon log (i.e. staged but not restarted).
            val stagedRestartPending = updateStatus?.success == true && isUpdateStatusNewerThanNewestLog()
            val hasUpdate = versionUpdate || stagedRestartPending

            val modelsResult = modelService.getAvailableModels(forceRefresh = forceRefresh)
            val rawModels = modelsResult.getOrDefault(emptyList())

            val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val seenModelIds: Set<String> = prefs
                ?.getStringSet(KEY_SEEN_MODELS, emptySet())
                ?.toSet()
                ?: emptySet()

            val capabilities = AgyCliAnalysisLogic.buildCapabilities(
                models = rawModels,
                seenModelIds = seenModelIds,
                currentCliVersion = currentVersion
            )
            val newlyDetected = capabilities.filter { it.isNew }
            _newModelIds.value = newlyDetected.map { it.id }.toSet()

            if (rawModels.isNotEmpty() && prefs != null) {
                val allCurrentIds = rawModels.map { it.id }.toSet()
                prefs.edit()
                    .putStringSet(KEY_SEEN_MODELS, seenModelIds + allCurrentIds)
                    .putString(KEY_LAST_SEEN_CLI_VERSION, currentVersion)
                    .apply()
            }

            val report = AgyCliAnalysisReport(
                currentCliVersion = currentVersion,
                latestCliVersion = latestVersion,
                hasCliUpdate = hasUpdate,
                updateMessage = when {
                    stagedRestartPending -> updateStatus?.message?.takeIf { it.isNotBlank() }
                    versionUpdate -> "Antigravity CLI $latestVersion is available (running $currentVersion)"
                    else -> null
                },
                releaseNotesSummary = latestRelease?.releaseNotes?.take(1500)?.takeIf { it.isNotBlank() },
                mentionedModels = AgyCliAnalysisLogic.extractModelMentions(
                    latestRelease?.releaseNotes ?: ""
                ),
                releaseUrl = latestRelease?.htmlUrl?.takeIf { it.isNotBlank() },
                newModelsDetected = newlyDetected,
                allActiveModels = capabilities,
                analysisTimestamp = System.currentTimeMillis()
            )

            _analysisReport.value = report
            report
        } catch (e: Exception) {
            Log.e(TAG, "Antigravity CLI/model analysis failed", e)
            val fallback = AgyCliAnalysisReport(
                currentCliVersion = runCatching { detectLocalCliVersion() }.getOrDefault("unknown"),
                latestCliVersion = null,
                hasCliUpdate = false,
                updateMessage = "Analysis unavailable: ${e.message}"
            )
            _analysisReport.value = fallback
            fallback
        } finally {
            _isChecking.value = false
        }
    }

    /** Clears the "new" markers, e.g. after the user has read the analysis. */
    fun acknowledgeNewModels() {
        _newModelIds.value = emptySet()
        _analysisReport.value = _analysisReport.value?.copy(
            newModelsDetected = emptyList()
        )
    }

    /** Reads the newest daemon log and extracts the language server / CLI version. */
    fun detectLocalCliVersion(): String {
        val latestLog = newestLogFile() ?: return "unknown"
        runCatching {
            latestLog.useLines { lines ->
                for (line in lines.take(60)) {
                    AgyCliAnalysisLogic.parseVersionFromLogLine(line)?.let { return it }
                }
            }
        }
        return "unknown"
    }

    private fun readLocalUpdateStatus(): AgyCliAnalysisLogic.LocalUpdateStatus? {
        val file = File(antigravityHome, "updater/update_status.json")
        if (!file.exists()) return null
        return runCatching {
            AgyCliAnalysisLogic.parseUpdateStatus(file.readText())
        }.getOrNull()
    }

    /** True when the staged-update marker is newer than the newest daemon log. */
    private fun isUpdateStatusNewerThanNewestLog(): Boolean {
        val statusFile = File(antigravityHome, "updater/update_status.json")
        if (!statusFile.exists()) return false
        val newestLog = newestLogFile() ?: return true
        return statusFile.lastModified() > newestLog.lastModified()
    }

    private fun newestLogFile(): File? =
        File(antigravityHome, "log")
            .listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.maxByOrNull { it.lastModified() }

    private fun fetchLatestGitHubRelease(): AgyCliReleaseInfo? = runCatching {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
            .header("User-Agent", "AntiBot-Harness")
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            AgyCliAnalysisLogic.parseGitHubRelease(body)
        }
    }.getOrNull()
}
