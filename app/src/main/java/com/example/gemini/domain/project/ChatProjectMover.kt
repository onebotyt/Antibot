package com.example.gemini.domain.project

import com.example.gemini.data.daemon.ProjectItem
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ProjectExportResult(
    val success: Boolean,
    val projectItem: ProjectItem,
    val filesWritten: List<String>,
    val message: String
)

data class MoveChatIntent(
    val isMatch: Boolean,
    val projectName: String?,
    val isNewProject: Boolean
)

/**
 * Universal project and conversation exporter for AntiBot.
 * Exports active chat transcripts, requirements, and decisions into
 * structured PROJECT_CONTEXT.md and docs/CONVERSATION_HISTORY.md files
 * within a project workspace.
 */
object ChatProjectMover {

    val DEFAULT_PROJECTS_DIR = "/data/user/0/com.termux/files/home/projects"
    val SCRATCH_PROJECTS_DIR = "/data/user/0/com.termux/files/home/.gemini/antigravity-cli/scratch"

    fun getPreferredProjectsRoot(): File {
        val defaultDir = File(DEFAULT_PROJECTS_DIR)
        if (defaultDir.exists() && defaultDir.canWrite()) return defaultDir
        val scratchDir = File(SCRATCH_PROJECTS_DIR)
        if (scratchDir.exists() && scratchDir.canWrite()) return scratchDir
        defaultDir.mkdirs()
        return defaultDir
    }

    /**
     * Detects if a user message in chat is instructing the AI to create/move
     * the current conversation into a project workspace.
     */
    fun parseMoveChatIntent(prompt: String): MoveChatIntent? {
        val trimmed = prompt.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. "make new project <name> and move (all/this)? chat inside/into that"
        val makeNewRegex = Regex(
            """(?:make|create|start)\s+(?:a\s+)?new\s+project\s+([a-zA-Z0-9_\-\.]+).*?move\s+(?:all\s+)?(?:the\s+|this\s+)?chat""",
            RegexOption.IGNORE_CASE
        )
        makeNewRegex.find(lower)?.let { match ->
            val name = match.groupValues.getOrNull(1)?.trim()
            return MoveChatIntent(isMatch = true, projectName = name, isNewProject = true)
        }

        // 2. "move (all/this)? chat (in(to)?|to) (new\s+)?project\s+([a-zA-Z0-9_\-\.]+)"
        val moveRegex = Regex(
            """(?:move|save|export)\s+(?:all\s+)?(?:the\s+|this\s+)?chat\s+(?:in(?:to)?|to)\s+(?:a\s+)?(?:new\s+)?project(?:\s+called|\s+named)?\s*([a-zA-Z0-9_\-\.]*)""",
            RegexOption.IGNORE_CASE
        )
        moveRegex.find(lower)?.let { match ->
            val name = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            val isNew = lower.contains("new project")
            return MoveChatIntent(isMatch = true, projectName = name, isNewProject = isNew)
        }

        // 3. "move into project <name>" or "move to project <name>"
        val moveToRegex = Regex(
            """(?:move|save|export)\s+(?:into|to)\s+(?:new\s+)?project(?:\s+called|\s+named)?\s*([a-zA-Z0-9_\-\.]*)""",
            RegexOption.IGNORE_CASE
        )
        moveToRegex.find(lower)?.let { match ->
            val name = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            val isNew = lower.contains("new project")
            return MoveChatIntent(isMatch = true, projectName = name, isNewProject = isNew)
        }

        // 4. Generic request: "move this chat into project", "move chat to project"
        if (lower.contains("move") && lower.contains("chat") && lower.contains("project")) {
            return MoveChatIntent(isMatch = true, projectName = null, isNewProject = lower.contains("new project"))
        }

        return null
    }

    /**
     * Creates a new project workspace folder and exports all chat history and context into it.
     */
    fun createProjectAndMoveChat(
        projectName: String?,
        conversation: Conversation?,
        messages: List<ChatMessage>,
        baseDir: File = getPreferredProjectsRoot()
    ): ProjectExportResult {
        val sanitized = sanitizeProjectName(projectName ?: conversation?.title ?: "Project_${System.currentTimeMillis() % 10000}")
        val targetDir = File(baseDir, sanitized)
        targetDir.mkdirs()
        return exportChatToProject(targetDir, conversation, messages, sanitized)
    }

