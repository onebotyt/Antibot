package com.example.gemini.data.remote.inspector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class NativeNetworkMetrics(
    val totalBytesSent: Long,
    val totalBytesReceived: Long,
    val activeConnections: Int,
    val requestCount: Long,
    val responseCount: Long,
    val errorCount: Long,
    val uploadBps: Float,
    val downloadBps: Float
)

/**
 * Thread-safe singleton manager for capturing and inspecting real-time network activity.
 * Designed with a zero-cost fast-path when disabled to ensure 100% native performance.
 */
object NetworkInspectorManager {
    private const val MAX_LOG_CAPACITY = 1000

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    @Volatile
    var isEnabled: Boolean = false

    @Volatile
    var isFloatingBubbleEnabled: Boolean = false

    private val lock = Any()
    private val buffer = ArrayDeque<NetworkLogEntry>(MAX_LOG_CAPACITY)

    private val _logs = MutableStateFlow<List<NetworkLogEntry>>(emptyList())
    val logs: StateFlow<List<NetworkLogEntry>> = _logs.asStateFlow()

    private val _activeCallsCount = MutableStateFlow(0)
    val activeCallsCount: StateFlow<Int> = _activeCallsCount.asStateFlow()

    private val _activeStreamsCount = MutableStateFlow(0)
    val activeStreamsCount: StateFlow<Int> = _activeStreamsCount.asStateFlow()

    fun recordStart(entry: NetworkLogEntry) {
        if (!isEnabled) return
        synchronized(lock) {
            if (buffer.size >= MAX_LOG_CAPACITY) {
                buffer.removeLast()
            }
            buffer.addFirst(entry)
            _logs.value = buffer.toList()
            recomputeCounts()
        }
    }

    fun recordComplete(
        id: String,
        statusCode: Int,
        grpcStatus: String?,
        durationMs: Long
    ) {
        if (!isEnabled) return
        var entryToSync: NetworkLogEntry? = null
        synchronized(lock) {
            val idx = buffer.indexOfFirst { it.id == id }
            if (idx != -1) {
                val existing = buffer[idx]
                val updated = existing.copy(
                    statusCode = statusCode,
                    grpcStatus = grpcStatus,
                    durationMs = durationMs
                )
                buffer[idx] = updated
                _logs.value = buffer.toList()
                recomputeCounts()
                entryToSync = updated
            }
        }
        entryToSync?.let { syncToRustBridge(it) }
    }

    fun recordError(id: String, errorMsg: String, durationMs: Long) {
        if (!isEnabled) return
        var entryToSync: NetworkLogEntry? = null
        synchronized(lock) {
            val idx = buffer.indexOfFirst { it.id == id }
            if (idx != -1) {
                val existing = buffer[idx]
                val updated = existing.copy(
                    durationMs = durationMs,
                    error = errorMsg
                )
                buffer[idx] = updated
                _logs.value = buffer.toList()
                recomputeCounts()
                entryToSync = updated
            }
        }
        entryToSync?.let { syncToRustBridge(it) }
    }

    private fun syncToRustBridge(entry: NetworkLogEntry) {
        ioScope.launch {
            try {
                val bridgeBase = com.example.gemini.data.preferences.AuthPreferences.currentBridgeHttpUrl.trimEnd('/')
                val payload = org.json.JSONObject().apply {
                    put("id", entry.id)
                    put("timestamp", entry.timestamp / 1000)
                    put("method", entry.method)
                    put("url", entry.url)
                    put("status_code", if (entry.statusCode > 0) entry.statusCode else 200)
                    put("duration_ms", entry.durationMs.coerceAtLeast(0L))
                    put("bytes_sent", 0)
                    put("bytes_received", 0)
                    put("client_ip", "127.0.0.1")
                    if (entry.error != null) {
                        put("error", entry.error)
                    }
                }.toString()

                val request = okhttp3.Request.Builder()
                    .url("$bridgeBase/api/network/record")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(request).execute().close()
            } catch (_: Exception) {}
        }
    }

    suspend fun fetchMetricsViaBridge(): NativeNetworkMetrics? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val bridgeBase = com.example.gemini.data.preferences.AuthPreferences.currentBridgeHttpUrl.trimEnd('/')
            val request = okhttp3.Request.Builder()
                .url("$bridgeBase/api/network/stats")
                .get()
                .build()
            val resp = http.newCall(request).execute()
            if (resp.isSuccessful) {
                val json = org.json.JSONObject(resp.body?.string() ?: "{}")
                return@withContext NativeNetworkMetrics(
                    totalBytesSent = json.optLong("total_bytes_sent"),
                    totalBytesReceived = json.optLong("total_bytes_received"),
                    activeConnections = json.optInt("active_connections"),
                    requestCount = json.optLong("request_count"),
                    responseCount = json.optLong("response_count"),
                    errorCount = json.optLong("error_count"),
                    uploadBps = json.optDouble("upload_bps", 0.0).toFloat(),
                    downloadBps = json.optDouble("download_bps", 0.0).toFloat()
                )
            }
        } catch (_: Exception) {}
        null
    }

    fun clearLogs() {
        synchronized(lock) {
            val activeEntries = buffer.filter { it.durationMs < 0 && it.error == null }
            buffer.clear()
            for (entry in activeEntries.reversed()) {
                buffer.addFirst(entry)
            }
            _logs.value = buffer.toList()
            recomputeCounts()
        }
    }

    private fun recomputeCounts() {
        var active = 0
        for (item in buffer) {
            if (item.durationMs < 0 && item.error == null) {
                active++
            }
        }
        _activeCallsCount.value = active
        _activeStreamsCount.value = active
    }
}
