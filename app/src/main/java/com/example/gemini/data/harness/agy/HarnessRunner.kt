package com.example.gemini.data.harness.agy

import android.content.Context
import com.example.gemini.data.harness.model.HarnessConfig
import com.example.gemini.data.harness.model.HarnessEvent
import com.example.gemini.data.harness.model.HarnessEventType
import com.example.gemini.data.harness.model.HarnessUsage
import com.example.gemini.data.harness.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** One row in the transcript. */
sealed interface HarnessItem {
    data class User(val text: String) : HarnessItem

    data class Assistant(
        val text: String,
        val reasoning: String? = null,
        val streaming: Boolean = false
    ) : HarnessItem

    data class Tool(
        val name: String,
        val arguments: String? = null,
        val output: String? = null,
        val isError: Boolean = false
    ) : HarnessItem

    data class Notice(val text: String, val isError: Boolean = false) : HarnessItem
}

/** Everything the harness screen draws. */
data class HarnessUiState(
    val sessionId: String? = null,
    val conversationId: String? = null,
    val config: HarnessConfig = HarnessConfig(),
    val items: List<HarnessItem> = emptyList(),
    val running: Boolean = false,
    /** A blocking condition, e.g. the CLI needs a sign-in or is not installed. */
    val status: String? = null,
    /** The CLI's sign-in URL, when it is waiting for authentication. */
    val signInUrl: String? = null,
    val lastUsage: HarnessUsage? = null,
    val cliVersion: String? = null
)

data class HarnessSessionSummary(
    val id: String,
    val title: String,
    val lastTime: String
)

/**
 * Drives one Antigravity CLI session and keeps the durable log in step with the screen.
 *
 * The CLI owns the agent loop, its tools and its own history. This class owns the app's
 * side: the process, the transcript, the session log, and the settings that need a restart.
 */
