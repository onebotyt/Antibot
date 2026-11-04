package com.example.gemini.domain.context

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * TaskFlightRecorder: Ultra-lightweight on-device execution checkpoint recorder.
 *
 * Persists live step progress, in-flight tool actions, and objective context
 * so that unexpected halts (process kills, OOMs, phone restarts) can be
 * resumed instantly without scanning full historical logs or guessing state.
 */
object TaskFlightRecorder {

    private const val TAG = "TaskFlightRecorder"

    data class Checkpoint(
        val conversationId: String,
        val userObjective: String,
        val status: String, // "RUNNING", "HALTED", "COMPLETED"
        val completedActions: List<String>,
        val inFlightTool: String,
        val inFlightDetail: String,
        val timestamp: Long
    )

    private fun getStorageDir(): File {
        val homePath = System.getenv("HOME") ?: "/data/data/com.termux/files/home"
        val dir = File(homePath, ".gemini/antigravity-cli/brain/checkpoints")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getCheckpointFile(conversationId: String): File {
        val safeId = conversationId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(getStorageDir(), "checkpoint_${safeId}.json")
    }

    fun recordTurnStart(conversationId: String, userObjective: String) {
        if (conversationId.isBlank()) return
        try {
            val file = getCheckpointFile(conversationId)
            val json = JSONObject().apply {
                put("conversation_id", conversationId)
                put("user_objective", userObjective)
                put("status", "RUNNING")
                put("completed_actions", JSONArray())
                put("in_flight_tool", "")
                put("in_flight_detail", "")
                put("timestamp", System.currentTimeMillis())
            }
            file.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record turn start: ${e.message}")
        }
    }

    fun recordActionInFlight(conversationId: String, toolName: String, detail: String) {
        if (conversationId.isBlank()) return
        try {
            val file = getCheckpointFile(conversationId)
            val current = if (file.exists()) {
                try { JSONObject(file.readText()) } catch (_: Exception) { JSONObject() }
            } else JSONObject()

            current.put("status", "RUNNING")
            current.put("in_flight_tool", toolName)
            current.put("in_flight_detail", detail.take(300))
            current.put("timestamp", System.currentTimeMillis())
            file.writeText(current.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record in-flight action: ${e.message}")
        }
    }

    fun recordActionCompleted(conversationId: String, actionSummary: String) {
        if (conversationId.isBlank()) return
        try {
            val file = getCheckpointFile(conversationId)
            val current = if (file.exists()) {
                try { JSONObject(file.readText()) } catch (_: Exception) { JSONObject() }
            } else JSONObject()

            val completedArray = current.optJSONArray("completed_actions") ?: JSONArray()
            completedArray.put(actionSummary.take(150))
            current.put("completed_actions", completedArray)
            current.put("in_flight_tool", "")
            current.put("in_flight_detail", "")
            current.put("timestamp", System.currentTimeMillis())
            file.writeText(current.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record completed action: ${e.message}")
        }
    }

    fun recordTurnCompleted(conversationId: String) {
        if (conversationId.isBlank()) return
        try {
            val file = getCheckpointFile(conversationId)
            if (file.exists()) {
                val current = try { JSONObject(file.readText()) } catch (_: Exception) { JSONObject() }
                current.put("status", "COMPLETED")
                current.put("in_flight_tool", "")
                current.put("in_flight_detail", "")
                current.put("timestamp", System.currentTimeMillis())
                file.writeText(current.toString(2))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record turn completion: ${e.message}")
        }
    }

    fun checkInterruption(conversationId: String): Checkpoint? {
        if (conversationId.isBlank()) return null
        return try {
            val file = getCheckpointFile(conversationId)
            if (!file.exists()) return null
            val obj = JSONObject(file.readText())
            val status = obj.optString("status", "")
            if (status != "RUNNING" && status != "HALTED") return null

            val inFlightTool = obj.optString("in_flight_tool", "")
            val inFlightDetail = obj.optString("in_flight_detail", "")
            val completedJson = obj.optJSONArray("completed_actions")
            val completedList = mutableListOf<String>()
            if (completedJson != null) {
                for (i in 0 until completedJson.length()) {
                    completedList.add(completedJson.getString(i))
                }
            }

            Checkpoint(
                conversationId = conversationId,
                userObjective = obj.optString("user_objective", ""),
                status = "HALTED",
                completedActions = completedList,
                inFlightTool = inFlightTool,
                inFlightDetail = inFlightDetail,
                timestamp = obj.optLong("timestamp", System.currentTimeMillis())
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error checking interruption: ${e.message}")
            null
        }
    }

    fun generateResumePrompt(checkpoint: Checkpoint): String {
        return buildString {
            appendLine("=== [FLIGHT RECORDER: RESUMPTION DIRECTIVE] ===")
            appendLine("Previous task execution was unexpectedly halted by the system/host.")
            if (checkpoint.userObjective.isNotBlank()) {
                appendLine("Objective: \"${checkpoint.userObjective}\"")
            }
            if (checkpoint.completedActions.isNotEmpty()) {
                appendLine("Completed Actions (Do NOT re-execute):")
                checkpoint.completedActions.takeLast(8).forEach { act ->
                    appendLine("  ✓ $act")
                }
            }
            if (checkpoint.inFlightTool.isNotBlank()) {
                appendLine("Interrupted Action:")
                appendLine("  ⚡ Tool: ${checkpoint.inFlightTool}")
                if (checkpoint.inFlightDetail.isNotBlank()) {
                    appendLine("  ⚡ Details: ${checkpoint.inFlightDetail}")
                }
            }
            appendLine("INSTRUCTION: Resume directly from the interrupted action. Do not re-run already completed steps or scan the full conversation history.")
            appendLine("================================================")
        }
    }

    fun clearCheckpoint(conversationId: String) {
        try {
            val file = getCheckpointFile(conversationId)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {}
    }
}
