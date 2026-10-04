package com.shijiannote.app

import com.shijiannote.app.data.NoteNode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Calendar anniversaries use the device's local day, including leap-day anniversaries. */
object DiaryRecallRules {
    fun recalled(entries: List<NoteNode>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<NoteNode> =
        entries.filter { entry ->
            if (entry.kind != "diary" || entry.deletedAt != null || entry.day == null) false
            else {
                val date = Instant.ofEpochMilli(entry.day).atZone(zone).toLocalDate()
                date <= today && date.monthValue == today.monthValue && date.dayOfMonth == today.dayOfMonth
            }
        }.sortedByDescending { it.day }

    fun hasPreviousYear(entries: List<NoteNode>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        recalled(entries, today, zone).any { Instant.ofEpochMilli(it.day!!).atZone(zone).year < today.year }

    fun unread(enabled: Boolean, availableDate: String?, readDate: String?, today: LocalDate): Boolean =
        enabled && availableDate == today.toString() && readDate != today.toString()

    fun shouldNotify(enabled: Boolean, availableDate: String?, notifiedDate: String?, recallReadDate: String?, today: LocalDate): Boolean =
        unread(enabled, availableDate, recallReadDate, today) && notifiedDate != today.toString()

    fun delayUntilNextDay(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return (today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - nowMillis).coerceAtLeast(1L)
    }
}
