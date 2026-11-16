package com.example.gemini.data.harness.agy

import android.content.Context
import android.util.Log
import com.example.gemini.data.harness.model.HarnessConfig
import com.example.gemini.data.local.LocalEnvironmentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

/** What the engine reports while a session runs. */
sealed interface AgyEngineEvent {
    /** A parsed line from the CLI's event stream. */
    data class Stream(val event: AgyStreamEvent) : AgyEngineEvent

    /** A non-JSON line: the CLI's diagnostics go to stderr, merged into stdout. */
    data class Diagnostic(val line: String) : AgyEngineEvent

    /** The process ended. */
    data class Exited(val code: Int) : AgyEngineEvent
}

/**
 * Owns one Antigravity CLI process running `--input-format stream-json`.
 *
 * The process is long lived: one `user` line on stdin runs one turn, and every turn
 * ends with a `result` event. Changing model, effort or mode needs a new process
 * (restarted with `--conversation <id>`), because those flags are read at startup.
 */
class AgyCliEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var process: Process? = null
    private var readerJob: Job? = null

    private val _events = MutableSharedFlow<AgyEngineEvent>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<AgyEngineEvent> = _events.asSharedFlow()

    val isRunning: Boolean get() = process?.isAlive == true

    /**
     * Starts the CLI.
     *
     * @param binaryPath the `agy` binary, as the CLI's own filesystem sees it.
     * @param workingDir where the CLI runs; the workspace it can edit.
     * @return failure when the process could not be started at all.
     */
    fun start(
        binaryPath: String,
        config: HarnessConfig,
        resumeConversationId: String? = null,
        workingDir: String? = null
    ): Result<Unit> {
        close()
        val args = AgyCliProtocol.buildArgs(config, resumeConversationId)
        val command = "exec " + (listOf(binaryPath) + args).joinToString(" ") { shellQuote(it) }
        return runCatching {
            val started = LocalEnvironmentManager.startInteractiveProcess(
                context = context,
                command = command,
                workingDir = workingDir,
                customEnv = mapOf("GEMINI_CLI_HEADLESS" to "1")
            )
            process = started
            readerJob = scope.launch(Dispatchers.IO) { readLoop(started) }
            Log.i(TAG, "agy started: $binaryPath ${args.joinToString(" ")}")
        }.onFailure { Log.e(TAG, "Could not start agy: ${it.message}", it) }
    }

    /** Sends one prompt. Returns false when the session is not accepting input. */
    fun sendPrompt(text: String): Boolean {
        val process = this.process ?: return false
        if (!process.isAlive) return false
        return runCatching {
            process.outputStream.write((AgyCliProtocol.userMessageJson(text) + "\n").toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
            true
        }.getOrElse {
            Log.e(TAG, "Could not write prompt: ${it.message}", it)
            false
        }
    }

    /** Stops the current turn by ending the process; the CLI reports no result for it. */
    fun cancel() {
        process?.let {
            runCatching { it.outputStream.close() }
            it.destroy()
        }
    }

    /** Ends the session: closes stdin so the CLI finishes cleanly, then destroys it. */
    fun close() {
        readerJob?.cancel()
        readerJob = null
        process?.let {
            runCatching { it.outputStream.close() }
            it.destroy()
        }
        process = null
    }

    private suspend fun readLoop(process: Process) {
        try {
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val parsed = AgyCliProtocol.parseLine(line)
                    if (parsed != null) {
                        _events.emit(AgyEngineEvent.Stream(parsed))
                    } else if (isWorthShowing(line)) {
                        _events.emit(AgyEngineEvent.Diagnostic(line.trim()))
                    }
                }
            }
        } catch (e: Exception) {
            if (process.isAlive) Log.w(TAG, "Reading agy output failed: ${e.message}")
        } finally {
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            if (this.process === process) this.process = null
            _events.emit(AgyEngineEvent.Exited(code))
        }
    }

    /** The CLI logs a noisy Google banner on every run; only keep lines that help. */
    private fun isWorthShowing(line: String): Boolean {
        val text = line.trim()
        if (text.isEmpty()) return false
        if (text.startsWith("ERROR: logging before google.Init")) return false
        if (text.startsWith("warning: ignoring unsupported stream input message event")) return false
        return true
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val TAG = "AgyCliEngine"
    }
}
