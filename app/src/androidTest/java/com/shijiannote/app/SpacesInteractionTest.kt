package com.shijiannote.app

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.shijiannote.app.data.NoteNode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SpacesInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun model(): WorkspaceModel {
        lateinit var value: WorkspaceModel
        rule.runOnUiThread { value = WorkspaceModel(rule.activity.application as Application) }
        runBlocking { value.spacesReady.first { it } }
        return value
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/shijian-regression-$name.png").let { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    @Test fun diaryTagsAreLocalAndBlankTitleStaysUnnamed(): Unit = runBlocking {
        val model = model()
        val diary = NoteNode(id = "ui-tags-diary", kind = "diary", day = dayMillis(), text = "标签正文保持")
        val other = NoteNode(id = "ui-tags-other", parentId = MemorySpaces.WORK_ID, title = "已有标签记录", tags = "复用", text = "其他正文")
        model.notes.putAll(listOf(diary, other))
        try {
            rule.activity.setContent { YouthTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) { RichNoteEditor(diary, model, true, onBack = {}, onOpen = {}, onExport = {}) } } }
            rule.onNodeWithText("未命名").assertExists()
            rule.onAllNodes(hasSetTextAction())[0].performClick()
            rule.onNodeWithText("未命名").assertDoesNotExist()
            rule.onNodeWithText("＋ 添加标签").performClick()
            rule.onNodeWithText("#复用").performClick()
            rule.onNodeWithText("#复用").assertExists()
            rule.onNodeWithText("＋ 添加标签").performClick()
            rule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("新标签")
            rule.onNodeWithText("添加", substring = false).performClick()
            rule.onNodeWithText("#新标签").assertExists()
            rule.onNodeWithText("#新标签").performClick()
            rule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("改名")
            rule.onNodeWithText("完成", substring = false).performClick()
            rule.onNodeWithText("#改名").assertExists()
            rule.onNodeWithContentDescription("从当前记录移除标签 复用").performClick()
            rule.onNodeWithText("#复用").assertDoesNotExist()
            screenshot("dark-tags-editor")
            rule.onNodeWithContentDescription("保存并返回").performClick()
            model.flush(diary.id)
            val saved = model.notes.node(diary.id)!!
            assertEquals("改名", saved.tags)
            assertEquals(diary.text, saved.text)
            assertEquals("未命名", saved.displayTitle())
            assertEquals("复用", model.notes.node(other.id)!!.tags)
        } finally { model.flushAll(); model.notes.remove(listOf(diary.id, other.id)); model.notes.removeVersions(listOf(diary.id, other.id)) }
    }

    @Test fun editorMovePersistsLatestLocationAfterEditingAndLeaving(): Unit = runBlocking {
        val model = model()
        val note = NoteNode(id = "ui-editor-move", parentId = MemorySpaces.WORK_ID, title = "正文内移动", text = "保存这篇正文")
        model.notes.put(note)
        var left = false
        try {
            rule.activity.setContent { YouthTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) { RichNoteEditor(note, model, false, onBack = { left = true }, onOpen = {}, onExport = {}) } } }
            rule.onNodeWithContentDescription("更多").performClick()
            rule.onNodeWithText("移动到").performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithText("生活").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("生活").performClick()
            rule.onNodeWithText("移动到这里").performClick()
            rule.waitUntil(5_000) { runBlocking { model.notes.node(note.id)!!.parentId == MemorySpaces.LIFE_ID } }
            rule.waitUntil(5_000) { rule.onAllNodesWithText("移动到这里").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithContentDescription("编辑").performClick()
            rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("移动后的标题")
            rule.onNodeWithContentDescription("保存并返回").performClick()
            try { rule.waitUntil(5_000) { left } } catch (failure: Throwable) {
                screenshot("editor-move-failure")
                throw AssertionError(rule.onAllNodes(isRoot()).printToString(), failure)
            }
            assertEquals(MemorySpaces.LIFE_ID, model.notes.node(note.id)!!.parentId)
            assertEquals("移动后的标题", model.notes.node(note.id)!!.title)
        } finally { model.flushAll(); model.notes.remove(listOf(note.id)); model.notes.removeVersions(listOf(note.id)) }
    }

    @Test fun leavingTreeForParentClearsTreeOriginAndChildrenReturnNormally(): Unit = runBlocking {
        val model = model()
        val folder = NoteNode(id = "ui-tree-exit", kind = "folder", parentId = MemorySpaces.WORK_ID, title = "树返回入口")
        val child = NoteNode(id = "ui-tree-exit-child", kind = "folder", parentId = folder.id, title = "入口下的分类")
        model.notes.putAll(listOf(folder, child))
        try {
            rule.activity.setContent { ModernShiJianApp() }
            rule.onNodeWithText("记忆").performClick()
            rule.onNodeWithContentDescription("新增").assertDoesNotExist()
            screenshot("memory-two-spaces")
            rule.onNodeWithContentDescription("更多").performClick()
            rule.onNodeWithText("结构树").performClick()
            rule.onNodeWithContentDescription("进入分类：${folder.title}").performClick()
            rule.onNodeWithText(child.title).performClick()
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithText("返回到哪里？").assertDoesNotExist()
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithText("返回到哪里？").assertExists()
            screenshot("tree-return-choice")
            rule.onNodeWithText("返回上级").performClick()
            rule.onNodeWithText("结构树").assertDoesNotExist()
            rule.onNodeWithText(folder.title).assertExists()
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithText("返回到哪里？").assertDoesNotExist()
            rule.onNodeWithContentDescription("新增").assertDoesNotExist()
            rule.onNodeWithText("生活").assertExists()
        } finally { model.notes.remove(listOf(folder.id, child.id)); model.notes.removeVersions(listOf(folder.id, child.id)) }
    }

    @Test fun trashHasModulePagesAndRestoresOnlyTheChosenSpace(): Unit = runBlocking {
        val model = model()
        val work = NoteNode(id = "ui-space-trash-work", parentId = MemorySpaces.WORK_ID, title = "工作删除记录", deletedAt = 1, deleteGroup = "ui-space-trash")
        val life = work.copy(id = "ui-space-trash-life", parentId = MemorySpaces.LIFE_ID, title = "生活删除记录")
        model.notes.putAll(listOf(work, life))
        try {
            rule.activity.setContent { ModernShiJianApp() }
            rule.onNodeWithText("设置").performClick()
            rule.onNodeWithText("回收站").performClick()
            listOf("时间表", "待办", "日记", "记忆").forEach { rule.onNodeWithText(it).assertExists() }
            screenshot("trash-modules")
            rule.onNodeWithText("记忆").performClick()
            screenshot("trash-memory-spaces")
            rule.onNodeWithText("工作").performClick()
            rule.onNodeWithText(work.title).assertExists()
            rule.onNodeWithText(life.title).assertDoesNotExist()
            rule.onNodeWithText("恢复").performClick()
            rule.waitUntil(5_000) { runBlocking { model.notes.node(work.id)!!.deletedAt == null } }
            assertNotNull(model.notes.node(life.id)!!.deletedAt)
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithText("生活").performClick()
            rule.onNodeWithText(life.title).assertExists()
            rule.onNodeWithText("永久删除").performClick()
            rule.onNode(hasText("永久删除") and hasAnyAncestor(isDialog()) and hasClickAction()).performClick()
            rule.waitUntil(5_000) { runBlocking { model.notes.node(life.id) == null } }
            assertNotNull(model.notes.node(work.id))
        } finally { model.notes.remove(listOf(work.id, life.id)); model.notes.removeVersions(listOf(work.id, life.id)) }
    }
}
