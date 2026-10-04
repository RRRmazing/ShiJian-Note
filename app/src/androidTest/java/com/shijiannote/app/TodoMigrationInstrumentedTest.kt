package com.shijiannote.app

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.shijiannote.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TodoMigrationInstrumentedTest {
    @Test fun versionElevenUpgradeClearsAllOldTodoStatesPreservesOtherModulesAndRunsOnce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "todo-v11-${UUID.randomUUID()}.db"
        val initial = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val dao = initial.appDao()
        val oldBoards = listOf(
            TodoBoard(summary = "活动清单"),
            TodoBoard(summary = "已归档", archived = true),
            TodoBoard(summary = "回收站", deletedAt = 123L)
        ).map { dao.insertTodoBoard(it) }
        val oldItems = oldBoards.map { dao.insertTodoItems(listOf(TodoItem(boardId = it, text = "旧事项"))).single() }
        val schedule = dao.insertSchedule(ScheduleEvent(title = "保留时间表", eventAt = 123, reminderDays = 0, reminderHours = 0, reminderMinutes = 0))
        initial.noteDao().put(NoteNode(id = "preserved", text = "保留正文", tags = "保留标签", deletedAt = 456))
        initial.noteDao().version(NoteVersion(nodeId = "preserved", snapshot = "保留版本"))
        initial.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
            listOf("timeMode", "reminderSkipAt").forEach { sqlite.execSQL("ALTER TABLE todo_boards DROP COLUMN $it") }
            listOf("reminderRule", "reminderBaseAt", "reminderCustomDays", "reminderSkipAt").forEach { sqlite.execSQL("ALTER TABLE todo_items DROP COLUMN $it") }
            sqlite.execSQL("DROP TABLE legacy_todo_alarms")
            sqlite.execSQL("DROP TABLE room_master_table")
            sqlite.version = 11
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.migration11To12).build()
        val newId: Long
        try {
            assertTrue(migrated.appDao().allTodos().isEmpty())
            assertEquals(schedule, migrated.appDao().allSchedule().single().id)
            assertEquals("保留正文", migrated.noteDao().node("preserved")!!.text)
            assertEquals("保留标签", migrated.noteDao().node("preserved")!!.tags)
            assertEquals(456L, migrated.noteDao().node("preserved")!!.deletedAt)
            assertEquals("保留版本", migrated.noteDao().versions("preserved").single().snapshot)
            val queued = mutableSetOf<Pair<String, Long>>()
            migrated.openHelper.readableDatabase.query("SELECT kind, legacyId FROM legacy_todo_alarms").use { rows ->
                while (rows.moveToNext()) queued += rows.getString(0) to rows.getLong(1)
            }
            assertEquals(oldBoards.map { "board" to it }.toSet() + oldItems.map { "item" to it }.toSet(), queued)
            newId = migrated.appDao().insertTodoBoard(TodoBoard(summary = "新版清单", timeMode = "INDEPENDENT"))
        } finally { migrated.close() }
        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.migration11To12).build()
        try { assertEquals("新版清单", reopened.appDao().getTodoBoard(newId)!!.summary) }
        finally { reopened.close(); context.deleteDatabase(name) }
    }
}
