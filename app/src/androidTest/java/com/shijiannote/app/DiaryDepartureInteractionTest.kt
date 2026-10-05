package com.shijiannote.app

import android.app.Application
import android.graphics.Bitmap
import android.view.WindowInsets
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.shijiannote.app.data.NoteNode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

class DiaryDepartureInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun model(): WorkspaceModel {
        lateinit var value: WorkspaceModel
        rule.runOnUiThread { value = WorkspaceModel(rule.activity.application as Application) }
        runBlocking { value.spacesReady.first { it } }
        return value
    }

    private fun seed(model: WorkspaceModel, moment: DiaryMoment? = null): NoteNode = runBlocking {
        val occupied = model.notes.nodes().mapNotNull { it.day }.toSet()
        val date = generateSequence(LocalDate.of(1984, 1, 1)) { it.plusDays(1) }.first { dayMillis(it) !in occupied }
        NoteNode(id = "departure-${UUID.randomUUID()}", kind = "diary", day = dayMillis(date),
            text = "当天结语保留", diaryRoadEnabled = true,
            diaryRoad = moment?.let { encodeDiaryMoments(listOf(it)) }.orEmpty()).also { note ->
                model.notes.put(note)
                model.nodes.first { nodes -> nodes.any { it.id == note.id } }
            }
    }

    private fun cleanup(model: WorkspaceModel, note: NoteNode) = runBlocking {
        rule.runOnUiThread { rule.activity.setContent { YouthTheme {} } }
        model.flush(note.id)
        model.notes.remove(listOf(note.id)); model.notes.removeVersions(listOf(note.id))
    }

    private fun backPastKeyboard(hasLeft: () -> Boolean) {
        repeat(3) {
            if (hasLeft() || rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) return
            rule.onNodeWithContentDescription("返回").performClick()
            rule.waitUntil(3_000) { !rule.activity.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime()) }
            // Visibility changes before Compose consumes the IME show/hide animation.
            android.os.SystemClock.sleep(350)
            rule.waitForIdle()
        }
    }

    @Test fun unchangedPlainAndRichMomentsLeaveWithoutPromptOrRetainedEdit() {
        val model = model()
        listOf(
            DiaryMoment(text = "旧纯文本片段", createdAt = 100, sentAt = 200, occurredAt = 150),
            DiaryMoment(text = "原有富文本", tags = "生活", createdAt = 100, sentAt = 200, occurredAt = 150,
                document = encodeBlocks(listOf(NoteBlock(text = "原有富文本", bold = true), NoteBlock(type = "quote", text = "引用段落"))))
        ).forEach { original ->
            val note = seed(model, original)
            var left = false
            try {
                rule.activity.setContent { YouthTheme { DiaryRoadScreen(note, model, { left = true }, {}, { _, _ -> }) } }
                rule.onNodeWithText(original.text).performScrollTo().performClick()
                rule.waitUntil(5_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithContentDescription("发送片段").assertIsNotEnabled()
                backPastKeyboard { left }
                rule.waitUntil(5_000) { left }
                rule.onNodeWithText("片段修改尚未完成").assertDoesNotExist()
                val saved = runBlocking { model.notes.node(note.id) }!!
                assertEquals(listOf(original), saved.diaryMoments())
                assertTrue(saved.diaryInboxItems().isEmpty())
                runBlocking { model.notes.versions(note.id) }.forEach { version ->
                    assertEquals(listOf(original), decodeNode(JSONObject(version.snapshot)).diaryMoments())
                }
            } finally { cleanup(model, note) }
        }
    }

    @Test fun editingThenUndoingToOriginalAlsoLeavesWithoutPrompt() {
        val model = model()
        val original = DiaryMoment(text = "撤销后还是原文", createdAt = 100, sentAt = 200, occurredAt = 150)
        val note = seed(model, original)
        var left = false
        try {
            rule.activity.setContent { YouthTheme { DiaryRoadScreen(note, model, { left = true }, {}, { _, _ -> }) } }
            rule.onNodeWithText(original.text).performScrollTo().performClick()
            rule.onNode(hasSetTextAction()).performTextInput("追加的文字")
            rule.onNodeWithContentDescription("撤销片段修改").performClick()
            val restored = model.currentDiary(note.day!!).diaryInboxItems().single().moment!!
            assertTrue("Undo must first restore the original content: ${jsonObject(restored)}",
                sameDiaryMomentEditContent(original, restored))
            backPastKeyboard { left }
            rule.waitUntil(5_000) { left }
            rule.onNodeWithText("片段修改尚未完成").assertDoesNotExist()
            assertEquals(listOf(original), model.currentDiary(note.day!!).diaryMoments())
            assertTrue(model.currentDiary(note.day!!).diaryInboxItems().isEmpty())
        } finally { cleanup(model, note) }
    }

    @Test fun changedMomentCanBeSavedDirectlyFromDepartureDialog() {
        val model = model()
        val original = DiaryMoment(text = "保存前的原文", createdAt = 100, sentAt = 200, occurredAt = 150)
        val note = seed(model, original)
        var left = false
        try {
            rule.activity.setContent { YouthTheme { DiaryRoadScreen(note, model, { left = true }, {}, { _, _ -> }) } }
            rule.onNodeWithText(original.text).performScrollTo().performClick()
            rule.onNode(hasSetTextAction()).performTextReplacement("从弹窗直接保存的正文")
            backPastKeyboard { left }
            rule.onNodeWithText("片段修改尚未完成").assertExists()
            val choices = listOf("直接保存", "存入收纳箱并离开", "继续编辑", "丢弃")
            choices.forEach { rule.onNodeWithText(it, substring = false).assertIsDisplayed() }
            val bounds = choices.map { rule.onNodeWithText(it, substring = false).getUnclippedBoundsInRoot() }
            bounds.forEachIndexed { index, a -> bounds.drop(index + 1).forEach { b ->
                assertTrue("Departure actions must not overlap: $a / $b",
                    a.bottom <= b.top || b.bottom <= a.top || a.right <= b.left || b.right <= a.left)
            } }
            File(rule.activity.filesDir, "diary-departure-save.png").outputStream().use {
                rule.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertFalse(left)
            assertEquals(listOf(original), model.currentDiary(note.day!!).diaryMoments())
            rule.onNodeWithText("直接保存").performClick()
            rule.waitUntil(5_000) { left }
            val saved = runBlocking { model.notes.node(note.id) }!!
            val changed = saved.diaryMoments().single()
            assertEquals("从弹窗直接保存的正文", changed.text)
            assertEquals(original.id, changed.id)
            assertEquals(original.createdAt, changed.createdAt)
            assertEquals(original.sentAt, changed.sentAt)
            assertEquals(original.occurredAt, changed.occurredAt)
            assertEquals(note.text, saved.text)
            assertTrue(saved.diaryInboxItems().isEmpty())
        } finally { cleanup(model, note) }
    }

    @Test fun newUnsentMomentCanAlsoBeSavedDirectlyBeforeLeaving() {
        val model = model(); val note = seed(model)
        var left = false
        try {
            rule.activity.setContent { YouthTheme { DiaryRoadScreen(note, model, { left = true }, {}, { _, _ -> }) } }
            rule.onNodeWithContentDescription("键盘").performClick()
            rule.onNode(hasSetTextAction()).performTextInput("新片段直接保存")
            backPastKeyboard { left }
            rule.onNodeWithText("片段尚未发送").assertExists()
            rule.onNodeWithText("直接保存").performClick()
            rule.waitUntil(5_000) { left }
            val saved = runBlocking { model.notes.node(note.id) }!!
            assertEquals("新片段直接保存", saved.diaryMoments().single().text)
            assertNotNull(saved.diaryMoments().single().sentAt)
            assertTrue(saved.diaryInboxItems().isEmpty())
        } finally { cleanup(model, note) }
    }

    @Test fun actualChangesIncludeFormatTagsTimeAndResumedDraftRatherThanOnlyThisSession() {
        val model = model()
        val original = DiaryMoment(text = "原文", createdAt = 100, sentAt = 200, occurredAt = 150)
        val note = seed(model, original)
        val state = DiaryMomentComposerState(model, note.day!!) { throw AssertionError(it) }
        try {
            state.open(original.asNote(note))
            assertFalse(state.hasPublishedChanges)
            val variants = listOf(original.copy(tags = "新标签"), original.copy(occurredAt = 151),
                original.copy(document = encodeBlocks(listOf(NoteBlock(text = original.text, italic = true)))),
                original.copy(document = encodeBlocks(listOf(NoteBlock(text = original.text), NoteBlock(type = "file", uri = "content://departure/file")))))
            variants.forEach { changed ->
                state.change(changed)
                assertTrue(state.hasPublishedChanges)
                assertFalse(model.cancelUnchangedDiaryMomentEdit(note, changed))
                state.undo()
                assertFalse(state.hasPublishedChanges)
            }
            val audio = File(rule.activity.filesDir, "assets/departure-${UUID.randomUUID()}.m4a").apply {
                parentFile!!.mkdirs(); writeText("pending recording")
            }
            try {
                val prefs = rule.activity.getSharedPreferences("recording_inbox", android.content.Context.MODE_PRIVATE)
                val entry = org.json.JSONObject().put("owner", state.recordOwner).put("path", audio.absolutePath)
                    .put("finished", true).put("duration", 900)
                prefs.edit().putString("items", org.json.JSONArray(prefs.getString("items", "[]")).put(entry).toString()).commit()
                assertTrue(state.hasPublishedChanges)
                assertFalse(model.cancelUnchangedDiaryMomentEdit(note, state.moment))
            } finally {
                RecordingService.removeInbox(rule.activity, audio.absolutePath)
                audio.delete()
            }
            assertFalse(state.hasPublishedChanges)
            state.change(original.copy(text = "上次留下的修改草稿"))
            val resumed = DiaryMomentComposerState(model, note.day!!) { throw AssertionError(it) }
            resumed.open(original.asNote(note))
            assertTrue(resumed.hasPublishedChanges)
            assertEquals("上次留下的修改草稿", resumed.moment.text)
            assertFalse(runBlocking { resumed.closeUnchangedEdit() })
            assertEquals(listOf(original), model.currentDiary(note.day!!).diaryMoments())
        } finally { cleanup(model, note) }
    }

    @Test fun legacyDefaultTimeAndBlockIdentityNormalizationAreNotActualChanges() {
        val model = model()
        val original = DiaryMoment(text = "旧片段默认时间", createdAt = 100, sentAt = 200)
        val note = seed(model, original)
        val state = DiaryMomentComposerState(model, note.day!!) { throw AssertionError(it) }
        try {
            state.open(original.asNote(note))
            state.persist()
            assertFalse(state.hasPublishedChanges)
            assertTrue(runBlocking { state.closeUnchangedEdit() })
            assertEquals(listOf(original), model.currentDiary(note.day!!).diaryMoments())
            assertTrue(model.currentDiary(note.day!!).diaryInboxItems().isEmpty())
        } finally { cleanup(model, note) }
    }
}