class HarnessRunner(
    private val context: Context,
    private val scope: CoroutineScope,
    val prefs: HarnessPreferences = HarnessPreferences(context),
    private val store: SessionStore = SessionStore(File(context.filesDir, "harness/sessions"))
) {
    private val engine = AgyCliEngine(context, scope)
    private val _state = MutableStateFlow(HarnessUiState(config = prefs.config.value))
    val state: StateFlow<HarnessUiState> = _state.asStateFlow()

    /** Text streamed for the current turn, replaced by the final response. */
    private val streamed = StringBuilder()
    private var cancelRequested = false
    /** The session the running process belongs to, so an exit lands in the right log. */
    private var engineSessionId: String? = null

    init {
        scope.launch { engine.events.collect(::onEngineEvent) }
    }

    fun startNewSession() {
        engine.close()
        streamed.setLength(0)
        _state.value = HarnessUiState(
            sessionId = newSessionId(),
            config = prefs.config.value,
            items = emptyList()
        )
    }

    /** Reopens a logged session: the CLI is resumed by conversation id on the next turn. */
    fun openSession(sessionId: String) {
        engine.close()
        streamed.setLength(0)
        _state.value = HarnessUiState(sessionId = sessionId, config = prefs.config.value)
        scope.launch {
            val events = store.readEvents(sessionId)
            val items = events.mapNotNull(::toItem)
            val conversationId = events.lastOrNull { it.type == HarnessEventType.SESSION_STARTED }
                ?.data?.get("conversation_id")?.let { (it as? JsonPrimitive)?.content }
                ?.takeIf { it.isNotBlank() }
            val usage = events.lastOrNull { it.type == HarnessEventType.TURN_RESULT }
                ?.data?.get("total_tokens")?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() }
                ?.let { HarnessUsage(totalTokens = it) }
            _state.update { it.copy(items = items, conversationId = conversationId, lastUsage = usage) }
        }
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty()) return
        val sessionId = _state.value.sessionId ?: newSessionId().also { id ->
            _state.update { it.copy(sessionId = id) }
        }

        streamed.setLength(0)
        cancelRequested = false
        _state.update {
            it.copy(
                items = it.items + HarnessItem.User(prompt),
                running = true,
                status = null
            )
        }

        scope.launch(Dispatchers.IO) {
            store.appendEvent(
                sessionId,
                HarnessEventType.USER_MESSAGE,
                buildJsonObject { put("content", prompt) }
            )
        }

        if (!engine.isRunning) {
            val config = prefs.config.value
            val started = engine.start(
                binaryPath = prefs.binaryPath,
                config = config,
                resumeConversationId = _state.value.conversationId,
                workingDir = prefs.workingDir
            )
            if (started.isFailure) {
                _state.update {
                    it.copy(
                        running = false,
                        status = "Could not start the Antigravity CLI at ${prefs.binaryPath}."
                    )
                }
                return
            }
            engineSessionId = sessionId
        }

        if (!engine.sendPrompt(prompt)) {
            _state.update {
                it.copy(running = false, status = "The CLI is not accepting input. Send again to restart it.")
            }
        }
    }

    /** Stops the turn. The CLI reports no result for an interrupted turn. */
    fun cancel() {
        if (!_state.value.running) return
        cancelRequested = true
        engine.cancel()
    }

    /** Applies settings; the next turn starts a fresh process with the new flags. */
    fun applyConfig(config: HarnessConfig) {
        prefs.setConfig(config)
        _state.update { it.copy(config = config) }
        if (engine.isRunning) engine.close()
    }

    fun stop() {
        engine.close()
        _state.update { it.copy(running = false) }
    }

    suspend fun sessions(): List<HarnessSessionSummary> = store.listSessions().map { id ->
        val events = store.readEvents(id)
        val firstUser = events.firstOrNull { it.type == HarnessEventType.USER_MESSAGE }
            ?.data?.get("content")?.let { (it as? JsonPrimitive)?.content }
        HarnessSessionSummary(
            id = id,
            title = firstUser?.lineSequence()?.firstOrNull()?.take(60)?.takeIf { it.isNotBlank() } ?: "New session",
            lastTime = events.lastOrNull()?.time.orEmpty()
        )
    }

    private fun onEngineEvent(event: AgyEngineEvent) {
        when (event) {
            is AgyEngineEvent.Stream -> onStreamEvent(event.event)
            is AgyEngineEvent.Diagnostic -> onDiagnostic(event.line)
            is AgyEngineEvent.Exited -> onExited(event.code)
        }
    }

    private fun onStreamEvent(event: AgyStreamEvent) {
        val sessionId = _state.value.sessionId ?: return
        when (event) {
            is AgyStreamEvent.Init -> {
                _state.update {
                    it.copy(
                        conversationId = event.conversationId.takeIf { id -> id.isNotBlank() },
                        config = it.config.copy(model = event.model ?: it.config.model)
                    )
                }
                log(sessionId, HarnessEventType.SESSION_STARTED, buildJsonObject {
                    put("conversation_id", event.conversationId)
                    put("cwd", event.cwd.orEmpty())
                    put("permission_mode", event.permissionMode.orEmpty())
                    put("model", event.model.orEmpty())
                    put("agent", event.agent.orEmpty())
                    put("tools", event.tools.size)
                })
            }

            is AgyStreamEvent.Step -> onStep(event.step)

            is AgyStreamEvent.Result -> {
                val result = event.result
                val finalText = result.response.ifBlank { streamed.toString() }
                if (finalText.isNotBlank()) {
                    _state.update { state ->
                        state.copy(
                            items = state.items.replaceStreaming(
                                HarnessItem.Assistant(text = finalText.trimEnd(), streaming = false)
                            ),
                            running = false,
                            lastUsage = result.usage ?: state.lastUsage,
                            // A real answer means the CLI is signed in and working.
                            signInUrl = null
                        )
                    }
                    log(sessionId, HarnessEventType.ASSISTANT_MESSAGE, buildJsonObject {
                        put("content", finalText.trimEnd())
                    })
                } else {
                    _state.update { it.copy(running = false) }
                }
                log(sessionId, HarnessEventType.TURN_RESULT, buildJsonObject {
                    put("status", result.status.name)
                    put("duration_seconds", result.durationSeconds)
                    put("num_turns", result.numTurns)
                    put("total_tokens", result.usage?.totalTokens ?: 0)
                    result.error?.let { put("error", it) }
                })
                if (result.status.isFailure) {
                    _state.update {
                        it.copy(status = result.error?.takeIf { e -> e.isNotBlank() } ?: "The CLI reported an error.")
                    }
                }
            }

            is AgyStreamEvent.Unknown -> Unit
        }
    }

    private fun onStep(step: AgyStep) {
        when (step.type) {
            AgyStepType.AGENT_RESPONSE -> {
                step.textDelta?.let { streamed.append(it) }
                if (streamed.isNotEmpty()) {
                    _state.update { state ->
                        state.copy(
                            items = state.items.replaceStreaming(
                                HarnessItem.Assistant(text = streamed.toString(), streaming = true)
                            )
                        )
                    }
                }
            }

            AgyStepType.TOOL -> {
                val tool = step.tool ?: return
                if (step.state != AgyStepState.DONE) return
                _state.update { state ->
                    state.copy(
                        items = state.items + HarnessItem.Tool(
                            name = tool.name,
                            arguments = tool.parameters?.toString(),
                            output = tool.output?.trimEnd().orEmpty(),
                            isError = tool.isError
                        )
                    )
                }
                val sessionId = _state.value.sessionId ?: return
                log(sessionId, HarnessEventType.TOOL_CALL, buildJsonObject {
                    put("tool_name", tool.name)
                    tool.parameters?.let { put("parameters", it) }
                })
                log(sessionId, HarnessEventType.TOOL_RESULT, buildJsonObject {
                    put("tool_name", tool.name)
                    put("content", tool.output?.trimEnd().orEmpty())
                    put("is_error", tool.isError)
                })
            }

            else -> Unit
        }
    }

    private fun onDiagnostic(line: String) {
        val sessionId = _state.value.sessionId ?: return
        log(sessionId, HarnessEventType.CLI_STDERR, buildJsonObject { put("line", line) })

        val lower = line.lowercase()
        if (lower.contains("accounts.google.com") || lower.contains("oauth")) {
            val url = Regex("https://\\S+").find(line)?.value
            if (url != null) _state.update { it.copy(signInUrl = url) }
        }
        val status = when {
            lower.contains("authenticat") || lower.contains("sign in") ->
                "Sign in to Antigravity first: run `agy` once in a terminal, then come back."
            lower.contains("no such file") && lower.contains("agy") ->
                "The Antigravity CLI is not installed at ${prefs.binaryPath}."
            lower.contains("permission") && lower.contains("denied") ->
                "A tool was denied. Allow it in the CLI's settings, or turn on skip-permissions."
            else -> null
        }
        if (status != null) _state.update { it.copy(status = status, running = false) }
    }

    private fun onExited(code: Int) {
        val sessionId = engineSessionId
        engineSessionId = null
        val wasRunning = _state.value.running
        val wasCanceled = cancelRequested
        cancelRequested = false

        _state.update { state ->
            val streaming = state.items.lastOrNull { it is HarnessItem.Assistant && it.streaming }
                as? HarnessItem.Assistant
            state.copy(
                items = if (streaming != null) state.items.replaceStreaming(streaming.copy(streaming = false)) else state.items,
                running = false
            )
        }

        if (sessionId != null) {
            log(sessionId, HarnessEventType.CLI_EXIT, buildJsonObject { put("code", code) })
        }

        if (wasRunning && !wasCanceled) {
            _state.update {
                it.copy(
                    items = it.items + HarnessItem.Notice(
                        text = "The CLI exited (code $code). Send a message to restart it.",
                        isError = code != 0
                    )
                )
            }
        }
        if (sessionId != null && wasRunning && wasCanceled) {
            log(sessionId, HarnessEventType.TURN_CANCELED, buildJsonObject { put("code", code) })
        }
    }

    private fun log(
        sessionId: String,
        type: String,
        data: kotlinx.serialization.json.JsonObject
    ) {
        scope.launch(Dispatchers.IO) { store.appendEvent(sessionId, type, data) }
    }

    private fun toItem(event: HarnessEvent): HarnessItem? = when (event.type) {
        HarnessEventType.USER_MESSAGE ->
            (event.data["content"] as? JsonPrimitive)?.content?.let { HarnessItem.User(it) }

        HarnessEventType.ASSISTANT_MESSAGE ->
            (event.data["content"] as? JsonPrimitive)?.content?.let { HarnessItem.Assistant(it) }

        HarnessEventType.TOOL_CALL ->
            (event.data["tool_name"] as? JsonPrimitive)?.content?.let { HarnessItem.Tool(name = it) }

        else -> null
    }

    private fun newSessionId(): String = "session-${System.currentTimeMillis()}"

    /** Replaces the live assistant row, or appends one when the turn has not written yet. */
    private fun List<HarnessItem>.replaceStreaming(item: HarnessItem.Assistant): List<HarnessItem> {
        val index = indexOfLast { it is HarnessItem.Assistant && it.streaming }
        return if (index >= 0) toMutableList().also { it[index] = item } else this + item
    }
}
