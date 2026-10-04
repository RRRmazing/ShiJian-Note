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
import androidx.compose.runtime.saveable.rememberSaveable
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
@Composable fun GeneralSettings(model: WorkspaceModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = appPreferences(context)
    var imagePage by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<File>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    androidx.activity.compose.BackHandler { if (imagePage) imagePage = false else onBack() }
    if (imagePage) { ImageSettings { imagePage = false }; return }
    Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("常规", back = onBack)
        SoftCard(Modifier.clickable { imagePage = true }) {
            Text("图片显示与保存", fontSize = 18.sp)
            Text("默认预览、卡片和图片副本设置", fontSize = 13.sp, color = Quiet)
        }
        SoftCard {
            Text("清理未引用的图片与附件", fontSize = 18.sp)
            Text("删除记录或替换素材后，时笺保存的副本可能仍留在本机。这里清理没有被任何记录、回收站、历史版本或草稿引用的副本。最近一天新增的文件会暂时保留。", color = Quiet, fontSize = 13.sp)
            OutlinedButton(onClick = { busy = true; scope.launch {
                runCatching { unusedAssets(context, model) }.onSuccess { candidates = it; confirm = true }.onFailure { status = "检查失败：${it.message}" }
                busy = false
            } }, enabled = !busy) { Text(if (busy) "检查中…" else "查看可清理素材") }
            if (status.isNotBlank()) Text(status, color = Quiet)
        }
    }
    if (confirm) SoftDialog("清理未引用素材", { if (!busy) confirm = false }) {
        Text("可清理 ${candidates.size} 个文件，占用 ${mediaSize(candidates.sumOf { it.length() }).ifBlank { "0 KB" }}。只清理时笺保存的副本，相册和绑定的原文件会保留。")
        Button(onClick = { busy = true; scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val selected = candidates.map { it.canonicalPath }.toSet()
                var bytes = 0L
                unusedAssets(context, model).filter { it.canonicalPath in selected }.forEach { file -> val size = file.length(); if (file.delete()) bytes += size }
                bytes
            } }.onSuccess { status = "已清理 ${mediaSize(it).ifBlank { "0 KB" }}" }.onFailure { status = "清理失败：${it.message}" }
            busy = false; confirm = false
        } }, enabled = !busy && candidates.isNotEmpty()) { Text(if (busy) "清理中…" else "清理") }
        TextButton(onClick = { confirm = false }) { Text("取消") }
    }
}

private suspend fun unusedAssets(context: android.content.Context, model: WorkspaceModel): List<File> = withContext(Dispatchers.IO) {
    model.flushAll()
    val all = model.notes.nodes() + model.notes.allVersions().map { decodeNode(org.json.JSONObject(it.snapshot)) }
    val drafts = File(context.filesDir, "drafts").listFiles().orEmpty().filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
        .map { File(it.path.removeSuffix(".bak")) }.distinct().map { file -> decodeNode(org.json.JSONObject(android.util.AtomicFile(file).openRead().bufferedReader().use { it.readText() })) }
    val keep = (all + drafts).flatMap { it.blocks() }.mapNotNull { Uri.parse(it.uri).path }.toMutableSet()
    val inbox = org.json.JSONArray(context.getSharedPreferences("recording_inbox", android.content.Context.MODE_PRIVATE).getString("items", "[]"))
    for (i in 0 until inbox.length()) keep += inbox.getJSONObject(i).optString("path")
    val assets = File(context.filesDir, "assets").canonicalFile
    assets.listFiles().orEmpty().filter { it.isFile && it.canonicalFile.parentFile == assets && it.canonicalPath !in keep && System.currentTimeMillis() - it.lastModified() > 86_400_000L }
}

