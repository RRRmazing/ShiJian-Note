package com.shijiannote.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import com.shijiannote.app.data.*
import kotlinx.coroutines.*
import java.io.File

@Composable fun SettingsHome(general: () -> Unit, trash: () -> Unit, backup: () -> Unit, advanced: () -> Unit, about: () -> Unit, appInfo: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageTitle("设置", "让时笺适合你的习惯") }
        listOf(Triple("常规", "图片默认显示与保存方式", general), Triple("回收站", "恢复删除的分类、记录和事务", trash), Triple("备份与恢复", "完整保存本机内容与素材", backup), Triple("高级功能", "文字导入事务", advanced), Triple("关于与使用说明", "了解时笺的新功能", about), Triple("系统应用设置", "通知、麦克风与提醒权限", appInfo)).forEach { (title, subtitle, action) -> item { SoftCard(Modifier.clickable(onClick = action)) { Text(title, fontSize = 18.sp); Text(subtitle, fontSize = 13.sp, color = Quiet) } } }
    }
}
@Composable fun GeneralSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = appPreferences(context)
    var display by remember { mutableStateOf(imageDisplayDefault(context)) }
    var storage by remember { mutableStateOf(imageStorageDefault(context)) }
    var status by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("常规", back = onBack)
        SoftCard {
            ChoiceRow("图片默认显示", display, listOf("preview" to "正文预览", "card" to "图片卡片")) { display = it; prefs.edit().putString("imageDisplay", it).apply() }
            ChoiceRow("新插入图片默认保存", storage, listOf("copy" to "保存副本", "reference" to "引用原图")) { storage = it; prefs.edit().putString("imageStorage", it).apply() }
            Text("分类、单篇记录和单张图片可以覆盖显示默认值。保存默认值只影响新插入图片。", fontSize = 13.sp, color = Quiet)
            Text("引用原图依赖原文件与访问权限；普通文件附件始终只建立引用。", fontSize = 13.sp, color = Quiet)
        }
        SoftCard { Text("存储维护", fontSize = 18.sp); Text("清理没有被记录、历史版本或录音草稿使用的素材。", color = Quiet, fontSize = 13.sp); OutlinedButton(onClick = { confirm = true }) { Text("清理未使用素材") }; if (status.isNotBlank()) Text(status, color = Quiet) }
    }
    if (confirm) SoftDialog("清理未使用素材", { confirm = false }) {
        Text("只清理时笺自己的素材目录，不操作相册和绑定文件。")
        Button(onClick = { confirm = false; scope.launch(Dispatchers.IO) {
            val db = AppDatabase.get(context)
            val all = db.noteDao().nodes() + db.noteDao().allVersions().mapNotNull { runCatching { decodeNode(org.json.JSONObject(it.snapshot)) }.getOrNull() }
            val drafts = File(context.filesDir, "drafts").listFiles().orEmpty().mapNotNull { runCatching { decodeNode(org.json.JSONObject(it.readText())) }.getOrNull() }
            val keep = (all + drafts).flatMap { it.blocks() }.filter { it.owned }.mapNotNull { Uri.parse(it.uri).path }.toMutableSet()
            val inbox = org.json.JSONArray(context.getSharedPreferences("recording_inbox", android.content.Context.MODE_PRIVATE).getString("items", "[]"))
            for (i in 0 until inbox.length()) keep += inbox.getJSONObject(i).optString("path")
            var bytes = 0L
            File(context.filesDir, "assets").listFiles().orEmpty().filter { it.isFile && it.absolutePath !in keep && System.currentTimeMillis() - it.lastModified() > 86_400_000L }.forEach { f -> val size = f.length(); if (f.delete()) bytes += size }
            withContext(Dispatchers.Main) { status = "已清理 ${mediaSize(bytes).ifBlank { "0 KB" }}" }
        } }) { Text("清理") }
        TextButton(onClick = { confirm = false }) { Text("取消") }
    }
}
@Composable fun TrashScreen(model: WorkspaceModel, onBack: () -> Unit) {
    val nodes by model.nodes.collectAsState()
    val schedules by model.schedules.collectAsState()
    val todos by model.todos.collectAsState()
    val groups = nodes.filter { it.deletedAt != null }.groupBy { it.deleteGroup }
    var permanent by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PageTitle("回收站", "删除的内容会保留，直到你永久删除", onBack) }
        if (groups.isEmpty() && schedules.none { it.deletedAt != null } && todos.none { it.board.deletedAt != null || it.items.any { t -> t.deletedAt != null } }) item { SoftCard { Text("回收站是空的") } }
        groups.forEach { (group, records) ->
            if (group != null) item("n$group") { SoftCard {
                val root = records.firstOrNull { n -> records.none { it.id == n.parentId } } ?: records.first()
                Text(root.displayTitle(), fontSize = 18.sp); Text("${records.count { it.kind == "folder" }} 个分类 · ${records.count { it.kind != "folder" }} 条记录", color = Quiet, fontSize = 12.sp)
                Row { TextButton(onClick = { model.restore(group) }) { Text("恢复") }; TextButton(onClick = { permanent = "note" to group }) { Text("永久删除", color = MaterialTheme.colorScheme.error) } }
            } }
        }
        schedules.filter { it.deletedAt != null }.forEach { event -> item("s${event.id}") { SoftCard { Text(event.title); Text("时间表 · ${dateText(event.eventAt)}", color = Quiet, fontSize = 12.sp); Row { TextButton(onClick = { model.schedule(event.copy(deletedAt = null)) }) { Text("恢复") }; TextButton(onClick = { permanent = "schedule" to event.id.toString() }) { Text("永久删除") } } } } }
        todos.filter { it.board.deletedAt != null }.forEach { b -> item("b${b.board.id}") { SoftCard { Text(b.board.summary); Text("待办清单 · ${b.items.size} 项", color = Quiet, fontSize = 12.sp); Row { TextButton(onClick = { model.board(b.board.copy(deletedAt = null)) }) { Text("恢复") }; TextButton(onClick = { permanent = "todo" to b.board.id.toString() }) { Text("永久删除") } } } } }
        todos.filter { it.board.deletedAt == null }.forEach { b -> b.items.filter { it.deletedAt != null }.forEach { task -> item("t${task.id}") { SoftCard { Text(task.text); Text("事项 · ${b.board.summary}", color = Quiet, fontSize = 12.sp); Row { TextButton(onClick = { model.task(task.copy(deletedAt = null)) }) { Text("恢复") }; TextButton(onClick = { permanent = "task" to task.id.toString() }) { Text("永久删除") } } } } } }
    }
    permanent?.let { (type, id) -> SoftDialog("永久删除", { permanent = null }) {
        Text("永久删除后无法从回收站恢复。")
        Button(onClick = { permanent = null; when (type) { "note" -> model.permanentlyDelete(id); "schedule" -> scope.launch { schedules.find { it.id.toString() == id }?.let { model.dao.deleteSchedule(it) } }; "todo" -> scope.launch { todos.find { it.board.id.toString() == id }?.let { model.dao.deleteTodoBoard(it.board) } }; "task" -> scope.launch { todos.flatMap { it.items }.find { it.id.toString() == id }?.let { model.dao.deleteTodoItem(it) } } } }) { Text("永久删除") }
        TextButton(onClick = { permanent = null }) { Text("取消") }
    } }
}
@Composable fun GlobalSearch(model: WorkspaceModel, initialTab: Int, onClose: () -> Unit, onNote: (NoteNode, String) -> Unit, onSchedule: (ScheduleEvent) -> Unit, onTodo: (TodoBoardWithItems) -> Unit) {
    val nodes by model.nodes.collectAsState()
    val events by model.schedules.collectAsState()
    val boards by model.todos.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(initialTab) }
    var history by remember { mutableStateOf(false) }
    fun snippet(text: String): String { val index = text.indexOf(query, ignoreCase = true).coerceAtLeast(0); return text.substring((index - 25).coerceAtLeast(0), (index + query.length + 100).coerceAtMost(text.length)) }
    SoftDialog("搜索", onClose) {
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("搜索标题、正文、事项或附件名称") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf(-1 to "全部", 0 to "时间表", 1 to "待办", 2 to "日记", 3 to "记忆").forEach { (index, name) -> FilterChip(filter == index, { filter = index }, label = { Text(name) }) } }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(history, { history = it }); Text("包含历史与归档", fontSize = 13.sp) }
        if (query.isBlank()) Text("输入关键词开始查找，支持中文正文与附件名称。", color = Quiet, fontSize = 13.sp)
        else {
            val notes = nodes.filter { it.deletedAt == null && it.kind != "folder" && (filter == -1 || if (it.kind == "diary") filter == 2 else filter == 3) && (it.title + it.text + it.tags).contains(query, true) }
            val schedule = events.filter { it.deletedAt == null && (history || !it.archived) && (filter == -1 || filter == 0) && (it.title + it.note).contains(query, true) }
            val todo = boards.filter { it.board.deletedAt == null && (history || !it.board.archived) && (filter == -1 || filter == 1) && (it.board.summary + it.items.filter { t -> t.deletedAt == null }.joinToString(" ") { t -> t.text }).contains(query, true) }.map { it.copy(items = it.items.filter { t -> t.deletedAt == null }) }
            Text("找到 ${notes.size + schedule.size + todo.size} 条结果", color = Quiet, fontSize = 12.sp)
            notes.take(100).forEach { n -> Column(Modifier.fillMaxWidth().clickable { onNote(n, query) }.padding(vertical = 10.dp)) { Text(highlightText(n.displayTitle(), query)); Text(highlightText(snippet(n.text), query), fontSize = 13.sp, color = Quiet); Text(if (n.kind == "diary") "日记 · ${dateText(n.day ?: n.createdAt)}" else "记忆 · ${TreeRules.path(n, nodes)}", color = Sky, fontSize = 11.sp) } }
            schedule.take(100).forEach { s -> Column(Modifier.fillMaxWidth().clickable { onSchedule(s) }.padding(vertical = 10.dp)) { Text(highlightText(s.title, query)); Text(highlightText(snippet(s.note), query), color = Quiet, fontSize = 13.sp); Text("时间表 · ${dateText(s.eventAt)}", color = Sky, fontSize = 11.sp) } }
            todo.take(100).forEach { b -> Column(Modifier.fillMaxWidth().clickable { onTodo(b) }.padding(vertical = 10.dp)) { Text(highlightText(b.board.summary, query)); Text(highlightText(snippet(b.items.joinToString("\n") { it.text }), query), color = Quiet, fontSize = 13.sp); Text("待办清单", color = Sky, fontSize = 11.sp) } }
            if (notes.size > 100 || schedule.size > 100 || todo.size > 100) Text("结果较多，请缩小搜索范围。", color = Quiet)
        }
    }
}
