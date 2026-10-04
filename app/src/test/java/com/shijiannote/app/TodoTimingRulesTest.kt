package com.shijiannote.app

import com.shijiannote.app.data.TodoBoard
import com.shijiannote.app.data.TodoItem
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TodoTimingRulesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(day: String, hour: Int = 0, minute: Int = 0): Long =
        LocalDate.parse(day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    private val board = TodoBoard(id = 1, summary = "独立", timeMode = "INDEPENDENT")

    @Test fun dailyWithoutReminderKeepsMembershipAndExpiresAtLocalDayEnd() {
        val task = TodoItem(boardId = 1, text = "整理", plannedDay = at("2026-10-05"))
        assertTrue(taskInView(task, board, "tomorrow", at("2026-10-04", 12), zone))
        assertTrue(taskInView(task, board, "today", at("2026-10-05", 23, 59), zone))
        assertTrue(taskInView(task, board, "overdue", at("2026-10-06"), zone))
        assertNull(taskReminderOnDay(task, board, at("2026-10-05"), zone))
    }

    @Test fun unifiedTimeIgnoresConflictingItemSettingsAndInheritsBoardReminder() {
        val unified = board.copy(timeMode = "UNIFIED", reminderAt = at("2026-10-05", 9), dueDate = at("2026-10-06", 20))
        val task = TodoItem(boardId = 1, text = "统一", reminderAt = at("2026-10-04", 8), dueAt = at("2026-10-04", 7), reminderRule = "DAILY")
        assertEquals(unified.dueDate, effectiveTaskDeadline(task, unified, zone))
        assertEquals(unified.reminderAt, nextTaskReminderAt(task, unified, at("2026-10-04", 12), zone))
        assertTrue(taskInView(task, unified, "tomorrow", at("2026-10-04", 12), zone))
        assertNull(nextBoardReminderAt(board.copy(reminderAt = at("2026-10-05", 9)), at("2026-10-04"), zone))
    }

    @Test fun skippedRepeatKeepsDayMembershipAndOriginalCadence() {
        val base = at("2026-10-04", 9)
        val skip = at("2026-10-07", 9)
        val task = TodoItem(boardId = 1, text = "每三天", reminderAt = base, reminderBaseAt = base, reminderRule = "EVERY_3_DAYS", reminderSkipAt = skip)
        assertEquals(at("2026-10-10", 9), nextTaskReminderAt(task, board, at("2026-10-05"), zone))
        assertEquals(skip, taskReminderOnDay(task, board, at("2026-10-07"), zone))
        assertTrue(taskInView(task, board, "tomorrow", at("2026-10-06", 12), zone))
        assertEquals(skip, nextTaskReminderAt(task.copy(reminderSkipAt = null), board, at("2026-10-05"), zone))
        assertEquals(at("2026-10-10", 9), nextTaskReminderAt(task, board, skip, zone))
    }

    @Test fun cutoffAndCompletionOverrideRepeatsAndReopeningUsesFutureOccurrence() {
        val base = at("2026-10-04", 9)
        val task = TodoItem(boardId = 1, text = "每天", reminderAt = base, reminderBaseAt = base, reminderRule = "DAILY", dueAt = at("2026-10-05", 10))
        assertEquals(at("2026-10-05", 9), nextTaskReminderAt(task, board, base, zone))
        assertNull(nextTaskReminderAt(task, board, at("2026-10-05", 9), zone))
        assertNull(nextTaskReminderAt(task.copy(completed = true), board, base, zone))
        assertNull(nextTaskReminderAt(task, board.copy(archived = true), base, zone))
        assertNull(nextTaskReminderAt(task.copy(deletedAt = base), board, base, zone))
        assertEquals(at("2026-10-05", 9), nextTaskReminderAt(task.copy(completed = false), board, at("2026-10-04", 18), zone))
    }

    @Test fun closingRepeatWithPastBaseDoesNotCreateCatchUpAlarm() {
        val base = at("2026-10-04", 9)
        val closed = TodoItem(boardId = 1, text = "单次", reminderAt = base)
        assertNull(nextTaskReminderAt(closed, board, at("2026-10-05"), zone))
        assertEquals(base, taskReminderOnDay(closed, board, at("2026-10-04"), zone))
        assertEquals(base, taskReminderOnDay(closed.copy(reminderTriggered = true), board, at("2026-10-04"), zone))
    }

    @Test fun monthsAndYearsUseTheOriginalAnchorAfterShortMonths() {
        val monthly = TodoItem(boardId = 1, text = "每月", reminderBaseAt = at("2026-01-31", 9), reminderRule = "MONTHLY")
        assertEquals(at("2026-02-28", 9), nextTaskReminderAt(monthly, board, at("2026-02-01"), zone))
        assertEquals(at("2026-03-31", 9), nextTaskReminderAt(monthly, board, at("2026-02-28", 9), zone))
        val yearly = monthly.copy(reminderBaseAt = at("2024-02-29", 9), reminderRule = "YEARLY")
        assertEquals(at("2028-02-29", 9), nextTaskReminderAt(yearly, board, at("2027-03-01"), zone))
    }

    @Test fun workdaysAndWeekendsIncludeOnlyMatchingDates() {
        val sunday = at("2026-10-04", 9)
        val task = TodoItem(boardId = 1, text = "按星期", reminderBaseAt = sunday, reminderRule = "WEEKDAYS")
        assertEquals(at("2026-10-05", 9), nextTaskReminderAt(task, board, at("2026-10-04"), zone))
        assertEquals(at("2026-10-10", 9), nextTaskReminderAt(task.copy(reminderRule = "WEEKENDS"), board, sunday, zone))
    }

    @Test fun repeatedReminderMaintainsLocalClockAcrossDaylightSaving() {
        val dstZone = ZoneId.of("America/New_York")
        val base = LocalDate.of(2026, 3, 7).atTime(9, 0).atZone(dstZone).toInstant().toEpochMilli()
        val next = LocalDate.of(2026, 3, 8).atTime(9, 0).atZone(dstZone).toInstant().toEpochMilli()
        val task = TodoItem(boardId = 1, text = "每天", reminderBaseAt = base, reminderRule = "DAILY")
        assertEquals(next, nextTaskReminderAt(task, board, base, dstZone))
        assertEquals(23 * 3_600_000L, next - base)
    }
}
