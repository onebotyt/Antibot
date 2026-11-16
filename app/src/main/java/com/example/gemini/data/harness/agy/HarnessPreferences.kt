package com.example.gemini.data.harness.agy

import android.content.Context
import com.example.gemini.data.harness.model.AgyEffort
import com.example.gemini.data.harness.model.AgyMode
import com.example.gemini.data.harness.model.HarnessConfig
import com.example.gemini.data.local.LocalEnvironmentManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the harness runs the CLI: which binary, which model, and how much freedom.
 * Kept in SharedPreferences because a harness session must survive process death.
 */
class HarnessPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())
    val config: StateFlow<HarnessConfig> = _config.asStateFlow()

    /** The `agy` path as the CLI's filesystem sees it. */
    val binaryPath: String
        get() = prefs.getString(KEY_BINARY, defaultBinaryPath(context)) ?: defaultBinaryPath(context)

    val isHostedInUbuntu: Boolean
        get() = !LocalEnvironmentManager.isTermuxPackage(context) &&
            LocalEnvironmentManager.isUbuntuInstalled(context)

    /** Where the CLI runs: the workspace it may edit. */
    val workingDir: String
        get() = if (isHostedInUbuntu) "/root/workspace" else LocalEnvironmentManager.getHomeDir(context).absolutePath

    fun setConfig(config: HarnessConfig) {
        prefs.edit()
            .putString(KEY_MODEL, config.model)
            .putString(KEY_EFFORT, config.effort.flag)
            .putString(KEY_MODE, config.mode.flag)
            .putBoolean(KEY_SKIP_PERMISSIONS, config.skipPermissions)
            .putBoolean(KEY_SANDBOX, config.sandbox)
            .apply()
        _config.value = config
    }

    /** What the last analysis the user saw was about: a new CLI version or new models. */
    fun seenAnalysisFingerprint(): String? = prefs.getString(KEY_ANALYSIS_SEEN, null)

    fun markAnalysisSeen(fingerprint: String) {
        prefs.edit().putString(KEY_ANALYSIS_SEEN, fingerprint).apply()
    }

    private fun read(): HarnessConfig = HarnessConfig(
        model = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() },
        effort = AgyEffort.fromFlag(prefs.getString(KEY_EFFORT, null)),
        mode = AgyMode.fromFlag(prefs.getString(KEY_MODE, null)),
        skipPermissions = prefs.getBoolean(KEY_SKIP_PERMISSIONS, false),
        sandbox = prefs.getBoolean(KEY_SANDBOX, false)
    )

    private companion object {
        const val PREFS = "harness_prefs"
        const val KEY_BINARY = "agy_binary_path"
        const val KEY_MODEL = "model"
        const val KEY_EFFORT = "effort"
        const val KEY_MODE = "mode"
        const val KEY_SKIP_PERMISSIONS = "skip_permissions"
        const val KEY_SANDBOX = "sandbox"
        const val KEY_ANALYSIS_SEEN = "analysis_seen"

        /** Inside the Ubuntu rootfs the CLI is installed for root; in Termux, in ~/.local/bin. */
        fun defaultBinaryPath(context: Context): String =
            if (!LocalEnvironmentManager.isTermuxPackage(context) && LocalEnvironmentManager.isUbuntuInstalled(context)) {
                "/root/.local/bin/agy"
            } else {
                LocalEnvironmentManager.getHomeDir(context).absolutePath + "/.local/bin/agy"
            }
    }
}
