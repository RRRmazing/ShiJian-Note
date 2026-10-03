package com.shijiannote.app

import com.shijiannote.app.data.TodoItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

fun nextRepeatedTask(item: TodoItem, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): TodoItem {
    val days = item.repeatDays.toLong()
    val original = item.plannedDay?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: today
    val nextDay = maxOf(original, today).plusDays(days)
    fun shift(value: Long?): Long? = value?.let {
        val date = Instant.ofEpochMilli(it).atZone(zone)
        val offset = java.time.temporal.ChronoUnit.DAYS.between(original, nextDay)
        date.plusDays(offset).toInstant().toEpochMilli()
    }
    return item.copy(id = 0, completed = false, repeatSpawned = false, deletedAt = null,
        plannedDay = nextDay.atStartOfDay(zone).toInstant().toEpochMilli(), dueAt = shift(item.dueAt),
        reminderAt = shift(item.reminderAt), reminderTriggered = false)
}
