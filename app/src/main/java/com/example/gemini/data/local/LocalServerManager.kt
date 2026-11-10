package com.example.gemini.data.local

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyBridgeService
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

sealed class LocalServerStatus {
    object Idle : LocalServerStatus()
    object Starting : LocalServerStatus()
    data class Running(val startTimeMs: Long = System.currentTimeMillis()) : LocalServerStatus()
    object Stopping : LocalServerStatus()
    data class Stopped(val exitCode: Int? = null, val stopTimeMs: Long = System.currentTimeMillis()) :
        LocalServerStatus()

    data class Error(val message: String) : LocalServerStatus()
}

object LocalServerManager {

    private const val TAG = "LocalServerManager"

    private val _status = MutableStateFlow<LocalServerStatus>(LocalServerStatus.Idle)
    val status: StateFlow<LocalServerStatus> = _status.asStateFlow()

    fun publishRemoteStatus(newStatus: LocalServerStatus) {
        _status.value = newStatus
    }

    private val _serverSession = MutableStateFlow<LocalPtySession?>(null)
    val serverSession: StateFlow<LocalPtySession?> = _serverSession.asStateFlow()

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var exitObserverJob: Job? = null
    private var activeJob: Job? = null

    // Track whether auto-start has already run during the lifetime of this app process
    private var hasInitialAutoStarted = false

    private const val PREFS_NAME = "local_server_prefs"
    private const val KEY_BRIDGE_LAST_UPDATE_TIME = "bridge_last_update_time"

    fun hasServerScript(context: Context): Boolean {
        val binBridge = File(LocalEnvironmentManager.getBinDir(context), "agy_ide_bridge")
        if (binBridge.exists()) return true
        return try {
            context.assets.list("bin")?.contains("agy_ide_bridge") == true
        } catch (_: Exception) {
            false
        }
    }

    /** The IDE bridge is shipped xz-compressed (see compressAgyIdeBridge in app/build.gradle.kts). */
    private const val BRIDGE_ASSET = "agy_ide_bridge.xz"

    /** Opens assets/bin/[assetName], decompressing it on the fly when it is an .xz file. */
    private fun openBinAsset(context: Context, assetName: String): java.io.InputStream {
        val raw = context.assets.open("bin/$assetName")
        return if (assetName.endsWith(".xz")) org.tukaani.xz.XZInputStream(java.io.BufferedInputStream(raw, 64 * 1024)) else raw
    }

    private fun extractAssetToBin(
        context: Context,
        binDir: File,
        assetName: String,
        targetFileName: String = assetName.removeSuffix(".xz")
    ) {
        val targetFile = File(binDir, targetFileName)
        openBinAsset(context, assetName).use { input ->
            val tempFile = File(binDir, "$targetFileName.tmp")
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
            tempFile.setExecutable(true, false)
            tempFile.setReadable(true, false)
            if (targetFile.exists()) {
                targetFile.delete()
            }
            tempFile.renameTo(targetFile)
            targetFile.setExecutable(true, false)
            targetFile.setReadable(true, false)
        }
    }

    private fun ensureBridgeBinary(context: Context) {
        val binDir = LocalEnvironmentManager.getBinDir(context)
        val bridgeFile = File(binDir, "agy_ide_bridge")
        val backupFile = File(binDir, "backup_bootstrap")
        val backupChatsFile = File(binDir, "backup_chats")
        val restoreChatsFile = File(binDir, "restore_chats")
        val backupProjectsFile = File(binDir, "backup_projects")
        val restoreProjectsFile = File(binDir, "restore_projects")
        val backupProotUbuntuFile = File(binDir, "backup_proot_ubuntu")
        val gitCloneFile = File(binDir, "git_clone")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSavedUpdate = prefs.getLong(KEY_BRIDGE_LAST_UPDATE_TIME, 0L)
        val packageInfo = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        } catch (_: Exception) {
            null
        }
        val currentAppUpdateTime = packageInfo?.lastUpdateTime ?: 0L

