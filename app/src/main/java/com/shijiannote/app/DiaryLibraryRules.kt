package com.shijiannote.app

import com.shijiannote.app.data.NoteNode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Today has two entrances; dates with retained inbox items remain accessible from history. */
object DiaryLibraryRules {
    fun isVisible(note: NoteNode): Boolean = note.kind == "diary" && note.deletedAt == null &&
        (note.hasDiaryContent() || note.diaryInboxItems().isNotEmpty())

    fun publishedTags(note: NoteNode): List<String> =
        publishedTags(note.tags, note.diaryMoments().map { it.tags })

    fun publishedTags(summary: String, moments: List<String>): List<String> =
        (listOf(summary) + moments).flatMap(TagRules::names).distinct()

    fun inboxOnlyLabel(note: NoteNode): String? =
        inboxOnlyLabel(note.hasDiaryContent(), note.diaryInboxItems().map { it.status })

    fun inboxOnlyLabel(hasPublished: Boolean, statuses: List<String>): String? = when {
        hasPublished || statuses.isEmpty() -> null
        statuses.all { it == "draft" } -> "仅有草稿"
        else -> "仅有收纳"
    }

    fun isOnDate(note: NoteNode, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        Instant.ofEpochMilli(note.day ?: note.createdAt).atZone(zone).toLocalDate() == date

    fun list(entries: List<NoteNode>, today: LocalDate, favorites: Boolean = false, selecting: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault()): List<NoteNode> = entries.filter {
        isVisible(it) && (!favorites || it.favorite) && (favorites || selecting || !isOnDate(it, today, zone))
    }

    fun summaryPreview(note: NoteNode): String = preview(note.text, note.document)

    fun momentPreview(moment: DiaryMoment): String = moment.title.takeIf { it.isNotBlank() }
        ?: preview(moment.text, moment.document).ifBlank { "一个瞬间" }

    private fun preview(text: String, document: String): String {
        text.lineSequence().firstOrNull { it.isNotBlank() }?.let { return it.trim().take(160) }
        // Attachments deserve a preview even when a record has no plain text.
        return runCatching {
            decodeBlocks(document).firstOrNull { it.text.isNotBlank() || it.uri.isNotBlank() || it.target.isNotBlank() }?.let {
                it.text.takeIf { text -> text.isNotBlank() }?.trim()?.take(160) ?: when (it.type) {
                    "image" -> "一张照片"
                    "audio" -> "一段语音"
                    "file" -> "一个文件"
                    "link" -> "一个链接"
                    else -> "一条记录"
                }
            }.orEmpty()
        }.getOrDefault("")
    }
}
