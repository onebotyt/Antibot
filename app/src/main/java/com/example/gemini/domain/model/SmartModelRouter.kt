package com.example.gemini.domain.model

import androidx.compose.ui.graphics.Color
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.theme.GeminiBlue

/**
 * Categorized routing reasons explaining why the Auto LLM Manager chose a specific model.
 */
enum class RoutingReason(val label: String, val badgeColor: Color) {
    LOCAL_SHELL("Local Shell", Color(0xFF10B981)),
    LOCAL_WORKSPACE("Workspace", Color(0xFF10B981)),
    LOCAL_PORT_PROCESS("Port/Process", Color(0xFF10B981)),
    LOCAL_OFFLINE("Offline Fallback", Color(0xFF10B981)),
    FAST_QA("Fast Q&A", GeminiBlue),
    DEEP_REASONING("Deep Logic", ClaudeTerracotta),
    CYBER_SECURITY("Security", Color(0xFFEF4444)),
    QUOTA_FALLBACK("Quota Fallback", Color(0xFFF59E0B)),
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
    val badgeColor: Color = reason.badgeColor
) {
    val shortPillText: String
        get() = when {
            targetModelId.startsWith("local-") || targetModelId == "local-model-only" -> "Auto ➔ Qwen"
            targetModelId.contains("claude", ignoreCase = true) -> "Auto ➔ Claude"
            targetModelId.contains("gemini", ignoreCase = true) -> "Auto ➔ Flash"
            else -> "Auto ➔ $modelDisplayName"
        }

    val dynamicDisplayPill: String
        get() = "$shortPillText (${reason.label})"
}

/**
 * AntiBot Smart LLM Manager & Model Router.
 * Automatically dispatches queries across Local Qwen, Fast Cloud (Flash), and Deep Reasoning (Claude)
 * based on prompt heuristics, local filesystem/terminal intent, connectivity, and quota availability.
 */
object SmartModelRouter {
    const val AUTO_MODEL_ID = "auto"
    const val AUTO_DISPLAY_NAME = "Auto (Smart LLM Manager)"

    fun isAuto(modelId: String?): Boolean {
        if (modelId.isNullOrBlank()) return true
        return modelId.equals(AUTO_MODEL_ID, ignoreCase = true) ||
                modelId.equals("smart-router", ignoreCase = true) ||
                modelId.equals("auto-manager", ignoreCase = true)
    }

    /**
     * Resolves the optimal model and backend for a given user prompt.
     */
    fun route(
        prompt: String,
        hasAttachments: Boolean = false,
        isOnline: Boolean = true,
        availableModels: List<AiModel> = emptyList(),
        quotas: List<ModelQuota> = emptyList(),
        currentSelectedModelId: String = AUTO_MODEL_ID
    ): SmartRoutingDecision {
        // 1. If user manually locked onto a specific model, honor it
        if (!isAuto(currentSelectedModelId)) {
            val isLocal = LocalCodingDomain.isLocalModel(currentSelectedModelId)
            val modelObj = availableModels.find { it.id == currentSelectedModelId }
            return SmartRoutingDecision(
                targetModelId = currentSelectedModelId,
                targetAgent = if (isLocal) AgentKind.LOCAL else AgentKind.AGY,
                modelDisplayName = if (isLocal) LocalCodingDomain.fromModelId(currentSelectedModelId).displayName else (modelObj?.displayName ?: currentSelectedModelId),
                reason = RoutingReason.MANUAL,
                explanation = "Manually selected model",
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
                explanation = "Network offline · Running 100% on-device neural engine",
                badgeColor = Color(0xFF10B981)
            )
        }

        val cleanPrompt = prompt.trim().lowercase()

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

        if (isTerminalCommand || isPortOrProcess || isWorkspaceInspection) {
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
                explanation = "Executed locally on device (0 tokens, instant bash/port execution)",
                badgeColor = Color(0xFF10B981)
            )
        }

        // 4. Cloud Models lookup & quota verification
        val claudeModel = availableModels.find { it.family == ModelFamily.CLAUDE }
            ?: availableModels.find { it.id.contains("claude", ignoreCase = true) }
        val claudeQuota = quotas.find { it.modelId == claudeModel?.id }
        val isClaudeAvailable = claudeModel != null && (claudeQuota == null || claudeQuota.percentage > 0)

        val flashModel = availableModels.find {
            it.id.contains("flash", ignoreCase = true) || it.displayName.contains("flash", ignoreCase = true)
        } ?: availableModels.find { it.family == ModelFamily.GEMINI }
        val flashQuota = quotas.find { it.modelId == flashModel?.id }
        val isFlashAvailable = flashModel != null && (flashQuota == null || flashQuota.percentage > 0)

