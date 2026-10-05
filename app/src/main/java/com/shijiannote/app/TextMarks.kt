package com.shijiannote.app

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color

// A compact, JSON-safe range representation; unknown or out-of-bounds ranges are ignored.
data class TextMark(val start: Int, val end: Int, val style: String)
fun NoteBlock.textMarks(): List<TextMark> = marks.split(';').mapNotNull { value ->
    val parts = value.split(':')
    if (parts.size != 3) return@mapNotNull null
    val start = parts[0].toIntOrNull() ?: return@mapNotNull null
    val end = parts[1].toIntOrNull() ?: return@mapNotNull null
    if (start < 0 || end > text.length || start >= end || parts[2] !in setOf("b", "i", "h")) null else TextMark(start, end, parts[2])
}
private fun encodedMarks(marks: List<TextMark>) = marks.joinToString(";") { "${it.start}:${it.end}:${it.style}" }
fun NoteBlock.toggleMark(start: Int, end: Int, style: String): NoteBlock {
    require(style in setOf("b", "i", "h"))
    if (start == end) return when (style) {
        "b" -> copy(bold = !bold)
        "i" -> copy(italic = !italic)
        else -> copy(display = if (display == "highlight") "inherit" else "highlight")
    }
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
    if (display == "highlight" && text.isNotEmpty()) addStyle(SpanStyle(background = Color(0xFFFFE7A0)), 0, text.length)
    textMarks().forEach { mark -> addStyle(when (mark.style) {
        "b" -> SpanStyle(fontWeight = FontWeight.SemiBold)
        "i" -> SpanStyle(fontStyle = FontStyle.Italic)
        else -> SpanStyle(background = Color(0xFFFFE7A0))
    }, mark.start, mark.end) }
}.toAnnotatedString()

/** Explicit typing styles affect newly inserted/replaced text, leaving surrounding ranges intact. */
fun NoteBlock.editText(next: String, typingStyles: Set<String>): NoteBlock {
    val edited = editText(next)
    if (next == text) return edited
    var prefix = 0
    while (prefix < minOf(next.length, text.length) && next[prefix] == text[prefix]) prefix++
    var suffix = 0
    while (suffix < minOf(next.length, text.length) - prefix && next[next.lastIndex - suffix] == text[text.lastIndex - suffix]) suffix++
    val end = next.length - suffix
    if (prefix >= end) return edited
    val marks = edited.textMarks().flatMap { mark ->
        if (mark.end <= prefix || mark.start >= end) listOf(mark)
        else listOfNotNull(mark.takeIf { it.start < prefix }?.copy(end = prefix), mark.takeIf { it.end > end }?.copy(start = end))
    } + typingStyles.filter { it in setOf("b", "i", "h") }.map { TextMark(prefix, end, it) }
    return edited.copy(marks = encodedMarks(marks))
}

fun NoteBlock.markdownText(escape: (String) -> String): String {
    val marks = textMarks()
    if (marks.isEmpty()) return if (display == "highlight") "<mark>${escape(text)}</mark>" else escape(text)
    val cuts = (listOf(0, text.length) + marks.flatMap { listOf(it.start, it.end) }).distinct().sorted()
    return cuts.zipWithNext().joinToString("") { (start, end) ->
        var part = escape(text.substring(start, end))
        if (marks.any { it.style == "b" && it.start <= start && it.end >= end }) part = "**$part**"
        if (marks.any { it.style == "i" && it.start <= start && it.end >= end }) part = "*$part*"
        if (display == "highlight" || marks.any { it.style == "h" && it.start <= start && it.end >= end }) part = "<mark>$part</mark>"
        part
    }
}
