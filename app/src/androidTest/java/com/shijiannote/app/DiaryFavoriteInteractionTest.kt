package com.shijiannote.app

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
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

class DiaryFavoriteInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun model(): WorkspaceModel {
        lateinit var value: WorkspaceModel
        rule.runOnUiThread { value = WorkspaceModel(rule.activity.application as Application) }
        runBlocking { value.spacesReady.first { it } }
        return value
    }

    private fun fixtures(model: WorkspaceModel, count: Int): List<NoteNode> = runBlocking {
        val occupied = model.notes.nodes().mapNotNull { it.day }.toSet()
        generateSequence(LocalDate.now().minusDays(1)) { it.minusDays(1) }
            .filter { dayMillis(it) !in occupied }.take(count).map { date ->
                NoteNode(id = "favorite-${UUID.randomUUID()}", kind = "diary", day = dayMillis(date),
                    text = "收藏不改变正文", document = encodeBlocks(listOf(NoteBlock(text = "收藏不改变正文"))),
                    diaryRoadEnabled = true, diaryRoad = encodeDiaryMoments(listOf(DiaryMoment(text = "原有片段"))))
            }.toList().also { notes ->
                model.notes.putAll(notes)
                model.nodes.first { nodes -> notes.all { note -> nodes.any { it.id == note.id } } }
            }
    }

    private fun cleanup(model: WorkspaceModel, notes: List<NoteNode>) = runBlocking {
        rule.runOnUiThread { rule.activity.setContent { YouthTheme {} } }
        notes.forEach { model.flush(it.id) }
        model.notes.remove(notes.map { it.id })
        model.notes.removeVersions(notes.map { it.id })
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(rule.activity.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun homeBulkFavoritesAndFavoritePageBulkUnfavoritesOnlySelectedDates() {
        val model = model()
        val notes = fixtures(model, 3)
        model.setDiaryFavorites(setOf(notes[1].id, notes[2].id), true)
        runBlocking {
            notes.forEach { model.flush(it.id) }
            model.nodes.first { nodes -> nodes.count { it.id in notes.map { n -> n.id } && it.favorite } == 2 }
        }
        val prefs = appPreferences(rule.activity)
        val oldCalendar = prefs.getBoolean("diaryCalendar", false)
        prefs.edit().putBoolean("diaryCalendar", false).commit()
        var selecting = false
        try {
            rule.activity.setContent { YouthTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                DiaryLibrary(model, onOpen = { _, _ -> }, onSearch = {}, onExport = {}, onTrash = {},
                    onSelection = { selecting = it })
            } } }
            fun selectTwo() {
                rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-row-${notes[0].id}"))
                rule.onNodeWithTag("diary-row-${notes[0].id}").performTouchInput { longClick() }
                rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-row-${notes[1].id}"))
                rule.onNodeWithTag("diary-row-${notes[1].id}").performClick()
                rule.onNodeWithTag("diary-list").performScrollToIndex(0)
                rule.onNodeWithText("已选2").assertExists()
            }
            selectTwo()
            rule.onNodeWithText("取消收藏", substring = false).assertDoesNotExist()
            rule.onNodeWithText("收藏", substring = false).performClick()
            rule.waitUntil(5_000) { !selecting && model.nodes.value.filter { it.id in notes.map { n -> n.id } }.all { it.favorite } }
            rule.onNodeWithContentDescription("仅显示收藏日记").performClick()
            selectTwo()
            rule.onNodeWithText("收藏", substring = false).assertDoesNotExist()
            screenshot("diary-favorite-bulk.png")
            rule.onNodeWithText("取消收藏", substring = false).performClick()
            rule.waitUntil(5_000) { !selecting && model.nodes.value.filter { it.id in notes.take(2).map { n -> n.id } }.none { it.favorite } }
            rule.onNodeWithTag("diary-row-${notes[0].id}").assertDoesNotExist()
            rule.onNodeWithTag("diary-row-${notes[1].id}").assertDoesNotExist()
            rule.onNodeWithTag("diary-row-${notes[2].id}").assertExists()
            runBlocking { notes.forEach { model.flush(it.id) } }
            notes.forEachIndexed { index, original ->
                val saved = runBlocking { model.notes.node(original.id) }!!
                assertEquals(index == 2, saved.favorite)
                assertEquals(original.text, saved.text)
                assertEquals(original.diaryRoad, saved.diaryRoad)
            }
        } finally {
            cleanup(model, notes)
            prefs.edit().putBoolean("diaryCalendar", oldCalendar).commit()
        }
    }

    @Test fun roadAndSummaryHeartsStaySynchronizedThroughEditingUndoAndNavigation() {
        val model = model()
        val note = fixtures(model, 1).single()
        var road by mutableStateOf(true)
        try {
            rule.activity.setContent { YouthTheme {
                if (road) DiaryRoadScreen(note, model, {}, { road = false }, { _, _ -> })
                else RichNoteEditor(model.currentDiary(note.day!!), model, true,
                    onBack = {}, onOpen = {}, onExport = {}, onDiaryRoad = { road = true })
            } }
            val roadHeart = rule.onNodeWithContentDescription("收藏日记").getUnclippedBoundsInRoot()
            val summaryLink = rule.onNodeWithContentDescription("这一天的结语").getUnclippedBoundsInRoot()
            assertTrue(roadHeart.right <= summaryLink.left)
            rule.onNodeWithContentDescription("收藏日记").performClick()
            rule.onNodeWithContentDescription("取消收藏日记").assertExists()
            screenshot("diary-favorite-road.png")
            rule.onNodeWithContentDescription("这一天的结语").performClick()
            rule.waitUntil(5_000) { !road }
            val summaryHeart = rule.onNodeWithContentDescription("取消收藏日记").getUnclippedBoundsInRoot()
            val roadLink = rule.onNodeWithContentDescription("查看小路").getUnclippedBoundsInRoot()
            assertTrue(summaryHeart.right <= roadLink.left)
            screenshot("diary-favorite-summary.png")
            rule.onNode(hasSetTextAction() and hasText(note.text)).performTextReplacement("收藏后的新正文")
            rule.onNodeWithContentDescription("撤销").performClick()
            rule.onNodeWithContentDescription("取消收藏日记").assertExists()
            assertTrue(model.currentDiary(note.day!!).favorite)
            rule.onNodeWithContentDescription("取消收藏日记").performClick()
            rule.onNodeWithContentDescription("查看小路").performClick()
            rule.waitUntil(5_000) { road }
            rule.onNodeWithContentDescription("收藏日记").assertExists()
            runBlocking { model.flush(note.id) }
            val saved = runBlocking { model.notes.node(note.id) }!!
            assertFalse(saved.favorite)
            assertEquals(note.text, saved.text)
            assertEquals(note.diaryRoad, saved.diaryRoad)
        } finally { cleanup(model, listOf(note)) }
    }

    @Test fun staleSummarySavesAndRestorationKeepLatestFavoriteAndPendingDraft() {
        val model = model()
        val note = fixtures(model, 1).single()
        try {
            val draft = DiaryMoment(text = "尚未刷盘的新草稿")
            model.saveDiaryDraft(note, draft)
            model.setDiaryFavorites(setOf(note.id), true)
            model.save(note.copy(text = "旧编辑器保存的新正文"))
            assertTrue(model.currentDiary(note.day!!).favorite)
            assertEquals(draft.id, model.currentDiary(note.day!!).diaryInboxItems().single().moment?.id)
            model.restoreDiarySnapshot(note)
            assertTrue(model.currentDiary(note.day!!).favorite)
            model.toggleDiaryFavorite(note)
            model.save(note.copy(favorite = true))
            runBlocking { model.flush(note.id) }
            val saved = runBlocking { model.notes.node(note.id) }!!
            assertFalse(saved.favorite)
            assertEquals(draft.id, saved.diaryInboxItems().single().moment?.id)
            assertEquals(note.diaryRoad, saved.diaryRoad)
        } finally { cleanup(model, listOf(note)) }
    }

    @Test fun favoriteBeforeFirstTextSaveKeepsOneDatedDiaryIdentity() {
        val model = model()
        val note = fixtures(model, 1).single()
        runBlocking { model.notes.remove(listOf(note.id)); model.nodes.first { entries -> entries.none { it.id == note.id } } }
        try {
            rule.activity.setContent { YouthTheme {
                RichNoteEditor(note.copy(text = "", document = "", diaryRoad = ""), model, true,
                    onBack = {}, onOpen = {}, onExport = {})
            } }
            rule.onNodeWithContentDescription("收藏日记").performClick()
            rule.onNode(hasSetTextAction()).performTextInput("先收藏再写日记")
            rule.onNodeWithContentDescription("完成编辑").performClick()
            rule.waitUntil(5_000) { model.currentDiary(note.day!!).text == "先收藏再写日记" }
            runBlocking { model.flush(note.id) }
            val saved = runBlocking { model.notes.nodes() }.filter { it.kind == "diary" && it.day == note.day && it.deletedAt == null }
            assertEquals(1, saved.size)
            assertEquals(note.id, saved.single().id)
            assertTrue(saved.single().favorite)
        } finally { cleanup(model, listOf(note)) }
    }
}
