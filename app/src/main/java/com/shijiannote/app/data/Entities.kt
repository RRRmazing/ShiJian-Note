package com.shijiannote.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "schedule_events")
data class ScheduleEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val eventAt: Long,
    val reminderDays: Int,
    val reminderHours: Int,
    val reminderMinutes: Int,
    val note: String = "",
    val archived: Boolean = false,
    val reminderTriggered: Boolean = false,
    /** Keeps an intentional on-time reminder distinct from no reminder at all. */
    val reminderEnabled: Boolean = false
)

@Entity(tableName = "todo_boards")
data class TodoBoard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val summary: String,
    /** Optional time on the deadline day from which the advance reminder is calculated. */
    val reminderAt: Long? = null,
    val reminderDays: Int = 0,
    val reminderHours: Int = 0,
    val dueDate: Long? = null,
    val reminderMinutes: Int = 0,
    val expanded: Boolean = true,
    val reminderTriggered: Boolean = false,
    /** Null is a one-time advance reminder; non-null is a recurring reminder rule. */
    val reminderRule: String? = null,
    val reminderBaseAt: Long? = null,
    val reminderCustomDays: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
    val position: Int = 0,
    /** LIST is a legacy/free-form list; TODAY and TOMORROW are the fixed daily pages. */
    val boardType: String = "LIST"
)

@Entity(
    tableName = "todo_items",
    foreignKeys = [ForeignKey(
        entity = TodoBoard::class,
        parentColumns = ["id"],
        childColumns = ["boardId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("boardId")]
)
data class TodoItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val boardId: Long,
    val text: String,
    val completed: Boolean = false,
    val position: Int = 0,
    val important: Boolean = false,
    val reminderAt: Long? = null,
    val reminderHours: Int = 0,
    val reminderMinutes: Int = 0,
    val reminderTriggered: Boolean = false
)

/** A singleton row (id = 1) keeps daily page visibility and rollover checkpoint. */
@Entity(tableName = "daily_todo_preferences")
data class DailyTodoPreferences(
    @PrimaryKey val id: Int = 1,
    val showToday: Boolean = true,
    val showTomorrow: Boolean = true,
    val lastRolloverDay: Long = 0L
)
@Entity(tableName = "diary_entries", indices = [Index(value = ["day"], unique = true)])
data class DiaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val summary: String,
    val content: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "memory_categories")
data class MemoryCategory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "memory_entries",
    foreignKeys = [ForeignKey(
        entity = MemoryCategory::class,
        parentColumns = ["id"],
        childColumns = ["categoryId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("categoryId")]
)
data class MemoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val title: String,
    val content: String,
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
