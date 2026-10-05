package com.shijiannote.app

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DiaryNeighbors(val previous: DiaryMoment?, val next: DiaryMoment?)

/** The quick composer may only resume the plain single-block captures it can represent losslessly. */
fun canResumeDiaryTextDraft(moment: DiaryMoment): Boolean = moment.title.isBlank() && runCatching {
    val blocks = decodeBlocks(moment.document, moment.text)
    blocks.size <= 1 && blocks.all { it.type == "text" && it.uri.isBlank() && it.target.isBlank() &&
        !it.bold && !it.italic && !it.checked && !it.collapsed && it.marks.isBlank() }
}.getOrDefault(false)

fun diaryNeighbors(moments: List<DiaryMoment>, candidate: DiaryMoment, order: String, now: Long): DiaryNeighbors {
    val sent = candidate.sentAt ?: now
    val positioned = candidate.copy(sentAt = sent, occurredAt = candidate.occurredAt ?: sent)
    val sorted = sortedDiaryMoments(moments.filterNot { it.id == candidate.id } + positioned, order)
    val index = sorted.indexOfFirst { it.id == candidate.id }
    return DiaryNeighbors(sorted.getOrNull(index - 1), sorted.getOrNull(index + 1))
}

fun diaryTimestamp(at: Long, day: Long? = null): String {
    val dateTime = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val sameDate = day != null && dateTime.toLocalDate() == Instant.ofEpochMilli(day).atZone(ZoneId.systemDefault()).toLocalDate()
    return dateTime.format(DateTimeFormatter.ofPattern(if (sameDate) "HH:mm:ss" else "yyyy年M月d日 HH:mm:ss"))
}

fun diaryDateTimeMillis(date: String, hour: String, minute: String, second: String): Long? = runCatching {
    val d = java.time.LocalDate.parse(date)
    LocalDateTime.of(d, java.time.LocalTime.of(hour.toInt(), minute.toInt(), second.toInt()))
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrNull()
