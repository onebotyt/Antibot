package com.example.gemini.ui.chat

import androidx.compose.runtime.Immutable
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import java.io.File
import java.util.regex.Pattern

enum class WorkStepType {
    THINKING,
    SKILL_READ,
    FILE_CREATE,
    FILE_EDIT,
    FILE_CHECK,
    COMMAND_EXEC,
    WEB_SEARCH,
    TOOL_GENERIC,
    SUBAGENT_EXEC
}

enum class WorkStepStatus {
    RUNNING,
    COMPLETED,
    FAILED
}

@Immutable
data class WorkSummaryStep(
    val id: String,
    val type: WorkStepType,
    val title: String,
    val detailText: String? = null,
    val status: WorkStepStatus = WorkStepStatus.COMPLETED,
    val durationMs: Long? = null,
    val targetPath: String? = null
)

@Immutable
data class ClaudeArtifactItem(
    val id: String,
    val title: String,
    val fileName: String,
    val filePath: String,
    val fileExtension: String,
    val isWebHtml: Boolean,
    val codeContent: String? = null
)

@Immutable
data class TurnWorkSummary(
    val messageId: String,
    val steps: List<WorkSummaryStep>,
    val isActivelyRunning: Boolean,
    val latestTitle: String,
    val totalDurationMs: Long? = null
)

object ClaudeWorkSummaryParser {

    private val THOUGHT_TAG_REGEX = Pattern.compile("<!--\\s*thought(?::(\\d+))?\\s*-->([\\s\\S]*?)<!--\\s*/thought\\s*-->", Pattern.CASE_INSENSITIVE)
    private val XML_THOUGHT_REGEX = Pattern.compile("<thought>([\\s\\S]*?)</thought>", Pattern.CASE_INSENSITIVE)

