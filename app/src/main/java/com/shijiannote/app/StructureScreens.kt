package com.shijiannote.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shijiannote.app.data.NoteNode
import kotlinx.coroutines.launch

@Composable fun MoveDestinationPicker(model: WorkspaceModel, ids: Set<String>, onClose: () -> Unit, onMoved: (NoteNode?) -> Unit = {}) {
    val all by model.nodes.collectAsState()
    val active = all.filter { it.deletedAt == null && it.kind == "folder" }
    val forbidden = ids.flatMap { TreeRules.descendants(it, all) }.toSet()
    var parent by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val current = active.find { it.id == parent }
    fun close() { if (!busy) onClose() }
    fun back() { if (busy) return; if (creating) creating = false else if (parent != null) parent = current?.parentId else onClose() }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)) {
        BackHandler { back() }
        Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), color = Mist) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PageTitle("移动到", back = ::back)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                Text(current?.let { "记忆 / ${TreeRules.path(it, all)}" } ?: "请选择工作或生活", color = Sky, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { parent = null; creating = false }, enabled = !busy) { Text("工作 / 生活") }
                    if (current != null) OutlinedButton(onClick = { creating = !creating; name = ""; error = "" }, enabled = !busy) { Icon(Icons.Default.CreateNewFolder, null); Text("新建分类") }
                }
                }
                if (creating && current != null) {
                    item {
                    OutlinedTextField(name, { name = it }, label = { Text("在当前位置创建分类") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                    }
                    item {
                    Button(onClick = {
                        keyboard?.hide(); focus.clearFocus()
                        busy = true; error = ""
                        val folder = model.newNote("folder", parent).copy(title = name.trim())
                        model.save(folder)
                        scope.launch {
                            runCatching { model.flush(folder.id) }.onSuccess { parent = folder.id; creating = false }
                                .onFailure { error = it.message ?: "创建失败，请重试" }
                            busy = false
                        }
                    }, enabled = name.isNotBlank() && !busy) { Text("创建并进入") }
                    }
                }
                    val folders = if (parent == null) listOf(MemorySpaces.WORK_ID, MemorySpaces.LIFE_ID).mapNotNull { id -> active.find { it.id == id } }
                        else active.filter { it.parentId == parent && it.id !in forbidden && !MemorySpaces.isRoot(it.id) }.sortedBy { it.position }
                    if (folders.isEmpty()) item { Text(if (parent == null) "正在载入工作与生活…" else "此处没有可进入的子分类，可以直接移动到这里。", color = Quiet) }
                    items(folders, key = { it.id }) { folder -> SoftCard(Modifier.heightIn(min = 72.dp).clickable(enabled = !busy) { parent = folder.id; error = "" }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, tint = Sky)
                            Text(folder.displayTitle(), Modifier.weight(1f).padding(horizontal = 10.dp))
                            Icon(Icons.Default.ChevronRight, "进入分类")
                        }
                    } }
                    if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = ::close, modifier = Modifier.weight(1f), enabled = !busy) { Text("取消") }
                    Button(onClick = {
                        busy = true; error = ""; keyboard?.hide(); focus.clearFocus()
                        scope.launch {
                            var completed = false
                            runCatching {
                                model.moveMany(ids, parent) { updated ->
                                    completed = true
                                    onMoved(if (ids.size == 1) updated.firstOrNull { it.id in ids } else null)
                                    onClose()
                                }.join()
                            }.onFailure { error = it.message ?: "移动失败，请重试" }
                            if (!completed && error.isBlank()) error = "移动未完成，请重试"
                            busy = false
                        }
                    }, modifier = Modifier.weight(1f), enabled = !busy && current != null && parent !in forbidden && ids.none { MemorySpaces.isRoot(it) }) { Text(if (busy) "正在处理…" else "移动到这里") }
                }
            }
        }
    }
}

