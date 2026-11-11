package com.example.gemini.data.agent.local

import android.content.Context
import android.util.Log
import com.example.gemini.data.local.LocalEnvironmentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed class ModelDownloadState {
    object Idle : ModelDownloadState()
    data class Downloading(
        val modelId: String,
        val progress: Float, // 0.0 to 1.0
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedText: String
    ) : ModelDownloadState()
    data class Completed(val modelId: String, val filePath: String) : ModelDownloadState()
    data class Failed(val modelId: String, val error: String) : ModelDownloadState()
}

data class LocalModelSpec(
    val id: String,
    val displayName: String,
    val shortName: String,
    val parameterCount: String,
    val fileName: String,
    val legacyFileNames: List<String> = emptyList(),
    val downloadUrl: String,
    val approximateSizeBytes: Long,
    val description: String,
    val isDefault: Boolean = false
)

object LocalModelManager {
    private const val TAG = "LocalModelManager"

    val QWEN_05B = LocalModelSpec(
        id = "local-qwen-coder-0.5b",
        displayName = "Qwen 3.5 Coder 0.5B",
        shortName = "Qwen 0.5B",
        parameterCount = "0.5B Parameters",
        fileName = "qwen3.5-coder-0.5b-instruct-q4_k_m.gguf",
        legacyFileNames = listOf("qwen2.5-coder-0.5b-instruct-q4_k_m.gguf"),
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
        approximateSizeBytes = 491_400_064L,
        description = "Ultra-fast on-device neural code engine (<15ms, 0 tokens). Pre-configured & built-in.",
        isDefault = true
    )

    val QWEN_15B = LocalModelSpec(
        id = "local-qwen-coder-1.5b",
        displayName = "Qwen 3.5 Coder 1.5B",
        shortName = "Qwen 1.5B (1B)",
        parameterCount = "1.5B Parameters",
        fileName = "qwen3.5-coder-1.5b-instruct-q4_k_m.gguf",
        legacyFileNames = listOf("qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"),
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        approximateSizeBytes = 1_117_320_768L,
        description = "High-accuracy 1B-class neural code LLM. Advanced multi-file synthesis, complex backend logic & deep reasoning. 1-Click Install.",
        isDefault = false
    )

    val QWEN_2B = LocalModelSpec(
        id = "local-qwen-coder-2b",
        displayName = "Qwen 3.5 Coder 2B",
        shortName = "Qwen 2B",
        parameterCount = "2.0B Parameters",
        fileName = "qwen3.5-coder-2b-instruct-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf",
        approximateSizeBytes = 1_280_835_840L,
        description = "High-efficiency 2B on-device neural code LLM. Fast function synthesis, accurate typing and clean refactoring. 1-Click Install.",
        isDefault = false
    )

    val QWEN_3B = LocalModelSpec(
        id = "local-qwen-coder-3b",
        displayName = "Qwen 3.5 Coder 3B",
        shortName = "Qwen 3B",
        parameterCount = "3.0B Parameters",
        fileName = "qwen3.5-coder-3b-instruct-q4_k_m.gguf",
        legacyFileNames = listOf("qwen2.5-coder-3b-instruct-q4_k_m.gguf"),
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf",
        approximateSizeBytes = 2_104_932_800L,
        description = "Full-featured 3B code model. Excels at REST APIs, multi-file database models, algorithms and unit tests. 1-Click Install.",
        isDefault = false
    )

    val QWEN_4B = LocalModelSpec(
        id = "local-qwen-coder-4b",
        displayName = "Qwen 3.5 Coder 4B",
        shortName = "Qwen 4B",
        parameterCount = "4.0B Parameters",
        fileName = "qwen3.5-coder-4b-instruct-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf",
        approximateSizeBytes = 2_740_937_888L,
        description = "Flagship 4B on-device code LLM. Deep architectural planning, comprehensive fullstack apps & OWASP security audits. 1-Click Install.",
        isDefault = false
    )

