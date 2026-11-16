package com.example.gemini.data.harness.session

import com.example.gemini.data.harness.model.ContentBlock
import com.example.gemini.data.harness.model.HarnessEvent
import com.example.gemini.data.harness.model.HarnessMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages durable append-only session events stored in JSONL format:
 * `filesDir/harness/sessions/<sessionId>/session.v1.jsonl`
 *
 * Implements the core invariant:
 * Model-Visible <==> Logged
 * `deriveMessages()` reconstructs model input directly from the durable log.
 */
class SessionStore(
    private val sessionsDir: File
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    private val fileLocks = mutableMapOf<String, Mutex>()
    private val globalLock = Mutex()

    private suspend fun getLock(sessionId: String): Mutex {
        return globalLock.withLock {
            fileLocks.getOrPut(sessionId) { Mutex() }
        }
    }

    private fun getSessionFile(sessionId: String): File {
        val dir = File(sessionsDir, sessionId)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "session.v1.jsonl")
    }

    /**
     * Reads all durable events for a given session.
     */
    suspend fun readEvents(sessionId: String): List<HarnessEvent> = withContext(Dispatchers.IO) {
        val lock = getLock(sessionId)
        lock.withLock {
            val file = getSessionFile(sessionId)
            if (!file.exists()) return@withContext emptyList()

            file.useLines { lines ->
                lines.filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        try {
                            json.decodeFromString<HarnessEvent>(line)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    .toList()
            }
        }
    }

    /**
     * Appends a new durable event to the session's JSONL file.
     * Assigns a monotonic sequence number if not already present or <= 0.
     */
    suspend fun appendEvent(
        sessionId: String,
        type: String,
        data: JsonObject = JsonObject(emptyMap())
    ): HarnessEvent = withContext(Dispatchers.IO) {
        val lock = getLock(sessionId)
        lock.withLock {
            val file = getSessionFile(sessionId)
            var lastSeq = 0L

            if (file.exists()) {
                // Find highest existing seq
                file.useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        try {
                            val parsed = json.decodeFromString<HarnessEvent>(line)
                            if (parsed.seq > lastSeq) lastSeq = parsed.seq
                        } catch (_: Exception) {}
                    }
                }
            }

            val nextSeq = lastSeq + 1
            val isoTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())

            val event = HarnessEvent(
                seq = nextSeq,
                type = type,
                time = isoTime,
                data = data
            )

            val serialized = json.encodeToString(event)
            file.appendText(serialized + "\n")
            event
        }
    }

    /**
     * Rebuilds the transcript the agent has seen by walking the durable log
     * from start to finish.
     *
     * Invariant: model-visible <==> logged. The CLI keeps its own history, so this
     * is the app's copy: what the UI shows and what a re-sent prompt would contain.
     */
    suspend fun deriveMessages(sessionId: String): List<HarnessMessage> {
        val events = readEvents(sessionId)
        val messages = mutableListOf<HarnessMessage>()

        for (event in events) {
            when (event.type) {
                "user/message" -> {
                    val text = event.data["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    messages.add(
                        HarnessMessage(
                            role = "user",
                            content = listOf(ContentBlock.Text(text))
                        )
                    )
                }

                "assistant/message" -> {
                    val blocks = mutableListOf<ContentBlock>()
                    val reasoning = event.data["reasoning"]?.jsonPrimitive?.contentOrNull
                    if (!reasoning.isNullOrBlank()) {
                        blocks.add(ContentBlock.Reasoning(reasoning))
                    }
                    val text = event.data["content"]?.jsonPrimitive?.contentOrNull
                    if (!text.isNullOrBlank()) {
                        blocks.add(ContentBlock.Text(text))
                    }
                    if (blocks.isNotEmpty()) {
                        messages.add(HarnessMessage(role = "assistant", content = blocks))
                    }
                }

                "tool/call" -> {
                    val name = event.data["tool_name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                    val input = event.data["parameters"] ?: JsonObject(emptyMap())
                    messages.add(
                        HarnessMessage(
                            role = "assistant",
                            content = listOf(ContentBlock.ToolUse(id = name, name = name, input = input))
                        )
                    )
                }

                "tool/result" -> {
                    val toolName = event.data["tool_name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                    val content = event.data["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    val isError = event.data["is_error"]?.jsonPrimitive?.booleanOrNull ?: false
                    messages.add(
                        HarnessMessage(
                            role = "tool",
                            content = listOf(ContentBlock.ToolResult(toolName, content, isError))
                        )
                    )
                }
            }
        }

        return messages
    }

    /**
     * Lists all existing session IDs.
     */
    suspend fun listSessions(): List<String> = withContext(Dispatchers.IO) {
        if (!sessionsDir.exists()) return@withContext emptyList()
        sessionsDir.listFiles { file -> file.isDirectory && File(file, "session.v1.jsonl").exists() }
            ?.map { it.name }
            ?.sortedDescending()
            ?: emptyList()
    }
}
