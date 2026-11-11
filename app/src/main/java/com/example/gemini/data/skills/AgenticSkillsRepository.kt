package com.example.gemini.data.skills

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object AgenticSkillsRepository {
    private const val TAG = "AgenticSkillsRepo"

    private val localPaths = listOf(
        "/data/user/0/com.termux/files/home/.agentic-awesome-skills",
        "/data/data/com.termux/files/home/.agentic-awesome-skills"
    )

    private var cachedIndex: List<SkillMeta>? = null
    private var baseDir: File? = null

    fun getLocalBaseDir(): File? {
        if (baseDir != null && baseDir!!.exists()) return baseDir
        for (p in localPaths) {
            val f = File(p)
            if (f.exists() && File(f, "skills").exists()) {
                baseDir = f
                return f
            }
        }
        return null
    }

    suspend fun getOrLoadIndex(context: Context? = null): List<SkillMeta> = withContext(Dispatchers.IO) {
        cachedIndex?.let { return@withContext it }

        val bDir = getLocalBaseDir()
        val indexFile = if (bDir != null) File(bDir, "skills_index.json") else null

        val jsonStr = try {
            if (indexFile != null && indexFile.exists()) {
                indexFile.readText(Charsets.UTF_8)
            } else if (context != null) {
                context.assets.open("skills_index.json.xz").use { raw ->
                    XZInputStream(raw).use { xzIn ->
                        BufferedReader(InputStreamReader(xzIn, Charsets.UTF_8)).readText()
                    }
                }
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error loading skills index", e)
            null
        }

        if (jsonStr.isNullOrBlank()) {
            return@withContext emptyList()
        }

        val list = mutableListOf<SkillMeta>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id", "")
                if (id.isBlank()) continue

                val tagsList = mutableListOf<String>()
                val tagsArr = obj.optJSONArray("tags")
                if (tagsArr != null) {
                    for (t in 0 until tagsArr.length()) {
                        tagsList.add(tagsArr.getString(t))
                    }
                }

                list.add(
                    SkillMeta(
                        id = id,
                        name = obj.optString("name", id),
                        category = obj.optString("category", "general"),
                        description = obj.optString("description", ""),
                        risk = obj.optString("risk", "safe"),
                        source = obj.optString("source", "community"),
                        dateAdded = obj.optString("date_added", ""),
                        tags = tagsList,
                        path = obj.optString("path", "skills/$id")
                    )
                )
            }
            cachedIndex = list
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing skills index JSON", e)
        }

        return@withContext list
    }

    suspend fun searchSkills(
        query: String,
        category: String? = null,
        risk: String? = null,
        limit: Int = 30,
        context: Context? = null
    ): List<SkillMeta> = withContext(Dispatchers.IO) {
        val index = getOrLoadIndex(context)
        if (index.isEmpty()) return@withContext emptyList()

        val qTokens = query.lowercase().trim().split("\\s+".toRegex()).filter { it.isNotBlank() }

        return@withContext index.asSequence().filter { meta ->
            if (!category.isNullOrBlank() && category != "all" && !meta.category.equals(category, ignoreCase = true)) {
                return@filter false
            }
            if (!risk.isNullOrBlank() && risk != "all" && !meta.risk.equals(risk, ignoreCase = true)) {
                return@filter false
            }
            if (qTokens.isEmpty()) return@filter true

            val textToMatch = "${meta.id} ${meta.name} ${meta.description} ${meta.category} ${meta.tags.joinToString(" ")}".lowercase()
            qTokens.all { textToMatch.contains(it) }
        }.take(limit).toList()
    }

    suspend fun getSkillDetail(id: String, context: Context? = null): SkillDetail? = withContext(Dispatchers.IO) {
        val index = getOrLoadIndex(context)
        val meta = index.find { it.id == id } ?: SkillMeta(id, id, "general", "AI skill playbook $id")

        val bDir = getLocalBaseDir()
        var content: String? = null
        val files = mutableListOf<String>()

        if (bDir != null) {
            val skillDir = File(bDir, "skills/$id")
            val skillFile = File(skillDir, "SKILL.md")
            if (skillFile.exists()) {
                content = skillFile.readText(Charsets.UTF_8)
                skillDir.walkTopDown().filter { it.isFile && it.name != "SKILL.md" }.forEach {
                    files.add(it.relativeTo(skillDir).path)
                }
            }
        }

        if (content == null) {
            // Online fallback if needed
            content = """
            # Skill: ${meta.name}
            
            ${meta.description}
            
            *(Full skill file available at ~/.agentic-awesome-skills/skills/${id}/SKILL.md)*
            """.trimIndent()
        }

        return@withContext SkillDetail(meta, content, files)
    }

    suspend fun installSkill(
        id: String,
        target: SkillInstallTarget,
        projectPath: String? = null
    ): SkillActionResult = withContext(Dispatchers.IO) {
        val bDir = getLocalBaseDir()
        if (bDir == null) {
            return@withContext SkillActionResult(false, "Local agentic skills directory not found on device.", error = "Directory missing")
        }

        val srcSkillDir = File(bDir, "skills/$id")
        if (!srcSkillDir.exists() || !srcSkillDir.isDirectory) {
            return@withContext SkillActionResult(false, "Skill '$id' not found in catalog.", error = "Skill not found")
        }

        val targetDir = when (target) {
            SkillInstallTarget.PROJECT -> {
                if (projectPath.isNullOrBlank()) {
                    return@withContext SkillActionResult(false, "No active project specified for project install.", error = "Missing project")
                }
                File(projectPath, ".agents/skills/$id")
            }
            SkillInstallTarget.GEMINI -> File("/data/user/0/com.termux/files/home/.gemini/config/skills/$id")
            SkillInstallTarget.CODEX -> File("/data/user/0/com.termux/files/home/.codex/skills/$id")
            SkillInstallTarget.CLAUDE -> File("/data/user/0/com.termux/files/home/.claude/skills/$id")
            SkillInstallTarget.OPENCODE -> File("/data/user/0/com.termux/files/home/.opencode/skills/$id")
        }

        try {
            targetDir.parentFile?.mkdirs()
            srcSkillDir.copyRecursively(targetDir, overwrite = true)
            return@withContext SkillActionResult(
                success = true,
                message = "Skill '$id' successfully installed to ${target.displayName}!",
                installedPath = targetDir.absolutePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy skill $id", e)
            return@withContext SkillActionResult(false, "Failed to install skill '$id': ${e.message}", error = e.localizedMessage)
        }
    }

    suspend fun installBundle(
        bundleName: String,
        target: SkillInstallTarget,
        projectPath: String? = null
    ): SkillActionResult = withContext(Dispatchers.IO) {
        val bDir = getLocalBaseDir() ?: return@withContext SkillActionResult(false, "Local catalog missing")
        val pluginsDir = File(bDir, "plugins")

        val bundleDir = File(pluginsDir, "agentic-bundle-$bundleName").takeIf { it.exists() }
            ?: File(pluginsDir, bundleName).takeIf { it.exists() }
            ?: pluginsDir.listFiles()?.find { it.name.contains(bundleName) }

        if (bundleDir == null || !bundleDir.exists()) {
            return@withContext SkillActionResult(false, "Bundle '$bundleName' not found.")
        }

        val skillsSub = File(bundleDir, "skills")
        if (!skillsSub.exists() || !skillsSub.isDirectory) {
            return@withContext SkillActionResult(false, "Bundle has no skills folder.")
        }

        val skills = skillsSub.listFiles()?.filter { it.isDirectory } ?: emptyList()
        var installedCount = 0

        for (s in skills) {
            val res = installSkill(s.name, target, projectPath)
            if (res.success) installedCount++
        }

        return@withContext SkillActionResult(
            success = installedCount > 0,
            message = "Installed $installedCount skills from bundle '$bundleName' into ${target.displayName}!"
        )
    }

    suspend fun listBundles(): List<SkillBundle> = withContext(Dispatchers.IO) {
        val bDir = getLocalBaseDir() ?: return@withContext emptyList()
        val pluginsDir = File(bDir, "plugins")
        if (!pluginsDir.exists()) return@withContext emptyList()

        val list = mutableListOf<SkillBundle>()
        val dirs = pluginsDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("agentic-bundle-") } ?: emptyList()

        for (d in dirs.sortedBy { it.name }) {
            val rawName = d.name.removePrefix("agentic-bundle-")
            val displayName = rawName.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            val skillsSub = File(d, "skills")
            val skills = skillsSub.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()

            var desc = "Curated bundle of ${skills.size} expert skills for $displayName."
            val pluginJson = File(d, "plugin.json")
            if (pluginJson.exists()) {
                try {
                    val obj = JSONObject(pluginJson.readText(Charsets.UTF_8))
                    val dStr = obj.optString("description", "")
                    if (dStr.isNotBlank()) desc = dStr
                } catch (_: Exception) {}
            }

            list.add(SkillBundle(rawName, displayName, desc, skills))
        }

        return@withContext list
    }

    suspend fun listInstalled(targetDir: File): List<String> = withContext(Dispatchers.IO) {
        if (!targetDir.exists() || !targetDir.isDirectory) return@withContext emptyList()
        return@withContext targetDir.listFiles()?.filter {
            it.isDirectory && File(it, "SKILL.md").exists()
        }?.map { it.name }?.sorted() ?: emptyList()
    }
}