@Composable private fun ImageSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = appPreferences(context)
    var display by remember { mutableStateOf(imageDisplayDefault(context)) }
    var storage by remember { mutableStateOf(imageStorageDefault(context)) }
    Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("图片显示与保存", back = onBack)
        SoftCard {
            ChoiceRow("图片默认显示", display, listOf("preview" to "正文预览", "card" to "图片卡片")) { display = it; prefs.edit().putString("imageDisplay", it).apply() }
            ChoiceRow("新插入图片默认保存", storage, listOf("copy" to "保存副本", "reference" to "引用原图")) { storage = it; prefs.edit().putString("imageStorage", it).apply() }
            Text("分类、记录和单张图片可设置各自的显示方式；启用分类的强制下级后，统一沿用该分类的设置。", fontSize = 13.sp, color = Quiet)
            Text("默认保存方式影响新插入图片。已有引用图片可在素材设置中保存副本，也可通过分类设置批量补存。", fontSize = 13.sp, color = Quiet)
            Text("引用原图依赖原文件与访问权限；普通文件附件只建立引用。", fontSize = 13.sp, color = Quiet)
        }
    }
}
private data class TrashDeletion(val type: String, val id: String?, val noteIds: Set<String> = emptySet())

private fun trashNoteGroups(records: List<NoteNode>): List<List<NoteNode>> {
    val byId = records.associateBy { it.id }
    fun legacyRoot(node: NoteNode): String {
        var root = node
        val visited = mutableSetOf(root.id)
        while (true) {
            val parent = root.parentId?.let { byId[it] } ?: break
            if (parent.deleteGroup != null || !visited.add(parent.id)) break
            root = parent
        }
        return root.id
    }
    return records.groupBy { node -> node.deleteGroup?.let { "group:$it" } ?: "legacy:${legacyRoot(node)}" }.values.toList()
}

@Composable private fun TrashModuleButton(title: String, count: Int, onClick: () -> Unit) {
    SoftCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Text("$count 项", color = Quiet, fontSize = 14.sp)
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Quiet)
        }
    }
}

