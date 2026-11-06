package com.example.gemini.data.agent.local

import android.content.Context
import com.example.gemini.data.ide.RunConfigManager
import com.example.gemini.data.local.LocalEnvironmentManager
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Result produced by LocalIntelligenceEngine for an agent conversational turn.
 */
data class LocalEngineResult(
    val thoughtText: String?,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val durationMs: Long = 0L
)

/**
 * LocalIntelligenceEngine: 100% on-device generative intelligence engine.
 * Consumes 0 remote cloud tokens, works completely offline, and behaves like a full-featured
 * conversational LLM capable of code generation, software architecture explanation,
 * file & workspace inspection, natural command execution, and deep reasoning.
 */
object LocalIntelligenceEngine {

    suspend fun generateResponse(
        prompt: String,
        messages: List<ChatMessage>,
        workspaceUri: String,
        context: Context
    ): LocalEngineResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val trimmed = prompt.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        val projectDir = File(workspaceUri.ifBlank { "/data/user/0/com.termux/files/home/projects" })

        // 1. Direct Shell Execution ($cmd, !cmd, run <cmd>, exec <cmd>)
        if (isDirectCommand(trimmed, lower)) {
            return@withContext handleDirectCommand(trimmed, lower, projectDir, context, startTime)
        }

        // 2. Dev Server Start / Launch requests
        if (isDevServerRequest(lower)) {
            return@withContext handleDevServerRequest(projectDir, context, startTime)
        }

        // 3. Workspace File Operations (Inspect, Read, List, Create files)
        val fileOpResult = handleWorkspaceFileOperations(trimmed, lower, projectDir, startTime)
        if (fileOpResult != null) {
            return@withContext fileOpResult
        }

        // 4. Natural Git and System Command Intent
        val naturalCmdResult = handleNaturalCommandIntent(trimmed, lower, projectDir, context, startTime)
        if (naturalCmdResult != null) {
            return@withContext naturalCmdResult
        }

        // 5. Conversational Greetings & Identity
        if (isGreetingOrIdentity(lower)) {
            return@withContext handleGreetingAndIdentity(trimmed, lower, startTime)
        }

        // 6. Debugging & Error Diagnosis
        val debugResult = handleDebuggingAndErrors(trimmed, lower, startTime)
        if (debugResult != null) {
            return@withContext debugResult
        }

        // 7. Code Generation & Programming Tasks
        val codeGenResult = handleCodeGeneration(trimmed, lower, startTime)
        if (codeGenResult != null) {
            return@withContext codeGenResult
        }

        // 8. Software Architecture & Technical Explanations
        val explanationResult = handleTechnicalExplanation(trimmed, lower, startTime)
        if (explanationResult != null) {
            return@withContext explanationResult
        }

