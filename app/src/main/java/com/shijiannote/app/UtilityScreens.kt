package com.shijiannote.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.*
import com.shijiannote.app.data.*
import kotlinx.coroutines.*
import java.io.File

@Composable fun SettingsHome(general: () -> Unit, trash: () -> Unit, backup: () -> Unit, advanced: () -> Unit, about: () -> Unit, appInfo: () -> Unit, problems: () -> Unit = {}, problemCount: Int = 0, referenced: () -> Unit = {}) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp, 10.dp, 24.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { PageTitle("设置", "让时笺适合你的习惯") }
        item { SettingsSection("使用偏好",
            SettingsEntry("常规", "图片显示与保存", Icons.Default.Tune, general),
            SettingsEntry("高级功能", "文字导入事务", Icons.Default.AutoAwesome, advanced)) }
        item { HorizontalDivider(color = MaterialTheme.colorScheme.outline); Spacer(Modifier.height(18.dp)); SettingsSection("数据管理",
            SettingsEntry("备份与恢复", "保存与还原数据", Icons.Default.Inventory2, backup),
            SettingsEntry("回收站", "找回删除的内容", Icons.Default.DeleteOutline, trash)) }
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = problems) { BadgedBox(badge = { if (problemCount > 0) Badge { Text(problemCount.toString()) } }) { Text("问题日志") } }
                TextButton(onClick = referenced) { Text("已引用照片与视频") }
            }
        }
        item { HorizontalDivider(color = MaterialTheme.colorScheme.outline); Spacer(Modifier.height(18.dp)); SettingsSection("帮助与系统",
            SettingsEntry("关于与使用说明", "认识时笺", Icons.Default.MenuBook, about),
            SettingsEntry("系统应用设置", "通知与权限", Icons.Default.PhonelinkSetup, appInfo)) }
    }
}
private data class SettingsEntry(val title: String, val subtitle: String, val icon: ImageVector, val action: () -> Unit)

