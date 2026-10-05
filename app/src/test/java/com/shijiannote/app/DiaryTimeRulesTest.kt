package com.shijiannote.app

import org.junit.Assert.*
import org.junit.Test

class DiaryTimeRulesTest {
    @Test fun previewUsesOccurrenceTimeAndExcludesEditedMomentItself() {
        val a = DiaryMoment(id = "a", sentAt = 100, occurredAt = 10)
        val b = DiaryMoment(id = "b", sentAt = 200, occurredAt = 20)
        val c = DiaryMoment(id = "c", sentAt = 300, occurredAt = 30)
        val position = diaryNeighbors(listOf(a, b, c), b.copy(occurredAt = 25), "occurred", 1_000)
        assertEquals(a, position.previous)
        assertEquals(c, position.next)
    }

    @Test fun sendOrderKeepsOldPositionAndNewDraftFollowsActualSendTime() {
        val a = DiaryMoment(id = "a", sentAt = 100, occurredAt = 900)
        val b = DiaryMoment(id = "b", sentAt = 200, occurredAt = 1)
        assertEquals(a, diaryNeighbors(listOf(a, b), b.copy(occurredAt = 0), "sent", 1_000).previous)
        val draft = DiaryMoment(id = "draft", createdAt = 5, occurredAt = 1)
        val position = diaryNeighbors(listOf(a, b), draft, "sent", 1_000)
        assertEquals(b, position.previous)
        assertNull(position.next)
    }

    @Test fun futureOccurrenceAndStableTiesMatchFinalComparator() {
        val a = DiaryMoment(id = "a", sentAt = 100, occurredAt = 1_000)
        val b = DiaryMoment(id = "b", sentAt = 100, occurredAt = 1_000)
        val candidate = DiaryMoment(id = "aa", sentAt = 100, occurredAt = 1_000)
        val tied = diaryNeighbors(listOf(b, a), candidate, "occurred", 2_000)
        assertEquals(a, tied.previous)
        assertEquals(b, tied.next)
        val fresh = diaryNeighbors(listOf(a), DiaryMoment(id = "new"), "occurred", 500)
        assertNull(fresh.previous)
        assertEquals(a, fresh.next)
    }

    @Test fun dateTimeInputRejectsInvalidSecondsAndPreservesFullDate() {
        assertNotNull(diaryDateTimeMillis("2026-10-05", "14", "15", "37"))
        assertNull(diaryDateTimeMillis("2026-10-05", "14", "15", "60"))
        assertNull(diaryDateTimeMillis("2026-02-30", "14", "15", "37"))
    }
}
