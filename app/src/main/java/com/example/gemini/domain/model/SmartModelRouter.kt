package com.example.gemini.domain.model

import androidx.compose.ui.graphics.Color
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.theme.GeminiBlue
import com.example.gemini.data.preferences.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Categorized routing reasons explaining why the Auto LLM Manager chose a specific model.
 */
enum class RoutingReason(val label: String, val badgeColor: Color) {
    LOCAL_SHELL("Local Shell", Color(0xFF10B981)),
    LOCAL_WORKSPACE("Workspace", Color(0xFF10B981)),
    LOCAL_PORT_PROCESS("Port/Process", Color(0xFF10B981)),
    LOCAL_OFFLINE("Offline Fallback", Color(0xFF10B981)),
    FAST_QA("Fast Q&A", GeminiBlue),
    CODING("Coding", Color(0xFF8B5CF6)),
    DEEP_REASONING("Deep Logic", ClaudeTerracotta),
    CYBER_SECURITY("Security", Color(0xFFEF4444)),
    QUOTA_FALLBACK("Quota Defense", Color(0xFFF59E0B)),
    MANUAL("Manual", Color(0xFF3B82F6))
}

/**
 * Decision record computed by [SmartModelRouter] for dynamic query dispatch.
 */
data class SmartRoutingDecision(
    val targetModelId: String,
    val targetAgent: AgentKind,
    val modelDisplayName: String,
    val reason: RoutingReason,
    val explanation: String,
    val badgeColor: Color = reason.badgeColor,
    val managerBrain: String = "Qwen 2.5 Coder 0.5B (On-Device Neural Engine)"
) {
    val targetCliDisplayName: String
        get() = when (targetAgent) {
            AgentKind.AGY -> "Antigravity CLI"
            AgentKind.CLAUDE -> "Claude Code CLI"
            AgentKind.CODEX -> "Codex CLI"
            AgentKind.OPENCODE -> "OpenCode CLI"
            AgentKind.LOCAL -> "Local Qwen CLI"
        }

    val shortPillText: String
        get() = when {
            targetAgent == AgentKind.LOCAL || targetModelId.startsWith("local-") || targetModelId == "local-model-only" || targetModelId == "cli:local" -> "Auto ➔ Qwen (0 Tokens)"
            targetAgent == AgentKind.CLAUDE || targetModelId == "cli:claude" -> "Auto ➔ Claude Code"
            targetAgent == AgentKind.CODEX || targetModelId == "cli:codex" -> "Auto ➔ Codex CLI"
            targetAgent == AgentKind.OPENCODE || targetModelId == "cli:opencode" -> "Auto ➔ OpenCode"
            targetModelId.contains("claude", ignoreCase = true) -> "Auto ➔ Claude"
            targetModelId.contains("flash", ignoreCase = true) -> "Auto ➔ Flash"
            targetModelId.contains("pro", ignoreCase = true) -> "Auto ➔ Gemini Pro"
            else -> "Auto ➔ $targetCliDisplayName"
        }

    val dynamicDisplayPill: String
        get() = "$shortPillText (${reason.label})"
}

/**
 * User-configurable task-to-model preferences configured in Settings -> Smart LLM Manager.
 */
data class LlmManagerConfig(
    val architectureModel: String = "claude",
    val codingModel: String = "claude",
    val fastQaModel: String = "gemini",
    val shellModel: String = "local-qwen-coder-0.5b",
    val fallbackModel: String = "gemini",
    val preferredCli: String = "auto"
)

/**
 * AntiBot Smart LLM Manager & Model Router.
 * Automatically dispatches queries across any configured cloud models (Claude, Gemini, GPT-OSS)
 * and Local Qwen based on prompt heuristics, user task mappings, rate limit protection,
 * and offline status.
 *
 * Routing logic is now authoritative in the Rust bridge (POST /api/models/route).
 * [routeViaBridge] delegates there; [route] is kept as a synchronous in-process fallback.
 */
