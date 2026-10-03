package com.shijiannote.app

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.shijiannote.app.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class WorkspaceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun failedSaveRetainsPreviousRecordAndRetrySucceeds() = runBlocking {
        val model = withContext(Dispatchers.Main) { WorkspaceModel(context.applicationContext as Application) }
        model.flushAll()
        val node = NoteNode(id = "save-failure-test", text = "之前的正文")
        model.save(node); model.flush(node.id)
        val blocked = File(context.filesDir, "drafts/${node.id}.json.new")
        blocked.mkdirs()
        try {
            model.save(node.copy(text = "修改后的正文"))
            assertTrue(runCatching { model.flush(node.id) }.isFailure)
            assertEquals("之前的正文", model.notes.node(node.id)!!.text)
            assertTrue(model.saveStates.value[node.id]!!.contains("失败"))
        } finally { blocked.delete() }
        model.flush(node.id)
        assertEquals("修改后的正文", model.notes.node(node.id)!!.text)
        model.notes.remove(listOf(node.id)); model.notes.removeVersions(listOf(node.id))
    }

    @Test fun restoreBranchAndMissingParentDoNotLoseChildren() = runBlocking {
        val model = withContext(Dispatchers.Main) { WorkspaceModel(context.applicationContext as Application) }
        val root = NoteNode(id = "recycle-root-test", kind = "folder", title = "父分类")
        val child = NoteNode(id = "recycle-child-test", parentId = root.id, text = "正文")
        model.notes.putAll(listOf(root, child))
        model.trash(setOf(root.id)).join()
        val group = model.notes.node(root.id)!!.deleteGroup!!
        assertEquals(group, model.notes.node(child.id)!!.deleteGroup)
        model.restore(group).join()
        assertEquals(root.id, model.notes.node(child.id)!!.parentId)
        model.trash(setOf(child.id)).join()
        val childGroup = model.notes.node(child.id)!!.deleteGroup!!
        model.trash(setOf(root.id)).join()
        model.permanentlyDelete(model.notes.node(root.id)!!.deleteGroup!!).join()
        model.restore(childGroup).join()
        assertNull(model.notes.node(child.id)!!.parentId)
        model.notes.remove(listOf(child.id)); model.notes.removeVersions(listOf(child.id))
    }

    @Test fun versionNineMigrationPreservesLegacyTextAndTaskStates() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val initial = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val dao = initial.appDao()
        val category = dao.insertMemoryCategory(MemoryCategory(name = "旧分类"))
        val id = dao.insertMemoryEntry(MemoryEntry(categoryId = category, title = "旧记忆", content = "# 原文\n**保持原样**"))
        dao.saveDiary(DiaryEntry(day = dayMillis(), summary = "旧日记", content = "旧内容"))
        val board = dao.insertTodoBoard(TodoBoard(summary = "过去未完成", boardType = "OVERDUE"))
        val task = dao.insertTodoItems(listOf(TodoItem(boardId = board, text = "旧事项", important = true))).single()
        initial.close()
        val path = context.getDatabasePath(name)
        val sqlite = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE)
        sqlite.execSQL("DROP TABLE note_nodes"); sqlite.execSQL("DROP TABLE note_versions"); sqlite.execSQL("DROP TABLE room_master_table")
        listOf("plannedDay", "dueAt", "repeatDays", "repeatSpawned", "deletedAt").forEach { sqlite.execSQL("ALTER TABLE todo_items DROP COLUMN $it") }
        sqlite.execSQL("ALTER TABLE todo_boards DROP COLUMN pinned"); sqlite.execSQL("ALTER TABLE todo_boards DROP COLUMN deletedAt")
        sqlite.execSQL("ALTER TABLE schedule_events DROP COLUMN deletedAt")
        sqlite.version = 9; sqlite.close()
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.migration9To10).build()
        try {
            val memory = migrated.noteDao().node("m$id")!!
            assertEquals("c$category", memory.parentId)
            assertEquals("# 原文\n**保持原样**", memory.text)
            assertTrue(memory.document.isEmpty())
            assertEquals("旧内容", migrated.noteDao().diary(dayMillis())!!.text)
            val oldTask = migrated.appDao().allTodos().flatMap { it.items }.single { it.id == task }
            assertTrue(oldTask.important); assertFalse(oldTask.completed); assertTrue(oldTask.plannedDay!! < dayMillis())
            assertEquals("LIST", migrated.appDao().getTodoBoard(board)!!.boardType)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    @Test fun exportAndBackupRoundTripKeepIndependentDocumentsMediaAndDeletedSubtrees() = runBlocking {
        val model = withContext(Dispatchers.Main) { WorkspaceModel(context.applicationContext as Application) }
        model.flushAll()
        val original = ExportEngine.backup(context, model, ExportOptions())
        val dir = File(context.filesDir, "assets").apply { mkdirs() }
        val image = File(dir, "test-${UUID.randomUUID()}.png")
        image.outputStream().use { Bitmap.createBitmap(8, 40, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val voice = File(dir, "test-${UUID.randomUUID()}.m4a").apply { writeText("owned-audio-payload") }
        val external = File(context.cacheDir, "external-test.txt").apply { writeText("external-reference-payload") }
        val root = NoteNode(id = "test-root", kind = "folder", title = "同名")
        val child = NoteNode(id = "test-child", kind = "folder", parentId = root.id, title = "同名")
        val outside = NoteNode(id = "test-outside", title = "范围外")
        val blocks = listOf(NoteBlock(text = "中文正文").toggleMark(0, 2, "b"),
            NoteBlock(type = "image", text = "长图", uri = Uri.fromFile(image).toString(), owned = true, mime = "image/png"),
            NoteBlock(type = "audio", text = "语音", uri = Uri.fromFile(voice).toString(), owned = true, duration = 4200),
            NoteBlock(type = "file", text = "文件", uri = Uri.fromFile(external).toString(), owned = false),
            NoteBlock(type = "file", text = "缺失文件", uri = "file:///nonexistent/shijian-test.txt"),
            NoteBlock(type = "link", text = "范围外", target = outside.id))
        val note = NoteNode(id = "test-note", parentId = child.id, title = "同名", document = encodeBlocks(blocks), text = blockPlainText(blocks))
        val deletedRoot = NoteNode(id = "deleted-root", kind = "folder", title = "删除的分类", deletedAt = 1, deleteGroup = "deleted-group")
        val deletedNote = NoteNode(id = "deleted-note", parentId = deletedRoot.id, text = "删除的子记录", deletedAt = 1, deleteGroup = "deleted-group")
        try {
            model.notes.putAll(listOf(root, child, note, outside, deletedRoot, deletedNote))
            model.notes.version(NoteVersion(nodeId = note.id, snapshot = jsonObject(note).toString()))
            val all = model.notes.nodes()
            val selected = ExportEngine.scope(setOf(root.id), all)
            val output = ExportEngine.notes(context, selected, all, ExportOptions())
            ZipFile(output.file).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertEquals(1, names.count { it.endsWith(".md") && it !in setOf("目录.md", "引用说明.md") })
                assertEquals(2, names.count { it.startsWith("素材/") })
                assertTrue(names.any { it.endsWith(".m4a") }); assertFalse(names.any { it.contains(outside.id) })
                val text = zip.getInputStream(zip.getEntry(ExportEngine.paths(selected).getValue(note.id))).bufferedReader().readText()
                assertTrue(text.contains("未包含在本次导出范围")); assertTrue(text.contains("**中文**正文"))
            }
            assertTrue(output.missing.contains("缺失文件"))
            val included = ExportEngine.notes(context, listOf(note), all, ExportOptions(referenceFiles = true))
            assertEquals(3, included.materialCount); assertTrue(included.missing.contains("缺失文件"))
            val pdf = ExportEngine.pdf(context, listOf(note), all, true)
            assertEquals("%PDF", pdf.file.inputStream().use { String(it.readNBytes(4)) })
            val backup = ExportEngine.backup(context, model, ExportOptions())
            model.notes.remove(listOf(root.id, child.id, note.id, outside.id, deletedRoot.id, deletedNote.id))
            ExportEngine.restore(context, model, Uri.fromFile(backup.file))
            val restored = model.notes.node(note.id)!!
            assertEquals(child.id, restored.parentId)
            assertEquals(blocks.first().marks, restored.blocks().first().marks)
            val restoredVoice = restored.blocks().single { it.type == "audio" }
            assertTrue(restoredVoice.owned)
            assertEquals("owned-audio-payload", File(Uri.parse(restoredVoice.uri).path!!).readText())
            assertFalse(restored.blocks().first { it.type == "file" }.owned)
            assertEquals(2, model.notes.nodes().count { it.deleteGroup == "deleted-group" })
            assertTrue(model.notes.versions(note.id).isNotEmpty())
        } finally { ExportEngine.restore(context, model, Uri.fromFile(original.file)) }
    }

    @Test fun repeatedTaskIsCreatedOnlyOnceAndDraftSurvivesBlankTitle() = runBlocking {
        val model = withContext(Dispatchers.Main) { WorkspaceModel(context.applicationContext as Application) }
        val board = model.dao.insertTodoBoard(TodoBoard(summary = "重复测试"))
        try {
            val id = model.dao.insertTodoItems(listOf(TodoItem(boardId = board, text = "每两天", repeatDays = 2))).single()
            fun item() = runBlocking { model.dao.getTodoItems(board).single { it.id == id } }
            model.complete(item()).join(); model.complete(item()).join(); model.complete(item()).join()
            assertEquals(2, model.dao.getTodoItems(board).size)
            val node = NoteNode(id = "blank-title-test", text = "只有正文", title = "")
            model.save(node); model.flush(node.id)
            assertEquals("只有正文", model.notes.node(node.id)!!.displayTitle())
            model.notes.remove(listOf(node.id)); model.notes.removeVersions(listOf(node.id))
        } finally { model.dao.getTodoBoard(board)?.let { model.dao.deleteTodoBoard(it) } }
    }
}