        // 5. Deep Reasoning / Architecture / Complex Debugging / Security Auditing
        val isSecurityAudit = cleanPrompt.contains("security audit") ||
                cleanPrompt.contains("owasp") ||
                cleanPrompt.contains("vulnerability") ||
                cleanPrompt.contains("pentest") ||
                cleanPrompt.contains("penetration test") ||
                cleanPrompt.contains("sql injection") ||
                cleanPrompt.contains("xss") ||
                cleanPrompt.contains("csrf") ||
                cleanPrompt.contains("hardening")

        val isDeepReasoning = isSecurityAudit ||
                cleanPrompt.contains("architecture") ||
                cleanPrompt.contains("architect") ||
                cleanPrompt.contains("design pattern") ||
                cleanPrompt.contains("refactor") ||
                cleanPrompt.contains("system design") ||
                cleanPrompt.contains("plan") ||
                cleanPrompt.contains("step by step") ||
                cleanPrompt.contains("traceback") ||
                cleanPrompt.contains("stack trace") ||
                cleanPrompt.contains("nullpointerexception") ||
                cleanPrompt.contains("deadlock") ||
                cleanPrompt.contains("memory leak") ||
                cleanPrompt.length > 700 ||
                prompt.count { it == '\n' } > 15

        if (isDeepReasoning) {
            if (isClaudeAvailable && claudeModel != null) {
                return SmartRoutingDecision(
                    targetModelId = claudeModel.id,
                    targetAgent = AgentKind.AGY,
                    modelDisplayName = claudeModel.displayName,
                    reason = if (isSecurityAudit) RoutingReason.CYBER_SECURITY else RoutingReason.DEEP_REASONING,
                    explanation = if (isSecurityAudit) "Security & Vulnerability Audit · Claude" else "Deep Architectural & Coding Logic · Claude Thinking",
                    badgeColor = if (isSecurityAudit) Color(0xFFEF4444) else ClaudeTerracotta
                )
            } else if (isFlashAvailable && flashModel != null) {
                // Seamless fallback to Gemini Flash if Claude quota is exhausted
                return SmartRoutingDecision(
                    targetModelId = flashModel.id,
                    targetAgent = AgentKind.AGY,
                    modelDisplayName = flashModel.displayName,
                    reason = RoutingReason.QUOTA_FALLBACK,
                    explanation = "Claude quota exhausted · Auto-routed to ${flashModel.displayName}",
                    badgeColor = Color(0xFFF59E0B)
                )
            } else {
                // Fallback to local Qwen
                return SmartRoutingDecision(
                    targetModelId = LocalCodingDomain.QWEN_CODER.modelId,
                    targetAgent = AgentKind.LOCAL,
                    modelDisplayName = "Qwen 2.5 Coder 0.5B",
                    reason = RoutingReason.QUOTA_FALLBACK,
                    explanation = "Cloud quotas exhausted · Auto-fallback to Local Qwen",
                    badgeColor = Color(0xFF10B981)
                )
            }
        }

        // 6. Fast Q&A & General Inquiries -> Route to Gemini Flash
        if (isFlashAvailable && flashModel != null) {
            return SmartRoutingDecision(
                targetModelId = flashModel.id,
                targetAgent = AgentKind.AGY,
                modelDisplayName = flashModel.displayName,
                reason = RoutingReason.FAST_QA,
                explanation = "Fast Q&A & General Assistance · ${flashModel.displayName}",
                badgeColor = GeminiBlue
            )
        } else if (isClaudeAvailable && claudeModel != null) {
            return SmartRoutingDecision(
                targetModelId = claudeModel.id,
                targetAgent = AgentKind.AGY,
                modelDisplayName = claudeModel.displayName,
                reason = RoutingReason.DEEP_REASONING,
                explanation = "Routed to available ${claudeModel.displayName}",
                badgeColor = ClaudeTerracotta
            )
        }

        // 7. Ultimate fallback: Local Qwen 2.5 Coder
        return SmartRoutingDecision(
            targetModelId = LocalCodingDomain.QWEN_CODER.modelId,
            targetAgent = AgentKind.LOCAL,
            modelDisplayName = "Qwen 2.5 Coder 0.5B",
            reason = RoutingReason.LOCAL_SHELL,
            explanation = "Running on Local Qwen 2.5 Coder (100% On-Device)",
            badgeColor = Color(0xFF10B981)
        )
    }
}
