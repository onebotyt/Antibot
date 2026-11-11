package com.example.gemini.data.skills

data class SkillMeta(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val risk: String = "safe",
    val source: String = "community",
    val dateAdded: String = "",
    val tags: List<String> = emptyList(),
    val path: String = "skills/$id"
)

data class SkillDetail(
    val meta: SkillMeta,
    val content: String,
    val files: List<String> = emptyList()
)

data class SkillBundle(
    val name: String,
    val displayName: String,
    val description: String,
    val skills: List<String>,
    val skillCount: Int = skills.size
)

enum class SkillInstallTarget(val displayName: String) {
    PROJECT("Active Project (.agents/skills)"),
    GEMINI("Antigravity & Gemini CLI (~/.gemini/config/skills)"),
    CODEX("Codex CLI (~/.codex/skills)"),
    CLAUDE("Claude Code (~/.claude/skills)"),
    OPENCODE("OpenCode (~/.opencode/skills)")
}

data class SkillActionResult(
    val success: Boolean,
    val message: String,
    val installedPath: String? = null,
    val error: String? = null
)