@Composable fun StructureTreeScreen(nodes: List<NoteNode>, parent: String?, onBack: () -> Unit, highlightId: String? = null, onOpen: (NoteNode) -> Unit) {
    val root = nodes.find { it.id == parent }
    val lines = remember(nodes, parent) { TreeRules.structure(nodes, root) }
    val measure = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 22.sp)
    val branchWidth = with(density) { measure.measure(AnnotatedString("MMMM"), style, softWrap = false).size.width.toDp() }
    val titleWidth = with(density) { measure.measure(AnnotatedString("《六六六六六六...》"), style, softWrap = false).size.width.toDp() }
    val width = maxOf(LocalConfiguration.current.screenWidthDp.dp, branchWidth * (lines.maxOfOrNull { it.prefix.length / 4 } ?: 0) + titleWidth + 48.dp)
    val horizontal = rememberScrollState()
    val listState = rememberLazyListState()
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        PageTitle("结构树", root?.displayTitle() ?: "记忆首页", onBack)
        Text("《分类》 · 记忆标题；点击分类进入分类，点击记忆打开正文。", color = Quiet, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).horizontalScroll(horizontal)) {
            LazyColumn(Modifier.width(width).fillMaxHeight(), state = listState) {
                if (lines.isEmpty()) item { Text("这里还没有内容", color = Quiet) }
                items(lines, key = { it.node.id }) { line ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).height(IntrinsicSize.Min)
                        .background(if (line.node.id == highlightId) Lavender else Color.Transparent).clickable { onOpen(line.node) }
                        .semantics(mergeDescendants = true) { contentDescription = (if (line.node.kind == "folder") "进入分类：" else "打开记忆：") + line.node.displayTitle() },
                        verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.width(branchWidth * (line.prefix.length / 4)).fillMaxHeight()) {
                            val parts = line.prefix.chunked(4)
                            val step = branchWidth.toPx()
                            parts.forEachIndexed { index, part ->
                                val x = step * (index + 0.18f)
                                val middle = size.height / 2f
                                if (part.startsWith("│") || part.startsWith("├")) drawLine(Quiet, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
                                if (part.startsWith("└")) drawLine(Quiet, Offset(x, 0f), Offset(x, middle), 1.5.dp.toPx())
                                if (part.startsWith("├") || part.startsWith("└")) drawLine(Quiet, Offset(x, middle), Offset(step * (index + 1) - 4.dp.toPx(), middle), 1.5.dp.toPx())
                            }
                        }
                        Text(TreeRules.shortTitle(line.node), style = style, softWrap = false, color = if (line.node.kind == "folder") Sky else NoteInk)
                    }
                }
            }
        }
    }
}

@Composable fun ImageFailuresScreen(model: WorkspaceModel, onBack: () -> Unit, onOpen: (NoteNode) -> Unit) {
    val failures by model.imageFailures.collectAsState()
    val nodes by model.nodes.collectAsState()
    val busy by model.imageBusy.collectAsState()
    BackHandler(onBack = onBack)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            PageTitle("图片处理失败", "${failures.size} 张图片未能保存副本，原引用已保留", onBack)
            Text("可以打开对应记录，在图片的素材设置中重新选择原文件，然后重试。", color = Quiet)
            Button(onClick = { model.retryImages() }, enabled = !busy && failures.isNotEmpty()) { Text("重试保存副本") }
        }
        items(failures, key = { it.nodeId + ":" + it.blockId }) { failure -> SoftCard(color = Peach) {
            Text(failure.path, color = Sky)
            Text(failure.name.ifBlank { "未命名图片" })
            Text(failure.reason, color = MaterialTheme.colorScheme.error)
            Text("原引用：${failure.uri}", color = Quiet, fontSize = 12.sp)
            nodes.find { it.id == failure.nodeId && it.deletedAt == null }?.let { node ->
                OutlinedButton(onClick = { onOpen(node) }) { Text("打开记录并修复") }
            }
        } }
    }
}
