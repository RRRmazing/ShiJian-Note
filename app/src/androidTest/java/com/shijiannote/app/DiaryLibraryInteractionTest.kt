package com.shijiannote.app

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.shijiannote.app.data.NoteNode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class DiaryLibraryInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun model(): WorkspaceModel {
        lateinit var value: WorkspaceModel
        rule.runOnUiThread { value = WorkspaceModel(rule.activity.application as Application) }
        runBlocking { value.spacesReady.first { it } }
        return value
    }

    @Test fun diarySearchStartsWithAllEvenWhenHomeShowsFavorites(): Unit = runBlocking {
        val model = model()
        val favorite = NoteNode(id = "ui-search-favorite", kind = "diary", day = dayMillis(), title = "搜索独立条件收藏", favorite = true)
        val normal = favorite.copy(id = "ui-search-unfavorite", title = "搜索独立条件普通", favorite = false)
        model.notes.putAll(listOf(favorite, normal))
        try {
            rule.activity.setContent { ModernShiJianApp() }
            rule.onNodeWithText("日记").performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithText(normal.title).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("仅显示收藏日记").performClick()
            rule.onNodeWithText(normal.title).assertDoesNotExist()
            rule.onNodeWithContentDescription("搜索日记").performClick()
            rule.onNodeWithText("仅收藏").assertIsOff()
            rule.onNodeWithContentDescription("搜索关键词").performTextInput("搜索独立条件")
            rule.onNodeWithContentDescription("搜索关键词").performImeAction()
            rule.onNodeWithText(normal.title).assertExists()
            rule.onNodeWithText(favorite.title).assertExists()
            rule.onNodeWithText(normal.title).performClick()
            rule.onNodeWithContentDescription("保存并返回").performClick()
            rule.onNodeWithContentDescription("搜索关键词").assertTextContains("搜索独立条件")
            rule.onNodeWithText("仅收藏").assertIsOff()
            rule.onNodeWithText(normal.title).assertExists()
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithContentDescription("显示全部日记").assertExists()
            rule.onNodeWithText(normal.title).assertDoesNotExist()
        } finally { model.notes.remove(listOf(favorite.id, normal.id)); model.notes.removeVersions(listOf(favorite.id, normal.id)) }
    }

    @Test fun memoryScopeFiltersDescendantsAndSurvivesOpeningAResult(): Unit = runBlocking {
        val model = model()
        val work = NoteNode(id = "ui-scope-work", parentId = MemorySpaces.WORK_ID, title = "分类范围样本工作")
        val life = work.copy(id = "ui-scope-life", parentId = MemorySpaces.LIFE_ID, title = "分类范围样本生活")
        model.notes.putAll(listOf(work, life))
        try {
            rule.activity.setContent { ModernShiJianApp() }
            rule.onNodeWithText("记忆").performClick()
            rule.onNodeWithText("工作").performClick()
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            rule.onNodeWithContentDescription("搜索记忆").performClick()
            rule.onNodeWithText("仅在当前分类下").assertIsOn()
            rule.onNodeWithContentDescription("搜索关键词").performTextInput("分类范围样本")
            rule.onNodeWithContentDescription("搜索关键词").performImeAction()
            rule.onNodeWithText(work.title).assertExists()
            rule.onNodeWithText(life.title).assertDoesNotExist()
            rule.onNodeWithText("仅在当前分类下").performClick().assertIsOff()
            rule.onNode(hasText("搜索") and hasClickAction()).performClick()
            rule.onNodeWithText(life.title).assertExists()
            rule.onNodeWithText(life.title).performClick()
            rule.onNodeWithContentDescription("保存并返回").performClick()
            rule.onNodeWithText("仅在当前分类下").assertIsOff()
            rule.onNodeWithContentDescription("搜索关键词").assertTextContains("分类范围样本")
            rule.onNodeWithText(life.title).assertExists()
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        } finally { model.notes.remove(listOf(work.id, life.id)); model.notes.removeVersions(listOf(work.id, life.id)) }
    }

    @Test fun heartFiltersInPlaceAndSearchDoesNotResetItsDisplay(): Unit = runBlocking {
        val model = model()
        val favorite = NoteNode(id = "ui-diary-favorite", kind = "diary", day = dayMillis(), title = "收藏筛选样本", text = "正文第一行\n正文第二行\n正文第三行", favorite = true, mood = "平静", tags = "旅行 阅读")
        val normal = favorite.copy(id = "ui-diary-normal", title = "普通日记样本", favorite = false, tags = "", mood = "")
        model.notes.putAll(listOf(favorite, normal))
        var searches = 0
        val settings = appPreferences(rule.activity)
        val previousCalendar = settings.getBoolean("diaryCalendar", false)
        settings.edit().putBoolean("diaryCalendar", false).commit()
        try {
            rule.activity.setContent { YouthTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                DiaryLibrary(model, onOpen = { _, _ -> }, onSearch = { searches++ }, onExport = {}, onTrash = {}, onSelection = {})
            } } }
            rule.waitUntil(5_000) { rule.onAllNodesWithText(normal.title).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("平静 · #旅行 · #阅读").assertExists()
            rule.onNodeWithContentDescription("仅显示收藏日记").performClick()
            rule.onNodeWithText(favorite.title).assertExists()
            rule.onNodeWithText(normal.title).assertDoesNotExist()
            rule.onNodeWithContentDescription("搜索日记").performClick()
            assertEquals(1, searches)
            rule.onNodeWithContentDescription("显示全部日记").assertExists()
            rule.onNodeWithContentDescription("显示全部日记").performClick()
            rule.onNodeWithText(normal.title).assertExists()
        } finally {
            model.notes.remove(listOf(favorite.id, normal.id))
            settings.edit().putBoolean("diaryCalendar", previousCalendar).commit()
        }
    }

    @Test fun calendarAndRecallAcknowledgeSeparatelyAndIncludeToday(): Unit = runBlocking {
        val model = model()
        val today = LocalDate.now()
        val prior = NoteNode(id = "ui-diary-recall-prior", kind = "diary", day = dayMillis(today.minusYears(1)), title = "往年回顾样本")
        val current = prior.copy(id = "ui-diary-recall-current", day = dayMillis(today), title = "今年回顾样本")
        model.notes.putAll(listOf(prior, current))
        val reminderPrefs = DiaryRecallReminder.preferences(rule.activity)
        val savedReminderPrefs = reminderPrefs.all.filterKeys { it.startsWith("diaryRecall") }
        val settings = appPreferences(rule.activity)
        val previousCalendar = settings.getBoolean("diaryCalendar", false)
        settings.edit().putBoolean("diaryCalendar", false).commit()
        reminderPrefs.edit().putBoolean("diaryRecallEnabled", true)
            .remove("diaryRecallCalendarReadDate").remove("diaryRecallReadDate")
            .putString("diaryRecallNotifiedDate", today.toString()).commit()
        DiaryRecallReminder.refresh(rule.activity)
        try {
            rule.activity.setContent { YouthTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                var recall by remember { mutableStateOf(false) }
                DiaryLibrary(model, onOpen = { _, _ -> }, onSearch = {}, onExport = {}, onTrash = {}, onSelection = {}, pastPage = recall, onPastChange = { recall = it })
            } } }
            rule.waitUntil(5_000) { rule.onAllNodesWithText(current.title).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(DiaryRecallReminder.calendarUnread(rule.activity, today))
            assertTrue(DiaryRecallReminder.recallUnread(rule.activity, today))
            rule.onNodeWithContentDescription("展开日历").performClick()
            assertFalse(DiaryRecallReminder.calendarUnread(rule.activity, today))
            assertTrue(DiaryRecallReminder.recallUnread(rule.activity, today))
            rule.onNodeWithText("往年今日").performClick()
            rule.waitForIdle()
            assertFalse(DiaryRecallReminder.recallUnread(rule.activity, today))
            rule.onNodeWithText(current.title).assertExists()
            rule.onNodeWithText(prior.title).assertExists()
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithContentDescription("更多").performClick()
            rule.onNodeWithText("日记设置").performClick()
            rule.onNodeWithText("提醒往年今日").performClick()
            rule.waitForIdle()
            assertFalse(DiaryRecallReminder.enabled(rule.activity))
            assertFalse(DiaryRecallReminder.calendarUnread(rule.activity, today))
            assertFalse(DiaryRecallReminder.recallUnread(rule.activity, today))
        } finally {
            model.notes.remove(listOf(prior.id, current.id))
            settings.edit().putBoolean("diaryCalendar", previousCalendar).commit()
            val editor = reminderPrefs.edit()
            reminderPrefs.all.keys.filter { it.startsWith("diaryRecall") }.forEach { editor.remove(it) }
            savedReminderPrefs.forEach { (key, value) -> when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
            } }
            editor.commit()
        }
    }
}
