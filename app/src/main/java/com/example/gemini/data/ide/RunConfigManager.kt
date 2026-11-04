package com.example.gemini.data.ide

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

@Serializable
data class RunConfiguration(
    val name: String,
    val type: String = "terminal", // "terminal" or "webview"
    val command: String? = null,
    val file: String? = null,
    val cwd: String? = null,
    val env: Map<String, String>? = null,
    val args: List<String>? = null,
    val isDefault: Boolean = false
)

@Serializable
data class LaunchConfigFile(
    val version: String = "1.0",
    val configurations: List<RunConfiguration> = emptyList()
)

object RunConfigManager {
    private const val TAG = "RunConfigManager"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun getLaunchFile(projectPath: String): File {
        val ideDir = File(projectPath, ".ide")
        if (!ideDir.exists()) {
            ideDir.mkdirs()
        }
        return File(ideDir, "launch.json")
    }

    fun loadConfigurations(projectPath: String): List<RunConfiguration> {
        val file = File(projectPath, ".ide/launch.json")
        val existing = if (file.exists() && file.isFile) {
            try {
                val content = file.readText()
                val parsed = json.decodeFromString<LaunchConfigFile>(content)
                parsed.configurations
            } catch (e: Exception) {
                Log.e(TAG, "Failed parsing .ide/launch.json in $projectPath: ${e.message}", e)
                emptyList()
            }
        } else {
            emptyList()
        }

        if (existing.isNotEmpty()) {
            return existing
        }

        // Auto-detect project server/dev configuration if none saved yet
        val detected = detectProjectServer(projectPath)
        return if (detected != null) {
            listOf(detected)
        } else {
            emptyList()
        }
    }

    fun saveConfigurations(projectPath: String, configs: List<RunConfiguration>): Boolean {
        return try {
            val file = getLaunchFile(projectPath)
            val configObj = LaunchConfigFile(version = "1.0", configurations = configs)
            val jsonStr = json.encodeToString(configObj)
            file.writeText(jsonStr)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving .ide/launch.json in $projectPath: ${e.message}", e)
            false
        }
    }

    fun addConfiguration(projectPath: String, config: RunConfiguration): Boolean {
        val current = loadConfigurations(projectPath).toMutableList()
        current.removeAll { it.name.equals(config.name, ignoreCase = true) }
        current.add(config)
        return saveConfigurations(projectPath, current)
    }

    fun getOrCreateLaunchJson(projectPath: String, activeFilePath: String? = null): File {
        val file = getLaunchFile(projectPath)
        if (!file.exists()) {
            val sampleConfigs = mutableListOf<RunConfiguration>()
            if (activeFilePath != null) {
                val defaultRunner = getDefaultRunnerForFile(activeFilePath, projectPath)
                if (defaultRunner != null) {
                    sampleConfigs.add(defaultRunner)
                }
            }
            if (sampleConfigs.isEmpty()) {
                sampleConfigs.add(
                    RunConfiguration(
                        name = "Run Active File",
                        type = "terminal",
                        command = "python3 \"\${file}\"",
                        cwd = "\${fileDirname}"
                    )
                )
            }
            saveConfigurations(projectPath, sampleConfigs)
        }
        return file
    }

    fun resolveVariables(template: String, activeFilePath: String?, projectPath: String?): String {
        var resolved = template
        val activeFile = activeFilePath?.let { File(it) }

        val filePath = activeFile?.absolutePath ?: ""
        val fileBasename = activeFile?.name ?: ""
        val fileBasenameNoExt = activeFile?.nameWithoutExtension ?: ""
        val fileDirname = activeFile?.parentFile?.absolutePath ?: projectPath ?: ""
        val fileExt = activeFile?.extension?.let { if (it.isNotBlank()) ".$it" else "" } ?: ""
        val workspaceFolder = projectPath ?: fileDirname

        resolved = resolved.replace("\${file}", filePath)
        resolved = resolved.replace("\${fileBasename}", fileBasename)
        resolved = resolved.replace("\${fileBasenameNoExtension}", fileBasenameNoExt)
        resolved = resolved.replace("\${fileDirname}", fileDirname)
        resolved = resolved.replace("\${fileExt}", fileExt)
        resolved = resolved.replace("\${workspaceFolder}", workspaceFolder)

        return resolved
    }

