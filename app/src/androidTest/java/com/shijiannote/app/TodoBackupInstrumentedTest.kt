package com.shijiannote.app

import android.app.Application
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.shijiannote.app.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class TodoBackupInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun newBackupsKeepTimingAndTrashWhileOldBackupsFilterOnlyTodos() = runBlocking {
        val model = withContext(Dispatchers.Main) { WorkspaceModel(context.applicationContext as Application) }
        val recovery = ExportEngine.backup(context, model, ExportOptions())
        val fixture = File(context.cacheDir, "legacy-todo-${UUID.randomUUID()}.zip")
        try {
            val note = NoteNode(id = "backup-todo-${UUID.randomUUID()}", parentId = MemorySpaces.WORK_ID, text = "保留的记忆", tags = "标签")
            model.notes.put(note)
            model.notes.version(NoteVersion(nodeId = note.id, snapshot = jsonObject(note).toString()))
            val at = dayMillis(java.time.LocalDate.now().plusDays(2)) + 9 * 3_600_000L
            val unifiedId = model.dao.insertTodoBoard(TodoBoard(summary = "统一重复", reminderAt = at,
                reminderBaseAt = at, reminderRule = ReminderScheduler.RULE_DAILY, reminderSkipAt = at))
            model.dao.insertTodoItems(listOf(TodoItem(boardId = unifiedId, text = "继承时间")))
            val independentId = model.dao.insertTodoBoard(TodoBoard(summary = "独立重复", timeMode = "INDEPENDENT"))
            val itemId = model.dao.insertTodoItems(listOf(TodoItem(boardId = independentId, text = "独立时间", important = true,
                reminderAt = at, reminderBaseAt = at, reminderRule = ReminderScheduler.RULE_CUSTOM_DAYS,
                reminderCustomDays = 3, reminderSkipAt = at, dueAt = at + 7 * 86_400_000L))).single()
            val deletedId = model.dao.insertTodoBoard(TodoBoard(summary = "已删除", timeMode = "INDEPENDENT", archived = true, deletedAt = 123))
            model.dao.insertTodoItems(listOf(TodoItem(boardId = deletedId, text = "垃圾事项", deletedAt = 123)))
            val scheduleId = model.dao.insertSchedule(ScheduleEvent(title = "保留的事务", eventAt = at,
                reminderDays = 0, reminderHours = 0, reminderMinutes = 0, important = true))
            val backup = ExportEngine.backup(context, model, ExportOptions())
            model.dao.clearTodos()
            ExportEngine.restore(context, model, Uri.fromFile(backup.file))
            assertEquals("UNIFIED", model.dao.getTodoBoard(unifiedId)!!.timeMode)
            assertEquals(at, model.dao.getTodoBoard(unifiedId)!!.reminderSkipAt)
            assertEquals("INDEPENDENT", model.dao.getTodoBoard(independentId)!!.timeMode)
            val item = model.dao.getTodoItem(itemId)!!
            assertEquals(ReminderScheduler.RULE_CUSTOM_DAYS, item.reminderRule)
            assertEquals(3, item.reminderCustomDays)
            assertEquals(at, item.reminderSkipAt)
            assertTrue(item.important)
            assertEquals(123L, model.dao.getTodoBoard(deletedId)!!.deletedAt)
            assertTrue(model.dao.getTodoBoard(deletedId)!!.archived)

            // Transform only the format marker to exercise the real old-backup restore path.
            ZipFile(backup.file).use { source -> ZipOutputStream(fixture.outputStream()).use { target ->
                source.entries().asSequence().forEach { entry ->
                    target.putNextEntry(ZipEntry(entry.name))
                    source.getInputStream(entry).use { input ->
                        if (entry.name == "backup.json") {
                            val json = JSONObject(input.bufferedReader().readText()).put("databaseVersion", 11)
                            json.remove("todoSchemaVersion")
                            target.write(json.toString().toByteArray(Charsets.UTF_8))
                        } else input.copyTo(target)
                    }
                    target.closeEntry()
                }
            } }
            val message = ExportEngine.restore(context, model, Uri.fromFile(fixture))
            assertTrue(message.contains("旧版待办"))
            assertTrue(model.dao.allTodos().isEmpty())
            assertEquals(note.text, model.notes.node(note.id)!!.text)
            assertEquals(note.tags, model.notes.node(note.id)!!.tags)
            assertTrue(model.notes.versions(note.id).isNotEmpty())
            assertTrue(model.dao.allSchedule().single { it.id == scheduleId }.important)
        } finally {
            ExportEngine.restore(context, model, Uri.fromFile(recovery.file))
            fixture.delete()
        }
    }
}
