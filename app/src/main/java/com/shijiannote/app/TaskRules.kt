package com.shijiannote.app

import com.shijiannote.app.data.TodoItem
import java.time.Instant
import java.time.ZoneId
import com.shijiannote.app.data.TodoBoard

fun planDeadline(day: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(day).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

fun isUnifiedBoard(board: TodoBoard): Boolean = board.timeMode == "UNIFIED" && board.boardType != "DAILY"

fun effectiveTaskDeadline(item: TodoItem, board: TodoBoard, zone: ZoneId = ZoneId.systemDefault()): Long? =
    if (isUnifiedBoard(board)) board.dueDate else item.dueAt ?: item.plannedDay?.let { planDeadline(it, zone) }

fun reminderRuleLabel(rule: String?, customDays: Int = 0): String = when (rule) {
    "WEEKDAYS" -> "工作日"
    "WEEKENDS" -> "周末"
    "DAILY" -> "每天"
    "EVERY_3_DAYS" -> "每三天"
    "WEEKLY" -> "每周"
    "SEMI_MONTHLY" -> "每半月"
    "MONTHLY" -> "每月"
    "YEARLY" -> "每年"
    "CUSTOM_DAYS" -> "每${customDays.coerceAtLeast(1)}天"
    else -> "单次"
}

/** Compute from the original anchor, so skips and short months never shift the rhythm. */
internal fun reminderOccurrenceAfter(base: Long, rule: String, customDays: Int, afterTime: Long, zone: ZoneId): Long? {
    val anchor = Instant.ofEpochMilli(base).atZone(zone)
    val after = Instant.ofEpochMilli(afterTime).atZone(zone)
    val days = java.time.temporal.ChronoUnit.DAYS.between(anchor.toLocalDate(), after.toLocalDate()).coerceAtLeast(0)
    if (rule == "WEEKDAYS" || rule == "WEEKENDS") {
        var date = anchor.plusDays(days)
        repeat(8) {
            val weekday = date.dayOfWeek.value <= 5
            if (date.toInstant().toEpochMilli() > afterTime && (if (rule == "WEEKDAYS") weekday else !weekday)) return date.toInstant().toEpochMilli()
            date = date.plusDays(1)
        }
        return null
    }
    val step = when (rule) {
        "DAILY" -> 1L
        "EVERY_3_DAYS" -> 3L
        "WEEKLY" -> 7L
        "SEMI_MONTHLY" -> 15L
        "CUSTOM_DAYS" -> customDays.coerceAtLeast(1).toLong()
        else -> null
    }
    if (step != null) {
        var index = days / step
        var date = anchor.plusDays(index * step)
        if (date.toInstant().toEpochMilli() <= afterTime) date = anchor.plusDays(++index * step)
        return date.toInstant().toEpochMilli()
    }
    var index = when (rule) {
        "MONTHLY" -> java.time.temporal.ChronoUnit.MONTHS.between(java.time.YearMonth.from(anchor), java.time.YearMonth.from(after)).coerceAtLeast(0)
        "YEARLY" -> (after.year.toLong() - anchor.year).coerceAtLeast(0)
        else -> return null
    }
    fun occurrence(i: Long) = if (rule == "MONTHLY") anchor.plusMonths(i) else anchor.plusYears(i)
    var date = occurrence(index)
    if (date.toInstant().toEpochMilli() <= afterTime) date = occurrence(++index)
    return date.toInstant().toEpochMilli()
}

private fun nextReminder(at: Long?, base: Long?, rule: String?, customDays: Int, skipAt: Long?, deadline: Long?, triggered: Boolean, afterTime: Long, zone: ZoneId, includeSkipped: Boolean = false): Long? {
    if (deadline != null && deadline < afterTime) return null
    val next = if (rule == null) {
        if (triggered) null else at?.takeIf { it > afterTime }
    } else {
        val anchor = base ?: at ?: return null
        var candidate = reminderOccurrenceAfter(anchor, rule, customDays, afterTime, zone) ?: return null
        if (!includeSkipped && candidate == skipAt) candidate = reminderOccurrenceAfter(anchor, rule, customDays, candidate, zone) ?: return null
        candidate
    }
    return next?.takeIf { deadline == null || it <= deadline }
}

fun nextBoardReminderAt(board: TodoBoard, afterTime: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long? {
    if (!isUnifiedBoard(board) || board.archived || board.deletedAt != null) return null
    val at = board.reminderAt?.minus(board.reminderDays * 86_400_000L + board.reminderHours * 3_600_000L + board.reminderMinutes * 60_000L)
    return nextReminder(at, board.reminderBaseAt, board.reminderRule, board.reminderCustomDays, board.reminderSkipAt, board.dueDate, board.reminderTriggered, afterTime, zone)
}

fun nextTaskReminderAt(item: TodoItem, board: TodoBoard, afterTime: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long? {
    if (item.completed || item.deletedAt != null || board.archived || board.deletedAt != null) return null
    if (isUnifiedBoard(board)) return nextBoardReminderAt(board, afterTime, zone)
    val at = item.reminderAt?.minus(item.reminderHours * 3_600_000L + item.reminderMinutes * 60_000L)
    return nextReminder(at, item.reminderBaseAt, item.reminderRule, item.reminderCustomDays, item.reminderSkipAt, effectiveTaskDeadline(item, board, zone), item.reminderTriggered, afterTime, zone)
}

/** View membership includes a skipped notification and earlier reminders on the same day. */
fun taskReminderOnDay(item: TodoItem, board: TodoBoard, day: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
    if (item.deletedAt != null || board.deletedAt != null || board.archived) return null
    val date = Instant.ofEpochMilli(day).atZone(zone).toLocalDate()
    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val unified = isUnifiedBoard(board)
    val at = if (unified) board.reminderAt?.minus(board.reminderDays * 86_400_000L + board.reminderHours * 3_600_000L + board.reminderMinutes * 60_000L)
        else item.reminderAt?.minus(item.reminderHours * 3_600_000L + item.reminderMinutes * 60_000L)
    val next = nextReminder(at, if (unified) board.reminderBaseAt else item.reminderBaseAt,
        if (unified) board.reminderRule else item.reminderRule, if (unified) board.reminderCustomDays else item.reminderCustomDays,
        null, effectiveTaskDeadline(item, board, zone), false, start - 1, zone, includeSkipped = true)
    return next?.takeIf { it < end }
}

fun taskInView(item: TodoItem, board: TodoBoard, view: String, now: Long, zone: ZoneId = ZoneId.systemDefault(), includeCompleted: Boolean = false): Boolean {
    if (item.deletedAt != null || board.deletedAt != null) return false
    if (view == "history") return board.archived
    if (board.archived) return false
    if (view == "lists") return board.boardType != "DAILY"
    if (item.completed && !includeCompleted) return false
    val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val today = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val tomorrow = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val afterTomorrow = day.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
    val deadline = effectiveTaskDeadline(item, board, zone)
    val expired = deadline?.let { it < now } == true
    return when (view) {
        "today" -> !expired && (item.plannedDay == today || deadline?.let { it in today until tomorrow } == true || taskReminderOnDay(item, board, today, zone) != null)
        "tomorrow" -> !expired && (item.plannedDay == tomorrow || deadline?.let { it in tomorrow until afterTomorrow } == true || taskReminderOnDay(item, board, tomorrow, zone) != null)
        "overdue" -> expired
        "important" -> item.important
        else -> true
    }
}

/** Match unchanged rows first, so inserting a row cannot steal a later row's state. */
fun matchTaskEdits(old: List<TodoItem>, values: List<String>): List<TodoItem?> {
    val unused = old.sortedBy { it.position }.toMutableList()
    val result = MutableList<TodoItem?>(values.size) { null }
    values.forEachIndexed { i, value -> unused.firstOrNull { it.text == value }?.let { result[i] = it; unused.remove(it) } }
    values.indices.filter { result[it] == null }.forEach { i ->
        unused.firstOrNull { it.position == i }?.let { result[i] = it; unused.remove(it) }
    }
    return result
}
