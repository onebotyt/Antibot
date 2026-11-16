package com.example.gemini.data.harness.agy

import com.example.gemini.data.harness.model.HarnessConfig
import com.example.gemini.data.harness.model.HarnessUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The Antigravity CLI's stream-json protocol, with no Android or process code so it
 * can be unit tested on the JVM.
 *
 * A session emits one `init` event, any number of `step_update` events, and one
 * `result` event per turn. The app writes one `user` event per line to stdin.
 */
object AgyCliProtocol {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** One prompt, as `--input-format stream-json` expects it. */
    fun userMessageJson(text: String): String =
        buildJsonObject {
            put("event", "user")
            put(
                "message",
                buildJsonObject { put("content", text) }
            )
        }.toString()

    /**
     * CLI arguments for a persistent stream-json session.
     *
     * @param resumeConversationId conversation to continue, or null for a fresh one.
     */
    fun buildArgs(config: HarnessConfig, resumeConversationId: String? = null): List<String> = buildList {
        add("--input-format")
        add("stream-json")
        add("--output-format")
        add("stream-json")
        config.model?.takeIf { it.isNotBlank() }?.let {
            add("--model")
            add(it)
        }
        add("--effort")
        add(config.effort.flag)
        add("--mode")
        add(config.mode.flag)
        if (config.sandbox) add("--sandbox")
        if (config.skipPermissions) add("--dangerously-skip-permissions")
        if (!resumeConversationId.isNullOrBlank()) {
            add("--conversation")
            add(resumeConversationId)
        }
    }

