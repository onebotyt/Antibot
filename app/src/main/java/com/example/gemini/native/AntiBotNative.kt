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

    @JvmStatic
    external fun fuzzyScoreNative(pattern: String, target: String): Int

    @JvmStatic
    external fun fuzzyFilterIndicesNative(pattern: String, targets: Array<String>, maxResults: Int): IntArray?

    @JvmStatic
    external fun countLinesMmapNative(path: String): Int

    @JvmStatic
    external fun getLineMmapNative(path: String, lineIndex: Int): String

    @JvmStatic
    external fun getSplicedTurnMmapNative(compactPath: String, fullPath: String, lineIndex: Int): String

    @JvmStatic
    external fun extractTranscriptMetaMmapNative(transcriptPath: String): String

    @JvmStatic
    external fun applyPatchNative(original: String, patchText: String, reverse: Boolean): String

    @JvmStatic
    external fun threeWayMergeNative(base: String, ours: String, theirs: String): String

    @JvmStatic
    external fun resolveConflictsNative(mergedText: String, strategy: String): String

    data class TranscriptSummary(
        val title: String,
        val totalSteps: Int,
        val isRunning: Boolean,
        val firstPrompt: String,
        val lastRole: String
    )

    data class PatchResult(
        val ok: Boolean,
        val text: String?,
        val error: String?,
        val hunkIndex: Int?
    )

    data class MergeResult(
        val merged: String,
        val hasConflicts: Boolean,
        val conflictCount: Int,
        val conflictRegions: List<ConflictRegion>
    )

    data class ConflictRegion(
        val baseStart: Int,
        val baseEnd: Int,
        val oursText: String,
        val theirsText: String
    )

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

    /**
     * Scores a string against a search pattern using zero-allocation FZF fuzzy matching in Rust.
     * Returns a positive score (higher = better match) or -1 if no match.
     */
    fun fuzzyScore(pattern: String, target: String): Int {
        if (pattern.isBlank()) return 0
        if (!isLoaded) {
            val pLower = pattern.lowercase()
            val tLower = target.lowercase()
            var pIdx = 0
            for (ch in tLower) {
                if (pIdx < pLower.length && ch == pLower[pIdx]) {
                    pIdx++
                }
            }
            return if (pIdx == pLower.length) {
                100 - (target.length - pattern.length).coerceAtMost(50)
            } else -1
        }
        return try {
            fuzzyScoreNative(pattern, target)
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * Batch ranks a list of target strings (e.g. project file paths) using native Rust FZF matching.
     * Returns matching strings in descending order of relevance.
     */
    fun fuzzyFilter(pattern: String, targets: List<String>, maxResults: Int = 30): List<String> {
        if (targets.isEmpty()) return emptyList()
        if (pattern.isBlank()) return targets.take(maxResults)

        if (isLoaded) {
            try {
                val flat = fuzzyFilterIndicesNative(pattern, targets.toTypedArray(), maxResults)
                if (flat != null && flat.isNotEmpty()) {
                    val result = mutableListOf<String>()
                    var i = 0
                    while (i < flat.size) {
                        val idx = flat[i]
                        if (idx in targets.indices) {
                            result.add(targets[idx])
                        }
                        i += 2
                    }
                    return result
                }
            } catch (e: Throwable) {
                Log.w(TAG, "fuzzyFilterIndicesNative error: ${e.message}")
            }
        }

        // JVM fallback
        return targets.mapNotNull { item ->
            val score = fuzzyScore(pattern, item)
            if (score > 0) Pair(item, score) else null
        }.sortedByDescending { it.second }.take(maxResults).map { it.first }
    }

    /**
     * Counts lines in a file via memory mapping and SIMD memchr in Rust.
     */
    fun countLinesMmap(path: String): Int {
        if (!isLoaded || path.isEmpty()) return 0
        return try {
            countLinesMmapNative(path)
        } catch (e: Throwable) {
            Log.w(TAG, "countLinesMmapNative error: ${e.message}")
            0
        }
    }

    /**
     * Reads a single line by index from a file using zero-copy memory mapping in Rust.
     */
    fun getLineMmap(path: String, lineIndex: Int): String? {
        if (!isLoaded || path.isEmpty() || lineIndex < 0) return null
        return try {
            val line = getLineMmapNative(path, lineIndex)
            line.takeIf { it.isNotEmpty() }
        } catch (e: Throwable) {
            Log.w(TAG, "getLineMmapNative error: ${e.message}")
            null
        }
    }

    /**
     * Retrieves a transcript turn, automatically splicing full untruncated fields
     * from `transcript_full.jsonl` if `transcript.jsonl` contains truncated fields.
     */
    fun getSplicedTurnMmap(compactPath: String, fullPath: String, lineIndex: Int): String? {
        if (!isLoaded || compactPath.isEmpty() || lineIndex < 0) return null
        return try {
            val turn = getSplicedTurnMmapNative(compactPath, fullPath, lineIndex)
            turn.takeIf { it.isNotEmpty() }
        } catch (e: Throwable) {
            Log.w(TAG, "getSplicedTurnMmapNative error: ${e.message}")
            null
        }
    }

    /**
     * Ultra-fast transcript summary extraction (title, total steps, running status)
     * directly via Rust memory mapping in microseconds.
     */
    fun extractTranscriptMeta(transcriptPath: String): TranscriptSummary? {
        if (!isLoaded || transcriptPath.isEmpty()) return null
        return try {
            val jsonStr = extractTranscriptMetaMmapNative(transcriptPath)
            if (jsonStr.isBlank() || jsonStr == "{}") return null
            val obj = JSONObject(jsonStr)
            TranscriptSummary(
                title = obj.optString("title", ""),
                totalSteps = obj.optInt("total_steps", 0),
                isRunning = obj.optBoolean("is_running", false),
                firstPrompt = obj.optString("first_prompt", ""),
                lastRole = obj.optString("last_role", "")
            )
        } catch (e: Throwable) {
            Log.w(TAG, "extractTranscriptMeta error: ${e.message}")
            null
        }
    }

    /**
     * Applies a unified diff patch to the original text in native Rust.
     * Supports fuzzy line drift tolerance and reverse (un-apply) mode.
     */
    fun applyPatch(original: String, patchText: String, reverse: Boolean = false): PatchResult {
        if (!isLoaded) return PatchResult(false, null, "Native library not loaded", null)
        return try {
            val jsonStr = applyPatchNative(original, patchText, reverse)
            val obj = JSONObject(jsonStr)
            PatchResult(
                ok = obj.optBoolean("ok", false),
                text = obj.optString("text").takeIf { obj.optBoolean("ok", false) },
                error = obj.optString("error").takeIf { !obj.optBoolean("ok", false) },
                hunkIndex = if (obj.has("hunk")) obj.optInt("hunk") else null
            )
        } catch (e: Throwable) {
            Log.w(TAG, "applyPatchNative error: ${e.message}")
            PatchResult(false, null, e.message, null)
        }
    }

    /**
     * Performs a 3-way merge in native Rust. Returns merged text with conflict markers
     * when overlapping edits are detected.
     */
    fun threeWayMerge(base: String, ours: String, theirs: String): MergeResult {
        if (!isLoaded) return MergeResult(ours, false, 0, emptyList())
        return try {
            val jsonStr = threeWayMergeNative(base, ours, theirs)
            val obj = JSONObject(jsonStr)
            val regionsArr = obj.optJSONArray("conflict_regions") ?: JSONArray()
            val regions = mutableListOf<ConflictRegion>()
            for (i in 0 until regionsArr.length()) {
                val r = regionsArr.getJSONObject(i)
                regions.add(ConflictRegion(
                    baseStart = r.optInt("base_start", 0),
                    baseEnd = r.optInt("base_end", 0),
                    oursText = r.optString("ours_text", ""),
                    theirsText = r.optString("theirs_text", "")
                ))
            }
            MergeResult(
                merged = obj.optString("merged", ""),
                hasConflicts = obj.optBoolean("has_conflicts", false),
                conflictCount = obj.optInt("conflict_count", 0),
                conflictRegions = regions
            )
        } catch (e: Throwable) {
            Log.w(TAG, "threeWayMergeNative error: ${e.message}")
            MergeResult(ours, false, 0, emptyList())
        }
    }

    /**
     * Resolves all conflict markers in text using the given strategy: "ours", "theirs", or "both".
     */
    fun resolveConflicts(mergedText: String, strategy: String = "ours"): String {
        if (!isLoaded || mergedText.isEmpty()) return mergedText
        return try {
            resolveConflictsNative(mergedText, strategy)
        } catch (e: Throwable) {
            Log.w(TAG, "resolveConflictsNative error: ${e.message}")
            mergedText
        }
    }
}