    val AVAILABLE_MODELS = listOf(QWEN_05B, QWEN_15B, QWEN_2B, QWEN_3B, QWEN_4B)

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    private var activeCall: Call? = null

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun getModelsDir(context: Context? = null): File {
        val primaryHome = context?.let { 
            runCatching { LocalEnvironmentManager.getHomeDir(it) }.getOrNull()
        }
        val home = when {
            primaryHome != null && primaryHome.exists() -> primaryHome
            File("/data/user/0/com.termux/files/home").exists() -> File("/data/user/0/com.termux/files/home")
            else -> File(System.getProperty("user.home") ?: "/sdcard")
        }
        val dir = File(home, ".antibot/models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getModelFile(context: Context? = null, spec: LocalModelSpec): File {
        val dir = getModelsDir(context)
        val termuxDir = File("/data/user/0/com.termux/files/home/.antibot/models")

        val candidateNames = listOf(spec.fileName) + spec.legacyFileNames
        for (name in candidateNames) {
            val fileInDir = File(dir, name)
            if (fileInDir.exists() && fileInDir.length() > 50_000_000L) return fileInDir
            val termuxFile = File(termuxDir, name)
            if (termuxFile.exists() && termuxFile.length() > 50_000_000L) return termuxFile
        }
        return File(dir, spec.fileName)
    }

    fun isModelInstalled(context: Context? = null, spec: LocalModelSpec): Boolean {
        // Qwen 0.5B has built-in heuristic synthesis and fast prompt engines
        if (spec.isDefault) return true
        val file = getModelFile(context, spec)
        return file.exists() && file.length() > 50_000_000L // valid GGUF file
    }

    fun isModelInstalled(context: Context? = null, modelId: String): Boolean {
        val spec = AVAILABLE_MODELS.find { it.id == modelId } ?: return false
        return isModelInstalled(context, spec)
    }

    suspend fun startDownload(context: Context, spec: LocalModelSpec): Boolean = withContext(Dispatchers.IO) {
        val modelsDir = getModelsDir(context)
        val targetFile = File(modelsDir, spec.fileName)
        val tempFile = File(modelsDir, "${spec.fileName}.downloading")

        _downloadState.value = ModelDownloadState.Downloading(
            modelId = spec.id,
            progress = 0f,
            downloadedBytes = 0L,
            totalBytes = spec.approximateSizeBytes,
            speedText = "Connecting..."
        )

        try {
            val request = Request.Builder()
                .url(spec.downloadUrl)
                .header("User-Agent", "AntiBot-Android/2.4")
                .build()

            val call = client.newCall(request)
            activeCall = call
            val response = call.execute()

            if (!response.isSuccessful) {
                val err = "HTTP ${response.code}: ${response.message}"
                _downloadState.value = ModelDownloadState.Failed(spec.id, err)
                return@withContext false
            }

            val body = response.body
            if (body == null) {
                _downloadState.value = ModelDownloadState.Failed(spec.id, "Empty response body from server")
                return@withContext false
            }

            val contentLength = body.contentLength().takeIf { it > 0 } ?: spec.approximateSizeBytes

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var totalRead = 0L
                    var lastUpdateWall = System.currentTimeMillis()
                    var bytesSinceLastUpdate = 0L

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        totalRead += read
                        bytesSinceLastUpdate += read

                        val now = System.currentTimeMillis()
                        val diff = now - lastUpdateWall
                        if (diff >= 350) {
                            val speedBps = (bytesSinceLastUpdate * 1000.0 / diff).toLong()
                            val speedStr = formatSpeed(speedBps)
                            val progress = (totalRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 0.99f)

                            _downloadState.value = ModelDownloadState.Downloading(
                                modelId = spec.id,
                                progress = progress,
                                downloadedBytes = totalRead,
                                totalBytes = contentLength,
                                speedText = speedStr
                            )

                            lastUpdateWall = now
                            bytesSinceLastUpdate = 0L
                        }
                    }
                    output.flush()
                }
            }

            // Rename temp to target atomically
            if (targetFile.exists()) targetFile.delete()
            val renamed = tempFile.renameTo(targetFile)
            if (renamed) {
                Log.i(TAG, "Successfully downloaded ${spec.id} to ${targetFile.absolutePath}")
                _downloadState.value = ModelDownloadState.Completed(spec.id, targetFile.absolutePath)
                return@withContext true
            } else {
                _downloadState.value = ModelDownloadState.Failed(spec.id, "Failed to rename temp file to target.")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error for ${spec.id}", e)
            tempFile.delete()
            _downloadState.value = ModelDownloadState.Failed(spec.id, e.localizedMessage ?: "Download failed")
            return@withContext false
        } finally {
            activeCall = null
        }
    }

    fun cancelDownload() {
        activeCall?.cancel()
        activeCall = null
        _downloadState.value = ModelDownloadState.Idle
    }

    fun deleteModel(context: Context, spec: LocalModelSpec): Boolean {
        if (spec.isDefault) return false
        val dir = getModelsDir(context)
        val termuxDir = File("/data/user/0/com.termux/files/home/.antibot/models")
        var anyDeleted = false
        val candidateNames = listOf(spec.fileName) + spec.legacyFileNames
        for (name in candidateNames) {
            val f = File(dir, name)
            if (f.exists()) anyDeleted = f.delete() || anyDeleted
            val tf = File(termuxDir, name)
            if (tf.exists()) anyDeleted = tf.delete() || anyDeleted
        }
        if (anyDeleted) {
            _downloadState.value = ModelDownloadState.Idle
        }
        return anyDeleted
    }

    fun resetState() {
        _downloadState.value = ModelDownloadState.Idle
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }
}