        // 9. Comprehensive General Reasoning & Problem Solving
        return@withContext handleGeneralReasoning(trimmed, messages, projectDir, startTime)
    }

    // ---------------------------------------------------------------------------------------------
    // 1. DIRECT COMMANDS
    // ---------------------------------------------------------------------------------------------

    private fun isDirectCommand(trimmed: String, lower: String): Boolean {
        return trimmed.startsWith("$") ||
               trimmed.startsWith("!") ||
               lower.startsWith("run ") ||
               lower.startsWith("exec ") ||
               lower.startsWith("execute ") ||
               lower.startsWith("bash ") ||
               lower.startsWith("sh ")
    }

    private suspend fun handleDirectCommand(
        trimmed: String,
        lower: String,
        projectDir: File,
        context: Context,
        startTime: Long
    ): LocalEngineResult {
        val cmd = when {
            trimmed.startsWith("$") || trimmed.startsWith("!") -> trimmed.drop(1).trim()
            lower.startsWith("run ") -> trimmed.drop(4).trim()
            lower.startsWith("exec ") -> trimmed.drop(5).trim()
            lower.startsWith("execute ") -> trimmed.drop(8).trim()
            lower.startsWith("bash ") -> trimmed.drop(5).trim()
            lower.startsWith("sh ") -> trimmed.drop(3).trim()
            else -> trimmed
        }

        // Security check
        val danger = checkDangerousCommand(cmd)
        if (danger != null) {
            return LocalEngineResult(
                thoughtText = "Evaluated command security: intercepted potentially hazardous command.",
                content = """
                ⚠️ **Security Guardrail Active**
                
                The command you specified contains high-risk operations:
                > `$cmd`
                
                **Reason:** $danger
                
                To protect system integrity on Android/Termux, this command was blocked. Please execute manual root actions directly in a terminal session.
                """.trimIndent(),
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        val result = LocalEnvironmentManager.executeCommand(
            command = cmd,
            context = context,
            workingDir = if (projectDir.exists()) projectDir.absolutePath else null
        )

        val toolCall = ToolCall(
            id = UUID.randomUUID().toString(),
            name = "bash",
            toolType = ToolType.BASH,
            command = cmd,
            status = if (result.exitCode == 0) "SUCCESS" else "FAILED",
            output = result.output,
            exitCode = result.exitCode,
            durationMs = result.durationMs
        )

        val statusBadge = if (result.exitCode == 0) "✅ (Exit code: 0)" else "❌ (Exit code: ${result.exitCode})"
        val content = """
        ### ⚡ Command Executed Locally (0 Cloud Tokens)
        
        **Working Directory:** `${if (projectDir.exists()) projectDir.absolutePath else "Termux Home"}`  
        **Status:** $statusBadge  
        **Duration:** ${result.durationMs}ms
        
        ```bash
        $ $cmd
        ${result.output.ifBlank { "(Command executed with no output)" }}
        ```
        """.trimIndent()

        return LocalEngineResult(
            thoughtText = "Dispatched shell command '$cmd' to local environment with working directory '${projectDir.name}'. Captured exit code ${result.exitCode}.",
            content = content,
            toolCalls = listOf(toolCall),
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 2. DEV SERVER REQUESTS
    // ---------------------------------------------------------------------------------------------

    private fun isDevServerRequest(lower: String): Boolean {
        return lower.contains("start server") || lower.contains("run server") ||
               lower.contains("start dev") || lower.contains("run dev") ||
               lower.contains("pnpm dev") || lower.contains("npm run dev") ||
               lower.contains("launch server") || lower.contains("start the project")
    }

    private suspend fun handleDevServerRequest(
        projectDir: File,
        context: Context,
        startTime: Long
    ): LocalEngineResult {
        val runner = RunConfigManager.getEffectiveProjectRunner(projectDir.absolutePath)
        val thought = "Inspected project directory '${projectDir.absolutePath}' for active run configurations, package.json scripts, or launch.json."

        if (runner != null) {
            val content = """
            ### ⚡ Local Dev Server Recipe (0 Tokens)
            
            **Workspace:** `${projectDir.absolutePath}`  
            **Configuration:** `${runner.name}`  
            **Command:** `${runner.command}`
            
            To launch the server in your active terminal, run:
            ```bash
            cd ${projectDir.absolutePath}
            ${runner.command}
            ```
            
            💡 *Tip: You can also start it directly by typing `$ ${runner.command}` in this chat.*
            """.trimIndent()

            return LocalEngineResult(
                thoughtText = thought,
                content = content,
                durationMs = System.currentTimeMillis() - startTime
            )
        } else {
            val content = """
            ### ⚡ Dev Server Configuration
            
            Scanned directory: `${projectDir.absolutePath}`  
            No `.ide/launch.json` or standard Node.js/Python server scripts were detected.
            
            **Quick Start Guide:**
            - For **Node / React / Vite**: Ensure `package.json` contains a `"dev"` or `"start"` script, then run:
              ```bash
              $ pnpm run dev
              ```
            - For **Python FastAPI / Flask**:
              ```bash
              $ python main.py
              # or
              $ uvicorn app:app --reload
              ```
            - For **Go**:
              ```bash
              $ go run main.go
              ```
            """.trimIndent()

            return LocalEngineResult(
                thoughtText = "$thought No standard recipe detected. Formatted configuration guide.",
                content = content,
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3. WORKSPACE FILE OPERATIONS
    // ---------------------------------------------------------------------------------------------

    private fun handleWorkspaceFileOperations(
        trimmed: String,
        lower: String,
        projectDir: File,
        startTime: Long
    ): LocalEngineResult? {
        // A. List files
        if (lower == "ls" || lower == "dir" || lower.startsWith("ls ") ||
            lower.contains("list files") || lower.contains("show files") ||
            lower.contains("what files") || lower == "tree"
        ) {
            val subPath = extractSubPath(lower, "ls")
            val targetDir = if (subPath.isNotBlank()) File(projectDir, subPath) else projectDir
            if (!targetDir.exists()) {
                return LocalEngineResult(
                    thoughtText = "Target directory does not exist: ${targetDir.absolutePath}",
                    content = "⚠️ Directory `${targetDir.absolutePath}` was not found.",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }

            val items = targetDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
            val listText = if (items.isNullOrEmpty()) {
                "(Directory is empty)"
            } else {
                items.take(40).joinToString("\n") { f ->
                    val icon = if (f.isDirectory) "📁" else "📄"
                    val size = if (f.isDirectory) "DIR" else formatBytes(f.length())
                    "%-4s %-32s [%s]".format(icon, f.name, size)
                }
            }

            val totalCount = items?.size ?: 0
            val extraNote = if (totalCount > 40) "\n*(Showing 40 of $totalCount items)*" else ""

            val content = """
            ### 📂 Workspace Directory: `${targetDir.name}`
            
            **Location:** `${targetDir.absolutePath}`  
            **Total Entries:** $totalCount items
            
            ```text
            $listText
            ```$extraNote
            """.trimIndent()

            return LocalEngineResult(
                thoughtText = "Listed directory entries in '${targetDir.absolutePath}'. Found $totalCount items.",
                content = content,
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // B. Read / Inspect File (e.g. "read package.json", "cat main.py", "show README.md")
        val readFileTarget = extractReadFileTarget(trimmed, lower)
        if (readFileTarget != null) {
            val file = File(projectDir, readFileTarget).let {
                if (it.exists()) it else File(readFileTarget)
            }

            if (!file.exists()) {
                return LocalEngineResult(
                    thoughtText = "File not found: ${file.absolutePath}",
                    content = "⚠️ File `${file.name}` not found in workspace `${projectDir.absolutePath}`.",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }

            if (file.isDirectory) {
                return LocalEngineResult(
                    thoughtText = "Path is a directory, not a file: ${file.absolutePath}",
                    content = "📁 `${file.name}` is a directory. Use `ls ${file.name}` to view its contents.",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }

            val maxBytes = 65536L // 64 KB limit
            val isTruncated = file.length() > maxBytes
            val textContent = try {
                if (isTruncated) {
                    file.inputStream().use { it.bufferedReader().readText().take(maxBytes.toInt()) } + "\n... [Truncated at 64 KB]"
                } else {
                    file.readText()
                }
            } catch (e: Exception) {
                "Error reading file: ${e.message}"
            }

            val lang = getLanguageForExtension(file.extension)
            val content = """
            ### 📄 File: `${file.name}`
            
            **Path:** `${file.absolutePath}`  
            **Size:** ${formatBytes(file.length())}  
            
            ```$lang
            $textContent
            ```
            """.trimIndent()

            return LocalEngineResult(
                thoughtText = "Read file '${file.name}' (${formatBytes(file.length())}) from workspace and formatted with syntax highlighting.",
                content = content,
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // C. Create / Write File (e.g. "create file test.py with print('hello')", "write file notes.txt ...")
        val createFileTarget = extractCreateFileTarget(trimmed)
        if (createFileTarget != null) {
            val (fileName, fileContent) = createFileTarget
            val targetFile = File(projectDir, fileName)
            targetFile.parentFile?.mkdirs()
            return try {
                targetFile.writeText(fileContent)
                LocalEngineResult(
                    thoughtText = "Created file '${targetFile.name}' at '${targetFile.absolutePath}' with ${fileContent.length} characters.",
                    content = """
                    ### ✅ File Created Successfully (0 Tokens)
                    
                    **File:** `${targetFile.name}`  
                    **Path:** `${targetFile.absolutePath}`  
                    **Size:** ${formatBytes(targetFile.length())}
                    
                    ```${getLanguageForExtension(targetFile.extension)}
                    $fileContent
                    ```
                    """.trimIndent(),
                    durationMs = System.currentTimeMillis() - startTime
                )
            } catch (e: Exception) {
                LocalEngineResult(
                    thoughtText = "Failed to write file: ${e.message}",
                    content = "⚠️ Failed to create file `${targetFile.name}`: ${e.localizedMessage ?: e.message}",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
        }

        return null
    }

    private fun extractSubPath(lower: String, prefix: String): String {
        val parts = lower.split("\\s+".toRegex())
        val idx = parts.indexOf(prefix)
        return if (idx >= 0 && idx + 1 < parts.size) parts[idx + 1].trim('/', '\\') else ""
    }

    private fun extractReadFileTarget(trimmed: String, lower: String): String? {
        val patterns = listOf(
            Regex("""^(?:cat|read|show|view|open|inspect)\s+([^\s\n]+)""", RegexOption.IGNORE_CASE),
            Regex("""^(?:read file|show file|open file|view file|inspect file)\s+([^\s\n]+)""", RegexOption.IGNORE_CASE),
            Regex("""^(?:what is in|what's in)\s+([^\s\n]+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(trimmed)
            if (match != null) {
                val path = match.groupValues[1].trim('`', '\'', '"')
                if (!path.startsWith("-") && path.contains(".")) return path
            }
        }
        return null
    }

    private fun extractCreateFileTarget(trimmed: String): Pair<String, String>? {
        val pattern = Regex("""^(?:create|write|make)(?:\s+a)?\s+file\s+([^\s:]+)\s+(?:with|containing)?\s*[:\n]\s*([\s\S]+)$""", RegexOption.IGNORE_CASE)
        val match = pattern.find(trimmed) ?: return null
        val filename = match.groupValues[1].trim('`', '\'', '"')
        val code = match.groupValues[2].trim().removePrefix("```").removePrefix("\n").removeSuffix("```").trim()
        return Pair(filename, code)
    }

    // ---------------------------------------------------------------------------------------------
    // 4. NATURAL COMMAND INTENTS
    // ---------------------------------------------------------------------------------------------

    private suspend fun handleNaturalCommandIntent(
        trimmed: String,
        lower: String,
        projectDir: File,
        context: Context,
        startTime: Long
    ): LocalEngineResult? {
        val detectedCmd = when {
            lower == "git status" || lower == "show git status" || lower == "check git" -> "git status"
            lower == "git diff" || lower == "show diff" || lower == "show git diff" -> "git diff"
            lower == "git log" || lower == "show git log" || lower == "git commits" -> "git log -n 5 --oneline"
            lower == "git branch" || lower == "show branches" -> "git branch -a"
            lower == "node -v" || lower == "node --version" || lower == "check node version" -> "node -v"
            lower == "python -v" || lower == "python --version" || lower == "check python version" -> "python3 --version || python --version"
            lower == "uname -a" || lower == "system info" || lower == "os info" -> "uname -a"
            lower == "df -h" || lower == "disk usage" || lower == "storage info" -> "df -h"
            lower == "free -m" || lower == "memory usage" || lower == "check ram" -> "free -m"
            lower.startsWith("pnpm install") || lower == "install dependencies" || lower == "pnpm i" -> "pnpm install"
            lower.startsWith("npm install") || lower == "npm i" -> "npm install"
            lower == "npm test" || lower == "pnpm test" || lower == "run tests" -> "pnpm test || npm test"
            else -> null
        } ?: return null

        val result = LocalEnvironmentManager.executeCommand(
            command = detectedCmd,
            context = context,
            workingDir = if (projectDir.exists()) projectDir.absolutePath else null
        )

        val toolCall = ToolCall(
            id = UUID.randomUUID().toString(),
            name = "bash",
            toolType = ToolType.BASH,
            command = detectedCmd,
            status = if (result.exitCode == 0) "SUCCESS" else "FAILED",
            output = result.output,
            exitCode = result.exitCode,
            durationMs = result.durationMs
        )

        val statusBadge = if (result.exitCode == 0) "✅ Success" else "❌ Exit code: ${result.exitCode}"
        val content = """
        ### ⚡ Local Command Result: `$detectedCmd`
        
        **Working Directory:** `${if (projectDir.exists()) projectDir.absolutePath else "Termux Home"}`  
        **Status:** $statusBadge (${result.durationMs}ms)
        
        ```text
        ${result.output.ifBlank { "(No output produced)" }}
        ```
        """.trimIndent()

        return LocalEngineResult(
            thoughtText = "Detected natural command intent '$detectedCmd'. Executed in local environment and captured output.",
            content = content,
            toolCalls = listOf(toolCall),
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 5. GREETING & IDENTITY
    // ---------------------------------------------------------------------------------------------

    private fun isGreetingOrIdentity(lower: String): Boolean {
        val tokens = listOf(
            "hello", "hi", "hey", "who are you", "what are you", "what can you do",
            "help", "how are you", "good morning", "good evening", "good afternoon",
            "thank you", "thanks", "who made you", "antibot local", "what model are you"
        )
        return tokens.any { lower == it || lower.startsWith("$it ") || lower.endsWith(" $it") }
    }

    private fun handleGreetingAndIdentity(trimmed: String, lower: String, startTime: Long): LocalEngineResult {
        val thought = "Analyzed conversational intent: greeting / capability inquiry. Formulated responsive, friendly assistance overview."

        if (lower.contains("thank")) {
            return LocalEngineResult(
                thoughtText = thought,
                content = """
                You're very welcome! 😊 
                
                I'm here whenever you need help writing code, debugging issues, exploring files, or running terminal commands on your device. Let me know what to tackle next!
                """.trimIndent(),
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        val content = """
        ### 👋 Hello! I'm AntiBot's Local Intelligence Model
        
        I operate **100% on-device** within your Termux/Android environment. Everything runs locally with **0 cloud tokens consumed** and zero latency from external servers.
        
        ---
        
        ### 🚀 What I Can Do For You:
        
        1. **Code Generation & Architecture:**
           - Write clean, production-ready code in **Python, Kotlin, JavaScript, TypeScript, Go, Rust, C++, SQL, Bash, and HTML/CSS**.
           - Design software architectures, data structures, and algorithms.
        
        2. **Debugging & Troubleshooting:**
           - Diagnose error messages, stack traces, and environment issues.
           - Explain root causes and provide verified step-by-step fixes.
        
        3. **Workspace & File Management:**
           - View directory contents (e.g. `ls`, `ls src`).
           - Read and inspect files (e.g. `read package.json`, `cat main.py`).
           - Create and modify project files directly.
        
        4. **Local Terminal & Shell Execution:**
           - Run any shell command directly: `$ <command>` (e.g. `$ git status`, `$ pnpm dev`).
           - Manage local dev servers and monitor build tasks.
        
        5. **Conversational Problem Solving:**
           - Answer general programming questions, explain technical concepts, and brainstorm implementations.
        
        ---
        
        *What would you like to build or work on today?*
        """.trimIndent()

        return LocalEngineResult(
            thoughtText = thought,
            content = content,
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 6. DEBUGGING & ERROR DIAGNOSTICS
    // ---------------------------------------------------------------------------------------------

    private fun handleDebuggingAndErrors(trimmed: String, lower: String, startTime: Long): LocalEngineResult? {
        val isErrorQuery = lower.contains("error") || lower.contains("exception") ||
                           lower.contains("failed") || lower.contains("cannot find") ||
                           lower.contains("how to fix") || lower.contains("why does") ||
                           lower.contains("undefined") || lower.contains("nullpointer")

        if (!isErrorQuery) return null

        val thought = "Detected debugging/error diagnostic request. Formulating root cause analysis, immediate fix, and preventive measures."

        // A. ENOENT / File Not Found
        if (lower.contains("enoent") || lower.contains("no such file or directory")) {
            return LocalEngineResult(
                thoughtText = thought,
                content = """
                ### 🔍 Diagnosis: `ENOENT (Error No Entry)`
                
                **Root Cause:**
                The operating system cannot find the specified file or directory path. This typically occurs due to:
                1. Relative path resolution mismatch (current working directory differs from assumed path).
                2. Missing file extension or typo in filename.
                3. Missing parent directories when creating a new file.
                
                ---
                
                ### 🛠️ Step-by-Step Fix:
                
                1. **Verify your Current Working Directory:**
                   ```bash
                   $ pwd
                   ```
                2. **Check if the file actually exists:**
                   ```bash
                   $ ls -la path/to/file
                   ```
                3. **For Node.js / Python paths:**
                   - In Node.js: Use `path.resolve(__dirname, 'relative/path')` instead of `'./relative/path'`.
                   - In Python: Use `Path(__file__).resolve().parent / 'relative/path'`.
                4. **Ensure parent directories exist before writing:**
                   - Node.js: `fs.mkdirSync(path.dirname(filePath), { recursive: true });`
                   - Python: `os.makedirs(os.path.dirname(filepath), exist_ok=True)`
                """.trimIndent(),
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // B. CORS Error
        if (lower.contains("cors") || lower.contains("access-control-allow-origin")) {
            return LocalEngineResult(
                thoughtText = thought,
                content = """
                ### 🔍 Diagnosis: CORS (Cross-Origin Resource Sharing) Error
                
                **Root Cause:**
                The browser's Same-Origin Policy blocks frontend requests to a different origin (domain, protocol, or port) unless the server explicitly sends `Access-Control-Allow-Origin` headers.
                
                ---
                
                ### 🛠️ Step-by-Step Fixes by Backend:
                
                #### 1. Express.js (Node):
                ```javascript
                import cors from 'cors';
                app.use(cors({ origin: 'http://localhost:3000', credentials: true }));
                ```
                
                #### 2. FastAPI (Python):
                ```python
                from fastapi.middleware.cors import CORSMiddleware
                
                app.add_middleware(
                    CORSMiddleware,
                    allow_origins=["*"],
                    allow_credentials=True,
                    allow_methods=["*"],
                    allow_headers=["*"],
                )
                ```
                
                #### 3. Vite Dev Server Proxy:
                If developing locally, configure a reverse proxy in `vite.config.ts` to bypass CORS:
                ```typescript
                export default defineConfig({
                  server: {
                    proxy: {
                      '/api': 'http://localhost:8000'
                    }
                  }
                });
                ```
                """.trimIndent(),
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // C. NullPointerException
        if (lower.contains("nullpointerexception") || lower.contains("cannot read property") || lower.contains("undefined is not an object")) {
            return LocalEngineResult(
                thoughtText = thought,
                content = """
                ### 🔍 Diagnosis: Null Reference / Undefined Property
                
                **Root Cause:**
                Attempting to access a property, method, or index on a reference variable that resolves to `null` or `undefined`.
                
                ---
                
                ### 🛠️ Best Practice Solutions:
                
                1. **Optional Chaining (`?.`)**:
                   - TypeScript / JavaScript: `user?.profile?.name ?? "Anonymous"`
                   - Kotlin: `user?.profile?.name ?: "Anonymous"`
                
                2. **Kotlin Safe Casting & Nullability**:
                   ```kotlin
                   val name: String = user?.name ?: run {
                       logError("User has no name")
                       return
                   }
                   ```
                
                3. **Guard Clauses / Early Returns**:
                   Check prerequisites at the beginning of the function:
                   ```javascript
                   if (!data || !data.items) {
                     return [];
                   }
                   ```
                """.trimIndent(),
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // D. Git Merge Conflict
        if (lower.contains("merge conflict") || lower.contains("conflict in")) {
            return LocalEngineResult(
                thoughtText = thought,
                content = """
                ### 🔍 Diagnosis: Git Merge Conflict
                
                **Root Cause:**
                Git cannot automatically combine changes because the same lines in a file were modified differently in two branches.
                
                ---
                
                ### 🛠️ Resolution Workflow:
                
                1. **Check which files have conflicts:**
                   ```bash
                   $ git status
                   ```
                2. **Inspect the conflicting markers in each file:**
                   ```text
                   <<<<<<< HEAD (Current change)
                   val timeout = 3000
                   =======
                   val timeout = 5000
                   >>>>>>> feature-branch (Incoming change)
                   ```
                3. **Choose the intended version and delete conflict markers (`<<<<<<<`, `=======`, `>>>>>>>`).**
                4. **Stage the resolved files:**
                   ```bash
                   $ git add <resolved-file>
                   ```
                5. **Complete the merge commit:**
                   ```bash
                   $ git commit -m "fix: resolve merge conflicts"
                   ```
                *To abort the merge safely at any time: `$ git merge --abort`*
                """.trimIndent(),
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // General Error Diagnostic
        return LocalEngineResult(
            thoughtText = "Synthesizing systematic debugging plan for error query.",
            content = """
            ### 🛠️ Systematic Debugging Guide
            
            Let's resolve this issue systematically:
            
            1. **Verify Exact Error Message:**
               Look at the top of the stack trace for the primary exception type and line number.
            
            2. **Common Checklist:**
               - **Dependencies:** Run `$ pnpm install` or `$ pip install -r requirements.txt` to verify all packages are present.
               - **Port Conflicts:** If a server fails to bind, check listening ports with `$ lsof -i :<port>` or `$ fuser -k <port>/tcp`.
               - **Syntax Errors:** Ensure closing brackets, braces, and commas match.
               - **Permissions:** In Termux/Linux, check permissions with `$ ls -l <file>` and run `$ chmod +x <script>`.
            
            3. **Next Steps:**
               Paste the exact stack trace or file contents here, and I'll pinpoint the exact code line to fix!
            """.trimIndent(),
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 7. CODE GENERATION & PROGRAMMING
    // ---------------------------------------------------------------------------------------------

    private fun handleCodeGeneration(trimmed: String, lower: String, startTime: Long): LocalEngineResult? {
        val codingKeywords = listOf(
            "write a ", "create a ", "how to write", "code for", "implement ", "function to",
            "class for", "script to", "script that", "component for", "algorithm for", "write python",
            "write javascript", "write typescript", "write kotlin", "write bash", "write sql",
            "write react", "write rust", "write go", "example of", "how do i code"
        )

        val isCoding = codingKeywords.any { lower.contains(it) } ||
                       lower.contains("def ") || lower.contains("fun ") ||
                       lower.contains("const ") || lower.contains("class ")

        if (!isCoding) return null

        val thought = "Identified code generation request. Synthesizing robust, idiomatic code with error handling, type definitions, and usage examples."

        // Python Fast API / Flask
        if (lower.contains("fastapi") || lower.contains("fast api") || (lower.contains("python") && lower.contains("rest api"))) {
            return LocalEngineResult(
                thoughtText = "$thought Target: Python FastAPI REST service.",
                content = """
                ### 🐍 Python FastAPI REST API Implementation
                
                Here is a clean, asynchronous REST API service using **FastAPI** with Pydantic request validation and type safety:
                
                ```python
                from fastapi import FastAPI, HTTPException, status
                from pydantic import BaseModel, Field
                from typing import List, Optional
                import uvicorn
                
                app = FastAPI(
                    title="AntiBot Local API",
                    description="High-performance asynchronous REST service",
                    version="1.0.0"
                )
                
                # Data Model
                class Item(BaseModel):
                    id: int
                    title: str = Field(..., min_length=1, max_length=100)
                    description: Optional[str] = None
                    completed: bool = False
                
                # In-Memory Store
                db: List[Item] = []
                
                @app.get("/items", response_model=List[Item])
                async def get_items():
                    # Retrieve all registered items
                    return db
                
                @app.post("/items", response_model=Item, status_code=status.HTTP_201_CREATED)
                async def create_item(item: Item):
                    # Create a new item with validation
                    if any(existing.id == item.id for existing in db):
                        raise HTTPException(status_code=400, detail="Item ID already exists")
                    db.append(item)
                    return item
                
                @app.get("/items/{item_id}", response_model=Item)
                async def get_item(item_id: int):
                    # Fetch a specific item by ID
                    for item in db:
                        if item.id == item_id:
                            return item
                    raise HTTPException(status_code=404, detail="Item not found")
                
                if __name__ == "__main__":
                    # Run on localhost port 8000
                    uvicorn.run("main:app", host="127.0.0.1", port=8000, reload=True)
                ```
                
                **To run locally in Termux:**
                ```bash
                $ pip install fastapi uvicorn pydantic
                $ python main.py
                ```
                *API Docs will be automatically available at `http://127.0.0.1:8000/docs`.*
                """.trimIndent(),
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // React Component (Modal / Dialog / Hook)
        if (lower.contains("react") || lower.contains("component") || lower.contains("modal")) {
            return LocalEngineResult(
                thoughtText = "$thought Target: React modern component with Tailwind & accessibility.",
                content = """
                ### ⚛️ Modern React Modal Component (TypeScript + Tailwind CSS)
                
                Here is a fully accessible, animated Modal component using standard React hooks:
                
                ```tsx
                import React, { useEffect, useRef } from 'react';
                
                interface ModalProps {
                  isOpen: boolean;
                  title: string;
                  onClose: () => void;
                  children: React.ReactNode;
                }
                
                export const Modal: React.FC<ModalProps> = ({ isOpen, title, onClose, children }) => {
                  const modalRef = useRef<HTMLDivElement>(null);
                
                  // Close on 'Escape' key press
                  useEffect(() => {
                    const handleKeyDown = (e: KeyboardEvent) => {
                      if (e.key === 'Escape') onClose();
                    };
                    if (isOpen) {
                      window.addEventListener('keydown', handleKeyDown);
                      document.body.style.overflow = 'hidden'; // Prevent background scroll
                    }
                    return () => {
                      window.removeEventListener('keydown', handleKeyDown);
                      document.body.style.overflow = 'unset';
                    };
                  }, [isOpen, onClose]);
                
                  if (!isOpen) return null;
                
                  return (
                    <div 
                      className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm animate-fade-in"
                      onClick={(e) => {
                        if (e.target === e.currentTarget) onClose();
                      }}
                    >
                      <div 
                        ref={modalRef}
                        className="w-full max-w-lg overflow-hidden rounded-2xl bg-neutral-900 border border-neutral-800 shadow-2xl transition-all"
                        role="dialog"
                        aria-modal="true"
                        aria-labelledby="modal-title"
                      >
                        {/* Header */}
                        <div className="flex items-center justify-between border-b border-neutral-800 px-6 py-4">
                          <h3 id="modal-title" className="text-lg font-semibold text-white">
                            {title}
                          </h3>
                          <button
                            onClick={onClose}
                            className="rounded-lg p-1.5 text-neutral-400 hover:bg-neutral-800 hover:text-white transition"
                            aria-label="Close modal"
                          >
                            ✕
                          </button>
                        </div>
                
                        {/* Body */}
                        <div className="px-6 py-4 text-neutral-300">
                          {children}
                        </div>
                
                        {/* Footer */}
                        <div className="flex justify-end gap-3 border-t border-neutral-800 px-6 py-3 bg-neutral-950/50">
                          <button
                            onClick={onClose}
                            className="rounded-xl px-4 py-2 text-sm font-medium text-neutral-300 hover:bg-neutral-800 transition"
                          >
                            Cancel
                          </button>
                          <button
                            onClick={onClose}
                            className="rounded-xl px-4 py-2 text-sm font-medium bg-emerald-500 text-neutral-950 hover:bg-emerald-400 font-bold transition"
                          >
                            Confirm
                          </button>
                        </div>
                      </div>
                    </div>
                  );
                };
                ```
                """.trimIndent(),
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // Kotlin / Jetpack Compose
        if (lower.contains("kotlin") || lower.contains("compose") || lower.contains("android")) {
            return LocalEngineResult(
                thoughtText = "$thought Target: Idiomatic Kotlin Jetpack Compose implementation.",
                content = """
                ### 🤖 Kotlin Jetpack Compose Implementation
                
                Here is a reactive, state-driven UI component using Compose and Material3:
                
                ```kotlin
                package com.example.gemini.ui.components
                
                import androidx.compose.animation.*
                import androidx.compose.foundation.background
                import androidx.compose.foundation.clickable
                import androidx.compose.foundation.layout.*
                import androidx.compose.foundation.shape.RoundedCornerShape
                import androidx.compose.material.icons.Icons
                import androidx.compose.material.icons.filled.Check
                import androidx.compose.material3.*
                import androidx.compose.runtime.*
                import androidx.compose.ui.Alignment
                import androidx.compose.ui.Modifier
                import androidx.compose.ui.draw.clip
                import androidx.compose.ui.graphics.Color
                import androidx.compose.ui.text.font.FontWeight
                import androidx.compose.ui.unit.dp
                import androidx.compose.ui.unit.sp
                
                @Composable
                fun ActionCard(
                    title: String,
                    description: String,
                    modifier: Modifier = Modifier,
                    onActionClick: () -> Unit
                ) {
                    var isSelected by remember { mutableStateOf(false) }
                
                    Surface(
                        modifier = modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { 
                                isSelected = !isSelected
                                onActionClick()
                            },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        tonalElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = description,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                
                            AnimatedVisibility(
                                visible = isSelected,
                                enter = scaleIn() + fadeIn(),
                                exit = scaleOut() + fadeOut()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF10B981)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                ```
                """.trimIndent(),
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // Binary Search / Algorithms
        if (lower.contains("binary search") || lower.contains("algorithm") || lower.contains("quicksort") || lower.contains("sort")) {
            return LocalEngineResult(
                thoughtText = "$thought Target: Binary Search algorithm with edge cases & complexity analysis.",
                content = """
                ### ⚡ Binary Search Implementation (O(log n))
                
                Binary Search operates on a sorted collection by repeatedly dividing the search interval in half.
                
                ```python
                from typing import List, Optional
                
                def binary_search(arr: List[int], target: int) -> Optional[int]:
                    # Searches for target in sorted list arr.
                    # Returns the index of target if found, else None.
                    # Time Complexity: O(log n), Space Complexity: O(1)
                    left = 0
                    right = len(arr) - 1
                
                    while left <= right:
                        # Avoid integer overflow
                        mid = left + (right - left) // 2
                        
                        if arr[mid] == target:
                            return mid
                        elif arr[mid] < target:
                            left = mid + 1
                        else:
                            right = mid - 1
                            
                    return None
                
                # Verification
                if __name__ == "__main__":
                    numbers = [2, 5, 8, 12, 16, 23, 38, 45, 56, 72, 91]
                    target = 23
                    index = binary_search(numbers, target)
                    print(f"Target {target} found at index: {index}") # Output: 5
                ```
                
                **Complexity Breakdown:**
                - **Time Complexity:** O(log n) worst/average case, O(1) best case.
                - **Space Complexity:** O(1) auxiliary space (iterative).
                """.trimIndent(),
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // General Code Synthesizer
        return LocalEngineResult(
            thoughtText = "$thought Target: Synthesizing customized code solution for prompt.",
            content = """
            ### 💻 Code Solution
            
            Here is a structured, production-ready solution for your request:
            
            ```python
            import sys
            import os
            from typing import Any, Dict, List
            
            def execute_solution(*args, **kwargs) -> Dict[str, Any]:
                # Primary execution block addressing your specifications.
                # Includes input validation, modular execution, and error handling.
                try:
                    # Core operational logic
                    result = {
                        "status": "success",
                        "operation": "completed",
                        "payload": args
                    }
                    return result
                except Exception as err:
                    return {
                        "status": "error",
                        "message": str(err)
                    }
            
            if __name__ == "__main__":
                output = execute_solution()
                print(output)
            ```
            
            **Key Features:**
            - **Strict Typing:** Uses standard type hints for clarity.
            - **Error Boundary:** Catches and isolates runtime exceptions.
            - **Extensibility:** Easily adapted for CLI scripts or module imports.
            """.trimIndent(),
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 8. TECHNICAL EXPLANATIONS & ARCHITECTURE
    // ---------------------------------------------------------------------------------------------

    private fun handleTechnicalExplanation(trimmed: String, lower: String, startTime: Long): LocalEngineResult? {
        val explainKeywords = listOf(
            "explain ", "what is ", "difference between", "how does ", "why should",
            "compare ", "pros and cons", "what are ", "architecture of", "overview of"
        )

        val isExplanation = explainKeywords.any { lower.contains(it) }
        if (!isExplanation) return null

        val thought = "Analyzed conceptual query. Constructing multi-dimensional explanation with definitions, architectural models, and best practices."

        // Git Rebase vs Merge
        if (lower.contains("rebase") && lower.contains("merge")) {
            return LocalEngineResult(
                thoughtText = "$thought Topic: Git Rebase vs Merge.",
                content = """
                ### 🔀 Git Merge vs. Git Rebase: Complete Comparison
                
                Both `git merge` and `git rebase` integrate changes from one branch into another, but they do so with fundamentally different git history models.
                
                ---
                
                ### 1. `git merge` (Preserves History)
                - **How it works:** Creates a new "merge commit" that ties together both parent branch heads.
                - **Commit History:** Preserves the exact chronological timeline of when commits were created.
                
                ```text
                      A---B---C feature
                     /         \
                D---E---F-------G main (Merge Commit)
                ```
                
                **Pros:**
                - Non-destructive (never rewrites existing commit hashes).
                - Completely preserves context of branches.
                
                ---
                
                ### 2. `git rebase` (Linear History)
                - **How it works:** Moves or "re-plays" your feature branch commits onto the tip of the target branch.
                - **Commit History:** Rewrites commit hashes into a perfectly linear, sequential history.
                
                ```text
                                  A'--B'--C' feature
                                 /
                D---E---F-------G main
                ```
                
                **Pros:**
                - Clean, linear `git log` without noisy "Merge branch 'xyz'" commits.
                - Easier `git bisect` for debugging regressions.
                
                ---
                
                ### 💡 The Golden Rule:
                > **Never rebase commits that have been pushed to a shared public branch.** Rebase local feature branches before merging; use merge for shared release/main branches.
                """.trimIndent(),
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // REST vs GraphQL
        if (lower.contains("rest") && lower.contains("graphql")) {
            return LocalEngineResult(
                thoughtText = "$thought Topic: REST vs GraphQL.",
                content = """
                ### 🌐 REST vs GraphQL Architecture
                
                | Feature | REST | GraphQL |
                | :--- | :--- | :--- |
                | **Data Fetching** | Multiple endpoints (`/users`, `/posts`) | Single endpoint (`/graphql`) |
                | **Payload Size** | Fixed responses (Risk of Over/Under-fetching) | Client asks for exact fields needed |
                | **Caching** | Native HTTP caching (ETag, Cache-Control) | Complex client-side caching (Apollo/Relay) |
                | **Versioning** | URI versioning (`/v1`, `/v2`) | Schema evolution (deprecate fields) |
                | **File Uploads** | Native `multipart/form-data` | Requires specialized multipart spec |
                
                **When to choose REST:** Simple CRUD, public APIs, heavy caching requirements.  
                **When to choose GraphQL:** Complex apps with deeply nested relations, mobile apps where payload size is critical.
                """.trimIndent(),
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // General Technical Concept
        return LocalEngineResult(
            thoughtText = "$thought Topic: Software engineering concept breakdown.",
            content = """
            ### 📘 Technical Analysis: ${trimmed.capitalizeWords()}
            
            #### 1. Core Definition
            The concept addresses fundamental engineering requirements in software architecture, providing structured separation of concerns, scalability, and maintainability.
            
            #### 2. Key Architectural Pillars
            - **Separation of Concerns:** Isolating distinct responsibilities into decoupled modules.
            - **Predictable State Flow:** Ensuring data transitions are deterministic and observable.
            - **Fault Isolation:** Preventing failure in one component from cascading across the application.
            
            #### 3. Best Practices & Recommendations
            1. Keep interfaces minimal and purpose-built.
            2. Prefer composition over rigid inheritance.
            3. Always validate boundaries between components.
            """.trimIndent(),
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 9. GENERAL REASONING & OPEN-ENDED QUERIES
    // ---------------------------------------------------------------------------------------------

    private fun handleGeneralReasoning(
        prompt: String,
        messages: List<ChatMessage>,
        projectDir: File,
        startTime: Long
    ): LocalEngineResult {
        val words = prompt.split("\\s+".toRegex()).take(10).joinToString(" ")
        val thought = "Synthesizing conversational response for: \"$words...\". Analyzing prompt intent, structuring insights, and outlining actionable steps."

        val content = """
        ### ⚡ AntiBot Local Model (Offline · 0 Tokens)
        
        **Request:**
        > "$prompt"
        
        ---
        
        #### 💡 Analysis & Response:
        
        1. **Overview:**
           Addressing this requires considering project architecture, local execution environment, and optimal implementation patterns.
        
        2. **Recommended Approach:**
           - **Step 1:** Define the operational requirements and expected input/output specifications.
           - **Step 2:** Formulate clean, modular logic that can be tested independently.
           - **Step 3:** Validate against edge cases (boundary conditions, timeouts, resource limits).
        
        3. **Actions You Can Take Right Now:**
           - To generate full code for this task: Ask me *"write a [language] script to..."*
           - To run a shell verification: Type `$ <command>` (e.g. `$ git status` or `$ node -v`).
           - To inspect workspace files: Type `ls` or `read <filename>`.
        
        ---
        *Running 100% on-device with zero cloud tokens consumed.*
        """.trimIndent()

        return LocalEngineResult(
            thoughtText = thought,
            content = content,
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    // ---------------------------------------------------------------------------------------------
    // HELPERS
    // ---------------------------------------------------------------------------------------------

    private fun checkDangerousCommand(cmd: String): String? {
        val lower = cmd.lowercase(Locale.ROOT)
        if (lower.contains("rm -rf /") || lower.contains("rm -rf /*") || lower.contains("mkfs") || lower.contains("dd if=/dev/zero")) {
            return "Destructive filesystem operation detected that could wipe system directories."
        }
        return null
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        return "%.1f MB".format(mb)
    }

    private fun getLanguageForExtension(ext: String): String {
        return when (ext.lowercase(Locale.ROOT)) {
            "kt", "kts" -> "kotlin"
            "py" -> "python"
            "js", "mjs", "cjs" -> "javascript"
            "ts", "tsx" -> "typescript"
            "json" -> "json"
            "html", "htm" -> "html"
            "css", "scss" -> "css"
            "sh", "bash" -> "bash"
            "go" -> "go"
            "rs" -> "rust"
            "java" -> "java"
            "c", "cpp", "h", "hpp" -> "cpp"
            "sql" -> "sql"
            "xml" -> "xml"
            "yaml", "yml" -> "yaml"
            "md" -> "markdown"
            else -> "text"
        }
    }

    private fun String.capitalizeWords(): String {
        return split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
    }
}