        val needsCopy =
            !bridgeFile.exists() || !backupFile.exists() || !backupChatsFile.exists() || !restoreChatsFile.exists() || !backupProjectsFile.exists() || !restoreProjectsFile.exists() || !backupProotUbuntuFile.exists() || !gitCloneFile.exists() || (currentAppUpdateTime > 0L && currentAppUpdateTime != lastSavedUpdate)

        if (needsCopy) {
            try {
                if (!binDir.exists()) {
                    binDir.mkdirs()
                }
                try {
                    val bridgeAsset = if (runCatching { context.assets.open("bin/$BRIDGE_ASSET").close() }.isSuccess) BRIDGE_ASSET else "agy_ide_bridge"
                    extractAssetToBin(context, binDir, bridgeAsset, "agy_ide_bridge")
                } catch (e: Exception) {
                    Log.w(TAG, "agy_ide_bridge asset not extracted", e)
                }
                try {
                    extractAssetToBin(context, binDir, "backup_bootstrap")
                } catch (e: Exception) {
                    Log.w(TAG, "backup_bootstrap asset not extracted", e)
                }
                try {
                    extractAssetToBin(context, binDir, "backup_chats")
                } catch (e: Exception) {
                    Log.w(TAG, "backup_chats asset not extracted", e)
                }
                try {
                    extractAssetToBin(context, binDir, "restore_chats")
                } catch (e: Exception) {
                    Log.w(TAG, "restore_chats asset not extracted", e)
                }
                try {
                    extractAssetToBin(context, binDir, "backup_projects")
                } catch (e: Exception) {
                    Log.w(TAG, "backup_projects asset not extracted", e)
                }
                try {
                    extractAssetToBin(context, binDir, "restore_projects")
                } catch (e: Exception) {
                    Log.w(TAG, "restore_projects asset not extracted", e)
                }
                try {
                    extractAssetToBin(context, binDir, "backup_proot_ubuntu")
                } catch (e: Exception) {
                    Log.w(TAG, "backup_proot_ubuntu asset not extracted", e)
                }
                try {
                    extractAssetToBin(context, binDir, "git_clone")
                } catch (e: Exception) {
                    Log.w(TAG, "git_clone asset not extracted", e)
                }

                // Also copy agy_ide_bridge to public Downloads folder for user access
                saveBridgeToDownloads(context)

                if (currentAppUpdateTime > 0L) {
                    prefs.edit().putLong(KEY_BRIDGE_LAST_UPDATE_TIME, currentAppUpdateTime).apply()
                }
                Log.d(TAG, "[ServerManager] Successfully extracted assets to ${binDir.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "[ServerManager] Failed to extract assets from bin/", e)
            }
        }
    }

    /**
     * Copies agy_ide_bridge executable to the device's public Download folder.
     */
    fun saveBridgeToDownloads(context: Context): File? {
        return try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists()) downloadDir.mkdirs()
            val destFile = File(downloadDir, "agy_ide_bridge")
            val binDir = LocalEnvironmentManager.getBinDir(context)
            val bridgeFile = File(binDir, "agy_ide_bridge")

