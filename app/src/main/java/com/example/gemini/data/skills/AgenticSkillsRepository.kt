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
import java.net.HttpURLConnection
import java.net.URL
import com.example.gemini.AntiGemApplication
import com.example.gemini.data.daemon.GitApiClient
import com.example.gemini.data.daemon.IdeApiClient

object AgenticSkillsRepository {
    private const val TAG = "AgenticSkillsRepo"

    private val localPaths = listOf(
        "/data/user/0/com.termux/files/home/.agentic-awesome-skills",
        "/data/data/com.termux/files/home/.agentic-awesome-skills",
        "/data/user/0/com.antibot/files/.agentic-awesome-skills",
        "/data/data/com.antibot/files/.agentic-awesome-skills",
        "/data/user/0/com.example.gemini/files/.agentic-awesome-skills",
        "/data/data/com.example.gemini/files/.agentic-awesome-skills",
        "/data/user/0/com.termux/files/home/.gemini/config",
        "/data/data/com.termux/files/home/.gemini/config"
    )

    private var cachedIndex: List<SkillMeta>? = null
    private var baseDir: File? = null

    fun getLocalBaseDir(context: Context? = null): File? {
        if (baseDir != null && baseDir!!.exists()) return baseDir
        val ctx = context ?: AntiGemApplication.appContext
        if (ctx != null) {
            val appDir = File(ctx.filesDir, ".agentic-awesome-skills")
            if (appDir.exists() && File(appDir, "skills").exists()) {
                baseDir = appDir
                return appDir
            }
        }
        for (p in localPaths) {
            try {
                val f = File(p)
                if (f.exists() && File(f, "skills").exists()) {
                    baseDir = f
                    return f
                }
            } catch (_: Exception) {}
        }
        return null
    }

