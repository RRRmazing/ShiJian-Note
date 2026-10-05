package com.shijiannote.app

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.shijiannote.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipFile

class DiaryRoadDataInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun versionTwelveKeepsSummaryVersionsAndSafeRoadDefaults() = runBlocking {
        val name = "diary-v12-${UUID.randomUUID()}.db"
        val original = NoteNode(id = "legacy-diary", kind = "diary", day = dayMillis(), title = "旧结语", text = "原正文", tags = "标签")
        val initial = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        initial.noteDao().put(original)
        initial.noteDao().version(NoteVersion(nodeId = original.id, snapshot = jsonObject(original).toString()))
        initial.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
            listOf("diaryRoad", "diaryRoadTheme", "diaryRoadBackground", "diaryRoadEnabled", "diaryRoadLayout", "diaryInbox", "diaryOccurredAt", "diaryTrashExpiresAt").forEach { sqlite.execSQL("ALTER TABLE note_nodes DROP COLUMN $it") }
            sqlite.execSQL("DROP TABLE room_master_table")
            sqlite.version = 12
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.migration12To13, AppDatabase.migration13To14).build()
        try {
            assertEquals(original, migrated.noteDao().node(original.id))
            assertEquals(original, decodeNode(JSONObject(migrated.noteDao().versions(original.id).single().snapshot)))
            assertTrue(migrated.noteDao().node(original.id)!!.diaryMoments().isEmpty())
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    @Test fun rapidMomentEditsPreserveLegacyIdentityAndStaleSummaryCannotEraseRoad() = runBlocking {
        val model = withContext(Dispatchers.Main) { WorkspaceModel(context.applicationContext as Application) }
        model.spacesReady.first { it }
        val day = dayMillis(LocalDate.of(2042, 5, 8))
        val parent = NoteNode(id = "legacy-${UUID.randomUUID()}", kind = "diary", day = day, text = "原结语")
        val deletedDay = dayMillis(LocalDate.of(2042, 5, 9))
        val emptyDay = dayMillis(LocalDate.of(2042, 5, 10))
        val ids = mutableSetOf(parent.id)
        try {
            model.save(parent); model.flush(parent.id)
            assertNotNull(model.notes.node(parent.id))
            model.nodes.first { list -> list.any { it.id == parent.id } }
            val moments = (0..4).map { DiaryMoment(id = "m$it", createdAt = it.toLong(), text = "随记 $it") }
            moments.forEach { model.appendDiaryMoment(parent, it) }
            model.flush(moments.last().asNote(parent).id)
            model.save(parent.copy(text = "新结语")); model.flush(parent.id)
            val saved = model.notes.node(parent.id)!!
            assertEquals(parent.id, saved.id)
            assertEquals("新结语", saved.text)
            assertEquals(moments.map { it.id }, saved.diaryMoments().map { it.id })
            assertTrue(saved.diaryMoments().all { it.sentAt != null && it.occurredAt == it.sentAt })
            model.save(moments[1].asNote(parent).copy(text = "")); model.flush(parent.id)
            assertEquals(5, model.notes.node(parent.id)!!.diaryMoments().size)
            model.deleteDiaryMoment(parent, "m1"); model.flush(parent.id)
            assertEquals(moments.filterNot { it.id == "m1" }.map { it.id }, model.notes.node(parent.id)!!.diaryMoments().map { it.id })
            assertEquals("deleted", model.notes.node(parent.id)!!.diaryInboxItems().single().status)

            val doomed = model.currentDiary(deletedDay).copy(text = "留在回收站")
            ids += doomed.id
            model.save(doomed); model.flush(doomed.id)
            model.trash(setOf(doomed.id)).join()
            val replacement = model.currentDiary(deletedDay)
            ids += replacement.id
            assertNotEquals(doomed.id, replacement.id)
            model.save(replacement.copy(text = "重新记录")); model.flush(replacement.id)
            assertNotNull(model.notes.node(doomed.id)!!.deletedAt)
            assertEquals("重新记录", model.notes.node(replacement.id)!!.text)

            val empty = model.currentDiary(emptyDay)
            ids += empty.id
            model.updateDiaryRoadAppearance(empty, "river", "")
            model.flushAll()
            assertEquals("river", model.currentDiary(emptyDay).diaryRoadTheme)
            assertFalse(model.notes.diary(emptyDay)!!.hasDiaryContent())
            assertFalse(model.notes.diary(emptyDay)!!.diaryRoadEnabled)
        } finally { model.flushAll(); model.notes.removeVersions(ids.toList()); model.notes.remove(ids.toList()) }
    }

    @Test fun roadMediaBackgroundAndRecordingInboxSurviveBackupAndRestore() = runBlocking {
        val model = withContext(Dispatchers.Main) { WorkspaceModel(context.applicationContext as Application) }
        model.spacesReady.first { it }
        val recovery = ExportEngine.backup(context, model, ExportOptions())
        val folder = File(context.filesDir, "assets").apply { mkdirs() }
        val image = File(folder, "road-${UUID.randomUUID()}.png").apply { writeText("image payload") }
        val background = File(folder, "bg-${UUID.randomUUID()}.png").apply { writeText("background payload") }
        val voice = File(folder, "voice-${UUID.randomUUID()}.m4a").apply { writeText("audio payload") }
        val day = dayMillis(LocalDate.of(2042, 5, 11))
        try {
            val parent = model.currentDiary(day)
            val block = NoteBlock(type = "image", text = "沿途照片", uri = Uri.fromFile(image).toString(), owned = true)
            val moment = DiaryMoment(text = "触物有感", document = encodeBlocks(listOf(NoteBlock(text = "触物有感"), block)))
            model.appendDiaryMoment(parent, moment)
            model.updateDiaryRoadAppearance(parent, "river", Uri.fromFile(background).toString())
            val recording = DiaryMoment()
            val virtual = recording.asNote(parent)
            model.registerDiaryRecording(virtual)
            val prefs = context.getSharedPreferences("recording_inbox", android.content.Context.MODE_PRIVATE)
            val inbox = JSONArray(prefs.getString("items", "[]"))
            inbox.put(JSONObject().put("owner", virtual.id).put("path", voice.absolutePath).put("finished", true).put("duration", 1200))
            prefs.edit().putString("items", inbox.toString()).commit()
            val backup = ExportEngine.backup(context, model, ExportOptions())
            assertTrue(RecordingService.inbox(context, virtual.id).isEmpty())
            ZipFile(backup.file).use { zip ->
                val manifest = JSONObject(zip.getInputStream(zip.getEntry("backup.json")).bufferedReader().readText())
                assertTrue(manifest.getJSONObject("assets").has(Uri.fromFile(background).toString()))
                assertTrue(manifest.getJSONObject("assets").has(Uri.fromFile(image).toString()))
                assertTrue(manifest.getJSONObject("assets").has(Uri.fromFile(voice).toString()))
            }
            ExportEngine.restore(context, model, Uri.fromFile(backup.file))
            val restored = model.notes.diary(day)!!
            assertEquals("river", restored.diaryRoadTheme)
            assertEquals("background payload", File(Uri.parse(restored.diaryRoadBackground).path!!).readText())
            assertEquals(1, restored.diaryMoments().size)
            assertEquals(1, restored.diaryInboxItems().size)
            assertTrue(SearchRules.noteText(restored).contains("触物有感"))
            val audio = restored.materialBlocks().single { it.type == "audio" }
            assertEquals("audio payload", File(Uri.parse(audio.uri).path!!).readText())
            val markdown = ExportEngine.markdown(restored, listOf(restored), "diary.md", emptyMap(), emptyMap())
            assertTrue(markdown.contains("触物有感"))
            assertFalse(markdown.contains("语音记录"))
            val oldSnapshot = jsonObject(restored).apply { remove("diaryRoad"); remove("diaryRoadTheme"); remove("diaryRoadBackground") }
            assertTrue(decodeNode(oldSnapshot).diaryMoments().isEmpty())
        } finally {
            RecordingService.removeInbox(context, voice.absolutePath)
            ExportEngine.restore(context, model, Uri.fromFile(recovery.file))
            image.delete(); background.delete(); voice.delete()
        }
    }

    @Test fun conflictingRestoreExposesRoadSummaryAndBackgroundWithoutChangingCurrentDiary() = runBlocking {
        val model = withContext(Dispatchers.Main) { WorkspaceModel(context.applicationContext as Application) }
        model.spacesReady.first { it }
        val day = dayMillis(LocalDate.of(2044, 3, 2))
        val suffix = UUID.randomUUID().toString()
        val old = NoteNode(id = "old-$suffix", kind = "diary", day = day, text = "旧结语", deletedAt = 123, deleteGroup = "trash-$suffix",
            diaryRoad = encodeDiaryMoments(listOf(DiaryMoment(text = "旧小路片段"))),
            diaryRoadBackground = "file:///retained/background.png", diaryRoadTheme = "river")
        val current = NoteNode(id = "current-$suffix", kind = "diary", day = day, text = "当前结语")
        val ids = listOf(old.id, current.id)
        try {
            model.notes.putAll(listOf(old, current))
            model.nodes.first { it.any { node -> node.id == current.id } }
            model.restore(null, setOf(old.id)).join()
            val restored = model.notes.node(old.id)!!
            assertEquals("memory", restored.kind)
            assertEquals(MemorySpaces.WORK_ID, restored.parentId)
            assertTrue(restored.text.contains("旧小路片段"))
            assertTrue(restored.text.contains("旧结语"))
            assertTrue(restored.diaryRoad.isBlank())
            assertTrue(restored.diaryRoadBackground.isBlank())
            assertEquals(old.diaryRoadBackground, restored.blocks().last().uri)
            assertTrue(restored.blocks().last().owned)
            assertEquals(current, model.notes.node(current.id))
            assertEquals(old, decodeNode(JSONObject(model.notes.versions(old.id).first().snapshot)))

            // Restoring an old diary snapshot into this memory uses the same readable flattening.
            model.save(old.copy(kind = "memory", deletedAt = null, parentId = MemorySpaces.WORK_ID))
            model.flush(old.id)
            val versionRestored = model.notes.node(old.id)!!
            assertTrue(versionRestored.text.contains("旧小路片段"))
            assertTrue(versionRestored.diaryRoad.isBlank())
            assertEquals(current, model.notes.node(current.id))
        } finally { model.flushAll(); model.notes.removeVersions(ids); model.notes.remove(ids) }
    }

    @Test fun startupRemovesAbandonedRecordingPlaceholderAndKeepsWrittenMoments() = runBlocking {
        val model = withContext(Dispatchers.Main) { WorkspaceModel(context.applicationContext as Application) }
        model.spacesReady.first { it }
        val parent = model.currentDiary(dayMillis(LocalDate.of(2044, 3, 3)))
        val onlyEmpty = model.currentDiary(dayMillis(LocalDate.of(2044, 3, 4)))
        val stillRecording = model.currentDiary(dayMillis(LocalDate.of(2044, 3, 5)))
        val previousRecording = RecordingService.state.value
        val ids = listOf(parent.id, onlyEmpty.id, stillRecording.id)
        try {
            val abandoned = DiaryMoment()
            model.registerDiaryRecording(abandoned.asNote(parent))
            val written = DiaryMoment(text = "已经写下的内容")
            model.appendDiaryMoment(parent, written)
            model.registerDiaryRecording(DiaryMoment().asNote(onlyEmpty))
            val active = DiaryMoment()
            model.registerDiaryRecording(active.asNote(stillRecording))
            RecordingService.state.value = RecordingState(owner = active.asNote(stillRecording).id, running = true)
            model.flushAll()
            val sentWritten = model.notes.node(parent.id)!!.diaryMoments().single()
            assertEquals(1, model.notes.node(parent.id)!!.diaryInboxItems().size)
            val reopened = withContext(Dispatchers.Main) { WorkspaceModel(context.applicationContext as Application) }
            reopened.spacesReady.first { it }
            val restored = reopened.notes.node(parent.id)!!
            assertEquals(listOf(sentWritten), restored.diaryMoments())
            assertTrue(reopened.notes.node(onlyEmpty.id)!!.diaryInboxItems().isEmpty())
            assertEquals(active, reopened.notes.node(stillRecording.id)!!.diaryInboxItems().single().moment)
        } finally { RecordingService.state.value = previousRecording; model.notes.removeVersions(ids); model.notes.remove(ids) }
    }
}