            if (bridgeFile.exists() && bridgeFile.length() > 0) {
                bridgeFile.copyTo(destFile, overwrite = true)
            } else {
                context.assets.open("bin/agy_ide_bridge").use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            destFile.setExecutable(true, false)
            destFile.setReadable(true, false)
            Log.d(TAG, "[ServerManager] agy_ide_bridge saved to Downloads: ${destFile.absolutePath}")
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "[ServerManager] Failed to save agy_ide_bridge to Downloads", e)
            null
        }
    }

    private fun isBinaryAvailable(context: Context, path: String): Boolean {
        if (path.isBlank()) return false
        val homeDir = LocalEnvironmentManager.getHomeDir(context)
        val candidate = when {
            path.startsWith("~/") -> File(homeDir, path.removePrefix("~/"))
            path.startsWith("~") -> File(homeDir, path.removePrefix("~"))
            path.startsWith("/") -> File(path)
            else -> File(homeDir, path)
        }
        return candidate.exists() && candidate.isFile
    }

    private fun resolveServerCommand(context: Context): String? {
        val authPrefs = AuthPreferences(context)
        val binBridge = File(LocalEnvironmentManager.getBinDir(context), "agy_ide_bridge")
        val bridgeCmd = if (binBridge.exists()) {
            binBridge.absolutePath
        } else {
            authPrefs.getAgyBridgeBinaryPathSync().trim().ifBlank { authPrefs.getDefaultBridgeBinaryPath() }
        }
        val agyCmd = authPrefs.getAgyBinaryPathSync().trim()

        val token = authPrefs.getSecurityTokenSync()
        val bridgePort = try {
            java.net.URI(AuthPreferences.currentBridgeHttpUrl).port.takeIf { it > 0 } ?: com.example.gemini.BuildConfig.BRIDGE_PORT
        } catch (_: Exception) {
            com.example.gemini.BuildConfig.BRIDGE_PORT
        }
        val hubPort = try {
            java.net.URI(AuthPreferences.currentHubUrl).port.takeIf { it > 0 } ?: com.example.gemini.BuildConfig.HUB_PORT
        } catch (_: Exception) {
            com.example.gemini.BuildConfig.HUB_PORT
        }
        val tzOffset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000

        // Only pass --bin if the binary actually exists on disk.
        // If it doesn't exist yet, omit --bin so agy_ide_bridge can use auto-discovery
        // and run gracefully without crashing.
        val binParam = if (agyCmd.isNotBlank() && isBinaryAvailable(context, agyCmd)) {
            " --bin \"$agyCmd\""
        } else {
            ""
        }

        // Run bridge for Antigravity language server supervisor
        return "$bridgeCmd -f --token $token -p $bridgePort --hub-port $hubPort --tz-offset $tzOffset$binParam"
    }

    /**
     * Clear terminal transcript logs upon user request
     */
    fun clearLogs() {
        val session = _serverSession.value ?: return
        try {
            session.terminalSession.emulator?.screen?.clearTranscript()
            session.terminalSession.emulator?.reset()
            session.notifyTextChanged()
        } catch (_: Exception) {
        }
    }

    /**
     * Immediate force kill for service notification exit (closes session and finishes processes)
     */
    fun forceKillAll() {
        exitObserverJob?.cancel()
        exitObserverJob = null
        activeJob?.cancel()
        activeJob = null

        val session = _serverSession.value
        if (session != null) {
            try {
                session.write("\u0003")
                session.terminalSession.finishIfRunning()
                session.close()
            } catch (_: Exception) {
            }
        }

        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -9 -f agy_ide_bridge; pkill -9 -f gemini-server; pkill -9 -f 'server -f'; pkill -9 -f 'agy '")).waitFor()
        } catch (_: Exception) {
        }

        _serverSession.value = null
        _status.value = LocalServerStatus.Stopped(exitCode = 0)
    }

    @Synchronized
    fun resetAutoStartFlag() {
        hasInitialAutoStarted = false
    }

    @Synchronized
    fun autoStartOnAppLaunch(context: Context) {
        if (hasInitialAutoStarted) {
            Log.d(
                TAG,
                "[ServerManager] Initial auto-start already executed for this process, skipping autoStartOnAppLaunch"
            )
            return
        }
        if (!LocalEnvironmentManager.isInstalled(context) && !hasServerScript(context)) {
            Log.d(TAG, "[ServerManager] Bootstrap not installed yet, deferring auto-start until installation completes")
            return
        }
        if (!hasServerScript(context)) {
            Log.d(TAG, "[ServerManager] No server executable found in home directory, skipping autoStartOnAppLaunch")
            return
        }
        if (!com.example.gemini.data.agent.AgentPreferences.hasChosenSync(context)) {
            // the server starts with the agent flags: wait for the first-launch agent choice
            Log.d(TAG, "[ServerManager] Agents not chosen yet, deferring auto-start until the choice is made")
            return
        }
        hasInitialAutoStarted = true
        startServer(context, forceRestart = false)
    }

    fun startServer(context: Context, forceRestart: Boolean = false) {
        if (hasServerScript(context)) {
            hasInitialAutoStarted = true
        }
        activeJob?.cancel()
        activeJob = managerScope.launch {
            startServerInternal(context, forceRestart)
        }
    }

    private suspend fun startServerInternal(context: Context, forceRestart: Boolean) {
        val current = _status.value
        var existingSession = _serverSession.value

        if (!forceRestart && existingSession != null && !existingSession.isExited.value && (current is LocalServerStatus.Running || current is LocalServerStatus.Starting)) {
            Log.d(TAG, "[ServerManager] Server session already running, skipping start")
            return
        }

        // a restart always starts the bridge in a new session (see retireServerSession)
        if (forceRestart && existingSession != null) {
            retireServerSession(existingSession)
            existingSession = null
        }

        if (!LocalEnvironmentManager.isInstalled(context) && !hasServerScript(context)) {
            Log.d(TAG, "[ServerManager] Bootstrap not installed, skipping server start")
            return
        }

        val appContext = context.applicationContext
        ensureBridgeBinary(appContext)
        LocalEnvironmentManager.ensureGlibcEnvironment(appContext)

        val command = resolveServerCommand(appContext)
        if (command == null) {
            Log.d(TAG, "[ServerManager] No start or server executable found in home directory")
            return
        }

        _status.value = LocalServerStatus.Starting

        // If a persistent terminal session is already alive, reuse it and execute command inside it
        if (existingSession != null && !existingSession.isExited.value) {
            Log.d(TAG, "[ServerManager] Reusing active persistent terminal session to launch: $command")
            existingSession.write("\u0003")
            delay(150)
            existingSession.write("cd \$HOME && $command\n")
            _status.value = LocalServerStatus.Running()
            return
        }

        withContext(Dispatchers.IO) {
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -9 -f agy_ide_bridge; pkill -9 -f 'agy '")).waitFor()
            } catch (_: Exception) {}
        }

        Log.d(
            TAG,
            "[ServerManager] Spawning dedicated persistent terminal session for server runner with initialCommand: $command"
        )

        val session = withContext(Dispatchers.Main) {
            LocalPtySession(
                id = "server-runner-pty",
                initialTitle = "Server Runner",
                context = appContext,
                isSsh = false,
                initialCommand = command,
                execShellAfterCommand = false,
                forceShell = "bash",
                initialCols = 80,
                initialRows = 24
            )
        }

        _serverSession.value = session
        _status.value = LocalServerStatus.Running()

        exitObserverJob?.cancel()
        exitObserverJob = managerScope.launch {
            session.isExited.collect { isExited ->
                if (isExited) {
                    _status.value = LocalServerStatus.Stopped(exitCode = 0)
                }
            }
        }
    }

    /**
     * A restart starts the bridge in a new terminal session instead of typing the command into the old one. The old
     * session can still be running the bridge while it stops (it drops the app's connection first, then stops the
     * agents and the hub), and the Ctrl+C sent to stop it also ends zsh's `-c` line, so its `exec login` never ran:
     * the session closed and the typed start command was lost (the server stopped and did not come back).
     * Waits until the old bridge is gone (its session ended or its port refuses connections, 15 s at most), then
     * closes that session.
     */
    private suspend fun retireServerSession(session: LocalPtySession) {
        _status.value = LocalServerStatus.Starting
        // its exit must not mark the new server stopped
        exitObserverJob?.cancel()
        exitObserverJob = null
        val healthUrl = AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/health"
        withTimeoutOrNull(15_000L) {
            while (!session.isExited.value && bridgeStillUp(healthUrl)) delay(250)
        }
        if (!session.isExited.value) session.close()
    }

    /** False once the bridge's port refuses connections; a slow answer while it stops still counts as up. */
    private fun bridgeStillUp(healthUrl: String): Boolean = try {
        AgyBridgeService.instance.fastClient.newCall(Request.Builder().url(healthUrl).get().build()).execute().close()
        true
    } catch (_: java.net.ConnectException) {
        false
    } catch (_: Exception) {
        true
    }

    fun stopServer() {
        activeJob?.cancel()
        activeJob = managerScope.launch {
            stopServerInternal()
        }
    }

    private suspend fun stopServerInternal() {
        _status.value = LocalServerStatus.Stopping

        val bridgeUrl = AuthPreferences.currentBridgeHttpUrl
        val session = _serverSession.value

        // 1. Send graceful shutdown HTTP request to the Go IDE bridge
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("$bridgeUrl/api/shutdown")
                    .post("{}".toRequestBody(null))
                    .build()
                AgyBridgeService.instance.fastClient.newCall(req).execute().close()
            } catch (_: Exception) {
            }
        }

        // 2. Also send Ctrl+C to persistent PTY terminal session
        if (session != null && !session.isExited.value) {
            try {
                session.write("\u0003")
            } catch (_: Exception) {
            }
        }

        // 3. Send pkill to terminate any child tree processes (agy daemon, child node processes, bridge)
        if (session != null && !session.isExited.value) {
            try {
                session.write("pkill -9 -f agy_ide_bridge; pkill -9 -f gemini-server; pkill -9 -f 'server -f'; pkill -9 -f 'agy '\n")
            } catch (_: Exception) {
            }
        }
        withContext(Dispatchers.IO) {
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -9 -f agy_ide_bridge; pkill -9 -f gemini-server; pkill -9 -f 'server -f'; pkill -9 -f 'agy '")).waitFor()
            } catch (_: Exception) {}
        }

        // 4. Wait for SystemConnectionState to transition to Offline
        withTimeoutOrNull(2500L) {
            while (AgyBridgeService.instance.systemConnectionState.value !is com.example.gemini.data.remote.SystemConnectionState.Offline) {
                delay(150)
            }
        }

        AgyBridgeService.instance.notifyLocalStopped()
        // Retain _serverSession.value so terminal logs and history remain fully visible in the dialog
        _status.value = LocalServerStatus.Stopped(exitCode = 0)
    }

    /** Restarts the server and waits until it is starting again (agent selection changed). */
    suspend fun restartServerAndWait(context: Context) {
        hasInitialAutoStarted = true
        activeJob?.cancel()
        _status.value = LocalServerStatus.Starting
        stopServerInternal()
        delay(200)
        startServerInternal(context, forceRestart = true)
    }

    /**
     * Stops the server and ends its terminal session for good (the app restarts next, e.g. after the agent
     * selection changed): nothing of the old server may keep running.
     */
    suspend fun stopServerCompletely() {
        hasInitialAutoStarted = true
        activeJob?.cancel()
        stopServerInternal()
        // let the bridge finish stopping its children (the AGY hub) before the session is closed
        withTimeoutOrNull(5_000L) {
            while (AgyBridgeService.instance.systemConnectionState.value !is com.example.gemini.data.remote.SystemConnectionState.Offline) {
                delay(150)
            }
        }
        forceKillAll()
    }

    fun restartServer(context: Context) {
        hasInitialAutoStarted = true
        activeJob?.cancel()
        activeJob = managerScope.launch {
            _status.value = LocalServerStatus.Starting
            stopServerInternal()
            delay(200)
            startServerInternal(context, forceRestart = true)
        }
    }
}
