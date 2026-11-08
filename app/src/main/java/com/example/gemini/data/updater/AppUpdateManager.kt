package com.example.gemini.data.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.system.Os
import android.util.Log
import androidx.core.content.FileProvider
import com.example.gemini.BuildConfig
import com.example.gemini.data.local.LocalEnvironmentManager
import com.example.gemini.data.local.LocalServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "AppUpdateManager"
private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/onebotyt/Antibot/releases/latest"
private const val DEFAULT_VERSION_URL = "https://raw.githubusercontent.com/onebotyt/Antibot/main/version.json"

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val currentVersionCode: Int,
    val currentVersionName: String,
    val packageName: String,
    val downloadUrl: String,
    val bridgeBinaryUrl: String? = null,
    val changelog: List<String>,
    val isCritical: Boolean = false,
    val releaseName: String = "",
    val publishedAt: String = ""
)

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
    data class Completed(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class AppUpdateManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Checks if a new version is available by querying GitHub Releases API directly,
     * with graceful fallback to raw version.json.
     */
    suspend fun checkForUpdates(
        versionJsonUrl: String = DEFAULT_VERSION_URL,
        forceCheck: Boolean = false
    ): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            // 1. Try GitHub Releases API first (detects every new build published to GitHub)
            val githubResult = checkGitHubReleases()
            if (githubResult.isSuccess && githubResult.getOrNull() != null) {
                return@withContext githubResult
            }

            // 2. Fallback to raw version.json if GitHub Releases returned null or was rate-limited
            checkVersionJson(versionJsonUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun checkGitHubReleases(): Result<AppUpdateInfo?> {
        try {
            val request = Request.Builder()
                .url(GITHUB_RELEASES_URL)
                .header("User-Agent", "AntiBot-Updater/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/vnd.github.v3+json")
                .header("Cache-Control", "no-cache")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return Result.failure(Exception("GitHub API HTTP ${response.code}: ${response.message}"))
            }

            val body = response.body?.string() ?: return Result.failure(Exception("Empty release response"))
            val json = JSONObject(body)

            val tagName = json.optString("tag_name", "").trim()
            val releaseName = json.optString("name", tagName)
            val bodyText = json.optString("body", "")
            val publishedAt = json.optString("published_at", "")

            val remoteVersionName = tagName.removePrefix("v").trim()
            if (remoteVersionName.isBlank()) return Result.success(null)

            val assets = json.optJSONArray("assets") ?: JSONArray()
            var apkDownloadUrl: String? = null
            var bridgeDownloadUrl: String? = null

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val assetName = asset.optString("name", "")
                val assetUrl = asset.optString("browser_download_url", "")

                if (assetName.endsWith(".apk", ignoreCase = true)) {
                    if (assetName.contains("termux", ignoreCase = true) || apkDownloadUrl == null) {
                        apkDownloadUrl = assetUrl
                    }
                } else if (assetName.contains("agy_ide_bridge", ignoreCase = true)) {
                    bridgeDownloadUrl = assetUrl
                }
            }

            // Fallback APK URL if not in assets list
            if (apkDownloadUrl.isNullOrBlank()) {
                apkDownloadUrl = "https://github.com/onebotyt/Antibot/releases/download/$tagName/antibot-$tagName.apk"
            }

            val isNewer = isRemoteVersionNewer(remoteVersionName, BuildConfig.VERSION_NAME)
            if (isNewer) {
                val changelog = extractChangelog(bodyText)
                return Result.success(
                    AppUpdateInfo(
                        versionCode = estimateVersionCode(remoteVersionName),
                        versionName = remoteVersionName,
                        currentVersionCode = BuildConfig.VERSION_CODE,
                        currentVersionName = BuildConfig.VERSION_NAME,
                        packageName = context.packageName,
                        downloadUrl = apkDownloadUrl,
                        bridgeBinaryUrl = bridgeDownloadUrl,
                        changelog = changelog,
                        isCritical = false,
                        releaseName = releaseName,
                        publishedAt = publishedAt
                    )
                )
            }
            return Result.success(null)
        } catch (e: Exception) {
            Log.w(TAG, "GitHub Releases check failed, falling back to version.json: ${e.message}")
            return Result.failure(e)
        }
    }

    private fun checkVersionJson(url: String = DEFAULT_VERSION_URL): Result<AppUpdateInfo?> {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "AntiBot-Updater/${BuildConfig.VERSION_NAME}")
                .header("Cache-Control", "no-cache")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return Result.failure(Exception("Failed to fetch version.json (HTTP ${response.code})"))
            }

            val body = response.body?.string() ?: return Result.failure(Exception("Empty version.json"))
            val json = JSONObject(body)

            val remoteVersionCode = json.optInt("version_code", 0)
            val remoteVersionName = json.optString("version_name", "1.0.0").removePrefix("v").trim()
            val minSupportedCode = json.optInt("min_supported_version_code", 0)
            val isCritical = json.optBoolean("is_critical", false) || (BuildConfig.VERSION_CODE < minSupportedCode)

            val changelog = when (val raw = json.opt("changelog")) {
                is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
                is String -> listOf(raw)
                else -> listOf("General improvements and updates from GitHub.")
            }

            val downloadUrl = "https://github.com/onebotyt/Antibot/releases/download/v$remoteVersionName/antibot-v$remoteVersionName.apk"
            val isNewer = isRemoteVersionNewer(remoteVersionName, BuildConfig.VERSION_NAME) || (remoteVersionCode > BuildConfig.VERSION_CODE)

            if (isNewer) {
                return Result.success(
                    AppUpdateInfo(
                        versionCode = if (remoteVersionCode > 0) remoteVersionCode else estimateVersionCode(remoteVersionName),
                        versionName = remoteVersionName,
                        currentVersionCode = BuildConfig.VERSION_CODE,
                        currentVersionName = BuildConfig.VERSION_NAME,
                        packageName = context.packageName,
                        downloadUrl = downloadUrl,
                        bridgeBinaryUrl = "https://github.com/onebotyt/Antibot/releases/download/v$remoteVersionName/agy_ide_bridge",
                        changelog = changelog,
                        isCritical = isCritical
                    )
                )
            }
            return Result.success(null)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Downloads and dynamically updates the agy_ide_bridge binary and restarts
     * the local server without requiring an APK installation.
     */
    suspend fun updateBridgeBinary(
        bridgeUrl: String,
        onProgress: (Float, Long, Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(bridgeUrl)
                .header("User-Agent", "AntiBot-Updater/${BuildConfig.VERSION_NAME}")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val hint = when (response.code) {
                    404, 504 -> " (Release assets still building or publishing on GitHub. Retry in a moment)"
                    403 -> " (GitHub rate limit reached, please try again shortly)"
                    else -> ""
                }
                return@withContext Result.failure(Exception("Failed to download daemon binary: HTTP ${response.code}$hint"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty daemon binary response"))
            val totalBytes = body.contentLength()
            val tempFile = File(context.cacheDir, "agy_ide_bridge_new.tmp")

            body.byteStream().use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    var downloaded = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        val progress = if (totalBytes > 0) (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
                        withContext(Dispatchers.Main) {
                            onProgress(progress, downloaded, totalBytes)
                        }
                    }
                    output.flush()
                }
            }

            if (tempFile.length() < 100_000) {
                tempFile.delete()
                return@withContext Result.failure(Exception("Downloaded bridge binary is corrupt or too small (${tempFile.length()} bytes)"))
            }

            // Set executable permissions
            tempFile.setExecutable(true, false)
            tempFile.setReadable(true, false)
            try { Os.chmod(tempFile.absolutePath, 493) } catch (_: Exception) {}

            // Target destination in binDir
            val binDir = LocalEnvironmentManager.getBinDir(context)
            binDir.mkdirs()
            val targetBridge = File(binDir, "agy_ide_bridge")

            if (targetBridge.exists()) {
                targetBridge.delete()
            }
            if (!tempFile.renameTo(targetBridge)) {
                tempFile.copyTo(targetBridge, overwrite = true)
                tempFile.delete()
            }

            targetBridge.setExecutable(true, false)
            targetBridge.setReadable(true, false)
            try { Os.chmod(targetBridge.absolutePath, 493) } catch (_: Exception) {}

            // Save copy to Downloads
            try {
                LocalServerManager.saveBridgeToDownloads(context)
            } catch (_: Exception) {}

            // Restart the server with new binary
            try {
                LocalServerManager.restartServer(context)
            } catch (_: Exception) {}

            Result.success(targetBridge)
        } catch (e: Exception) {
            Log.e(TAG, "Dynamic daemon update error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads the APK file to cache directory with live progress updates.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (Float, Long, Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "AntiBot-Updater/${BuildConfig.VERSION_NAME}")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val hint = when (response.code) {
                    404, 504 -> " (APK still building on GitHub Actions. Please retry shortly)"
                    403 -> " (GitHub rate limit reached, please try again shortly)"
                    else -> ""
                }
                return@withContext Result.failure(Exception("Download failed: HTTP ${response.code}$hint"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty download body"))
            val totalBytes = body.contentLength()

            val targetDir = context.externalCacheDir ?: context.cacheDir
            val targetFile = File(targetDir, "antibot_update_${System.currentTimeMillis()}.apk")

            body.byteStream().use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    var downloadedBytes = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val progress = if (totalBytes > 0) {
                            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        withContext(Dispatchers.Main) {
                            onProgress(progress, downloadedBytes, totalBytes)
                        }
                    }
                    output.flush()
                }
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download APK error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Launches the system Package Installer using FileProvider.
     */
    fun installApk(apkFile: File): Boolean {
        return try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Android 8.0+ Unknown sources permission check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(manageIntent)
                    return false
                }
            }

            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
            false
        }
    }

    companion object {
        fun isRemoteVersionNewer(remote: String, local: String): Boolean {
            val rParts = remote.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
            val lParts = local.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(rParts.size, lParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val l = lParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
            return false
        }

        private fun estimateVersionCode(versionName: String): Int {
            val parts = versionName.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
            val patch = parts.getOrNull(2) ?: 0
            return 120 + patch
        }

        private fun extractChangelog(bodyText: String): List<String> {
            val lines = bodyText.lines()
                .map { it.trim().removePrefix("-").removePrefix("*").trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("```") }
            return if (lines.isNotEmpty()) lines.take(8) else listOf("Latest updates and improvements from GitHub.")
        }
    }
}
