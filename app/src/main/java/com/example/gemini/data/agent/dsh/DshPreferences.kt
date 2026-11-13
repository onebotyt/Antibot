package com.example.gemini.data.agent.dsh

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preferences for the DeepSeek Harness (DSH) daemon, Cordis runtime, and multi-CLI integration.
 */
class DshPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, "") ?: "")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _baseUrl = MutableStateFlow(prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL)
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _port = MutableStateFlow(prefs.getInt(KEY_PORT, DEFAULT_PORT))
    val port: StateFlow<Int> = _port.asStateFlow()
    val daemonPort: StateFlow<Int> = _port.asStateFlow()

    private val _modelId = MutableStateFlow(prefs.getString(KEY_MODEL_ID, DEFAULT_MODEL) ?: DEFAULT_MODEL)
    val modelId: StateFlow<String> = _modelId.asStateFlow()

    private val _profile = MutableStateFlow(prefs.getString(KEY_PROFILE, "standard") ?: "standard")
    val profile: StateFlow<String> = _profile.asStateFlow()

    private val _daemonAutoStart = MutableStateFlow(prefs.getBoolean(KEY_AUTO_START, true))
    val daemonAutoStart: StateFlow<Boolean> = _daemonAutoStart.asStateFlow()
    val autoStartDaemon: StateFlow<Boolean> = _daemonAutoStart.asStateFlow()

    private val _dshCommand = MutableStateFlow(prefs.getString(KEY_DSH_COMMAND, DEFAULT_COMMAND) ?: DEFAULT_COMMAND)
    val dshCommand: StateFlow<String> = _dshCommand.asStateFlow()

    /** Port the harness web UI is served on (the app shows it full screen). */
    private val _harnessPort = MutableStateFlow(prefs.getInt(KEY_HARNESS_PORT, DEFAULT_HARNESS_PORT))
    val harnessPort: StateFlow<Int> = _harnessPort.asStateFlow()

    /** Command used to launch the app's own harness web UI. */
    private val _harnessCommand = MutableStateFlow(prefs.getString(KEY_HARNESS_COMMAND, DEFAULT_HARNESS_COMMAND) ?: DEFAULT_HARNESS_COMMAND)
    val harnessCommand: StateFlow<String> = _harnessCommand.asStateFlow()

    /**
     * Optional URL of an already running harness (for example one started from
     * Termux' Ubuntu). When it is set the app shows that harness instead of
     * starting its own. The launch token may be included, e.g.
     * `http://127.0.0.1:4010/?token=...`.
     */
    private val _harnessUrl = MutableStateFlow(prefs.getString(KEY_HARNESS_URL, "") ?: "")
    val harnessUrl: StateFlow<String> = _harnessUrl.asStateFlow()

    fun setHarnessPort(portNum: Int) {
        val p = portNum.coerceIn(1024, 65535)
        prefs.edit().putInt(KEY_HARNESS_PORT, p).apply()
        _harnessPort.value = p
    }

    fun setHarnessCommand(cmd: String) {
        val c = cmd.trim().ifEmpty { DEFAULT_HARNESS_COMMAND }
        prefs.edit().putString(KEY_HARNESS_COMMAND, c).apply()
        _harnessCommand.value = c
    }

    fun setHarnessUrl(url: String) {
        val u = url.trim()
        prefs.edit().putString(KEY_HARNESS_URL, u).apply()
        _harnessUrl.value = u
    }

    fun setApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply()
        _apiKey.value = key.trim()
    }

    fun setBaseUrl(url: String) {
        val u = url.trim().ifEmpty { DEFAULT_BASE_URL }
        prefs.edit().putString(KEY_BASE_URL, u).apply()
        _baseUrl.value = u
    }

    fun setPort(portNum: Int) {
        val p = portNum.coerceIn(1024, 65535)
        prefs.edit().putInt(KEY_PORT, p).apply()
        _port.value = p
    }

    fun setDaemonPort(portNum: Int) = setPort(portNum)

    fun setModelId(id: String) {
        prefs.edit().putString(KEY_MODEL_ID, id.trim()).apply()
        _modelId.value = id.trim()
    }

    fun setProfile(mode: String) {
        prefs.edit().putString(KEY_PROFILE, mode.trim()).apply()
        _profile.value = mode.trim()
    }

    fun setDaemonAutoStart(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
        _daemonAutoStart.value = enabled
    }

    fun setDshCommand(cmd: String) {
        prefs.edit().putString(KEY_DSH_COMMAND, cmd.trim()).apply()
        _dshCommand.value = cmd.trim()
    }

    companion object {
        const val PREFS_NAME = "dsh_preferences"
        const val KEY_API_KEY = "dsh_api_key"
        const val KEY_BASE_URL = "dsh_base_url"
        const val KEY_PORT = "dsh_port"
        const val KEY_MODEL_ID = "dsh_model_id"
        const val KEY_PROFILE = "dsh_profile"
        const val KEY_AUTO_START = "dsh_auto_start"
        const val KEY_DSH_COMMAND = "dsh_command"
        const val KEY_HARNESS_PORT = "dsh_harness_port"
        const val KEY_HARNESS_COMMAND = "dsh_harness_command"
        const val KEY_HARNESS_URL = "dsh_harness_url"

        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_PORT = 3080
        // The app's own harness runs on its own port so it never clashes with a
        // harness the user already started from Termux (dsh-web uses 4010).
        const val DEFAULT_HARNESS_PORT = 4011
        const val DEFAULT_MODEL = "deepseek-chat"
        const val REASONER_MODEL = "deepseek-reasoner"
        const val DEFAULT_COMMAND = "npx @deepseek-ai/dsh web"
        /**
         * The app installs and runs the harness itself (see [HarnessInstaller]);
         * this value means "use that managed launcher".
         */
        const val DEFAULT_HARNESS_COMMAND = "app-managed"
        /** The launcher command older versions stored; treated as [DEFAULT_HARNESS_COMMAND]. */
        const val LEGACY_HARNESS_COMMAND = "npx -y @deepseek-ai/dsh@next web"

        fun getHttpBaseUrl(context: Context): String {
            val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_PORT, DEFAULT_PORT)
            return "http://127.0.0.1:$p"
        }

        fun getWsBaseUrl(context: Context): String {
            val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_PORT, DEFAULT_PORT)
            return "ws://127.0.0.1:$p/ws"
        }
    }
}
