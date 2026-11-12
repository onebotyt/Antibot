package com.example.gemini.data.agent.dsh

import android.content.Context
import android.util.Log
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ModelFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class DshStreamEvent {
    data class Token(val text: String) : DshStreamEvent()
    data class Thought(val thought: String) : DshStreamEvent()
    data class ToolStart(val id: String, val toolName: String, val input: String) : DshStreamEvent()
    data class ToolFinish(val id: String, val output: String, val exitCode: Int = 0) : DshStreamEvent()
    data class StepFinished(val stepIndex: Int, val totalSteps: Int = 1) : DshStreamEvent()
    data class Done(val fullReply: String, val totalDurationMs: Long = 0L) : DshStreamEvent()
    data class Error(val error: String) : DshStreamEvent()
}

data class DshPluginInfo(
    val name: String,
    val description: String = "",
    val version: String = "1.0.0",
    val enabled: Boolean = true,
    val tools: List<String> = emptyList()
)

class DshBridgeClient(
    private val context: Context,
    private val prefs: DshPreferences = DshPreferences(context)
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json".toMediaType()

    private val defaultModels = listOf(
        AiModel(
            id = "deepseek-chat",
            displayName = "DeepSeek-V3 (Chat & Coding)",
            family = ModelFamily.DEEPSEEK,
            supportsThinking = false,
            description = "DeepSeek-V3 general reasoning, multi-language coding and tool dispatch.",
            isDefault = true
        ),
        AiModel(
            id = "deepseek-reasoner",
            displayName = "DeepSeek-R1 (Deep Reasoning)",
            family = ModelFamily.DEEPSEEK,
            supportsThinking = true,
            description = "DeepSeek-R1 deep chain-of-thought reasoning, math and architectural planning."
        )
    )

    private val _cachedModels = MutableStateFlow<List<AiModel>>(defaultModels)
    val cachedModels: StateFlow<List<AiModel>> = _cachedModels.asStateFlow()

    private fun baseUrl(): String = DshPreferences.getHttpBaseUrl(context)
    private fun wsUrl(): String = DshPreferences.getWsBaseUrl(context)

    suspend fun isDaemonOnline(): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${baseUrl()}/api/status")
            .get()
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        }.getOrElse {
            // Also try root endpoint as fallback health check
            runCatching {
                val rootReq = Request.Builder().url(baseUrl()).get().build()
                client.newCall(rootReq).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
    }

    suspend fun getAvailableModels(): List<AiModel> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${baseUrl()}/api/models").get().build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use defaultModels
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val arr = json.optJSONArray("models") ?: JSONArray()
                val list = mutableListOf<AiModel>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.optString("id")
                    val name = obj.optString("name", id)
                    val think = id.contains("reasoner", true) || id.contains("r1", true)
                    list.add(
                        AiModel(
                            id = id,
                            displayName = name,
                            family = ModelFamily.DEEPSEEK,
                            supportsThinking = think,
                            description = obj.optString("description", "DeepSeek model via DSH")
                        )
                    )
                }
                val result = if (list.isNotEmpty()) list else defaultModels
                _cachedModels.value = result
                result
            }
        }.getOrElse {
            _cachedModels.value = defaultModels
            defaultModels
        }
    }

    suspend fun listPlugins(): List<DshPluginInfo> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${baseUrl()}/api/plugins").get().build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val arr = json.optJSONArray("plugins") ?: JSONArray()
                val list = mutableListOf<DshPluginInfo>()
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    val toolsArr = p.optJSONArray("tools") ?: JSONArray()
                    val tools = (0 until toolsArr.length()).map { toolsArr.getString(it) }
                    list.add(
                        DshPluginInfo(
                            name = p.optString("name"),
                            description = p.optString("description"),
                            version = p.optString("version", "1.0.0"),
                            enabled = p.optBoolean("enabled", true),
                            tools = tools
                        )
                    )
                }
                list
            }
        }.getOrDefault(emptyList())
    }

    suspend fun fetchPlugins(): List<String> = listPlugins().map { it.name }

    /**
     * Connects to DeepSeek Harness daemon stream for a conversation turn.
     * Yields incremental tokens, thoughts, tool executions, and step completion.
     */
    fun sendTurnStream(
        conversationId: String,
        prompt: String,
        modelId: String,
        workspacePath: String?
    ): Flow<DshStreamEvent> = callbackFlow {
        val payload = JSONObject().apply {
            put("conversationId", conversationId)
            put("prompt", prompt)
            put("model", modelId.ifBlank { prefs.modelId.value })
            workspacePath?.let { put("workspace", it) }
        }

        val wsRequest = Request.Builder()
            .url(wsUrl())
            .build()

        var webSocket: WebSocket? = null
        var isClosed = false

        webSocket = client.newWebSocket(wsRequest, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d("DshBridgeClient", "[DSH WS] Connected, sending turn prompt")
                ws.send(payload.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val event = JSONObject(text)
                    when (event.optString("type")) {
                        "token", "content" -> {
                            trySend(DshStreamEvent.Token(event.optString("text")))
                        }
                        "thought", "think" -> {
                            trySend(DshStreamEvent.Thought(event.optString("text")))
                        }
                        "tool_start" -> {
                            trySend(
                                DshStreamEvent.ToolStart(
                                    id = event.optString("id"),
                                    toolName = event.optString("name", "bash"),
                                    input = event.optString("input")
                                )
                            )
                        }
                        "tool_finish" -> {
                            trySend(
                                DshStreamEvent.ToolFinish(
                                    id = event.optString("id"),
                                    output = event.optString("output"),
                                    exitCode = event.optInt("exitCode", 0)
                                )
                            )
                        }
                        "step_finished" -> {
                            trySend(
                                DshStreamEvent.StepFinished(
                                    stepIndex = event.optInt("stepIndex", 0),
                                    totalSteps = event.optInt("totalSteps", 1)
                                )
                            )
                        }
                        "done" -> {
                            trySend(
                                DshStreamEvent.Done(
                                    fullReply = event.optString("reply"),
                                    totalDurationMs = event.optLong("durationMs", 0L)
                                )
                            )
                            close()
                        }
                        "error" -> {
                            trySend(DshStreamEvent.Error(event.optString("message", "Unknown DSH error")))
                            close()
                        }
                    }
                } catch (e: Exception) {
                    Log.w("DshBridgeClient", "[DSH WS] Event parse exception: ${e.message}")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e("DshBridgeClient", "[DSH WS] Failure: ${t.message}")
                if (!isClosed) {
                    trySend(DshStreamEvent.Error(t.message ?: "DSH connection failed"))
                    close(t)
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isClosed = true
                channel.close()
            }
        })

        awaitClose {
            isClosed = true
            webSocket?.close(1000, "Turn cancelled or completed")
        }
    }
}
