package com.shijiannote.app

import com.shijiannote.app.data.NoteNode
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DiaryRecallRulesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 5)
    private fun diary(date: LocalDate, id: String = date.toString()) = NoteNode(id = id, kind = "diary", day = date.atStartOfDay(zone).toInstant().toEpochMilli())

    @Test fun recallIncludesTodayButNotificationNeedsPreviousYears() {
        val current = diary(today)
        assertEquals(listOf(current), DiaryRecallRules.recalled(listOf(current), today, zone))
        assertFalse(DiaryRecallRules.hasPreviousYear(listOf(current), today, zone))
        val prior = diary(today.minusYears(1))
        assertTrue(DiaryRecallRules.hasPreviousYear(listOf(current, prior), today, zone))
        assertEquals(listOf(current, prior), DiaryRecallRules.recalled(listOf(prior, current), today, zone))
    }

    @Test fun recallExcludesTrashMemoriesOtherDaysAndFutureYears() {
        val prior = diary(today.minusYears(2))
        val entries = listOf(prior, diary(today.minusYears(1)).copy(deletedAt = 1), diary(today.minusYears(3)).copy(kind = "memory"), diary(today.minusDays(1)), diary(today.plusYears(1)), diary(today).copy(day = null))
        assertEquals(listOf(prior), DiaryRecallRules.recalled(entries, today, zone))
        assertFalse(DiaryRecallRules.hasPreviousYear(entries - prior, today, zone))
    }

    @Test fun independentReadDatesOnlyClearTheirOwnBadgeUntilNextDay() {
        val date = today.toString()
        var calendarRead: String? = null
        var recallRead: String? = null
        assertTrue(DiaryRecallRules.unread(true, date, calendarRead, today))
        assertTrue(DiaryRecallRules.unread(true, date, recallRead, today))
        calendarRead = date
        assertFalse(DiaryRecallRules.unread(true, date, calendarRead, today))
        assertTrue(DiaryRecallRules.unread(true, date, recallRead, today))
        recallRead = date
        assertFalse(DiaryRecallRules.unread(true, date, recallRead, today))
        val tomorrow = today.plusDays(1)
        assertTrue(DiaryRecallRules.unread(true, tomorrow.toString(), calendarRead, tomorrow))
        assertTrue(DiaryRecallRules.unread(true, tomorrow.toString(), recallRead, tomorrow))
    }

    @Test fun notifyingIsIdempotentPerDayAndReadingRecallSuppressesPendingPush() {
        val date = today.toString()
        assertTrue(DiaryRecallRules.shouldNotify(true, date, null, null, today))
        assertFalse(DiaryRecallRules.shouldNotify(true, date, date, null, today))
        assertFalse(DiaryRecallRules.shouldNotify(true, date, null, date, today))
        assertFalse(DiaryRecallRules.shouldNotify(false, date, null, null, today))
        assertFalse(DiaryRecallRules.shouldNotify(true, today.minusDays(1).toString(), null, null, today))
        val tomorrow = today.plusDays(1)
        assertTrue(DiaryRecallRules.shouldNotify(true, tomorrow.toString(), date, date, tomorrow))
    }

    @Test fun leapDayMatchesExactlyWithoutSubstitutingFebruary28() {
        val leapDay = LocalDate.of(2024, 2, 29)
        val previous = diary(LocalDate.of(2020, 2, 29))
        assertTrue(DiaryRecallRules.hasPreviousYear(listOf(previous), leapDay, zone))
        assertTrue(DiaryRecallRules.recalled(listOf(previous), LocalDate.of(2026, 2, 28), zone).isEmpty())
    }

    @Test fun schedulingTargetsLocalMidnightAcrossDaylightSavingAndTimezone() {
        val dstZone = ZoneId.of("America/New_York")
        val morning = LocalDate.of(2026, 3, 8).atStartOfDay(dstZone).toInstant().toEpochMilli()
        assertEquals(23 * 3_600_000L, DiaryRecallRules.delayUntilNextDay(morning, dstZone))
        val evening = today.atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        assertEquals(60_000L, DiaryRecallRules.delayUntilNextDay(evening, zone))
    }
}