    /**
     * Parses an assistant message to extract:
     * 1. The structured background work summary (timeline of thoughts, tool calls, and actions).
     * 2. Any created or referenced artifacts (HTML webpages, scripts, code files).
     * 3. Clean assistant markdown text with background thought and tool noise stripped.
     */
    fun parse(message: ChatMessage): Triple<TurnWorkSummary?, List<ClaudeArtifactItem>, String> {
        val steps = mutableListOf<WorkSummaryStep>()
        val artifacts = mutableListOf<ClaudeArtifactItem>()
        val seenArtifactPaths = mutableSetOf<String>()

        var cleanedContent = message.content

        // 1. Extract thought blocks
        val rawThoughts = mutableListOf<Pair<String, Long?>>()
        val thoughtMatcher = THOUGHT_TAG_REGEX.matcher(cleanedContent)
        while (thoughtMatcher.find()) {
            val dur = thoughtMatcher.group(1)?.toLongOrNull()
            val text = thoughtMatcher.group(2)?.trim().orEmpty()
            if (text.isNotBlank()) rawThoughts.add(text to dur)
        }
        cleanedContent = THOUGHT_TAG_REGEX.matcher(cleanedContent).replaceAll("")

        val xmlMatcher = XML_THOUGHT_REGEX.matcher(cleanedContent)
        while (xmlMatcher.find()) {
            val text = xmlMatcher.group(1)?.trim().orEmpty()
            if (text.isNotBlank()) rawThoughts.add(text to null)
        }
        cleanedContent = XML_THOUGHT_REGEX.matcher(cleanedContent).replaceAll("")

        if (rawThoughts.isEmpty() && !message.thoughtText.isNullOrBlank()) {
            rawThoughts.add(message.thoughtText.trim() to message.thoughtDurationMs)
        }

        // Process thought lines into distinct chronological timeline steps
        for ((idx, pair) in rawThoughts.withIndex()) {
            val (thoughtText, duration) = pair
            val lines = thoughtText.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue

            // Pick informative summaries from lines or bullet points
            val importantLines = lines.filter { line ->
                val l = line.lowercase()
                l.startsWith("planning") || l.startsWith("checking") || l.startsWith("choosing") ||
                l.startsWith("reading") || l.startsWith("creating") || l.startsWith("building") ||
                l.startsWith("designing") || l.startsWith("investigating") || l.startsWith("sleuthing") ||
                l.startsWith("- ") || l.startsWith("* ") || l.startsWith("1.") || l.startsWith("2.")
            }

            if (importantLines.isNotEmpty()) {
                for ((lineIdx, line) in importantLines.take(5).withIndex()) {
                    val cleanLine = line.removePrefix("- ").removePrefix("* ").replace(Regex("^\\d+\\.\\s*"), "").trim()
                    if (cleanLine.length in 5..85) {
                        steps.add(
                            WorkSummaryStep(
                                id = "thought_${idx}_$lineIdx",
                                type = WorkStepType.THINKING,
                                title = cleanLine.replaceFirstChar { it.uppercase() },
                                detailText = thoughtText,
                                status = WorkStepStatus.COMPLETED,
                                durationMs = if (lineIdx == 0) duration else null
                            )
                        )
                    }
                }
            } else {
                val firstLine = lines.first().take(75).trim()
                steps.add(
                    WorkSummaryStep(
                        id = "thought_$idx",
                        type = WorkStepType.THINKING,
                        title = firstLine.ifBlank { "Thinking" },
                        detailText = thoughtText,
                        status = WorkStepStatus.COMPLETED,
                        durationMs = duration
                    )
                )
            }
        }

        // 2. Extract tool calls into work steps & artifacts
        for ((idx, tool) in message.toolCalls.withIndex()) {
            val toolStatus = when (tool.status.uppercase()) {
                "SUCCESS" -> WorkStepStatus.COMPLETED
                "FAILED" -> WorkStepStatus.FAILED
                "RUNNING" -> WorkStepStatus.RUNNING
                else -> WorkStepStatus.COMPLETED
            }

            val step = when {
                tool.name.contains("skill", ignoreCase = true) || tool.command.contains("skill", ignoreCase = true) -> {
                    val skillName = extractSkillName(tool)
                    WorkSummaryStep(
                        id = tool.id,
                        type = WorkStepType.SKILL_READ,
                        title = if (skillName.isNotBlank()) "Read $skillName skill before building" else "Read skill guidance",
                        detailText = tool.output.ifBlank { tool.command },
                        status = toolStatus,
                        durationMs = tool.durationMs
                    )
                }
                tool.name == "write_to_file" || tool.name.contains("write", ignoreCase = true) -> {
                    val filePath = extractFilePath(tool.command)
                    val fileName = if (filePath.isNotBlank()) File(filePath).name else "file"
                    checkAndRegisterArtifact(filePath, tool.output, artifacts, seenArtifactPaths)
                    WorkSummaryStep(
                        id = tool.id,
                        type = WorkStepType.FILE_CREATE,
                        title = if (toolStatus == WorkStepStatus.FAILED) "Failed to create $fileName ⚠️" else "Created $fileName",
                        detailText = "Path: $filePath\n\n${tool.output.take(500)}",
                        status = toolStatus,
                        durationMs = tool.durationMs,
                        targetPath = filePath
                    )
                }
                tool.name == "replace_file_content" || tool.name.contains("replace", ignoreCase = true) -> {
                    val filePath = extractFilePath(tool.command)
                    val fileName = if (filePath.isNotBlank()) File(filePath).name else "file"
                    checkAndRegisterArtifact(filePath, tool.output, artifacts, seenArtifactPaths)
                    WorkSummaryStep(
                        id = tool.id,
                        type = WorkStepType.FILE_EDIT,
                        title = if (toolStatus == WorkStepStatus.FAILED) "Failed to edit $fileName ⚠️" else "Edited $fileName",
                        detailText = "Path: $filePath\n\n${tool.output.take(500)}",
                        status = toolStatus,
                        durationMs = tool.durationMs,
                        targetPath = filePath
                    )
                }
                tool.name == "view_file" || tool.name.contains("view", ignoreCase = true) || tool.name.contains("read", ignoreCase = true) -> {
                    val filePath = extractFilePath(tool.command)
                    val fileName = if (filePath.isNotBlank()) File(filePath).name else "file"
                    WorkSummaryStep(
                        id = tool.id,
                        type = WorkStepType.FILE_CHECK,
                        title = "Checking whether the $fileName already exists",
                        detailText = tool.command,
                        status = toolStatus,
                        durationMs = tool.durationMs,
                        targetPath = filePath
                    )
                }
                tool.toolType == ToolType.SEARCH_WEB || tool.name.contains("search", ignoreCase = true) -> {
                    WorkSummaryStep(
                        id = tool.id,
                        type = WorkStepType.WEB_SEARCH,
                        title = "Searching web for documentation & patterns",
                        detailText = tool.command,
                        status = toolStatus,
                        durationMs = tool.durationMs
                    )
                }
                tool.toolType == ToolType.BASH || tool.name == "run_command" -> {
                    val cmd = tool.command.trim().lines().firstOrNull()?.take(60) ?: "command"
                    WorkSummaryStep(
                        id = tool.id,
                        type = WorkStepType.COMMAND_EXEC,
                        title = "Running $cmd",
                        detailText = "Command:\n${tool.command}\n\nOutput:\n${tool.output}",
                        status = toolStatus,
                        durationMs = tool.durationMs
                    )
                }
                tool.name == "invoke_subagent" || tool.name.contains("subagent", ignoreCase = true) -> {
                    val role = extractSubagentRole(tool)
                    val engine = extractSubagentEngine(tool)
                    val title = when {
                        role.isNotBlank() && engine.isNotBlank() -> "Subagent: $role ($engine)"
                        role.isNotBlank() -> "Subagent: $role"
                        engine.isNotBlank() -> "Subagent ($engine)"
                        else -> "Subagent Delegated Task"
                    }
                    WorkSummaryStep(
                        id = tool.id,
                        type = WorkStepType.SUBAGENT_EXEC,
                        title = if (toolStatus == WorkStepStatus.FAILED) "$title ⚠️" else title,
                        detailText = "Subagent Spec:\n${tool.command}\n\nExecution Result:\n${tool.output}",
                        status = toolStatus,
                        durationMs = tool.durationMs
                    )
                }
                else -> {
                    WorkSummaryStep(
                        id = tool.id,
                        type = WorkStepType.TOOL_GENERIC,
                        title = "Executed ${tool.name}",
                        detailText = tool.output.ifBlank { tool.command },
                        status = toolStatus,
                        durationMs = tool.durationMs
                    )
                }
            }
            steps.add(step)
        }

        // Check if message content references generated web preview files or artifacts directly
        detectArtifactsInText(cleanedContent, artifacts, seenArtifactPaths)

        val isRunning = message.isStreaming || steps.any { it.status == WorkStepStatus.RUNNING }
        val latestTitle = when {
            isRunning -> {
                steps.lastOrNull { it.status == WorkStepStatus.RUNNING }?.title
                    ?: steps.lastOrNull()?.title
                    ?: "Thinking"
            }
            steps.isNotEmpty() -> steps.last().title
            else -> "Completed"
        }

        val totalDuration = message.thoughtDurationMs ?: message.tokenUsage?.durationMs

        val summary = if (steps.isNotEmpty() || isRunning) {
            TurnWorkSummary(
                messageId = message.id,
                steps = steps,
                isActivelyRunning = isRunning,
                latestTitle = latestTitle,
                totalDurationMs = totalDuration
            )
        } else null

        return Triple(summary, artifacts, cleanedContent.trim())
    }