@Composable private fun SettingsSection(title: String, first: SettingsEntry, second: SettingsEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 12.sp, color = Quiet)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(first, second).forEach { entry ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = entry.action).padding(vertical = 14.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(entry.icon, null, tint = Sky, modifier = Modifier.size(28.dp))
                    Text(entry.title, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(entry.subtitle, fontSize = 12.sp, color = Quiet, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
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
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { imagePage = true }.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Image, null, tint = Sky)
            Text("图片显示与保存", fontSize = 18.sp)
            Text("默认预览、卡片和图片副本设置", fontSize = 13.sp, color = Quiet)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

internal suspend fun unusedAssets(context: android.content.Context, model: WorkspaceModel): List<File> = withContext(Dispatchers.IO) {
    model.flushAll()
    val all = model.notes.nodes() + model.notes.allVersions().map { decodeNode(org.json.JSONObject(it.snapshot)) }
    val drafts = File(context.filesDir, "drafts").listFiles().orEmpty().filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
        .map { File(it.path.removeSuffix(".bak")) }.distinct().map { file -> decodeNode(org.json.JSONObject(android.util.AtomicFile(file).openRead().bufferedReader().use { it.readText() })) }
    // Android may expose the same app file through /data/user/0 and /data/data aliases.
    fun canonical(path: String): String = File(path).canonicalPath
    val keep = (all + drafts).flatMap { it.materialBlocks() }.mapNotNull { Uri.parse(it.uri).path }.map(::canonical).toMutableSet()
    val inbox = org.json.JSONArray(context.getSharedPreferences("recording_inbox", android.content.Context.MODE_PRIVATE).getString("items", "[]"))
    for (i in 0 until inbox.length()) keep += canonical(inbox.getJSONObject(i).optString("path"))
    val preferences = appPreferences(context)
    val backgrounds = org.json.JSONArray(preferences.getString("diary_background_library", "[]") ?: "[]")
    for (index in 0 until backgrounds.length()) Uri.parse(backgrounds.getJSONObject(index).optString("uri")).path?.let { keep += canonical(it) }
    Uri.parse(preferences.getString("diary_default_background_uri", "").orEmpty()).path?.let { keep += canonical(it) }
    val assets = File(context.filesDir, "assets").canonicalFile
    assets.listFiles().orEmpty().filter { it.isFile && it.canonicalFile.parentFile == assets && it.canonicalPath !in keep && System.currentTimeMillis() - it.lastModified() > 86_400_000L }
}

@Composable private fun ImageSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = appPreferences(context)
    var display by remember { mutableStateOf(imageDisplayDefault(context)) }
    var storage by remember { mutableStateOf(imageStorageDefault(context)) }
    var videoStorage by remember { mutableStateOf(prefs.getString("videoStorage", storage)!!) }
    var imageQuality by remember { mutableStateOf(prefs.getString("imageCopyQuality", "original")!!) }
    var videoQuality by remember { mutableStateOf(prefs.getString("videoCopyQuality", "original")!!) }
    Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("图片显示与保存", back = onBack)
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ChoiceRow("图片默认显示", display, listOf("preview" to "正文预览", "card" to "图片卡片")) { display = it; prefs.edit().putString("imageDisplay", it).apply() }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            ChoiceRow("新插入图片默认保存", storage, listOf("copy" to "保存副本", "reference" to "引用原图")) { storage = it; prefs.edit().putString("imageStorage", it).apply() }
            ChoiceRow("照片副本默认质量", imageQuality, listOf("original" to "原文件", "high" to "高清", "small" to "节省空间")) { imageQuality = it; prefs.edit().putString("imageCopyQuality", it).apply() }
            Text("原文件副本保持原始字节。高清照片最长边2560像素、JPEG质量90；节省空间最长边1280像素、JPEG质量80，不放大小图。透明PNG保留透明。动图/WebP使用原文件副本。", fontSize = 12.sp, color = Quiet)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            ChoiceRow("新插入视频默认保存", videoStorage, listOf("reference" to "引用原视频", "copy" to "保存副本")) { videoStorage = it; prefs.edit().putString("videoStorage", it).apply() }
            ChoiceRow("视频副本默认质量", videoQuality, listOf("original" to "原视频", "1080" to "1080p", "720" to "720p")) { videoQuality = it; prefs.edit().putString("videoCopyQuality", it).apply() }
            Text("1080p/720p限制短边并保持比例，不放大原视频。压缩使用H.264，目标码率分别8/4Mbps，保留音轨；实际效果由编码器决定。原视频档位不转码。导入时可单独选择本次保存方式与质量。", fontSize = 12.sp, color = Quiet)
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
    LaunchedEffect(Unit) { model.cleanupDiaryRetention().join() }
    val nodes by model.nodes.collectAsState()
    val schedules by model.schedules.collectAsState()
    val todos by model.todos.collectAsState()
    var page by rememberSaveable { mutableStateOf("home") }
    var permanent by remember { mutableStateOf<TrashDeletion?>(null) }
    var retentionSettings by remember { mutableStateOf(false) }
    var retentionDays by remember { mutableStateOf(model.diaryTrashRetentionDays().toString()) }
    var pendingRetention by remember { mutableStateOf<Int?>(null) }
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
            item { PageTitle(title, if (page == "diary") "整篇日记在这里保留；默认永久保存" else "删除的内容会保留，直到你永久删除", { back() }) {
                if (page == "diary") IconButton(onClick = { retentionDays = model.diaryTrashRetentionDays().toString(); retentionSettings = true }) { Icon(Icons.Default.Settings, "日记回收保留期限") }
            } }
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
                        if (page == "diary") Text(root.diaryTrashExpiresAt?.let { "保留至 ${dateText(it, true)}" } ?: "永久保留", color = Quiet, fontSize = 12.sp)
                        Row {
                            TextButton(onClick = { model.restore(root.deleteGroup, ids) }) { Text("恢复") }
                            TextButton(onClick = { permanent = TrashDeletion("note", root.deleteGroup, ids) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("永久删除") }
                        }
                    } }
                }
            }
        }
    }
    if (retentionSettings) SoftDialog("日记回收保留期限", { retentionSettings = false }) {
        Text("只影响以后移入回收站的整篇日记，现有 ${diaries.size} 篇日记的保留期限不变。收纳箱使用独立设置。", color = Quiet, fontSize = 13.sp)
        ChoiceRow("保留方式", if (retentionDays == "0") "forever" else "days", listOf("forever" to "永久保存", "days" to "指定天数")) { retentionDays = if (it == "forever") "0" else "30" }
        if (retentionDays != "0") OutlinedTextField(retentionDays, { value -> retentionDays = value.filter(Char::isDigit).take(5) }, label = { Text("保留天数") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("有期限的项目从删除时刻起计时，到期后自动彻底删除。", color = Quiet, fontSize = 12.sp)
        Button(onClick = { pendingRetention = retentionDays.toIntOrNull(); retentionSettings = false }, enabled = retentionDays.toIntOrNull()?.let { it in 0..36500 } == true, modifier = Modifier.fillMaxWidth()) { Text("下一步") }
    }
    pendingRetention?.let { days -> SoftDialog("确认保留设置", { pendingRetention = null }) {
        Text(if (days == 0) "以后移入日记回收站的整篇日记将永久保留。" else "以后移入日记回收站的整篇日记将在删除 $days 天后自动彻底删除。")
        Text("现有 ${diaries.size} 篇日记仍沿用原来的保留期限。", color = Quiet, fontSize = 13.sp)
        Button(onClick = { model.setDiaryRetentionDays(model.diaryInboxRetentionDays(), days); pendingRetention = null }) { Text("确认") }
        TextButton(onClick = { pendingRetention = null; retentionSettings = true }) { Text("返回修改") }
    } }
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
