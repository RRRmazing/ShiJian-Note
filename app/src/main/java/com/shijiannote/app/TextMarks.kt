package com.shijiannote.app

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

// A compact, JSON-safe range representation; unknown or out-of-bounds ranges are ignored.
data class TextMark(val start: Int, val end: Int, val style: String)
fun NoteBlock.textMarks(): List<TextMark> = marks.split(';').mapNotNull { value ->
    val parts = value.split(':')
    if (parts.size != 3) return@mapNotNull null
    val start = parts[0].toIntOrNull() ?: return@mapNotNull null
    val end = parts[1].toIntOrNull() ?: return@mapNotNull null
    if (start < 0 || end > text.length || start >= end || parts[2] !in setOf("b", "i")) null else TextMark(start, end, parts[2])
}
private fun encodedMarks(marks: List<TextMark>) = marks.joinToString(";") { "${it.start}:${it.end}:${it.style}" }
fun NoteBlock.toggleMark(start: Int, end: Int, style: String): NoteBlock {
    if (start == end) return if (style == "b") copy(bold = !bold) else copy(italic = !italic)
    val rangeStart = minOf(start, end); val rangeEnd = maxOf(start, end)
    val old = textMarks()
    val removing = old.any { it.style == style && it.start <= rangeStart && it.end >= rangeEnd }
    val next = old.flatMap { mark ->
        if (mark.style != style || mark.end <= rangeStart || mark.start >= rangeEnd) listOf(mark)
        else listOfNotNull(mark.takeIf { it.start < rangeStart }?.copy(end = rangeStart), mark.takeIf { it.end > rangeEnd }?.copy(start = rangeEnd))
    }.toMutableList()
    if (!removing) next.add(TextMark(rangeStart, rangeEnd, style))
    return copy(marks = encodedMarks(next))
}
fun NoteBlock.editText(next: String): NoteBlock {
    if (next == text) return this
    var prefix = 0
    while (prefix < minOf(next.length, text.length) && next[prefix] == text[prefix]) prefix++
    var suffix = 0
    while (suffix < minOf(next.length, text.length) - prefix && next[next.lastIndex - suffix] == text[text.lastIndex - suffix]) suffix++
    val oldEnd = text.length - suffix; val newEnd = next.length - suffix
    val delta = next.length - text.length
    val adjusted = textMarks().mapNotNull { mark ->
        val start = when { mark.start < prefix -> mark.start; mark.start >= oldEnd -> mark.start + delta; else -> prefix }
        val end = when { mark.end <= prefix -> mark.end; mark.end >= oldEnd -> mark.end + delta; else -> newEnd }
        if (start >= end) null else mark.copy(start = start, end = end)
    }
    return copy(text = next, marks = encodedMarks(adjusted))
}
fun NoteBlock.richText(query: String = ""): AnnotatedString = AnnotatedString.Builder(highlightText(text, query)).apply {
    textMarks().forEach { mark -> addStyle(if (mark.style == "b") SpanStyle(fontWeight = FontWeight.SemiBold) else SpanStyle(fontStyle = FontStyle.Italic), mark.start, mark.end) }
}.toAnnotatedString()

fun NoteBlock.markdownText(escape: (String) -> String): String {
    val marks = textMarks()
    if (marks.isEmpty()) return escape(text)
    val cuts = (listOf(0, text.length) + marks.flatMap { listOf(it.start, it.end) }).distinct().sorted()
    return cuts.zipWithNext().joinToString("") { (start, end) ->
        var part = escape(text.substring(start, end))
        if (marks.any { it.style == "b" && it.start <= start && it.end >= end }) part = "**$part**"
        if (marks.any { it.style == "i" && it.start <= start && it.end >= end }) part = "*$part*"
        part
    }
}