    suspend fun getOrLoadIndex(context: Context? = null): List<SkillMeta> = withContext(Dispatchers.IO) {
        cachedIndex?.let { return@withContext it }
        val ctx = context ?: AntiGemApplication.appContext

        val bDir = getLocalBaseDir(ctx)
        val indexFile = if (bDir != null) File(bDir, "skills_index.json") else null

        val jsonStr = try {
            if (indexFile != null && indexFile.exists() && indexFile.canRead()) {
                indexFile.readText(Charsets.UTF_8)
            } else if (ctx != null) {
                ctx.assets.open("skills_index.json.xz").use { raw ->
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

    suspend fun fetchRemoteSkillMd(id: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://raw.githubusercontent.com/sickn33/agentic-awesome-skills/main/skills/${id}/SKILL.md")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("User-Agent", "AntiBot-Android")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch remote skill $id: ${e.message}")
            null
        }
    }

    suspend fun getSkillDetail(id: String, context: Context? = null): SkillDetail? = withContext(Dispatchers.IO) {
        val index = getOrLoadIndex(context)
        val meta = index.find { it.id == id } ?: SkillMeta(id, id, "general", "AI skill playbook $id")

        val bDir = getLocalBaseDir(context)
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
            val installedDir = findInstalledSkillDir(id, context = context)
            if (installedDir != null) {
                val f = File(installedDir, "SKILL.md")
                if (f.exists() && f.canRead()) content = f.readText(Charsets.UTF_8)
            }
        }

        if (content == null) {
            try {
                content = IdeApiClient.readFile("~/.agentic-awesome-skills/skills/$id/SKILL.md")
                    ?: IdeApiClient.readFile("/data/user/0/com.termux/files/home/.agentic-awesome-skills/skills/$id/SKILL.md")
            } catch (_: Exception) {}
        }

        if (content == null) {
            content = fetchRemoteSkillMd(id)
        }

        if (content == null) {
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
        projectPath: String? = null,
        context: Context? = null
    ): SkillActionResult = withContext(Dispatchers.IO) {
        val ctx = context ?: AntiGemApplication.appContext
        val bDir = getLocalBaseDir(ctx)

        var skillMd: String? = null

        // 1. Try local catalog file read (if accessible)
        if (bDir != null) {
            try {
                val srcFile = File(bDir, "skills/$id/SKILL.md")
                if (srcFile.exists() && srcFile.canRead()) {
                    skillMd = srcFile.readText(Charsets.UTF_8)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Direct read of local catalog skill $id skipped: ${e.message}")
            }
        }

        // 2. Try IDE Bridge daemon read (if bridge is running in Termux)
        if (skillMd.isNullOrBlank()) {
            try {
                skillMd = IdeApiClient.readFile("~/.agentic-awesome-skills/skills/$id/SKILL.md")
                    ?: IdeApiClient.readFile("/data/user/0/com.termux/files/home/.agentic-awesome-skills/skills/$id/SKILL.md")
            } catch (e: Exception) {
                Log.d(TAG, "Bridge read skill failed: ${e.message}")
            }
        }

        // 3. Fallback to on-demand direct download from GitHub
        if (skillMd.isNullOrBlank()) {
            skillMd = fetchRemoteSkillMd(id)
        }

        if (skillMd.isNullOrBlank()) {
            return@withContext SkillActionResult(
                false,
                "Could not install skill '$id'. Skill not found in local catalog and remote download failed (check internet connection).",
                error = "Download failed"
            )
        }

        var anySaved = false
        var primaryInstalledPath = ""

        // Target A: App internal private storage (ALWAYS 100% WRITABLE on all Android devices)
        if (ctx != null) {
            try {
                val appBase = ctx.filesDir
                val localDirs = mutableListOf<File>()

                // Primary local skill dir & cache
                localDirs.add(File(appBase, "skills/$id"))
                localDirs.add(File(appBase, "skills_cache/skills/$id"))
                localDirs.add(File(appBase, ".antibot/skills/$id"))

                when (target) {
                    SkillInstallTarget.UNIVERSAL -> {
                        localDirs.add(File(appBase, "gemini/config/skills/$id"))
                        localDirs.add(File(appBase, "claude/skills/$id"))
                        localDirs.add(File(appBase, "codex/skills/$id"))
                        localDirs.add(File(appBase, "opencode/skills/$id"))
                    }
                    SkillInstallTarget.LOCAL -> {}
                    SkillInstallTarget.GEMINI -> localDirs.add(File(appBase, "gemini/config/skills/$id"))
                    SkillInstallTarget.CLAUDE -> localDirs.add(File(appBase, "claude/skills/$id"))
                    SkillInstallTarget.CODEX -> localDirs.add(File(appBase, "codex/skills/$id"))
                    SkillInstallTarget.OPENCODE -> localDirs.add(File(appBase, "opencode/skills/$id"))
                    SkillInstallTarget.PROJECT -> {}
                }

                for (dir in localDirs) {
                    dir.mkdirs()
                    File(dir, "SKILL.md").writeText(skillMd, Charsets.UTF_8)
                }
                anySaved = true
                primaryInstalledPath = File(appBase, "skills/$id/SKILL.md").absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Error saving skill to app internal storage", e)
            }
        }

        // Target B: Project workspace (if specified)
        if (!projectPath.isNullOrBlank() && (target == SkillInstallTarget.PROJECT || target == SkillInstallTarget.UNIVERSAL)) {
            val projSkillDir = File(projectPath, ".agents/skills/$id")
            var projSaved = false
            try {
                projSkillDir.mkdirs()
                File(projSkillDir, "SKILL.md").writeText(skillMd, Charsets.UTF_8)
                projSaved = true
                anySaved = true
                if (primaryInstalledPath.isBlank()) primaryInstalledPath = File(projSkillDir, "SKILL.md").absolutePath
            } catch (e: Exception) {
                Log.w(TAG, "Direct project write failed, falling back to bridge: ${e.message}")
            }
            if (!projSaved) {
                try {
                    val bridgeSaved = IdeApiClient.saveFile("$projectPath/.agents/skills/$id/SKILL.md", skillMd, force = true)
                    if (bridgeSaved) {
                        anySaved = true
                        if (primaryInstalledPath.isBlank()) primaryInstalledPath = "$projectPath/.agents/skills/$id/SKILL.md"
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Bridge project save failed: ${e.message}")
                }
            }
        }

        // Target C: Termux / CLI Locations (Bridge daemon or direct if permitted)
        val termuxTargetPaths = mutableListOf<String>()
        when (target) {
            SkillInstallTarget.UNIVERSAL -> {
                termuxTargetPaths.add("~/.antibot/skills/$id/SKILL.md")
                termuxTargetPaths.add("~/.gemini/config/skills/$id/SKILL.md")
                termuxTargetPaths.add("~/.claude/skills/$id/SKILL.md")
                termuxTargetPaths.add("~/.codex/skills/$id/SKILL.md")
                termuxTargetPaths.add("~/.opencode/skills/$id/SKILL.md")
            }
            SkillInstallTarget.LOCAL -> termuxTargetPaths.add("~/.antibot/skills/$id/SKILL.md")
            SkillInstallTarget.GEMINI -> termuxTargetPaths.add("~/.gemini/config/skills/$id/SKILL.md")
            SkillInstallTarget.CLAUDE -> termuxTargetPaths.add("~/.claude/skills/$id/SKILL.md")
            SkillInstallTarget.CODEX -> termuxTargetPaths.add("~/.codex/skills/$id/SKILL.md")
            SkillInstallTarget.OPENCODE -> termuxTargetPaths.add("~/.opencode/skills/$id/SKILL.md")
            SkillInstallTarget.PROJECT -> {}
        }

        for (tp in termuxTargetPaths) {
            // 1. Try bridge save
            try {
                val ok = IdeApiClient.saveFile(tp, skillMd, force = true)
                if (ok) anySaved = true
            } catch (_: Exception) {}

            // 2. Try direct Termux file write if accessible (e.g. root or shared uid)
            try {
                val expanded = tp.replace("~", "/data/user/0/com.termux/files/home")
                val f = File(expanded)
                f.parentFile?.mkdirs()
                f.writeText(skillMd, Charsets.UTF_8)
                anySaved = true
            } catch (_: Exception) {
                // Ignore DAC permission denied for Termux direct write since bridge and/or internal app storage is used
            }
        }

        // 3. Try bridge skill install API as well (if catalog exists in Termux)
        try {
            val bridgeTargetStr = when (target) {
                SkillInstallTarget.UNIVERSAL -> "universal"
                SkillInstallTarget.LOCAL -> "local"
                SkillInstallTarget.GEMINI -> "gemini"
                SkillInstallTarget.CLAUDE -> "claude"
                SkillInstallTarget.CODEX -> "codex"
                SkillInstallTarget.OPENCODE -> "opencode"
                SkillInstallTarget.PROJECT -> "project"
            }
            IdeApiClient.installBridgeSkill(id, bridgeTargetStr, projectPath)
        } catch (_: Exception) {}

        if (anySaved) {
            return@withContext SkillActionResult(
                success = true,
                message = if (target == SkillInstallTarget.UNIVERSAL)
                    "🌟 Skill '$id' installed universally across Local Model, Antigravity, Claude, Codex, OpenCode & Project!"
                else
                    "Skill '$id' successfully installed to ${target.displayName}!",
                installedPath = primaryInstalledPath
            )
        } else {
            return@withContext SkillActionResult(
                false,
                "Failed to save skill '$id'. No writable location found.",
                error = "Permission denied"
            )
        }
    }

    suspend fun installBundle(
        bundleName: String,
        target: SkillInstallTarget,
        projectPath: String? = null,
        context: Context? = null
    ): SkillActionResult = withContext(Dispatchers.IO) {
        val ctx = context ?: AntiGemApplication.appContext
        val bundles = listBundles(ctx)
        val bundle = bundles.find { it.name.equals(bundleName, ignoreCase = true) }

        val skillNames = bundle?.skills ?: emptyList()
        if (skillNames.isEmpty()) {
            return@withContext SkillActionResult(false, "Bundle '$bundleName' not found or contains no skills.")
        }

        var installedCount = 0
        for (sName in skillNames) {
            val res = installSkill(sName, target, projectPath, ctx)
            if (res.success) installedCount++
        }

        return@withContext SkillActionResult(
            success = installedCount > 0,
            message = "Installed $installedCount/${skillNames.size} skills from bundle '$bundleName' into ${target.displayName}!"
        )
    }

    suspend fun listBundles(context: Context? = null): List<SkillBundle> = withContext(Dispatchers.IO) {
        val ctx = context ?: AntiGemApplication.appContext
        val bDir = getLocalBaseDir(ctx)
        val list = mutableListOf<SkillBundle>()

        // 1. Try local filesystem if accessible
        if (bDir != null) {
            try {
                val pluginsDir = File(bDir, "plugins")
                if (pluginsDir.exists() && pluginsDir.canRead()) {
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
                }
            } catch (_: Exception) {}
        }

        // 2. Try IDE Bridge daemon fallback
        if (list.isEmpty()) {
            try {
                val bridgeBundles = IdeApiClient.getSkillBundles()
                if (bridgeBundles.isNotEmpty()) return@withContext bridgeBundles
            } catch (_: Exception) {}
        }

        // 3. Fallback to predefined curated bundles
        if (list.isEmpty()) {
            return@withContext getCuratedFallbackBundles()
        }

        return@withContext list
    }

    fun getCuratedFallbackBundles(): List<SkillBundle> = listOf(
        SkillBundle(
            name = "essentials",
            displayName = "Essentials",
            description = "Core software development, design, debugging, testing, and architecture skills.",
            skills = listOf(
                "concise-planning", "brainstorming", "systematic-debugging", "test-driven-development",
                "clean-code", "refactoring-patterns", "api-design-principles", "database-design"
            )
        ),
        SkillBundle(
            name = "frontend",
            displayName = "Frontend & UI/UX",
            description = "High-end visual design, responsive layouts, Tailwind, React, and accessibility.",
            skills = listOf(
                "frontend-developer", "frontend-design", "high-end-visual-design", "tailwind-patterns",
                "react-patterns", "nextjs-best-practices", "build-ui", "ui-ux-pro-max", "shadcn", "ui-a11y"
            )
        ),
        SkillBundle(
            name = "backend",
            displayName = "Backend & Systems",
            description = "Scalable backend architecture, APIs, microservices, databases, and microservices.",
            skills = listOf(
                "backend-architect", "api-endpoint-builder", "fastapi-pro", "python-pro",
                "golang-pro", "rust-pro", "nodejs-best-practices", "database-architect", "sql-pro"
            )
        ),
        SkillBundle(
            name = "security",
            displayName = "Security & Reverse Engineering",
            description = "Penetration testing, SAST, vulnerability scanning, and reverse engineering tools.",
            skills = listOf(
                "security-auditor", "vulnerability-scanner", "sast-configuration",
                "web-security-testing", "api-security-testing", "cloud-penetration-testing"
            )
        ),
        SkillBundle(
            name = "devops",
            displayName = "DevOps & Cloud",
            description = "Kubernetes, Terraform, CI/CD pipelines, Docker, and observability.",
            skills = listOf(
                "kubernetes-architect", "terraform-specialist", "github-actions-templates",
                "observability-engineer", "devops-troubleshooter", "incident-responder"
            )
        ),
        SkillBundle(
            name = "mobile",
            displayName = "Mobile Development",
            description = "Android, iOS, Flutter, and React Native mobile application development.",
            skills = listOf(
                "mobile-developer", "flutter-expert", "ios-developer", "react-native-architecture",
                "ios-screens", "hig-components-layout"
            )
        )
    )

    suspend fun listInstalled(targetDir: File): List<String> = withContext(Dispatchers.IO) {
        if (!targetDir.exists() || !targetDir.isDirectory) return@withContext emptyList()
        return@withContext targetDir.listFiles()?.filter {
            it.isDirectory && File(it, "SKILL.md").exists()
        }?.map { it.name }?.sorted() ?: emptyList()
    }

    fun getAllSkillSearchDirs(projectPath: String? = null, context: Context? = null): List<File> {
        val list = mutableListOf<File>()
        val ctx = context ?: AntiGemApplication.appContext
        if (ctx != null) {
            val appBase = ctx.filesDir
            list.add(File(appBase, "skills"))
            list.add(File(appBase, ".antibot/skills"))
            list.add(File(appBase, "skills_cache/skills"))
            list.add(File(appBase, "gemini/config/skills"))
            list.add(File(appBase, "claude/skills"))
            list.add(File(appBase, "codex/skills"))
            list.add(File(appBase, "opencode/skills"))
        }
        if (!projectPath.isNullOrBlank()) {
            list.add(File(projectPath, ".agents/skills"))
        }
        list.add(File("/data/user/0/com.termux/files/home/.antibot/skills"))
        list.add(File("/data/user/0/com.termux/files/home/.gemini/config/skills"))
        list.add(File("/data/user/0/com.termux/files/home/.claude/skills"))
        list.add(File("/data/user/0/com.termux/files/home/.codex/skills"))
        list.add(File("/data/user/0/com.termux/files/home/.opencode/skills"))
        return list
    }

    suspend fun listAllInstalled(projectPath: String? = null, context: Context? = null): List<String> = withContext(Dispatchers.IO) {
        val all = mutableSetOf<String>()
        for (d in getAllSkillSearchDirs(projectPath, context)) {
            if (d.exists() && d.isDirectory) {
                d.listFiles()?.filter { it.isDirectory && File(it, "SKILL.md").exists() }?.forEach {
                    all.add(it.name)
                }
            }
        }
        return@withContext all.sorted()
    }

    suspend fun isSkillInstalled(skillId: String, projectPath: String? = null, context: Context? = null): Boolean = withContext(Dispatchers.IO) {
        for (d in getAllSkillSearchDirs(projectPath, context)) {
            if (File(d, "$skillId/SKILL.md").exists()) return@withContext true
        }
        return@withContext false
    }

    suspend fun getInstalledSkillLocations(skillId: String, projectPath: String? = null, context: Context? = null): List<String> = withContext(Dispatchers.IO) {
        val locs = mutableListOf<String>()
        val ctx = context ?: AntiGemApplication.appContext
        val appBase = ctx?.filesDir

        if (!projectPath.isNullOrBlank() && File(projectPath, ".agents/skills/$skillId/SKILL.md").exists()) {
            locs.add("Project")
        }
        if ((appBase != null && (File(appBase, "skills/$skillId/SKILL.md").exists() || File(appBase, ".antibot/skills/$skillId/SKILL.md").exists())) ||
            File("/data/user/0/com.termux/files/home/.antibot/skills/$skillId/SKILL.md").exists()) {
            locs.add("Local Model")
        }
        if ((appBase != null && File(appBase, "gemini/config/skills/$skillId/SKILL.md").exists()) ||
            File("/data/user/0/com.termux/files/home/.gemini/config/skills/$skillId/SKILL.md").exists()) {
            locs.add("Antigravity")
        }
        if ((appBase != null && File(appBase, "claude/skills/$skillId/SKILL.md").exists()) ||
            File("/data/user/0/com.termux/files/home/.claude/skills/$skillId/SKILL.md").exists()) {
            locs.add("Claude Code")
        }
        if ((appBase != null && File(appBase, "codex/skills/$skillId/SKILL.md").exists()) ||
            File("/data/user/0/com.termux/files/home/.codex/skills/$skillId/SKILL.md").exists()) {
            locs.add("Codex")
        }
        if ((appBase != null && File(appBase, "opencode/skills/$skillId/SKILL.md").exists()) ||
            File("/data/user/0/com.termux/files/home/.opencode/skills/$skillId/SKILL.md").exists()) {
            locs.add("OpenCode")
        }
        return@withContext locs
    }

    suspend fun findInstalledSkillDir(skillId: String, projectPath: String? = null, context: Context? = null): File? = withContext(Dispatchers.IO) {
        for (d in getAllSkillSearchDirs(projectPath, context)) {
            val sDir = File(d, skillId)
            if (File(sDir, "SKILL.md").exists()) return@withContext sDir
        }
        return@withContext null
    }

    fun getLocalHeadSha(baseDir: File?): String {
        if (baseDir == null) return ""
        try {
            val headFile = File(baseDir, ".git/HEAD")
            if (!headFile.exists()) return ""
            val headContent = headFile.readText(Charsets.UTF_8).trim()
            if (headContent.startsWith("ref:")) {
                val refPath = headContent.removePrefix("ref:").trim()
                val refFile = File(baseDir, ".git/$refPath")
                if (refFile.exists()) {
                    return refFile.readText(Charsets.UTF_8).trim()
                }
                val packedRefs = File(baseDir, ".git/packed-refs")
                if (packedRefs.exists()) {
                    val line = packedRefs.readLines().find { it.endsWith(refPath) }
                    if (line != null) {
                        return line.split(" ").firstOrNull()?.trim().orEmpty()
                    }
                }
            } else {
                return headContent
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read local HEAD SHA", e)
        }
        return ""
    }

    suspend fun checkForUpdates(): SkillUpdateStatus = withContext(Dispatchers.IO) {
        val bDir = getLocalBaseDir()
        val localSha = getLocalHeadSha(bDir)

        try {
            val url = URL("https://api.github.com/repos/sickn33/agentic-awesome-skills/commits/main")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "AntiBot-Android")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            if (conn.responseCode in 200..299) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(jsonStr)
                val remoteSha = obj.optString("sha", "")
                val commitObj = obj.optJSONObject("commit")
                val msg = commitObj?.optString("message", "").orEmpty().lines().firstOrNull().orEmpty()
                val date = commitObj?.optJSONObject("author")?.optString("date", "").orEmpty()

                val hasUpdate = remoteSha.isNotBlank() && localSha.isNotBlank() && !remoteSha.startsWith(localSha) && !localSha.startsWith(remoteSha)

                return@withContext SkillUpdateStatus(
                    hasUpdate = hasUpdate,
                    localSha = localSha.take(7),
                    remoteSha = remoteSha.take(7),
                    commitMessage = msg,
                    commitDate = date
                )
            } else {
                return@withContext SkillUpdateStatus(
                    hasUpdate = false,
                    localSha = localSha.take(7),
                    remoteSha = "",
                    error = "GitHub API response: ${conn.responseCode}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking skills update", e)
            return@withContext SkillUpdateStatus(
                hasUpdate = false,
                localSha = localSha.take(7),
                remoteSha = "",
                error = e.localizedMessage ?: "Network error"
            )
        }
    }

    suspend fun updateSkills(
        projectPath: String? = null,
        context: Context? = null,
        onProgress: ((String) -> Unit)? = null
    ): SkillUpdateResult = withContext(Dispatchers.IO) {
        val bDir = getLocalBaseDir()
        if (bDir == null) {
            return@withContext SkillUpdateResult(
                success = false,
                message = "Local skills directory not found on device.",
                error = "Repository missing"
            )
        }

        val oldSha = getLocalHeadSha(bDir)
        onProgress?.invoke("Pulling latest commits from GitHub repository...")

        var pullSuccess = false
        var pullOutput = ""
        var pullError = ""

        // 1. Try GitApiClient (via bridge daemon)
        try {
            val res = GitApiClient.pullDetailed(bDir.absolutePath)
            if (res.success) {
                pullSuccess = true
                pullOutput = res.output
            } else {
                pullError = res.error
            }
        } catch (e: Exception) {
            pullError = e.message.orEmpty()
        }

        // 2. Direct Process execution fallback if bridge daemon was unavailable
        if (!pullSuccess) {
            try {
                val gitBin = listOf(
                    "/data/data/com.termux/files/usr/bin/git",
                    "/data/user/0/com.termux/files/usr/bin/git",
                    "git"
                ).find { File(it).canExecute() } ?: "git"

                val pb = ProcessBuilder(gitBin, "pull", "--rebase", "origin", "main")
                    .directory(bDir)
                    .redirectErrorStream(true)
                pb.environment()["PATH"] = "/data/data/com.termux/files/usr/bin:/data/user/0/com.termux/files/usr/bin:" + (System.getenv("PATH") ?: "")
                val proc = pb.start()
                pullOutput = proc.inputStream.bufferedReader().use { it.readText() }
                val exitCode = proc.waitFor()
                pullSuccess = (exitCode == 0)
                if (!pullSuccess) {
                    pullError = pullOutput.ifBlank { "Git process exited with code $exitCode" }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Process git pull failed", e)
                if (pullError.isBlank()) pullError = e.localizedMessage ?: "Process execution failed"
            }
        }

        val newSha = getLocalHeadSha(bDir)

        // Clear catalog memory cache so new skills/updates are loaded
        cachedIndex = null
        getOrLoadIndex(context)

        // 3. Re-sync installed skills in active project & global configs
        var refreshedInstalledCount = 0
        try {
            val targetDirs = getAllSkillSearchDirs(projectPath, context)
            val srcSkills = File(bDir, "skills")
            if (srcSkills.exists() && srcSkills.canRead()) {
                for (tDir in targetDirs) {
                    if (tDir.exists() && tDir.isDirectory && tDir.canWrite()) {
                        tDir.listFiles()?.filter { it.isDirectory }?.forEach { installedDir ->
                            val srcDir = File(srcSkills, installedDir.name)
                            if (srcDir.exists() && srcDir.isDirectory) {
                                try {
                                    srcDir.copyRecursively(installedDir, overwrite = true)
                                    refreshedInstalledCount++
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error re-syncing installed skills after update", e)
        }

        if (pullSuccess || (newSha.isNotBlank() && oldSha.isNotBlank() && newSha != oldSha)) {
            val msg = if (refreshedInstalledCount > 0) {
                "Skills updated to ${newSha.take(7)}! Refreshed $refreshedInstalledCount installed skills."
            } else {
                "Skills updated to latest version (${newSha.take(7)})!"
            }
            return@withContext SkillUpdateResult(
                success = true,
                message = msg,
                oldSha = oldSha.take(7),
                newSha = newSha.take(7),
                updatedSkillsCount = refreshedInstalledCount
            )
        } else if (pullOutput.contains("Already up to date", ignoreCase = true) || (newSha == oldSha && newSha.isNotBlank())) {
            return@withContext SkillUpdateResult(
                success = true,
                message = "Skills library is already up to date (${newSha.take(7)})! Refreshed $refreshedInstalledCount installed skills.",
                oldSha = oldSha.take(7),
                newSha = newSha.take(7),
                updatedSkillsCount = refreshedInstalledCount
            )
        } else {
            return@withContext SkillUpdateResult(
                success = false,
                message = "Failed to pull skills update: $pullError",
                oldSha = oldSha.take(7),
                newSha = newSha.take(7),
                error = pullError
            )
        }
    }

    suspend fun updateAllInstalledSkills(projectPath: String? = null, context: Context? = null): SkillActionResult = withContext(Dispatchers.IO) {
        val ctx = context ?: AntiGemApplication.appContext
        val bDir = getLocalBaseDir(ctx) ?: return@withContext SkillActionResult(false, "Skills catalog missing")
        val srcSkills = File(bDir, "skills")
        if (!srcSkills.exists() || !srcSkills.canRead()) return@withContext SkillActionResult(false, "Skills source folder missing or unreadable")

        val targetDirs = getAllSkillSearchDirs(projectPath, ctx)
        var updatedCount = 0
        for (tDir in targetDirs) {
            if (tDir.exists() && tDir.isDirectory && tDir.canWrite()) {
                tDir.listFiles()?.filter { it.isDirectory }?.forEach { installedDir ->
                    val srcDir = File(srcSkills, installedDir.name)
                    if (srcDir.exists() && srcDir.isDirectory) {
                        try {
                            srcDir.copyRecursively(installedDir, overwrite = true)
                            updatedCount++
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        return@withContext SkillActionResult(
            success = true,
            message = "Successfully synchronized $updatedCount installed skills with the latest library!"
        )
    }
}

