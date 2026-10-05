package com.shijiannote.app

import android.app.Application
import android.database.sqlite.SQLiteDatabase
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

class DiaryInboxInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun model(): WorkspaceModel = withContext(Dispatchers.Main) { WorkspaceModel(context.applicationContext as Application) }.also { it.spacesReady.first { ready -> ready } }

    @Test fun newDatesUseSavedBackgroundDefaultsWithoutChangingExistingTextDiaries() = runBlocking {
        val model = model()
        val prefs = appPreferences(context)
        val keys = listOf("diary_default_background_theme", "diary_default_background_uri", "diary_default_road_layout")
        val previous = keys.associateWith { prefs.getString(it, null) }
        val existing = NoteNode(id = "old-text-${UUID.randomUUID()}", kind = "diary", day = dayMillis(LocalDate.of(2050, 1, 2)), text = "旧文本日记")
        try {
            DiaryBackgroundLibrary.setDefaults(context, DiaryBackground("chosen", "我的背景", "file:///defaults/example.png", "custom"), "right")
            val fresh = model.currentDiary(dayMillis(LocalDate.of(2050, 1, 3)))
            assertEquals("custom", fresh.diaryRoadTheme)
            assertEquals("file:///defaults/example.png", fresh.diaryRoadBackground)
            assertEquals("right", fresh.diaryRoadLayout)
            assertFalse(fresh.diaryRoadEnabled)
            assertNull(model.notes.node(fresh.id))
            model.notes.put(existing)
            model.nodes.first { it.any { node -> node.id == existing.id } }
            assertEquals(existing, model.currentDiary(existing.day!!))
        } finally {
            prefs.edit().apply { previous.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) } }.commit()
            model.notes.remove(listOf(existing.id))
        }
    }

    @Test fun v13MigrationEnablesOnlyRealRoadsAndLegacyTimesRemainStable() = runBlocking {
        val name = "diary-v13-${UUID.randomUUID()}.db"
        val original = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val legacy = JSONArray().put(JSONObject().put("id", "old-moment").put("createdAt", 1234).put("text", "旧随记")).toString()
        val text = NoteNode(id = "text", kind = "diary", text = "纯文本正文", tags = "旧标签", day = 100)
        val road = NoteNode(id = "road", kind = "diary", text = "结语保留", diaryRoad = legacy, diaryRoadBackground = "file:///old/bg.png", day = 200)
        val empty = NoteNode(id = "empty", kind = "diary", diaryRoad = "[]", day = 300)
        original.noteDao().putAll(listOf(text, road, empty))
        original.noteDao().version(NoteVersion(nodeId = road.id, snapshot = jsonObject(road).toString()))
        original.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
            listOf("diaryRoadEnabled", "diaryRoadLayout", "diaryInbox", "diaryOccurredAt", "diaryTrashExpiresAt").forEach { sqlite.execSQL("ALTER TABLE note_nodes DROP COLUMN $it") }
            sqlite.execSQL("DROP TABLE room_master_table"); sqlite.version = 13
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.migration13To14).build()
        try {
            assertEquals(text, migrated.noteDao().node(text.id))
            val restored = migrated.noteDao().node(road.id)!!
            assertTrue(restored.diaryRoadEnabled)
            assertFalse(migrated.noteDao().node(empty.id)!!.diaryRoadEnabled)
            assertEquals(road.text, restored.text)
            assertEquals(road.diaryRoadBackground, restored.diaryRoadBackground)
            assertEquals(1234L, restored.diaryMoments().single().sentAt)
            assertEquals(1234L, restored.diaryMoments().single().occurredAt)
            assertEquals(road.diaryRoad, decodeNode(JSONObject(migrated.noteDao().versions(road.id).single().snapshot)).diaryRoad)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    @Test fun autosaveDoesNotSendAndRetractionResendAndDeletionUseCorrectTimes() = runBlocking {
        val model = model()
        val parent = model.currentDiary(dayMillis(LocalDate.of(2048, 1, 2)))
        val oldInboxDays = model.diaryInboxRetentionDays(); val oldTrashDays = model.diaryTrashRetentionDays()
        model.setDiaryRetentionDays(0, 0)
        val draft = DiaryMoment(createdAt = 100, text = "迟记", occurredAt = 50)
        try {
            model.save(draft.asNote(parent)); model.flush("moment-${draft.id}")
            val staged = model.notes.node(parent.id)!!
            assertTrue(staged.diaryMoments().isEmpty())
            assertFalse(staged.hasDiaryContent())
            assertNull(staged.diaryInboxItems().single().moment!!.sentAt)
            val beforeSend = System.currentTimeMillis()
            val published = model.publishDiaryMoment(parent, draft)
            assertTrue(published.sentAt!! >= beforeSend)
            assertEquals(50L, published.occurredAt)
            assertTrue(model.notes.node(parent.id)!!.diaryInboxItems().isEmpty())
            assertTrue(model.notes.node(parent.id)!!.diaryRoadEnabled)

            model.save(published.asNote(parent).copy(text = "", diaryOccurredAt = null)); model.flush(parent.id)
            val blank = model.notes.node(parent.id)!!.diaryMoments().single()
            assertEquals(published.sentAt, blank.sentAt)
            assertEquals(blank.sentAt, blank.occurredAt)
            assertTrue(model.notes.node(parent.id)!!.diaryRoadEnabled)
            model.save(blank.asNote(parent).copy(text = "编辑后内容", diaryOccurredAt = 80)); model.flush(parent.id)
            model.retractDiaryMoment(parent, draft.id); model.flush(parent.id)
            val withdrawn = model.notes.node(parent.id)!!.diaryInboxItems().single()
            assertEquals("retracted", withdrawn.status)
            assertNull(withdrawn.expiresAt)
            model.deleteDiaryInbox(parent, withdrawn.id); model.flush(parent.id)
            assertEquals("retracted", model.notes.node(parent.id)!!.diaryInboxItems().single().originalStatus)
            model.restoreDiaryInbox(parent, withdrawn.id); model.flush(parent.id)
            assertEquals("retracted", model.notes.node(parent.id)!!.diaryInboxItems().single().status)
            delay(3)
            val resent = model.publishDiaryMoment(parent, model.currentDiary(parent.day!!).diaryInboxItems().single().moment!!)
            assertTrue(resent.sentAt!! > published.sentAt!!)
            assertEquals(80L, resent.occurredAt)
            model.deleteDiaryMoment(parent, resent.id); model.flush(parent.id)
            val deleted = model.notes.node(parent.id)!!.diaryInboxItems().single()
            assertEquals("deleted", deleted.status)
            assertEquals("published", deleted.originalStatus)
            model.restoreDiaryInbox(parent, deleted.id); model.flush(parent.id)
            assertEquals(resent, model.notes.node(parent.id)!!.diaryMoments().single())
            assertTrue(model.notes.node(parent.id)!!.diaryInboxItems().isEmpty())
            model.deleteDiaryMoment(parent, resent.id)
            val editDeleted = model.editDiaryInbox(parent, resent.id)!!
            model.flush(parent.id)
            assertEquals("draft", model.notes.node(parent.id)!!.diaryInboxItems().single().status)
            assertEquals(resent.occurredAt, editDeleted.diaryOccurredAt)
            assertEquals(resent.sentAt, model.notes.node(parent.id)!!.diaryInboxItems().single().moment!!.sentAt)
        } finally { model.setDiaryRetentionDays(oldInboxDays, oldTrashDays); model.flushAll(); model.notes.removeVersions(listOf(parent.id)); model.notes.remove(listOf(parent.id)) }
    }

    @Test fun archivedRoadKeepsMediaAndSettingsAndMergesWithoutReplacingNewContent() = runBlocking {
        val model = model()
        val parent = model.currentDiary(dayMillis(LocalDate.of(2048, 1, 3)))
        val image = NoteBlock(type = "image", text = "照片", uri = "file:///protected/image.png", owned = true)
        try {
            model.save(parent.copy(text = "当天正文")); model.flush(parent.id)
            val first = model.publishDiaryMoment(parent, DiaryMoment(text = "第一处", document = encodeBlocks(listOf(NoteBlock(text = "第一处"), image))))
            model.updateDiaryRoadAppearance(parent, "autumn", "file:///protected/background.png")
            model.updateDiaryRoadLayout(parent, "right")
            model.archiveDiaryRoad(parent); model.flush(parent.id)
            val archived = model.notes.node(parent.id)!!
            assertFalse(archived.diaryRoadEnabled)
            assertEquals("当天正文", archived.text)
            val item = archived.diaryInboxItems().single()
            assertEquals("road", item.status)
            assertEquals("right", item.roadLayout)
            assertTrue(archived.materialBlocks().any { it.uri == image.uri })
            assertTrue(archived.materialBlocks().any { it.uri == "file:///protected/background.png" })
            val second = model.publishDiaryMoment(parent, DiaryMoment(text = "新的一处"))
            model.updateDiaryRoadAppearance(parent, "winter", "file:///new/background.png")
            model.updateDiaryRoadLayout(parent, "left")
            model.restoreDiaryInbox(parent, item.id); model.flush(parent.id)
            val restored = model.notes.node(parent.id)!!
            assertEquals(setOf(first.id, second.id), restored.diaryMoments().map { it.id }.toSet())
            assertEquals("winter", restored.diaryRoadTheme)
            assertEquals("left", restored.diaryRoadLayout)
            assertEquals("当天正文", restored.text)
            assertTrue(restored.diaryInboxItems().isEmpty())
        } finally { model.flushAll(); model.notes.removeVersions(listOf(parent.id)); model.notes.remove(listOf(parent.id)) }
    }

    @Test fun trashKeepsInboxAccessibleAndRestoringWholeDiaryMergesNewDraftEdits() = runBlocking {
        val model = model()
        val parent = model.currentDiary(dayMillis(LocalDate.of(2048, 1, 4)))
        val ids = mutableSetOf(parent.id)
        try {
            model.save(parent.copy(text = "原日记正文")); model.flush(parent.id)
            model.publishDiaryMoment(parent, DiaryMoment(text = "已发布随记"))
            val draft = DiaryMoment(text = "未发送原稿", document = encodeBlocks(listOf(NoteBlock(text = "未发送原稿"), NoteBlock(type = "file", text = "附件", uri = "file:///protected/file.pdf", owned = true))))
            model.saveDiaryDraft(parent, draft); model.flush(parent.id)
            model.trash(setOf(parent.id)).join()
            val carrier = model.currentDiary(parent.day!!)
            ids += carrier.id
            assertNotEquals(parent.id, carrier.id)
            assertFalse(carrier.hasDiaryContent())
            assertEquals(draft.id, carrier.diaryInboxItems().single().moment!!.id)
            assertTrue(model.notes.node(parent.id)!!.diaryInboxItems().isEmpty())
            val editable = model.getDiaryMomentNote(parent.day!!, draft.id)!!
            assertEquals(carrier.id, editable.parentId)
            val intermediate = carrier.copy(diaryInbox = encodeDiaryInbox(listOf(DiaryInboxItem(id = draft.id,
                moment = draft.copy(document = encodeBlocks(listOf(NoteBlock(type = "image", uri = "file:///protected/after-trash-only.png", owned = true))))))))
            model.notes.version(NoteVersion(nodeId = carrier.id, snapshot = jsonObject(intermediate).toString()))
            model.save(editable.copy(text = "删除日记后继续编辑", document = encodeBlocks(listOf(NoteBlock(text = "删除日记后继续编辑"), NoteBlock(type = "file", uri = "file:///protected/file.pdf")))))
            model.flush(editable.id)
            model.restore(null, setOf(parent.id)).join()
            val restored = model.notes.node(parent.id)!!
            assertEquals("原日记正文", restored.text)
            assertEquals("删除日记后继续编辑", restored.diaryInboxItems().single().moment!!.text)
            assertEquals(1, restored.diaryMoments().size)
            assertNull(model.notes.node(carrier.id))
            assertTrue(restored.materialBlocks().any { it.uri == "file:///protected/file.pdf" })
            assertTrue(model.notes.versions(parent.id).any { version -> decodeNode(JSONObject(version.snapshot)).materialBlocks().any { it.uri == "file:///protected/after-trash-only.png" } })
        } finally { model.flushAll(); model.notes.removeVersions(ids.toList()); model.notes.remove(ids.toList()) }
    }

    @Test fun failedSendRetainsDraftAndRetryGetsActualSendTime() = runBlocking {
        val model = model()
        val parent = model.currentDiary(dayMillis(LocalDate.of(2048, 1, 5)))
        val draft = DiaryMoment(createdAt = 1, text = "保留直到成功发送")
        val blocked = File(context.filesDir, "drafts/${parent.id}.json.new")
        try {
            model.saveDiaryDraft(parent, draft); model.flush(parent.id)
            blocked.mkdirs()
            assertTrue(runCatching { model.publishDiaryMoment(parent, draft) }.isFailure)
            assertTrue(model.currentDiary(parent.day!!).diaryMoments().isEmpty())
            assertEquals(draft, model.currentDiary(parent.day!!).diaryInboxItems().single().moment)
            blocked.delete()
            model.flushAll()
            val retryAt = System.currentTimeMillis()
            val sent = model.publishDiaryMoment(parent, draft)
            assertTrue(sent.sentAt!! >= retryAt)
            assertEquals(sent.sentAt, sent.occurredAt)
            assertTrue(model.notes.node(parent.id)!!.diaryInboxItems().isEmpty())
        } finally { blocked.delete(); model.flushAll(); model.notes.removeVersions(listOf(parent.id)); model.notes.remove(listOf(parent.id)) }
    }

    @Test fun retentionOnlyExpiresExplicitFutureDeletionsAndSendSortIgnoresOccurrenceEdits() = runBlocking {
        val model = model()
        val parent = model.currentDiary(dayMillis(LocalDate.of(2048, 1, 6)))
        val inboxDays = model.diaryInboxRetentionDays(); val trashDays = model.diaryTrashRetentionDays()
        try {
            model.setDiaryRetentionDays(0, 0)
            val permanent = DiaryMoment(text = "永久保存")
            model.saveDiaryDraft(parent, permanent); model.deleteDiaryInbox(parent, permanent.id); model.flush(parent.id)
            assertNull(model.notes.node(parent.id)!!.diaryInboxItems().single().expiresAt)
            model.setDiaryRetentionDays(1, 2)
            val limited = DiaryMoment(text = "主动选择期限")
            model.saveDiaryDraft(parent, limited); model.deleteDiaryInbox(parent, limited.id); model.flush(parent.id)
            val chosen = model.notes.node(parent.id)!!
            assertNotNull(chosen.diaryInboxItems().first { it.id == limited.id }.expiresAt)
            assertNull(chosen.diaryInboxItems().first { it.id == permanent.id }.expiresAt)
            model.setDiaryRetentionDays(0, 0)
            assertNotNull(model.currentDiary(parent.day!!).diaryInboxItems().first { it.id == limited.id }.expiresAt)
            val expired = chosen.copy(diaryInbox = encodeDiaryInbox(chosen.diaryInboxItems().map { if (it.id == limited.id) it.copy(expiresAt = 1) else it }), updatedAt = chosen.updatedAt + 100)
            model.notes.put(expired)
            model.nodes.first { list -> list.any { it.id == parent.id && it.updatedAt == expired.updatedAt } }
            model.cleanupDiaryRetention().join()
            assertEquals(listOf(permanent.id), model.notes.node(parent.id)!!.diaryInboxItems().map { it.id })

            val a = DiaryMoment(id = "a", text = "A", sentAt = 10, occurredAt = 30)
            val b = DiaryMoment(id = "b", text = "B", sentAt = 10, occurredAt = 20)
            assertEquals(listOf("a", "b"), sortedDiaryMoments(listOf(b, a), "sent").map { it.id })
            assertEquals(listOf("a", "b"), sortedDiaryMoments(listOf(b.copy(occurredAt = 5), a), "sent").map { it.id })
            assertEquals(listOf("b", "a"), sortedDiaryMoments(listOf(a, b), "occurred").map { it.id })
        } finally { model.setDiaryRetentionDays(inboxDays, trashDays); model.flushAll(); model.notes.removeVersions(listOf(parent.id)); model.notes.remove(listOf(parent.id)) }
    }

    @Test fun legacyDeletedDiaryConflictMovesInboxToActiveDayAndClonesDifferentSameIdDrafts() = runBlocking {
        val model = model()
        val day = dayMillis(LocalDate.of(2050, 1, 4))
        val suffix = UUID.randomUUID().toString()
        val shared = "shared-$suffix"
        val currentMoment = DiaryMoment(id = shared, text = "当前草稿", document = encodeBlocks(listOf(NoteBlock(type = "file", uri = "file:///current/file.pdf"))))
        val oldMoment = DiaryMoment(id = shared, text = "旧草稿", occurredAt = 123,
            document = encodeBlocks(listOf(NoteBlock(type = "image", uri = "file:///legacy/photo.png", owned = true))))
        val current = NoteNode(id = "current-$suffix", kind = "diary", day = day, text = "当前正文",
            diaryInbox = encodeDiaryInbox(listOf(DiaryInboxItem(id = shared, moment = currentMoment))))
        val archived = DiaryInboxItem(status = "road", originalStatus = "road", road = encodeDiaryMoments(listOf(DiaryMoment(text = "旧归档片段"))),
            roadBackground = "file:///legacy/background.png", roadLayout = "right")
        val old = NoteNode(id = "deleted-$suffix", kind = "diary", day = day, text = "旧正文", deletedAt = 123, deleteGroup = "group-$suffix",
            diaryRoad = encodeDiaryMoments(listOf(DiaryMoment(text = "旧小路"))),
            diaryInbox = encodeDiaryInbox(listOf(DiaryInboxItem(id = shared, moment = oldMoment), archived)))
        val ids = listOf(current.id, old.id)
        try {
            model.notes.putAll(listOf(current, old))
            model.nodes.first { it.any { node -> node.id == current.id } }
            model.restore(null, setOf(old.id)).join()
            val recovered = model.notes.node(old.id)!!
            assertEquals("memory", recovered.kind)
            assertTrue(recovered.diaryInboxItems().isEmpty())
            assertTrue(recovered.text.contains("旧正文"))
            assertTrue(recovered.text.contains("旧小路"))
            val active = model.notes.node(current.id)!!
            assertEquals("当前正文", active.text)
            assertEquals(3, active.diaryInboxItems().size)
            assertEquals(1, active.diaryInboxItems().count { it.id == shared })
            val copied = active.diaryInboxItems().single { it.moment?.text == "旧草稿" }
            assertNotEquals(shared, copied.id)
            assertNotEquals(shared, copied.moment!!.id)
            assertEquals(123L, copied.moment.occurredAt)
            assertTrue(active.materialBlocks().any { it.uri == "file:///legacy/photo.png" })
            assertTrue(active.materialBlocks().any { it.uri == "file:///legacy/background.png" })
            assertEquals(old.diaryInbox, decodeNode(JSONObject(model.notes.versions(old.id).first().snapshot)).diaryInbox)
            val editable = model.getDiaryMomentNote(day, copied.moment.id)!!
            assertEquals(current.id, editable.parentId)
            model.save(editable.copy(text = "继续编辑旧草稿")); model.flush(editable.id)
            assertTrue(model.notes.node(current.id)!!.diaryInboxItems().any { it.moment?.text == "继续编辑旧草稿" })
            assertTrue(model.notes.node(current.id)!!.diaryInboxItems().any { it.moment?.text == "当前草稿" })
        } finally { model.flushAll(); model.notes.removeVersions(ids); model.notes.remove(ids) }
    }
}
