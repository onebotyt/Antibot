package com.example.gemini.domain.context

import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AntigravityApiService
import com.example.gemini.data.remote.StreamEvent
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class SummarizationResult(
    val summary: String,
    val title: String? = null
)

data class CompactResult(
    val older: List<ChatMessage>,
    val recent: List<ChatMessage>,
    val summarizationPrompt: String
)

object ContextCompactor {

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Splits chat history into (olderMessages, recentMessages) based on contextWindowLimit.
     */
    fun splitHistory(
        messages: List<ChatMessage>,
        windowLimit: Int
    ): Pair<List<ChatMessage>, List<ChatMessage>> {
        if (messages.size <= windowLimit) {
            return Pair(emptyList(), messages)
        }
        val splitIndex = messages.size - windowLimit
        val older = messages.take(splitIndex)
        val recent = messages.drop(splitIndex)
        return Pair(older, recent)
    }

    /**
     * Builds the prompt sent to the LLM to generate an executive context summary and updated title.
     */
    fun buildSummarizationPrompt(
        messagesToSummarize: List<ChatMessage>,
        existingSummary: String? = null
    ): String {
        val conversationText = buildString {
            if (!existingSummary.isNullOrBlank()) {
                appendLine("=== PREVIOUS CONTEXT SUMMARY ===")
                appendLine(existingSummary)
                appendLine("================================\n")
            }

            appendLine("=== CONVERSATION TURNS TO SUMMARIZE ===")
            for (msg in messagesToSummarize) {
                val roleName = if (msg.role == MessageRole.USER) "User" else "Assistant"
                val cleanContent = msg.content.replace(Regex("<!--\\s*tool_call:[a-zA-Z0-9_-]+\\s*-->"), "").trim()
                appendLine("[$roleName]: $cleanContent")

                if (msg.toolCalls.isNotEmpty()) {
                    for (tool in msg.toolCalls) {
                        appendLine("  [Tool Executed: ${tool.name}] command: ${tool.command.take(120)} (status: ${tool.status}, exit: ${tool.exitCode ?: 0})")
                        if (tool.output.isNotBlank()) {
                            val shortOut = if (tool.output.length > 250) tool.output.take(250) + "..." else tool.output
                            appendLine("  [Tool Output Snippet]: $shortOut")
                        }
                    }
                }
                appendLine()
            }
            appendLine("=======================================")
        }

        return """
You are an expert AI Context Summarizer. Condense the following conversation history into a high-density, crystal-clear executive memory block that an AI can use to continue working seamlessly without losing any critical context.

$conversationText

INSTRUCTIONS:
1. Output a refined, comprehensive 3-6 word title for this overall conversation enclosed in <chat_title>...</chat_title> at the very top (e.g. <chat_title>Spring Boot Auth Migration</chat_title>).
2. Produce a concise, structured markdown summary (around 150-300 words) with these exact sections:
• **Core Goal & Requirements**: What the user is building or asking for.
• **Key Decisions & Architecture**: Important choices made, libraries/frameworks chosen, preferences specified.
• **Work Accomplished & Tool Results**: Files created/modified, commands run, features implemented, and key outcomes.
• **Current Status & Pending Tasks**: Where the conversation currently left off and what needs to happen next.

Keep it factual, concise, and focused on code, files, decisions, and outcomes. Do NOT include filler words.
""".trimIndent()
    }

