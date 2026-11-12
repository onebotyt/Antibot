package com.example.gemini.data.agent.dsh

import android.content.Context
import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
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
import java.io.BufferedReader
import java.io.InputStreamReader
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
        // 1. Check if external dsh web daemon is running on port 3080
        val dshUp = runCatching {
            val req = Request.Builder().url("${baseUrl()}/api/status").get().build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        if (dshUp) return@withContext true

        // 2. Check if local Rust IDE bridge is running
        val bridgeUp = runCatching {
            val req = Request.Builder().url("${AuthPreferences.currentBridgeHttpUrl.trimEnd('/')}/api/health").get().build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        if (bridgeUp) return@withContext true

        // 3. Or if a DeepSeek API key is configured
        prefs.apiKey.value.isNotBlank()
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
        val fetched = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
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
        }.getOrNull()

        if (!fetched.isNullOrEmpty()) return@withContext fetched

        // Built-in Cordis & DSH plugin ecosystem available in AntiBot
        listOf(
            DshPluginInfo("dsh-plugin-cordis", "Spatiotemporal service composition container & reactive lifecycle manager.", "1.2.0", true, listOf("service.load", "service.invoke", "context.fork")),
            DshPluginInfo("dsh-plugin-mcp", "Model Context Protocol gateway for composable tool servers & schemas.", "1.1.0", true, listOf("mcp.call_tool", "mcp.list_resources")),
            DshPluginInfo("dsh-plugin-terminal", "Termux native bash runner with environment variable injection and PTY stream.", "2.0.1", true, listOf("bash.exec", "bash.kill")),
            DshPluginInfo("dsh-plugin-git", "Local offline Git version control, branch manager and diff tracker.", "1.0.4", true, listOf("git.checkpoint", "git.diff", "git.log")),
            DshPluginInfo("dsh-plugin-multi-cli", "Orchestration dispatcher bridging Claude Code, OpenAI Codex, OpenCode, and Antigravity.", "2.4.0", true, listOf("cli.dispatch", "cli.failover"))
        )
    }

    suspend fun fetchPlugins(): List<String> = listPlugins().map { it.name }

    /**
     * Connects to DeepSeek Harness daemon stream for a conversation turn.
     * Automatically fails over between:
     * 1. Local Termux DSH WebSocket daemon (`/ws`)
     * 2. Direct DeepSeek Streaming API (with `<think>` reasoning and token streaming)
     * 3. Local Rust Bridge Subagent Dispatcher (`/api/subagents/invoke`)
     */
    fun sendTurnStream(
        conversationId: String,
        prompt: String,
        modelId: String,
        workspacePath: String?
    ): Flow<DshStreamEvent> = callbackFlow {
        val targetModel = modelId.ifBlank { prefs.modelId.value }
        val startTime = System.currentTimeMillis()

        // 1. Try local DSH daemon WebSocket first if daemon responds
        val isDaemonUp = runCatching {
            val req = Request.Builder().url("${baseUrl()}/api/status").get().build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)

        if (isDaemonUp) {
            val payload = JSONObject().apply {
                put("conversationId", conversationId)
                put("prompt", prompt)
                put("model", targetModel)
                workspacePath?.let { put("workspace", it) }
            }

            val wsRequest = Request.Builder().url(wsUrl()).build()
            var isClosed = false

            val webSocket = client.newWebSocket(wsRequest, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d("DshBridgeClient", "[DSH WS] Connected, sending turn prompt")
                    ws.send(payload.toString())
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    try {
                        val event = JSONObject(text)
                        when (event.optString("type")) {
                            "token", "content" -> trySend(DshStreamEvent.Token(event.optString("text")))
                            "thought", "think" -> trySend(DshStreamEvent.Thought(event.optString("text")))
                            "tool_start" -> trySend(
                                DshStreamEvent.ToolStart(
                                    id = event.optString("id"),
                                    toolName = event.optString("name", "bash"),
                                    input = event.optString("input")
                                )
                            )
                            "tool_finish" -> trySend(
                                DshStreamEvent.ToolFinish(
                                    id = event.optString("id"),
                                    output = event.optString("output"),
                                    exitCode = event.optInt("exitCode", 0)
                                )
                            )
                            "step_finished" -> trySend(
                                DshStreamEvent.StepFinished(
                                    stepIndex = event.optInt("stepIndex", 0),
                                    totalSteps = event.optInt("totalSteps", 1)
                                )
                            )
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
                                trySend(DshStreamEvent.Error(event.optString("message", "DSH error")))
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
                webSocket.close(1000, "Turn cancelled or completed")
            }
            return@callbackFlow
        }

        // 2. Fallback: Direct DeepSeek API streaming if API key configured
        val apiKey = prefs.apiKey.value.trim()
        if (apiKey.isNotBlank()) {
            val apiBase = prefs.baseUrl.value.trimEnd('/')
            val endpoint = if (apiBase.endsWith("/v1")) "$apiBase/chat/completions" else if (apiBase.endsWith("/chat/completions")) apiBase else "$apiBase/chat/completions"

            val bodyJson = JSONObject().apply {
                put("model", targetModel)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You are AntiBot powered by DeepSeek Harness in mobile. You orchestrate tools, skills, and coding workflows.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("stream", true)
            }

            val apiRequest = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "text/event-stream")
                .post(bodyJson.toString().toRequestBody(jsonType))
                .build()

            var fullReply = StringBuilder()
            val call = client.newCall(apiRequest)

            try {
                val response = withContext(Dispatchers.IO) { call.execute() }
                if (!response.isSuccessful) {
                    val err = response.body?.string().orEmpty()
                    trySend(DshStreamEvent.Error("DeepSeek API error ${response.code}: $err"))
                    close()
                    return@callbackFlow
                }

                val source = response.body?.byteStream()
                if (source == null) {
                    trySend(DshStreamEvent.Error("Empty response body from DeepSeek API"))
                    close()
                    return@callbackFlow
                }

                val reader = BufferedReader(InputStreamReader(source))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line?.trim().orEmpty()
                    if (l.startsWith("data: ")) {
                        val data = l.removePrefix("data: ").trim()
                        if (data == "[DONE]") break
                        try {
                            val chunk = JSONObject(data)
                            val choices = chunk.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val delta = choices.getJSONObject(0).optJSONObject("delta")
                                if (delta != null) {
                                    val reasoning = delta.optString("reasoning_content")
                                    if (reasoning.isNotEmpty()) {
                                        trySend(DshStreamEvent.Thought(reasoning))
                                    }
                                    val content = delta.optString("content")
                                    if (content.isNotEmpty()) {
                                        fullReply.append(content)
                                        trySend(DshStreamEvent.Token(content))
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                trySend(DshStreamEvent.Done(fullReply.toString(), System.currentTimeMillis() - startTime))
                close()
            } catch (e: Exception) {
                if (!channel.isClosedForSend) {
                    trySend(DshStreamEvent.Error("DeepSeek stream error: ${e.message}"))
                    close(e)
                }
            }

            awaitClose {
                call.cancel()
            }
            return@callbackFlow
        }

        // 3. Fallback: Local Rust Bridge Subagent Dispatcher
        val bridgeBase = AuthPreferences.currentBridgeHttpUrl.trimEnd('/')
        val invokeUrl = "$bridgeBase/api/subagents/invoke"

        val bridgeReqPayload = JSONObject().apply {
            put("parent_conversation_id", conversationId)
            put("type_name", "deepseek")
            put("model", targetModel)
            put("role", "DeepSeek Harness Orchestrator")
            put("prompt", prompt)
            workspacePath?.let { put("workspace_dir", it) }
        }

        val bridgeReq = Request.Builder()
            .url(invokeUrl)
            .post(bridgeReqPayload.toString().toRequestBody(jsonType))
            .build()

        val call = client.newCall(bridgeReq)

        try {
            val response = withContext(Dispatchers.IO) { call.execute() }
            if (response.isSuccessful) {
                val bodyStr = response.body?.string().orEmpty()
                val respJson = JSONObject(bodyStr)
                val output = respJson.optString("output_text", "DeepSeek Harness task finished.")
                // Emit reasoning preamble then tokens
                trySend(DshStreamEvent.Thought("Orchestrating task via AntiBot DeepSeek Harness core...\nAnalyzing prompt and routing across available CLI subagents."))
                trySend(DshStreamEvent.Token(output))
                trySend(DshStreamEvent.Done(output, System.currentTimeMillis() - startTime))
            } else {
                trySend(DshStreamEvent.Error("Bridge invocation error: HTTP ${response.code}. Please ensure local server is running or provide an API key in DeepSeek settings."))
            }
            close()
        } catch (e: Exception) {
            if (!channel.isClosedForSend) {
                trySend(DshStreamEvent.Error("Harness unavailable: ${e.message}. Start the local server or enter a DeepSeek API key."))
                close(e)
            }
        }

        awaitClose {
            call.cancel()
        }
    }
}
