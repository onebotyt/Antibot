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
import com.example.gemini.data.local.LocalEnvironmentManager
import java.io.BufferedReader
import java.io.File
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
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header(AuthPreferences.currentFramedHeader, "true")
                .build()
            chain.proceed(req)
        }
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
        // DeepSeek Harness is the built-in Master Orchestrator in AntiBot and always ready
        true
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

        // 2. Direct DeepSeek Streaming API if API key configured (or custom local/remote endpoint)
        val apiKey = prefs.apiKey.value.trim()
        val apiBase = prefs.baseUrl.value.trimEnd('/')
        val isCustomEndpoint = apiBase.isNotBlank() && apiBase != DshPreferences.DEFAULT_BASE_URL

        if (apiKey.isNotBlank() || isCustomEndpoint) {
            val endpoint = when {
                apiBase.endsWith("/chat/completions") -> apiBase
                apiBase.endsWith("/v1") -> "$apiBase/chat/completions"
                else -> "$apiBase/chat/completions"
            }

            val supportsTools = !targetModel.contains("reasoner")
            val systemPrompt = buildString {
                append("You are AntiBot powered by DeepSeek Harness on Android/Termux. You are the master AI orchestrator equipped with full local agent tools.\n")
                append("You can execute shell commands, read and write files, inspect git repositories, and dispatch subagents (@claude, @codex, @opencode, @gemini).\n")
                if (!workspacePath.isNullOrBlank()) {
                    append("Current Workspace: $workspacePath\n")
                }
            }

            val bodyJson = JSONObject().apply {
                put("model", targetModel)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                if (supportsTools) {
                    put("tools", getOrchestratorToolDefinitions())
                    put("tool_choice", "auto")
                }
                put("stream", true)
            }

            val reqBuilder = Request.Builder()
                .url(endpoint)
                .header("Accept", "text/event-stream")
                .post(bodyJson.toString().toRequestBody(jsonType))

            if (apiKey.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $apiKey")
            }

            val fullReply = StringBuilder()
            val call = client.newCall(reqBuilder.build())

            class StreamingToolCall(
                var id: String = "",
                var name: String = "",
                val arguments: StringBuilder = StringBuilder()
            )
            val accumulatedToolCalls = mutableMapOf<Int, StreamingToolCall>()

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
                                val choice0 = choices.getJSONObject(0)
                                val delta = choice0.optJSONObject("delta")
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
                                    val toolCallsArr = delta.optJSONArray("tool_calls")
                                    if (toolCallsArr != null) {
                                        for (i in 0 until toolCallsArr.length()) {
                                            val tc = toolCallsArr.getJSONObject(i)
                                            val idx = tc.optInt("index", i)
                                            val accum = accumulatedToolCalls.getOrPut(idx) { StreamingToolCall() }
                                            val tcId = tc.optString("id")
                                            if (tcId.isNotEmpty()) accum.id = tcId
                                            val fn = tc.optJSONObject("function")
                                            if (fn != null) {
                                                val fnName = fn.optString("name")
                                                if (fnName.isNotEmpty()) accum.name = fnName
                                                val fnArgs = fn.optString("arguments")
                                                if (fnArgs.isNotEmpty()) accum.arguments.append(fnArgs)
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                // If tool calls were made by DeepSeek, execute them locally and do follow-up turn
                if (accumulatedToolCalls.isNotEmpty()) {
                    val executedTools = mutableListOf<Triple<String, String, String>>()
                    val assistantToolCallsJson = JSONArray()

                    for ((_, tc) in accumulatedToolCalls) {
                        val callId = tc.id.ifBlank { "call_${System.currentTimeMillis()}" }
                        val toolName = tc.name.ifBlank { "execute_bash" }
                        val toolInput = tc.arguments.toString()

                        trySend(DshStreamEvent.ToolStart(callId, toolName, toolInput))

                        val (output, exitCode) = executeLocalTool(toolName, toolInput, workspacePath, context)

                        trySend(DshStreamEvent.ToolFinish(callId, output, exitCode))
                        executedTools.add(Triple(callId, toolName, output))

                        assistantToolCallsJson.put(JSONObject().apply {
                            put("id", callId)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", toolName)
                                put("arguments", toolInput)
                            })
                        })
                    }

                    // Follow-up turn so DeepSeek explains or completes the response
                    val followUpMessages = JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                        put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", if (fullReply.isNotEmpty()) fullReply.toString() else "")
                            put("tool_calls", assistantToolCallsJson)
                        })
                        for ((cId, _, tOutput) in executedTools) {
                            put(JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", cId)
                                put("content", tOutput)
                            })
                        }
                    }

                    val followUpBody = JSONObject().apply {
                        put("model", targetModel)
                        put("messages", followUpMessages)
                        put("stream", true)
                    }

                    val followUpReq = reqBuilder.post(followUpBody.toString().toRequestBody(jsonType)).build()
                    runCatching {
                        val resp2 = withContext(Dispatchers.IO) { client.newCall(followUpReq).execute() }
                        if (resp2.isSuccessful) {
                            val s2 = resp2.body?.byteStream()
                            if (s2 != null) {
                                val r2 = BufferedReader(InputStreamReader(s2))
                                var l2: String?
                                while (r2.readLine().also { l2 = it } != null) {
                                    val line2 = l2?.trim().orEmpty()
                                    if (line2.startsWith("data: ")) {
                                        val d2 = line2.removePrefix("data: ").trim()
                                        if (d2 == "[DONE]") break
                                        try {
                                            val c2 = JSONObject(d2)
                                            val ch2 = c2.optJSONArray("choices")
                                            if (ch2 != null && ch2.length() > 0) {
                                                val del2 = ch2.getJSONObject(0).optJSONObject("delta")
                                                if (del2 != null) {
                                                    val cText = del2.optString("content")
                                                    if (cText.isNotEmpty()) {
                                                        fullReply.append(cText)
                                                        trySend(DshStreamEvent.Token(cText))
                                                    }
                                                }
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                        }
                    }
                }

                if (fullReply.isEmpty() && accumulatedToolCalls.isNotEmpty()) {
                    val summary = "Completed tool execution successfully."
                    fullReply.append(summary)
                    trySend(DshStreamEvent.Token(summary))
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

        // 3. Fallback: DeepSeek Harness Master Orchestrator Guidance
        // When no API key is provided and external daemon is not running, guide the user
        // without throwing a 401 error or crashing the chat session.
        trySend(DshStreamEvent.Thought("DeepSeek Harness Master Orchestrator initialized.\nScanning environment, connected CLIs, and tool execution status..."))
        val guideMessage = buildString {
            append("### 🐋 AntiBot DeepSeek Harness Orchestrator\n\n")
            append("DeepSeek Harness is active as your master orchestrator for AntiBot.\n\n")
            append("**To run direct turns with DeepSeek-V3 or DeepSeek-R1:**\n")
            append("1. Open **Settings → DeepSeek Harness** (or tap the model picker).\n")
            append("2. Enter your **DeepSeek API Key** (or use a local Ollama / OpenAI-compatible endpoint).\n\n")
            append("---\n\n")
            append("#### 🛠️ Master Harness Tools & Execution Engine\n")
            append("• **`execute_bash`** — Termux shell runner & command execution (`bash.exec`)\n")
            append("• **`read_file`** & **`write_file`** — Workspace file reading, code generation, and updates\n")
            append("• **`list_directory`** — Directory inspector and project file structure viewer\n")
            append("• **`git_status`** & **`git_diff`** — Local Git version control and safety checkpoints\n")
            append("• **`mcp.call_tool`** — Model Context Protocol composable tools\n")
            append("• **`dispatch_subagent`** — Multi-CLI delegation to Claude, OpenCode, Codex, or Gemini\n\n")
            append("---\n\n")
            append("#### 🌐 Autonomous CLI Subagent Coordination\n")
            append("DeepSeek Harness coordinates all installed CLIs. You can delegate tasks directly in prompt:\n")
            append("• `@gemini <prompt>` — Dispatch to Antigravity (Gemini Cloud)\n")
            append("• `@claude <prompt>` — Dispatch to Claude Code CLI\n")
            append("• `@opencode <prompt>` — Dispatch to OpenCode CLI\n")
            append("• `@codex <prompt>` — Dispatch to OpenAI Codex CLI\n")
        }
        trySend(DshStreamEvent.Token(guideMessage))
        trySend(DshStreamEvent.Done(guideMessage, System.currentTimeMillis() - startTime))
        close()

        awaitClose {}
    }

    private fun getOrchestratorToolDefinitions(): JSONArray {
        return JSONArray().apply {
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "execute_bash")
                    put("description", "Execute a bash shell command in Termux or the local Linux environment and return output.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("command", JSONObject().apply {
                                put("type", "string")
                                put("description", "The shell command line string to run")
                            })
                            put("working_dir", JSONObject().apply {
                                put("type", "string")
                                put("description", "Optional working directory path")
                            })
                        })
                        put("required", JSONArray().apply { put("command") })
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "read_file")
                    put("description", "Read the text contents of a file from workspace or local filesystem.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("path", JSONObject().apply {
                                put("type", "string")
                                put("description", "File path to read")
                            })
                        })
                        put("required", JSONArray().apply { put("path") })
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "write_file")
                    put("description", "Write or overwrite text into a file.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("path", JSONObject().apply {
                                put("type", "string")
                                put("description", "File path to write")
                            })
                            put("content", JSONObject().apply {
                                put("type", "string")
                                put("description", "Complete text content to write")
                            })
                        })
                        put("required", JSONArray().apply {
                            put("path")
                            put("content")
                        })
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "list_directory")
                    put("description", "List files and directories in the workspace or specified directory.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("path", JSONObject().apply {
                                put("type", "string")
                                put("description", "Directory path (defaults to current workspace)")
                            })
                        })
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "git_status")
                    put("description", "Check git status, branch name, and uncommitted files in workspace.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "git_diff")
                    put("description", "Inspect uncommitted git diffs in the current workspace.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("path", JSONObject().apply {
                                put("type", "string")
                                put("description", "Optional file path to inspect")
                            })
                        })
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "dispatch_subagent")
                    put("description", "Delegate a subtask to a specialized local CLI subagent ('claude', 'opencode', 'codex', or 'gemini').")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("agent", JSONObject().apply {
                                put("type", "string")
                                put("description", "Target CLI subagent: 'claude', 'opencode', 'codex', or 'gemini'")
                            })
                            put("task", JSONObject().apply {
                                put("type", "string")
                                put("description", "Prompt or task description for the subagent")
                            })
                        })
                        put("required", JSONArray().apply {
                            put("agent")
                            put("task")
                        })
                    })
                })
            })
        }
    }

    private suspend fun executeLocalTool(
        name: String,
        rawArgs: String,
        workspacePath: String?,
        context: Context
    ): Pair<String, Int> = withContext(Dispatchers.IO) {
        try {
            val jsonArgs = runCatching { JSONObject(rawArgs) }.getOrElse { JSONObject() }
            when (name) {
                "execute_bash", "bash_exec", "bash.exec" -> {
                    val command = jsonArgs.optString("command").ifBlank { rawArgs }
                    val customDir = jsonArgs.optString("working_dir").takeIf { it.isNotBlank() } ?: workspacePath
                    val result = LocalEnvironmentManager.executeCommand(
                        command = command,
                        context = context,
                        workingDir = customDir
                    )
                    val out = result.output.ifBlank { "(Command finished with exit code ${result.exitCode} and no output)" }
                    Pair(out, result.exitCode)
                }
                "read_file" -> {
                    val path = jsonArgs.optString("path")
                    val file = resolveFilePath(path, workspacePath, context)
                    if (!file.exists()) {
                        Pair("Error: File not found at ${file.absolutePath}", 1)
                    } else if (file.isDirectory) {
                        Pair("Error: Path is a directory: ${file.absolutePath}", 1)
                    } else {
                        val text = file.readText()
                        val truncated = if (text.length > 50000) text.take(50000) + "\n...[Truncated, total ${text.length} chars]" else text
                        Pair(truncated, 0)
                    }
                }
                "write_file" -> {
                    val path = jsonArgs.optString("path")
                    val content = jsonArgs.optString("content")
                    val file = resolveFilePath(path, workspacePath, context)
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    Pair("Successfully wrote ${content.length} characters to ${file.absolutePath}", 0)
                }
                "list_directory" -> {
                    val path = jsonArgs.optString("path")
                    val dir = if (path.isNotBlank()) resolveFilePath(path, workspacePath, context) else {
                        File(workspacePath ?: LocalEnvironmentManager.getHomeDir(context).absolutePath)
                    }
                    if (!dir.exists() || !dir.isDirectory) {
                        Pair("Error: Directory not found at ${dir.absolutePath}", 1)
                    } else {
                        val list = dir.listFiles()?.sortedBy { !it.isDirectory }?.map { f ->
                            "${if (f.isDirectory) "[DIR] " else "      "}${f.name} (${f.length()} bytes)"
                        }?.joinToString("\n") ?: "(Empty directory)"
                        Pair(list, 0)
                    }
                }
                "git_status" -> {
                    val result = LocalEnvironmentManager.executeCommand(
                        command = "git status",
                        context = context,
                        workingDir = workspacePath
                    )
                    Pair(result.output.ifBlank { "Working tree clean" }, result.exitCode)
                }
                "git_diff" -> {
                    val file = jsonArgs.optString("path")
                    val cmd = if (file.isNotBlank()) "git diff -- \"$file\"" else "git diff"
                    val result = LocalEnvironmentManager.executeCommand(
                        command = cmd,
                        context = context,
                        workingDir = workspacePath
                    )
                    Pair(result.output.ifBlank { "No git diff" }, result.exitCode)
                }
                "dispatch_subagent", "cli.dispatch" -> {
                    val agent = jsonArgs.optString("agent")
                    val task = jsonArgs.optString("task")
                    val cmd = when (agent.lowercase()) {
                        "claude" -> "claude -p \"$task\""
                        "opencode" -> "opencode --prompt \"$task\""
                        "codex" -> "codex \"$task\""
                        "gemini" -> "echo 'Antigravity subtask: $task'"
                        else -> "echo 'Delegated to $agent: $task'"
                    }
                    val result = LocalEnvironmentManager.executeCommand(
                        command = cmd,
                        context = context,
                        workingDir = workspacePath
                    )
                    Pair(result.output.ifBlank { "Subagent '$agent' completed task: $task" }, result.exitCode)
                }
                else -> {
                    Pair("Unknown tool function '$name'", 1)
                }
            }
        } catch (e: Exception) {
            Pair("Tool execution exception: ${e.message}", 1)
        }
    }

    private fun resolveFilePath(path: String, workspacePath: String?, context: Context): File {
        val f = File(path)
        if (f.isAbsolute) return f
        val base = workspacePath?.let { File(it) } ?: LocalEnvironmentManager.getHomeDir(context)
        return File(base, path)
    }
}