    fun resolveWorkingDir(cwdTemplate: String?, activeFilePath: String?, projectPath: String?): String? {
        if (cwdTemplate.isNullOrBlank()) {
            return activeFilePath?.let { File(it).parentFile?.absolutePath } ?: projectPath
        }
        return resolveVariables(cwdTemplate, activeFilePath, projectPath)
    }

    private val SUPPORTED_EXTENSIONS = setOf(
        "c", "cpp", "cc", "cxx", "c++",
        "py", "pyw",
        "js", "mjs", "cjs",
        "ts", "mts", "cts",
        "php",
        "sh", "bash", "zsh",
        "rs",
        "go",
        "java",
        "kts",
        "rb",
        "lua",
        "r"
    )

    fun hasDefaultRunnerForFile(filePath: String?): Boolean {
        if (filePath.isNullOrBlank()) return false
        val ext = File(filePath).extension.lowercase()
        return ext in SUPPORTED_EXTENSIONS
    }

    fun getDefaultRunnerForFile(filePath: String, projectPath: String?): RunConfiguration? {
        val file = File(filePath)
        val ext = file.extension.lowercase()

        return when (ext) {
            "c" -> RunConfiguration(
                name = "Compile & Run C (${file.name})",
                type = "terminal",
                command = "mkdir -p \"\${TMPDIR:-\$PREFIX/tmp}\" && gcc -Wall -O2 \"\${file}\" -o \"\${TMPDIR:-\$PREFIX/tmp}/\${fileBasenameNoExtension}\" && \"\${TMPDIR:-\$PREFIX/tmp}/\${fileBasenameNoExtension}\"",
                cwd = "\${fileDirname}"
            )
            "cpp", "cc", "cxx", "c++" -> RunConfiguration(
                name = "Compile & Run C++ (${file.name})",
                type = "terminal",
                command = "mkdir -p \"\${TMPDIR:-\$PREFIX/tmp}\" && g++ -Wall -O2 \"\${file}\" -o \"\${TMPDIR:-\$PREFIX/tmp}/\${fileBasenameNoExtension}\" && \"\${TMPDIR:-\$PREFIX/tmp}/\${fileBasenameNoExtension}\"",
                cwd = "\${fileDirname}"
            )
            "py", "pyw" -> RunConfiguration(
                name = "Run Python (${file.name})",
                type = "terminal",
                command = "python3 \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            "js", "mjs", "cjs" -> RunConfiguration(
                name = "Run Node.js (${file.name})",
                type = "terminal",
                command = "node \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            "ts", "mts", "cts" -> RunConfiguration(
                name = "Run TypeScript (${file.name})",
                type = "terminal",
                command = "npx tsx \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            "php" -> RunConfiguration(
                name = "Run PHP CLI (${file.name})",
                type = "terminal",
                command = "php \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            "sh", "bash", "zsh" -> RunConfiguration(
                name = "Execute Shell Script (${file.name})",
                type = "terminal",
                command = "bash \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            "rs" -> RunConfiguration(
                name = "Compile & Run Rust (${file.name})",
                type = "terminal",
                command = "mkdir -p \"\${TMPDIR:-\$PREFIX/tmp}\" && rustc \"\${file}\" -o \"\${TMPDIR:-\$PREFIX/tmp}/\${fileBasenameNoExtension}\" && \"\${TMPDIR:-\$PREFIX/tmp}/\${fileBasenameNoExtension}\"",
                cwd = "\${fileDirname}"
            )
            "go" -> RunConfiguration(
                name = "Run Go (${file.name})",
                type = "terminal",
                command = "go run \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            "java" -> RunConfiguration(
                name = "Compile & Run Java (${file.name})",
                type = "terminal",
                command = "mkdir -p \"\${TMPDIR:-\$PREFIX/tmp}\" && javac -d \"\${TMPDIR:-\$PREFIX/tmp}\" \"\${file}\" && java -cp \"\${TMPDIR:-\$PREFIX/tmp}\" \"\${fileBasenameNoExtension}\"",
                cwd = "\${fileDirname}"
            )
            "kts" -> RunConfiguration(
                name = "Run Kotlin Script (${file.name})",
                type = "terminal",
                command = "kotlinc -script \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            "rb" -> RunConfiguration(
                name = "Run Ruby (${file.name})",
                type = "terminal",
                command = "ruby \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            "lua" -> RunConfiguration(
                name = "Run Lua (${file.name})",
                type = "terminal",
                command = "lua \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            "r" -> RunConfiguration(
                name = "Run R Script (${file.name})",
                type = "terminal",
                command = "Rscript \"\${file}\"",
                cwd = "\${fileDirname}"
            )
            else -> null
        }
    }