object SmartModelRouter {
    const val AUTO_MODEL_ID = "auto"
    const val AUTO_DISPLAY_NAME = "Auto (Smart LLM Manager)"

    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    /**
     * Preferred entry-point: delegates routing to the Rust bridge daemon.
     * Falls back to the synchronous [route] if the bridge is unreachable.
     */
    suspend fun routeViaBridge(
        prompt: String,
        hasAttachments: Boolean = false,
        isOnline: Boolean = true,
        availableModels: List<AiModel> = emptyList(),
        quotas: List<ModelQuota> = emptyList(),
        currentSelectedModelId: String = AUTO_MODEL_ID,
        config: LlmManagerConfig = LlmManagerConfig()
    ): SmartRoutingDecision = withContext(Dispatchers.IO) {
        try {
            val bridgeBase = AuthPreferences.currentBridgeHttpUrl.trimEnd('/')
            val modelsArray = JSONArray().apply {
                availableModels.forEach { m ->
                    put(JSONObject().apply {
                        put("id", m.id)
                        put("displayName", m.displayName)
                        put("family", m.family.name)
                    })
                }
            }
            val quotasArray = JSONArray().apply {
                quotas.forEach { q ->
                    put(JSONObject().apply {
                        put("modelId", q.modelId)
                        put("percentage", q.percentage)
                    })
                }
            }
            val body = JSONObject().apply {
                put("prompt", prompt)
                put("hasAttachments", hasAttachments)
                put("isOnline", isOnline)
                put("availableModels", modelsArray)
                put("quotas", quotasArray)
                put("currentSelectedModelId", currentSelectedModelId)
                put("config", JSONObject().apply {
                    put("architectureModel", config.architectureModel)
                    put("codingModel", config.codingModel)
                    put("fastQaModel", config.fastQaModel)
                    put("shellModel", config.shellModel)
                    put("fallbackModel", config.fallbackModel)
                    put("preferredCli", config.preferredCli)
                })
            }.toString()

            val request = Request.Builder()
                .url("$bridgeBase/api/models/route")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val resp = http.newCall(request).execute()
            if (resp.isSuccessful) {
                val json = JSONObject(resp.body!!.string())
                val targetModelId = json.getString("targetModelId")
                val agentStr = json.optString("targetAgent", "AGY").uppercase()
                val targetAgent = when (agentStr) {
                    "LOCAL" -> AgentKind.LOCAL
                    "CLAUDE" -> AgentKind.CLAUDE
                    "CODEX" -> AgentKind.CODEX
                    "OPENCODE" -> AgentKind.OPENCODE
                    else -> AgentKind.AGY
                }
                val displayName = json.optString("modelDisplayName", targetModelId)
                val reasonStr = json.optString("reason", "FAST_QA")
                val reason = runCatching { RoutingReason.valueOf(reasonStr) }.getOrDefault(RoutingReason.FAST_QA)
                val explanation = json.optString("explanation", "")
                return@withContext SmartRoutingDecision(
                    targetModelId = targetModelId,
                    targetAgent = targetAgent,
                    modelDisplayName = displayName,
                    reason = reason,
                    explanation = explanation
                )
            }
        } catch (_: Exception) {
            // Bridge unavailable — fall through to in-process fallback
        }
        // Fallback: run synchronous in-process router
        route(prompt, hasAttachments, isOnline, availableModels, quotas, currentSelectedModelId, config)
    }

    fun isAuto(modelId: String?): Boolean {
        if (modelId.isNullOrBlank()) return true
        return modelId.equals(AUTO_MODEL_ID, ignoreCase = true) ||
                modelId.equals("smart-router", ignoreCase = true) ||
                modelId.equals("auto-manager", ignoreCase = true)
    }