    /**
     * Delegates history compaction and summarization prompt building to the Rust bridge daemon (POST /api/conversations/compact).
     * Falls back to in-process splitHistory + buildSummarizationPrompt if bridge is unavailable.
     */
    suspend fun compactViaBridge(
        messages: List<ChatMessage>,
        windowLimit: Int = 20,
        existingSummary: String? = null
    ): CompactResult = withContext(Dispatchers.IO) {
        try {
            val bridgeBase = AuthPreferences.currentBridgeHttpUrl.trimEnd('/')
            val messagesArray = JSONArray().apply {
                messages.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", if (msg.role == MessageRole.USER) "user" else "assistant")
                        put("content", msg.content)
                        val tcArray = JSONArray().apply {
                            msg.toolCalls.forEach { tc ->
                                put(JSONObject().apply {
                                    put("name", tc.name)
                                    put("command", tc.command)
                                    put("status", tc.status)
                                    put("exit_code", tc.exitCode ?: 0)
                                    put("output", tc.output)
                                })
                            }
                        }
                        put("tool_calls", tcArray)
                    })
                }
            }
            val body = JSONObject().apply {
                put("messages", messagesArray)
                put("windowLimit", windowLimit)
                if (!existingSummary.isNullOrBlank()) {
                    put("existingSummary", existingSummary)
                }
            }.toString()

            val request = Request.Builder()
                .url("$bridgeBase/api/conversations/compact")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val resp = http.newCall(request).execute()
            if (resp.isSuccessful) {
                val json = JSONObject(resp.body!!.string())
                val summarizationPrompt = json.getString("summarization_prompt")
                val (older, recent) = splitHistory(messages, windowLimit)
                return@withContext CompactResult(older, recent, summarizationPrompt)
            }
        } catch (_: Exception) {
            // Fall through to in-process fallback
        }
        val (older, recent) = splitHistory(messages, windowLimit)
        val prompt = buildSummarizationPrompt(older, existingSummary)
        CompactResult(older, recent, prompt)
    }

    /**
     * Builds summarization prompt using the Rust bridge, falling back to local string building.
     */
    suspend fun buildSummarizationPromptViaBridge(
        messagesToSummarize: List<ChatMessage>,
        existingSummary: String? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val res = compactViaBridge(
                messages = messagesToSummarize,
                windowLimit = 0,
                existingSummary = existingSummary
            )
            if (res.summarizationPrompt.isNotBlank()) {
                return@withContext res.summarizationPrompt
            }
        } catch (_: Exception) {}
        buildSummarizationPrompt(messagesToSummarize, existingSummary)
    }

    /**
     * Executes the summarization API call using the selected model.
     */
    suspend fun executeSummarization(
        apiService: AntigravityApiService,
        token: String,
        projectId: String,
        modelId: String,
        messagesToSummarize: List<ChatMessage>,
        existingSummary: String? = null
    ): Result<SummarizationResult> = withContext(Dispatchers.IO) {
        try {
            val prompt = buildSummarizationPromptViaBridge(messagesToSummarize, existingSummary)
            val syntheticMessage = ChatMessage(
                conversationId = "summary-temp",
                role = MessageRole.USER,
                content = prompt
            )

            val summaryBuilder = StringBuilder()
            val thoughtBuilder = StringBuilder()

            android.util.Log.d("GeminiApp", "[ContextCompactor] Starting summarization with model: $modelId, messages: ${messagesToSummarize.size}")

            apiService.streamGenerateContent(
                token = token,
                projectId = projectId,
                modelId = modelId,
                sessionId = "summary-" + UUID.randomUUID().toString(),
                messages = listOf(syntheticMessage),
                summary = null,
                thinkingBudget = 0,
                isThinkingEnabled = false,
                toolInstruction = null
            ).collect { event ->
                when (event) {
                    is StreamEvent.TextChunk -> summaryBuilder.append(event.text)
                    is StreamEvent.ThoughtChunk -> thoughtBuilder.append(event.thought)
                    is StreamEvent.Error -> throw Exception(event.message)
                    else -> {}
                }
            }

            var finalSummary = summaryBuilder.toString().trim()
            if (finalSummary.isBlank() && thoughtBuilder.isNotBlank()) {
                finalSummary = thoughtBuilder.toString().trim()
            }

            if (finalSummary.isBlank()) {
                return@withContext Result.failure(Exception("Summarizer returned empty content"))
            }

            var extractedTitle: String? = null
            val titleMatch = Regex("<chat_title>([\\s\\S]*?)</chat_title>", RegexOption.IGNORE_CASE).find(finalSummary)
            if (titleMatch != null) {
                extractedTitle = titleMatch.groupValues[1].trim().replace("\"", "").replace("'", "")
                finalSummary = finalSummary.replace(Regex("<chat_title>[\\s\\S]*?</chat_title>\\s*", RegexOption.IGNORE_CASE), "").trim()
            }

            android.util.Log.d("GeminiApp", "[ContextCompactor] Summarization completed! Title: '$extractedTitle', Length: ${finalSummary.length}")
            Result.success(SummarizationResult(summary = finalSummary, title = extractedTitle))
        } catch (e: Exception) {
            android.util.Log.e("GeminiApp", "[ContextCompactor] Summarization failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Loads invariant project rules that must ALWAYS be carried forward in full (never summarized).
     * Scans for AGENTS.md, .gemini/rules/, and project guidelines in the workspace.
     */
    fun loadAnchorRules(workspacePath: String): List<Pair<String, String>> {
        val rules = mutableListOf<Pair<String, String>>()
        try {
            val root = java.io.File(workspacePath)
            if (!root.exists() || !root.isDirectory) return rules

            // 1. AGENTS.md
            val agentsMd = java.io.File(root, "AGENTS.md")
            if (agentsMd.exists() && agentsMd.isFile) {
                rules.add("AGENTS.md" to agentsMd.readText().take(6000))
            }

            // 2. .gemini/rules/ or .claude/CLAUDE.md
            val geminiRulesDir = java.io.File(root, ".gemini/rules")
            if (geminiRulesDir.exists() && geminiRulesDir.isDirectory) {
                geminiRulesDir.listFiles()?.filter { it.isFile && (it.extension == "md" || it.extension == "txt") }?.forEach { f ->
                    rules.add(f.name to f.readText().take(4000))
                }
            }

            val claudeMd = java.io.File(root, "CLAUDE.md")
            if (claudeMd.exists() && claudeMd.isFile) {
                rules.add("CLAUDE.md" to claudeMd.readText().take(6000))
            }
        } catch (e: Exception) {
            android.util.Log.w("GeminiApp", "[ContextCompactor] Error loading anchor rules: ${e.message}")
        }
        return rules
    }

    /**
     * Builds a comprehensive seed prompt for a newly rolled-over chat.
     * Merges full uncompressed Anchor Rules with the compacted executive summary.
     */
    fun buildRolloverSeedPrompt(
        summary: String,
        anchorRules: List<Pair<String, String>>,
        pendingObjective: String? = null
    ): String {
        return buildString {
            appendLine("=== [AUTO-ROLLOVER: FRESH CONTINUATION SESSION] ===")
            appendLine("This chat was automatically initialized from a previous session to ensure peak agent reasoning and zero memory bloat.")
            appendLine()

            if (anchorRules.isNotEmpty()) {
                appendLine("### 📌 INVARIANT PROJECT RULES (ALWAYS CARRIED FULL)")
                for ((ruleName, ruleContent) in anchorRules) {
                    appendLine("--- [$ruleName] ---")
                    appendLine(ruleContent.trim())
                    appendLine("-------------------")
                }
                appendLine()
            }

            appendLine("### 📋 EXECUTIVE PROGRESS FROM PREVIOUS CHAT")
            appendLine(summary.trim())
            appendLine()

            if (!pendingObjective.isNullOrBlank()) {
                appendLine("### 🎯 IMMEDIATE NEXT OBJECTIVE")
                appendLine(pendingObjective.trim())
                appendLine()
            }

            appendLine("INSTRUCTION: Continue development seamlessly from this point forward. Follow all invariant rules above.")
            appendLine("===================================================")
        }
    }

    /**
     * Builds rollover seed prompt via the Rust bridge daemon (POST /api/conversations/rollover-seed).
     * Falls back to local prompt building if bridge is unavailable.
     */
    suspend fun buildRolloverSeedPromptViaBridge(
        summary: String,
        workspacePath: String,
        pendingObjective: String? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val bridgeBase = AuthPreferences.currentBridgeHttpUrl.trimEnd('/')
            val body = JSONObject().apply {
                put("summary", summary)
                put("workspacePath", workspacePath)
                if (!pendingObjective.isNullOrBlank()) {
                    put("pendingObjective", pendingObjective)
                }
            }.toString()

            val request = Request.Builder()
                .url("$bridgeBase/api/conversations/rollover-seed")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val resp = http.newCall(request).execute()
            if (resp.isSuccessful) {
                val json = JSONObject(resp.body!!.string())
                return@withContext json.getString("seed_prompt")
            }
        } catch (_: Exception) {}
        val anchorRules = loadAnchorRules(workspacePath)
        buildRolloverSeedPrompt(summary, anchorRules, pendingObjective)
    }
}