@Composable fun TrashScreen(model: WorkspaceModel, onBack: () -> Unit) {
    val nodes by model.nodes.collectAsState()
    val schedules by model.schedules.collectAsState()
    val todos by model.todos.collectAsState()
    var page by rememberSaveable { mutableStateOf("home") }
    var permanent by remember { mutableStateOf<TrashDeletion?>(null) }
    val scope = rememberCoroutineScope()
    val deletedNotes = nodes.filter { it.deletedAt != null && !MemorySpaces.isRoot(it.id) }
    val diaries = deletedNotes.filter { it.kind == "diary" }
    val memories = deletedNotes.filter { it.kind != "diary" }
    val work = memories.filter { MemorySpaces.rootId(it, nodes) != MemorySpaces.LIFE_ID }
    val life = memories.filter { MemorySpaces.rootId(it, nodes) == MemorySpaces.LIFE_ID }
    val deletedSchedules = schedules.filter { it.deletedAt != null }
    val deletedBoards = todos.filter { it.board.deletedAt != null }
    val deletedTasks = todos.filter { it.board.deletedAt == null }.flatMap { board -> board.items.filter { it.deletedAt != null }.map { board to it } }
    val visibleNotes = when (page) { "diary" -> diaries; "work" -> work; "life" -> life; else -> emptyList() }
    val groups = trashNoteGroups(visibleNotes)
    fun back() { permanent = null; when (page) { "home" -> onBack(); "work", "life" -> page = "memory"; else -> page = "home" } }
    androidx.activity.compose.BackHandler(page != "home") { back() }
    val title = when (page) { "schedule" -> "时间表回收站"; "todo" -> "待办回收站"; "diary" -> "日记回收站"; "memory" -> "记忆回收站"; "work" -> "工作回收站"; "life" -> "生活回收站"; else -> "回收站" }
    key(page) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { PageTitle(title, "删除的内容会保留，直到你永久删除", { back() }) }
            when (page) {
                "home" -> {
                    item { TrashModuleButton("时间表", deletedSchedules.size) { page = "schedule" } }
                    item { TrashModuleButton("待办", deletedBoards.size + deletedTasks.size) { page = "todo" } }
                    item { TrashModuleButton("日记", diaries.size) { page = "diary" } }
                    item { TrashModuleButton("记忆", memories.size) { page = "memory" } }
                }
                "memory" -> {
                    item { TrashModuleButton("工作", work.size) { page = "work" } }
                    item { TrashModuleButton("生活", life.size) { page = "life" } }
                }
                "schedule" -> {
                    if (deletedSchedules.isEmpty()) item { SoftCard { Text("时间表回收站是空的") } }
                    items(deletedSchedules, key = { "s${it.id}" }) { event -> SoftCard {
                        Text(event.title, fontSize = 18.sp)
                        Text("时间表 · ${dateText(event.eventAt)}", color = Quiet, fontSize = 12.sp)
                        Row {
                            TextButton(onClick = { model.schedule(event.copy(deletedAt = null)) }) { Text("恢复") }
                            TextButton(onClick = { permanent = TrashDeletion("schedule", event.id.toString()) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("永久删除") }
                        }
                    } }
                }
                "todo" -> {
                    if (deletedBoards.isEmpty() && deletedTasks.isEmpty()) item { SoftCard { Text("待办回收站是空的") } }
                    items(deletedBoards, key = { "b${it.board.id}" }) { board -> SoftCard {
                        Text(board.board.summary, fontSize = 18.sp)
                        Text("待办清单 · ${board.items.size} 项", color = Quiet, fontSize = 12.sp)
                        Row {
                            TextButton(onClick = { model.board(board.board.copy(deletedAt = null)) }) { Text("恢复") }
                            TextButton(onClick = { permanent = TrashDeletion("todo", board.board.id.toString()) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("永久删除") }
                        }
                    } }
                    items(deletedTasks, key = { "t${it.second.id}" }) { (board, task) -> SoftCard {
                        Text(task.text, fontSize = 18.sp)
                        Text("事项 · ${board.board.summary}", color = Quiet, fontSize = 12.sp)
                        Row {
                            TextButton(onClick = { model.task(task.copy(deletedAt = null)) }) { Text("恢复") }
                            TextButton(onClick = { permanent = TrashDeletion("task", task.id.toString()) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("永久删除") }
                        }
                    } }
                }
                else -> {
                    if (groups.isEmpty()) item { SoftCard { Text("${title}是空的") } }
                    items(groups, key = { records -> records.first().deleteGroup ?: "legacy:${records.first().id}" }) { records -> SoftCard {
                        val root = records.firstOrNull { node -> records.none { it.id == node.parentId } } ?: records.first()
                        val ids = records.map { it.id }.toSet()
                        Text(root.displayTitle(), fontSize = 18.sp)
                        Text(if (page == "diary") "${records.size} 条日记" else "${records.count { it.kind == "folder" }} 个分类 · ${records.count { it.kind != "folder" }} 条记录", color = Quiet, fontSize = 12.sp)
                        Row {
                            TextButton(onClick = { model.restore(root.deleteGroup, ids) }) { Text("恢复") }
                            TextButton(onClick = { permanent = TrashDeletion("note", root.deleteGroup, ids) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("永久删除") }
                        }
                    } }
                }
            }
        }
    }
    permanent?.let { target -> SoftDialog("永久删除", { permanent = null }) {
        Text("永久删除后无法从回收站恢复。")
        Button(onClick = {
            permanent = null
            when (target.type) {
                "note" -> model.permanentlyDelete(target.id, target.noteIds)
                "schedule" -> scope.launch { schedules.find { it.id.toString() == target.id && it.deletedAt != null }?.let { model.dao.deleteSchedule(it) } }
                "todo" -> scope.launch { todos.find { it.board.id.toString() == target.id && it.board.deletedAt != null }?.let { model.dao.deleteTodoBoard(it.board) } }
                "task" -> scope.launch { todos.filter { it.board.deletedAt == null }.flatMap { it.items }.find { it.id.toString() == target.id && it.deletedAt != null }?.let { model.dao.deleteTodoItem(it) } }
            }
        }) { Text("永久删除") }
        TextButton(onClick = { permanent = null }) { Text("取消") }
    } }
}
@Composable fun GlobalSearch(model: WorkspaceModel, initialTab: Int, onClose: () -> Unit, onNote: (NoteNode, String) -> Unit, onFolder: (NoteNode) -> Unit, onSchedule: (ScheduleEvent) -> Unit, onTodo: (TodoBoardWithItems) -> Unit) {
    val nodes by model.nodes.collectAsState()
    val events by model.schedules.collectAsState()
    val boards by model.todos.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(initialTab) }
    var history by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    @OptIn(ExperimentalLayoutApi::class)
    val imeVisible = WindowInsets.isImeVisible
    androidx.activity.compose.BackHandler { if (imeVisible) { keyboard?.hide(); focus.clearFocus() } else onClose() }
    fun snippet(text: String): String { val index = text.indexOf(query, ignoreCase = true).coerceAtLeast(0); return text.substring((index - 25).coerceAtLeast(0), (index + query.length + 100).coerceAtMost(text.length)) }
    Column(Modifier.fillMaxSize().imePadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PageTitle("搜索", back = { keyboard?.hide(); focus.clearFocus(); onClose() })
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("搜索标题、正文、标签、事项或附件名称") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf(-1 to "全部", 0 to "时间表", 1 to "待办", 2 to "日记", 3 to "记忆").forEach { (index, name) -> FilterChip(filter == index, { filter = index }, label = { Text(name) }) } }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(history, { history = it }); Text("包含历史与归档", fontSize = 13.sp) }
        if (query.isBlank()) Text("输入关键词开始查找，支持中文正文、标签与附件名称。", color = Quiet, fontSize = 13.sp)
        else {
            val notes = nodes.filter { it.deletedAt == null && !MemorySpaces.isRoot(it.id) && (filter == -1 || if (it.kind == "diary") filter == 2 else filter == 3) && (it.title + it.text + it.tags).contains(query, true) }
            val schedule = events.filter { it.deletedAt == null && (history || !it.archived) && (filter == -1 || filter == 0) && (it.title + it.note).contains(query, true) }
            val todo = boards.filter { it.board.deletedAt == null && (history || !it.board.archived) && (filter == -1 || filter == 1) }.flatMap { group ->
                val live = group.items.filter { it.deletedAt == null }
                if (group.board.boardType == "DAILY") live.filter { it.text.contains(query, true) }.map { group.copy(items = listOf(it)) }
                else if ((group.board.summary + live.joinToString(" ") { it.text }).contains(query, true)) listOf(group.copy(items = live)) else emptyList()
            }
            Text("找到 ${notes.size + schedule.size + todo.size} 条结果", color = Quiet, fontSize = 12.sp)
            LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(notes, key = { "n-${it.id}" }) { n -> SoftCard(Modifier.clickable {
                    keyboard?.hide(); focus.clearFocus()
                    if (n.kind == "folder") onFolder(n) else onNote(n, query)
                }) { Text(highlightText(n.displayTitle(), query)); Text(highlightText(snippet(n.text), query), fontSize = 13.sp, color = Quiet); Text(if (n.kind == "diary") "日记 · ${dateText(n.day ?: n.createdAt)}" else "${if (n.kind == "folder") "分类" else "记忆"} · ${TreeRules.path(n, nodes)}", color = Sky, fontSize = 11.sp) } }
                items(schedule, key = { "s-${it.id}" }) { s -> SoftCard(Modifier.clickable { keyboard?.hide(); focus.clearFocus(); onSchedule(s) }) { Text(highlightText(s.title, query)); Text(highlightText(snippet(s.note), query), color = Quiet, fontSize = 13.sp); Text("时间表 · ${dateText(s.eventAt)}", color = Sky, fontSize = 11.sp) } }
                items(todo, key = { if (it.board.boardType == "DAILY") "t-${it.items.single().id}" else "b-${it.board.id}" }) { b -> SoftCard(Modifier.clickable { keyboard?.hide(); focus.clearFocus(); onTodo(b) }) {
                    val daily = b.board.boardType == "DAILY"
                    Text(highlightText(if (daily) b.items.single().text else b.board.summary, query))
                    if (!daily) Text(highlightText(snippet(b.items.joinToString("\n") { it.text }), query), color = Quiet, fontSize = 13.sp)
                    Text(if (daily) "独立事项 · ${b.items.single().plannedDay?.let { dateText(it) }.orEmpty()}" else "待办清单", color = Sky, fontSize = 11.sp)
                } }
            }
        }
    }
}
