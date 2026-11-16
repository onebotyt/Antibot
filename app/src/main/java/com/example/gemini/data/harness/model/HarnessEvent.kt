package com.example.gemini.data.harness.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Common envelope for every durable harness event.
 *
 * Invariant: every turn the CLI sees must be reconstructable from the durable log,
 * so the transcript on screen and the transcript the agent runs on never drift.
 */
@Serializable
data class HarnessEvent(
    val seq: Long,
    val type: String,
    val time: String, // ISO-8601 UTC timestamp
    val data: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())
)

/** Event type names. Kept as strings so the log stays readable and forward-compatible. */
object HarnessEventType {
    /** The CLI process started (or restarted) for this session. */
    const val SESSION_STARTED = "session/started"

    /** A prompt the user sent. */
    const val USER_MESSAGE = "user/message"

    /** The agent's answer for one turn. */
    const val ASSISTANT_MESSAGE = "assistant/message"

    /** A tool the agent ran. */
    const val TOOL_CALL = "tool/call"

    /** What that tool returned. */
    const val TOOL_RESULT = "tool/result"

    /** One turn started. */
    const val TURN_STARTED = "turn/started"

    /** One turn finished: status, usage, timing. */
    const val TURN_RESULT = "turn/result"

    /** The user stopped the turn. */
    const val TURN_CANCELED = "turn/canceled"

    /** Diagnostics from the CLI's stderr (never model-visible). */
    const val CLI_STDERR = "cli/stderr"

    /** The CLI process exited. */
    const val CLI_EXIT = "cli/exit"
}

/** Message roles used by the durable transcript projection. */
enum class MessageRole {
    @SerialName("user")
    USER,
    @SerialName("assistant")
    ASSISTANT,
    @SerialName("system")
    SYSTEM,
    @SerialName("tool")
    TOOL
}

/** A content block inside a projected message. */
@Serializable
sealed class ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlock()

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(val reasoning: String) : ContentBlock()

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonElement
    ) : ContentBlock()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        val content: String,
        @SerialName("is_error") val isError: Boolean = false
    ) : ContentBlock()
}

/** One projected message: what the agent was shown, rebuilt from the log. */
@Serializable
data class HarnessMessage(
    val role: String,
    val content: List<ContentBlock>
)

/** Token accounting reported by the CLI for a turn. */
@Serializable
data class HarnessUsage(
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("thinking_tokens") val thinkingTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0
)

/** How the CLI is allowed to act while it works. Mirrors `--mode`. */
enum class AgyMode(val flag: String, val label: String) {
    PLAN("plan", "Plan"),
    ACCEPT_EDITS("accept-edits", "Accept edits"),
    AUTO("auto", "Auto");

    companion object {
        fun fromFlag(raw: String?): AgyMode =
            entries.firstOrNull { it.flag == raw } ?: ACCEPT_EDITS
    }
}

/** Reasoning depth. Mirrors `--effort`. */
enum class AgyEffort(val flag: String, val label: String) {
    LOW("low", "Low"),
    MEDIUM("medium", "Medium"),
    HIGH("high", "High"),
    XHIGH("xhigh", "XHigh"),
    MAX("max", "Max");

    companion object {
        fun fromFlag(raw: String?): AgyEffort =
            entries.firstOrNull { it.flag == raw } ?: HIGH
    }
}

/** Settings that decide what the CLI may do without asking; a restart applies them. */
@Serializable
data class HarnessConfig(
    val model: String? = null,
    val effort: AgyEffort = AgyEffort.HIGH,
    val mode: AgyMode = AgyMode.ACCEPT_EDITS,
    /** `--dangerously-skip-permissions`: the CLI stops soft-denying tools. */
    val skipPermissions: Boolean = false,
    /** `--sandbox`: run tools inside the CLI's sandbox. */
    val sandbox: Boolean = false,
    /** Workspace the CLI runs in (guest path when hosted in the Ubuntu rootfs). */
    val workingDir: String? = null
)
