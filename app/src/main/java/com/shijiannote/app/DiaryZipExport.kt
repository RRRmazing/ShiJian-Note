package com.shijiannote.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.shijiannote.app.data.NoteNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DiaryZipOptions(val road: Boolean = true, val diary: Boolean = true, val format: String = "html", val sort: String = "occurred")
data class DiaryZipCounts(val selected: Int, val road: Int, val diary: Int, val included: Int)

/** Portable reading export. Inbox contents belong only to a complete backup. */
object DiaryZipExport {
    fun hasRoad(note: NoteNode): Boolean = note.diaryMoments().any { it.asNote(note).hasNoteContent() }
    fun hasSummary(note: NoteNode): Boolean = note.hasNoteContent()
    fun counts(entries: List<NoteNode>, options: DiaryZipOptions = DiaryZipOptions()): DiaryZipCounts {
        val notes = entries.filter { it.kind == "diary" && it.deletedAt == null }
        return DiaryZipCounts(notes.size, notes.count(::hasRoad), notes.count(::hasSummary),
            notes.count { options.road && hasRoad(it) || options.diary && hasSummary(it) })
    }
    private fun html(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    private fun md(text: String) = text.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("*", "\\*").replace("_", "\\_")
    private fun htmlInline(block: NoteBlock, start: Int = 0, end: Int = block.text.length): String {
        val marks = block.textMarks()
        val cuts = (listOf(start, end) + marks.flatMap { listOf(it.start, it.end) }.filter { it > start && it < end }).distinct().sorted()
        return cuts.zipWithNext().joinToString("") { (from, to) ->
            var part = html(block.text.substring(from, to)).replace("\n", "<br>")
            if (block.bold || marks.any { it.style == "b" && it.start <= from && it.end >= to }) part = "<strong>$part</strong>"
            if (block.italic || marks.any { it.style == "i" && it.start <= from && it.end >= to }) part = "<em>$part</em>"
            if (block.display == "highlight" || marks.any { it.style == "h" && it.start <= from && it.end >= to }) part = "<mark>$part</mark>"
            part
        }
    }
    private fun date(note: NoteNode): String = Instant.ofEpochMilli(note.day ?: note.createdAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    private fun time(value: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()))
    private fun extension(context: Context, block: NoteBlock): String {
        val sourceName = runCatching {
            context.contentResolver.query(Uri.parse(block.uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        val fromSourceName = sourceName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
        val fromName = block.text.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
        val fromUri = Uri.parse(block.uri).lastPathSegment?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
        val mime = block.mime.ifBlank { runCatching { context.contentResolver.getType(Uri.parse(block.uri)) }.getOrNull().orEmpty() }
        return fromSourceName ?: fromUri ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: fromName ?: "bin"
    }
    private fun backgroundAsset(theme: String): String? = when (theme) {
        "spring", "chun-jing" -> "diary-backgrounds/spring.webp"
        "autumn", "qiu-mo" -> "diary-backgrounds/autumn.webp"
        "winter", "dong-tu" -> "diary-backgrounds/winter.webp"
        else -> "diary-backgrounds/summer.webp"
    }

    suspend fun export(context: Context, selected: List<NoteNode>, all: List<NoteNode>, options: DiaryZipOptions): ExportResult = withContext(Dispatchers.IO) {
        require(options.road || options.diary) { "至少选择小路或日记" }
        require(options.format in setOf("html", "md", "txt")) { "不支持的阅读格式" }
        require(options.sort in setOf("occurred", "sent")) { "不支持的时间排序" }
        val notes = selected.filter { it.kind == "diary" && it.deletedAt == null && (options.road && hasRoad(it) || options.diary && hasSummary(it)) }
            .sortedWith(compareBy<NoteNode> { it.day ?: it.createdAt }.thenBy { it.id })
        require(notes.isNotEmpty()) { "所选日期没有可导出的内容" }
        val dates = notes.map(::date).distinct()
        val title = if (dates.size == 1) "时笺日记_${dates.single()}" else "时笺日记_${dates.first()}至${dates.last()}_共${dates.size}天"
        val file = File(File(context.cacheDir, "exports").apply { mkdirs() }, "$title-${UUID.randomUUID().toString().take(8)}.zip")
        val manifest = JSONObject().put("format", "shijian-diary-reading").put("version", 1).put("readingFormat", options.format)
            .put("sort", options.sort).put("timezone", ZoneId.systemDefault().id).put("createdAt", System.currentTimeMillis())
        val dateRecords = JSONArray()
        val navigation = mutableListOf<Pair<String, List<Pair<String, String>>>>()
        var mediaCount = 0
        val dateFrequency = notes.groupingBy(::date).eachCount()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                fun write(path: String, content: String) {
                    zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                }
                notes.forEachIndexed { dayIndex, note ->
                    val day = date(note)
                    // Legacy duplicate dates remain distinct instead of overwriting archive entries.
                    val directoryDay = day + if (dateFrequency.getValue(day) > 1) "-${dayIndex + 1}" else ""
                    val links = mutableListOf<Pair<String, String>>()
                    val record = JSONObject().put("id", note.id).put("date", day).put("title", note.title).put("tags", note.tags).put("mood", note.mood)
                    fun section(part: String, moments: List<DiaryMoment>?, blocks: List<NoteBlock>) {
                        val directory = "$part/$directoryDay"
                        val assets = mutableMapOf<String, String>()
                        var number = 0
                        fun copyMedia(block: NoteBlock, background: Boolean = false): String {
                            assets[block.uri]?.let { return it }
                            val suffix = extension(context, block)
                            val label = block.text.ifBlank { block.type }.codePoints().limit(25).toArray().joinToString("") { String(Character.toChars(it)) }
                            val safeName = ExportEngine.clean(label).replace('#', '_').replace('%', '_')
                            val path = if (background) "$directory/background.$suffix" else "$directory/media/${(++number).toString().padStart(3, '0')}-$safeName.$suffix"
                            val stream = runCatching { context.contentResolver.openInputStream(Uri.parse(block.uri)) }.getOrNull()
                                ?: error("素材无法读取：${block.text.ifBlank { block.type }}。请重新绑定后重试。")
                            stream.use { input -> zip.putNextEntry(ZipEntry(path)); input.copyTo(zip); zip.closeEntry() }
                            assets[block.uri] = path; mediaCount++
                            return path
                        }
                        val actualBlocks = moments?.flatMap { decodeBlocks(it.document, it.text) } ?: blocks
                        actualBlocks.filter { it.type in setOf("image", "audio", "file") && it.uri.isNotBlank() }.forEach { copyMedia(it) }
                        // A media block without a URI must be reported, never silently exported as success.
                        require(actualBlocks.none { it.type in setOf("image", "audio", "file") && it.uri.isBlank() }) { "存在未绑定的图片、语音或文件，请补充素材后重试。" }
                        var backgroundPath: String? = null
                        if (part == "road") {
                            if (note.diaryRoadBackground.isNotBlank()) backgroundPath = copyMedia(NoteBlock(type = "image", text = "小路背景", uri = note.diaryRoadBackground), true)
                            else backgroundAsset(note.diaryRoadTheme)?.let { asset ->
                                val path = "$directory/background.${asset.substringAfterLast('.')}"
                                context.assets.open(asset).use { input -> zip.putNextEntry(ZipEntry(path)); input.copyTo(zip); zip.closeEntry() }
                                backgroundPath = path; mediaCount++
                            }
                        }
                        val documentPath = "$directory/index.${options.format}"
                        fun local(path: String) = path.removePrefix("$directory/")
                        fun blockRecords(content: List<NoteBlock>): JSONArray = JSONArray().apply {
                            content.forEach { b ->
                                val description = jsonObject(b)
                                description.remove("uri")
                                put(description.put("mediaPath", assets[b.uri] ?: JSONObject.NULL))
                            }
                        }
                        val sectionManifest = JSONObject().put("document", documentPath)
                        if (moments != null) {
                            sectionManifest.put("enabled", note.diaryRoadEnabled).put("theme", note.diaryRoadTheme).put("layout", note.diaryRoadLayout)
                                .put("background", backgroundPath ?: JSONObject.NULL).put("sort", options.sort)
                                .put("moments", JSONArray().apply { moments.forEach { moment ->
                                    put(JSONObject().put("id", moment.id).put("title", moment.title).put("tags", moment.tags).put("occurredAt", moment.occurrenceTime).put("sentAt", moment.sendTime)
                                        .put("occurredTime", time(moment.occurrenceTime)).put("sentTime", time(moment.sendTime)).put("blocks", blockRecords(decodeBlocks(moment.document, moment.text))))
                                } })
                        } else sectionManifest.put("blocks", blockRecords(blocks))
                        record.put(part, sectionManifest)
                        val content = buildString {
                            if (options.format == "html") {
                                append("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>${html(day)}</title>")
                                append("<style>body{font-family:system-ui,sans-serif;background:#f4f6ee;color:#293445;max-width:760px;margin:auto;padding:24px;line-height:1.75}article{background:#fffef9;border:1px solid #d6dfcd;border-radius:18px;padding:20px;margin:20px 0;overflow-wrap:anywhere}img{max-width:100%;height:auto}audio{max-width:100%}pre{white-space:pre-wrap}small{color:#5d6e75}a{color:#365da0}blockquote{border-left:3px solid #a6b99d;padding-left:14px}</style>")
                                append("<nav><a href=\"../../index.html\">返回日期目录</a></nav><h1>${html(day)} · ${if (part == "road") "经历小路" else "日记"}</h1>")
                                if (note.title.isNotBlank()) append("<p>${html(note.title)}</p>")
                                if (note.tags.isNotBlank() || note.mood.isNotBlank()) append("<small>${html(listOf(note.tags, note.mood).filter(String::isNotBlank).joinToString(" · "))}</small>")
                                backgroundPath?.let { append("<p><a href=\"${html(local(it))}\">查看小路背景</a></p>") }
                            } else {
                                if (options.format == "md") append("# $day · ${if (part == "road") "经历小路" else "日记"}\n\n[返回日期目录](../../index.md)\n\n")
                                else append("$day · ${if (part == "road") "经历小路" else "日记"}\n返回日期目录：../../index.txt\n\n")
                                if (note.title.isNotBlank()) append("${note.title}\n\n")
                                if (note.tags.isNotBlank()) append("标签：${note.tags}\n")
                                if (note.mood.isNotBlank()) append("心情：${note.mood}\n")
                            }
                            fun render(content: List<NoteBlock>) { content.forEach { b -> append(renderBlock(b, options.format, assets[b.uri]?.let(::local), all)) } }
                            if (moments != null) moments.forEach { moment ->
                                if (options.format == "html") append("<article><h2>${html(moment.title.ifBlank { "随记片段" })}</h2><small>发生：${html(time(moment.occurrenceTime))}<br>发送：${html(time(moment.sendTime))}</small>")
                                else append("\n${if (options.format == "md") "## " else ""}${moment.title.ifBlank { "随记片段" }}\n发生：${time(moment.occurrenceTime)}\n发送：${time(moment.sendTime)}\n\n")
                                val labels = TagRules.names(moment.tags).joinToString(" · ") { "#$it" }
                                if (labels.isNotBlank()) {
                                    if (options.format == "html") append("<p><small>${html(labels)}</small></p>")
                                    else append("标签：$labels\n\n")
                                }
                                render(decodeBlocks(moment.document, moment.text))
                                if (options.format == "html") append("</article>")
                            } else { if (options.format == "html") append("<article>"); render(blocks); if (options.format == "html") append("</article>") }
                            if (options.format == "html") append("</html>")
                        }
                        write(documentPath, content)
                        links += (if (part == "road") "经历小路" else "日记") to documentPath
                    }
                    if (options.road && hasRoad(note)) section("road", sortedDiaryMoments(note.diaryMoments().filter { it.asNote(note).hasNoteContent() }, options.sort), emptyList())
                    if (options.diary && hasSummary(note)) section("diary", null, note.blocks())
                    dateRecords.put(record); navigation += day to links
                }
                manifest.put("dates", dateRecords)
                write("manifest.json", manifest.toString(2))
                val index = when (options.format) {
                    "html" -> "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>时笺日记</title><style>body{font-family:system-ui,sans-serif;max-width:760px;margin:auto;padding:24px;line-height:1.8}a{color:#365da0}</style><h1>时笺日记</h1>" + navigation.joinToString("") { (day, links) -> "<h2>${html(day)}</h2><ul>" + links.joinToString("") { (label, path) -> "<li><a href=\"${html(path)}\">${html(label)}</a></li>" } + "</ul>" } + "</html>"
                    "md" -> "# 时笺日记\n\n" + navigation.joinToString("\n\n") { (day, links) -> "## $day\n\n" + links.joinToString("\n") { (label, path) -> "- [$label](<$path>)" } }
                    else -> "时笺日记\n\n" + navigation.joinToString("\n\n") { (day, links) -> "$day\n" + links.joinToString("\n") { (label, path) -> "$label：$path" } }
                }
                write("index.${options.format}", index)
            }
            ExportResult(file, emptyList(), mediaCount)
        } catch (failure: Exception) { file.delete(); throw failure }
    }

    private fun renderBlock(block: NoteBlock, format: String, path: String?, all: List<NoteNode>): String {
        val text = block.text
        if (block.type == "link") {
            val label = all.find { it.id == block.target && it.deletedAt == null }?.displayTitle() ?: text.ifBlank { "关联记录已删除" }
            return if (format == "html") "<p>关联记录：${html(label)}（未包含关联正文）</p>" else "关联记录：$label（未包含关联正文）\n\n"
        }
        if (block.type in setOf("image", "audio", "file")) {
            val media = requireNotNull(path) { "素材没有成功打包" }
            val label = text.ifBlank { when (block.type) { "image" -> "图片"; "audio" -> "语音"; else -> "文件" } }
            return when (format) {
                "html" -> when (block.type) {
                    "image" -> "<figure><img src=\"${html(media)}\" alt=\"${html(label)}\"><figcaption>${html(label)}</figcaption></figure>"
                    "audio" -> "<p>${html(label)} · ${audioTime(block.duration)}</p><audio controls src=\"${html(media)}\"></audio><p><a href=\"${html(media)}\">打开原语音文件</a></p>"
                    else -> "<p><a href=\"${html(media)}\">${html(label)}</a></p>"
                }
                "md" -> "${if (block.type == "image") "!" else ""}[${md(label)}](<$media>)\n\n"
                else -> "$label：$media${if (block.type == "audio") " · ${audioTime(block.duration)}" else ""}\n\n"
            }
        }
        if (format == "txt") return (if (block.type == "check") if (block.checked) "[x] " else "[ ] " else if (block.type == "bullet") "• " else "") + text + "\n\n"
        if (format == "md") {
            val styled = block.markdownText(::md).let { if (block.bold) "**$it**" else it }.let { if (block.italic) "*$it*" else it }
            return when (block.type) {
                "heading1" -> "### $styled\n\n"
                "heading2" -> "#### $styled\n\n"
                "quote" -> styled.lines().joinToString("\n") { "> $it" } + "\n\n"
                "bullet" -> styled.lines().joinToString("\n") { "- $it" } + "\n\n"
                "check" -> "- [${if (block.checked) "x" else " "}] $styled\n\n"
                "code" -> { val fence = "`".repeat(maxOf(3, Regex("`+").findAll(text).maxOfOrNull { it.value.length + 1 } ?: 3)); "$fence\n$text\n$fence\n\n" }
                else -> "$styled\n\n"
            }
        }
        val styled = htmlInline(block)
        return when (block.type) {
            "heading1" -> "<h3>$styled</h3>"
            "heading2" -> "<h4>$styled</h4>"
            "quote" -> "<blockquote>$styled</blockquote>"
            "code" -> "<pre><code>${html(text)}</code></pre>"
            "bullet" -> {
                var cursor = 0
                "<ul>" + text.split('\n').joinToString("") { line ->
                    val rendered = "<li>${htmlInline(block, cursor, cursor + line.length)}</li>"
                    cursor += line.length + 1; rendered
                } + "</ul>"
            }
            "check" -> "<p>${if (block.checked) "☑" else "☐"} $styled</p>"
            else -> "<p>$styled</p>"
        }
    }
}
