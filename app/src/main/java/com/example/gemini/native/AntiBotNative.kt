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

    @JvmStatic
    external fun getCompletionsNative(prefix: String, ext: String, symbolsJson: String, limit: Int): String

    @JvmStatic
    external fun extractFileSymbolsNative(content: String, ext: String): String

    @JvmStatic
    external fun parseDiagnosticsNative(rawOutput: String): String

    @JvmStatic
    external fun frameJsonRpcRequestNative(id: Int, method: String, paramsJson: String): String

    @JvmStatic
    external fun parseJsonRpcResponseNative(raw: String): String

    @JvmStatic
    external fun validateAgentDagNative(nodesJson: String): String

    @JvmStatic
    external fun evaluateConsensusNative(proposalsJson: String, strategyStr: String): String

    @JvmStatic
    external fun cosineSimilarityNative(vecA: FloatArray, vecB: FloatArray): Float

    @JvmStatic
    external fun searchRagDocumentsNative(queryVec: FloatArray, documentsJson: String, topK: Int, minScore: Float): String

    @JvmStatic
    external fun estimateTokenCountNative(text: String): Int

    @JvmStatic
    external fun truncateToTokenBudgetNative(text: String, maxTokens: Int): String

    @JvmStatic
    external fun initAudioRingBufferNative(capacitySamples: Int)

    @JvmStatic
    external fun writeAudioRingBufferNative(pcmBytes: ByteArray): Int

    @JvmStatic
    external fun computeEqualizerBandsNative(pcmBytes: ByteArray, numBands: Int): FloatArray?

    @JvmStatic
    external fun computeAudioTelemetryNative(pcmBytes: ByteArray, sampleRate: Int): String

    @JvmStatic
    external fun indexDocumentNative(docJson: String): Boolean

    @JvmStatic
    external fun searchHybridNative(queryText: String, queryEmbedding: FloatArray, alpha: Float, topK: Int): String

    @JvmStatic
    external fun getIndexStatsNative(): String

    @JvmStatic
    external fun recordNetworkTrafficNative(entryJson: String): Boolean

    @JvmStatic
    external fun getRecentNetworkTrafficNative(limit: Int): String

    @JvmStatic
    external fun getNetworkMetricsNative(): String

    @JvmStatic
    external fun clearNetworkTrafficNative()

    data class NetworkTrafficEntry(
        val id: String,
        val timestamp: Long = System.currentTimeMillis(),
        val method: String,
        val url: String,
        val statusCode: Int,
        val durationMs: Long,
        val bytesSent: Long,
        val bytesReceived: Long,
        val clientIp: String = "127.0.0.1",
        val error: String? = null
    )

    data class NetworkMetrics(
        val totalBytesSent: Long,
        val totalBytesReceived: Long,
        val activeConnections: Int,
        val requestCount: Long,
        val responseCount: Long,
        val errorCount: Long,
        val uploadBps: Float,
        val downloadBps: Float
    )

    data class SearchDocument(
        val id: String,
        val title: String,
        val body: String,
        val embedding: FloatArray = floatArrayOf(),
        val metadata: Map<String, String> = emptyMap()
    )

    data class SearchResultItem(
        val id: String,
        val score: Float,
        val bm25Score: Float,
        val vectorScore: Float,
        val title: String,
        val snippet: String,
        val metadata: Map<String, String> = emptyMap()
    )

    data class IndexStats(
        val totalDocuments: Int,
        val uniqueTerms: Int,
        val totalPostings: Int,
        val avgDocLength: Float
    )

    data class AudioTelemetry(
        val normalizedAmplitude: Float,
        val peakAmplitude: Float,
        val rmsDb: Float,
        val snrDb: Float,
        val isSpeech: Boolean,
        val noiseFloor: Float,
        val dominantFrequencyHz: Float,
        val spectrumBands: FloatArray,
        val sampleRate: Int
    )

    data class VectorDocument(
        val id: String,
        val text: String,
        val embedding: FloatArray,
        val metadata: Map<String, String> = emptyMap()
    )

    data class RagSearchResult(
        val id: String,
        val score: Float,
        val text: String,
        val metadata: Map<String, String> = emptyMap()
    )

    data class TokenBudgetResult(
        val text: String,
        val tokenCount: Int
    )

    data class AgentTaskNode(
        val id: String,
        val title: String,
        val assignedRole: String = "",
        val dependencies: List<String> = emptyList(),
        val status: String = "pending",
        val priority: Int = 0
    )

    data class AgentNodeLayout(
        val taskId: String,
        val depth: Int,
        val lane: Int
    )

    data class AgentDagResult(
        val isValid: Boolean,
        val hasCycle: Boolean,
        val cyclePath: List<String>? = null,
        val topologicalOrder: List<String> = emptyList(),
        val readyTasks: List<String> = emptyList(),
        val nodeLayouts: List<AgentNodeLayout> = emptyList()
    )

    data class AgentProposal(
        val agentId: String,
        val role: String,
        val value: String,
        val confidence: Double = 1.0,
        val rationale: String? = null
    )

    data class ConsensusEvaluation(
        val strategy: String,
        val winner: String? = null,
        val isConsensusReached: Boolean,
        val agreementRatio: Double,
        val totalVotes: Int,
        val voteTallies: Map<String, Int> = emptyMap(),
        val confidenceTallies: Map<String, Double> = emptyMap()
    )

    data class NativeJsonRpcResponse(
        val id: Long,
        val result: JSONObject? = null,
        val error: NativeJsonRpcError? = null
    )

    data class NativeJsonRpcError(
        val code: Long,
        val message: String,
        val data: String? = null
    )

    data class NativeCompletionItem(
        val label: String,
        val kind: String,
        val detail: String? = null,
        val documentation: String? = null,
        val insertText: String? = null,
        val sortPriority: Int = 0
    )

    data class NativeFileSymbol(
        val name: String,
        val kind: String,
        val line: Int,
        val character: Int,
        val containerName: String? = null
    )

    data class NativeDiagnostic(
        val file: String? = null,
        val line: Int,
        val col: Int,
        val endLine: Int? = null,
        val endCol: Int? = null,
        val severity: String,
        val message: String,
        val source: String? = null,
        val code: String? = null
    )

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

    /**
     * Fast in-memory auto-completion powered by native prefix Trie in Rust.
     */
    fun getCompletions(prefix: String, ext: String, extraSymbols: List<NativeCompletionItem> = emptyList(), limit: Int = 20): List<NativeCompletionItem> {
        if (!isLoaded || prefix.isEmpty()) return emptyList()
        return try {
            val extraJson = if (extraSymbols.isNotEmpty()) {
                val arr = JSONArray()
                for (s in extraSymbols) {
                    val obj = JSONObject()
                    obj.put("label", s.label)
                    obj.put("kind", s.kind)
                    s.detail?.let { obj.put("detail", it) }
                    s.documentation?.let { obj.put("documentation", it) }
                    s.insertText?.let { obj.put("insert_text", it) }
                    obj.put("sort_priority", s.sortPriority)
                    arr.put(obj)
                }
                arr.toString()
            } else ""

            val jsonStr = getCompletionsNative(prefix, ext, extraJson, limit)
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<NativeCompletionItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(NativeCompletionItem(
                    label = o.optString("label"),
                    kind = o.optString("kind"),
                    detail = o.optString("detail").takeIf { it.isNotBlank() },
                    documentation = o.optString("documentation").takeIf { it.isNotBlank() },
                    insertText = o.optString("insert_text").takeIf { it.isNotBlank() },
                    sortPriority = o.optInt("sort_priority", 0)
                ))
            }
            list
        } catch (e: Throwable) {
            Log.w(TAG, "getCompletionsNative error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Extracts declared symbols (functions, classes, structs) from source files using native Rust scanner.
     */
    fun extractFileSymbols(content: String, ext: String): List<NativeFileSymbol> {
        if (!isLoaded || content.isEmpty()) return emptyList()
        return try {
            val jsonStr = extractFileSymbolsNative(content, ext)
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<NativeFileSymbol>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(NativeFileSymbol(
                    name = o.optString("name"),
                    kind = o.optString("kind"),
                    line = o.optInt("line", 1),
                    character = o.optInt("character", 1),
                    containerName = o.optString("container_name").takeIf { it.isNotBlank() }
                ))
            }
            list
        } catch (e: Throwable) {
            Log.w(TAG, "extractFileSymbolsNative error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parses compiler and linter error/warning output into structured diagnostics with Compose UTF-16 coordinates.
     */
    fun parseDiagnostics(rawOutput: String): List<NativeDiagnostic> {
        if (!isLoaded || rawOutput.isEmpty()) return emptyList()
        return try {
            val jsonStr = parseDiagnosticsNative(rawOutput)
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<NativeDiagnostic>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(NativeDiagnostic(
                    file = o.optString("file").takeIf { it.isNotBlank() },
                    line = o.optInt("line", 1),
                    col = o.optInt("col", 1),
                    endLine = if (o.has("end_line")) o.optInt("end_line") else null,
                    endCol = if (o.has("end_col")) o.optInt("end_col") else null,
                    severity = o.optString("severity", "error"),
                    message = o.optString("message"),
                    source = o.optString("source").takeIf { it.isNotBlank() },
                    code = o.optString("code").takeIf { it.isNotBlank() }
                ))
            }
            list
        } catch (e: Throwable) {
            Log.w(TAG, "parseDiagnosticsNative error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Frames a method and arguments into a JSON-RPC 2.0 packet using native Rust framing.
     */
    fun frameJsonRpcRequest(id: Int, method: String, params: JSONObject? = null): String {
        val paramsJson = params?.toString() ?: ""
        if (!isLoaded) {
            val obj = JSONObject()
            obj.put("jsonrpc", "2.0")
            obj.put("id", id)
            obj.put("method", method)
            if (params != null) obj.put("params", params)
            return obj.toString() + "\n"
        }
        return try {
            frameJsonRpcRequestNative(id, method, paramsJson)
        } catch (e: Throwable) {
            Log.w(TAG, "frameJsonRpcRequestNative error: ${e.message}")
            "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"$method\"}\n"
        }
    }

    /**
     * Parses a raw JSON-RPC 2.0 response using native Rust parser.
     */
    fun parseJsonRpcResponse(raw: String): NativeJsonRpcResponse {
        if (!isLoaded || raw.isBlank()) return NativeJsonRpcResponse(0, null, null)
        return try {
            val jsonStr = parseJsonRpcResponseNative(raw)
            val obj = JSONObject(jsonStr)
            val errObj = obj.optJSONObject("error")
            val error = if (errObj != null) {
                NativeJsonRpcError(
                    code = errObj.optLong("code", -1L),
                    message = errObj.optString("message", "Unknown error"),
                    data = errObj.optString("data").takeIf { it.isNotBlank() }
                )
            } else null

            NativeJsonRpcResponse(
                id = obj.optLong("id", 0L),
                result = obj.optJSONObject("result"),
                error = error
            )
        } catch (e: Throwable) {
            Log.w(TAG, "parseJsonRpcResponseNative error: ${e.message}")
            NativeJsonRpcResponse(0, null, NativeJsonRpcError(-32700, e.message ?: "Parse error"))
        }
    }

    /**
     * Validates an Agent Task DAG, runs Kahn's topological sort, detects cycles,
     * computes ready unblocked tasks, and calculates Compose 2D layout coordinates.
     */
    fun validateAndScheduleDag(nodes: List<AgentTaskNode>): AgentDagResult {
        if (!isLoaded || nodes.isEmpty()) {
            return AgentDagResult(
                isValid = true,
                hasCycle = false,
                topologicalOrder = nodes.map { it.id },
                readyTasks = nodes.filter { it.status == "pending" && it.dependencies.isEmpty() }.map { it.id }
            )
        }
        return try {
            val jsonArr = JSONArray()
            for (node in nodes) {
                val obj = JSONObject()
                obj.put("id", node.id)
                obj.put("title", node.title)
                obj.put("assigned_role", node.assignedRole)
                val depArr = JSONArray()
                for (dep in node.dependencies) depArr.put(dep)
                obj.put("dependencies", depArr)
                obj.put("status", node.status)
                obj.put("priority", node.priority)
                jsonArr.put(obj)
            }
            val resJson = validateAgentDagNative(jsonArr.toString())
            val resObj = JSONObject(resJson)
            val isValid = resObj.optBoolean("is_valid", false)
            val hasCycle = resObj.optBoolean("has_cycle", false)

            val cycleArr = resObj.optJSONArray("cycle_path")
            val cyclePath = if (cycleArr != null) {
                (0 until cycleArr.length()).map { cycleArr.getString(it) }
            } else null

            val topoArr = resObj.optJSONArray("topological_order") ?: JSONArray()
            val topologicalOrder = (0 until topoArr.length()).map { topoArr.getString(it) }

            val readyArr = resObj.optJSONArray("ready_tasks") ?: JSONArray()
            val readyTasks = (0 until readyArr.length()).map { readyArr.getString(it) }

            val layoutsArr = resObj.optJSONArray("node_layouts") ?: JSONArray()
            val nodeLayouts = (0 until layoutsArr.length()).map {
                val layoutObj = layoutsArr.getJSONObject(it)
                AgentNodeLayout(
                    taskId = layoutObj.optString("task_id", ""),
                    depth = layoutObj.optInt("depth", 0),
                    lane = layoutObj.optInt("lane", 0)
                )
            }

            AgentDagResult(
                isValid = isValid,
                hasCycle = hasCycle,
                cyclePath = cyclePath,
                topologicalOrder = topologicalOrder,
                readyTasks = readyTasks,
                nodeLayouts = nodeLayouts
            )
        } catch (e: Throwable) {
            Log.w(TAG, "validateAgentDagNative error: ${e.message}")
            AgentDagResult(
                isValid = false,
                hasCycle = false,
                topologicalOrder = emptyList(),
                readyTasks = emptyList()
            )
        }
    }

    /**
     * Evaluates multi-agent consensus across proposals using the specified strategy.
     */
    fun evaluateConsensus(
        proposals: List<AgentProposal>,
        strategy: String = "majority"
    ): ConsensusEvaluation {
        if (!isLoaded || proposals.isEmpty()) {
            return ConsensusEvaluation(
                strategy = strategy,
                winner = null,
                isConsensusReached = false,
                agreementRatio = 0.0,
                totalVotes = proposals.size
            )
        }
        return try {
            val jsonArr = JSONArray()
            for (p in proposals) {
                val obj = JSONObject()
                obj.put("agent_id", p.agentId)
                obj.put("role", p.role)
                obj.put("value", p.value)
                obj.put("confidence", p.confidence)
                p.rationale?.let { obj.put("rationale", it) }
                jsonArr.put(obj)
            }
            val resJson = evaluateConsensusNative(jsonArr.toString(), strategy)
            val resObj = JSONObject(resJson)

            val voteTallies = mutableMapOf<String, Int>()
            val vObj = resObj.optJSONObject("vote_tallies")
            if (vObj != null) {
                val keys = vObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    voteTallies[k] = vObj.optInt(k, 0)
                }
            }

            val confTallies = mutableMapOf<String, Double>()
            val cObj = resObj.optJSONObject("confidence_tallies")
            if (cObj != null) {
                val keys = cObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    confTallies[k] = cObj.optDouble(k, 0.0)
                }
            }

            ConsensusEvaluation(
                strategy = resObj.optString("strategy", strategy),
                winner = resObj.optString("winner").takeIf { it.isNotBlank() },
                isConsensusReached = resObj.optBoolean("is_consensus_reached", false),
                agreementRatio = resObj.optDouble("agreement_ratio", 0.0),
                totalVotes = resObj.optInt("total_votes", 0),
                voteTallies = voteTallies,
                confidenceTallies = confTallies
            )
        } catch (e: Throwable) {
            Log.w(TAG, "evaluateConsensusNative error: ${e.message}")
            ConsensusEvaluation(
                strategy = strategy,
                winner = null,
                isConsensusReached = false,
                agreementRatio = 0.0,
                totalVotes = proposals.size
            )
        }
    }

    /**
     * Computes cosine similarity between two float embedding vectors.
     */
    fun computeCosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (!isLoaded || a.isEmpty() || b.isEmpty()) return 0.0f
        return try {
            cosineSimilarityNative(a, b)
        } catch (e: Throwable) {
            Log.w(TAG, "cosineSimilarityNative error: ${e.message}")
            0.0f
        }
    }

    /**
     * Performs in-memory k-NN semantic search across document snippets using cosine similarity.
     */
    fun searchRagDocuments(
        queryEmbedding: FloatArray,
        documents: List<VectorDocument>,
        topK: Int = 5,
        minScore: Float = 0.0f
    ): List<RagSearchResult> {
        if (!isLoaded || queryEmbedding.isEmpty() || documents.isEmpty() || topK <= 0) return emptyList()
        return try {
            val jsonArr = JSONArray()
            for (doc in documents) {
                val obj = JSONObject()
                obj.put("id", doc.id)
                obj.put("text", doc.text)
                val embArr = JSONArray()
                for (v in doc.embedding) embArr.put(v.toDouble())
                obj.put("embedding", embArr)
                val metaObj = JSONObject()
                for ((k, v) in doc.metadata) metaObj.put(k, v)
                obj.put("metadata", metaObj)
                jsonArr.put(obj)
            }
            val resJson = searchRagDocumentsNative(queryEmbedding, jsonArr.toString(), topK, minScore)
            val resArr = JSONArray(resJson)
            (0 until resArr.length()).map { i ->
                val o = resArr.getJSONObject(i)
                val meta = mutableMapOf<String, String>()
                val mObj = o.optJSONObject("metadata")
                if (mObj != null) {
                    val keys = mObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        meta[k] = mObj.optString(k, "")
                    }
                }
                RagSearchResult(
                    id = o.optString("id", ""),
                    score = o.optDouble("score", 0.0).toFloat(),
                    text = o.optString("text", ""),
                    metadata = meta
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "searchRagDocumentsNative error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Estimates the token count for a text string using high-performance native heuristics.
     */
    fun estimateTokenCount(text: String): Int {
        if (!isLoaded || text.isEmpty()) return 0
        return try {
            estimateTokenCountNative(text)
        } catch (e: Throwable) {
            Log.w(TAG, "estimateTokenCountNative error: ${e.message}")
            text.length / 4
        }
    }

    /**
     * Truncates text to fit within the specified token budget.
     */
    fun truncateToTokenBudget(text: String, maxTokens: Int): TokenBudgetResult {
        if (!isLoaded || text.isEmpty() || maxTokens <= 0) return TokenBudgetResult("", 0)
        return try {
            val resJson = truncateToTokenBudgetNative(text, maxTokens)
            val obj = JSONObject(resJson)
            TokenBudgetResult(
                text = obj.optString("text", text),
                tokenCount = obj.optInt("token_count", 0)
            )
        } catch (e: Throwable) {
            Log.w(TAG, "truncateToTokenBudgetNative error: ${e.message}")
            TokenBudgetResult(text.take(maxTokens * 4), maxTokens)
        }
    }

    /**
     * Initializes the native lock-free circular audio ring buffer with specified capacity.
     */
    fun initAudioRingBuffer(capacitySamples: Int = 16000) {
        if (!isLoaded) return
        try {
            initAudioRingBufferNative(capacitySamples)
        } catch (e: Throwable) {
            Log.w(TAG, "initAudioRingBufferNative error: ${e.message}")
        }
    }

    /**
     * Writes 16-bit PCM audio samples into the native circular ring buffer.
     */
    fun writeAudioRingBuffer(pcmBytes: ByteArray): Int {
        if (!isLoaded || pcmBytes.isEmpty()) return 0
        return try {
            writeAudioRingBufferNative(pcmBytes)
        } catch (e: Throwable) {
            Log.w(TAG, "writeAudioRingBufferNative error: ${e.message}")
            0
        }
    }

    /**
     * Computes N-band visual audio equalizer spectrum for live Compose rendering.
     */
    fun computeEqualizerBands(pcmBytes: ByteArray, numBands: Int = 8): FloatArray {
        if (!isLoaded || pcmBytes.isEmpty()) return FloatArray(numBands)
        return try {
            computeEqualizerBandsNative(pcmBytes, numBands) ?: FloatArray(numBands)
        } catch (e: Throwable) {
            Log.w(TAG, "computeEqualizerBandsNative error: ${e.message}")
            FloatArray(numBands)
        }
    }

    /**
     * Analyzes PCM audio samples to compute comprehensive real-time telemetry
     * including RMS, peak energy, SNR, speech decision, dominant pitch, and equalizer bands.
     */
    fun computeAudioTelemetry(pcmBytes: ByteArray, sampleRate: Int = 16000): AudioTelemetry {
        if (!isLoaded || pcmBytes.isEmpty()) {
            return AudioTelemetry(
                normalizedAmplitude = 0.08f,
                peakAmplitude = 0.0f,
                rmsDb = -96.0f,
                snrDb = 0.0f,
                isSpeech = false,
                noiseFloor = 300.0f,
                dominantFrequencyHz = 0.0f,
                spectrumBands = FloatArray(8),
                sampleRate = sampleRate
            )
        }
        return try {
            val resJson = computeAudioTelemetryNative(pcmBytes, sampleRate)
            val obj = JSONObject(resJson)
            val bandsArr = obj.optJSONArray("spectrum_bands") ?: JSONArray()
            val bands = FloatArray(bandsArr.length()) { i -> bandsArr.optDouble(i, 0.0).toFloat() }

            AudioTelemetry(
                normalizedAmplitude = obj.optDouble("normalized_amplitude", 0.08).toFloat(),
                peakAmplitude = obj.optDouble("peak_amplitude", 0.0).toFloat(),
                rmsDb = obj.optDouble("rms_db", -96.0).toFloat(),
                snrDb = obj.optDouble("snr_db", 0.0).toFloat(),
                isSpeech = obj.optBoolean("is_speech", false),
                noiseFloor = obj.optDouble("noise_floor", 300.0).toFloat(),
                dominantFrequencyHz = obj.optDouble("dominant_frequency_hz", 0.0).toFloat(),
                spectrumBands = bands,
                sampleRate = obj.optInt("sample_rate", sampleRate)
            )
        } catch (e: Throwable) {
            Log.w(TAG, "computeAudioTelemetryNative error: ${e.message}")
            AudioTelemetry(
                normalizedAmplitude = 0.08f,
                peakAmplitude = 0.0f,
                rmsDb = -96.0f,
                snrDb = 0.0f,
                isSpeech = false,
                noiseFloor = 300.0f,
                dominantFrequencyHz = 0.0f,
                spectrumBands = FloatArray(8),
                sampleRate = sampleRate
            )
        }
    }

    /**
     * Indexes a document into the native full-text (BM25) and hybrid vector database.
     */
    fun indexDocument(doc: SearchDocument): Boolean {
        if (!isLoaded || doc.id.isBlank()) return false
        return try {
            val obj = JSONObject()
            obj.put("id", doc.id)
            obj.put("title", doc.title)
            obj.put("body", doc.body)
            val embArr = JSONArray()
            for (v in doc.embedding) embArr.put(v.toDouble())
            obj.put("embedding", embArr)
            val metaObj = JSONObject()
            for ((k, v) in doc.metadata) metaObj.put(k, v)
            obj.put("metadata", metaObj)
            indexDocumentNative(obj.toString())
        } catch (e: Throwable) {
            Log.w(TAG, "indexDocumentNative error: ${e.message}")
            false
        }
    }

    /**
     * Performs hybrid search combining BM25 lexical ranking and embedding cosine similarity.
     */
    fun searchHybrid(
        queryText: String,
        queryEmbedding: FloatArray = floatArrayOf(),
        alpha: Float = 0.5f,
        topK: Int = 10
    ): List<SearchResultItem> {
        if (!isLoaded || (queryText.isBlank() && queryEmbedding.isEmpty())) return emptyList()
        return try {
            val resJson = searchHybridNative(queryText, queryEmbedding, alpha, topK)
            val resArr = JSONArray(resJson)
            (0 until resArr.length()).map { i ->
                val o = resArr.getJSONObject(i)
                val meta = mutableMapOf<String, String>()
                val mObj = o.optJSONObject("metadata")
                if (mObj != null) {
                    val keys = mObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        meta[k] = mObj.optString(k, "")
                    }
                }
                SearchResultItem(
                    id = o.optString("id", ""),
                    score = o.optDouble("score", 0.0).toFloat(),
                    bm25Score = o.optDouble("bm25_score", 0.0).toFloat(),
                    vectorScore = o.optDouble("vector_score", 0.0).toFloat(),
                    title = o.optString("title", ""),
                    snippet = o.optString("snippet", ""),
                    metadata = meta
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "searchHybridNative error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Returns statistics of the native full-text and vector search database.
     */
    fun getIndexStats(): IndexStats {
        if (!isLoaded) return IndexStats(0, 0, 0, 0.0f)
        return try {
            val resJson = getIndexStatsNative()
            val obj = JSONObject(resJson)
            IndexStats(
                totalDocuments = obj.optInt("total_documents", 0),
                uniqueTerms = obj.optInt("unique_terms", 0),
                totalPostings = obj.optInt("total_postings", 0),
                avgDocLength = obj.optDouble("avg_doc_length", 0.0).toFloat()
            )
        } catch (e: Throwable) {
            Log.w(TAG, "getIndexStatsNative error: ${e.message}")
            IndexStats(0, 0, 0, 0.0f)
        }
    }

    /**
     * Records an HTTP transaction into native ring-buffer traffic history.
     */
    fun recordNetworkTraffic(entry: NetworkTrafficEntry): Boolean {
        if (!isLoaded) return false
        return try {
            val obj = JSONObject()
            obj.put("id", entry.id)
            obj.put("timestamp", entry.timestamp)
            obj.put("method", entry.method)
            obj.put("url", entry.url)
            obj.put("status_code", entry.statusCode)
            obj.put("duration_ms", entry.durationMs)
            obj.put("bytes_sent", entry.bytesSent)
            obj.put("bytes_received", entry.bytesReceived)
            obj.put("client_ip", entry.clientIp)
            if (entry.error != null) {
                obj.put("error", entry.error)
            }
            recordNetworkTrafficNative(obj.toString())
        } catch (e: Throwable) {
            Log.w(TAG, "recordNetworkTraffic error: ${e.message}")
            false
        }
    }

    /**
     * Retrieves the most recent recorded network traffic transactions.
     */
    fun getRecentNetworkTraffic(limit: Int = 50): List<NetworkTrafficEntry> {
        if (!isLoaded) return emptyList()
        return try {
            val resJson = getRecentNetworkTrafficNative(limit)
            val arr = JSONArray(resJson)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                NetworkTrafficEntry(
                    id = o.optString("id", ""),
                    timestamp = o.optLong("timestamp", 0L),
                    method = o.optString("method", "GET"),
                    url = o.optString("url", ""),
                    statusCode = o.optInt("status_code", 200),
                    durationMs = o.optLong("duration_ms", 0L),
                    bytesSent = o.optLong("bytes_sent", 0L),
                    bytesReceived = o.optLong("bytes_received", 0L),
                    clientIp = o.optString("client_ip", "127.0.0.1"),
                    error = if (o.isNull("error")) null else o.optString("error", null)
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "getRecentNetworkTraffic error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Computes real-time bandwidth metrics, total bytes sent/received, and error counts.
     */
    fun getNetworkMetrics(): NetworkMetrics {
        if (!isLoaded) return NetworkMetrics(0L, 0L, 0, 0L, 0L, 0L, 0.0f, 0.0f)
        return try {
            val resJson = getNetworkMetricsNative()
            val o = JSONObject(resJson)
            NetworkMetrics(
                totalBytesSent = o.optLong("total_bytes_sent", 0L),
                totalBytesReceived = o.optLong("total_bytes_received", 0L),
                activeConnections = o.optInt("active_connections", 0),
                requestCount = o.optLong("request_count", 0L),
                responseCount = o.optLong("response_count", 0L),
                errorCount = o.optLong("error_count", 0L),
                uploadBps = o.optDouble("upload_bps", 0.0).toFloat(),
                downloadBps = o.optDouble("download_bps", 0.0).toFloat()
            )
        } catch (e: Throwable) {
            Log.w(TAG, "getNetworkMetrics error: ${e.message}")
            NetworkMetrics(0L, 0L, 0, 0L, 0L, 0L, 0.0f, 0.0f)
        }
    }

    /**
     * Clears recorded network traffic history and counters.
     */
    fun clearNetworkTraffic() {
        if (!isLoaded) return
        try {
            clearNetworkTrafficNative()
        } catch (e: Throwable) {
            Log.w(TAG, "clearNetworkTraffic error: ${e.message}")
        }
    }
}



