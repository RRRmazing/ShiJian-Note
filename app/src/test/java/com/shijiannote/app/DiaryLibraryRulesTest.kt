package com.shijiannote.app

import com.shijiannote.app.data.NoteNode
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DiaryLibraryRulesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 5)
    private fun diary(date: LocalDate, text: String = "记录") = NoteNode(id = date.toString(), kind = "diary", text = text,
        day = date.atStartOfDay(zone).toInstant().toEpochMilli())

    @Test fun midnightMovesTodayRecordToHistoryWithoutChangingItsIdentity() {
        val current = diary(today)
        val yesterday = diary(today.minusDays(1))
        val entries = listOf(current, yesterday)
        assertEquals(listOf(yesterday), DiaryLibraryRules.list(entries, today, zone = zone))
        assertEquals(entries, DiaryLibraryRules.list(entries, today.plusDays(1), zone = zone))
    }

    @Test fun favoritesAndSelectionKeepTodayRealRecordAccessible() {
        val current = diary(today).copy(favorite = true)
        val yesterday = diary(today.minusDays(1))
        val entries = listOf(current, yesterday)
        assertEquals(listOf(current), DiaryLibraryRules.list(entries, today, favorites = true, zone = zone))
        assertEquals(entries, DiaryLibraryRules.list(entries, today, selecting = true, zone = zone))
    }

    @Test fun emptyRecordsStayOutEvenWhenMetadataOrInboxRemain() {
        val empty = diary(today.minusDays(1), "")
        assertFalse(DiaryLibraryRules.isVisible(empty))
        assertFalse(DiaryLibraryRules.isVisible(empty.copy(mood = "平静", favorite = true, diaryRoadEnabled = true)))
        assertTrue(DiaryLibraryRules.isVisible(empty.copy(title = "旧日记")))
        assertFalse(DiaryLibraryRules.isVisible(empty.copy(text = "旧日记", deletedAt = 1)))
        assertFalse(DiaryLibraryRules.isVisible(empty.copy(text = "记忆", kind = "memory")))
    }

    @Test fun undatedLegacyDiaryUsesCreatedLocalDay() {
        val current = diary(today).copy(day = null, createdAt = today.atTime(23, 59).atZone(zone).toInstant().toEpochMilli())
        assertTrue(DiaryLibraryRules.isOnDate(current, today, zone))
        assertFalse(DiaryLibraryRules.isOnDate(current, today.plusDays(1), zone))
    }
}
