package com.example.gemini.assistant.tasks

import java.util.UUID

enum class TaskRecurrence {
    NONE,
    DAILY,
    WEEKLY,
    MONTHLY
}

enum class TaskPriority(val value: Int, val label: String) {
    LOW(0, "Low"),
    NORMAL(1, "Normal"),
    HIGH(2, "High");

    companion object {
        fun fromValue(v: Int): TaskPriority = entries.find { it.value == v } ?: NORMAL
    }
}

/**
 * Native Smart Task entity ported and enhanced from Smart-To-Do.
 * Supports note-first input, recurrence, sync revisions, device attribution, and tombstones.
 */
data class SmartTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val notes: String = "",
    val dueAt: Long? = null, // epoch millis UTC
    val priority: TaskPriority = TaskPriority.NORMAL,
    val category: String = "",
    val completed: Boolean = false,
    val recurrence: TaskRecurrence = TaskRecurrence.NONE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val revision: Int = 0,
    val deviceId: String = "",
    val deleted: Boolean = false
) {
    /**
     * Smart headline for notification and widget previews.
     * Uses title or first line of notes if title is blank.
     */
    val displayHeadline: String
        get() = title.ifBlank {
            notes.lines().firstOrNull()?.take(60) ?: "Untitled Task"
        }

    val displayNotes: String
        get() = notes.ifBlank { title }

    fun copyWith(
        title: String? = null,
        notes: String? = null,
        dueAt: Long? = null,
        clearDueAt: Boolean = false,
        priority: TaskPriority? = null,
        category: String? = null,
        completed: Boolean? = null,
        recurrence: TaskRecurrence? = null,
        updatedAt: Long? = null,
        revision: Int? = null,
        deviceId: String? = null,
        deleted: Boolean? = null
    ): SmartTask = SmartTask(
        id = this.id,
        title = title ?: this.title,
        notes = notes ?: this.notes,
        dueAt = if (clearDueAt) null else (dueAt ?: this.dueAt),
        priority = priority ?: this.priority,
        category = category ?: this.category,
        completed = completed ?: this.completed,
        recurrence = recurrence ?: this.recurrence,
        createdAt = this.createdAt,
        updatedAt = updatedAt ?: System.currentTimeMillis(),
        revision = revision ?: (this.revision + 1),
        deviceId = deviceId ?: this.deviceId,
        deleted = deleted ?: this.deleted
    )
}
