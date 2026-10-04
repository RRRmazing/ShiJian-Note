package com.shijiannote.app

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import com.shijiannote.app.data.NoteNode
import com.shijiannote.app.data.NoteVersion
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import java.time.LocalDate

class ModuleSearchInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun model(): WorkspaceModel {
        lateinit var value: WorkspaceModel
        rule.runOnUiThread { value = WorkspaceModel(rule.activity.application as Application) }
        runBlocking { value.spacesReady.first { it } }
        return value
    }
    private fun show(model: WorkspaceModel, module: Int) {
        rule.activity.setContent { YouthTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            ModuleSearch(model, module, onClose = {}, onNote = { _, _ -> }, onFolder = {}, onSchedule = {}, onTodo = {})
        } } }
    }

    @Test fun partialDatesClearAndValidOpenEndedRangeSearchesWithoutKeyword(): Unit = runBlocking {
        val model = model()
        val node = NoteNode(id = "ui-date-search", kind = "diary", title = "日期范围唯一样本", day = dayMillis(LocalDate.of(2030, 10, 5)))
        model.notes.put(node)
        try {
            show(model, 2)
            rule.onNodeWithText("时间范围").performClick().assertIsOn()
            rule.onNodeWithContentDescription("开始年").performTextInput("2030")
            rule.onNodeWithContentDescription("开始月").performTextInput("10")
            rule.onNodeWithContentDescription("搜索关键词").performImeAction()
            rule.onNodeWithText("时间范围").assertIsOff()
            rule.onNodeWithContentDescription("开始年").assertDoesNotExist()
            rule.onNodeWithText("请输入关键词或完整日期").assertExists()
            rule.onNodeWithText("时间范围").performClick()
            assertEquals("", rule.onNodeWithContentDescription("开始年").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            rule.onNodeWithContentDescription("开始年").performTextInput("2030")
            rule.onNodeWithContentDescription("开始月").performTextInput("10")
            rule.onNodeWithContentDescription("开始日").performTextInput("5")
            rule.onNodeWithContentDescription("开始日").performImeAction()
            rule.onNodeWithText(node.title).performScrollTo().assertExists()
            rule.onNodeWithContentDescription("收起时间范围").performScrollTo().performClick()
            rule.onNodeWithText("时间范围").assertIsOn()
            rule.onNodeWithContentDescription("开始年").assertDoesNotExist()
            rule.onNodeWithText(node.title).assertExists()
        } finally { model.notes.remove(listOf(node.id)) }
    }

    @Test fun historyOnlyMatchesDisplayReadOnlySnapshotAndAttachmentNames(): Unit = runBlocking {
        val model = model()
        val owner = NoteNode(id = "ui-history-search", parentId = MemorySpaces.WORK_ID, title = "历史检索唯一样本", text = "现在的正文")
        val old = owner.copy(text = "过去的记录", document = encodeBlocks(listOf(NoteBlock(type = "file", text = "旧会议附件唯一关键词.pdf"))))
        model.notes.put(owner)
        model.notes.version(NoteVersion(nodeId = owner.id, snapshot = jsonObject(old).toString()))
        try {
            show(model, 3)
            rule.onNodeWithContentDescription("搜索关键词").performTextInput("旧会议附件唯一关键词")
            rule.onNodeWithContentDescription("搜索关键词").performImeAction()
            rule.onNodeWithText(owner.title).assertDoesNotExist()
            rule.onNodeWithText("包含历史与归档").performClick()
            rule.onNodeWithContentDescription("搜索关键词").performImeAction()
            rule.waitUntil(5_000) { rule.onAllNodesWithText(owner.title).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("查看匹配内容", substring = true).performClick()
            rule.onAllNodesWithText("旧会议附件唯一关键词.pdf", substring = true, useUnmergedTree = true).assertCountEquals(2)
        } finally { model.notes.removeVersions(listOf(owner.id)); model.notes.remove(listOf(owner.id)) }
    }

    @Test fun monthDayMatchesAcrossYearsAndIntersectsInclusiveRange(): Unit = runBlocking {
        val model = model()
        val old = NoteNode(id = "ui-monthday-old", kind = "diary", title = "月日检索旧年", day = dayMillis(LocalDate.of(2025, 10, 5)))
        val current = old.copy(id = "ui-monthday-current", title = "月日检索本年", day = dayMillis(LocalDate.of(2026, 10, 5)))
        val other = old.copy(id = "ui-monthday-other", title = "月日检索他日", day = dayMillis(LocalDate.of(2026, 10, 6)))
        model.notes.putAll(listOf(old, current, other))
        try {
            show(model, 2)
            rule.onNodeWithText("某一天").performClick()
            rule.onNodeWithContentDescription("日期月").performTextInput("10")
            rule.onNodeWithContentDescription("日期日").performTextInput("5")
            rule.onNodeWithContentDescription("日期日").performImeAction()
            rule.onNodeWithText(old.title).performScrollTo().assertExists()
            rule.onNodeWithText(current.title).performScrollTo().assertExists()
            rule.onNodeWithText(other.title).assertDoesNotExist()
            rule.onNodeWithText("时间范围").performScrollTo().performClick()
            rule.onNodeWithContentDescription("开始年").performTextInput("2026")
            rule.onNodeWithContentDescription("开始月").performTextInput("10")
            rule.onNodeWithContentDescription("开始日").performTextInput("5")
            rule.onNodeWithContentDescription("结束年").performTextInput("2026")
            rule.onNodeWithContentDescription("结束月").performTextInput("10")
            rule.onNodeWithContentDescription("结束日").performTextInput("5")
            rule.onNodeWithContentDescription("结束日").performImeAction()
            rule.onNodeWithText(current.title).performScrollTo().assertExists()
            rule.onNodeWithText(old.title).assertDoesNotExist()
            rule.onNodeWithText(other.title).assertDoesNotExist()
        } finally { model.notes.remove(listOf(old.id, current.id, other.id)) }
    }
}
