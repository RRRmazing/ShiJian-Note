package com.shijiannote.app

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.shijiannote.app.data.NoteNode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.io.File
import java.util.UUID

class DiaryRoadInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun model(): WorkspaceModel {
        lateinit var value: WorkspaceModel
        rule.runOnUiThread { value = WorkspaceModel(rule.activity.application as Application) }
        runBlocking { value.spacesReady.first { it } }
        return value
    }

    // Use an unoccupied historical date and unique fixture identity; never replace existing diary data.
    private fun fixture(model: WorkspaceModel): NoteNode = runBlocking {
        val occupied = model.notes.nodes().mapNotNull { it.day }.toSet()
        val date = generateSequence(LocalDate.of(1990, 1, 1)) { it.plusDays(1) }.first { dayMillis(it) !in occupied }
        NoteNode(id = "ui-road-${UUID.randomUUID()}", kind = "diary", day = dayMillis(date), title = "小路交互测试")
    }

    private fun seed(model: WorkspaceModel, note: NoteNode) = runBlocking {
        model.notes.put(note)
        model.nodes.first { entries -> entries.any { it.id == note.id } }
    }

    private fun cleanup(model: WorkspaceModel, note: NoteNode) = runBlocking {
        rule.runOnUiThread { rule.activity.setContent { YouthTheme {} } }
        model.flush(note.id)
        model.notes.remove(listOf(note.id))
        model.notes.removeVersions(listOf(note.id))
    }

    @Test fun quickSendAddsAMomentAndBothViewsKeepTheSameDatedRecord() {
        val model = model()
        val note = fixture(model).copy(text = "完整结语保留")
        seed(model, note)
        var summary: NoteNode? = null
        var openedMoment: NoteNode? = null
        try {
            rule.activity.setContent { YouthTheme {
                DiaryRoadScreen(note, model, onBack = {}, onSummary = { summary = it },
                    onMoment = { moment, _ -> openedMoment = moment })
            } }
            rule.onNode(hasSetTextAction()).performTextInput("路上想到的一句话")
            rule.onNodeWithContentDescription("保存片段").performClick()
            rule.waitUntil(5_000) { model.currentDiary(note.day!!).diaryMoments().size == 1 }
            rule.onNodeWithText("路上想到的一句话").performScrollTo().assertExists()
            rule.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            rule.onNodeWithContentDescription("这一天的结语").performClick()
            assertEquals(note.id, summary?.id)
            assertEquals("完整结语保留", summary?.text)
            assertEquals(1, summary?.diaryMoments()?.size)
            rule.onNodeWithText("路上想到的一句话").performClick()
            assertEquals("diary_moment", openedMoment?.kind)
            assertEquals(note.id, openedMoment?.parentId)
            assertEquals(note.day, openedMoment?.day)
            runBlocking { model.flush(note.id) }
            val saved = runBlocking { model.notes.node(note.id) }!!
            assertEquals("完整结语保留", saved.text)
            assertEquals("路上想到的一句话", saved.diaryMoments().single().text)
        } finally { cleanup(model, note) }
    }

    @Test fun deletingAMomentRequiresConfirmationAndPreservesTheConclusion() {
        val model = model()
        val note = fixture(model).copy(text = "删除片段不会删掉结语",
            diaryRoad = encodeDiaryMoments(listOf(DiaryMoment(text = "待删除的片段"))))
        seed(model, note)
        try {
            rule.activity.setContent { YouthTheme { DiaryRoadScreen(note, model, {}, {}, { _, _ -> }) } }
            rule.onNodeWithText("待删除的片段").performScrollTo()
            rule.onNodeWithContentDescription("片段操作").performClick()
            rule.onNodeWithText("删除片段").performClick()
            rule.onNodeWithText("将这个片段移入收纳箱？").assertExists()
            rule.onNodeWithText("取消").performClick()
            assertEquals(1, model.currentDiary(note.day!!).diaryMoments().size)
            rule.onNodeWithContentDescription("片段操作").performClick()
            rule.onNodeWithText("删除片段").performClick()
            rule.onNodeWithText("移入收纳箱", substring = false).performClick()
            rule.waitUntil(5_000) { model.currentDiary(note.day!!).diaryMoments().isEmpty() }
            assertEquals("删除片段不会删掉结语", model.currentDiary(note.day!!).text)
            assertEquals("deleted", model.currentDiary(note.day!!).diaryInboxItems().single().status)
            rule.onNodeWithText("待删除的片段").assertDoesNotExist()
        } finally { cleanup(model, note) }
    }

    @Test fun recallLightsTheNextMomentAndCanExitAtAnyTime() {
        val model = model()
        val note = fixture(model).copy(diaryRoad = encodeDiaryMoments(listOf(
            DiaryMoment(createdAt = 1, text = "第一处"), DiaryMoment(createdAt = 2, text = "第二处"))))
        seed(model, note)
        try {
            rule.activity.setContent { YouthTheme { DiaryRoadScreen(note, model, {}, {}, { _, _ -> }) } }
            rule.onNodeWithContentDescription("暗背景回溯").performClick()
            rule.onNodeWithText("已走过 0 / 2 处").assertExists()
            rule.onNodeWithText("下一处").performClick()
            rule.onNodeWithText("已走过 1 / 2 处").assertExists()
            rule.onNodeWithText("退出回溯").performClick()
            rule.onNodeWithText("下一处").assertDoesNotExist()
            rule.onNodeWithContentDescription("保存片段").assertExists()
            assertEquals(2, model.currentDiary(note.day!!).diaryMoments().size)
        } finally { cleanup(model, note) }
    }

    @Test fun historicalConclusionOnlyEntryCanOpenAndSupplementItsRoad() {
        val model = model()
        val note = fixture(model).copy(title = "仅结语日记路由-${UUID.randomUUID()}", text = "历史结语")
        seed(model, note)
        val preferences = appPreferences(rule.activity)
        val wasCalendar = preferences.getBoolean("diaryCalendar", false)
        preferences.edit().putBoolean("diaryCalendar", false).commit()
        try {
            rule.activity.setContent { ModernShiJianApp() }
            rule.onNodeWithText("日记").performClick()
            rule.onNodeWithText(note.title).performScrollTo().performClick()
            rule.onNodeWithContentDescription("保存并返回").assertExists()
            rule.onNodeWithText("历史结语").assertExists()
            rule.onNodeWithContentDescription("日记设置").performClick()
            rule.onNodeWithText("显示‘经历小路’").assertExists()
            rule.onNode(isToggleable()).performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithText("这一天的小路").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("这一天的小路").assertExists()
            rule.onNodeWithContentDescription("这一天的结语").assertExists()
            rule.onNodeWithContentDescription("这一天的结语").performClick()
            rule.onNodeWithText("历史结语").assertExists()
        } finally {
            cleanup(model, note)
            preferences.edit().putBoolean("diaryCalendar", wasCalendar).commit()
        }
    }

    @Test fun previewThreeMomentsWithDifferentTextLengths() {
        val model = model()
        val note = fixture(model).copy(text = "今天也留下了一点值得记住的东西。", diaryRoad = encodeDiaryMoments(listOf(
            DiaryMoment(createdAt = 1770004800000, text = "路边的花开了。走慢一点，才发现春天已经到了。"),
            DiaryMoment(createdAt = 1770008400000, text = "午后泡了一杯茶，听窗外的鸟叫。"),
            DiaryMoment(createdAt = 1770012000000, title = "一段慢慢展开的感想", text = "今天忽然想起，日子并不总是需要一个宏大的总结。\n有时只是走过一段小路，看到一棵树，或者记住一句刚刚听到的话。\n把这些当时的感受放在这里，以后再沿着路走回来，也许就能想起当时的自己。\n这样的记录可以很短，也可以写得久一点。"))))
        seed(model, note)
        try {
            rule.activity.setContent { YouthTheme { DiaryRoadScreen(note, model, {}, {}, { _, _ -> }) } }
            rule.onNodeWithText("从这里出发").assertExists()
            rule.waitForIdle()
            File(rule.activity.getExternalFilesDir(null), "diary-road-preview.png").outputStream().use {
                rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            rule.onNodeWithText("一段慢慢展开的感想").performScrollTo().assertExists()
            File(rule.activity.getExternalFilesDir(null), "diary-road-detail-preview.png").outputStream().use {
                rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { cleanup(model, note) }
    }

    @Test fun roadOnlyDiaryOpensAnEditableConclusionAndReturnsToTheRoad() {
        val model = model()
        val moment = DiaryMoment(text = "只有小路的日记-${UUID.randomUUID()}")
        val note = fixture(model).copy(title = "", diaryRoad = encodeDiaryMoments(listOf(moment)))
        seed(model, note)
        val preferences = appPreferences(rule.activity)
        val wasCalendar = preferences.getBoolean("diaryCalendar", false)
        preferences.edit().putBoolean("diaryCalendar", false).commit()
        try {
            rule.activity.setContent { ModernShiJianApp() }
            rule.onNodeWithText("日记").performClick()
            rule.onNodeWithText(moment.text).performScrollTo().performClick()
            rule.onNodeWithContentDescription("这一天的结语").performClick()
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(2)
            rule.onNodeWithContentDescription("保存并返回").performClick()
            rule.onNodeWithText("这一天的小路").assertExists()
            rule.onNodeWithText(moment.text).assertExists()
            runBlocking { model.flush(note.id) }
            assertEquals(listOf(moment), runBlocking { model.notes.node(note.id) }!!.diaryMoments())
        } finally {
            cleanup(model, note)
            preferences.edit().putBoolean("diaryCalendar", wasCalendar).commit()
        }
    }

    @Test fun typingAutosavesToInboxAndStagingStartsAnIndependentCapture() {
        val model = model()
        val note = fixture(model).copy(title = "", text = "结语不变")
        seed(model, note)
        try {
            rule.activity.setContent { YouthTheme { DiaryRoadScreen(note, model, {}, {}, { _, _ -> }) } }
            rule.onNode(hasSetTextAction()).performTextInput("第一段先暂存")
            rule.waitUntil(5_000) { model.currentDiary(note.day!!).diaryInboxItems().size == 1 }
            assertTrue(model.currentDiary(note.day!!).diaryMoments().isEmpty())
            assertNull(model.currentDiary(note.day!!).diaryInboxItems().single().moment!!.sentAt)
            rule.onNodeWithText("暂存下一条").performClick()
            rule.onNode(hasSetTextAction()).performTextInput("第二段继续写")
            rule.waitUntil(5_000) { model.currentDiary(note.day!!).diaryInboxItems().size == 2 }
            val captures = model.currentDiary(note.day!!).diaryInboxItems()
            assertEquals(setOf("第一段先暂存", "第二段继续写"), captures.map { it.moment!!.text }.toSet())
            assertEquals(2, captures.map { it.moment!!.id }.distinct().size)
            assertEquals("结语不变", model.currentDiary(note.day!!).text)
        } finally { cleanup(model, note) }
    }

    @Test fun cancellingHistoricalRoadArchivesWholeRoadAndPreservesText() {
        val model = model()
        val moment = DiaryMoment(text = "整条路中的片段", sentAt = 1_000, occurredAt = 900)
        val note = fixture(model).copy(text = "正文一直保留", diaryRoadEnabled = true, diaryRoadLayout = "right",
            diaryRoadTheme = "winter", diaryRoad = encodeDiaryMoments(listOf(moment)))
        seed(model, note)
        try {
            rule.activity.setContent { YouthTheme { DiaryRoadScreen(note, model, {}, {}, { _, _ -> }) } }
            rule.onNodeWithContentDescription("小路设置").performClick()
            rule.onNode(isToggleable()).performClick()
            rule.onNodeWithText("将整条小路移入收纳箱？").assertExists()
            rule.onNodeWithText("保留小路").performClick()
            assertTrue(model.currentDiary(note.day!!).diaryRoadEnabled)
            assertEquals(1, model.currentDiary(note.day!!).diaryMoments().size)
            rule.onNode(isToggleable()).performClick()
            rule.onNodeWithText("移入收纳箱", substring = false).performClick()
            rule.waitUntil(5_000) { !model.currentDiary(note.day!!).diaryRoadEnabled }
            val archived = model.currentDiary(note.day!!)
            assertEquals("正文一直保留", archived.text)
            assertTrue(archived.diaryMoments().isEmpty())
            assertEquals("road", archived.diaryInboxItems().single().status)
            rule.activity.setContent { YouthTheme { DiaryInboxScreen(model, note.day, {}, {}) } }
            rule.onNodeWithText("恢复小路").performClick()
            rule.waitUntil(5_000) { model.currentDiary(note.day!!).diaryRoadEnabled }
            val restored = model.currentDiary(note.day!!)
            assertEquals(listOf(moment), restored.diaryMoments())
            assertEquals("right", restored.diaryRoadLayout)
            assertEquals("winter", restored.diaryRoadTheme)
            assertEquals("正文一直保留", restored.text)
        } finally { cleanup(model, note) }
    }

    @Test fun fullDateTimePickerIncludesSecondsWithoutChangingDate() {
        val initial = java.time.LocalDateTime.of(2026, 10, 5, 14, 15, 16).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        var selected: Long? = null
        rule.activity.setContent { YouthTheme { DiaryDateTimePicker(initial, {}, { selected = it }) } }
        rule.onAllNodes(hasSetTextAction())[3].performTextReplacement("37")
        rule.onNodeWithText("使用这个时间").performClick()
        val local = java.time.Instant.ofEpochMilli(selected!!).atZone(java.time.ZoneId.systemDefault())
        assertEquals(LocalDate.of(2026, 10, 5), local.toLocalDate())
        assertEquals(14, local.hour)
        assertEquals(15, local.minute)
        assertEquals(37, local.second)
    }

    @Test fun quickComposerDoesNotFlattenOrOverwriteAnExistingRichDraft() {
        val model = model()
        val note = fixture(model)
        seed(model, note)
        val rich = DiaryMoment(text = "文字和图片都要保留", document = encodeBlocks(listOf(
            NoteBlock(text = "图片之前"), NoteBlock(type = "image", text = "照片", uri = "content://fixture/photo"), NoteBlock(text = "图片之后"))))
        model.saveDiaryDraft(note, rich)
        val prefs = appPreferences(rule.activity)
        val key = "diary_quick_draft_${note.day}"
        val previous = prefs.getString(key, null)
        prefs.edit().putString(key, rich.id).commit()
        try {
            rule.activity.setContent { YouthTheme { DiaryRoadScreen(note, model, {}, {}, { _, _ -> }) } }
            rule.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            rule.onNode(hasSetTextAction()).performTextInput("新的简单片段")
            rule.onNodeWithContentDescription("返回").performClick()
            val saved = model.currentDiary(note.day!!).diaryInboxItems()
            assertEquals(2, saved.size)
            assertEquals(rich.document, saved.single { it.moment?.id == rich.id }.moment!!.document)
            assertTrue(saved.any { it.moment?.text == "新的简单片段" })
        } finally {
            cleanup(model, note)
            prefs.edit().apply { if (previous == null) remove(key) else putString(key, previous) }.commit()
        }
    }

    @Test fun returningAfterInboxPublicationDoesNotReviveTheOldComposerSnapshot() {
        val model = model()
        val note = fixture(model)
        seed(model, note)
        val shown = mutableStateOf(true)
        try {
            rule.activity.setContent { YouthTheme {
                val holder = rememberSaveableStateHolder()
                if (shown.value) holder.SaveableStateProvider("test-road") { DiaryRoadScreen(note, model, {}, {}, { _, _ -> }) }
            } }
            rule.onNode(hasSetTextAction()).performTextInput("这段在收纳箱发送")
            rule.waitUntil(5_000) { model.currentDiary(note.day!!).diaryInboxItems().size == 1 }
            val staged = model.currentDiary(note.day!!).diaryInboxItems().single().moment!!
            rule.runOnUiThread { shown.value = false }
            rule.waitForIdle()
            runBlocking { model.publishDiaryMoment(note, staged) }
            rule.runOnUiThread { shown.value = true }
            rule.waitForIdle()
            rule.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            rule.onNodeWithContentDescription("返回").performClick()
            assertEquals(1, model.currentDiary(note.day!!).diaryMoments().size)
            assertTrue(model.currentDiary(note.day!!).diaryInboxItems().isEmpty())
        } finally { cleanup(model, note) }
    }
}
