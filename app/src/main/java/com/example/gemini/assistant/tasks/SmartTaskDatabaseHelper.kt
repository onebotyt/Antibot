package com.example.gemini.assistant.tasks

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.UUID

/**
 * SQLite database helper for Smart-To-Do task storage, change tracking, and device pairings.
 * 100% offline, local-first transactional storage.
 */
class SmartTaskDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "antibot_smart_tasks.db"
        const val DATABASE_VERSION = 3

        const val TABLE_TASKS = "tasks"
        const val TABLE_CHANGE_LOG = "change_log"
        const val TABLE_DEVICES = "devices"
        const val TABLE_META = "meta"

        // Singleton instance
        @Volatile
        private var instance: SmartTaskDatabaseHelper? = null

        fun getInstance(context: Context): SmartTaskDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: SmartTaskDatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_TASKS (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                due_at INTEGER,
                priority INTEGER NOT NULL DEFAULT 1,
                category TEXT NOT NULL DEFAULT '',
                completed INTEGER NOT NULL DEFAULT 0,
                recurrence TEXT NOT NULL DEFAULT 'NONE',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                revision INTEGER NOT NULL DEFAULT 0,
                device_id TEXT NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_due ON $TABLE_TASKS(due_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_deleted ON $TABLE_TASKS(deleted)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_category ON $TABLE_TASKS(category)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_CHANGE_LOG (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id TEXT NOT NULL,
                op TEXT NOT NULL,
                revision INTEGER NOT NULL,
                device_id TEXT NOT NULL,
                at INTEGER NOT NULL,
                synced INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_change_log_synced ON $TABLE_CHANGE_LOG(synced)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_DEVICES (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                fingerprint TEXT NOT NULL,
                ip_address TEXT,
                port INTEGER NOT NULL DEFAULT 43884,
                paired_at INTEGER NOT NULL,
                last_seen INTEGER,
                trusted INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_devices_trusted ON $TABLE_DEVICES(trusted)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_META (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
        """.trimIndent())

        // Initialize device id if not present
        val deviceId = UUID.randomUUID().toString()
        db.execSQL("INSERT OR IGNORE INTO $TABLE_META (key, value) VALUES ('device_id', '$deviceId')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_deleted ON $TABLE_TASKS(deleted)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_change_log_synced ON $TABLE_CHANGE_LOG(synced)")
        }
        if (oldVersion < 3) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_DEVICES (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    fingerprint TEXT NOT NULL,
                    ip_address TEXT,
                    port INTEGER NOT NULL DEFAULT 43884,
                    paired_at INTEGER NOT NULL,
                    last_seen INTEGER,
                    trusted INTEGER NOT NULL DEFAULT 1
                )
            """.trimIndent())
        }
    }

    fun getDeviceId(): String {
        val cursor = readableDatabase.rawQuery("SELECT value FROM $TABLE_META WHERE key = 'device_id'", null)
        cursor.use {
            if (it.moveToFirst()) {
                return it.getString(0)
            }
        }
        val newId = UUID.randomUUID().toString()
        writableDatabase.execSQL("INSERT OR REPLACE INTO $TABLE_META (key, value) VALUES ('device_id', '$newId')")
        return newId
    }
}
