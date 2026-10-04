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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

class WorkspaceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun oldBackupWithoutSpacesMigratesToWorkAndCanRestoreLifeSeparately() = runBlocking {
        val model = withContext(Dispatchers.Main) { WorkspaceModel(context.applicationContext as Application) }
        model.spacesReady.first { it }
        val original = ExportEngine.backup(context, model, ExportOptions())
        val folder = NoteNode(id = "space-legacy-folder", kind = "folder", title = "旧手机分类", forceChildren = true)
        val child = NoteNode(id = "space-legacy-child", parentId = folder.id, text = "旧手机原文", tags = "旧标签")
        val deleted = NoteNode(id = "space-legacy-trash", kind = "folder", title = "旧删除分类", deletedAt = 123)
        val deletedChild = child.copy(id = "space-legacy-trash-child", parentId = deleted.id, deletedAt = 123)
        val legacy = File(context.cacheDir, "space-legacy-backup.zip")
        try {
            val manifest = ZipFile(original.file).use { zip -> JSONObject(zip.getInputStream(zip.getEntry("backup.json")).bufferedReader().readText()) }
            manifest.put("nodes", JSONArray().apply { listOf(folder, child, deleted, deletedChild).forEach { put(jsonObject(it)) } })
            manifest.put("versions", JSONArray().put(jsonObject(NoteVersion(id = 1, nodeId = child.id, snapshot = jsonObject(child).toString()))))
            manifest.put("assets", JSONObject())
            ZipOutputStream(legacy.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("backup.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry() }
            ExportEngine.restore(context, model, Uri.fromFile(legacy))
            assertEquals(MemorySpaces.WORK_ID, model.notes.node(folder.id)!!.parentId)
            assertEquals(folder.id, model.notes.node(child.id)!!.parentId)
            assertEquals(child.text, model.notes.node(child.id)!!.text)
            assertEquals(child.tags, model.notes.node(child.id)!!.tags)
            assertTrue(model.notes.node(folder.id)!!.forceChildren)
            assertEquals(1, model.notes.versions(child.id).size)
            val oldGroup = model.notes.node(deleted.id)!!.deleteGroup
            assertEquals(oldGroup, model.notes.node(deletedChild.id)!!.deleteGroup)
            model.restore(oldGroup, setOf(deleted.id, deletedChild.id)).join()
            assertEquals(deleted.id, model.notes.node(deletedChild.id)!!.parentId)
            assertEquals(MemorySpaces.WORK_ID, model.notes.node(deleted.id)!!.parentId)

            var moved: NoteNode? = null
            model.moveMany(setOf(child.id), MemorySpaces.LIFE_ID) { moved = it.single() }.join()
            assertEquals(MemorySpaces.LIFE_ID, moved!!.parentId)
            model.trash(setOf(folder.id, child.id)).join()
            val group = model.notes.node(child.id)!!.deleteGroup!!
            assertEquals(group, model.notes.node(folder.id)!!.deleteGroup)
            model.restore(group, setOf(child.id)).join()
            assertNull(model.notes.node(child.id)!!.deletedAt)
            assertNotNull(model.notes.node(folder.id)!!.deletedAt)
            assertEquals(MemorySpaces.LIFE_ID, model.notes.node(child.id)!!.parentId)
            model.permanentlyDelete(group, setOf(folder.id)).join()
            assertNull(model.notes.node(folder.id))
            assertNotNull(model.notes.node(MemorySpaces.WORK_ID))
            assertNotNull(model.notes.node(MemorySpaces.LIFE_ID))
            model.ensureMemorySpaces()
            assertEquals(2, model.notes.nodes().count { MemorySpaces.isRoot(it.id) })
        } finally { ExportEngine.restore(context, model, Uri.fromFile(original.file)); legacy.delete() }
    }

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
        assertEquals(MemorySpaces.WORK_ID, model.notes.node(child.id)!!.parentId)
        model.notes.remove(listOf(child.id)); model.notes.removeVersions(listOf(child.id))
    }

