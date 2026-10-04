package com.shijiannote.app

import com.shijiannote.app.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SearchRulesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(day: String, hour: Int = 0): Long = LocalDate.parse(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun date(day: String): SearchDateInput = LocalDate.parse(day).let { SearchDateInput(it.year.toString(), it.monthValue.toString(), it.dayOfMonth.toString()) }

    @Test fun incompleteGroupsAreClearedBeforeSubmitting() {
        val result = SearchRules.normalize(SearchInput(range = true, start = SearchDateInput("2026", "10"), end = date("2026-10-05"), certainDay = true, date = SearchDateInput(month = "10")))
        assertNull(result.error)
        assertEquals(SearchDateInput(), result.input.start)
        assertTrue(result.input.range)
        assertFalse(result.input.certainDay)
        assertEquals(SearchDateInput(), result.input.date)
        assertTrue(SearchRules.canSearch(result.input))
        val empty = SearchRules.normalize(result.input.copy(end = SearchDateInput("2026"))).input
        assertFalse(empty.range)
        assertFalse(SearchRules.canSearch(empty))
    }

    @Test fun invalidFullDatesAndReversedRangeRemainEditableAndRejectSearch() {
        assertEquals("开始日期不存在", SearchRules.normalize(SearchInput(range = true, start = SearchDateInput("2026", "2", "29"))).error)
        assertEquals("日期不存在", SearchRules.normalize(SearchInput(certainDay = true, date = SearchDateInput(month = "4", day = "31"))).error)
        assertEquals("开始日期不能晚于结束日期", SearchRules.normalize(SearchInput(range = true, start = date("2026-10-06"), end = date("2026-10-05"))).error)
        assertNull(SearchRules.normalize(SearchInput(certainDay = true, date = SearchDateInput(month = "2", day = "29"))).error)
    }

    @Test fun rangeIncludesWholeStartAndEndDaysAndSupportsOneBoundary() {
        val range = SearchInput(range = true, start = date("2026-10-04"), end = date("2026-10-05"))
        assertTrue(SearchRules.dateMatches(at("2026-10-04"), range, zone))
        assertTrue(SearchRules.dateMatches(at("2026-10-05", 23), range, zone))
        assertFalse(SearchRules.dateMatches(at("2026-10-06"), range, zone))
        assertTrue(SearchRules.dateMatches(at("2000-01-01"), range.copy(start = SearchDateInput()), zone))
        assertTrue(SearchRules.dateMatches(at("2030-01-01"), range.copy(end = SearchDateInput()), zone))
    }

    @Test fun monthDayMatchesAcrossYearsAndCombinesWithRange() {
        val day = SearchInput(certainDay = true, date = SearchDateInput(month = "10", day = "5"))
        assertTrue(SearchRules.dateMatches(at("2025-10-05"), day, zone))
        assertTrue(SearchRules.dateMatches(at("2026-10-05"), day, zone))
        assertFalse(SearchRules.dateMatches(at("2026-10-04"), day, zone))
        assertFalse(SearchRules.dateMatches(at("2025-10-05"), day.copy(range = true, start = date("2026-01-01")), zone))
    }

    @Test fun favoriteKeywordAndDateMustAllMatchAndDiarySortsByDate() {
        val diaries = listOf(
            NoteNode(id = "old", kind = "diary", text = "旅行", favorite = true, day = at("2025-10-05")),
            NoteNode(id = "new", kind = "diary", text = "旅行", favorite = true, day = at("2026-10-05")),
            NoteNode(id = "unmarked", kind = "diary", text = "旅行", day = at("2026-10-05")),
            NoteNode(id = "wrong-date", kind = "diary", text = "旅行", favorite = true, day = at("2026-10-04")),
            NoteNode(id = "wrong-text", kind = "diary", text = "工作", favorite = true, day = at("2026-10-05")))
        val query = SearchInput(query = "旅行", markedOnly = true, certainDay = true, date = SearchDateInput(month = "10", day = "5"))
        assertEquals(listOf("new", "old"), SearchRules.notes(diaries, emptyList(), 2, null, query, zone).map { it.owner.id })
        assertEquals(3, SearchRules.notes(diaries, emptyList(), 2, null, query.copy(query = ""), zone).size)
    }

    @Test fun memorySearchUsesCurrentTreeAndLiveOwnerForHistoryMatches() {
        val nodes = listOf(NoteNode(id = MemorySpaces.WORK_ID, kind = "folder"), NoteNode(id = MemorySpaces.LIFE_ID, kind = "folder"),
            NoteNode(id = "folder", kind = "folder", parentId = MemorySpaces.WORK_ID),
            NoteNode(id = "child", parentId = "folder", text = "新的内容"), NoteNode(id = "other", parentId = MemorySpaces.LIFE_ID, text = "旧关键词"),
            NoteNode(id = "deleted", parentId = "folder", deletedAt = 1, text = "旧关键词"))
        val snapshots = listOf(SearchNoteSnapshot("child", nodes[3].copy(text = "旧关键词"), 1),
            SearchNoteSnapshot("deleted", nodes[5].copy(deletedAt = null), 2), SearchNoteSnapshot("removed", NoteNode(text = "旧关键词"), 3))
        val input = SearchInput(query = "旧关键词", history = true)
        val hits = SearchRules.notes(nodes, snapshots, 3, "folder", input, zone)
        assertEquals(listOf("child"), hits.map { it.owner.id })
        assertEquals("新的内容", hits.single().owner.text)
        assertEquals("旧关键词", hits.single().matched.text)
        assertEquals(1L, hits.single().versionAt)
        assertTrue(SearchRules.notes(nodes, snapshots, 3, "folder", input.copy(history = false), zone).isEmpty())
        assertEquals(2, SearchRules.notes(nodes, snapshots, 3, "folder", input.copy(currentCategory = false), zone).size)
        assertEquals(2, SearchRules.notes(nodes, snapshots, 3, null, input, zone).size)
    }

    @Test fun scheduleCombinesImportanceArchiveKeywordAndDate() {
        val event = ScheduleEvent(id = 1, title = "旅行", eventAt = at("2026-10-05", 23), reminderDays = 0, reminderHours = 0, reminderMinutes = 0, important = true)
        val events = listOf(event, event.copy(id = 2, important = false), event.copy(id = 3, archived = true), event.copy(id = 4, deletedAt = 1))
        val query = SearchInput(query = "旅行", markedOnly = true, range = true, end = date("2026-10-05"))
        assertEquals(listOf(1L), SearchRules.schedules(events, query, zone).map { it.id })
        assertEquals(setOf(1L, 3L), SearchRules.schedules(events, query.copy(history = true), zone).map { it.id }.toSet())
    }

    @Test fun todoScopesUseDeadlinesRemindersAndExcludeDeletedText() {
        val now = at("2026-10-04", 12)
        val board = TodoBoard(id = 1, summary = "清单", timeMode = "INDEPENDENT")
        val tasks = listOf(TodoItem(id = 1, boardId = 1, text = "任务", plannedDay = at("2026-10-05")),
            TodoItem(id = 2, boardId = 1, text = "任务", plannedDay = at("2026-10-04"), important = true),
            TodoItem(id = 3, boardId = 1, text = "已删关键词", deletedAt = 1),
            TodoItem(id = 4, boardId = 1, text = "任务", completed = true, plannedDay = at("2026-10-05")))
        val groups = listOf(TodoBoardWithItems(board, tasks), TodoBoardWithItems(board.copy(id = 2, archived = true), tasks.map { it.copy(boardId = 2) }))
        val query = SearchInput(query = "任务", todoScope = "tomorrow")
        assertEquals(listOf(1L), SearchRules.todos(groups, query, now, zone).single().items.map { it.id })
        assertEquals(2, SearchRules.todos(groups, query.copy(history = true), now, zone).size)
        assertTrue(SearchRules.todos(groups, query.copy(query = "已删关键词", todoScope = "all"), now, zone).isEmpty())
        assertEquals(listOf(2L), SearchRules.todos(groups, query.copy(todoScope = "important"), now, zone).single().items.map { it.id })
    }
}
