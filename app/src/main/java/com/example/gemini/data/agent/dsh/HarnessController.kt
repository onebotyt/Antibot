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
import java.io.RandomAccessFile
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
    /** The first run installs the harness from npm, which takes much longer. */
    private const val INSTALL_TIMEOUT_MS = 45 * 60_000L
    private const val TOKEN_WAIT_MS = 5_000L
    private const val PROBE_TIMEOUT_S = 2L

    /** Where a launcher script can leave the harness URL for the app. */
    private const val SHARED_URL_FILE = "antibot-harness-url.txt"
    private val SHARED_URL_DIRS = listOf(
        "/storage/emulated/0/Download",
        "/sdcard/Download",
        "/storage/emulated/0/Documents"
    )

    sealed interface UiState {
        data object Idle : UiState
        data class Starting(
            val message: String = "Starting the harness…",
            val logs: String = "",
            val elapsedSeconds: Long = 0
        ) : UiState
        data class Ready(val url: String) : UiState
        data class Error(val message: String, val details: String? = null) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        // A valid one-time token responds with a redirect that sets the WebView
        // cookie. Do not follow it here: OkHttp has no cookie jar, so the final
        // page would incorrectly look like an unauthenticated 401.
        .followRedirects(false)
        .followSslRedirects(false)
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

        // A harness the user explicitly configured wins: the app only has to
        // show it. A URL that does not answer (or whose token is invalid) is an
        // error so the user is not silently switched to another harness.
        val configured = prefs.harnessUrl.value.trim()
        if (configured.isNotBlank()) {
            val base = baseOf(configured)
            if (probeExternal(configured)) {
                _state.value = UiState.Ready(configured)
            } else {
                _state.value = UiState.Error(
                    "The configured harness ($base) did not answer, or its launch token is no " +
                        "longer valid. Start it, or clear the Harness URL in Settings → DeepSeek, " +
                        "then tap Retry."
                )
            }
            return
        }

        // The Termux 'dsh-web' script leaves its launch URL in shared storage.
        // A stale file there must not hijack the app, so a URL that does not
        // authenticate simply falls back to the app's own harness.
        val shared = readSharedUrl()
        if (shared != null && probeExternal(shared)) {
            _state.value = UiState.Ready(shared)
            return
        }

        val port = prefs.harnessPort.value
        val base = baseUrl(port)

        if (!force && _state.value is UiState.Ready && probe(base)) return

        val startedAt = System.currentTimeMillis()
        _state.value = UiState.Starting()

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

        val deadline = System.currentTimeMillis() +
            if (isManaged(prefs) && !HarnessInstaller.isReady(context)) INSTALL_TIMEOUT_MS
            else START_TIMEOUT_MS
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
            val recentLog = logTail(context, 10)
            val message = progressMessage(recentLog)
            val elapsed = (System.currentTimeMillis() - startedAt) / 1000L
            _state.value = UiState.Starting(
                message = message,
                logs = recentLog,
                elapsedSeconds = elapsed
            )
            delay(1000)
        }

        if (probe(base)) {
            _state.value = UiState.Ready(resolveUrl(context, port))
        } else {
            val log = logTail(context)
            _state.value = UiState.Error(diagnose(port, log), log.ifBlank { null })
        }
    }

    /**
     * Explains why the launcher did not produce a harness. The harness runs
     * natively now, so a failure is usually the first-run install (large npm
     * download) or the one-time native addon build.
     */
    private fun diagnose(port: Int, log: String): String {
        val text = log.lowercase()
        val fallback = "You can also run the harness in Termux with 'dsh-web': the app picks its " +
            "launch URL up from Download/antibot-harness-url.txt automatically."
        return when {
            text.contains("pnpm add") && text.contains("failed") ->
                "The DeepSeek Harness could not be downloaded. Check the connection and tap Retry. " +
                    "The download is large (several hundred MB) and only happens once. $fallback"
            text.contains("native addon") || text.contains("clang++ failed") ->
                "The native addon the harness needs could not be built on this device. " +
                    "The launcher output is shown below. $fallback"
            text.contains("cache/policy.js") || text.contains("make-fetch-happen") ->
                "npm in this environment is broken, so the app installs the harness with pnpm " +
                    "instead. The launcher output is shown below. $fallback"
            text.isBlank() ->
                "The harness did not come up on port $port. $fallback"
            else ->
                "The harness did not come up on port $port. The launcher output is shown below. $fallback"
        }
    }

    /** A short status line for the start screen, based on the launcher's output. */
    private fun progressMessage(log: String): String {
        val last = log.lines().lastOrNull { it.isNotBlank() } ?: ""
        return when {
            last.contains("Progress: resolved") ->
                "Installing the DeepSeek Harness (first run)…\n$last"
            last.contains("installing @deepseek-ai/dsh") ->
                "Installing the DeepSeek Harness (first run, several hundred MB)…"
            last.contains("building the native addon") ->
                "Building the native addon for Android (one time)…"
            last.contains("starting the harness") ->
                "Starting the harness…"
            else -> "Starting the harness…"
        }
    }

    /** The tail of the launcher's output, for the error screen. */
    fun logTail(context: Context, lines: Int = 12): String = try {
        val logFile = File(LocalEnvironmentManager.getHomeDir(context), LOG_NAME)
        if (!logFile.exists()) {
            ""
        } else {
            RandomAccessFile(logFile, "r").use { file ->
                val bytesToRead = minOf(file.length(), 16 * 1024L)
                file.seek(file.length() - bytesToRead)
                val bytes = ByteArray(bytesToRead.toInt())
                file.readFully(bytes)
                String(bytes, Charsets.UTF_8)
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .takeLast(lines)
                    .joinToString("\n")
            }
        }
    } catch (_: Exception) {
        ""
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

        val command = buildLaunchCommand(context, prefs, port, logFile)

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

    /** True when the app manages the harness (installs and runs it itself). */
    private fun isManaged(prefs: DshPreferences): Boolean {
        val raw = prefs.harnessCommand.value.trim()
        return raw.isEmpty() ||
            raw == DshPreferences.DEFAULT_HARNESS_COMMAND ||
            raw == DshPreferences.LEGACY_HARNESS_COMMAND
    }

    private fun buildLaunchCommand(
        context: Context,
        prefs: DshPreferences,
        port: Int,
        logFile: File
    ): String {
        val command = if (isManaged(prefs)) {
            // The app installs the harness and builds its missing android-arm64
            // native addon, then runs `dsh web`.
            val script = HarnessInstaller.prepare(context)
            "sh \"${script.absolutePath}\" $port"
        } else {
            val raw = prefs.harnessCommand.value.trim()
            buildString {
                append(raw)
                if (!raw.contains("--host")) append(" --host 127.0.0.1")
                if (!raw.contains("--port")) append(" --port ").append(port)
                if (!raw.contains("--no-open")) append(" --no-open")
            }
        }
        // Keep the launcher's output so its one-time token URL can be read back.
        return "$command > \"${logFile.absolutePath}\" 2>&1"
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

    /** The scheme/host/port part of a user supplied harness URL. */
    private fun baseOf(url: String): String = url.trim().substringBefore('?').trimEnd('/')

    private fun readSharedUrl(): String? = try {
        var found: String? = null
        for (dir in SHARED_URL_DIRS) {
            val file = File(dir, SHARED_URL_FILE)
            if (!file.isFile) continue
            val url = file.readText()
                .lines()
                .map { it.trim() }
                .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
            if (!url.isNullOrBlank()) {
                found = url
                break
            }
        }
        found
    } catch (_: Exception) {
        null
    }

    /** Checks both reachability and the supplied launch token for an external URL. */
    private fun probeExternal(url: String): Boolean = try {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            response.code in 200..399
        }
    } catch (_: Exception) {
        false
    }

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