    private fun extractSkillName(tool: ToolCall): String {
        val cmd = tool.command
        val nameMatch = Regex("""(?:get_skill|read_skill_file|skill)[\s:=]+["']?([a-zA-Z0-9_\-]+)""").find(cmd)
        if (nameMatch != null) {
            return nameMatch.groupValues[1].replace("-", " ")
        }
        val outputMatch = Regex("""Skill:\s*([^\n\r]+)""").find(tool.output)
        if (outputMatch != null) {
            return outputMatch.groupValues[1].trim()
        }
        return ""
    }

    private fun extractSubagentRole(tool: ToolCall): String {
        val cmd = tool.command
        val roleMatch = Regex("""Role[:=\s]+["']?([^"'\n,]+)""").find(cmd)
        if (roleMatch != null) return roleMatch.groupValues[1].trim()
        val typeMatch = Regex("""TypeName[:=\s]+["']?([^"'\n,]+)""").find(cmd)
        if (typeMatch != null) return typeMatch.groupValues[1].trim()
        val jsonRole = Regex(""""(?:role|type_name)"\s*:\s*"([^"]+)"""").find(cmd)
        if (jsonRole != null) return jsonRole.groupValues[1].trim()
        return ""
    }

    private fun extractSubagentEngine(tool: ToolCall): String {
        val cmd = tool.command
        val engineMatch = Regex("""(?:target_engine|engine|cli)[:=\s]+["']?([a-zA-Z0-9_\-]+)""").find(cmd)
        if (engineMatch != null) return engineMatch.groupValues[1].trim().uppercase()
        val modelMatch = Regex("""(?:model|model_tier)[:=\s]+["']?([a-zA-Z0-9_\-.]+)""").find(cmd)
        if (modelMatch != null) return modelMatch.groupValues[1].trim()
        return ""
    }

    private fun extractFilePath(command: String): String {
        val targetMatch = Regex("""TargetFile[:=\s]+["']?([^"'\s,]+)""").find(command)
        if (targetMatch != null) return targetMatch.groupValues[1]

        val pathMatch = Regex("""(?:file|path)[:=\s]+["']?([^"'\s,]+)""").find(command)
        if (pathMatch != null) return pathMatch.groupValues[1]

        val generalMatch = Regex("""(/data/user/0/[^\s"']+|/[a-zA-Z0-9_\-./]+\.[a-zA-Z0-9]+)""").find(command)
        if (generalMatch != null) return generalMatch.groupValues[1]

        return ""
    }

    private fun checkAndRegisterArtifact(
        filePath: String,
        output: String,
        artifacts: MutableList<ClaudeArtifactItem>,
        seen: MutableSet<String>
    ) {
        if (filePath.isBlank() || seen.contains(filePath)) return
        val file = File(filePath)
        val ext = file.extension.lowercase()
        val isWeb = ext in listOf("html", "htm", "svg") || (ext in listOf("js", "ts", "jsx", "tsx") && (filePath.contains("web") || filePath.contains("client") || filePath.contains("portfolio")))

        val isCode = ext in listOf("html", "htm", "css", "js", "ts", "jsx", "tsx", "py", "kt", "rs", "go", "json", "md", "sh")
        if (!isCode) return

        seen.add(filePath)
        val title = if (file.name.contains("portfolio", ignoreCase = true)) {
            "Developer Portfolio · ${file.name}"
        } else {
            file.name
        }

        var code: String? = null
        if (file.exists() && file.isFile && file.length() < 2_000_000) {
            try { code = file.readText(Charsets.UTF_8) } catch (_: Exception) {}
        }

        artifacts.add(
            ClaudeArtifactItem(
                id = filePath.hashCode().toString(),
                title = title,
                fileName = file.name,
                filePath = filePath,
                fileExtension = ext,
                isWebHtml = isWeb,
                codeContent = code
            )
        )
    }

    private fun detectArtifactsInText(
        text: String,
        artifacts: MutableList<ClaudeArtifactItem>,
        seen: MutableSet<String>
    ) {
        val matches = Regex("""(?:file://|path:\s*|Project Location:\s*)([^\s\n\r"']+\.(?:html|htm|py|js|ts|kt|rs|go|css))""").findAll(text)
        for (m in matches) {
            val rawPath = m.groupValues[1].removePrefix("file://").trim()
            checkAndRegisterArtifact(rawPath, "", artifacts, seen)
        }

        // Detect full HTML blocks (```html ... ``` or <!DOCTYPE html... / <html>...)
        val htmlBlockRegex = Regex("""```(?:html|htm)\s*\n([\s\S]*?)```""", RegexOption.IGNORE_CASE)
        val codeMatch = htmlBlockRegex.find(text)
        if (codeMatch != null) {
            val htmlContent = codeMatch.groupValues[1].trim()
            if (htmlContent.contains("<html", ignoreCase = true) || htmlContent.contains("<!DOCTYPE", ignoreCase = true)) {
                val inlineId = "inline_html_${htmlContent.hashCode()}"
                if (!seen.contains(inlineId)) {
                    seen.add(inlineId)
                    val title = extractHtmlTitle(htmlContent) ?: "Web Preview Artifact"
                    artifacts.add(
                        ClaudeArtifactItem(
                            id = inlineId,
                            title = title,
                            fileName = "index.html",
                            filePath = "",
                            fileExtension = "html",
                            isWebHtml = true,
                            codeContent = htmlContent
                        )
                    )
                }
            }
        } else if (text.trimStart().startsWith("<!DOCTYPE html", ignoreCase = true) || text.trimStart().startsWith("<html", ignoreCase = true)) {
            val htmlContent = text.trim()
            val inlineId = "inline_html_${htmlContent.hashCode()}"
            if (!seen.contains(inlineId)) {
                seen.add(inlineId)
                val title = extractHtmlTitle(htmlContent) ?: "Web Preview Artifact"
                artifacts.add(
                    ClaudeArtifactItem(
                        id = inlineId,
                        title = title,
                        fileName = "index.html",
                        filePath = "",
                        fileExtension = "html",
                        isWebHtml = true,
                        codeContent = htmlContent
                    )
                )
            }
        }
    }

    private fun extractHtmlTitle(html: String): String? {
        val titleMatch = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html)
        return titleMatch?.groupValues?.get(1)?.trim()
    }
}
