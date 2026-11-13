package com.example.gemini.data.agent.dsh

import android.content.Context
import android.util.Log
import com.example.gemini.data.local.LocalEnvironmentManager
import com.example.gemini.data.local.LocalPtySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the app's own DeepSeek Harness (`dsh web`) and resolves the URL the
 * full screen WebView loads.
 *
 * The harness authenticates its UI with a one-time launch token
 * (`http://127.0.0.1:<port>/?token=...`): loading it once stores a long-lived
 * cookie, after which the bare URL keeps working. The token is printed on the
 * harness' output, so the launcher keeps that output in a log file the app can
 * read back.
 *
 * A harness that already answers on the configured port (for example one the
 * user started from Termux) is reused as-is; only when nothing answers does the
 * app start its own copy.
 */
object HarnessController {
    private const val TAG = "HarnessController"
    private const val LOG_NAME = ".dsh-harness.log"
    private const val START_TIMEOUT_MS = 120_000L
    private const val TOKEN_WAIT_MS = 5_000L
    private const val PROBE_TIMEOUT_S = 2L

    sealed interface UiState {
        data object Idle : UiState
        data object Starting : UiState
        data class Ready(val url: String) : UiState
        data class Error(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var session: LocalPtySession? = null

    /** Guards the one-time delay that lets the app's launch bootstrap settle. */
    @Volatile
    private var firstStartAttempted = false

    /** Starts (or re-checks) the harness. Safe to call repeatedly. */
    fun refresh(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (!force && startJob?.isActive == true) return
        startJob?.cancel()
        startJob = scope.launch {
            try {
                ensureStarted(app, force)
            } catch (t: Throwable) {
                Log.e(TAG, "Harness start failed", t)
                _state.value = UiState.Error(t.localizedMessage ?: "The harness could not be started")
            }
        }
    }

    private suspend fun ensureStarted(context: Context, force: Boolean) {
        val prefs = DshPreferences(context)
        val port = prefs.harnessPort.value
        val base = baseUrl(port)

        if (!force && _state.value is UiState.Ready && probe(base)) return

        _state.value = UiState.Starting

        if (force) {
            // A forced refresh must actually restart the app's own harness, not
            // reuse the instance that is still answering from the old settings.
            stopSession()
            delay(500)
        }

        // Reuse whatever already serves the port (e.g. a harness started from Termux).
        if (probe(base)) {
            _state.value = UiState.Ready(resolveUrl(context, port))
            return
        }

        if (!firstStartAttempted) {
            firstStartAttempted = true
            // On launch the app's local-server bootstrap kills stray `dsh`
            // processes; give it a moment so it cannot kill the harness we start.
            delay(3000)
        }

        startSession(context, prefs, port)

        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (probe(base)) {
                // The listener accepts connections a moment before the harness
                // prints its launch token; wait for the token so the very first
                // load is already authenticated instead of hitting the 401 page.
                val token = awaitToken(context, TOKEN_WAIT_MS)
                _state.value = UiState.Ready(urlFor(base, token))
                return
            }
            val current = session
            if (current != null && current.isExited.value && !probe(base)) {
                // The launcher exited and nothing is listening: do not keep waiting.
                break
            }
            delay(1000)
        }

        if (probe(base)) {
            _state.value = UiState.Ready(resolveUrl(context, port))
        } else {
            _state.value = UiState.Error(
                "The harness did not come up on port $port. Start 'dsh web' from Termux, then tap Retry."
            )
        }
    }

    private suspend fun startSession(context: Context, prefs: DshPreferences, port: Int) {
        if (session?.isExited?.value == false) return

        val logFile = File(LocalEnvironmentManager.getHomeDir(context), LOG_NAME)
        try {
            logFile.parentFile?.mkdirs()
            if (logFile.exists()) logFile.delete()
            logFile.createNewFile()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not prepare harness log ${logFile.absolutePath}", t)
        }

        val command = buildLaunchCommand(prefs, port, logFile)

        session = withContext(Dispatchers.Main) {
            LocalPtySession(
                id = "harness-runner-pty",
                initialTitle = "DeepSeek Harness",
                context = context,
                isSsh = false,
                initialCommand = command,
                initialCols = 100,
                initialRows = 30,
                // Let the PTY exit when the launcher exits so a failed start is
                // reported immediately instead of waiting for the timeout.
                execShellAfterCommand = false
            )
        }
    }

    private fun buildLaunchCommand(prefs: DshPreferences, port: Int, logFile: File): String {
        val raw = prefs.harnessCommand.value.trim().ifBlank { DshPreferences.DEFAULT_HARNESS_COMMAND }
        val withFlags = buildString {
            append(raw)
            if (!raw.contains("--host")) append(" --host 127.0.0.1")
            if (!raw.contains("--port")) append(" --port ").append(port)
            if (!raw.contains("--no-open")) append(" --no-open")
        }
        // Keep the launcher's output so its one-time token URL can be read back.
        return "$withFlags > \"${logFile.absolutePath}\" 2>&1"
    }

    private fun resolveUrl(context: Context, port: Int): String =
        urlFor(baseUrl(port), readToken(context))

    private fun urlFor(base: String, token: String?): String =
        if (token.isNullOrBlank()) "$base/" else "$base/?token=$token"

    /** Waits briefly for the launch token to appear in the harness log. */
    private suspend fun awaitToken(context: Context, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val token = readToken(context)
            if (!token.isNullOrBlank()) return token
            if (System.currentTimeMillis() >= deadline) return null
            delay(250)
        }
    }

    private fun readToken(context: Context): String? = try {
        val logFile = File(LocalEnvironmentManager.getHomeDir(context), LOG_NAME)
        if (!logFile.exists()) {
            null
        } else {
            Regex("token=([A-Za-z0-9_-]+)")
                .findAll(logFile.readText())
                .lastOrNull()
                ?.groupValues
                ?.getOrNull(1)
        }
    } catch (_: Exception) {
        null
    }

    private fun baseUrl(port: Int) = "http://127.0.0.1:$port"

    private fun probe(base: String): Boolean = try {
        val request = Request.Builder().url("$base/").get().build()
        client.newCall(request).execute().use { response ->
            response.code in 200..399 || response.code == 401
        }
    } catch (_: Exception) {
        false
    }

    fun stopSession() {
        val current = session ?: return
        session = null
        try {
            current.write("\u0003")
        } catch (_: Exception) {
        }
        try {
            current.close()
        } catch (_: Exception) {
        }
    }
}
