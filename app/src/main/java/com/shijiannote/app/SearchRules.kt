package com.shijiannote.app

import com.shijiannote.app.data.NoteNode
import com.shijiannote.app.data.ScheduleEvent
import com.shijiannote.app.data.TodoBoardWithItems
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId

data class SearchDateInput(val year: String = "", val month: String = "", val day: String = "") {
    fun complete(withYear: Boolean = true) = (!withYear || year.isNotBlank()) && month.isNotBlank() && day.isNotBlank()
    fun date(): LocalDate? = runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()
    fun monthDay(): MonthDay? = runCatching { MonthDay.of(month.toInt(), day.toInt()) }.getOrNull()
}

data class SearchInput(
    val query: String = "", val markedOnly: Boolean = false, val history: Boolean = false,
    val currentCategory: Boolean = true, val todoScope: String = "all",
    val range: Boolean = false, val start: SearchDateInput = SearchDateInput(), val end: SearchDateInput = SearchDateInput(),
    val certainDay: Boolean = false, val date: SearchDateInput = SearchDateInput()
)
data class SearchNormalization(val input: SearchInput, val error: String? = null)
data class SearchNoteSnapshot(val ownerId: String, val snapshot: NoteNode, val createdAt: Long)
data class SearchNoteHit(val owner: NoteNode, val matched: NoteNode, val versionAt: Long? = null)

object SearchRules {
    /** Clear an incomplete group atomically; complete invalid dates remain editable. */
    fun normalize(input: SearchInput): SearchNormalization {
        val start = if (input.range && input.start.complete()) input.start else SearchDateInput()
        val end = if (input.range && input.end.complete()) input.end else SearchDateInput()
        val day = if (input.certainDay && input.date.complete(false)) input.date.copy(year = "") else SearchDateInput()
        val clean = input.copy(query = input.query.trim(), start = start, end = end, date = day,
            range = input.range && (start.complete() || end.complete()), certainDay = input.certainDay && day.complete(false))
        val error = when {
            start.complete() && (start.year.toIntOrNull() !in 1..9999 || start.date() == null) -> "开始日期不存在"
            end.complete() && (end.year.toIntOrNull() !in 1..9999 || end.date() == null) -> "结束日期不存在"
            day.complete(false) && day.monthDay() == null -> "日期不存在"
            start.date() != null && end.date() != null && start.date()!! > end.date()!! -> "开始日期不能晚于结束日期"
            else -> null
        }
        return SearchNormalization(clean, error)
    }

    fun canSearch(input: SearchInput): Boolean = input.query.isNotBlank() ||
        input.range && (input.start.date() != null || input.end.date() != null) || input.certainDay && input.date.monthDay() != null

    fun dateMatches(time: Long, input: SearchInput, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val date = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
        if (input.range) {
            input.start.date()?.let { if (date < it) return false }
            input.end.date()?.let { if (date > it) return false }
        }
        return !input.certainDay || input.date.monthDay() == MonthDay.from(date)
    }

    fun noteText(node: NoteNode): String = listOf(node.title, node.text, node.tags,
        runCatching { node.blocks().joinToString(" ") { it.text } }.getOrDefault(""),
        runCatching { node.diaryMoments().joinToString("\n") { moment ->
            moment.title + "\n" + moment.text + "\n" + decodeBlocks(moment.document, moment.text).joinToString(" ") { it.text }
        } }.getOrDefault(""),
        runCatching { node.diaryInboxItems().joinToString("\n") { item ->
            (listOfNotNull(item.moment) + decodeDiaryMoments(item.road)).joinToString("\n") { moment ->
                moment.title + "\n" + moment.text + "\n" + decodeBlocks(moment.document, moment.text).joinToString(" ") { it.text }
            }
        } }.getOrDefault("")).joinToString("\n")

    fun notes(nodes: List<NoteNode>, snapshots: List<SearchNoteSnapshot>, module: Int, parent: String?, input: SearchInput, zone: ZoneId = ZoneId.systemDefault()): List<SearchNoteHit> {
        if (!canSearch(input)) return emptyList()
        val allowed = if (module == 3 && parent != null && input.currentCategory) TreeRules.descendants(parent, nodes) - parent else null
        val live = nodes.filter { it.deletedAt == null && !MemorySpaces.isRoot(it.id) &&
            (if (module == 2) it.kind == "diary" else it.kind != "diary") &&
            (allowed == null || it.id in allowed) && (!input.markedOnly || it.favorite) }
        val history = snapshots.groupBy { it.ownerId }
        fun matches(node: NoteNode) = noteText(node).contains(input.query, true) && dateMatches(node.day ?: node.createdAt, input, zone)
        return live.mapNotNull { owner ->
            if (matches(owner)) SearchNoteHit(owner, owner)
            else if (input.history) history[owner.id].orEmpty().sortedByDescending { it.createdAt }
                .firstOrNull { it.snapshot.deletedAt == null && matches(it.snapshot) }?.let { SearchNoteHit(owner, it.snapshot, it.createdAt) }
            else null
        }.sortedWith(if (module == 2) compareByDescending { it.owner.day ?: it.owner.createdAt } else compareByDescending { it.owner.updatedAt })
    }

    fun schedules(events: List<ScheduleEvent>, input: SearchInput, zone: ZoneId = ZoneId.systemDefault()): List<ScheduleEvent> =
        if (!canSearch(input)) emptyList() else events.filter { it.deletedAt == null && (input.history || !it.archived) &&
            (!input.markedOnly || it.important) && (it.title + "\n" + it.note).contains(input.query, true) && dateMatches(it.eventAt, input, zone) }.sortedByDescending { it.eventAt }

    fun todos(groups: List<TodoBoardWithItems>, input: SearchInput, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<TodoBoardWithItems> {
        if (input.query.isBlank()) return emptyList()
        return groups.filter { it.board.deletedAt == null && (input.history || !it.board.archived) }.flatMap { group ->
            val board = group.board
            // Archived rows are searched by their former view membership when explicitly included.
            val membershipBoard = board.copy(archived = false)
            val live = group.items.filter { it.deletedAt == null }
            val scoped = live.filter { item -> when (input.todoScope) {
                "all" -> true
                "lists" -> board.boardType != "DAILY"
                "repeat" -> if (isUnifiedBoard(board)) board.reminderRule != null && !item.completed && nextBoardReminderAt(membershipBoard.copy(reminderSkipAt = null), now, zone) != null
                    else item.reminderRule != null && nextTaskReminderAt(item.copy(reminderSkipAt = null), membershipBoard, now, zone) != null
                else -> taskInView(item, membershipBoard, input.todoScope, now, zone)
            } }
            if (board.boardType == "DAILY") scoped.filter { it.text.contains(input.query, true) }.map { group.copy(items = listOf(it)) }
            else if ((input.todoScope == "all" || input.todoScope == "lists" || scoped.isNotEmpty()) &&
                (board.summary + "\n" + scoped.joinToString("\n") { it.text }).contains(input.query, true)) listOf(group.copy(items = scoped)) else emptyList()
        }
    }
}
