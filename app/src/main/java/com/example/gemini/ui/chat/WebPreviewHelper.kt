package com.example.gemini.ui.chat

import android.content.Context
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.MessageRole
import java.io.File
import java.net.URI

object WebPreviewHelper {

    private val FILE_URL_REGEX = Regex("""file://(/[^\s`"'\)\],]+(?:\.html|\.htm|\.xhtml|\.svg))""", RegexOption.IGNORE_CASE)
    private val ABSOLUTE_HTML_PATH_REGEX = Regex("""(?:^|[\s`"'\(\[])((?:/data/user/0/[^\s`"'\)\]]+|/storage/[^\s`"'\)\]]+|/sdcard/[^\s`"'\)\]]+|/home/[^\s`"'\)\]]+)[^\s`"'\)\]]+(?:\.html|\.htm))""", RegexOption.IGNORE_CASE)
    private val HTTP_DEV_SERVER_REGEX = Regex("""(?:^|[\s`"'\(\[])(https?://(?:localhost|127\.0\.0\.1|0\.0\.0\.0):\d{2,5}(?:/[^\s`"'\)\]]*)?)""", RegexOption.IGNORE_CASE)
    private val MARKDOWN_LINK_REGEX = Regex("""\[(?:[^\]]*)\]\((file:///[^\s\)]+(?:\.html|\.htm)|https?://(?:localhost|127\.0\.0\.1|0\.0\.0\.0):\d{2,5}[^\s\)]*)\)""", RegexOption.IGNORE_CASE)
    private val WORKSPACE_DIR_REGEX = Regex("""Workspace Directory:\s*`?(/data/user/0/[^\s`\n]+|/[^\s`\n]+)`?""", RegexOption.IGNORE_CASE)
    private val CREATED_FILE_REGEX = Regex("""Created Files:[\s\S]*?-\s*`?([a-zA-Z0-9_\-]+\.html?)`?""", RegexOption.IGNORE_CASE)
    private val HTML_CODE_BLOCK_REGEX = Regex("""```(?:html|htm)\s*\n([\s\S]*?<!DOCTYPE html[\s\S]*?</html>)""", RegexOption.IGNORE_CASE)

    /**
     * Extracts a previewable HTML file URL or localhost server URL from a message's content.
     */
    fun extractWebPreviewTarget(content: String, context: Context? = null): String? {
        if (content.isBlank()) return null

        // 1. Markdown link targets: [Preview](file://... or http://localhost:...)
        MARKDOWN_LINK_REGEX.find(content)?.let { match ->
            return match.groupValues[1]
        }

        // 2. Direct file:// URL ending in .html / .htm
        FILE_URL_REGEX.find(content)?.let { match ->
            return match.value
        }

        // 3. Absolute path ending in .html / .htm
        ABSOLUTE_HTML_PATH_REGEX.find(content)?.let { match ->
            val cleanPath = match.groupValues[1]
            return "file://$cleanPath"
        }

        // 4. HTTP dev server URL (e.g. http://localhost:8080, http://127.0.0.1:3000)
        HTTP_DEV_SERVER_REGEX.find(content)?.let { match ->
            return match.groupValues[1]
        }

        // 5. Synthesized Workspace Directory + index.html
        val workspaceMatch = WORKSPACE_DIR_REGEX.find(content)
        if (workspaceMatch != null) {
            val dirPath = workspaceMatch.groupValues[1].trimEnd('/')
            val createdMatch = CREATED_FILE_REGEX.find(content)
            val fileName = createdMatch?.groupValues?.get(1)?.ifBlank { "index.html" } ?: "index.html"
            val targetFile = File(dirPath, fileName)
            if (targetFile.exists() || content.contains(fileName, ignoreCase = true)) {
                return "file://${targetFile.absolutePath}"
            }
        }

        // 6. Check common AntiBot projects directories if project name is mentioned
        val projectMatch = Regex("""Generated Project:\s*`?([a-zA-Z0-9_\-]+)`?""", RegexOption.IGNORE_CASE).find(content)
        if (projectMatch != null) {
            val projName = projectMatch.groupValues[1]
            val candidatePaths = listOf(
                "/data/user/0/com.antibot/files/home/projects/$projName/index.html",
                "/data/user/0/com.antibot/files/home/projects/$projName/public/index.html"
            )
            for (p in candidatePaths) {
                if (File(p).exists()) return "file://$p"
            }
            return "file:///data/user/0/com.antibot/files/home/projects/$projName/index.html"
        }

        // 7. Embedded complete HTML document in markdown code block
        if (context != null) {
            val htmlBlock = HTML_CODE_BLOCK_REGEX.find(content)
            if (htmlBlock != null) {
                try {
                    val code = htmlBlock.groupValues[1]
                    val cacheDir = File(context.cacheDir, "web_previews").apply { mkdirs() }
                    val tempFile = File(cacheDir, "preview_snippet_${System.currentTimeMillis() % 1000}.html")
                    tempFile.writeText(code)
                    return "file://${tempFile.absolutePath}"
                } catch (_: Exception) {}
            }
        }

        return null
    }

    /**
     * Finds the most recent web preview target from a list of chat messages.
     */
    fun findLatestWebPreviewTarget(messages: List<ChatMessage>, context: Context? = null): String? {
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            if (msg.role == MessageRole.ASSISTANT) {
                val target = extractWebPreviewTarget(msg.content, context)
                if (target != null) return target
            }
        }
        return null
    }

    /**
     * Returns a human-friendly display name for the preview target.
     */
    fun getPreviewDisplayName(url: String): String {
        return try {
            if (url.startsWith("file://", ignoreCase = true)) {
                val path = url.removePrefix("file://")
                val f = File(path)
                val parentName = f.parentFile?.name
                if (!parentName.isNullOrBlank() && parentName != "home" && parentName != "files") {
                    "$parentName/${f.name}"
                } else {
                    f.name
                }
            } else if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                val uri = URI(url)
                val host = uri.host ?: "localhost"
                val port = if (uri.port > 0) ":${uri.port}" else ""
                val path = uri.path?.ifBlank { "/" } ?: "/"
                "$host$port$path"
            } else {
                url.substringAfterLast('/')
            }
        } catch (_: Exception) {
            url.substringAfterLast('/')
        }
    }
}
