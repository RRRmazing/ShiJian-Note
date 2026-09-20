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
    val reminderTriggered: Boolean = false
)

@Entity(tableName = "todo_boards")
data class TodoBoard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val summary: String,
    val dueDate: Long? = null,
    val expanded: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false
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
    val position: Int = 0
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
    val createdAt: Long = System.currentTimeMillis()
)

