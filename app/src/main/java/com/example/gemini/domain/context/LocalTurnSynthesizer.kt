package com.example.gemini.domain.context

import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import java.io.File
import java.util.regex.Pattern

/**
 * LocalTurnRecap: Structured summary of an agent turn synthesized on-device with 0 tokens.
 */
data class LocalTurnRecap(
    val conversationId: String,
    val summary: String,
    val modifiedFiles: List<String>,
    val executedCommands: List<String>,
    val errorSummary: String? = null,
    val suggestedNextSteps: List<String>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * LocalTurnSynthesizer: On-device intelligence engine that performs local recap synthesis,
 * zero-token continuation prompt generation, and prompt pruning without spending remote model tokens.
 */
object LocalTurnSynthesizer {

    private val TARGET_FILE_REGEX = Pattern.compile("""(?:TargetFile|targetFile|path|file)["':=\s]+([/\w.\-_]+)""")
    private val NEXT_STEP_PATTERNS = listOf(
        Pattern.compile("""(?i)(?:next\s*(?:step|up|action)?[:\-]\s*)([^\n.!?]{5,80})"""),
        Pattern.compile("""(?i)(?:you can\s*(?:now)?\s*(?:run|try|test|verify)?[:\-]?\s*)([^\n.!?]{5,80})"""),
        Pattern.compile("""(?i)(?:to\s+(?:test|verify|run|check|continue)[:\-]?\s*)([^\n.!?]{5,80})"""),
        Pattern.compile("""(?i)(?:would you like (?:me to)?\s*)([^\n.!?]{5,80})""")
    )

    /**
     * Synthesize a turn recap and next actions from recent chat messages and flight recorder checkpoints.
     */
    fun synthesizeFromTurn(
        conversationId: String,
        messages: List<ChatMessage>,
        checkpoint: TaskFlightRecorder.Checkpoint? = null
    ): LocalTurnRecap? {
        if (messages.isEmpty()) return null

        val lastAssistantMsg = messages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return null
        val recentToolCalls = mutableListOf<ToolCall>()

        // Gather all tool calls from the latest assistant turn
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            if (msg.role == MessageRole.USER) break
            recentToolCalls.addAll(msg.toolCalls)
        }

        val modifiedFiles = mutableSetOf<String>()
        val executedCommands = mutableListOf<String>()
        var detectedError: String? = null

        // 1. Analyze tool calls
        for (call in recentToolCalls) {
            val name = call.name.lowercase()
            val cmd = call.command

            if (name.contains("replace") || name.contains("write") || name.contains("edit") || call.toolType == ToolType.EDIT_FILE) {
                extractFilePath(cmd)?.let { modifiedFiles.add(it) }
            } else if (name.contains("run_command") || name.contains("bash") || call.toolType == ToolType.BASH) {
                if (cmd.isNotBlank()) {
                    val cleanCmd = cmd.lines().firstOrNull()?.trim() ?: cmd.trim()
                    if (cleanCmd.isNotEmpty() && !executedCommands.contains(cleanCmd)) {
                        executedCommands.add(cleanCmd)
                    }
                }
            }

            // Detect errors in tool output
            if (call.status == "FAILED" || (call.exitCode != null && call.exitCode != 0)) {
                if (detectedError == null) {
                    val errLine = call.output.lines().firstOrNull { line ->
                        val lower = line.lowercase()
                        lower.contains("error") || lower.contains("failed") || lower.contains("exception")
                    } ?: call.output.take(80)
                    detectedError = errLine.trim().take(100)
                }
            }
        }

        // 2. Fall back to checkpoint if available
        if (checkpoint != null && modifiedFiles.isEmpty()) {
            for (action in checkpoint.completedActions) {
                extractFilePath(action)?.let { modifiedFiles.add(it) }
            }
        }

        // 3. Scan assistant response content for text cues
        val suggestions = mutableListOf<String>()

        if (detectedError != null) {
            suggestions.add("Fix the error: $detectedError")
        }

        // Extract explicit next-step cues from assistant text
        for (pattern in NEXT_STEP_PATTERNS) {
            val matcher = pattern.matcher(lastAssistantMsg.content)
            while (matcher.find() && suggestions.size < 4) {
                val candidate = matcher.group(1)?.trim()?.trimEnd('.', ':', '!', ';')
                if (!candidate.isNullOrBlank() && candidate.length in 6..70) {
                    val clean = candidate.replace(Regex("^[\\s*\\-`]+"), "").replace(Regex("[`*]+$"), "")
                    if (!suggestions.any { it.equals(clean, ignoreCase = true) }) {
                        suggestions.add(clean.replaceFirstChar { it.uppercase() })
                    }
                }
            }
        }

        // Smart context-based defaults if no explicit next steps found
        if (suggestions.isEmpty() || suggestions.size < 2) {
            if (modifiedFiles.isNotEmpty()) {
                suggestions.add("Verify changes and run tests")
                suggestions.add("Start dev server to preview")
            }
            if (executedCommands.isNotEmpty() && detectedError == null) {
                suggestions.add("Check git diff and status")
            }
            if (suggestions.isEmpty()) {
                suggestions.add("Proceed to next step")
                suggestions.add("Review summary of changes")
            }
        }

        // Build brief human-readable summary
        val summaryBuilder = StringBuilder()
        if (modifiedFiles.isNotEmpty()) {
            summaryBuilder.append("Updated ${modifiedFiles.size} file${if (modifiedFiles.size > 1) "s" else ""}")
            if (executedCommands.isNotEmpty()) {
                summaryBuilder.append(" • Executed ${executedCommands.size} command${if (executedCommands.size > 1) "s" else ""}")
            }
        } else if (executedCommands.isNotEmpty()) {
            summaryBuilder.append("Executed ${executedCommands.size} command${if (executedCommands.size > 1) "s" else ""}")
        } else {
            summaryBuilder.append("Turn completed")
        }

        if (detectedError != null) {
            summaryBuilder.append(" • Issue detected")
        }

        return LocalTurnRecap(
            conversationId = conversationId,
            summary = summaryBuilder.toString(),
            modifiedFiles = modifiedFiles.toList(),
            executedCommands = executedCommands,
            errorSummary = detectedError,
            suggestedNextSteps = suggestions.take(3),
            timestamp = System.currentTimeMillis()
        )
    }

    private fun extractFilePath(text: String): String? {
        val matcher = TARGET_FILE_REGEX.matcher(text)
        if (matcher.find()) {
            val raw = matcher.group(1) ?: return null
            return File(raw).name
        }
        // Match general Unix file paths ending with common code extensions
        val pathMatcher = Pattern.compile("""(?:^|\s)(/[^\s]+\.(?:kt|java|xml|gradle|js|ts|py|json|md|sh|c|cpp|rs|go))""").matcher(text)
        if (pathMatcher.find()) {
            val raw = pathMatcher.group(1) ?: return null
            return File(raw).name
        }
        return null
    }

    /**
     * Zero-token Prompt Pruning: Cleans repetitive noise and huge stdout blocks before sending.
     */
    fun prunePrompt(rawPrompt: String): String {
        if (rawPrompt.length < 500) return rawPrompt.trim()

        val lines = rawPrompt.lines()
        val cleanedLines = mutableListOf<String>()
        var duplicateStreak = 0
        var lastLine: String? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed == lastLine && trimmed.isNotBlank()) {
                duplicateStreak++
                if (duplicateStreak <= 2) {
                    cleanedLines.add(line)
                } else if (duplicateStreak == 3) {
                    cleanedLines.add("... [Repeated lines pruned locally] ...")
                }
            } else {
                duplicateStreak = 0
                lastLine = trimmed
                cleanedLines.add(line)
            }
        }

        val result = cleanedLines.joinToString("\n")
        return result.replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
