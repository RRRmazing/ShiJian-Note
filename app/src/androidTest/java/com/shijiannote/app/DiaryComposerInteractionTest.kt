package com.shijiannote.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import com.shijiannote.app.data.NoteNode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class DiaryComposerInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun model(): WorkspaceModel {
        lateinit var value: WorkspaceModel
        rule.runOnUiThread { value = WorkspaceModel(rule.activity.application as Application) }
        runBlocking { value.spacesReady.first { it } }
        return value
    }
    private fun fixture(model: WorkspaceModel, moment: DiaryMoment? = null): NoteNode = runBlocking {
        val occupied = model.notes.nodes().mapNotNull { it.day }.toSet()
        val date = generateSequence(LocalDate.of(1987, 1, 1)) { it.plusDays(1) }.first { dayMillis(it) !in occupied }
        NoteNode(id = "composer-ui-" + UUID.randomUUID(), kind = "diary", day = dayMillis(date), text = "结语保留",
            diaryRoad = moment?.let { encodeDiaryMoments(listOf(it)) }.orEmpty())
    }
    private fun seed(model: WorkspaceModel, note: NoteNode) = runBlocking {
        model.notes.put(note); model.nodes.first { all -> all.any { it.id == note.id } }
    }
    private fun cleanup(model: WorkspaceModel, note: NoteNode) = runBlocking {
        rule.runOnUiThread { rule.activity.setContent { YouthTheme {} } }
        model.flush(note.id); model.notes.remove(listOf(note.id)); model.notes.removeVersions(listOf(note.id))
    }
    @Test fun directMediaHasNoPlaceholderAndKeyboardAddsOnlyOneTextPosition() {
        val model = model(); val note = fixture(model); seed(model, note)
        lateinit var state: DiaryMomentComposerState
        try {
            val image = NoteBlock(type = "image", uri = "content://composer/direct-image", text = "图片")
            val file = NoteBlock(type = "file", uri = "content://composer/direct-file", text = "文件")
            rule.runOnUiThread {
                state = DiaryMomentComposerState(model, note.day!!) { throw AssertionError(it) }
                state.insert(image); state.insert(file)
                assertEquals(listOf(image, file), state.blocks)
            }
            rule.activity.setContent { YouthTheme { DiaryMomentComposer(state, androidx.compose.ui.unit.Dp(500f), {}, {}, {}, { throw AssertionError(it) }) } }
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            rule.onNodeWithText("留下这一刻…").assertDoesNotExist()
            rule.onNodeWithContentDescription("键盘").performClick()
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
            rule.onNodeWithContentDescription("键盘").performClick()
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
            rule.onNode(hasSetTextAction()).performTextInput("素材后接文字")
            rule.runOnUiThread {
                assertEquals(listOf("image", "file", "text"), state.blocks.map { it.type })
                state.undo(); assertEquals("", state.blocks.last().text)
                state.undo(); assertEquals(listOf(image, file), state.blocks)
                state.redo(); state.redo()
            }
            runBlocking { state.publish(rule.activity) }
            val published = model.currentDiary(note.day!!).diaryMoments().single()
            assertEquals(listOf("image", "file", "text"), published.blocks().map { it.type })
            assertEquals("素材后接文字", published.blocks().last().text)
        } finally { cleanup(model, note) }
    }
    @Test fun formatPlusAddsExactlyOneParagraphAndMediaKeepsMixedOrder() {
        val model = model(); val note = fixture(model); seed(model, note)
        lateinit var state: DiaryMomentComposerState
        try {
            rule.runOnUiThread {
                state = DiaryMomentComposerState(model, note.day!!) { throw AssertionError(it) }
                state.expanded = true
            }
            rule.activity.setContent { YouthTheme { DiaryMomentComposer(state, androidx.compose.ui.unit.Dp(500f), {}, {}, {}, { throw AssertionError(it) }) } }
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
            rule.onNodeWithContentDescription("新段落").performClick()
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(2)
            rule.onAllNodes(hasSetTextAction())[1].performTextInput("第二段")
            val image = NoteBlock(type = "image", uri = "content://composer/mixed-image", text = "图片")
            rule.runOnUiThread {
                assertEquals(2, state.blocks.size)
                state.insert(image)
                assertEquals(listOf("text", "text", "image"), state.blocks.map { it.type })
                assertEquals("第二段", state.blocks[1].text)
            }
            rule.onNodeWithContentDescription("新段落").performClick()
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(3)
            rule.runOnUiThread { assertEquals(listOf("text", "text", "image", "text"), state.blocks.map { it.type }) }
        } finally { cleanup(model, note) }
    }
    @Test fun directVoiceCollectionHasNoEmptyParagraphAndDoesNotDuplicateOnRetry() {
        val model = model(); val note = fixture(model); seed(model, note)
        lateinit var state: DiaryMomentComposerState
        val context = rule.activity
        val audio = File(context.filesDir, "assets/composer-voice-${UUID.randomUUID()}.m4a").apply { parentFile!!.mkdirs(); writeText("voice payload") }
        try {
            rule.runOnUiThread { state = DiaryMomentComposerState(model, note.day!!) { throw AssertionError(it) } }
            runBlocking { state.recordOwner = model.registerDiaryRecording(state.moment.asNote(state.parent())) }
            val prefs = context.getSharedPreferences("recording_inbox", android.content.Context.MODE_PRIVATE)
            val entry = org.json.JSONObject().put("owner", state.recordOwner).put("path", audio.absolutePath).put("finished", true).put("duration", 900)
            prefs.edit().putString("items", org.json.JSONArray(prefs.getString("items", "[]")).put(entry).toString()).commit()
            runBlocking { state.collectAudio(context); state.collectAudio(context) }
            assertEquals(listOf("audio"), state.blocks.map { it.type })
            rule.activity.setContent { YouthTheme { DiaryMomentComposer(state, androidx.compose.ui.unit.Dp(500f), {}, {}, {}, { throw AssertionError(it) }) } }
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            rule.onNodeWithText("留下这一刻…").assertDoesNotExist()
            runBlocking { state.publish(context) }
            assertEquals(listOf("audio"), model.currentDiary(note.day!!).diaryMoments().single().blocks().map { it.type })
        } finally {
            RecordingService.removeInbox(context, audio.absolutePath)
            cleanup(model, note); audio.delete()
        }
    }
    @Test fun collapsedComposerHasOnlyActionsAndCheckmarkRetainsUnsentDraft() {
        val model = model(); val note = fixture(model); seed(model, note)
        try {
            rule.activity.setContent { YouthTheme { DiaryRoadScreen(note, model, {}, {}, { _, _ -> }) } }
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            listOf("键盘", "语音", "发生时间", "新增标签", "暂存", "添加素材", "发送片段").forEach {
                rule.onNodeWithContentDescription(it).assertExists()
            }
            rule.onNodeWithContentDescription("键盘").performClick()
            rule.onNode(hasSetTextAction()).performTextInput("完成只是暂存")
            File(rule.activity.getExternalFilesDir(null), "diary-composer-edit-preview.png").outputStream().use {
                rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            rule.onNodeWithContentDescription("完成片段编辑").performClick()
            rule.waitUntil(5_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
            assertTrue(model.currentDiary(note.day!!).diaryMoments().isEmpty())
            val draft = model.currentDiary(note.day!!).diaryInboxItems().single().moment!!
            assertEquals("完成只是暂存", draft.text)
            assertNull(draft.sentAt)
        } finally { cleanup(model, note) }
    }
    @Test fun publishedInlineEditIsDraftUntilCheckmarkAndPreservesSendingTime() {
        val model = model()
        val old = DiaryMoment(createdAt = 100, text = "已发布原文", sentAt = 200, occurredAt = 150)
        val note = fixture(model, old); seed(model, note)
        try {
            rule.activity.setContent { YouthTheme { DiaryRoadScreen(note, model, {}, {}, { _, _ -> }) } }
            rule.onNodeWithText(old.text).performScrollTo().performClick()
            rule.waitUntil(5_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasSetTextAction()).performTextReplacement("修改中的新文")
            assertEquals(old.text, model.currentDiary(note.day!!).diaryMoments().single().text)
            val edit = model.currentDiary(note.day!!).diaryInboxItems().single()
            assertEquals("editing", edit.originalStatus)
            assertEquals(old.id, edit.moment!!.id)
            assertEquals("修改中的新文", edit.moment!!.text)
            rule.onNodeWithContentDescription("发送片段").assertIsNotEnabled()
            rule.onNodeWithContentDescription("完成片段编辑").performClick()
            rule.waitUntil(5_000) { model.currentDiary(note.day!!).diaryMoments().single().text == "修改中的新文" }
            val saved = model.currentDiary(note.day!!).diaryMoments().single()
            assertEquals(old.id, saved.id); assertEquals(old.sentAt, saved.sentAt)
            assertEquals(old.createdAt, saved.createdAt); assertEquals(old.occurredAt, saved.occurredAt)
            assertEquals("结语保留", model.currentDiary(note.day!!).text)
            assertTrue(model.currentDiary(note.day!!).diaryInboxItems().isEmpty())
        } finally { cleanup(model, note) }
    }
    @Test fun stashStartsBlankCaptureAndBackDiscardMovesOnlyDraftToDeleted() {
        val model = model(); val note = fixture(model); seed(model, note)
        var returned = false
        try {
            rule.activity.setContent { YouthTheme { DiaryRoadScreen(note, model, { returned = true }, {}, { _, _ -> }) } }
            rule.onNodeWithContentDescription("键盘").performClick()
            rule.onNode(hasSetTextAction()).performTextInput("先收纳的片段")
            rule.onNodeWithContentDescription("暂存").performClick()
            rule.waitUntil(5_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithContentDescription("键盘").performClick()
            rule.onNode(hasSetTextAction()).performTextInput("决定丢弃的片段")
            // First Back hides IME; the next Back presents the explicit choice.
            rule.onNodeWithContentDescription("返回").performClick()
            rule.waitForIdle()
            if (rule.onAllNodesWithText("片段尚未发送").fetchSemanticsNodes().isEmpty()) {
                rule.waitUntil(3_000) { !rule.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
                rule.onNodeWithContentDescription("返回").performClick()
            }
            rule.onNodeWithText("片段尚未发送").assertExists()
            rule.onNodeWithText("丢弃", substring = false).performClick()
            rule.waitUntil(5_000) { returned }
            val items = model.currentDiary(note.day!!).diaryInboxItems()
            assertEquals("draft", items.single { it.moment?.text == "先收纳的片段" }.status)
            assertEquals("deleted", items.single { it.moment?.text == "决定丢弃的片段" }.status)
            assertTrue(model.currentDiary(note.day!!).diaryMoments().isEmpty())
        } finally { cleanup(model, note) }
    }
    @Test fun highlightAppliesOnlyNewTypingAndTagsUndoWithoutFlatteningMedia() {
        val model = model(); val note = fixture(model); seed(model, note)
        lateinit var state: DiaryMomentComposerState
        try {
            rule.runOnUiThread {
                state = DiaryMomentComposerState(model, note.day!!) { throw AssertionError(it) }
                val text = NoteBlock(text = "原有文字")
                val image = NoteBlock(type = "image", uri = "content://composer/image", text = "图片")
                val audio = NoteBlock(type = "audio", uri = "content://composer/audio", text = "录音")
                state.setBlocks(listOf(text, image, audio))
                state.active = text.id; state.selections[text.id] = TextRange(text.text.length)
                state.typingStyles = setOf("h")
                state.updateBlock(text.editText("原有文字新输入", state.typingStyles))
                assertEquals(image, state.blocks[1]); assertEquals(audio, state.blocks[2])
                val marked = state.blocks[0].richText()
                assertTrue(marked.spanStyles.any { it.start == 4 && it.end == 7 })
                state.change(state.moment.copy(tags = "生活 随想"))
                state.undo()
                assertEquals("", state.moment.tags)
                state.redo()
                assertEquals("生活 随想", state.moment.tags)
            }
            runBlocking { state.stage(rule.activity, clear = true) }
            val stored = model.currentDiary(note.day!!).diaryInboxItems().single().moment!!
            assertEquals("生活 随想", stored.tags)
            assertEquals(listOf("text", "image", "audio"), stored.blocks().map { it.type })
            assertTrue(model.currentDiary(note.day!!).diaryMoments().isEmpty())
        } finally { cleanup(model, note) }
    }
    @Test fun timeOnlyDraftCanBeStagedButCannotBePublished() {
        val model = model(); val note = fixture(model); seed(model, note)
        lateinit var state: DiaryMomentComposerState
        try {
            rule.runOnUiThread {
                state = DiaryMomentComposerState(model, note.day!!) { throw AssertionError(it) }
                state.change(state.moment.copy(occurredAt = 123456789))
            }
            assertFalse(state.hasContent)
            runBlocking { state.stage(rule.activity, clear = true) }
            val saved = model.currentDiary(note.day!!).diaryInboxItems().single().moment!!
            assertEquals(123456789L, saved.occurredAt)
            assertNull(saved.sentAt)
            assertTrue(model.currentDiary(note.day!!).diaryMoments().isEmpty())
        } finally { cleanup(model, note) }
    }
    @Test fun failedAutosaveKeepsLocalInputAcrossReconciliationUntilRetrySucceeds() {
        val model = model(); val note = fixture(model); seed(model, note)
        val replacing = WorkspaceModel::class.java.getDeclaredField("replacingWorkspace").apply { isAccessible = true }.get(model) as AtomicBoolean
        lateinit var state: DiaryMomentComposerState
        var failure: String? = null
        try {
            rule.runOnUiThread {
                state = DiaryMomentComposerState(model, note.day!!) { failure = it }
                replacing.set(true)
                state.setBlocks(listOf(NoteBlock(text = "未成功保存的输入")))
                assertTrue(state.lastPersistFailed)
                assertNotNull(failure)
                assertTrue(model.currentDiary(note.day!!).diaryInboxItems().isEmpty())
                state.reconcile()
                assertEquals("未成功保存的输入", state.moment.text)
                val restored = DiaryMomentComposerState(model, note.day!!) { failure = it }
                restored.restore(jsonObject(state.moment).toString(), false, true, null, failed = true)
                restored.reconcile()
                assertEquals("未成功保存的输入", restored.moment.text)
                replacing.set(false)
            }
            runBlocking { state.stage(rule.activity, clear = true) }
            assertEquals("未成功保存的输入", model.currentDiary(note.day!!).diaryInboxItems().single().moment!!.text)
            assertTrue(model.currentDiary(note.day!!).diaryMoments().isEmpty())
        } finally { replacing.set(false); cleanup(model, note) }
    }
    @Test fun tagOnlyDraftIsRecognizableInBothInboxAndRoadPreview() {
        val model = model(); val note = fixture(model); seed(model, note)
        val draft = DiaryMoment(tags = "生活 晨间")
        try {
            model.saveDiaryDraft(note, draft)
            rule.activity.setContent { YouthTheme { DiaryInboxScreen(model, note.day, {}, {}) } }
            rule.onNodeWithText("#生活").assertExists()
            rule.onNodeWithText("#晨间").assertExists()
            runBlocking { model.publishDiaryMoment(note, draft) }
            rule.activity.setContent { YouthTheme { DiaryRoadScreen(note, model, {}, {}, { _, _ -> }) } }
            rule.onNodeWithText("#生活").performScrollTo().assertExists()
            rule.onNodeWithText("#晨间").assertExists()
        } finally { cleanup(model, note) }
    }
    @Test fun publishedCardPreservesInlineBoldItalicAndHighlightSpans() {
        val model = model()
        val text = NoteBlock(text = "加粗斜体高亮展示").toggleMark(0, 2, "b").toggleMark(2, 4, "i").toggleMark(4, 6, "h")
        val moment = DiaryMoment(text = text.text, document = encodeBlocks(listOf(text)), sentAt = 100, occurredAt = 100)
        val note = fixture(model, moment); seed(model, note)
        try {
            rule.activity.setContent { YouthTheme { DiaryRoadScreen(note, model, {}, {}, { _, _ -> }) } }
            val node = rule.onNodeWithText(text.text).performScrollTo().fetchSemanticsNode()
            val rendered = node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
            assertTrue(rendered.spanStyles.any { it.start == 0 && it.end == 2 && it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.SemiBold })
            assertTrue(rendered.spanStyles.any { it.start == 2 && it.end == 4 && it.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic })
            assertTrue(rendered.spanStyles.any { it.start == 4 && it.end == 6 && it.item.background != androidx.compose.ui.graphics.Color.Unspecified })
        } finally { cleanup(model, note) }
    }
    @Test fun backgroundingFlushesUnsentTextForANewWorkspaceModel() {
        val model = model(); val note = fixture(model); seed(model, note)
        try {
            rule.activity.setContent { YouthTheme { DiaryRoadScreen(note, model, {}, {}, { _, _ -> }) } }
            rule.onNodeWithContentDescription("键盘").performClick()
            rule.onNode(hasSetTextAction()).performTextInput("切到后台也要完整留在收纳箱")
            rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            rule.waitUntil(5_000) {
                runBlocking { model.notes.node(note.id)?.diaryInboxItems()?.singleOrNull()?.moment?.text == "切到后台也要完整留在收纳箱" }
            }
            val reopened = model()
            runBlocking { reopened.nodes.first { nodes -> nodes.any { it.id == note.id } } }
            val recovered = reopened.currentDiary(note.day!!)
            assertEquals("切到后台也要完整留在收纳箱", recovered.diaryInboxItems().single().moment!!.text)
            assertNull(recovered.diaryInboxItems().single().moment!!.sentAt)
            assertTrue(recovered.diaryMoments().isEmpty())
        } finally {
            rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            cleanup(model, note)
        }
    }
}