    /** Parses one stdout line. Returns null for blank lines, logs and warnings. */
    fun parseLine(line: String): AgyStreamEvent? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return null
        val root = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
        val name = root.string("event") ?: return null
        return when (name) {
            "init" -> parseInit(root)
            "step_update" -> root.obj("step_update")?.let { AgyStreamEvent.Step(parseStep(it)) }
            "result" -> root.obj("result")?.let { AgyStreamEvent.Result(parseResult(it)) }
            else -> AgyStreamEvent.Unknown(name, root)
        }
    }

    private fun parseInit(root: JsonObject): AgyStreamEvent.Init {
        val init = root.obj("init") ?: JsonObject(emptyMap())
        return AgyStreamEvent.Init(
            conversationId = root.string("conversation_id") ?: init.string("conversation_id").orEmpty(),
            cwd = init.string("cwd"),
            tools = init.arrayOfStrings("tools"),
            permissionMode = init.string("permission_mode"),
            model = init.string("model"),
            agent = init.string("agent")
        )
    }

    private fun parseStep(step: JsonObject): AgyStep {
        val toolInfo = step.obj("tool_info")?.let { info ->
            val error = info.obj("error")
            AgyToolInfo(
                name = info.string("name") ?: step.string("tool_name").orEmpty(),
                parameters = info["parameters"],
                output = info.string("output"),
                errorType = error?.string("type"),
                errorMessage = error?.string("message")
            )
        }
        return AgyStep(
            conversationId = step.string("conversation_id").orEmpty(),
            stepIndex = step["step_index"]?.jsonPrimitive?.intOrNull ?: -1,
            state = AgyStepState.from(step.string("state")),
            type = AgyStepType.from(step.string("step_type")),
            toolName = step.string("tool_name"),
            textDelta = step.string("text_delta"),
            durationSeconds = step["duration_seconds"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            usage = step.obj("usage")?.let(::parseUsage),
            tool = toolInfo
        )
    }

    private fun parseResult(result: JsonObject): AgyTurnResult = AgyTurnResult(
        conversationId = result.string("conversation_id").orEmpty(),
        status = AgyResultStatus.from(result.string("status")),
        response = result.string("response").orEmpty(),
        error = result.string("error"),
        durationSeconds = result["duration_seconds"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
        numTurns = result["num_turns"]?.jsonPrimitive?.intOrNull ?: 0,
        usage = result.obj("usage")?.let(::parseUsage),
        structuredOutput = result["structured_output"]
    )

    private fun parseUsage(usage: JsonObject): HarnessUsage = HarnessUsage(
        inputTokens = usage.long("input_tokens"),
        outputTokens = usage.long("output_tokens"),
        thinkingTokens = usage.long("thinking_tokens"),
        cacheReadTokens = usage.long("cache_read_tokens"),
        totalTokens = usage.long("total_tokens")
    )

    private fun JsonObject.string(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun JsonObject.long(key: String): Long =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L

    private fun JsonObject.obj(key: String): JsonObject? =
        (this[key] as? JsonObject)

    private fun JsonObject.arrayOfStrings(key: String): List<String> =
        (this[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            .orEmpty()
}

/** A parsed line from the CLI's stdout. */
sealed interface AgyStreamEvent {
    /** Emitted once when the session starts. */
    data class Init(
        val conversationId: String,
        val cwd: String?,
        val tools: List<String>,
        val permissionMode: String?,
        val model: String?,
        val agent: String?
    ) : AgyStreamEvent

    /** A step inside the current turn. */
    data class Step(val step: AgyStep) : AgyStreamEvent

    /** The turn finished. */
    data class Result(val result: AgyTurnResult) : AgyStreamEvent

    /** An event this app version does not know yet. */
    data class Unknown(val event: String, val raw: JsonObject) : AgyStreamEvent
}

enum class AgyStepState {
    ACTIVE, DONE, UNKNOWN;

    companion object {
        fun from(raw: String?): AgyStepState = when (raw?.uppercase()) {
            "ACTIVE" -> ACTIVE
            "DONE" -> DONE
            else -> UNKNOWN
        }
    }
}

enum class AgyStepType {
    USER_INPUT, AGENT_RESPONSE, TOOL, CHECKPOINT, UNKNOWN;

    companion object {
        fun from(raw: String?): AgyStepType = when (raw) {
            "user_input" -> USER_INPUT
            "agent_response" -> AGENT_RESPONSE
            "tool" -> TOOL
            "checkpoint" -> CHECKPOINT
            else -> UNKNOWN
        }
    }
}

enum class AgyResultStatus {
    SUCCESS, ERROR, CANCELED, INTERRUPTED, INVALID, WAITING, RUNNING, UNKNOWN;

    val isFailure: Boolean get() = this == ERROR || this == INVALID

    companion object {
        fun from(raw: String?): AgyResultStatus = when (raw?.uppercase()) {
            "SUCCESS" -> SUCCESS
            "ERROR" -> ERROR
            "CANCELED" -> CANCELED
            "INTERRUPTED" -> INTERRUPTED
            "INVALID" -> INVALID
            "WAITING" -> WAITING
            "RUNNING" -> RUNNING
            else -> UNKNOWN
        }
    }
}

/** The call and result of one tool step. */
data class AgyToolInfo(
    val name: String,
    val parameters: kotlinx.serialization.json.JsonElement? = null,
    val output: String? = null,
    val errorType: String? = null,
    val errorMessage: String? = null
) {
    val isError: Boolean get() = errorType != null || errorMessage != null
}

data class AgyStep(
    val conversationId: String,
    val stepIndex: Int,
    val state: AgyStepState,
    val type: AgyStepType,
    val toolName: String? = null,
    val textDelta: String? = null,
    val durationSeconds: Double? = null,
    val usage: HarnessUsage? = null,
    val tool: AgyToolInfo? = null
)

data class AgyTurnResult(
    val conversationId: String,
    val status: AgyResultStatus,
    val response: String,
    val error: String? = null,
    val durationSeconds: Double = 0.0,
    val numTurns: Int = 0,
    val usage: HarnessUsage? = null,
    val structuredOutput: kotlinx.serialization.json.JsonElement? = null
)
