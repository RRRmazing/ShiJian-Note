package com.shijiannote.app

import android.app.Application
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.shijiannote.app.data.NoteNode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.io.File
import java.util.UUID

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
        val favorite = NoteNode(id = "ui-search-favorite", kind = "diary", day = dayMillis(LocalDate.now().minusDays(1)), title = "搜索独立条件收藏", favorite = true)
        val normal = favorite.copy(id = "ui-search-unfavorite", title = "搜索独立条件普通", favorite = false)
        model.notes.putAll(listOf(favorite, normal))
        try {
            rule.activity.setContent { ModernShiJianApp() }
            rule.onNodeWithText("日记").performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("diary-row-${normal.id}").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("仅显示收藏日记").performClick()
            rule.onNodeWithTag("diary-row-${normal.id}").assertDoesNotExist()
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
            rule.onNodeWithTag("diary-row-${normal.id}").assertDoesNotExist()
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
        val favorite = NoteNode(id = "ui-diary-favorite", kind = "diary", day = dayMillis(LocalDate.now().minusDays(1)), title = "收藏筛选样本", text = "正文第一行\n正文第二行\n正文第三行", favorite = true, mood = "平静", tags = "旅行 阅读")
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
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("diary-row-${normal.id}").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("#旅行 · #阅读").assertExists()
            rule.onNodeWithText(favorite.title).assertDoesNotExist()
            rule.onNodeWithText("正文第一行", substring = true).assertDoesNotExist()
            rule.onNodeWithContentDescription("仅显示收藏日记").performClick()
            rule.onNodeWithTag("diary-row-${favorite.id}").assertExists()
            rule.onNodeWithTag("diary-row-${normal.id}").assertDoesNotExist()
            rule.onNodeWithContentDescription("搜索日记").performClick()
            assertEquals(1, searches)
            rule.onNodeWithContentDescription("显示全部日记").assertExists()
            rule.onNodeWithContentDescription("显示全部日记").performClick()
            rule.onNodeWithTag("diary-row-${normal.id}").assertExists()
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
            rule.waitUntil(5_000) { rule.onAllNodesWithText("今日小路").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("diary-row-${current.id}").assertDoesNotExist()
            assertTrue(DiaryRecallReminder.calendarUnread(rule.activity, today))
            assertTrue(DiaryRecallReminder.recallUnread(rule.activity, today))
            rule.onNodeWithContentDescription("展开日历").performClick()
            assertFalse(DiaryRecallReminder.calendarUnread(rule.activity, today))
            assertTrue(DiaryRecallReminder.recallUnread(rule.activity, today))
            rule.onNodeWithText("往年今日：${today.monthValue}.${today.dayOfMonth}").performClick()
            rule.waitForIdle()
            assertFalse(DiaryRecallReminder.recallUnread(rule.activity, today))
            rule.onNodeWithTag("diary-row-${current.id}").assertExists()
            rule.onNodeWithTag("diary-row-${prior.id}").assertExists()
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

    @Test fun todayEntrancesShareIdentityAndOpeningDoesNotSaveEmptyRecords(): Unit = runBlocking {
        val model = model()
        var road: NoteNode? = null
        var summary: NoteNode? = null
        val day = dayMillis()
        val initial = model.notes.nodes().filter { it.kind == "diary" && it.day == day && it.deletedAt == null }
        try {
            rule.activity.setContent { YouthTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                DiaryLibrary(model, onOpen = { _, _ -> }, onSearch = {}, onExport = {}, onTrash = {}, onSelection = {},
                    onRoad = { road = it }, onSummary = { node, _ -> summary = node })
            } } }
            rule.onNodeWithText("今日小路").performClick()
            rule.onNodeWithText("今日结语").performClick()
            File(rule.activity.getExternalFilesDir(null), "diary-home-preview.png").outputStream().use {
                rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertNotNull(road)
            assertEquals(road?.id, summary?.id)
            assertEquals(day, road?.day)
            assertEquals(initial, model.notes.nodes().filter { it.kind == "diary" && it.day == day && it.deletedAt == null })
        } finally {
            // Opening the entrances itself must not have introduced anything to clean up.
            assertEquals(initial.map { it.id }.toSet(), model.notes.nodes().filter { it.kind == "diary" && it.day == day && it.deletedAt == null }.map { it.id }.toSet())
        }
    }

    @Test fun compactHomeFitsSevenTaggedDatesAbovePhoneNavigation(): Unit = runBlocking {
        val model = model()
        val fixtures = (1..7).map { offset -> NoteNode(id = "ui-compact-${UUID.randomUUID()}", kind = "diary",
            day = dayMillis(LocalDate.now().minusDays(offset.toLong())), createdAt = Long.MAX_VALUE - offset,
            title = "旧标题不可见$offset", text = "首页不显示这段正文", tags = "旅行 阅读 这是一段需要省略的很长标签内容") }
        model.notes.putAll(fixtures)
        val prefs = appPreferences(rule.activity)
        val oldCalendar = prefs.getBoolean("diaryCalendar", false)
        prefs.edit().putBoolean("diaryCalendar", false).commit()
        try {
            rule.activity.setContent { YouthTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                    // A 360 x 640dp phone including 48dp status/cutout, 24dp gesture and 80dp app navigation areas.
                    Column(Modifier.width(360.dp).height(640.dp)) {
                        Spacer(Modifier.height(48.dp))
                        Box(Modifier.weight(1f)) {
                            DiaryLibrary(model, onOpen = { _, _ -> }, onSearch = {}, onExport = {}, onTrash = {}, onSelection = {})
                        }
                        Spacer(Modifier.height(104.dp))
                    }
                }
            } }
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("diary-row-${fixtures.first().id}").fetchSemanticsNodes().isNotEmpty() }
            val viewport = rule.onNodeWithTag("diary-list").getUnclippedBoundsInRoot()
            val rowTags = rule.onAllNodes(SemanticsMatcher("dated diary rows") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("diary-row-") == true
            }).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
            val rowBounds = rowTags.map { it to rule.onNodeWithTag(it).getUnclippedBoundsInRoot() }
            val visibleRows = rowBounds.map { it.second }.filter { it.top >= viewport.top && it.bottom <= viewport.bottom }
            // Preserve evidence before any layout assertion so failures can be diagnosed on the device.
            val screenshotFile = File(rule.activity.getExternalFilesDir(null), "diary-home-compact-seven.png")
            screenshotFile.outputStream().use {
                rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val layoutDiagnostic = buildString {
                appendLine("Scenario: 360 x 640dp; status/cutout=48dp, app navigation=80dp, gesture=24dp; fontScale=1")
                appendLine("Root=${rule.onRoot().getUnclippedBoundsInRoot()}")
                appendLine("Viewport=$viewport; height=${viewport.bottom - viewport.top}")
                appendLine("HeaderText=${rule.onNodeWithText("日记").getUnclippedBoundsInRoot()}")
                appendLine("TodayRoadText=${rule.onNodeWithText("今日小路").getUnclippedBoundsInRoot()}")
                appendLine("TodaySummaryText=${rule.onNodeWithText("今日结语").getUnclippedBoundsInRoot()}")
                appendLine("Composed history count=${rowBounds.size}; fully visible history count=${visibleRows.size}")
                rowBounds.forEachIndexed { index, (tag, bounds) ->
                    val complete = bounds.top >= viewport.top && bounds.bottom <= viewport.bottom
                    appendLine("Row[$index] $tag bounds=$bounds height=${bounds.bottom - bounds.top}; fullyVisible=$complete")
                }
            }
            val diagnosticFile = File(rule.activity.getExternalFilesDir(null), "diary-home-compact-seven-layout.txt")
            diagnosticFile.writeText(layoutDiagnostic)
            // Shared-storage copies survive connected-test cleanup uninstalling the target app.
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            listOf(screenshotFile to "/sdcard/shijian-diary-home-compact-seven-v7.png",
                diagnosticFile to "/sdcard/shijian-diary-home-compact-seven-v7.txt").forEach { (source, destination) ->
                // UiAutomation executes argv directly; these app-owned paths need no shell quoting.
                assertTrue("Evidence source path must be a controlled single argument",
                    source.absolutePath.all { it.isLetterOrDigit() || it in "/._-" })
                val descriptor = automation.executeShellCommand("cp ${source.absolutePath} $destination")
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                val verification = automation.executeShellCommand("stat -c %s $destination")
                val copiedSize = ParcelFileDescriptor.AutoCloseInputStream(verification).use { it.bufferedReader().readText().trim() }
                assertEquals("Layout evidence was not copied to $destination; stat output=$copiedSize",
                    source.length(), copiedSize.toLongOrNull() ?: -1L)
            }
            assertTrue("At least seven history rows must fit without scrolling\n$layoutDiagnostic", visibleRows.size >= 7)
            visibleRows.forEach { bounds ->
                assertTrue("Default history rows stay compact: $bounds\n$layoutDiagnostic", (bounds.bottom - bounds.top) in 48.dp..56.dp)
            }
            fixtures.forEach { rule.onNodeWithText(it.title).assertDoesNotExist() }
            rule.onNodeWithText("首页不显示这段正文").assertDoesNotExist()
            rule.onNodeWithText("今日小路").assertIsDisplayed()
            rule.onNodeWithText("今日结语").assertIsDisplayed()
        } finally {
            model.notes.remove(fixtures.map { it.id })
            prefs.edit().putBoolean("diaryCalendar", oldCalendar).commit()
        }
    }

    @Test fun retainedDraftDateShowsYellowBadgeAndWorksWithCalendarAndSelection(): Unit = runBlocking {
        val model = model()
        val draft = DiaryMoment(text = "草稿正文不应出现在首页", tags = "草稿标签不应混入首页")
        val item = DiaryInboxItem(id = draft.id, status = "draft", moment = draft)
        val note = NoteNode(id = "ui-draft-only-${UUID.randomUUID()}", kind = "diary",
            day = dayMillis(LocalDate.now().minusDays(1)), createdAt = Long.MAX_VALUE,
            diaryInbox = encodeDiaryInbox(listOf(item)))
        model.notes.put(note)
        val prefs = appPreferences(rule.activity)
        val oldCalendar = prefs.getBoolean("diaryCalendar", false)
        prefs.edit().putBoolean("diaryCalendar", false).commit()
        var opened: String? = null
        var selection = false
        var exported: Set<String> = emptySet()
        try {
            rule.activity.setContent { YouthTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                DiaryLibrary(model, onOpen = { value, _ -> opened = value.id }, onSearch = {},
                    onExport = { exported = it }, onTrash = {}, onSelection = { selection = it })
            } } }
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("diary-row-${note.id}").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(DiaryLibraryRules.isVisible(note))
            assertFalse(note.hasDiaryContent())
            rule.onNodeWithText("仅有草稿").assertExists()
            val badge = rule.onNodeWithTag("diary-inbox-status-${note.id}", useUnmergedTree = true).captureToImage()
            val pixels = badge.toPixelMap()
            val yellowText = (0 until badge.width).any { x -> (0 until badge.height).any { y ->
                val pixel = pixels[x, y]
                pixel.red > pixel.blue + .15f && pixel.green > pixel.blue + .1f
            } }
            assertTrue("Draft status uses pale yellow text", yellowText)
            rule.onNodeWithText(draft.text).assertDoesNotExist()
            rule.onNodeWithText("#${draft.tags}").assertDoesNotExist()
            rule.onAllNodesWithText("收纳箱 ·", substring = true).assertCountEquals(0)
            rule.onNodeWithTag("diary-row-${note.id}").performClick()
            assertEquals(note.id, opened)
            assertEquals(listOf(item), model.notes.nodes().first { it.id == note.id }.diaryInboxItems())
            rule.onNodeWithContentDescription("展开日历").performClick()
            rule.onNodeWithContentDescription("收起日历").performClick()
            rule.onNodeWithTag("diary-row-${note.id}").performScrollTo().performTouchInput { longClick() }
            rule.waitForIdle()
            assertTrue(selection)
            rule.onNodeWithText("今日小路").assertDoesNotExist()
            rule.onNodeWithText("取消").performClick()
            rule.waitForIdle()
            assertFalse(selection)
            rule.onNodeWithContentDescription("更多").performClick()
            rule.onNodeWithText("总收纳箱").assertDoesNotExist()
            rule.onNodeWithText("日记设置").performClick()
            rule.onNodeWithText("导出日记").performClick()
            rule.onNodeWithTag("diary-row-${note.id}").performScrollTo().performClick()
            rule.onNodeWithText("下一步").performClick()
            assertEquals(setOf(note.id), exported)
            assertEquals(DiaryZipCounts(1, 0, 0, 0), DiaryZipExport.counts(listOf(note)))
            // Returning from the export panel must preserve the selection until explicitly canceled.
            rule.onNodeWithText("已选择 1 天").assertExists()
            rule.onNodeWithText("取消").performClick()
            rule.onNodeWithTag("diary-row-${note.id}").assertExists()
        } finally {
            model.notes.remove(listOf(note.id))
            prefs.edit().putBoolean("diaryCalendar", oldCalendar).commit()
        }
    }

    @Test fun homeTagsIncludePublishedMomentsButNeverInboxDraftTags(): Unit = runBlocking {
        val model = model()
        val published = DiaryMoment(text = "已发布片段正文", tags = "重复 片段标签")
        val note = NoteNode(id = "ui-tags-${UUID.randomUUID()}", kind = "diary", day = dayMillis(LocalDate.now().minusDays(1)),
            createdAt = Long.MAX_VALUE, text = "当日结语正文", tags = "重复 结语标签",
            diaryRoad = encodeDiaryMoments(listOf(published)),
            diaryInbox = encodeDiaryInbox(listOf(DiaryInboxItem(moment = DiaryMoment(text = "暂存", tags = "暂存独有标签")))))
        val retained = NoteNode(id = "ui-retained-${UUID.randomUUID()}", kind = "diary", day = dayMillis(LocalDate.now().minusDays(2)),
            createdAt = Long.MAX_VALUE, diaryInbox = encodeDiaryInbox(listOf(DiaryInboxItem(status = "retracted", moment = published))))
        model.notes.putAll(listOf(note, retained))
        val prefs = appPreferences(rule.activity)
        val oldCalendar = prefs.getBoolean("diaryCalendar", false)
        prefs.edit().putBoolean("diaryCalendar", false).commit()
        try {
            rule.activity.setContent { YouthTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                DiaryLibrary(model, onOpen = { _, _ -> }, onSearch = {}, onExport = {}, onTrash = {}, onSelection = {})
            } } }
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("diary-row-${note.id}").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("#重复 · #结语标签 · #片段标签").assertExists()
            rule.onNodeWithText("#暂存独有标签", substring = true).assertDoesNotExist()
            rule.onNodeWithTag("diary-row-${retained.id}").performScrollTo()
            rule.onNodeWithText("仅有收纳").assertExists()
            assertEquals(listOf("重复", "结语标签", "片段标签"), DiaryLibraryRules.publishedTags(note))
            assertTrue(DiaryLibraryRules.isVisible(retained))
        } finally {
            model.notes.remove(listOf(note.id, retained.id))
            prefs.edit().putBoolean("diaryCalendar", oldCalendar).commit()
        }
    }
}