    fun getEffectiveProjectRunner(projectPath: String): RunConfiguration? {
        val configs = loadConfigurations(projectPath)
        return configs.firstOrNull { it.isDefault }
            ?: configs.firstOrNull { it.name.contains("server", ignoreCase = true) || it.name.contains("dev", ignoreCase = true) }
            ?: configs.firstOrNull()
            ?: detectProjectServer(projectPath)
    }

    /**
     * Auto-detect project server/database runner from project files on-device with 0 tokens.
     */
    fun detectProjectServer(projectPath: String): RunConfiguration? {
        val projectDir = File(projectPath)
        if (!projectDir.exists() || !projectDir.isDirectory) return null

        val hasNpmLock = File(projectDir, "package-lock.json").exists()
        val hasYarnLock = File(projectDir, "yarn.lock").exists()
        val hasBunLock = File(projectDir, "bun.lockb").exists()
        val pkgMgr = when {
            hasNpmLock -> "npm"
            hasYarnLock -> "yarn"
            hasBunLock -> "bun"
            else -> "pnpm" // Default per rules
        }

        // 1. Node.js (package.json)
        val packageJson = File(projectDir, "package.json")
        if (packageJson.exists()) {
            try {
                val obj = JSONObject(packageJson.readText())
                val scripts = obj.optJSONObject("scripts")
                val cmd = when {
                    scripts?.has("dev") == true -> if (pkgMgr == "npm") "npm run dev" else "$pkgMgr dev"
                    scripts?.has("start") == true -> "$pkgMgr start"
                    scripts?.has("serve") == true -> if (pkgMgr == "npm") "npm run serve" else "$pkgMgr serve"
                    scripts?.has("build") == true -> if (pkgMgr == "npm") "npm run build" else "$pkgMgr build"
                    obj.has("main") -> "node ${obj.getString("main")}"
                    File(projectDir, "server.js").exists() -> "node server.js"
                    File(projectDir, "index.js").exists() -> "node index.js"
                    File(projectDir, "app.js").exists() -> "node app.js"
                    else -> null
                }
                if (cmd != null) {
                    return RunConfiguration(
                        name = "Start Server ($pkgMgr)",
                        type = "terminal",
                        command = cmd,
                        cwd = projectPath,
                        isDefault = true
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading package.json for project server detection: ${e.message}")
            }
        }

        // 2. Python (manage.py, main.py, app.py, server.py)
        if (File(projectDir, "manage.py").exists()) {
            return RunConfiguration(
                name = "Run Django Server",
                type = "terminal",
                command = "python3 manage.py runserver",
                cwd = projectPath,
                isDefault = true
            )
        }

        val pyEntryFiles = listOf("main.py", "app.py", "server.py", "api.py")
        for (pyName in pyEntryFiles) {
            val pyFile = File(projectDir, pyName)
            if (pyFile.exists()) {
                val headContent = try {
                    pyFile.bufferedReader().useLines { lines -> lines.take(60).joinToString("\n") }
                } catch (_: Exception) { "" }

                val (serverName, cmd) = when {
                    headContent.contains("FastAPI", ignoreCase = true) || headContent.contains("uvicorn", ignoreCase = true) ->
                        "FastAPI Server" to "uvicorn ${pyFile.nameWithoutExtension}:app --reload"
                    headContent.contains("Flask", ignoreCase = true) ->
                        "Flask Server" to "flask run --debug"
                    else ->
                        "Python Application" to "python3 $pyName"
                }
                return RunConfiguration(
                    name = serverName,
                    type = "terminal",
                    command = cmd,
                    cwd = projectPath,
                    isDefault = true
                )
            }
        }

        // 3. SQL / Database & Services
        val sqliteFiles = projectDir.listFiles { file ->
            val ext = file.extension.lowercase()
            file.isFile && (ext == "sqlite" || ext == "sqlite3" || ext == "db")
        }
        if (!sqliteFiles.isNullOrEmpty()) {
            val dbFile = sqliteFiles.first()
            return RunConfiguration(
                name = "SQLite DB Shell (${dbFile.name})",
                type = "terminal",
                command = "sqlite3 \"${dbFile.name}\"",
                cwd = projectPath,
                isDefault = true
            )
        }

        if (File(projectDir, "docker-compose.yml").exists() || File(projectDir, "compose.yaml").exists()) {
            return RunConfiguration(
                name = "Docker Compose Services",
                type = "terminal",
                command = "docker compose up",
                cwd = projectPath,
                isDefault = true
            )
        }

        if (File(projectDir, "prisma/schema.prisma").exists()) {
            val cmd = if (pkgMgr == "npm") "npx prisma studio" else "$pkgMgr prisma studio"
            return RunConfiguration(
                name = "Prisma Studio (Database GUI)",
                type = "terminal",
                command = cmd,
                cwd = projectPath,
                isDefault = true
            )
        }

        // 4. Go
        if (File(projectDir, "go.mod").exists()) {
            return RunConfiguration(
                name = "Run Go Server/App",
                type = "terminal",
                command = "go run .",
                cwd = projectPath,
                isDefault = true
            )
        }

        // 5. Rust
        if (File(projectDir, "Cargo.toml").exists()) {
            return RunConfiguration(
                name = "Run Cargo Project",
                type = "terminal",
                command = "cargo run",
                cwd = projectPath,
                isDefault = true
            )
        }

        // 6. PHP
        if (File(projectDir, "artisan").exists()) {
            return RunConfiguration(
                name = "Run Laravel Server",
                type = "terminal",
                command = "php artisan serve",
                cwd = projectPath,
                isDefault = true
            )
        }
        if (File(projectDir, "index.php").exists()) {
            return RunConfiguration(
                name = "Run PHP Server",
                type = "terminal",
                command = "php -S 0.0.0.0:8000",
                cwd = projectPath,
                isDefault = true
            )
        }

        // 7. Gradle
        if (File(projectDir, "gradlew").exists()) {
            return RunConfiguration(
                name = "Run Gradle Server",
                type = "terminal",
                command = "./gradlew run",
                cwd = projectPath,
                isDefault = true
            )
        }

        return null
    }

    /**
     * Builds a prompt to ask the main AI model how to run this project in Termux when local heuristics don't recognize it.
     */
    fun buildAskModelPromptForProjectServer(projectPath: String): String {
        val dir = File(projectPath)
        val files = dir.listFiles()?.take(25)?.map { it.name } ?: emptyList()
        return "I am running this project in Termux on Android. How do I start the development server, database, or background services for this project?\n" +
               "Top-level project files: ${files.joinToString(", ")}.\n" +
               "Please provide ONLY the exact bash command to start the server/service, enclosed in a ```bash ... ``` block."
    }

    /**
     * Extracts the single bash command from the AI model's response.
     */
    fun extractRecipeFromModelResponse(modelResponse: String): String? {
        val blockPattern = Pattern.compile("```(?:bash|sh)?\\s*\\n?([^`]+)```")
        val matcher = blockPattern.matcher(modelResponse)
        if (matcher.find()) {
            val matched = matcher.group(1)?.trim()
            if (!matched.isNullOrBlank()) {
                return matched.lines().firstOrNull { it.trim().isNotBlank() && !it.trim().startsWith("#") }?.trim() ?: matched
            }
        }
        return modelResponse.lines().firstOrNull { line ->
            val trimmed = line.trim().trim('`')
            trimmed.startsWith("pnpm ") || trimmed.startsWith("npm ") || trimmed.startsWith("yarn ") ||
            trimmed.startsWith("python") || trimmed.startsWith("node ") || trimmed.startsWith("cargo ") ||
            trimmed.startsWith("docker ") || trimmed.startsWith("sqlite3 ") || trimmed.startsWith("./")
        }?.trim()?.trim('`')
    }

    /**
     * Records a learned project execution recipe into .ide/launch.json so it can be re-run with 0 tokens.
     */
    fun saveLearnedServerRecipe(projectPath: String, command: String, name: String = "Project Dev Server"): Boolean {
        val clean = command.trim()
        if (clean.isBlank()) return false
        val config = RunConfiguration(
            name = name,
            type = "terminal",
            command = clean,
            cwd = projectPath,
            isDefault = true
        )
        return addConfiguration(projectPath, config)
    }
}