    fun resolveAgentForTarget(targetId: String, preferredCli: String = "auto"): AgentKind {
        val lower = targetId.lowercase(java.util.Locale.ROOT)
        if (targetId.startsWith("cli:")) {
            return when (targetId.removePrefix("cli:").lowercase(java.util.Locale.ROOT)) {
                "claude", "claudecode" -> AgentKind.CLAUDE
                "codex" -> AgentKind.CODEX
                "opencode" -> AgentKind.OPENCODE
                "local", "qwen" -> AgentKind.LOCAL
                else -> AgentKind.AGY
            }
        }
        if (preferredCli != "auto" && preferredCli.isNotBlank()) {
            val pref = runCatching { AgentKind.valueOf(preferredCli.uppercase(java.util.Locale.ROOT)) }.getOrNull()
            if (pref != null) return pref
        }
        return when {
            lower.startsWith("local-") || lower == "local-model-only" -> AgentKind.LOCAL
            lower.contains("claude") -> AgentKind.CLAUDE
            lower.contains("codex") || lower.contains("openai") || lower.contains("gpt-oss") -> AgentKind.CODEX
            lower.contains("opencode") -> AgentKind.OPENCODE
            else -> AgentKind.AGY
        }
    }

    /**
     * Resolves candidate model matching an ID, keyword, CLI tag or family and checks whether its quota is valid.
     */
    fun resolveCandidateModel(
        pattern: String,
        availableModels: List<AiModel>,
        quotas: List<ModelQuota>
    ): AiModel? {
        if (pattern.isBlank()) return null
        val lower = pattern.lowercase(java.util.Locale.ROOT)

        if (pattern.startsWith("cli:")) {
            return when (pattern.removePrefix("cli:").lowercase(java.util.Locale.ROOT)) {
                "claude", "claudecode" -> availableModels.find { it.family == ModelFamily.CLAUDE }
                    ?: AiModel(id = "cli:claude", displayName = "Claude Code CLI", family = ModelFamily.CLAUDE, description = "Anthropic Claude Code CLI Manager")
                "codex" -> availableModels.find { it.id.contains("codex", ignoreCase = true) || it.id.contains("gpt", ignoreCase = true) }
                    ?: AiModel(id = "cli:codex", displayName = "Codex CLI", family = ModelFamily.OTHER, description = "OpenAI Codex CLI Manager")
                "opencode" -> AiModel(id = "cli:opencode", displayName = "OpenCode CLI", family = ModelFamily.OTHER, description = "OpenCode Multi-Model CLI Manager")
                "local", "qwen" -> AiModel(id = LocalCodingDomain.QWEN_CODER.modelId, displayName = "Local Qwen 2.5 Coder 0.5B", family = ModelFamily.LOCAL, description = "On-Device Neural Engine")
                "local-1.5b", "qwen-1.5b", "1b" -> AiModel(id = LocalCodingDomain.QWEN_CODER_1B.modelId, displayName = "Local Qwen 2.5 Coder 1.5B", family = ModelFamily.LOCAL, description = "On-Device 1B Neural Engine")
                else -> availableModels.find { it.family == ModelFamily.GEMINI }
                    ?: AiModel(id = "cli:agy", displayName = "Antigravity CLI", family = ModelFamily.GEMINI, description = "Gemini Cloud Code CLI Manager")
            }
        }

        if (pattern == LocalCodingDomain.QWEN_CODER_1B.modelId) {
            return AiModel(id = LocalCodingDomain.QWEN_CODER_1B.modelId, displayName = "Local Qwen 2.5 Coder 1.5B", family = ModelFamily.LOCAL, description = "On-Device 1B Neural Engine")
        }

        // 1. Exact ID match
        var match = availableModels.find { it.id.equals(pattern, ignoreCase = true) }
        // 2. Keyword/ID substring match
        if (match == null) {
            match = when (lower) {
                "claude" -> availableModels.find { it.family == ModelFamily.CLAUDE }
                    ?: availableModels.find { it.id.contains("claude", ignoreCase = true) }
                "gemini", "gemini-flash", "flash" -> availableModels.find {
                    it.id.contains("flash", ignoreCase = true) || it.displayName.contains("flash", ignoreCase = true)
                } ?: availableModels.find { it.family == ModelFamily.GEMINI }
                "gemini-pro", "pro" -> availableModels.find {
                    it.id.contains("pro", ignoreCase = true) || it.displayName.contains("pro", ignoreCase = true)
                }
                else -> availableModels.find {
                    it.id.contains(pattern, ignoreCase = true) || it.displayName.contains(pattern, ignoreCase = true)
                }
            }
        }

        if (match == null) return null

        // Check quota availability
        val quota = quotas.find { it.modelId == match?.id }
        if (quota != null && quota.percentage <= 0) {
            return null // Quota exhausted
        }
        return match
    }

