package com.shijiannote.app

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.room.withTransaction
import com.shijiannote.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.*
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.*

data class ExportOptions(val referenceImages: Boolean = false, val referenceFiles: Boolean = false)
data class ExportResult(val file: File, val missing: List<String>, val materialCount: Int)
data class ExportInspection(val folders: Int, val notes: Int, val owned: Int, val images: Int, val files: Int, val missing: List<String>)

object ExportEngine {
    fun scope(ids: Set<String>, all: List<NoteNode>): List<NoteNode> {
        val chosen = ids.flatMap { TreeRules.descendants(it, all) }.toSet()
        return all.filter { it.id in chosen && it.deletedAt == null }
    }
    fun clean(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.').take(55).ifBlank { "未命名" }
    private fun output(context: Context, title: String, extension: String): File = File(File(context.cacheDir, "exports").apply { mkdirs() }, "${clean(title)}-${UUID.randomUUID().toString().take(8)}.$extension")
    private fun readable(context: Context, block: NoteBlock): Boolean = runCatching { context.contentResolver.openInputStream(Uri.parse(block.uri))?.use { true } ?: false }.getOrDefault(false)
    suspend fun inspect(context: Context, selected: List<NoteNode>): ExportInspection = withContext(Dispatchers.IO) {
        val blocks = selected.flatMap { it.blocks() }.filter { it.uri.isNotBlank() }.distinctBy { it.uri }
        ExportInspection(selected.count { it.kind == "folder" }, selected.count { it.kind != "folder" }, blocks.count { it.owned }, blocks.count { !it.owned && it.type == "image" }, blocks.count { !it.owned && it.type != "image" }, blocks.filterNot { readable(context, it) }.map { it.text })
    }
    private fun selectedMaterial(b: NoteBlock, options: ExportOptions): Boolean = b.owned || if (b.type == "image") options.referenceImages else options.referenceFiles
    private fun escape(text: String) = text.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("*", "\\*").replace("_", "\\_")
    fun paths(selected: List<NoteNode>): Map<String, String> {
        val ids = selected.map { it.id }.toSet()
        return selected.associate { n ->
            val ancestors = TreeRules.ancestors(n, selected).filter { it.id in ids }
            val components = (ancestors + n).map { "${clean(it.displayTitle())}-${it.id.replace(Regex("[^a-zA-Z0-9-]"), "_")}" }
            val path = components.joinToString("/")
            n.id to (if (path.length > 200) "深层内容/${components.last()}" else path) + if (n.kind == "folder") "/" else ".md"
        }
    }
    private fun relative(from: String, to: String): String {
        val source = from.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
        val target = to.split('/').filter { it.isNotEmpty() }
        var common = 0
        while (common < minOf(source.size, target.size) && source[common] == target[common]) common++
        return (List(source.size - common) { ".." } + target.drop(common)).joinToString("/")
    }
    fun markdown(node: NoteNode, all: List<NoteNode>, documentPath: String, notePaths: Map<String, String>, assets: Map<String, String>): String = buildString {
        append("# ${escape(node.displayTitle())}\n\n")
        append("路径：${TreeRules.path(node, all)}\n\n")
        if (node.kind == "diary") append("日期：${dateText(node.day ?: node.createdAt)}\n\n")
        if (node.tags.isNotBlank()) append("标签：${node.tags}\n\n")
        node.blocks().forEach { b ->
            var text = if (b.type == "code") b.text else b.markdownText(::escape)
            if (b.bold) text = "**$text**"
            if (b.italic) text = "*$text*"
            when (b.type) {
                "heading1" -> append("## $text\n\n")
                "heading2" -> append("### $text\n\n")
                "bullet" -> append(text.lines().joinToString("\n") { "- $it" } + "\n\n")
                "check" -> append("- [${if (b.checked) "x" else " "}] $text\n\n")
                "quote" -> append(text.lines().joinToString("\n") { "> $it" } + "\n\n")
                "code" -> { val fence = "`".repeat(maxOf(3, Regex("`+").findAll(b.text).maxOfOrNull { it.value.length + 1 } ?: 3)); append("$fence\n${b.text}\n$fence\n\n") }
                "link" -> {
                    val target = all.find { it.id == b.target }
                    val name = target?.displayTitle() ?: b.text
                    val path = notePaths[b.target]
                    if (path != null) append("[${escape(name)}](<${relative(documentPath, path)}>)\n\n")
                    else append("关联记录：${escape(name)}（${target?.let { TreeRules.path(it, all) } ?: "原记录已删除"}；未包含在本次导出范围）\n\n")
                }
                "image", "audio", "file" -> {
                    val path = assets[b.uri]
                    if (path != null) append("${if (b.type == "image") "!" else ""}[$text](<${relative(documentPath, path)}>)${if (b.type == "audio") " · ${audioTime(b.duration)}" else ""}\n\n")
                    else append("${if (b.type == "image") "图片" else if (b.type == "audio") "录音" else "附件"}：$text（${if (b.owned) "素材不可读取" else "仅保留外部引用"}）\n\n原引用：`${b.uri}`\n\n")
                }
                else -> append("$text\n\n")
            }
        }
    }
    suspend fun notes(context: Context, selected: List<NoteNode>, all: List<NoteNode>, options: ExportOptions): ExportResult = withContext(Dispatchers.IO) {
        require(selected.isNotEmpty()) { "没有可导出的内容" }
        val materials = selected.flatMap { it.blocks() }.filter { it.uri.isNotBlank() }.distinctBy { it.uri }
        val included = materials.filter { selectedMaterial(it, options) }
        val missing = materials.filterNot { readable(context, it) }.map { it.text }.toMutableList()
        if (selected.size == 1 && selected.single().kind != "folder" && included.isEmpty()) {
            val file = output(context, selected.single().displayTitle(), "md")
            file.writeText(markdown(selected.single(), all, file.name, mapOf(selected.single().id to file.name), emptyMap()))
            return@withContext ExportResult(file, materials.filterNot { readable(context, it) }.map { it.text }, 0)
        }
        val file = output(context, selected.first().displayTitle(), "zip")
        val paths = paths(selected)
        val assets = mutableMapOf<String, String>()
        ZipOutputStream(file.outputStream()).use { zip ->
            included.forEachIndexed { index, b ->
                val ext = b.uri.substringBefore('?').substringAfterLast('.', "").take(8).filter(Char::isLetterOrDigit)
                val name = "素材/${index + 1}-${clean(b.text)}" + if (ext.isNotBlank() && !b.text.endsWith(".$ext", true)) ".$ext" else ""
                try {
                    val input = context.contentResolver.openInputStream(Uri.parse(b.uri)) ?: error("无法读取")
                    input.use { zip.putNextEntry(ZipEntry(name)); it.copyTo(zip); zip.closeEntry() }
                    assets[b.uri] = name
                } catch (_: Exception) { missing += b.text }
            }
            selected.forEach { n ->
                val path = paths.getValue(n.id)
                zip.putNextEntry(ZipEntry(path))
                if (n.kind != "folder") zip.write(markdown(n, all, path, paths, assets).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            val index = selected.joinToString("\n") { n -> if (n.kind == "folder") "- ${TreeRules.path(n, all)}" else "- [${escape(TreeRules.path(n, all))}](<${paths.getValue(n.id)}>)" }
            zip.putNextEntry(ZipEntry("目录.md")); zip.write(("# 导出目录\n\n$index\n").toByteArray()); zip.closeEntry()
            val refs = materials.filter { it.uri !in assets }.joinToString("\n\n") { "${it.text}\n${if (it.text in missing || !readable(context, it)) "原素材不可访问" else "未复制原文件，仅保留引用"}\n${it.uri}" }
            zip.putNextEntry(ZipEntry("引用说明.md")); zip.write(("# 外部引用与缺失素材\n\n$refs").toByteArray()); zip.closeEntry()
        }
        ExportResult(file, missing.distinct(), assets.size)
    }
    suspend fun pdf(context: Context, selected: List<NoteNode>, all: List<NoteNode>, images: Boolean): ExportResult = withContext(Dispatchers.IO) {
        require(selected.isNotEmpty())
        val file = output(context, selected.first().displayTitle(), "pdf")
        val missing = mutableListOf<String>()
        val pdf = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 52, 73); textSize = 13f }
        var page: PdfDocument.Page? = null
        var count = 0
        var y = 48f
        fun newPage() { page?.let { pdf.finishPage(it) }; page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, ++count).create()); y = 48f; page!!.canvas.drawText("时笺 · ${selected.first().displayTitle().take(30)}", 38f, 24f, paint); page!!.canvas.drawText(count.toString(), 552f, 818f, paint) }
        fun line(text: String, size: Float = 13f, bold: Boolean = false) {
            paint.textSize = size; paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            text.lines().forEach { paragraph ->
                var rest = paragraph
                if (rest.isEmpty()) { y += 12; return@forEach }
                while (rest.isNotEmpty()) {
                    if (page == null || y + size * 1.6f > 794) newPage()
                    val length = paint.breakText(rest, true, 519f, null).coerceAtLeast(1)
                    page!!.canvas.drawText(rest.take(length), 38f, y, paint); y += size * 1.6f; rest = rest.drop(length)
                }
            }
            y += 6
        }
        newPage()
        for (node in selected) {
            if (node.kind == "folder") { line(TreeRules.path(node, all), 20f, true); continue }
            line(node.displayTitle(), 23f, true); line(TreeRules.path(node, all), 10f); if (node.kind == "diary") line(dateText(node.day ?: node.createdAt), 10f)
            for (b in node.blocks()) {
                if (b.type == "image" && images) {
                    val bitmap = loadImage(context, b.uri)
                    if (bitmap == null) { missing += b.text; line("图片不可访问：${b.text}"); continue }
                    val scale = minOf(519f / bitmap.width, 250f / bitmap.height)
                    val height = bitmap.height * scale
                    if (y + height > 780) newPage()
                    page!!.canvas.drawBitmap(bitmap, null, RectF(38f, y, 38f + bitmap.width * scale, y + height), paint); y += height + 8; line(b.text, 10f)
                } else when (b.type) {
                    "heading1" -> line(b.text, 19f, true)
                    "heading2" -> line(b.text, 16f, true)
                    "audio" -> line("录音：${b.text} · ${audioTime(b.duration)}（PDF不包含音频文件）", 11f)
                    "file" -> line("附件：${b.text}（原文件不包含在PDF中）", 11f)
                    "image" -> line("图片：${b.text}（卡片形式）", 11f)
                    "link" -> line("关联记录：${all.find { it.id == b.target }?.displayTitle() ?: b.text}", 11f)
                    else -> line((if (b.type == "bullet") "• " else if (b.type == "check") if (b.checked) "☑ " else "☐ " else "") + b.text, bold = b.bold)
                }
            }
            y += 24
        }
        page?.let { pdf.finishPage(it) }; file.outputStream().use { pdf.writeTo(it) }; pdf.close()
        ExportResult(file, missing, 0)
    }
    suspend fun backup(context: Context, model: WorkspaceModel, options: ExportOptions): ExportResult = withContext(Dispatchers.IO) {
        model.flushAll()
        // Collect completed recording inboxes even when their editor has not reopened yet.
        for (node in model.notes.nodes().filter { it.deletedAt == null && it.kind != "folder" }) {
            val inbox = RecordingService.inbox(context, node.id)
            if (inbox.isNotEmpty()) {
                val blocks = node.blocks().toMutableList()
                inbox.filter { b -> blocks.none { it.id == b.id } }.forEach { blocks.add(it) }
                model.save(node.copy(document = encodeBlocks(blocks), text = blockPlainText(blocks)))
                model.flush(node.id)
                inbox.forEach { RecordingService.removeInbox(context, Uri.parse(it.uri).path.orEmpty()) }
            }
        }
        val nodes = model.notes.nodes()
        val versions = model.notes.allVersions()
        val blocks = (nodes + versions.mapNotNull { runCatching { decodeNode(JSONObject(it.snapshot)) }.getOrNull() }).flatMap { it.blocks() }.filter { it.uri.isNotBlank() }.distinctBy { it.uri }
        val file = output(context, "时笺完整备份", "zip")
        val assetMap = JSONObject()
        val missing = blocks.filterNot { readable(context, it) }.map { it.text }.toMutableList()
        ZipOutputStream(file.outputStream()).use { zip ->
            blocks.filter { selectedMaterial(it, options) }.forEachIndexed { index, b ->
                val ext = b.uri.substringAfterLast('.', "bin").take(8).filter(Char::isLetterOrDigit).ifBlank { "bin" }
                val entry = "assets/$index.$ext"
                try { context.contentResolver.openInputStream(Uri.parse(b.uri))?.use { input -> zip.putNextEntry(ZipEntry(entry)); input.copyTo(zip); zip.closeEntry() } ?: error("缺失"); assetMap.put(b.uri, entry) }
                catch (_: Exception) { missing += b.text }
            }
            val todos = model.dao.allTodos()
            val prefs = JSONObject().apply { appPreferences(context).all.forEach { (key, value) -> put(key, value) } }
            val json = JSONObject().put("format", "shijian-backup").put("version", 1).put("databaseVersion", 10).put("createdAt", System.currentTimeMillis())
                .put("nodes", JSONArray().apply { nodes.forEach { put(jsonObject(it)) } })
                .put("versions", JSONArray().apply { versions.forEach { put(jsonObject(it)) } })
                .put("schedule", JSONArray().apply { model.dao.allSchedule().forEach { put(jsonObject(it)) } })
                .put("boards", JSONArray().apply { todos.forEach { put(jsonObject(it.board)) } })
                .put("tasks", JSONArray().apply { todos.flatMap { it.items }.forEach { put(jsonObject(it)) } })
                .put("preferences", prefs).put("assets", assetMap).put("missing", JSONArray(missing))
            zip.putNextEntry(ZipEntry("backup.json")); zip.write(json.toString().toByteArray()); zip.closeEntry()
        }
        ExportResult(file, missing, assetMap.length())
    }
    suspend fun restore(context: Context, model: WorkspaceModel, uri: Uri): String = withContext(Dispatchers.IO) {
        val stage = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var total = 0L
            var entries = 0
            context.contentResolver.openInputStream(uri)?.use { input -> ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= 50000) { "备份文件条目过多" }
                    require(entry.name == "backup.json" || Regex("assets/[a-zA-Z0-9._-]+").matches(entry.name)) { "不是有效的时笺备份" }
                    val target = File(stage, entry.name)
                    require(target.canonicalPath.startsWith(stage.canonicalPath + File.separator)) { "无效的文件路径" }
                    target.parentFile!!.mkdirs()
                    target.outputStream().use { out -> val buffer = ByteArray(8192); while (true) { val n = zip.read(buffer); if (n < 0) break; total += n; require(total < 2L * 1024 * 1024 * 1024) { "备份超过2GB限制" }; out.write(buffer, 0, n) } }
                    zip.closeEntry()
                }
            } } ?: error("无法读取备份")
            val manifest = File(stage, "backup.json")
            require(manifest.exists() && manifest.length() <= 32L * 1024 * 1024) { "备份清单缺失或过大" }
            val j = JSONObject(manifest.readText())
            require(j.getString("format") == "shijian-backup" && j.getInt("version") == 1) { "不支持的备份版本" }
            fun array(name: String): List<JSONObject> = j.getJSONArray(name).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            val assetMap = j.getJSONObject("assets")
            val restored = mutableMapOf<String, String>()
            val assetsDir = File(context.filesDir, "assets").apply { mkdirs() }
            assetMap.keys().forEach { source ->
                val entry = assetMap.getString(source)
                require(Regex("assets/[a-zA-Z0-9._-]+").matches(entry)) { "备份素材路径无效" }
                val f = File(stage, entry)
                require(f.isFile) { "备份素材缺失：$entry" }
                val dest = File(assetsDir, "${UUID.randomUUID()}.${f.extension}")
                f.copyTo(dest); restored[source] = Uri.fromFile(dest).toString()
            }
            fun remap(node: NoteNode): NoteNode {
                val blocks = node.blocks().map { b -> restored[b.uri]?.let { b.copy(uri = it, owned = true) } ?: b }
                return node.copy(document = encodeBlocks(blocks), text = blockPlainText(blocks))
            }
            val notes = array("nodes").map { remap(decodeNode(it)) }
            require(notes.map { it.id }.distinct().size == notes.size) { "备份包含重复记录" }
            val map = notes.associateBy { it.id }
            notes.forEach { n ->
                require(Regex("[a-zA-Z0-9_-]{1,100}").matches(n.id)) { "无效的记录标识" }
                require(n.kind in setOf("folder", "memory", "diary")) { "未知记录类型" }
                require(n.parentId == null || map[n.parentId]?.kind == "folder") { "分类结构不完整" }
                require(n.parentId == null || n.parentId !in TreeRules.descendants(n.id, notes)) { "分类包含循环" }
            }
            require(notes.filter { it.kind == "diary" && it.deletedAt == null }.groupBy { it.day }.none { it.value.size > 1 }) { "备份中同一天存在多篇日记" }
            val versions = array("versions").map { NoteVersion(it.getLong("id"), it.getString("nodeId"), jsonObject(remap(decodeNode(JSONObject(it.getString("snapshot"))))).toString(), it.getLong("createdAt")) }
            val schedules = array("schedule").map { s -> ScheduleEvent(s.getLong("id"), s.getString("title"), s.getLong("eventAt"), s.optInt("reminderDays"), s.optInt("reminderHours"), s.optInt("reminderMinutes"), s.optString("note"), s.optBoolean("archived"), s.optBoolean("reminderTriggered"), s.optBoolean("reminderEnabled"), s.nullLong("deletedAt")) }
            val boards = array("boards").map { b -> TodoBoard(id = b.getLong("id"), summary = b.getString("summary"), reminderAt = b.nullLong("reminderAt"), reminderDays = b.optInt("reminderDays"), reminderHours = b.optInt("reminderHours"), dueDate = b.nullLong("dueDate"), reminderMinutes = b.optInt("reminderMinutes"), expanded = b.optBoolean("expanded"), reminderTriggered = b.optBoolean("reminderTriggered"), reminderRule = b.nullString("reminderRule"), reminderBaseAt = b.nullLong("reminderBaseAt"), reminderCustomDays = b.optInt("reminderCustomDays"), createdAt = b.getLong("createdAt"), archived = b.optBoolean("archived"), position = b.optInt("position"), boardType = b.optString("boardType", "LIST"), pinned = b.optBoolean("pinned"), deletedAt = b.nullLong("deletedAt")) }
            val tasks = array("tasks").map { t -> TodoItem(t.getLong("id"), t.getLong("boardId"), t.getString("text"), t.optBoolean("completed"), t.optInt("position"), t.optBoolean("important"), t.nullLong("reminderAt"), t.optInt("reminderHours"), t.optInt("reminderMinutes"), t.optBoolean("reminderTriggered"), t.nullLong("plannedDay"), t.nullLong("dueAt"), t.optInt("repeatDays"), t.optBoolean("repeatSpawned"), t.nullLong("deletedAt")) }
            require(tasks.all { task -> boards.any { it.id == task.boardId } }) { "待办清单结构不完整" }
            // A recovery point is written before the replacement transaction.
            val recovery = backup(context, model, ExportOptions())
            val oldSchedules = model.dao.allSchedule()
            val oldTodos = model.dao.allTodos()
            val recoverDir = File(context.filesDir, "recovery").apply { mkdirs() }
            recovery.file.copyTo(File(recoverDir, recovery.file.name))
            model.db.withTransaction {
                val old = model.notes.nodes().map { it.id }
                model.notes.removeVersions(old); model.notes.remove(old)
                model.dao.clearSchedule(); model.dao.clearTodos()
                model.notes.putAll(notes); versions.forEach { model.notes.version(it) }
                model.dao.restoreSchedule(schedules); model.dao.restoreBoards(boards); model.dao.restoreItems(tasks)
            }
            val pref = appPreferences(context).edit().clear()
            val saved = j.getJSONObject("preferences")
            saved.keys().forEach { key -> when (val value = saved.get(key)) { is Boolean -> pref.putBoolean(key, value); is Int -> pref.putInt(key, value); is Long -> pref.putLong(key, value); is String -> pref.putString(key, value) } }
            pref.commit()
            // Cancel previous reminders; only reschedule active future reminders from the imported data.
            oldSchedules.forEach { ReminderScheduler.cancel(context, it.id) }
            oldTodos.forEach { b -> ReminderScheduler.cancelTodoBoard(context, b.board.id); b.items.forEach { ReminderScheduler.cancelTodo(context, it.id) } }
            schedules.filter { !it.archived && it.deletedAt == null }.forEach { ReminderScheduler.schedule(context, it) }
            boards.filter { !it.archived && it.deletedAt == null }.forEach { b -> ReminderScheduler.scheduleTodoBoard(context, b); tasks.filter { it.boardId == b.id && !it.completed && it.deletedAt == null }.forEach { ReminderScheduler.scheduleTodo(context, it) } }
            "已恢复 ${notes.count { it.kind != "folder" }} 条记录。外部引用可能需要重新绑定。恢复前数据已保存在本机恢复备份中。"
        } finally { stage.deleteRecursively() }
    }
    suspend fun taskExport(context: Context, model: WorkspaceModel, type: String): File = withContext(Dispatchers.IO) {
        if (type == "schedule") {
            fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n").replace("\r", "")
            fun folded(s: String): String { val lines = mutableListOf<String>(); var line = ""; var bytes = 0; s.codePoints().toArray().forEach { cp -> val part = String(Character.toChars(cp)); val length = part.toByteArray().size; if (bytes + length > 73) { lines += line; line = " "; bytes = 1 }; line += part; bytes += length }; lines += line; return lines.joinToString("\r\n") }
            val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
            val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//ShiJian//Note//ZH", "CALSCALE:GREGORIAN")
            model.dao.allSchedule().filter { it.deletedAt == null }.forEach { s -> lines += listOf("BEGIN:VEVENT", "UID:${s.id}@shijian.local", "DTSTAMP:${formatter.format(Instant.now())}", "DTSTART:${formatter.format(Instant.ofEpochMilli(s.eventAt))}", "SUMMARY:${esc(s.title)}", "DESCRIPTION:${esc(s.note)}", "END:VEVENT") }
            lines += "END:VCALENDAR"
            output(context, "时间表", "ics").apply { writeText(lines.joinToString("\r\n") { folded(it) } + "\r\n") }
        } else {
            fun cell(s: String): String { val safe = if (s.startsWith('=') || s.startsWith('+') || s.startsWith('-') || s.startsWith('@')) "'$s" else s; return "\"${safe.replace("\"", "\"\"")}\"" }
            val lines = mutableListOf("清单,事项,完成,重要,计划日期,截止时间,提醒时间,归档")
            model.dao.allTodos().filter { it.board.deletedAt == null }.forEach { b -> b.items.filter { it.deletedAt == null }.forEach { t -> lines += listOf(b.board.summary, t.text, if (t.completed) "是" else "否", if (t.important) "是" else "否", t.plannedDay?.let { dateText(it) }.orEmpty(), (t.dueAt ?: b.board.dueDate)?.let { dateText(it, true) }.orEmpty(), t.reminderAt?.let { dateText(it, true) }.orEmpty(), if (b.board.archived) "是" else "否").joinToString(",", transform = ::cell) } }
            output(context, "待办", "csv").apply { writeText("\uFEFF" + lines.joinToString("\r\n")) }
        }
    }
}
