package com.example.gemini.assistant.share

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap

data class SharedPayloadItem(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val contentProvider: () -> InputStream
)

/**
 * Embedded Zero-Install Local Web Drop server.
 * Enables any device (PC, iPhone, Android, Mac) on the same Wi-Fi to download files
 * from AntiBot or upload files to AntiBot via a web browser without installing any app.
 */
class WebDropServer private constructor(private val appContext: Context) {

    companion object {
        const val DEFAULT_PORT = 5234

        @Volatile
        private var instance: WebDropServer? = null

        fun getInstance(context: Context): WebDropServer {
            return instance ?: synchronized(this) {
                instance ?: WebDropServer(context.applicationContext).also { instance = it }
            }
        }

        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    if (networkInterface.isLoopback || !networkInterface.isUp) continue

                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: ""
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                                return ip
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            return "127.0.0.1"
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var actualPort = DEFAULT_PORT

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val sharedItems = ConcurrentHashMap<String, SharedPayloadItem>()

    private val _receivedFiles = MutableStateFlow<List<File>>(emptyList())
    val receivedFiles: StateFlow<List<File>> = _receivedFiles.asStateFlow()

    fun getWebUrl(): String {
        return "http://${getLocalIpAddress()}:$actualPort"
    }

    fun addItemToShare(item: SharedPayloadItem) {
        sharedItems[item.id] = item
    }

    fun removeItem(id: String) {
        sharedItems.remove(id)
    }

    fun clearSharedItems() {
        sharedItems.clear()
    }

    @Synchronized
    fun start(port: Int = DEFAULT_PORT): Boolean {
        if (_isRunning.value) return true

        try {
            serverSocket = ServerSocket(port).apply {
                reuseAddress = true
            }
            actualPort = port
        } catch (_: Exception) {
            try {
                // Try port 0 (dynamic available port)
                serverSocket = ServerSocket(0).apply {
                    reuseAddress = true
                }
                actualPort = serverSocket?.localPort ?: port
            } catch (_: Exception) {
                return false
            }
        }

        _isRunning.value = true
        startAcceptLoop()
        return true
    }

    @Synchronized
    fun stop() {
        _isRunning.value = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    private fun startAcceptLoop() {
        scope.launch {
            while (_isRunning.value && serverSocket != null) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    launch {
                        handleClient(socket)
                    }
                } catch (_: Exception) {
                    if (!_isRunning.value) break
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())

                val headerLine = readLine(input) ?: return
                val parts = headerLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0].uppercase()
                val path = parts[1]

                // Parse headers
                val headers = mutableMapOf<String, String>()
                var line = readLine(input)
                while (!line.isNullOrBlank()) {
                    val colonIdx = line.indexOf(':')
                    if (colonIdx > 0) {
                        val key = line.substring(0, colonIdx).trim().lowercase()
                        val value = line.substring(colonIdx + 1).trim()
                        headers[key] = value
                    }
                    line = readLine(input)
                }

                when {
                    method == "GET" && (path == "/" || path.startsWith("/index")) -> {
                        serveWebPortal(output)
                    }
                    method == "GET" && path.startsWith("/download") -> {
                        val id = UriQueryHelper.getQueryParam(path, "id")
                        val item = id?.let { sharedItems[it] }
                        if (item != null) {
                            serveFileDownload(output, item)
                        } else {
                            serveNotFound(output)
                        }
                    }
                    method == "POST" && path.startsWith("/upload") -> {
                        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                        val filename = headers["x-filename"] ?: "received_file_${System.currentTimeMillis()}.bin"
                        saveUploadedFile(input, contentLength, filename, output)
                    }
                    else -> {
                        serveNotFound(output)
                    }
                }
            }
        } catch (_: Exception) {
            // Connection closed or interrupted
        }
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var c = input.read()
        if (c == -1) return null
        while (c != -1 && c != '\n'.code) {
            if (c != '\r'.code) {
                out.write(c)
            }
            c = input.read()
        }
        return out.toString("UTF-8")
    }

    private fun serveWebPortal(output: OutputStream) {
        val itemsHtml = StringBuilder()
        if (sharedItems.isEmpty()) {
            itemsHtml.append("<div class='empty'>No shared files yet. Select files or tasks in AntiBot to share them here.</div>")
        } else {
            for (item in sharedItems.values) {
                val sizeFormatted = formatFileSize(item.sizeBytes)
                itemsHtml.append("""
                    <div class="item">
                        <div class="item-info">
                            <span class="item-name">${escapeHtml(item.name)}</span>
                            <span class="item-size">$sizeFormatted</span>
                        </div>
                        <a href="/download?id=${item.id}" class="btn-download" download="${escapeHtml(item.name)}">Download</a>
                    </div>
                """.trimIndent())
            }
        }

        val html = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AntiBot Web Drop</title>
    <style>
        :root {
            --bg: #0F172A;
            --surface: #1E293B;
            --primary: #F97316;
            --primary-hover: #EA580C;
            --text: #F8FAFC;
            --text-sub: #94A3B8;
            --border: #334155;
            --card-radius: 16px;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
        body { background: var(--bg); color: var(--text); padding: 24px 16px; min-height: 100vh; display: flex; flex-direction: column; align-items: center; }
        .container { width: 100%; max-width: 600px; display: flex; flex-direction: column; gap: 20px; }
        header { text-align: center; margin-bottom: 8px; }
        .badge { display: inline-flex; align-items: center; gap: 6px; background: rgba(249, 115, 22, 0.15); color: var(--primary); padding: 4px 12px; border-radius: 20px; font-size: 13px; font-weight: 600; margin-bottom: 8px; border: 1px solid rgba(249, 115, 22, 0.3); }
        h1 { font-size: 26px; font-weight: 800; letter-spacing: -0.5px; }
        p.subtitle { color: var(--text-sub); font-size: 14px; margin-top: 4px; }
        .card { background: var(--surface); border: 1px solid var(--border); border-radius: var(--card-radius); padding: 20px; box-shadow: 0 10px 25px rgba(0,0,0,0.3); }
        .card-title { font-size: 16px; font-weight: 700; margin-bottom: 14px; display: flex; align-items: center; gap: 8px; }
        .item { display: flex; justify-content: space-between; align-items: center; padding: 12px 14px; background: rgba(255,255,255,0.03); border: 1px solid var(--border); border-radius: 12px; margin-bottom: 10px; }
        .item-info { display: flex; flex-direction: column; gap: 2px; overflow: hidden; }
        .item-name { font-weight: 600; font-size: 14px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .item-size { font-size: 12px; color: var(--text-sub); }
        .btn-download { background: var(--primary); color: white; padding: 8px 14px; border-radius: 8px; text-decoration: none; font-size: 13px; font-weight: 600; }
        .dropzone { border: 2px dashed var(--border); border-radius: 14px; padding: 32px 16px; text-align: center; cursor: pointer; transition: all 0.2s; background: rgba(255,255,255,0.01); }
        .dropzone.hover { border-color: var(--primary); background: rgba(249, 115, 22, 0.08); }
        .dropzone p { color: var(--text-sub); font-size: 14px; margin-top: 8px; }
        .dropzone b { color: var(--text); }
        #fileInput { display: none; }
        .status { margin-top: 12px; font-size: 13px; text-align: center; color: var(--primary); font-weight: 600; min-height: 20px; }
        .empty { text-align: center; color: var(--text-sub); font-size: 14px; padding: 20px 0; }
    </style>
</head>
<body>
    <div class="container">
        <header>
            <div class="badge">⚡ Zero-Install Web Drop</div>
            <h1>AntiBot Share</h1>
            <p class="subtitle">Download shared files or drop files directly to phone</p>
        </header>

        <div class="card">
            <div class="card-title">📥 Available Downloads</div>
            <div id="itemsContainer">
                $itemsHtml
            </div>
        </div>

        <div class="card">
            <div class="card-title">📤 Drop Files to Phone</div>
            <div class="dropzone" id="dropzone" onclick="document.getElementById('fileInput').click()">
                <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#F97316" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
                <p><b>Click to upload</b> or drag & drop any file here</p>
            </div>
            <input type="file" id="fileInput" onchange="uploadFiles(this.files)" multiple />
            <div class="status" id="statusText"></div>
        </div>
    </div>

    <script>
        const dropzone = document.getElementById('dropzone');
        const statusText = document.getElementById('statusText');

        ['dragenter', 'dragover'].forEach(e => dropzone.addEventListener(e, (evt) => {
            evt.preventDefault();
            dropzone.classList.add('hover');
        }));

        ['dragleave', 'drop'].forEach(e => dropzone.addEventListener(e, (evt) => {
            evt.preventDefault();
            dropzone.classList.remove('hover');
        }));

        dropzone.addEventListener('drop', (e) => {
            const files = e.dataTransfer.files;
            if (files.length > 0) uploadFiles(files);
        });

        async function uploadFiles(files) {
            for (let i = 0; i < files.length; i++) {
                const file = files[i];
                statusText.innerText = `Uploading ${'$'}{file.name}...`;
                try {
                    const res = await fetch('/upload', {
                        method: 'POST',
                        headers: {
                            'x-filename': encodeURIComponent(file.name),
                            'Content-Length': file.size
                        },
                        body: file
                    });
                    if (res.ok) {
                        statusText.innerText = `✓ Successfully sent ${'$'}{file.name} to AntiBot!`;
                    } else {
                        statusText.innerText = `Upload failed for ${'$'}{file.name}`;
                    }
                } catch (e) {
                    statusText.innerText = `Error: ${'$'}{e.message}`;
                }
            }
            setTimeout(() => { statusText.innerText = ''; }, 4000);
        }
    </script>
</body>
</html>
        """.trimIndent()

        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"

        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun serveFileDownload(output: OutputStream, item: SharedPayloadItem) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: ${item.mimeType}\r\n" +
                "Content-Length: ${item.sizeBytes}\r\n" +
                "Content-Disposition: attachment; filename=\"${item.name}\"\r\n" +
                "Connection: close\r\n\r\n"

        output.write(header.toByteArray(Charsets.UTF_8))
        item.contentProvider().use { input ->
            val buf = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buf).also { bytesRead = it } != -1) {
                output.write(buf, 0, bytesRead)
            }
        }
        output.flush()
    }

    private fun saveUploadedFile(
        input: InputStream,
        contentLength: Long,
        rawFilename: String,
        output: OutputStream
    ) {
        val cleanName = try {
            URLDecoder.decode(rawFilename, "UTF-8")
        } catch (_: Exception) {
            rawFilename
        }.replace(Regex("[^a-zA-Z0-9._-]"), "_")

        val targetDir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AntiBotDrop").apply {
            mkdirs()
        }
        val targetFile = File(targetDir, "${System.currentTimeMillis()}_$cleanName")

        var remaining = contentLength
        FileOutputStream(targetFile).use { fos ->
            val buf = ByteArray(8192)
            while (remaining > 0) {
                val toRead = remaining.coerceAtMost(buf.size.toLong()).toInt()
                val read = input.read(buf, 0, toRead)
                if (read == -1) break
                fos.write(buf, 0, read)
                remaining -= read
            }
        }

        _receivedFiles.value = _receivedFiles.value + targetFile

        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun serveNotFound(output: OutputStream) {
        val body = "404 Not Found"
        val header = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val num = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format("%.1f %s", num, units[digitGroups])
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private object UriQueryHelper {
        fun getQueryParam(path: String, paramName: String): String? {
            val qIdx = path.indexOf('?')
            if (qIdx == -1) return null
            val query = path.substring(qIdx + 1)
            val pairs = query.split("&")
            for (pair in pairs) {
                val parts = pair.split("=")
                if (parts.size == 2 && parts[0] == paramName) {
                    return try {
                        URLDecoder.decode(parts[1], "UTF-8")
                    } catch (_: Exception) {
                        parts[1]
                    }
                }
            }
            return null
        }
    }
}