    /**
     * Resolves the optimal model and backend for a given user prompt.
     * Uses Qwen Local Brain for intent understanding and multi-CLI routing.
     */
    fun route(
        prompt: String,
        hasAttachments: Boolean = false,
        isOnline: Boolean = true,
        availableModels: List<AiModel> = emptyList(),
        quotas: List<ModelQuota> = emptyList(),
        currentSelectedModelId: String = AUTO_MODEL_ID,
        config: LlmManagerConfig = LlmManagerConfig()
    ): SmartRoutingDecision {
        // 1. If user manually locked onto a specific model or CLI, honor it
        if (!isAuto(currentSelectedModelId)) {
            val isLocal = LocalCodingDomain.isLocalModel(currentSelectedModelId)
            val modelObj = availableModels.find { it.id == currentSelectedModelId }
            val targetAgent = resolveAgentForTarget(currentSelectedModelId, config.preferredCli)
            return SmartRoutingDecision(
                targetModelId = currentSelectedModelId,
                targetAgent = targetAgent,
                modelDisplayName = if (isLocal) LocalCodingDomain.fromModelId(currentSelectedModelId).displayName else (modelObj?.displayName ?: currentSelectedModelId),
                reason = RoutingReason.MANUAL,
                explanation = "Manually selected model · Dispatched to ${targetAgent.name} CLI",
                badgeColor = if (isLocal) Color(0xFF10B981) else Color(0xFF3B82F6)
            )
        }

        // 2. Connectivity check -> If offline or server disconnected, fallback immediately to Local Qwen
        if (!isOnline) {
            return SmartRoutingDecision(
                targetModelId = LocalCodingDomain.QWEN_CODER.modelId,
                targetAgent = AgentKind.LOCAL,
                modelDisplayName = "Qwen 2.5 Coder 0.5B",
                reason = RoutingReason.LOCAL_OFFLINE,
                explanation = "Network offline · Running 100% on-device Qwen neural engine",
                badgeColor = Color(0xFF10B981)
            )
        }

        val cleanPrompt = prompt.trim().lowercase(java.util.Locale.ROOT)

        // 🧠 Manager Brain: Qwen Local Neural Intent Classifier
        val qwenIntent = com.example.gemini.data.agent.local.LocalIntelligenceEngine.classifyLocalIntent(prompt)

        // 3. Local Shell, Terminal, Port & Process inspection tasks
        val isTerminalCommand = cleanPrompt.startsWith("$") ||
                cleanPrompt.startsWith("bash ") ||
                cleanPrompt.startsWith("sh ") ||
                cleanPrompt.startsWith("npm ") ||
                cleanPrompt.startsWith("pnpm ") ||
                cleanPrompt.startsWith("yarn ") ||
                cleanPrompt.startsWith("git ") ||
                cleanPrompt.startsWith("node ") ||
                cleanPrompt.startsWith("python ") ||
                cleanPrompt.startsWith("curl ") ||
                cleanPrompt.startsWith("ls ") ||
                cleanPrompt.startsWith("cat ") ||
                cleanPrompt.startsWith("grep ") ||
                cleanPrompt.startsWith("mkdir ") ||
                cleanPrompt.startsWith("rm ")

        val isPortOrProcess = cleanPrompt.contains("check port") ||
                cleanPrompt.contains("kill port") ||
                cleanPrompt.contains("check ports") ||
                cleanPrompt.contains("kill process") ||
                cleanPrompt.contains("free port") ||
                Regex("port\\s+\\d{2,5}").containsMatchIn(cleanPrompt)

        val isWorkspaceInspection = cleanPrompt.contains("grep search") ||
                cleanPrompt.contains("search files") ||
                cleanPrompt.contains("list files") ||
                cleanPrompt.contains("inbuilt browser") ||
                cleanPrompt.contains("preview html") ||
                cleanPrompt.contains("preview website")

        val isShellTask = qwenIntent.intent == "DIRECT_COMMAND" ||
                qwenIntent.intent == "DEV_SERVER" ||
                isTerminalCommand || isPortOrProcess || isWorkspaceInspection

        if (isShellTask) {
            val isLocalShell = config.shellModel.startsWith("local", ignoreCase = true) || config.shellModel == "cli:local"
            if (isLocalShell) {
                val reason = when {
                    isPortOrProcess -> RoutingReason.LOCAL_PORT_PROCESS
                    isWorkspaceInspection -> RoutingReason.LOCAL_WORKSPACE
                    else -> RoutingReason.LOCAL_SHELL
                }
                return SmartRoutingDecision(
                    targetModelId = LocalCodingDomain.QWEN_CODER.modelId,
                    targetAgent = AgentKind.LOCAL,
                    modelDisplayName = "Qwen 2.5 Coder 0.5B",
                    reason = reason,
                    explanation = "Qwen Brain evaluated command · Executed locally on device (0 tokens)",
                    badgeColor = Color(0xFF10B981)
                )
            } else {
                val shellCloudModel = resolveCandidateModel(config.shellModel, availableModels, quotas)
                if (shellCloudModel != null) {
                    val targetAgent = resolveAgentForTarget(config.shellModel, config.preferredCli)
                    return SmartRoutingDecision(
                        targetModelId = shellCloudModel.id,
                        targetAgent = targetAgent,
                        modelDisplayName = shellCloudModel.displayName,
                        reason = RoutingReason.LOCAL_SHELL,
                        explanation = "Qwen Brain routed to shell CLI: ${shellCloudModel.displayName}",
                        badgeColor = Color(0xFF3B82F6)
                    )
                }
            }
        }

        // Resolving fallback model for quota protection
        val fallbackModel = resolveCandidateModel(config.fallbackModel, availableModels, quotas)
            ?: resolveCandidateModel("flash", availableModels, quotas)
            ?: resolveCandidateModel("gemini", availableModels, quotas)

        // 4. Security Auditing & Deep Architecture Logic
        val isSecurityAudit = qwenIntent.intent == "SECURITY_AUDIT" ||
                cleanPrompt.contains("security audit") ||
                cleanPrompt.contains("owasp") ||
                cleanPrompt.contains("vulnerability") ||
                cleanPrompt.contains("pentest") ||
                cleanPrompt.contains("penetration test") ||
                cleanPrompt.contains("sql injection") ||
                cleanPrompt.contains("xss") ||
                cleanPrompt.contains("csrf") ||
                cleanPrompt.contains("hardening")

        val isArchitectureOrDeep = isSecurityAudit ||
                qwenIntent.intent == "ARCHITECTURE" ||
                cleanPrompt.contains("architecture") ||
                cleanPrompt.contains("architect") ||
                cleanPrompt.contains("design pattern") ||
                cleanPrompt.contains("system design") ||
                cleanPrompt.contains("plan") ||
                cleanPrompt.contains("step by step")

        if (isArchitectureOrDeep) {
            val archCandidate = resolveCandidateModel(config.architectureModel, availableModels, quotas)
            if (archCandidate != null) {
                val targetAgent = resolveAgentForTarget(config.architectureModel, config.preferredCli)
                return SmartRoutingDecision(
                    targetModelId = archCandidate.id,
                    targetAgent = targetAgent,
                    modelDisplayName = archCandidate.displayName,
                    reason = if (isSecurityAudit) RoutingReason.CYBER_SECURITY else RoutingReason.DEEP_REASONING,
                    explanation = if (isSecurityAudit) "Qwen Brain Security Dispatch · ${archCandidate.displayName}" else "Qwen Brain Architecture Dispatch · ${archCandidate.displayName}",
                    badgeColor = if (isSecurityAudit) Color(0xFFEF4444) else ClaudeTerracotta
                )
            } else if (fallbackModel != null) {
                val targetAgent = resolveAgentForTarget(config.fallbackModel, config.preferredCli)
                return SmartRoutingDecision(
                    targetModelId = fallbackModel.id,
                    targetAgent = targetAgent,
                    modelDisplayName = fallbackModel.displayName,
                    reason = RoutingReason.QUOTA_FALLBACK,
                    explanation = "Primary architecture model quota exhausted · Qwen Brain auto-switched to ${fallbackModel.displayName}",
                    badgeColor = Color(0xFFF59E0B)
                )
            }
        }

        // 5. Complex Coding, Refactoring, Stacktrace & Debugging
        val isCoding = qwenIntent.intent == "CODE_CREATION" ||
                qwenIntent.intent == "DEV_TEAM_FULLSTACK" ||
                cleanPrompt.contains("refactor") ||
                cleanPrompt.contains("traceback") ||
                cleanPrompt.contains("stack trace") ||
                cleanPrompt.contains("nullpointerexception") ||
                cleanPrompt.contains("deadlock") ||
                cleanPrompt.contains("memory leak") ||
                cleanPrompt.contains("compile error") ||
                cleanPrompt.contains("syntax error") ||
                cleanPrompt.contains("function") ||
                cleanPrompt.contains("class") ||
                cleanPrompt.length > 500 ||
                prompt.count { it == '\n' } > 10

        if (isCoding) {
            val codeCandidate = resolveCandidateModel(config.codingModel, availableModels, quotas)
            if (codeCandidate != null) {
                val targetAgent = resolveAgentForTarget(config.codingModel, config.preferredCli)
                return SmartRoutingDecision(
                    targetModelId = codeCandidate.id,
                    targetAgent = targetAgent,
                    modelDisplayName = codeCandidate.displayName,
                    reason = RoutingReason.CODING,
                    explanation = "Qwen Brain Coding Dispatch · ${codeCandidate.displayName}",
                    badgeColor = Color(0xFF8B5CF6)
                )
            } else if (fallbackModel != null) {
                val targetAgent = resolveAgentForTarget(config.fallbackModel, config.preferredCli)
                return SmartRoutingDecision(
                    targetModelId = fallbackModel.id,
                    targetAgent = targetAgent,
                    modelDisplayName = fallbackModel.displayName,
                    reason = RoutingReason.QUOTA_FALLBACK,
                    explanation = "Coding model limit reached · Qwen Brain auto-switched to ${fallbackModel.displayName}",
                    badgeColor = Color(0xFFF59E0B)
                )
            }
        }

        // 6. Fast Q&A & General Inquiries
        val fastQaCandidate = resolveCandidateModel(config.fastQaModel, availableModels, quotas)
        if (fastQaCandidate != null) {
            val targetAgent = resolveAgentForTarget(config.fastQaModel, config.preferredCli)
            return SmartRoutingDecision(
                targetModelId = fastQaCandidate.id,
                targetAgent = targetAgent,
                modelDisplayName = fastQaCandidate.displayName,
                reason = RoutingReason.FAST_QA,
                explanation = "Qwen Brain Fast Q&A Dispatch · ${fastQaCandidate.displayName}",
                badgeColor = GeminiBlue
            )
        }

        // 7. General Cloud Fallback
        if (fallbackModel != null) {
            val targetAgent = resolveAgentForTarget(config.fallbackModel, config.preferredCli)
            return SmartRoutingDecision(
                targetModelId = fallbackModel.id,
                targetAgent = targetAgent,
                modelDisplayName = fallbackModel.displayName,
                reason = RoutingReason.QUOTA_FALLBACK,
                explanation = "Qwen Brain routed to fallback: ${fallbackModel.displayName}",
                badgeColor = Color(0xFFF59E0B)
            )
        }

        // 8. Ultimate on-device fallback
        return SmartRoutingDecision(
            targetModelId = LocalCodingDomain.QWEN_CODER.modelId,
            targetAgent = AgentKind.LOCAL,
            modelDisplayName = "Qwen 2.5 Coder 0.5B",
            reason = RoutingReason.LOCAL_SHELL,
            explanation = "Running on Local Qwen 2.5 Coder (100% On-Device Brain)",
            badgeColor = Color(0xFF10B981)
        )
    }
}

