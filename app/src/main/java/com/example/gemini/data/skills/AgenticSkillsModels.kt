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

enum class SkillInstallTarget(val displayName: String, val subtitle: String = "") {
    UNIVERSAL("🌟 Universal (All Models & CLIs)", "Installs once across Local Model, Antigravity, Claude, Codex, OpenCode & Project"),
    LOCAL("🧠 Local Model & Neural Engine", "~/.antibot/skills"),
    PROJECT("Active Project (.agents/skills)", "Workspace-scoped (.agents/skills)"),
    GEMINI("Antigravity & Gemini CLI", "~/.gemini/config/skills"),
    CODEX("Codex CLI", "~/.codex/skills"),
    CLAUDE("Claude Code", "~/.claude/skills"),
    OPENCODE("OpenCode", "~/.opencode/skills")
}

data class SkillActionResult(
    val success: Boolean,
    val message: String,
    val installedPath: String? = null,
    val error: String? = null
)

data class SkillUpdateStatus(
    val hasUpdate: Boolean,
    val localSha: String,
    val remoteSha: String,
    val commitMessage: String = "",
    val commitDate: String = "",
    val error: String? = null
)

data class SkillUpdateResult(
    val success: Boolean,
    val message: String,
    val oldSha: String = "",
    val newSha: String = "",
    val updatedSkillsCount: Int = 0,
    val error: String? = null
)