    /**
     * Exports the conversation transcript and active context into an existing or new project directory.
     */
    fun exportChatToProject(
        projectDir: File,
        conversation: Conversation?,
        messages: List<ChatMessage>,
        customProjectName: String? = null
    ): ProjectExportResult {
        try {
            if (!projectDir.exists()) {
                projectDir.mkdirs()
            }
            val docsDir = File(projectDir, "docs")
            if (!docsDir.exists()) {
                docsDir.mkdirs()
            }

            val projectName = customProjectName ?: projectDir.name
            val filesWritten = mutableListOf<String>()
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            // 1. PROJECT_CONTEXT.md
            val contextFile = File(projectDir, "PROJECT_CONTEXT.md")
            val contextContent = buildProjectContextMarkdown(projectName, projectDir, conversation, messages, timestamp)
            contextFile.writeText(contextContent)
            filesWritten.add(contextFile.name)

            // 2. docs/CONVERSATION_HISTORY.md
            val historyFile = File(docsDir, "CONVERSATION_HISTORY.md")
            val historyContent = buildConversationHistoryMarkdown(projectName, conversation, messages, timestamp)
            historyFile.writeText(historyContent)
            filesWritten.add("docs/${historyFile.name}")

            // 3. AGENTS.md (if absent)
            val agentsFile = File(projectDir, "AGENTS.md")
            if (!agentsFile.exists()) {
                val agentsContent = """
                    # Agent Guidelines & Continuity
                    
                    ## 0. Project Context & Memory
                    - **Always Read Context First**: At the start of any conversation, read [PROJECT_CONTEXT.md](file://${contextFile.absolutePath}) and [docs/CONVERSATION_HISTORY.md](file://${historyFile.absolutePath}) to load the current architecture, completed tasks, and user requirements.
                    
                    ## 1. Operating Rules
                    - Maintain full parity between JSON and binary protocols.
                    - Always answer user questions and explain changes before editing files.
                    - Always use the active project workspace (${projectDir.absolutePath}) for terminal commands and file edits.
                """.trimIndent()
                agentsFile.writeText(agentsContent)
                filesWritten.add(agentsFile.name)
            }

            // 4. README.md (if project is otherwise empty)
            val readmeFile = File(projectDir, "README.md")
            if (!readmeFile.exists()) {
                val readmeContent = """
                    # $projectName
                    
                    Project workspace created by AntiBot.
                    
                    - **Created On**: $timestamp
                    - **Context Documentation**: See [PROJECT_CONTEXT.md](./PROJECT_CONTEXT.md)
                    - **Chat History**: See [docs/CONVERSATION_HISTORY.md](./docs/CONVERSATION_HISTORY.md)
                """.trimIndent()
                readmeFile.writeText(readmeContent)
                filesWritten.add(readmeFile.name)
            }

            val item = ProjectItem(name = projectName, path = projectDir.absolutePath)
            return ProjectExportResult(
                success = true,
                projectItem = item,
                filesWritten = filesWritten,
                message = "Successfully exported chat into project '$projectName' at ${projectDir.absolutePath}."
            )
        } catch (e: Exception) {
            val item = ProjectItem(name = customProjectName ?: projectDir.name, path = projectDir.absolutePath)
            return ProjectExportResult(
                success = false,
                projectItem = item,
                filesWritten = emptyList(),
                message = "Failed to export chat: ${e.message}"
            )
        }
    }

