package com.shijiannote.app

import android.app.Application
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import com.shijiannote.app.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class InteractionRegressionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun model(): WorkspaceModel {
        lateinit var result: WorkspaceModel
        rule.runOnUiThread { result = WorkspaceModel(rule.activity.application as Application) }
        return result
    }
    private fun back() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }
    private fun waitForKeyboard(visible: Boolean = true) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val info = automation.serviceInfo
        val originalFlags = info.flags
        try {
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
            rule.waitUntil(8_000) { automation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } == visible }
            automation.waitForIdle(500, 3_000)
        } finally { info.flags = originalFlags; automation.serviceInfo = info }
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val safeName = name.replace(Regex("[^a-z0-9-]"), "")
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/shijian-regression-$safeName.png").let { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    @Test fun todoCancelKeepsDraftUntilUserExplicitlyDiscards() {
        var dismissed = false
        var saved = false
        rule.activity.setContent { YouthTheme { QuickTodoCreateDialog(onDismiss = { dismissed = true }, onSave = { _, _, _, _, _ -> saved = true }) } }
        rule.onAllNodes(hasSetTextAction())[1].performScrollTo().performClick().performTextInput("准备考试")
        waitForKeyboard()
        back()
        rule.onNodeWithText("放弃添加待办？").assertDoesNotExist()
        // Compose idleness does not wait for the system keyboard's closing animation.
        waitForKeyboard(visible = false)
        back()
        try {
            rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("放弃添加待办？").assertIsDisplayed() }.isSuccess }
        } catch (failure: Throwable) {
            screenshot("discard-failure")
            throw AssertionError(rule.onAllNodes(isRoot()).printToString(), failure)
        }
        screenshot("discard-confirmation")
        rule.onNodeWithText("放弃添加待办？").assertIsDisplayed()
        assertFalse(dismissed); assertFalse(saved)
        rule.onNodeWithText("继续编辑").performClick()
        rule.onNodeWithText("准备考试").assertExists()
        rule.onNodeWithText("取消").performClick()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("放弃添加待办？").assertIsDisplayed() }.isSuccess }
        rule.onNodeWithText("放弃").performClick()
        assertTrue(dismissed); assertFalse(saved)
    }

    @Test fun dailyExistingTaskGetsTheDisplayedDefaultDeadlineAndKeepsPartialInput(): Unit = runBlocking {
        val model = model()
        val day = dayMillis(LocalDate.now().plusDays(1))
        val boardId = model.dao.insertTodoBoard(TodoBoard(summary = "当天编辑测试", boardType = "DAILY", timeMode = "INDEPENDENT"))
        val id = model.dao.insertTodoItems(listOf(TodoItem(boardId = boardId, text = "原有事项", plannedDay = day))).single()
        var closed = false
        try {
            val item = model.dao.getTodoItems(boardId).single { it.id == id }
            val board = model.dao.getTodoBoard(boardId)!!
            rule.activity.setContent { YouthTheme { TaskDetails(item, boardId, day, model, onClose = { closed = true }, board = board) } }
            rule.onNode(hasText("时") and hasSetTextAction()).performTextClearance()
            back()
            rule.onNodeWithText("保存").performScrollTo().performClick()
            rule.onNodeWithText("请填写完整的截止时分").assertExists()
            assertFalse(closed)
            rule.onNode(hasText("时") and hasSetTextAction()).performScrollTo().performTextInput("23")
            back()
            rule.onNodeWithText("保存").performScrollTo().performClick()
            rule.waitUntil(timeoutMillis = 3_000) { closed }
            val updated = model.dao.getTodoItems(boardId).single { it.id == id }
            assertEquals(day, updated.plannedDay)
            assertEquals(day + 23 * 3_600_000L + 59 * 60_000L, updated.dueAt)
        } finally { model.dao.getTodoBoard(boardId)?.let { model.dao.deleteTodoBoard(it) } }
    }

    @Test fun dailyCreateUsesInlineHoursAndSavesOneItemWithoutBoardTitle(): Unit = runBlocking {
        val model = model()
        val day = dayMillis(LocalDate.now().plusDays(1))
        var closed = false
        try {
            rule.activity.setContent { YouthTheme { TaskDetails(null, 0, day, model, onClose = { closed = true }) } }
            rule.onNodeWithText("添加事项").assertExists()
            rule.onNodeWithText("未命名清单").assertDoesNotExist()
            rule.onNodeWithText("计划日期").assertDoesNotExist()
            rule.onNodeWithText("截止时间").assertExists()
            rule.onNodeWithText("设置截止时间为").assertDoesNotExist()
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(3)
            screenshot("daily-create")
            rule.onNode(hasText("事项内容") and hasSetTextAction()).performTextInput("明天复习独立事项")
            back()
            rule.onNodeWithText("保存").performScrollTo().performClick()
            rule.waitUntil(5_000) { closed }
            val group = model.dao.allTodos().single { it.items.any { item -> item.text == "明天复习独立事项" } }
            val saved = group.items.single { it.text == "明天复习独立事项" }
            assertEquals("DAILY", group.board.boardType)
            assertEquals("INDEPENDENT", group.board.timeMode)
            assertEquals(day, saved.plannedDay)
            assertEquals(planDeadline(day), saved.dueAt)
            assertNull(saved.reminderAt)
        } finally {
            model.dao.allTodos().flatMap { it.items }.filter { it.text == "明天复习独立事项" }.forEach {
                ReminderScheduler.cancelTodo(rule.activity, it.id)
                model.dao.deleteTodoItem(it)
            }
        }
    }

    @Test fun reminderDateAndRepeatRuleUseSeparateButtonsAndDeadlineHoursStayAccessible() {
        val due = dayMillis() + 20 * 3_600_000L
        val board = TodoBoardWithItems(TodoBoard(id = 1, summary = "显示测试", dueDate = due, reminderAt = due,
            reminderRule = ReminderScheduler.RULE_CUSTOM_DAYS, reminderBaseAt = due, reminderCustomDays = 123), emptyList())
        rule.activity.setContent { YouthTheme { QuickTodoCreateDialog(board, onDismiss = {}, onSave = { _, _, _, _, _ -> }) } }
        rule.onNodeWithText("每隔123天").assertExists()
        rule.onAllNodesWithText(formatDeadlineDateTime(due)).assertCountEquals(2)
        rule.onNodeWithText("每隔123天").performScrollTo().assertIsDisplayed()
        screenshot("reminder-two-buttons")
        rule.onNodeWithText("每隔123天").performClick()
        rule.onNodeWithText("基准提醒时间").assertExists()
        rule.onNodeWithText("单次提醒").performClick()
        rule.onNodeWithText("提醒时间", substring = false).assertExists()
        rule.onNode(hasText("时") and hasSetTextAction()).assertTextContains("20")
        rule.onNodeWithText("重复提醒").performClick()
        rule.onNodeWithText("基准提醒时间").assertExists()
        rule.onNode(hasText("时") and hasSetTextAction()).assertTextContains("20")
        rule.onNodeWithText("确定").performScrollTo().performClick()
        rule.onNodeWithText("每隔123天").assertExists()
        rule.onNodeWithText("截止时间").performScrollTo().performClick()
        rule.onNodeWithText("设置截止时间为").assertExists()
        rule.onNodeWithText("时").assertIsDisplayed()
        rule.onNodeWithText("分").assertIsDisplayed()
        rule.onNode(hasText("分") and hasSetTextAction()).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("时").assertIsDisplayed()
        rule.onNodeWithText("分").assertIsDisplayed()
        screenshot("deadline-keyboard")
        back()
        rule.onNodeWithText("日", substring = false).performScrollTo()
        screenshot("deadline-calendar")
    }

    @Test fun unifiedRepeatCardSkipsRestoresAndClosesWithoutDeletingItsItems(): Unit = runBlocking {
        val model = model()
        val base = dayMillis(LocalDate.now().plusDays(1)) + 9 * 3_600_000L
        val boardId = model.dao.insertTodoBoard(TodoBoard(summary = "统一重复测试清单", reminderAt = base,
            reminderBaseAt = base, reminderRule = ReminderScheduler.RULE_DAILY, timeMode = "UNIFIED"))
        model.dao.insertTodoItems(listOf(TodoItem(boardId = boardId, text = "统一重复测试事项甲"), TodoItem(boardId = boardId, text = "统一重复测试事项乙")))
        try {
            rule.activity.setContent { ModernShiJianApp() }
            rule.onNodeWithText("待办", substring = false).performClick()
            rule.onNodeWithText("重复", substring = false).performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithText("统一重复测试清单").fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodesWithText("统一重复测试清单").assertCountEquals(1)
            rule.onNodeWithText("统一重复测试事项甲").assertDoesNotExist()
            rule.onNodeWithText("统一重复测试事项乙").assertDoesNotExist()
            rule.onNodeWithText("显示已完成").assertDoesNotExist()
            rule.onNodeWithText("一键收起").assertDoesNotExist()
            rule.onNodeWithContentDescription("新增").assertDoesNotExist()
            screenshot("repeat-card")
            rule.onNode(isToggleable()).assertIsOn().performClick()
            rule.onNodeWithText("跳过下一次提醒").assertExists()
            rule.onNodeWithText("关闭重复").assertExists()
            rule.onNodeWithText("删除", substring = false).assertExists()
            rule.onNodeWithText("取消", substring = false).assertExists()
            rule.onNodeWithText("跳过下一次提醒").performClick()
            rule.waitUntil(5_000) { runBlocking { model.dao.getTodoBoard(boardId)?.reminderSkipAt == base } }
            rule.waitUntil(5_000) { rule.onAllNodesWithText("下次提醒已跳过").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("下次提醒已跳过").assertExists()
            rule.onNodeWithText("统一重复测试清单").assertExists()
            screenshot("repeat-skipped")
            rule.onNode(isToggleable()).assertIsOff().performClick()
            rule.onNodeWithText("恢复下一次提醒").performClick()
            rule.waitUntil(5_000) { runBlocking { model.dao.getTodoBoard(boardId)?.reminderSkipAt == null } }
            rule.waitUntil(5_000) { runCatching { rule.onNode(isToggleable()).assertIsOn() }.isSuccess }
            rule.onNode(isToggleable()).assertIsOn().performClick()
            rule.onNodeWithText("删除", substring = false).performClick()
            rule.onNodeWithText("将整个待办框“统一重复测试清单”及其中所有事项移到回收站").assertExists()
            rule.onNodeWithText("取消", substring = false).performClick()
            assertNull(model.dao.getTodoBoard(boardId)!!.deletedAt)
            rule.onNode(isToggleable()).performClick()
            rule.onNodeWithText("关闭重复").performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithText("统一重复测试清单").fetchSemanticsNodes().isEmpty() }
            val saved = model.dao.getTodoBoard(boardId)!!
            assertNull(saved.reminderRule)
            assertEquals(base, saved.reminderAt)
            assertNull(saved.deletedAt)
            assertEquals(2, model.dao.getTodoItems(boardId).count { it.deletedAt == null })
            screenshot("repeat-closed")
        } finally {
            ReminderScheduler.cancelTodoBoard(rule.activity, boardId)
            model.dao.getTodoBoard(boardId)?.let { model.dao.deleteTodoBoard(it) }
        }
    }

    @Test fun moduleMemorySearchRestoresAfterOpeningRecord(): Unit = runBlocking {
        val model = model()
        val root = NoteNode(id = "ui-search-root", kind = "folder", parentId = MemorySpaces.WORK_ID, title = "搜索测试分类")
        val note = NoteNode(id = "ui-search-note", parentId = root.id, title = "独特搜索标题", text = "独特搜索正文")
        model.notes.putAll(listOf(root, note))
        try {
            rule.activity.setContent { ModernShiJianApp() }
            rule.onNodeWithText("记忆").performClick()
            rule.onNodeWithText("工作").performClick()
            rule.waitUntil { rule.onAllNodesWithText(root.title).fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            rule.onNodeWithContentDescription("搜索记忆").performClick()
            rule.onNode(hasSetTextAction()).performTextInput("独特搜索")
            rule.onNode(hasSetTextAction()).performImeAction()
            rule.onNodeWithText(note.title).performClick()
            rule.onNodeWithContentDescription("保存并返回").performClick()
            rule.onNode(hasSetTextAction()).assertTextContains("独特搜索")
            rule.onNodeWithText(note.title).assertExists()
            screenshot("search-restored")
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithText(root.title).performClick()
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            rule.onNodeWithContentDescription("搜索记忆").performClick()
            rule.onNodeWithText("仅在当前分类下").assertIsOn()
            rule.onNode(hasSetTextAction()).performTextInput("独特搜索")
            rule.onNode(hasSetTextAction()).performImeAction()
            rule.onNodeWithText(note.title).performClick()
            rule.onNodeWithContentDescription("保存并返回").performClick()
            rule.onNode(hasSetTextAction()).assertTextContains("独特搜索")
            rule.onNodeWithText(note.title).assertExists()
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            rule.onAllNodesWithText(root.title).fetchSemanticsNodes().also { assertTrue(it.isNotEmpty()) }
            rule.onNodeWithText("待办").performClick()
            rule.onNodeWithText("记忆").performClick()
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            rule.onNodeWithText("工作").assertExists()
            rule.onNodeWithText(root.title).assertDoesNotExist()
        } finally { model.notes.remove(listOf(root.id, note.id)); model.notes.removeVersions(listOf(root.id, note.id)) }
    }

    @Test fun memoryExportModeCanStartEmptyAndOrdinarySelectionMovesInstead(): Unit = runBlocking {
        val model = model()
        val root = NoteNode(id = "ui-select-root", kind = "folder", parentId = MemorySpaces.WORK_ID, title = "多选测试分类")
        model.notes.put(root)
        try {
            rule.activity.setContent { ModernShiJianApp() }
            rule.onNodeWithText("记忆").performClick()
            rule.onNodeWithText("工作").performClick()
            rule.onNodeWithContentDescription("更多").performClick()
            rule.onNodeWithText("多选").assertDoesNotExist()
            rule.onNodeWithText("‹　排序").assertExists()
            rule.onNodeWithText("‹　排序").performClick()
            rule.onNodeWithText("手动顺序", substring = true).assertExists()
            rule.onNodeWithText("最近创建").assertExists()
            screenshot("sort-left-submenu")
            // Placement is inspected in the screenshot: the two popups use separate windows.
            rule.onNodeWithText("最近修改").performClick()
            rule.onNodeWithContentDescription("更多").performClick()
            rule.onNodeWithText("导出").performClick()
            rule.onNodeWithText("选择导出内容").assertExists()
            screenshot("export-selection")
            rule.onNodeWithText("导出").assertIsNotEnabled()
            rule.onNodeWithText(root.title).performClick()
            rule.onNodeWithText("导出").assertIsEnabled()
            rule.onNodeWithText("取消").performClick()
            rule.onNodeWithContentDescription("条目操作").performClick()
            rule.onNodeWithText("导出").assertDoesNotExist()
            back()
            rule.onNodeWithText(root.title).performTouchInput { longClick() }
            rule.onNodeWithText("移动到").assertIsEnabled()
            rule.onNodeWithText("导出").assertDoesNotExist()
            rule.onNodeWithText("移动到").performClick()
            rule.onNodeWithText("生活").performClick()
            rule.onNodeWithText("新建分类").assertExists()
            rule.onNodeWithText("移动到这里").assertExists()
            screenshot("move-destination")
            rule.onNodeWithText("新建分类").performClick()
            rule.onNode(hasSetTextAction()).performTextInput("移动时创建的分类")
            waitForKeyboard()
            rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex) and hasAnyAncestor(isDialog())).performScrollToNode(hasText("创建并进入"))
            rule.onNodeWithText("创建并进入").performClick()
            rule.waitUntil(timeoutMillis = 3_000) { rule.onAllNodesWithText("记忆 / 生活 / 移动时创建的分类").fetchSemanticsNodes().isNotEmpty() }
            screenshot("move-created-destination")
            rule.onNodeWithText("移动到这里").performClick()
            rule.waitUntil { runBlocking { model.notes.node(root.id)?.parentId !in setOf(null, MemorySpaces.WORK_ID) } }
        } finally {
            val created = model.notes.nodes().filter { it.title == "移动时创建的分类" }.map { it.id }
            model.notes.remove(created + root.id); model.notes.removeVersions(created + root.id)
        }
    }

    @Test fun imageDefaultsAreOnTheirOwnSettingsPage() {
        val model = model()
        rule.activity.setContent { YouthTheme { GeneralSettings(model) {} } }
        rule.onNodeWithText("图片显示与保存").performClick()
        rule.onNodeWithText("图片默认显示").assertExists()
        rule.onNodeWithText("新插入图片默认保存").assertExists()
        back()
        rule.onNodeWithText("查看可清理素材").assertExists()
    }

    @Test fun memoryCreationMenuClosesOnBackAndBackdrop() {
        rule.activity.setContent { ModernShiJianApp() }
        rule.onNodeWithText("记忆").performClick()
        rule.onNodeWithContentDescription("新增").assertDoesNotExist()
        rule.onNodeWithText("工作").performClick()
        rule.onNodeWithContentDescription("新增").performClick()
        rule.onNodeWithText("创建分类").assertExists()
        rule.onNodeWithText("创建记忆").assertExists()
        screenshot("memory-create-shade")
        back()
        rule.onNodeWithText("创建记忆").assertDoesNotExist()
        rule.onNodeWithContentDescription("新增").performClick()
        rule.onRoot(useUnmergedTree = true).performTouchInput { click(androidx.compose.ui.geometry.Offset(10f, 200f)) }
        rule.onNodeWithText("创建记忆").assertDoesNotExist()
    }

    @Test fun forcedChildSettingsExplainWhichAncestorToUnlock() {
        val parent = NoteNode(id = "parent", kind = "folder", title = "图片总分类", forceChildren = true, imageDisplay = "card")
        val child = NoteNode(id = "child", kind = "folder", parentId = parent.id, title = "图片子分类", imageDisplay = "preview")
        rule.activity.setContent { YouthTheme { NoteSettingsDialog(child, listOf(parent, child), {}, {}) } }
        rule.onNodeWithText("正文预览").performClick()
        rule.onNodeWithText("请先前往路径“图片总分类”解除强制下级设置。").assertExists()
        screenshot("forced-settings-lock")
    }

    @Test fun structureTreeProvidesHorizontalScrollingForDeepBranches() {
        val nodes = (0..20).map { depth -> NoteNode(id = "structure-$depth", kind = "folder", parentId = if (depth == 0) null else "structure-${depth - 1}", title = "长名字的分类${depth}") }
        rule.activity.setContent { YouthTheme { StructureTreeScreen(nodes, null, {}, onOpen = {}) } }
        rule.onNodeWithText("结构树").assertExists()
        rule.onNode(SemanticsMatcher("横向可滚动") { node ->
            node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.maxValue?.invoke()?.let { it > 0f } == true
        }).assertExists()
        screenshot("structure-tree")
    }

    @Test fun diaryExportUsesSeparateSelectionAndDeleteConfirmationKeepsSelectionOnCancel(): Unit = runBlocking {
        val model = model()
        val first = NoteNode(id = "ui-diary-one", kind = "diary", day = dayMillis(LocalDate.now().minusDays(1)), title = "多选日记甲", text = "甲的正文")
        val second = NoteNode(id = "ui-diary-two", kind = "diary", day = dayMillis(LocalDate.now().minusDays(2)), title = "多选日记乙", text = "乙的正文")
        model.notes.putAll(listOf(first, second))
        try {
            rule.activity.setContent { ModernShiJianApp() }
            rule.onNodeWithText("日记").performClick()
            rule.onNodeWithText(first.title).performTouchInput { longClick() }
            rule.onNodeWithText("已选1").assertExists()
            rule.onNodeWithText("导出").assertDoesNotExist()
            assertTrue(rule.onNodeWithText("取消").getUnclippedBoundsInRoot().left < rule.onNodeWithText("删除").getUnclippedBoundsInRoot().left)
            rule.onNodeWithText("全选").performClick().assertIsSelected()
            rule.onNodeWithText("已选2").assertExists()
            rule.onNodeWithText("全选").performClick().assertIsNotSelected()
            rule.onNodeWithText("已选0").assertExists()
            rule.onNodeWithText("删除").assertIsNotEnabled()
            rule.onNodeWithText(first.title).performClick()
            rule.onNodeWithText("删除").performClick()
            rule.onNodeWithText("将这1条记录移到回收站").assertIsDisplayed()
            screenshot("diary-delete-confirmation")
            assertNull(model.notes.node(first.id)!!.deletedAt)
            rule.onNode(hasText("取消") and hasAnyAncestor(isDialog())).performClick()
            rule.onNodeWithText("已选1").assertExists()
            rule.onNodeWithText("取消").performClick()
            rule.onNodeWithContentDescription("更多").performClick()
            rule.onNodeWithText("多选").assertDoesNotExist()
            rule.onNodeWithText("导出日记模块").assertDoesNotExist()
            rule.onNodeWithText("导出日记").performClick()
            rule.onNodeWithText("已选0").assertExists()
            rule.onNodeWithText("导出").assertIsNotEnabled()
            rule.onNodeWithText("全选").performClick().assertIsSelected()
            screenshot("diary-export-selection")
            rule.onNodeWithText("导出").performClick()
            rule.onNodeWithText("导出内容").assertExists()
            rule.onNodeWithText("包含结构树形图").assertDoesNotExist()
            rule.onNodeWithText("每个所选顶层分类", substring = true).assertDoesNotExist()
            screenshot("diary-export-options")
            back()
            rule.onNodeWithText(first.title).performTouchInput { longClick() }
            rule.onNodeWithText("删除").performClick()
            rule.onNode(hasText("删除") and hasAnyAncestor(isDialog())).performClick()
            rule.waitUntil(3_000) { runBlocking { model.notes.node(first.id)?.deletedAt != null } }
            rule.onNodeWithText("已选1").assertDoesNotExist()
            rule.onNodeWithText("撤销", substring = false).assertDoesNotExist()
            rule.onNodeWithText("已移到回收站").assertDoesNotExist()
        } finally { model.notes.remove(listOf(first.id, second.id)); model.notes.removeVersions(listOf(first.id, second.id)) }
    }

    @Test fun memoryDeletionCountsEntireBranchAndAllSelectionCanBeToggledOff(): Unit = runBlocking {
        val model = model()
        val folder = NoteNode(id = "ui-delete-folder", kind = "folder", parentId = MemorySpaces.WORK_ID, title = "删除测试分类")
        val note = NoteNode(id = "ui-delete-note", parentId = folder.id, title = "删除测试记忆")
        model.notes.putAll(listOf(folder, note))
        try {
            rule.activity.setContent { ModernShiJianApp() }
            rule.onNodeWithText("记忆").performClick()
            rule.onNodeWithText("工作").performClick()
            rule.onNodeWithText(folder.title).performTouchInput { longClick() }
            rule.onNodeWithText("已选2").assertExists()
            rule.onNodeWithText("全选").assertIsSelected().performClick().assertIsNotSelected()
            rule.onNodeWithText("已选0").assertExists()
            rule.onNodeWithText(folder.title).performClick()
            rule.onNodeWithText("删除").performClick()
            rule.onNodeWithText("将这1个分类，1条记录移到回收站").assertIsDisplayed()
            rule.onNode(hasText("取消") and hasAnyAncestor(isDialog())).performClick()
            rule.onNodeWithText("已选2").assertExists()
            assertNull(model.notes.node(folder.id)!!.deletedAt)
            rule.onNodeWithText("删除").performClick()
            rule.onNode(hasText("删除") and hasAnyAncestor(isDialog())).performClick()
            rule.waitUntil(3_000) { runBlocking { model.notes.node(folder.id)?.deletedAt != null && model.notes.node(note.id)?.deletedAt != null } }
        } finally { model.notes.remove(listOf(folder.id, note.id)); model.notes.removeVersions(listOf(folder.id, note.id)) }
    }

    @Test fun scheduleDeletesOnlyAfterConfirmationAndExpandedCardHasNoDelete(): Unit = runBlocking {
        val model = model()
        val first = model.dao.insertSchedule(ScheduleEvent(title = "确认删除事务甲", eventAt = planDeadline(dayMillis()) + 3_600_000L, important = true, reminderDays = 0, reminderHours = 0, reminderMinutes = 0))
        val second = model.dao.insertSchedule(ScheduleEvent(title = "确认删除事务乙", eventAt = planDeadline(dayMillis()) + 7_200_000L, reminderDays = 0, reminderHours = 0, reminderMinutes = 0))
        try {
            rule.activity.setContent { ModernShiJianApp() }
            rule.onNodeWithContentDescription("重要事务").assertDoesNotExist()
            rule.onNodeWithText("确认删除事务甲").performClick()
            rule.onAllNodesWithContentDescription("取消重要").assertCountEquals(1)
            rule.onNodeWithText("删除").assertDoesNotExist()
            rule.onNodeWithText("确认删除事务甲").performTouchInput { longClick() }
            rule.onNodeWithText("删除").performScrollTo().performClick()
            rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("将这1条事务移到回收站").assertIsDisplayed() }.isSuccess }
            rule.onNodeWithText("将这1条事务移到回收站").assertIsDisplayed()
            assertNull(model.dao.allSchedule().single { it.id == first }.deletedAt)
            rule.onNode(hasText("取消") and hasAnyAncestor(isDialog())).performClick()
            rule.onNodeWithText("全选").performScrollTo().performClick()
            rule.onNodeWithText("取消全选").assertIsSelected().performScrollTo().performClick()
            rule.onNodeWithText("已选0").assertExists()
            rule.onNodeWithText("删除").assertIsNotEnabled()
            rule.onNodeWithText("全选").assertIsNotSelected().performScrollTo().performClick()
            rule.onNodeWithText("删除").performScrollTo().performClick()
            rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("将这2条事务移到回收站").assertIsDisplayed() }.isSuccess }
            rule.onNodeWithText("将这2条事务移到回收站").assertExists()
            screenshot("schedule-delete-confirmation")
            rule.onNode(hasText("删除") and hasAnyAncestor(isDialog())).performClick()
            rule.waitUntil(3_000) { runBlocking { model.dao.allSchedule().filter { it.id == first || it.id == second }.all { it.deletedAt != null } } }
            assertTrue(model.dao.allSchedule().single { it.id == first }.important)
        } finally { model.dao.allSchedule().filter { it.id == first || it.id == second }.forEach { model.dao.deleteSchedule(it) } }
    }

    @Test fun structureNodesNavigateAndReturnWithBothScrollPositions(): Unit = runBlocking {
        val model = model()
        val roots = (0..30).map { NoteNode(id = "ui-tree-root-$it", kind = "folder", parentId = MemorySpaces.WORK_ID, title = "树目录${it}完整名称", position = it) }
        val branches = (0..7).map { NoteNode(id = "ui-tree-branch-$it", kind = "folder", parentId = if (it == 0) roots[10].id else "ui-tree-branch-${it - 1}", title = "树枝${it}完整名称") }
        val leaf = NoteNode(id = "ui-tree-leaf", parentId = branches.last().id, title = "树跳转记忆", text = "结构树跳转正文")
        val sibling = NoteNode(id = "ui-tree-sibling", parentId = branches.first().id, title = "树侧枝记忆", position = 1)
        val nodes = roots + branches + leaf + sibling
        model.notes.putAll(nodes)
        fun scrollPositions(): Pair<Float, Float> {
            fun value(property: androidx.compose.ui.semantics.SemanticsPropertyKey<androidx.compose.ui.semantics.ScrollAxisRange>) =
                rule.onNode(SemanticsMatcher.keyIsDefined(property)).fetchSemanticsNode().config[property].value()
            return value(SemanticsProperties.HorizontalScrollAxisRange) to value(SemanticsProperties.VerticalScrollAxisRange)
        }
        try {
            rule.activity.setContent { ModernShiJianApp() }
            rule.onNodeWithText("记忆").performClick()
            rule.onNodeWithContentDescription("更多").performClick()
            rule.onNodeWithText("结构树").performClick()
            rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToIndex(10)
            screenshot("structure-overview")
            val folder = branches.last()
            val position = TreeRules.structure(model.notes.nodes()).indexOfFirst { it.node.id == folder.id }
            rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToIndex(position)
            rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).performTouchInput { swipeLeft() }
            val before = scrollPositions()
            assertTrue(before.first > 0); assertTrue(before.second > 0)
            screenshot("structure-branches")
            rule.onNodeWithContentDescription("进入分类：${folder.title}").performClick()
            rule.onNodeWithText(folder.title).assertExists()
            rule.onNodeWithContentDescription("更多").performClick()
            rule.onNodeWithText("结构树").performClick()
            rule.onNodeWithText("结构树").assertExists()
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithText(folder.title).assertExists()
            rule.onNodeWithContentDescription("搜索记忆").performClick()
            rule.onNodeWithText("仅在当前分类下").performClick().assertIsOff()
            rule.onNode(hasSetTextAction()).performTextInput(branches.first().title)
            rule.onNode(hasSetTextAction()).performImeAction()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 3_000)
            rule.onNode(hasText(branches.first().title) and !hasSetTextAction()).performClick()
            rule.onNodeWithContentDescription("更多").performClick()
            rule.onNodeWithText("结构树").performClick()
            rule.onNodeWithText("结构树").assertExists()
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithText(branches.first().title).assertExists()
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNode(hasSetTextAction()).assertTextContains(branches.first().title)
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithText(folder.title).assertExists()
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithText("返回树形图").performClick()
            rule.onNodeWithText("结构树").assertExists()
            assertEquals(before, scrollPositions())
            rule.onNodeWithContentDescription("打开记忆：${leaf.title}").performClick()
            rule.onNodeWithText(leaf.text).assertExists()
            rule.onNodeWithContentDescription("保存并返回").performClick()
            rule.onNodeWithText("返回树形图").performClick()
            rule.onNodeWithText("结构树").assertExists()
            assertEquals(before, scrollPositions())
            screenshot("structure-returned")
        } finally { model.notes.remove(nodes.map { it.id }); model.notes.removeVersions(nodes.map { it.id }) }
    }

    @Test fun oneClickCollapseAndScheduleStarUpdateTheirCards(): Unit = runBlocking {
        val model = model()
        val boardId = model.dao.insertTodoBoard(TodoBoard(summary = "收起测试清单"))
        model.dao.insertTodoItems(listOf(TodoItem(boardId = boardId, text = "收起测试事项")))
        val eventId = model.dao.insertSchedule(ScheduleEvent(title = "星标测试事务", eventAt = planDeadline(dayMillis()) + 24 * 3_600_000L, reminderDays = 0, reminderHours = 0, reminderMinutes = 0))
        try {
            rule.activity.setContent { YouthTheme { TodoLibrary(model, {}, {}, {}, {}, {}) } }
            rule.waitUntil { rule.onAllNodesWithText("收起测试事项").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("一键收起").performClick()
            rule.waitUntil { rule.onAllNodesWithText("收起测试事项").fetchSemanticsNodes().isEmpty() }
            rule.activity.setContent { YouthTheme { ScheduleLibrary(model, {}, {}, {}, {}) } }
            rule.waitUntil { rule.onAllNodesWithText("星标测试事务").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("星标测试事务").performClick()
            rule.onAllNodesWithContentDescription("标记重要").assertCountEquals(1)
            rule.onNodeWithContentDescription("标记重要").performClick()
            rule.waitUntil { rule.onAllNodesWithContentDescription("取消重要").fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodesWithContentDescription("取消重要").assertCountEquals(1)
            rule.onNodeWithContentDescription("重要事务").assertDoesNotExist()
            screenshot("schedule-important")
            assertTrue(model.dao.allSchedule().single { it.id == eventId }.important)
        } finally {
            model.dao.getTodoBoard(boardId)?.let { model.dao.deleteTodoBoard(it) }
            model.dao.allSchedule().find { it.id == eventId }?.let { model.dao.deleteSchedule(it) }
        }
    }
}
