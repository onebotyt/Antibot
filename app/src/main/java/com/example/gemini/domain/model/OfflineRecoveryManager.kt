package com.example.gemini.domain.model

/**
 * Task record saved by AntiBot Smart LLM Manager when an active turn is interrupted
 * due to network loss or internet disconnect.
 */
data class InterruptedTask(
    val conversationId: String,
    val prompt: String,
    val preferredModelId: String = "MODEL_GEMINI_3_8_FLASH_HIGH",
    val timestamp: Long = System.currentTimeMillis(),
    val reason: String = "Internet disconnected"
)

/**
 * Smart LLM Manager Offline Flight Recorder & Auto-Recovery Queue.
 *
 * Automatically captures interrupted chats when the internet drops,
 * and resumes them seamlessly using Gemini when connectivity returns.
 */
object OfflineRecoveryManager {
    @Volatile
    var pendingTask: InterruptedTask? = null
        private set

    fun recordInterruptedTask(
        conversationId: String,
        prompt: String,
        preferredModelId: String = "MODEL_GEMINI_3_8_FLASH_HIGH",
        reason: String = "Internet disconnected"
    ) {
        if (conversationId.isBlank() || prompt.isBlank()) return
        pendingTask = InterruptedTask(
            conversationId = conversationId,
            prompt = prompt,
            preferredModelId = preferredModelId,
            reason = reason
        )
    }

    fun popPendingTask(): InterruptedTask? {
        val task = pendingTask
        pendingTask = null
        return task
    }

    fun clearPendingTask() {
        pendingTask = null
    }

    fun hasPendingTask(): Boolean = pendingTask != null
}
