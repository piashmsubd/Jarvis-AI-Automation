package com.jarvis.ai.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * JarvisMemoryDb — Persistent long-term memory for the AI agent.
 * 
 * Stores conversations, user facts, and task history in SQLite.
 * This data survives app restarts and is injected into LLM context
 * so Jarvis "remembers" past interactions.
 *
 * Modded by Piash
 */
class JarvisMemoryDb(context: Context) : SQLiteOpenHelper(
    context, DB_NAME, null, DB_VERSION
) {
    companion object {
        private const val TAG = "JarvisMemory"
        private const val DB_NAME = "jarvis_memory.db"
        private const val DB_VERSION = 1

        // Tables
        private const val T_CONVERSATIONS = "conversations"
        private const val T_FACTS = "user_facts"
        private const val T_TASKS = "task_history"

        @Volatile
        private var instance: JarvisMemoryDb? = null

        fun getInstance(context: Context): JarvisMemoryDb {
            return instance ?: synchronized(this) {
                instance ?: JarvisMemoryDb(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $T_CONVERSATIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE $T_FACTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                key TEXT NOT NULL UNIQUE,
                value TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE $T_TASKS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task TEXT NOT NULL,
                result TEXT,
                success INTEGER DEFAULT 1,
                timestamp INTEGER NOT NULL
            )
        """)

        Log.i(TAG, "Memory database created")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations
    }

    // ------------------------------------------------------------------ //
    //  Conversations                                                      //
    // ------------------------------------------------------------------ //

    fun saveMessage(role: String, content: String) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("role", role)
                put("content", content)
                put("timestamp", System.currentTimeMillis())
            }
            db.insert(T_CONVERSATIONS, null, values)

            // Keep only last 500 messages
            db.execSQL("DELETE FROM $T_CONVERSATIONS WHERE id NOT IN (SELECT id FROM $T_CONVERSATIONS ORDER BY id DESC LIMIT 500)")
        } catch (e: Exception) {
            Log.e(TAG, "saveMessage error", e)
        }
    }

    fun getRecentMessages(count: Int = 20): List<MemoryMessage> {
        val messages = mutableListOf<MemoryMessage>()
        try {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT role, content, timestamp FROM $T_CONVERSATIONS ORDER BY id DESC LIMIT ?",
                arrayOf(count.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    messages.add(MemoryMessage(
                        role = it.getString(0),
                        content = it.getString(1),
                        timestamp = it.getLong(2)
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getRecentMessages error", e)
        }
        return messages.reversed() // Oldest first
    }

    /**
     * Get a summary of conversation history for LLM context injection.
     * Returns last N messages formatted for the system prompt.
     */
    fun getMemoryContext(maxMessages: Int = 10): String {
        val messages = getRecentMessages(maxMessages)
        if (messages.isEmpty()) return ""

        val sb = StringBuilder("[MEMORY — Previous conversations]\n")
        for (msg in messages) {
            val time = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(msg.timestamp))
            sb.appendLine("[$time] ${msg.role}: ${msg.content.take(200)}")
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ //
    //  User Facts (long-term memory about the user)                       //
    // ------------------------------------------------------------------ //

    fun saveFact(key: String, value: String) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("key", key)
                put("value", value)
                put("updated_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict(T_FACTS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            Log.e(TAG, "saveFact error", e)
        }
    }

    fun getFact(key: String): String? {
        return try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT value FROM $T_FACTS WHERE key = ?", arrayOf(key))
            cursor.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) { null }
    }

    fun getAllFacts(): Map<String, String> {
        val facts = mutableMapOf<String, String>()
        try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT key, value FROM $T_FACTS ORDER BY updated_at DESC LIMIT 50", null)
            cursor.use {
                while (it.moveToNext()) {
                    facts[it.getString(0)] = it.getString(1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAllFacts error", e)
        }
        return facts
    }

    fun getFactsContext(): String {
        val facts = getAllFacts()
        if (facts.isEmpty()) return ""
        val sb = StringBuilder("[USER FACTS — Things I know about Boss]\n")
        facts.forEach { (k, v) -> sb.appendLine("- $k: $v") }
        return sb.toString()
    }

    // ------------------------------------------------------------------ //
    //  Task History                                                        //
    // ------------------------------------------------------------------ //

    fun saveTask(task: String, result: String?, success: Boolean = true) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("task", task)
                put("result", result)
                put("success", if (success) 1 else 0)
                put("timestamp", System.currentTimeMillis())
            }
            db.insert(T_TASKS, null, values)

            // Keep last 100 tasks
            db.execSQL("DELETE FROM $T_TASKS WHERE id NOT IN (SELECT id FROM $T_TASKS ORDER BY id DESC LIMIT 100)")
        } catch (e: Exception) {
            Log.e(TAG, "saveTask error", e)
        }
    }

    fun getRecentTasks(count: Int = 5): List<TaskRecord> {
        val tasks = mutableListOf<TaskRecord>()
        try {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT task, result, success, timestamp FROM $T_TASKS ORDER BY id DESC LIMIT ?",
                arrayOf(count.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    tasks.add(TaskRecord(
                        task = it.getString(0),
                        result = it.getString(1),
                        success = it.getInt(2) == 1,
                        timestamp = it.getLong(3)
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getRecentTasks error", e)
        }
        return tasks.reversed()
    }

    // ------------------------------------------------------------------ //
    //  Data Classes                                                        //
    // ------------------------------------------------------------------ //

    data class MemoryMessage(val role: String, val content: String, val timestamp: Long)
    data class TaskRecord(val task: String, val result: String?, val success: Boolean, val timestamp: Long)
}
