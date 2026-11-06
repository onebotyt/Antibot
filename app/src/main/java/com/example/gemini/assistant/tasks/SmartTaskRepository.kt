package com.example.gemini.assistant.tasks

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.UUID

class SmartTaskRepository(
    private val context: Context,
    private val dbHelper: SmartTaskDatabaseHelper = SmartTaskDatabaseHelper.getInstance(context)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceId: String by lazy { dbHelper.getDeviceId() }

    private val _tasks = MutableStateFlow<List<SmartTask>>(emptyList())
    val tasks: StateFlow<List<SmartTask>> = _tasks.asStateFlow()

    private val _categories = MutableStateFlow<List<String>>(emptyList())
    val categories: StateFlow<List<String>> = _categories.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        scope.launch {
            val list = queryTasks(includeCompleted = true)
            val cats = queryCategories()
            _tasks.value = list
            _categories.value = cats
        }
    }

    suspend fun listTasks(
        includeCompleted: Boolean = true,
        category: String? = null,
        query: String = ""
    ): List<SmartTask> = withContext(Dispatchers.IO) {
        queryTasks(includeCompleted, category, query)
    }

    suspend fun getTaskById(id: String): SmartTask? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            SmartTaskDatabaseHelper.TABLE_TASKS,
            null,
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1"
        )
        cursor.use {
            if (it.moveToFirst()) parseTask(it) else null
        }
    }

    suspend fun createTask(
        title: String,
        notes: String = "",
        dueAt: Long? = null,
        priority: TaskPriority = TaskPriority.NORMAL,
        category: String = "",
        recurrence: TaskRecurrence = TaskRecurrence.NONE
    ): SmartTask = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val task = SmartTask(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            notes = notes.trim(),
            dueAt = dueAt,
            priority = priority,
            category = category.trim(),
            recurrence = recurrence,
            createdAt = now,
            updatedAt = now,
            revision = 0,
            deviceId = deviceId,
            deleted = false
        )

        saveTaskInTransaction(task, op = "create")

        // Schedule notification alarm if dueAt is in the future
        if (dueAt != null && dueAt > now) {
            SmartTaskAlarmScheduler.scheduleAlarm(context, task)
        }

        reload()
        task
    }

    suspend fun updateTask(task: SmartTask): SmartTask = withContext(Dispatchers.IO) {
        val updated = task.copyWith(
            updatedAt = System.currentTimeMillis(),
            deviceId = deviceId
        )

        saveTaskInTransaction(updated, op = "update")

        if (updated.dueAt != null && updated.dueAt > System.currentTimeMillis() && !updated.completed && !updated.deleted) {
            SmartTaskAlarmScheduler.scheduleAlarm(context, updated)
        } else {
            SmartTaskAlarmScheduler.cancelAlarm(context, updated.id)
        }

        reload()
        updated
    }

    suspend fun toggleComplete(taskId: String): SmartTask? = withContext(Dispatchers.IO) {
        val existing = getTaskById(taskId) ?: return@withContext null
        val newCompleted = !existing.completed

        // If completing a recurring task, schedule the next recurrence
        if (newCompleted && existing.recurrence != TaskRecurrence.NONE) {
            val nextDue = calculateNextRecurrence(existing.dueAt ?: System.currentTimeMillis(), existing.recurrence)
            createTask(
                title = existing.title,
                notes = existing.notes,
                dueAt = nextDue,
                priority = existing.priority,
                category = existing.category,
                recurrence = existing.recurrence
            )
        }

        val updated = existing.copyWith(
            completed = newCompleted,
            updatedAt = System.currentTimeMillis(),
            deviceId = deviceId
        )

        saveTaskInTransaction(updated, op = "update")

        if (newCompleted) {
            SmartTaskAlarmScheduler.cancelAlarm(context, taskId)
        }

        reload()
        updated
    }

    suspend fun deleteTask(taskId: String): Boolean = withContext(Dispatchers.IO) {
        val existing = getTaskById(taskId) ?: return@withContext false
        val tombstone = existing.copyWith(
            deleted = true,
            updatedAt = System.currentTimeMillis(),
            deviceId = deviceId
        )

        saveTaskInTransaction(tombstone, op = "delete")
        SmartTaskAlarmScheduler.cancelAlarm(context, taskId)

        reload()
        true
    }

    private fun saveTaskInTransaction(task: SmartTask, op: String) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("id", task.id)
                put("title", task.title)
                put("notes", task.notes)
                put("due_at", task.dueAt)
                put("priority", task.priority.value)
                put("category", task.category)
                put("completed", if (task.completed) 1 else 0)
                put("recurrence", task.recurrence.name)
                put("created_at", task.createdAt)
                put("updated_at", task.updatedAt)
                put("revision", task.revision)
                put("device_id", task.deviceId)
                put("deleted", if (task.deleted) 1 else 0)
            }
            db.insertWithOnConflict(
                SmartTaskDatabaseHelper.TABLE_TASKS,
                null,
                cv,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
            )

            val logCv = ContentValues().apply {
                put("task_id", task.id)
                put("op", op)
                put("revision", task.revision)
                put("device_id", task.deviceId)
                put("at", task.updatedAt)
                put("synced", 0)
            }
            db.insert(SmartTaskDatabaseHelper.TABLE_CHANGE_LOG, null, logCv)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun queryTasks(
        includeCompleted: Boolean = true,
        category: String? = null,
        query: String = ""
    ): List<SmartTask> {
        val db = dbHelper.readableDatabase
        val selectionParts = mutableListOf("deleted = 0")
        val selectionArgs = mutableListOf<String>()

        if (!includeCompleted) {
            selectionParts.add("completed = 0")
        }

        if (!category.isNullOrBlank()) {
            selectionParts.add("category = ?")
            selectionArgs.add(category.trim())
        }

        if (query.isNotBlank()) {
            selectionParts.add("(title LIKE ? OR notes LIKE ? OR category LIKE ?)")
            val wildcard = "%${query.trim()}%"
            selectionArgs.add(wildcard)
            selectionArgs.add(wildcard)
            selectionArgs.add(wildcard)
        }

        val orderBy = "completed ASC, priority DESC, due_at IS NULL, due_at ASC, created_at DESC"

        val cursor = db.query(
            SmartTaskDatabaseHelper.TABLE_TASKS,
            null,
            selectionParts.joinToString(" AND "),
            if (selectionArgs.isEmpty()) null else selectionArgs.toTypedArray(),
            null,
            null,
            orderBy
        )

        val results = mutableListOf<SmartTask>()
        cursor.use {
            while (it.moveToNext()) {
                results.add(parseTask(it))
            }
        }
        return results
    }

    private fun queryCategories(): List<String> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT DISTINCT category FROM ${SmartTaskDatabaseHelper.TABLE_TASKS} WHERE deleted = 0 AND category != '' ORDER BY category ASC",
            null
        )
        val list = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(it.getString(0))
            }
        }
        return list
    }

    private fun parseTask(c: Cursor): SmartTask {
        return SmartTask(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            title = c.getString(c.getColumnIndexOrThrow("title")),
            notes = c.getString(c.getColumnIndexOrThrow("notes")),
            dueAt = if (c.isNull(c.getColumnIndexOrThrow("due_at"))) null else c.getLong(c.getColumnIndexOrThrow("due_at")),
            priority = TaskPriority.fromValue(c.getInt(c.getColumnIndexOrThrow("priority"))),
            category = c.getString(c.getColumnIndexOrThrow("category")),
            completed = c.getInt(c.getColumnIndexOrThrow("completed")) == 1,
            recurrence = runCatching { TaskRecurrence.valueOf(c.getString(c.getColumnIndexOrThrow("recurrence"))) }.getOrDefault(TaskRecurrence.NONE),
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
            updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
            revision = c.getInt(c.getColumnIndexOrThrow("revision")),
            deviceId = c.getString(c.getColumnIndexOrThrow("device_id")),
            deleted = c.getInt(c.getColumnIndexOrThrow("deleted")) == 1
        )
    }

    private fun calculateNextRecurrence(baseTime: Long, recurrence: TaskRecurrence): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = baseTime }
        when (recurrence) {
            TaskRecurrence.DAILY -> cal.add(Calendar.DAY_OF_YEAR, 1)
            TaskRecurrence.WEEKLY -> cal.add(Calendar.WEEK_OF_YEAR, 1)
            TaskRecurrence.MONTHLY -> cal.add(Calendar.MONTH, 1)
            TaskRecurrence.NONE -> {}
        }
        return cal.timeInMillis
    }
}