    @Test fun versionNineMigrationPreservesLegacyNotesAndClearsOldTodos() = runBlocking {
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
        listOf("plannedDay", "dueAt", "repeatDays", "repeatSpawned", "deletedAt", "reminderRule", "reminderBaseAt", "reminderCustomDays", "reminderSkipAt").forEach { sqlite.execSQL("ALTER TABLE todo_items DROP COLUMN $it") }
        listOf("timeMode", "reminderSkipAt").forEach { sqlite.execSQL("ALTER TABLE todo_boards DROP COLUMN $it") }
        sqlite.execSQL("DROP TABLE legacy_todo_alarms")
        sqlite.execSQL("ALTER TABLE todo_boards DROP COLUMN pinned"); sqlite.execSQL("ALTER TABLE todo_boards DROP COLUMN deletedAt")
        sqlite.execSQL("ALTER TABLE schedule_events DROP COLUMN deletedAt")
        sqlite.execSQL("ALTER TABLE schedule_events DROP COLUMN important")
        sqlite.version = 9; sqlite.close()
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.migration9To10, AppDatabase.migration10To11, AppDatabase.migration11To12).build()
        try {
            val memory = migrated.noteDao().node("m$id")!!
            assertEquals("c$category", memory.parentId)
            assertEquals("# 原文\n**保持原样**", memory.text)
            assertTrue(memory.document.isEmpty())
            assertEquals("旧内容", migrated.noteDao().diary(dayMillis())!!.text)
            assertTrue(migrated.appDao().allTodos().isEmpty())
            assertNull(migrated.appDao().getTodoBoard(board))
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
        val root = NoteNode(id = "test-root", kind = "folder", title = "同名", forceChildren = true)
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
            val output = ExportEngine.notes(context, selected, all, ExportOptions(structureTree = true))
            ZipFile(output.file).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertEquals(1, names.count { it.endsWith(".md") && it !in setOf("目录.md", "引用说明.md") })
                assertEquals(2, names.count { it.startsWith("素材/") })
                assertTrue(names.any { it.endsWith(".m4a") }); assertFalse(names.any { it.contains(outside.id) })
                val text = zip.getInputStream(zip.getEntry(ExportEngine.paths(selected).getValue(note.id))).bufferedReader().readText()
                assertTrue(text.contains("未包含在本次导出范围")); assertTrue(text.contains("**中文**正文"))
                val trees = names.filter { it.startsWith("结构树/") }
                assertEquals(1, trees.size)
                val tree = zip.getInputStream(zip.getEntry(trees.single())).bufferedReader().readText()
                assertTrue(tree.contains("《同名》")); assertFalse(tree.contains("范围外"))
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
            assertTrue(model.notes.node(root.id)!!.forceChildren)
            assertEquals(blocks.first().marks, restored.blocks().first().marks)
            val restoredVoice = restored.blocks().single { it.type == "audio" }
            assertTrue(restoredVoice.owned)
            assertEquals("owned-audio-payload", File(Uri.parse(restoredVoice.uri).path!!).readText())
            assertFalse(restored.blocks().first { it.type == "file" }.owned)
            assertEquals(2, model.notes.nodes().count { it.deleteGroup == "deleted-group" })
            assertTrue(model.notes.versions(note.id).isNotEmpty())
        } finally { ExportEngine.restore(context, model, Uri.fromFile(original.file)) }
    }

    @Test fun completionDoesNotCopyTasksAndDraftSurvivesBlankTitle() = runBlocking {
        val model = withContext(Dispatchers.Main) { WorkspaceModel(context.applicationContext as Application) }
        val board = model.dao.insertTodoBoard(TodoBoard(summary = "重复测试"))
        try {
            val id = model.dao.insertTodoItems(listOf(TodoItem(boardId = board, text = "每两天", repeatDays = 2))).single()
            fun item() = runBlocking { model.dao.getTodoItems(board).single { it.id == id } }
            model.complete(item()).join(); model.complete(item()).join(); model.complete(item()).join()
            assertEquals(1, model.dao.getTodoItems(board).size)
            val node = NoteNode(id = "blank-title-test", text = "只有正文", title = "")
            model.save(node); model.flush(node.id)
            assertEquals("只有正文", model.notes.node(node.id)!!.displayTitle())
            model.notes.remove(listOf(node.id)); model.notes.removeVersions(listOf(node.id))
        } finally { model.dao.getTodoBoard(board)?.let { model.dao.deleteTodoBoard(it) } }
    }

    @Test fun versionTenMigrationAddsSafeDefaultsWithoutChangingExistingContent() = runBlocking {
        val name = "migration-ten-${UUID.randomUUID()}.db"
        val initial = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        initial.noteDao().put(NoteNode(id = "migration-note", title = "已有分类", kind = "folder", imageStorage = "reference"))
        val scheduleId = initial.appDao().insertSchedule(ScheduleEvent(title = "旧事务", eventAt = 123, reminderDays = 0, reminderHours = 0, reminderMinutes = 0))
        initial.close()
        val sqlite = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)
        sqlite.execSQL("ALTER TABLE note_nodes DROP COLUMN forceChildren")
        sqlite.execSQL("ALTER TABLE schedule_events DROP COLUMN important")
        listOf("timeMode", "reminderSkipAt").forEach { sqlite.execSQL("ALTER TABLE todo_boards DROP COLUMN $it") }
        listOf("reminderRule", "reminderBaseAt", "reminderCustomDays", "reminderSkipAt").forEach { sqlite.execSQL("ALTER TABLE todo_items DROP COLUMN $it") }
        sqlite.execSQL("DROP TABLE legacy_todo_alarms")
        sqlite.execSQL("DROP TABLE room_master_table")
        sqlite.version = 10; sqlite.close()
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.migration10To11, AppDatabase.migration11To12).build()
        try {
            assertFalse(migrated.noteDao().node("migration-note")!!.forceChildren)
            assertEquals("reference", migrated.noteDao().node("migration-note")!!.imageStorage)
            assertFalse(migrated.appDao().allSchedule().single { it.id == scheduleId }.important)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    @Test fun enforcingImagesCopiesReferencesReportsFailuresAndMovingRecalculatesSettings() = runBlocking {
        val model = withContext(Dispatchers.Main) { WorkspaceModel(context.applicationContext as Application) }
        model.flushAll()
        val source = File(context.cacheDir, "image-source-${UUID.randomUUID()}.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val root = NoteNode(id = "force-root", kind = "folder", imageDisplay = "card", imageStorage = "copy")
        val child = NoteNode(id = "force-child", kind = "folder", parentId = root.id, imageDisplay = "preview", imageStorage = "reference")
        val block = NoteBlock(type = "image", text = "来源.png", uri = Uri.fromFile(source).toString(), display = "preview")
        val missing = NoteBlock(type = "image", text = "失效.png", uri = "file:///missing/force-test.png")
        val note = NoteNode(id = "force-note", parentId = child.id, imageStorage = "reference", document = encodeBlocks(listOf(block, missing)), text = blockPlainText(listOf(block, missing)))
        val destination = NoteNode(id = "force-target", kind = "folder", forceChildren = true, imageDisplay = "preview", imageStorage = "copy")
        val ids = listOf(root.id, child.id, note.id, destination.id)
        try {
            model.notes.putAll(listOf(root, child, note, destination))
            model.updateImageSettings(root.copy(forceChildren = true)).join()
            val converted = model.notes.node(note.id)!!
            val copy = converted.blocks().first()
            assertEquals(block.id, copy.id); assertEquals(block.display, copy.display); assertTrue(copy.owned)
            assertArrayEquals(source.readBytes(), File(Uri.parse(copy.uri).path!!).readBytes())
            assertEquals(missing.uri, converted.blocks().last().uri)
            assertEquals(1, model.imageFailures.value.size)
            assertTrue(model.imageFailures.value.single().path.contains(" / "))
            assertEquals("reference", model.notes.node(child.id)!!.imageStorage)
            model.updateImageSettings(root.copy(forceChildren = false)).join()
            assertEquals("preview", TreeRules.imagePreference(model.notes.node(note.id)!!, model.notes.nodes(), false, "card"))
            assertTrue(model.notes.node(note.id)!!.blocks().first().owned)
            model.moveMany(setOf(child.id, note.id), destination.id).join()
            assertEquals(destination.id, model.notes.node(child.id)!!.parentId)
            assertEquals(child.id, model.notes.node(note.id)!!.parentId)
            assertEquals("copy", TreeRules.imagePreference(model.notes.node(note.id)!!, model.notes.nodes(), true, "reference"))
            assertEquals(1, model.imageFailures.value.size)
        } finally {
            model.notes.remove(ids); model.notes.removeVersions(ids); source.delete()
        }
    }

    @Test fun newDailyBoardPersistsPlanAndCollapseAndScheduleStarAreIndependent() = runBlocking {
        val model = withContext(Dispatchers.Main) { WorkspaceModel(context.applicationContext as Application) }
        val planned = dayMillis(java.time.LocalDate.now().plusDays(1))
        val first = model.saveTaskNow(TodoItem(boardId = 0, text = "日计划事项一", plannedDay = planned))
        val second = model.saveTaskNow(TodoItem(boardId = 0, text = "日计划事项二", plannedDay = planned))
        val board = model.dao.allTodos().single { it.board.id == first.boardId }
        val eventId = model.dao.insertSchedule(ScheduleEvent(title = "重要测试", eventAt = System.currentTimeMillis() + 100_000, reminderDays = 0, reminderHours = 0, reminderMinutes = 0, reminderTriggered = true))
        try {
            assertEquals(first.boardId, second.boardId)
            assertEquals("DAILY", board.board.boardType)
            assertEquals("INDEPENDENT", board.board.timeMode)
            assertNull(board.board.dueDate)
            assertEquals(planned, first.plannedDay)
            assertEquals(planDeadline(planned), first.dueAt)
            model.collapseBoards(listOf(board.board.id)).join()
            assertFalse(model.dao.getTodoBoard(board.board.id)!!.expanded)
            model.toggleScheduleImportant(eventId).join()
            val event = model.dao.allSchedule().single { it.id == eventId }
            assertTrue(event.important); assertTrue(event.reminderTriggered)
            val backup = ExportEngine.backup(context, model, ExportOptions())
            model.dao.toggleScheduleImportant(eventId)
            ExportEngine.restore(context, model, Uri.fromFile(backup.file))
            assertTrue(model.dao.allSchedule().single { it.id == eventId }.important)
        } finally {
            model.dao.getTodoItem(first.id)?.let { model.dao.deleteTodoItem(it) }
            model.dao.getTodoItem(second.id)?.let { model.dao.deleteTodoItem(it) }
            model.dao.allSchedule().find { it.id == eventId }?.let { model.dao.deleteSchedule(it) }
        }
    }
}
