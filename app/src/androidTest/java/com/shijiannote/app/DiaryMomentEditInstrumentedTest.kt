package com.shijiannote.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

class DiaryMomentEditInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun model(): WorkspaceModel = withContext(Dispatchers.Main) {
        WorkspaceModel(context.applicationContext as Application)
    }.also { it.spacesReady.first { ready -> ready } }

    @Test fun editingAutosavesSurviveRestartAndOnlyCompletionChangesPublishedContent() = runBlocking {
        val model = model()
        val parent = model.currentDiary(dayMillis(LocalDate.of(2072, 1, 1)))
        var restarted: WorkspaceModel? = null
        try {
            val original = model.publishDiaryMoment(parent, DiaryMoment(text = "当时写下的话", tags = "散步", occurredAt = 100))
            val summarySnapshot = model.currentDiary(parent.day!!)
            assertNotNull(model.beginDiaryMomentEdit(parent, original.id))
            val changed = original.copy(text = "重新整理的话", tags = "散步,感受", occurredAt = 200)
            val item = model.saveDiaryMomentEdit(parent, changed)
            assertEquals("editing", item.originalStatus)
            assertEquals(original.id, item.moment!!.id)
            assertEquals(original, model.currentDiary(parent.day!!).diaryMoments().single())
            model.save(summarySnapshot.copy(text = "结语也在同时编辑", title = "这一天"))
            model.flush(parent.id)
            assertEquals(changed.text, model.notes.node(parent.id)!!.diaryInboxItems().single().moment!!.text)

            val resumedModel = model().also { restarted = it }
            val resumed = resumedModel.currentDiary(parent.day!!)
            assertEquals(original, resumed.diaryMoments().single())
            val editor = resumedModel.getDiaryMomentNote(parent.day!!, original.id)!!
            assertEquals(changed.text, editor.text)
            assertEquals(changed.tags, editor.tags)
            assertEquals(200L, editor.diaryOccurredAt)
            val completed = resumedModel.completeDiaryMomentEdit(resumed, editor.asDiaryMoment())
            assertEquals(original.id, completed.id)
            assertEquals(original.createdAt, completed.createdAt)
            assertEquals(original.sentAt, completed.sentAt)
            assertEquals(200L, completed.occurredAt)
            assertEquals(changed.tags, completed.tags)
            val saved = resumedModel.notes.node(parent.id)!!
            assertEquals(listOf(completed), saved.diaryMoments())
            assertTrue(saved.diaryInboxItems().isEmpty())
            assertEquals("结语也在同时编辑", saved.text)
            assertTrue(resumedModel.notes.versions(parent.id).any { version ->
                decodeNode(JSONObject(version.snapshot)).diaryMoments().any { sameDiaryMomentState(it, original) }
            })
        } finally {
            restarted?.flushAll(); model.flushAll()
            model.notes.removeVersions(listOf(parent.id)); model.notes.remove(listOf(parent.id))
        }
    }

    @Test fun discardingOrRestoringEditingDraftNeverDeletesOriginalAndTimeOnlyDraftIsDurable() = runBlocking {
        val model = model()
        val parent = model.currentDiary(dayMillis(LocalDate.of(2072, 1, 2)))
        var restarted: WorkspaceModel? = null
        try {
            val original = model.publishDiaryMoment(parent, DiaryMoment(text = "原文", tags = "原标签"))
            model.beginDiaryMomentEdit(parent, original.id)
            val draft = model.saveDiaryMomentEdit(parent, original.copy(text = "", document = "", tags = "", occurredAt = 300))
            model.deleteDiaryInbox(parent, draft.id); model.flush(parent.id)
            var saved = model.notes.node(parent.id)!!
            assertEquals(original, saved.diaryMoments().single())
            assertEquals("editing", saved.diaryInboxItems().single().originalStatus)
            assertEquals("deleted", saved.diaryInboxItems().single().status)
            model.restoreDiaryInbox(parent, draft.id); model.flush(parent.id)
            saved = model.notes.node(parent.id)!!
            assertEquals(original, saved.diaryMoments().single())
            assertEquals("draft", saved.diaryInboxItems().single().status)
            model.permanentlyDeleteDiaryInbox(parent, draft.id)
            val timeOnly = DiaryMoment(occurredAt = 400)
            model.saveDiaryDraft(parent, timeOnly); model.flush(parent.id)
            val resumedModel = model().also { restarted = it }
            saved = resumedModel.currentDiary(parent.day!!)
            assertEquals(original, saved.diaryMoments().single())
            assertEquals(timeOnly, saved.diaryInboxItems().single().moment)
            assertNull(saved.diaryInboxItems().single().moment!!.sentAt)
        } finally {
            restarted?.flushAll(); model.flushAll()
            model.notes.removeVersions(listOf(parent.id)); model.notes.remove(listOf(parent.id))
        }
    }

    @Test fun withdrawingDuringEditRetainsSeparateDraftAndCannotResurrectOriginalOnCompletion() = runBlocking {
        val model = model()
        val parent = model.currentDiary(dayMillis(LocalDate.of(2072, 1, 3)))
        try {
            val original = model.publishDiaryMoment(parent, DiaryMoment(text = "原片段", tags = "原标签", occurredAt = 500))
            model.beginDiaryMomentEdit(parent, original.id)
            model.saveDiaryMomentEdit(parent, original.copy(text = "还没完成的修改", tags = "编辑标签"))
            model.retractDiaryMoment(parent, original.id)
            val failure = runCatching { model.completeDiaryMomentEdit(parent, original.copy(text = "最后一次自动保存", tags = "编辑标签")) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message!!.contains("收纳箱"))
            val saved = model.notes.node(parent.id)!!
            assertTrue(saved.diaryMoments().isEmpty())
            assertEquals(original, saved.diaryInboxItems().single { it.status == "retracted" }.moment)
            val retained = saved.diaryInboxItems().single { it.status == "draft" }
            assertEquals("draft", retained.originalStatus)
            assertEquals(original.id, retained.editingOf)
            assertNotEquals(original.id, retained.moment!!.id)
            assertEquals("最后一次自动保存", retained.moment!!.text)
            assertEquals(retained.moment!!.id, model.getDiaryMomentNote(parent.day!!, original.id)!!.asDiaryMoment().id)
            val resent = model.publishDiaryMoment(parent, retained.moment!!)
            assertEquals(retained.moment!!.id, resent.id)
            assertEquals(1, model.notes.node(parent.id)!!.diaryMoments().size)
            assertEquals(original, model.notes.node(parent.id)!!.diaryInboxItems().single { it.status == "retracted" }.moment)
        } finally { model.flushAll(); model.notes.removeVersions(listOf(parent.id)); model.notes.remove(listOf(parent.id)) }
    }

    @Test fun tagsAndEditingBaselineRoundTripAndRemapWithoutFalseConflict() = runBlocking {
        val model = model()
        val parent = model.currentDiary(dayMillis(LocalDate.of(2072, 1, 4)))
        val beforeUri = "file:///old/original-${UUID.randomUUID()}.png"
        val draftUri = "file:///old/edit-${UUID.randomUUID()}.png"
        val newBeforeUri = "file:///restored/original.png"
        val newDraftUri = "file:///restored/edit.png"
        try {
            val legacy = JSONObject().put("id", "legacy").put("createdAt", 123).put("text", "旧内容")
            assertEquals("", decodeDiaryMoment(legacy).tags)
            assertEquals(123L, decodeDiaryMoment(legacy).sentAt)
            val original = model.publishDiaryMoment(parent, DiaryMoment(title = "旧片段标题", tags = "照片,生活", text = "原文",
                document = encodeBlocks(listOf(NoteBlock(text = "原文"), NoteBlock(type = "image", uri = beforeUri, owned = true))), occurredAt = 600))
            model.beginDiaryMomentEdit(parent, original.id)
            val edited = original.copy(title = "修改标题", tags = "照片,旅程", text = "修改",
                document = encodeBlocks(listOf(NoteBlock(text = "修改"), NoteBlock(type = "image", uri = draftUri, owned = true))), occurredAt = 700)
            model.saveDiaryMomentEdit(parent, edited); model.flush(parent.id)
            val encoded = jsonObject(model.notes.node(parent.id)!!).toString()
            val decoded = decodeNode(JSONObject(encoded))
            assertEquals(original.tags, decoded.diaryMoments().single().asNote(decoded).asDiaryMoment().tags)
            assertEquals(edited.tags, decoded.diaryInboxItems().single().moment!!.tags)
            assertTrue(decoded.materialBlocks().any { it.uri == beforeUri })
            assertTrue(decoded.materialBlocks().any { it.uri == draftUri })
            val remapped = decoded.remapMaterials(mapOf(beforeUri to newBeforeUri, draftUri to newDraftUri))
            val baseline = decodeDiaryMoment(JSONObject(remapped.diaryInboxItems().single().originalMoment))
            assertTrue(sameDiaryMomentState(remapped.diaryMoments().single(), baseline))
            assertTrue(remapped.materialBlocks().any { it.uri == newBeforeUri })
            assertTrue(remapped.materialBlocks().none { it.uri == beforeUri || it.uri == draftUri })
            model.replaceWorkspace { model.notes.put(remapped) }
            model.nodes.first { rows -> rows.any { it.id == parent.id && it.diaryInbox == remapped.diaryInbox } }
            val resumed = model.currentDiary(parent.day!!)
            val completed = model.completeDiaryMomentEdit(resumed, resumed.diaryInboxItems().single().moment!!)
            assertEquals(original.sentAt, completed.sentAt)
            assertEquals(edited.tags, completed.tags)
            assertEquals(700L, completed.occurredAt)
            assertTrue(decodeBlocks(completed.document, completed.text).any { it.uri == newDraftUri })
            assertTrue(model.notes.node(parent.id)!!.diaryInboxItems().isEmpty())
            assertTrue(model.notes.versions(parent.id).any { decodeNode(JSONObject(it.snapshot)).materialBlocks().any { block -> block.uri == newBeforeUri } })
        } finally { model.flushAll(); model.notes.removeVersions(listOf(parent.id)); model.notes.remove(listOf(parent.id)) }
    }

    @Test fun editingRecordingRecoversIntoDraftEvenAfterWholeRoadIsArchived() = runBlocking {
        val model = model()
        val parent = model.currentDiary(dayMillis(LocalDate.of(2072, 1, 5)))
        val audio = File(context.filesDir, "assets/edit-voice-${UUID.randomUUID()}.m4a").apply { parentFile!!.mkdirs(); writeText("voice payload") }
        val previousRecording = RecordingService.state.value
        try {
            val original = model.publishDiaryMoment(parent, DiaryMoment(text = "原片段"))
            val editor = model.beginDiaryMomentEdit(parent, original.id)!!
            val owner = model.registerDiaryRecording(editor)
            assertNotEquals(editor.id, owner)
            val prefs = context.getSharedPreferences("recording_inbox", Context.MODE_PRIVATE)
            val inbox = JSONArray(prefs.getString("items", "[]"))
                .put(JSONObject().put("owner", owner).put("path", audio.absolutePath).put("finished", true).put("duration", 900))
            prefs.edit().putString("items", inbox.toString()).commit()
            RecordingService.state.value = RecordingState()
            model.recoverDiaryRecordings()
            var saved = model.notes.node(parent.id)!!
            assertEquals(original, saved.diaryMoments().single())
            assertTrue(decodeBlocks(saved.diaryInboxItems().single().moment!!.document).any { it.type == "audio" })
            assertTrue(RecordingService.inbox(context, owner).isEmpty())

            // The same service owner survives the edit being detached while the road is archived.
            model.archiveDiaryRoad(parent); model.flush(parent.id)
            val secondInbox = JSONArray(prefs.getString("items", "[]"))
                .put(JSONObject().put("owner", owner).put("path", audio.absolutePath).put("finished", true).put("duration", 900))
            prefs.edit().putString("items", secondInbox.toString()).commit()
            model.recoverDiaryRecordings()
            saved = model.notes.node(parent.id)!!
            assertTrue(saved.diaryMoments().isEmpty())
            val detached = saved.diaryInboxItems().single { it.status == "draft" }
            assertEquals("draft", detached.originalStatus)
            assertNotEquals(original.id, detached.moment!!.id)
            assertEquals(1, decodeBlocks(detached.moment!!.document).count { it.type == "audio" })
            assertEquals(original, decodeDiaryMoments(saved.diaryInboxItems().single { it.status == "road" }.road).single())
            assertTrue(RecordingService.inbox(context, owner).isEmpty())
            assertTrue(saved.materialBlocks().any { it.uri == Uri.fromFile(audio).toString() })
        } finally {
            RecordingService.state.value = previousRecording
            RecordingService.removeInbox(context, audio.absolutePath)
            model.flushAll(); model.notes.removeVersions(listOf(parent.id)); model.notes.remove(listOf(parent.id)); audio.delete()
        }
    }

    @Test fun completionFailureRetainsOriginalAndEditingDraftForRetry() = runBlocking {
        val model = model()
        val parent = model.currentDiary(dayMillis(LocalDate.of(2072, 1, 6)))
        val blocker = File(context.filesDir, "drafts/${parent.id}.json.new")
        try {
            val original = model.publishDiaryMoment(parent, DiaryMoment(text = "持久保存的原文", tags = "原标签", occurredAt = 800))
            model.beginDiaryMomentEdit(parent, original.id)
            val changed = original.copy(text = "发送失败也不能丢的修改", tags = "新标签", occurredAt = 900)
            model.saveDiaryMomentEdit(parent, changed); model.flush(parent.id)
            blocker.mkdirs()
            File(blocker, "keep").writeText("block AtomicFile startWrite")
            assertNotNull(runCatching { model.completeDiaryMomentEdit(parent, changed) }.exceptionOrNull())
            val retained = model.currentDiary(parent.day!!)
            assertEquals(original, retained.diaryMoments().single())
            assertEquals(changed.text, retained.diaryInboxItems().single().moment!!.text)
            assertEquals("editing", retained.diaryInboxItems().single().originalStatus)
            blocker.deleteRecursively()
            model.flush(parent.id)
            val completed = model.completeDiaryMomentEdit(parent, changed)
            assertEquals(original.id, completed.id)
            assertEquals(original.sentAt, completed.sentAt)
            assertEquals(changed.tags, completed.tags)
            assertEquals(changed.occurredAt, completed.occurredAt)
            assertTrue(model.notes.node(parent.id)!!.diaryInboxItems().isEmpty())
        } finally {
            blocker.deleteRecursively()
            model.flushAll(); model.notes.removeVersions(listOf(parent.id)); model.notes.remove(listOf(parent.id))
        }
    }

    @Test fun wholeDiaryTrashRestoreAndLateRecoveredCarrierDraftPreservePendingEditWithoutResurrection() = runBlocking {
        val model = model()
        val parent = model.currentDiary(dayMillis(LocalDate.of(2072, 1, 7)))
        val ids = mutableSetOf(parent.id)
        var restarted: WorkspaceModel? = null
        var lateFile: File? = null
        try {
            val original = model.publishDiaryMoment(parent, DiaryMoment(text = "原片段", tags = "原标签", occurredAt = 1000))
            model.beginDiaryMomentEdit(parent, original.id)
            model.saveDiaryMomentEdit(parent, original.copy(text = "编辑中的内容", tags = "编辑标签")); model.flush(parent.id)
            model.trash(setOf(parent.id)).join()
            val carrier = model.currentDiary(parent.day!!)
            ids += carrier.id
            assertNotEquals(parent.id, carrier.id)
            assertEquals("draft", carrier.diaryInboxItems().single().originalStatus)
            assertNotEquals(original.id, carrier.diaryInboxItems().single().moment!!.id)
            assertEquals("编辑中的内容", carrier.diaryInboxItems().single().moment!!.text)
            model.restore(null, setOf(parent.id)).join()
            assertEquals(original, model.notes.node(parent.id)!!.diaryMoments().single())
            assertNull(model.notes.node(carrier.id))
            assertNotNull(runCatching { model.completeDiaryMomentEdit(parent, original.copy(text = "旧编辑器不能覆盖恢复后的原文")) }.exceptionOrNull())
            assertEquals(original, model.notes.node(parent.id)!!.diaryMoments().single())

            // Simulate an AtomicFile draft left by a process exit while the old carrier was removed.
            val late = carrier.copy(updatedAt = System.currentTimeMillis() + 100,
                diaryInbox = encodeDiaryInbox(carrier.diaryInboxItems().map { item -> item.copy(updatedAt = System.currentTimeMillis() + 100,
                    moment = item.moment!!.copy(text = "进程退出前的最后修改", tags = "最后标签")) }))
            lateFile = File(context.filesDir, "drafts/${carrier.id}.json").apply { writeText(jsonObject(late).toString()) }
            val resumedModel = model().also { restarted = it }
            val saved = resumedModel.currentDiary(parent.day!!)
            assertEquals(parent.id, saved.id)
            assertEquals(original, saved.diaryMoments().single())
            assertTrue(saved.diaryInboxItems().any { it.moment?.let { moment -> moment.text == "进程退出前的最后修改" && moment.tags == "最后标签" } == true })
            assertNull(resumedModel.notes.node(carrier.id))
            assertEquals(1, resumedModel.notes.nodes().count { it.kind == "diary" && it.day == parent.day && it.deletedAt == null })
        } finally {
            lateFile?.delete(); restarted?.flushAll(); model.flushAll()
            model.notes.removeVersions(ids.toList()); model.notes.remove(ids.toList())
        }
    }

    @Test fun clearingDraftDuringRecordingKeepsOwnerAndCompletedVoiceRecoversAfterRestart() = runBlocking {
        val model = model()
        val parent = model.currentDiary(dayMillis(LocalDate.of(2072, 1, 8)))
        val moment = DiaryMoment()
        val audio = File(context.filesDir, "assets/empty-draft-voice-${UUID.randomUUID()}.m4a").apply {
            parentFile!!.mkdirs(); writeText("voice without text")
        }
        val previousRecording = RecordingService.state.value
        var restarted: WorkspaceModel? = null
        try {
            val owner = model.registerDiaryRecording(moment.asNote(parent))
            RecordingService.state.value = RecordingState(owner = owner, running = true)
            model.saveDiaryDraft(parent, moment.copy(text = "随后清空的文字", tags = "随后删除的标签"))
            model.saveDiaryDraft(parent, moment); model.flush(parent.id)
            val whileRecording = model.notes.node(parent.id)!!
            assertEquals(owner, whileRecording.diaryInboxItems().single().recordingOwner)
            assertEquals(moment, whileRecording.diaryInboxItems().single().moment)
            assertFalse(whileRecording.hasDiaryContent())

            val prefs = context.getSharedPreferences("recording_inbox", Context.MODE_PRIVATE)
            val inbox = JSONArray(prefs.getString("items", "[]"))
                .put(JSONObject().put("owner", owner).put("path", audio.absolutePath).put("finished", true).put("duration", 1100))
            prefs.edit().putString("items", inbox.toString()).commit()
            RecordingService.state.value = RecordingState()
            // A final empty autosave may arrive after service completion but before audio collection.
            model.saveDiaryDraft(parent, moment); model.flush(parent.id)
            assertEquals(owner, model.notes.node(parent.id)!!.diaryInboxItems().single().recordingOwner)
            val resumedModel = model().also { restarted = it }
            val recovered = resumedModel.currentDiary(parent.day!!)
            assertTrue(recovered.diaryMoments().isEmpty())
            assertTrue(decodeBlocks(recovered.diaryInboxItems().single().moment!!.document).any {
                it.type == "audio" && it.uri == Uri.fromFile(audio).toString()
            })
            assertEquals(listOf("audio"), decodeBlocks(recovered.diaryInboxItems().single().moment!!.document).map { it.type })
            assertTrue(RecordingService.inbox(context, owner).isEmpty())
        } finally {
            RecordingService.state.value = previousRecording
            RecordingService.removeInbox(context, audio.absolutePath)
            restarted?.flushAll(); model.flushAll()
            model.notes.removeVersions(listOf(parent.id)); model.notes.remove(listOf(parent.id)); audio.delete()
        }
    }
}
