package com.example.gemini.native

import android.util.Log
import com.example.gemini.ui.components.AlertType
import com.example.gemini.ui.components.MarkdownBlock
import com.example.gemini.ui.components.TableAlignment
import org.json.JSONArray
import org.json.JSONObject

/**
 * High-performance Android Native JNI Layer backed by libantibot_native.so.
 * Accelerates Markdown AST tokenization, inline span parsing, fast unified diffing,
 * and cryptographic hashing with zero-allocation Rust pipelines.
 */
object AntiBotNative {

    private const val TAG = "AntiBotNative"
    var isLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("antibot_native")
            isLoaded = true
            val ver = getNativeVersion()
            Log.i(TAG, "⚡ libantibot_native.so loaded successfully! Version: $ver")
        } catch (e: Throwable) {
            Log.w(TAG, "libantibot_native.so not loaded (falling back to JVM): ${e.message}")
            isLoaded = false
        }
    }

    // --- Native External JNI Functions ---
    @JvmStatic
    external fun isNativeAvailable(): Boolean

    @JvmStatic
    external fun getNativeVersion(): String

    @JvmStatic
    external fun parseMarkdownBlocksNative(content: String): String

    @JvmStatic
    external fun parseInlineSpansNative(text: String): String

    @JvmStatic
    external fun computeDiffNative(oldText: String, newText: String): String

    @JvmStatic
    external fun sha256Native(input: String): String

    @JvmStatic
    external fun highlightSyntaxNative(code: String, ext: String): IntArray?

    @JvmStatic
    external fun processAudioPcmNative(pcmBytes: ByteArray): FloatArray?

    @JvmStatic
    external fun resetVadNative()

    @JvmStatic
    external fun pcmToWavNative(pcmBytes: ByteArray, sampleRate: Int, channels: Short, bitsPerSample: Short): ByteArray?

    data class NativeAudioMetrics(
        val normalizedAmplitude: Float,
        val peakAmplitude: Float,
        val isSpeech: Boolean,
        val snrDb: Float,
        val rmsRaw: Float
    )

    object TokenType {
        const val DEFAULT = 0
        const val KEYWORD = 1
        const val CONTROL_KEYWORD = 2
        const val TYPE = 3
        const val FUNCTION = 4
        const val STRING = 5
        const val NUMBER = 6
        const val COMMENT = 7
        const val TAG = 8
        const val PROPERTY = 9
        const val ANNOTATION = 10
    }

    // --- High-Level Typed APIs ---

    /**
     * Parses Markdown content directly in Rust, producing typed Compose [MarkdownBlock]s
     * in sub-millisecond time. Returns null if native layer is unavailable or encounters an error.
     */
    fun parseMarkdownBlocks(content: String): List<MarkdownBlock>? {
        if (!isLoaded || content.isBlank()) return null

        return try {
            val jsonStr = parseMarkdownBlocksNative(content)
            if (jsonStr.isBlank() || jsonStr == "[]") return null

            val array = JSONArray(jsonStr)
            val blocks = mutableListOf<MarkdownBlock>()

            for (idx in 0 until array.length()) {
                val obj = array.getJSONObject(idx)
                val type = obj.optString("type", "")

                val block: MarkdownBlock? = when (type) {
                    "paragraph" -> MarkdownBlock.Paragraph(obj.optString("text", ""))
                    "header" -> MarkdownBlock.Header(
                        level = obj.optInt("level", 1),
                        text = obj.optString("text", "")
                    )
                    "code" -> MarkdownBlock.Code(
                        language = obj.optString("language", ""),
                        code = obj.optString("code", "")
                    )
                    "math" -> MarkdownBlock.Math(
                        latex = obj.optString("latex", ""),
                        isDisplay = obj.optBoolean("isDisplay", true)
                    )
                    "mermaid" -> MarkdownBlock.Mermaid(obj.optString("code", ""))
                    "interactive_ui" -> MarkdownBlock.InteractiveUi(obj.optString("htmlCode", ""))
                    "alert" -> {
                        val alertTypeStr = obj.optString("alertType", "NOTE").uppercase()
                        val alertType = try {
                            AlertType.valueOf(alertTypeStr)
                        } catch (_: Exception) {
                            AlertType.NOTE
                        }
                        MarkdownBlock.Alert(
                            type = alertType,
                            title = obj.optString("title").takeIf { it.isNotBlank() },
                            text = obj.optString("text", "")
                        )
                    }
                    "blockquote" -> MarkdownBlock.Blockquote(obj.optString("text", ""))
                    "bullet" -> MarkdownBlock.Bullet(
                        indent = obj.optInt("indent", 0),
                        text = obj.optString("text", "")
                    )
                    "numbered" -> MarkdownBlock.Numbered(
                        number = obj.optString("number", "1."),
                        text = obj.optString("text", "")
                    )
                    "task" -> MarkdownBlock.Task(
                        isChecked = obj.optBoolean("isChecked", false),
                        text = obj.optString("text", "")
                    )
                    "table" -> {
                        val headersArr = obj.optJSONArray("headers") ?: JSONArray()
                        val headers = mutableListOf<String>()
                        for (h in 0 until headersArr.length()) headers.add(headersArr.getString(h))

                        val rowsArr = obj.optJSONArray("rows") ?: JSONArray()
                        val rows = mutableListOf<List<String>>()
                        for (r in 0 until rowsArr.length()) {
                            val rowArr = rowsArr.getJSONArray(r)
                            val row = mutableListOf<String>()
                            for (c in 0 until rowArr.length()) row.add(rowArr.getString(c))
                            rows.add(row)
                        }

                        val alignsArr = obj.optJSONArray("alignments") ?: JSONArray()
                        val alignments = mutableListOf<TableAlignment>()
                        for (a in 0 until alignsArr.length()) {
                            val align = try {
                                TableAlignment.valueOf(alignsArr.getString(a))
                            } catch (_: Exception) {
                                TableAlignment.LEFT
                            }
                            alignments.add(align)
                        }

                        MarkdownBlock.Table(headers, rows, alignments)
                    }
                    "details" -> MarkdownBlock.Details(
                        summary = obj.optString("summary", "Details"),
                        body = obj.optString("body", ""),
                        defaultOpen = obj.optBoolean("defaultOpen", false)
                    )
                    "image" -> MarkdownBlock.Image(
                        alt = obj.optString("alt", ""),
                        url = obj.optString("url", "")
                    )
                    "video" -> MarkdownBlock.Video(
                        alt = obj.optString("alt", ""),
                        url = obj.optString("url", "")
                    )
                    "youtube" -> MarkdownBlock.YouTubeVideo(
                        videoId = obj.optString("videoId", ""),
                        originalUrl = obj.optString("originalUrl", ""),
                        title = obj.optString("title", "YouTube Video"),
                        customThumbnailUrl = obj.optString("customThumbnailUrl").takeIf { it.isNotBlank() }
                    )
                    "hr" -> MarkdownBlock.HorizontalRule
                    else -> null
                }

                if (block != null) {
                    blocks.add(block)
                }
            }

            blocks
        } catch (e: Exception) {
            Log.w(TAG, "parseMarkdownBlocks error: ${e.message}")
            null
        }
    }

    /**
     * Computes fast native SHA256 checksum in Rust.
     */
    fun computeSha256(text: String): String {
        return if (isLoaded) {
            try {
                sha256Native(text)
            } catch (e: Exception) {
                fallbackSha256(text)
            }
        } else {
            fallbackSha256(text)
        }
    }

    private fun fallbackSha256(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Tokenizes source code into flat `[start, end, token_type, ...]` spans in Rust.
     * Indices are in UTF-16 code units for 100% Android Compose compatibility.
     * Returns null if native layer is unavailable or encounters an error.
     */
    fun highlightSyntax(code: String, ext: String): IntArray? {
        if (!isLoaded || code.isEmpty()) return null
        return try {
            highlightSyntaxNative(code, ext)
        } catch (e: Throwable) {
            Log.w(TAG, "highlightSyntaxNative error: ${e.message}")
            null
        }
    }

    /**
     * SIMD/NEON-accelerated Audio DSP: computes RMS amplitude, peak, and Voice Activity Detection (VAD)
     * over a 16kHz 16-bit signed mono PCM chunk in < 5 microseconds.
     */
    fun processAudioPcm(pcmBytes: ByteArray): NativeAudioMetrics? {
        if (!isLoaded || pcmBytes.isEmpty()) return null
        return try {
            val raw = processAudioPcmNative(pcmBytes) ?: return null
            if (raw.size >= 5) {
                NativeAudioMetrics(
                    normalizedAmplitude = raw[0],
                    peakAmplitude = raw[1],
                    isSpeech = raw[2] > 0.5f,
                    snrDb = raw[3],
                    rmsRaw = raw[4]
                )
            } else null
        } catch (e: Throwable) {
            Log.w(TAG, "processAudioPcmNative error: ${e.message}")
            null
        }
    }

    /**
     * Resets the adaptive noise floor tracker for Voice Activity Detection.
     */
    fun resetVad() {
        if (isLoaded) {
            try { resetVadNative() } catch (_: Throwable) {}
        }
    }

    /**
     * Instantly converts raw 16kHz 16-bit mono PCM into standard 44-byte WAV container bytes.
     */
    fun pcmToWav(pcmBytes: ByteArray, sampleRate: Int = 16000, channels: Short = 1, bitsPerSample: Short = 16): ByteArray? {
        if (!isLoaded || pcmBytes.isEmpty()) return null
        return try {
            pcmToWavNative(pcmBytes, sampleRate, channels, bitsPerSample)
        } catch (e: Throwable) {
            Log.w(TAG, "pcmToWavNative error: ${e.message}")
            null
        }
    }
}