    private fun sanitizeProjectName(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("""^["']|["']$"""), "")
            .replace(Regex("""[^a-zA-Z0-9_\-\.]"""), "-")
            .replace(Regex("""-+"""), "-")
            .trim('-')
        return if (cleaned.isBlank()) "Antibot_Project" else cleaned
    }

    private fun buildProjectContextMarkdown(
        projectName: String,
        projectDir: File,
        conv: Conversation?,
        messages: List<ChatMessage>,
        timestamp: String
    ): String = buildString {
        appendLine("# Project Context & Active Memory: $projectName")
        appendLine()
        appendLine("> **AntiBot Workspace Continuity Document**")
        appendLine("> - Generated: $timestamp")
        appendLine("> - Source Chat: \"${conv?.title ?: "New Chat"}\" (ID: `${conv?.id ?: "unknown"}`)")
        appendLine("> - Active Agent: `${conv?.agent?.name ?: "AGY"}`")
        appendLine("> - Workspace Path: `${projectDir.absolutePath}`")
        appendLine()
        appendLine("---")
        appendLine()
        appendLine("## 1. Project Overview")
        appendLine("This project workspace was created or linked directly from an active AntiBot conversation.")
        appendLine("It contains all accumulated project requirements, technical specifications, and architectural state.")
        appendLine()

        // Extract high-level summary from conversation or user messages
        appendLine("## 2. Requirements & Context Summary")
        if (!conv?.summary.isNullOrBlank()) {
            appendLine(conv?.summary)
        } else {
            val userPrompts = messages.filter { it.role == MessageRole.USER }.map { it.content.trim() }
            if (userPrompts.isNotEmpty()) {
                appendLine("Key requests and instructions from user:")
                userPrompts.takeLast(6).forEach { prompt ->
                    val singleLine = prompt.lines().firstOrNull()?.take(120) ?: prompt.take(120)
                    appendLine("- $singleLine")
                }
            } else {
                appendLine("Clean starter project workspace initialized from chat.")
            }
        }
        appendLine()

        appendLine("## 3. Continuity Guidelines for Agents")
        appendLine("When working inside this project:")
        appendLine("1. Check `docs/CONVERSATION_HISTORY.md` for the complete transcript of previous discussion and decisions.")
        appendLine("2. Execute all commands, dev servers, and file operations relative to `${projectDir.absolutePath}`.")
        appendLine("3. Update this document whenever significant architecture changes or features are implemented.")
    }

    private fun buildConversationHistoryMarkdown(
        projectName: String,
        conv: Conversation?,
        messages: List<ChatMessage>,
        timestamp: String
    ): String = buildString {
        appendLine("# Conversation History & Session Transcript")
        appendLine()
        appendLine("- **Project**: $projectName")
        appendLine("- **Conversation Title**: ${conv?.title ?: "New Chat"}")
        appendLine("- **Conversation ID**: `${conv?.id ?: "unknown"}`")
        appendLine("- **Exported At**: $timestamp")
        appendLine("- **Total Messages**: ${messages.size}")
        appendLine()
        appendLine("---")
        appendLine()

        if (messages.isEmpty()) {
            appendLine("*No messages in conversation yet.*")
            return@buildString
        }

        messages.forEachIndexed { index, msg ->
            val roleName = when (msg.role) {
                MessageRole.USER -> "USER"
                MessageRole.ASSISTANT -> "ASSISTANT (${msg.modelId ?: "Agent"})"
                MessageRole.SYSTEM -> "SYSTEM"
                MessageRole.TOOL -> "TOOL"
                MessageRole.SUMMARY -> "SUMMARY"
            }
            val msgTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(msg.createdAt))

            appendLine("### Message ${index + 1} — [$roleName] ($msgTime)")
            appendLine()

            if (!msg.thoughtText.isNullOrBlank()) {
                appendLine("> [!NOTE] Thinking Process")
                msg.thoughtText.lines().forEach { line ->
                    appendLine("> $line")
                }
                appendLine()
            }

            if (msg.toolCalls.isNotEmpty()) {
                appendLine("**Tool Executions:**")
                msg.toolCalls.forEach { tc ->
                    appendLine("- `${tc.name}` (${tc.status}): `${tc.command}`")
                    if (tc.output.isNotBlank()) {
                        appendLine("  ```text")
                        tc.output.lines().take(20).forEach { line ->
                            appendLine("  $line")
                        }
                        if (tc.output.lines().size > 20) {
                            appendLine("  ... [truncated]")
                        }
                        appendLine("  ```")
                    }
                }
                appendLine()
            }

            appendLine(msg.content)
            appendLine()
            appendLine("---")
            appendLine()
        }
    }
}
