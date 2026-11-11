package com.example.gemini.domain.project

sealed class ChatSkillIntent {
    data class SearchSkills(val query: String) : ChatSkillIntent()
    data class InstallSkill(val skillId: String) : ChatSkillIntent()
    data class InstallBundle(val bundleName: String) : ChatSkillIntent()
    object ListInstalledSkills : ChatSkillIntent()
    object ListBundles : ChatSkillIntent()
    object UpdateSkills : ChatSkillIntent()
    object CheckSkillsUpdate : ChatSkillIntent()
}

object ChatSkillParser {
    fun parseIntent(prompt: String): ChatSkillIntent? {
        val trimmed = prompt.trim()
        val lower = trimmed.lowercase()

        // 0. Update / Upgrade skills
        if (lower in listOf("update skills", "upgrade skills", "sync skills", "update skill repo", "pull skills", "update all skills")) {
            return ChatSkillIntent.UpdateSkills
        }

        // 0.5. Check skills update
        if (lower in listOf("check skills update", "check skill updates", "check for skill updates", "skills update check")) {
            return ChatSkillIntent.CheckSkillsUpdate
        }

        // 1. List installed skills
        if (lower in listOf("list skills", "list installed skills", "show installed skills", "show my skills", "what skills are installed", "skills list")) {
            return ChatSkillIntent.ListInstalledSkills
        }

        // 2. List bundles
        if (lower in listOf("list bundles", "show bundles", "available bundles", "skills bundles")) {
            return ChatSkillIntent.ListBundles
        }

        // 3. Install Bundle
        val bundlePatterns = listOf(
            Regex("^(?:install|add|enable)\\s+bundle\\s+([a-zA-Z0-9_-]+)", RegexOption.IGNORE_CASE),
            Regex("^install\\s+([a-zA-Z0-9_-]+)\\s+bundle", RegexOption.IGNORE_CASE)
        )
        for (p in bundlePatterns) {
            val m = p.find(trimmed)
            if (m != null) {
                val bName = m.groupValues[1].trim()
                if (bName.isNotBlank()) return ChatSkillIntent.InstallBundle(bName)
            }
        }

        // 4. Install Skill
        val installPatterns = listOf(
            Regex("^(?:install|add|enable|use)\\s+skill\\s+([a-zA-Z0-9_-]+)", RegexOption.IGNORE_CASE),
            Regex("^install\\s+([a-zA-Z0-9_-]+)\\s+skill", RegexOption.IGNORE_CASE)
        )
        for (p in installPatterns) {
            val m = p.find(trimmed)
            if (m != null) {
                val sId = m.groupValues[1].trim()
                if (sId.isNotBlank() && sId != "for" && sId != "bundle") {
                    return ChatSkillIntent.InstallSkill(sId)
                }
            }
        }

        // 5. Search / Find Skills
        val searchPatterns = listOf(
            Regex("^(?:search|find|lookup|show)\\s+skills?\\s+(?:for\\s+|about\\s+)?(.+)", RegexOption.IGNORE_CASE),
            Regex("^skills?\\s+(?:for|about)\\s+(.+)", RegexOption.IGNORE_CASE)
        )
        for (p in searchPatterns) {
            val m = p.find(trimmed)
            if (m != null) {
                val q = m.groupValues[1].trim()
                if (q.isNotBlank()) return ChatSkillIntent.SearchSkills(q)
            }
        }

        return null
    }
}
