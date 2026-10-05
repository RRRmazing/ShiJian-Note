package com.shijiannote.app

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.shijiannote.app.data.NoteNode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** Exercise the rich editor boundary between an autosaved draft and a published road fragment. */
class DiaryEditorPublishInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun model(): WorkspaceModel {
        lateinit var model: WorkspaceModel
        rule.runOnUiThread { model = WorkspaceModel(rule.activity.application as Application) }
        runBlocking { model.spacesReady.first { it } }
        return model
    }

    private fun parent(model: WorkspaceModel): NoteNode = runBlocking {
        val occupied = model.notes.nodes().mapNotNull { it.day }.toSet()
        val date = generateSequence(LocalDate.of(1991, 1, 1)) { it.plusDays(1) }
            .first { dayMillis(it) !in occupied }
        NoteNode(id = "editor-diary-${UUID.randomUUID()}", kind = "diary", day = dayMillis(date))
            .also { model.notes.put(it); model.nodes.first { nodes -> nodes.any { n -> n.id == it.id } } }
    }

    private fun cleanup(model: WorkspaceModel, parent: NoteNode) = runBlocking {
        rule.runOnUiThread { rule.activity.setContent { YouthTheme {} } }
        model.flush(parent.id)
        model.notes.remove(listOf(parent.id))
        model.notes.removeVersions(listOf(parent.id))
    }

    @Test fun autosaveAndBackKeepRichMomentInInboxUntilExplicitSend() {
        val model = model()
        val parent = parent(model)
        val moment = DiaryMoment(text = "最初的草稿", document = encodeBlocks(listOf(NoteBlock(text = "最初的草稿"))))
        var left = false
        try {
            rule.activity.setContent { YouthTheme {
                RichNoteEditor(moment.asNote(parent), model, true, onBack = { left = true }, onOpen = {}, onExport = {})
            } }
            rule.onNode(hasSetTextAction() and hasText("最初的草稿")).performTextReplacement("修改后留在收纳箱")
            rule.waitUntil(5_000) { model.currentDiary(parent.day!!).diaryInboxItems().any { it.moment?.text == "修改后留在收纳箱" } }
            assertTrue(model.currentDiary(parent.day!!).diaryMoments().isEmpty())
            rule.onNodeWithContentDescription("保存并返回").performClick()
            rule.waitUntil(5_000) { left }
            runBlocking { model.flush(parent.id) }
            val saved = runBlocking { model.notes.node(parent.id) }!!
            assertTrue(saved.diaryMoments().isEmpty())
            assertEquals("修改后留在收纳箱", saved.diaryInboxItems().single().moment?.text)
        } finally { cleanup(model, parent) }
    }

    @Test fun richSendUsesSubmissionTimeRatherThanEditorCreationTime() {
        val model = model()
        val parent = parent(model)
        val moment = DiaryMoment(createdAt = 1_000L, text = "长时间编辑后发送",
            document = encodeBlocks(listOf(NoteBlock(text = "长时间编辑后发送"))))
        var left = false
        try {
            rule.activity.setContent { YouthTheme {
                RichNoteEditor(moment.asNote(parent), model, true, onBack = { left = true }, onOpen = {}, onExport = {})
            } }
            val before = System.currentTimeMillis()
            rule.onNodeWithText("发送", substring = false).performClick()
            rule.waitUntil(5_000) { left }
            runBlocking { model.flush(parent.id) }
            val saved = runBlocking { model.notes.node(parent.id) }!!
            val sent = saved.diaryMoments().single()
            assertTrue(sent.sentAt!! >= before)
            assertEquals(sent.sentAt, sent.occurredAt)
            assertEquals("长时间编辑后发送", sent.text)
            assertTrue(saved.diaryInboxItems().isEmpty())
            assertTrue(saved.diaryRoadEnabled)
        } finally { cleanup(model, parent) }
    }

    @Test fun customOccurrenceAndMixedBlocksSurviveRichPublishing() {
        val model = model()
        val parent = parent(model)
        val occurrence = parent.day!! + 9 * 60 * 60 * 1_000L + 15_000L
        val blocks = listOf(NoteBlock(text = "当时发生的事"), NoteBlock(type = "file", text = "关联材料", uri = "content://test/material", mime = "text/plain"))
        val moment = DiaryMoment(text = blockPlainText(blocks), document = encodeBlocks(blocks), occurredAt = occurrence)
        var left = false
        try {
            rule.activity.setContent { YouthTheme {
                RichNoteEditor(moment.asNote(parent), model, true, onBack = { left = true }, onOpen = {}, onExport = {})
            } }
            rule.onNodeWithText("发送", substring = false).performClick()
            rule.waitUntil(5_000) { left }
            val sent = model.currentDiary(parent.day!!).diaryMoments().single()
            assertEquals(occurrence, sent.occurredAt)
            assertTrue(sent.sentAt!! > occurrence)
            assertEquals(blocks, decodeBlocks(sent.document))
        } finally { cleanup(model, parent) }
    }

    @Test fun draftOnlyHistoryOpensBlankRoadAndSummaryUntilExplicitResume() {
        val model = model()
        val parent = parent(model)
        val draft = DiaryMoment(text = "仅在收纳箱中的秘密草稿", tags = "草稿标签")
        val preferences = appPreferences(rule.activity)
        val previousCalendar = preferences.getBoolean("diaryCalendar", false)
        try {
            preferences.edit().putBoolean("diaryCalendar", false).commit()
            model.saveDiaryDraft(parent, draft)
            runBlocking { model.flush(parent.id) }
            rule.activity.setContent { ModernShiJianApp() }
            rule.onNodeWithText("日记", substring = false).performClick()
            rule.onNodeWithTag("diary-row-${parent.id}").performScrollTo().performClick()
            rule.onNodeWithText(draft.text).assertDoesNotExist()
            rule.onNodeWithContentDescription("这一天的结语").performClick()
            rule.onNodeWithText(draft.text).assertDoesNotExist()
            assertFalse(model.currentDiary(parent.day!!).hasDiaryContent())
            assertEquals(draft, model.currentDiary(parent.day!!).diaryInboxItems().single().moment)
            rule.onNodeWithContentDescription("更多").performClick()
            rule.onNodeWithText("收纳箱").performClick()
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithContentDescription("完成编辑").assertExists()
            rule.onNodeWithText(draft.text).assertDoesNotExist()
            rule.onNodeWithContentDescription("更多").performClick()
            rule.onNodeWithText("收纳箱").performClick()
            rule.onNodeWithText("继续编辑").performScrollTo().performClick()
            rule.onNode(hasSetTextAction() and hasText(draft.text)).assertExists()
        } finally {
            preferences.edit().putBoolean("diaryCalendar", previousCalendar).commit()
            cleanup(model, parent)
        }
    }

    @Test fun summaryUsesSessionUndoAndKeepsOldTitleWithoutShowingTitleOrVersionMenu() {
        val model = model()
        val parent = parent(model)
        val original = parent.copy(title = "旧版本标题必须保留", text = "旧正文", document = encodeBlocks(listOf(NoteBlock(text = "旧正文"))))
        try {
            runBlocking { model.notes.put(original); model.nodes.first { nodes -> nodes.any { it.id == original.id && it.text == "旧正文" } } }
            rule.activity.setContent { YouthTheme { RichNoteEditor(original, model, true, onBack = {}, onOpen = {}, onExport = {}) } }
            rule.onNodeWithText(original.title).assertDoesNotExist()
            rule.onNode(hasSetTextAction() and hasText("旧正文")).performTextReplacement("本次编辑内容")
            rule.onNodeWithContentDescription("撤销").performClick()
            rule.onNode(hasSetTextAction() and hasText("旧正文")).assertExists()
            rule.onNodeWithContentDescription("重做").performClick()
            rule.onNode(hasSetTextAction() and hasText("本次编辑内容")).assertExists()
            rule.onNodeWithContentDescription("完成编辑").performClick()
            rule.onNodeWithText("本次编辑内容").assertExists()
            rule.onNodeWithContentDescription("更多").performClick()
            rule.onNodeWithText("标题目录").assertDoesNotExist()
            rule.onNodeWithText("历史版本").assertDoesNotExist()
            rule.onNodeWithText("查看小路").assertDoesNotExist()
            runBlocking { model.flush(parent.id) }
            assertEquals(original.title, model.currentDiary(parent.day!!).title)
            assertEquals("本次编辑内容", model.currentDiary(parent.day!!).text)
        } finally { cleanup(model, parent) }
    }

    @Test fun positionPreviewReturnsToTheSameRichDraftWithoutPublishing() {
        val model = model()
        val parent = parent(model)
        val first = DiaryMoment(text = "前一段", occurredAt = parent.day!! + 1_000, sentAt = 1_000)
        val last = DiaryMoment(text = "后一段", occurredAt = parent.day!! + 3_000, sentAt = 3_000)
        val blocks = listOf(NoteBlock(text = "编辑中的图文草稿"),
            NoteBlock(type = "file", text = "完整附件", uri = "content://test/preview", mime = "text/plain"))
        val draft = DiaryMoment(text = blockPlainText(blocks), document = encodeBlocks(blocks), occurredAt = parent.day!! + 2_000)
        val preferences = appPreferences(rule.activity)
        val previousSort = preferences.getString("diary_time_sort", null)
        try {
            preferences.edit().putString("diary_time_sort", "occurred").commit()
            runBlocking {
                model.notes.put(parent.copy(diaryRoadEnabled = true, diaryRoad = encodeDiaryMoments(listOf(first, last))))
                model.nodes.first { nodes -> nodes.any { it.id == parent.id && it.diaryMoments().size == 2 } }
            }
            model.saveDiaryDraft(model.currentDiary(parent.day!!), draft)
            runBlocking { model.flush(parent.id) }
            rule.activity.setContent { ModernShiJianApp() }
            rule.onNodeWithText("日记", substring = false).performClick()
            rule.onNodeWithTag("diary-row-${parent.id}").performScrollTo().performClick()
            rule.onNodeWithContentDescription("小路设置").performClick()
            rule.onNodeWithText("收纳箱").performClick()
            rule.onNodeWithText("继续编辑").performScrollTo().performClick()
            rule.onNodeWithContentDescription("发生时间").performClick()
            rule.onNodeWithText("在小路中查看").performScrollTo().performClick()
            rule.onNodeWithText("在小路中查看位置").assertExists()
            rule.onNodeWithText("前一条：", substring = true).assertExists()
            rule.onNodeWithText("后一条：", substring = true).assertExists()
            rule.onNodeWithText("继续编辑", substring = false).performClick()
            rule.onNode(hasSetTextAction() and hasText("编辑中的图文草稿")).performScrollTo().assertExists()
            val current = model.currentDiary(parent.day!!)
            assertEquals(setOf(first.id, last.id), current.diaryMoments().map { it.id }.toSet())
            assertEquals(blocks, decodeBlocks(current.diaryInboxItems().single().moment!!.document))
        } finally {
            preferences.edit().apply { if (previousSort == null) remove("diary_time_sort") else putString("diary_time_sort", previousSort) }.commit()
            cleanup(model, parent)
        }
    }
}
