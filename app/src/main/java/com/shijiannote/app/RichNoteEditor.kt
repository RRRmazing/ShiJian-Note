package com.shijiannote.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.shijiannote.app.data.NoteNode
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun RichNoteEditor(initial: NoteNode, model: WorkspaceModel, initialEditing: Boolean, reveal: String = "", onBack: () -> Unit, onOpen: (NoteNode) -> Unit, onExport: (Set<String>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val nodes by model.nodes.collectAsState()
    var snapshot by rememberSaveable(initial.id) { mutableStateOf(jsonObject(initial).toString()) }
    val note = remember(snapshot) { decodeNode(org.json.JSONObject(snapshot)) }
    val blocks = remember(note.document, note.text) { note.blocks() }
    var editing by rememberSaveable(initial.id) { mutableStateOf(initialEditing) }
    var changed by rememberSaveable(initial.id) { mutableStateOf(false) }
    var active by remember { mutableStateOf(blocks.first().id) }
    val selections = remember(initial.id) { mutableStateMapOf<String, TextRange>() }
    val compositions = remember(initial.id) { mutableStateMapOf<String, TextRange?>() }
    val undo = remember(initial.id) { mutableStateListOf<String>() }
    val redo = remember(initial.id) { mutableStateListOf<String>() }
    var more by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var attachmentMenu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    var versions by remember { mutableStateOf(false) }
    var tableOfContents by remember { mutableStateOf(false) }
    var templates by remember { mutableStateOf(false) }
    var titleFocused by remember(initial.id) { mutableStateOf(false) }
    var tagDialog by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<String?>(null) }
    var tagInput by rememberSaveable(initial.id) { mutableStateOf("") }
    var movePicker by remember { mutableStateOf(false) }
    var preparingMove by remember { mutableStateOf(false) }
    var stopBeforeMoving by remember { mutableStateOf(false) }
    var importKind by remember { mutableStateOf("image") }
    var rebinding by remember { mutableStateOf<String?>(null) }
    var assetSettings by remember { mutableStateOf<NoteBlock?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    var leaving by remember { mutableStateOf(false) }
    var stopBeforeLeaving by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val recording by RecordingService.state.collectAsState()
    val states by model.saveStates.collectAsState()

    fun change(next: NoteNode, track: Boolean = true) {
        changed = true
        if (track && snapshot != jsonObject(next).toString()) { undo.add(snapshot); if (undo.size > 100) undo.removeAt(0); redo.clear() }
        val updated = next.copy(updatedAt = System.currentTimeMillis())
        snapshot = jsonObject(updated).toString()
        model.save(updated)
    }
    fun updateBlock(block: NoteBlock) = change(note.copy(document = encodeBlocks(blocks.map { if (it.id == block.id) block else it }), text = blockPlainText(blocks.map { if (it.id == block.id) block else it })))
    fun insert(block: NoteBlock) {
        val next = blocks.toMutableList()
        val index = blocks.indexOfFirst { it.id == active }.takeIf { it >= 0 } ?: blocks.lastIndex
        next.add(index + 1, block)
        if (block.type in setOf("image", "audio", "file", "link")) next.add(index + 2, NoteBlock())
        change(note.copy(document = encodeBlocks(next), text = blockPlainText(next)))
        active = next.getOrNull(index + 2)?.id ?: block.id
    }
    suspend fun collectRecordings() {
        val inbox = RecordingService.inbox(context, initial.id)
        if (inbox.isNotEmpty()) {
            val latest = decodeNode(org.json.JSONObject(snapshot))
            val next = latest.blocks().toMutableList()
            inbox.filter { b -> next.none { it.id == b.id } }.forEach { next.add(it) }
            if (next != latest.blocks()) {
                next.add(NoteBlock()); change(latest.copy(document = encodeBlocks(next), text = blockPlainText(next)))
                active = next.last().id
            }
            model.flush(initial.id)
            inbox.forEach { RecordingService.removeInbox(context, Uri.parse(it.uri).path.orEmpty()) }
        }
    }
    fun leave() {
        if (recording.running && recording.owner == note.id) { stopBeforeLeaving = true; return }
        scope.launch {
            leaving = true
            runCatching {
                collectRecordings()
                val latest = decodeNode(org.json.JSONObject(snapshot))
                val hasContent = latest.title.isNotBlank() || TagRules.names(latest.tags).isNotEmpty() || latest.blocks().any { it.text.isNotBlank() || it.uri.isNotBlank() || it.target.isNotBlank() }
                if (hasContent || !initialEditing) { if (changed || initialEditing) model.save(decodeNode(org.json.JSONObject(snapshot))); model.flush(note.id) }
                else if (nodes.any { it.id == note.id }) {
                    model.flush(note.id)
                    model.notes.remove(listOf(note.id))
                }
                withContext(Dispatchers.Main.immediate) { keyboard?.hide(); focus.clearFocus(); onBack() }
            }.onFailure { error = it.message ?: "保存失败，请重试" }
            leaving = false
        }
    }
    suspend fun prepareMove() {
        collectRecordings()
        val latest = decodeNode(org.json.JSONObject(snapshot))
        model.save(latest)
        model.flush(latest.id)
        withContext(Dispatchers.Main.immediate) {
            keyboard?.hide(); focus.clearFocus(); movePicker = true
        }
    }
    fun openMovePicker() {
        if (preparingMove) return
        val currentRecording = RecordingService.state.value
        if (currentRecording.running && currentRecording.owner == note.id) { stopBeforeMoving = true; return }
        preparingMove = true
        scope.launch {
            runCatching { prepareMove() }.onFailure { error = it.message ?: "保存失败，请重试" }
            preparingMove = false
        }
    }
    val imeVisible = WindowInsets.isImeVisible
    BackHandler {
        when {
            imeVisible -> { keyboard?.hide(); focus.clearFocus() }
            addMenu || attachmentMenu -> { addMenu = false; attachmentMenu = false }
            else -> leave()
        }
    }
    val latestInsert by rememberUpdatedState<(NoteBlock) -> Unit> { block ->
        val id = rebinding
        if (id == null) insert(block) else {
            val old = blocks.find { it.id == id }
            if (old != null) updateBlock(block.copy(id = old.id, text = old.text, display = old.display))
            rebinding = null
        }
    }
    val latestNote by rememberUpdatedState(note)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val image = importKind == "image"
            val copy = image && TreeRules.imagePreference(latestNote, nodes, true, imageStorageDefault(context)) == "copy"
            runCatching { importMedia(context, uri, image, copy) }.onSuccess { latestInsert(it) }.onFailure { error = it.message ?: "添加失败" }
        }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) runCatching { RecordingService.command(context, "start", initial.id) }.onFailure { error = "无法开始录音" }
        else error = "录音需要麦克风权限，可在系统应用设置中开启"
    }
    fun startRecording() {
        if (recording.running) { error = "已有录音正在进行"; return }
        model.save(note)
        val permissions = listOf(Manifest.permission.RECORD_AUDIO) + if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
            runCatching { RecordingService.command(context, "start", note.id) }.onFailure { error = "无法开始录音，请重试" }
        else microphone.launch(permissions.toTypedArray())
    }
    val latestCollect by rememberUpdatedState<suspend () -> Unit> { collectRecordings() }
    LaunchedEffect(note.id) {
        while (true) { runCatching { latestCollect() }.onFailure { error = it.message.orEmpty() }; delay(1000) }
    }
    LaunchedEffect(recording.error) { if (recording.error.isNotBlank()) error = recording.error }
    val visibleIds = visibleBlockIds(blocks, reveal.isNotBlank())
    val shown = blocks.filter { it.id in visibleIds }
    LaunchedEffect(reveal) {
        if (reveal.isNotBlank()) {
            val index = shown.indexOfFirst { it.text.contains(reveal, ignoreCase = true) }
            if (index >= 0) listState.scrollToItem(index + 2)
        }
    }
    Surface(Modifier.fillMaxSize(), color = Mist) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::leave, enabled = !leaving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "保存并返回") }
                Column(Modifier.weight(1f)) {
                    Text(if (note.kind == "diary") dateText(note.day ?: note.createdAt) else TreeRules.ancestors(note, nodes).lastOrNull()?.displayTitle() ?: "记忆", maxLines = 2, fontSize = 14.sp, color = Quiet)
                    val saved = states[note.id].orEmpty()
                    if (saved.isNotBlank()) Text(saved, fontSize = 11.sp, color = if (saved.contains("失败")) MaterialTheme.colorScheme.error else Quiet, modifier = Modifier.clickable { model.retry() })
                }
                IconButton(onClick = { editing = !editing; focus.clearFocus(); keyboard?.hide() }) { Icon(if (editing) Icons.Default.Done else Icons.Default.Edit, if (editing) "阅读" else "编辑") }
                Box {
                    IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "更多") }
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text("记录设置") }, onClick = { more = false; settings = true })
                        if (note.kind == "memory") DropdownMenuItem(text = { Text("移动到") }, enabled = !preparingMove, onClick = { more = false; openMovePicker() })
                        DropdownMenuItem(text = { Text("标题目录") }, onClick = { more = false; tableOfContents = true })
                        DropdownMenuItem(text = { Text("历史版本") }, onClick = { more = false; versions = true })
                        DropdownMenuItem(text = { Text("导出这一篇") }, onClick = { more = false; scope.launch { model.save(note); model.flush(note.id); onExport(setOf(note.id)) } })
                        DropdownMenuItem(text = { Text(if (note.favorite) "取消收藏" else "收藏") }, onClick = { more = false; change(note.copy(favorite = !note.favorite)) })
                        DropdownMenuItem(text = { Text("从模板追加") }, onClick = { more = false; templates = true })
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("title") {
                    if (editing) BasicTextField(note.title, { change(note.copy(title = it)) }, Modifier.fillMaxWidth().onFocusChanged { titleFocused = it.isFocused }, textStyle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = NoteInk), decorationBox = { inner -> Box {
                        if (note.kind == "diary") {
                            if (note.title.isBlank() && !titleFocused) Text("未命名", fontSize = 26.sp, color = Quiet.copy(alpha = .55f))
                        } else if (note.title.isEmpty()) Text("标题（可选）", fontSize = 26.sp, color = Quiet.copy(alpha = .55f))
                        inner()
                    } })
                    else if (note.title.isNotBlank() || note.kind == "diary") Text(note.title.ifBlank { "未命名" }, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = if (note.title.isBlank()) Quiet.copy(alpha = .55f) else NoteInk)
                }
                item("meta") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            TagRules.names(note.tags).forEach { tag ->
                                EditorTag(tag, if (editing) ({ editingTag = tag; tagInput = tag; tagDialog = true }) else null,
                                    if (editing) ({ change(note.copy(tags = TagRules.remove(note.tags, tag))) }) else null)
                            }
                            if (editing) Surface(onClick = { editingTag = null; tagInput = ""; tagDialog = true }, shape = RoundedCornerShape(8.dp), color = Color(0xFF293445)) {
                                Text("＋ 添加标签", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color(0xFFF1F5FA), fontSize = 12.sp)
                            }
                        }
                        if (note.mood.isNotBlank()) Text(note.mood, fontSize = 12.sp, color = Quiet)
                    }
                }
                items(shown, key = { it.id }) { block ->
                    if (block.type in setOf("image", "audio", "file", "link")) {
                        MediaBlockCard(block, note, nodes, editing, onView = { viewing = block.id }, onOpen = { target -> onOpen(target) }, onOptions = { assetSettings = block }, onError = { error = it })
                    } else {
                        val heading = block.type.startsWith("heading")
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            when {
                                heading -> IconButton(onClick = { updateBlock(block.copy(collapsed = !block.collapsed)) }, modifier = Modifier.size(30.dp)) { Icon(if (block.collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore, if (block.collapsed) "展开章节" else "折叠章节") }
                                block.type == "check" -> Checkbox(block.checked, { updateBlock(block.copy(checked = it)) }, modifier = Modifier.size(30.dp))
                                block.type == "bullet" -> Text("•", modifier = Modifier.width(24.dp), color = Sky)
                                block.type == "quote" -> Text("│", modifier = Modifier.width(18.dp), color = Sky)
                            }
                            val style = TextStyle(color = NoteInk, fontSize = when (block.type) { "heading1" -> 23.sp; "heading2" -> 20.sp; "code" -> 15.sp; else -> 17.sp },
                                lineHeight = 28.sp, fontWeight = if (heading || block.bold) FontWeight.SemiBold else FontWeight.Normal,
                                fontStyle = if (block.italic) FontStyle.Italic else FontStyle.Normal, fontFamily = if (block.type == "code") FontFamily.Monospace else FontFamily.Default,
                                textDecoration = if (block.checked) TextDecoration.LineThrough else TextDecoration.None)
                            if (editing) BasicTextField(TextFieldValue(block.richText(), selections[block.id]?.let { TextRange(it.start.coerceIn(0, block.text.length), it.end.coerceIn(0, block.text.length)) } ?: TextRange(block.text.length), compositions[block.id]?.takeIf { it.end <= block.text.length }), { value -> selections[block.id] = value.selection; compositions[block.id] = value.composition; if (value.text != block.text) updateBlock(block.editText(value.text)) }, Modifier.weight(1f).onFocusChanged { if (it.isFocused) active = block.id }, textStyle = style,
                                decorationBox = { inner -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { if (block.text.isEmpty()) Text("开始记录…", style = style.copy(color = Quiet.copy(alpha = .5f))); inner() } })
                            else Text(block.richText(reveal), style = style, modifier = Modifier.weight(1f).clickable { editing = true; active = block.id })
                            if (editing && blocks.size > 1) IconButton(onClick = { val next = blocks.filter { it.id != block.id }; change(note.copy(document = encodeBlocks(next), text = blockPlainText(next))) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "移除此段", modifier = Modifier.size(15.dp), tint = Quiet) }
                        }
                    }
                }
                item("append") { if (editing) TextButton(onClick = { insert(NoteBlock()) }) { Text("＋ 新段落") } }
            }
            if (recording.running && recording.owner == note.id) SoftCard(color = Peach) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.error)
                    Text(" ${if (recording.paused) "已暂停" else "录音中"} ${audioTime(recording.elapsed)}", Modifier.weight(1f))
                    TextButton(onClick = { RecordingService.command(context, "pause") }) { Text(if (recording.paused) "继续" else "暂停") }
                    TextButton(onClick = { RecordingService.command(context, "stop") }) { Text("结束") }
                    IconButton(onClick = { RecordingService.command(context, "cancel") }) { Icon(Icons.Default.Close, "取消录音") }
                }
            }
            if (editing) Surface(color = Color.White) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { addMenu = true }) { Icon(Icons.Default.Add, "插入图片或附件") }
                    IconButton(onClick = ::startRecording) { Icon(Icons.Default.MicNone, "录音") }
                    IconButton(onClick = { if (undo.isNotEmpty()) { changed = true; redo.add(snapshot); val s = undo.removeAt(undo.lastIndex); snapshot = s; model.save(decodeNode(org.json.JSONObject(s))) } }, enabled = undo.isNotEmpty()) { Icon(Icons.Default.Undo, "撤销") }
                    IconButton(onClick = { if (redo.isNotEmpty()) { changed = true; undo.add(snapshot); val s = redo.removeAt(redo.lastIndex); snapshot = s; model.save(decodeNode(org.json.JSONObject(s))) } }, enabled = redo.isNotEmpty()) { Icon(Icons.Default.Redo, "重做") }
                    val current = blocks.find { it.id == active }
                    if (current != null && current.type !in setOf("image", "file", "audio", "link")) {
                        IconButton(onClick = { val range = selections[current.id] ?: TextRange.Zero; updateBlock(current.toggleMark(range.start, range.end, "b")) }) { Icon(Icons.Default.FormatBold, "加粗选中文字或整段", tint = if (current.bold) Sky else Quiet) }
                        IconButton(onClick = { val range = selections[current.id] ?: TextRange.Zero; updateBlock(current.toggleMark(range.start, range.end, "i")) }) { Icon(Icons.Default.FormatItalic, "倾斜选中文字或整段", tint = if (current.italic) Sky else Quiet) }
                        var format by remember { mutableStateOf(false) }
                        Box { TextButton(onClick = { format = true }) { Text("格式") }; DropdownMenu(format, { format = false }) {
                            listOf("text" to "正文", "heading1" to "大标题", "heading2" to "小标题", "bullet" to "列表", "check" to "勾选项", "quote" to "引用", "code" to "代码").forEach { (key, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { updateBlock(current.copy(type = key)); format = false }) }
                        } }
                    }
                    TextButton(onClick = { insert(NoteBlock(text = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date()))) }) { Text("时间") }
                }
            }
        }
    }
    if (addMenu) SoftDialog("添加内容", { addMenu = false }) {
        Button(onClick = { importKind = "image"; rebinding = null; addMenu = false; filePicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) { Text("图片") }
        OutlinedButton(onClick = { addMenu = false; startRecording() }, modifier = Modifier.fillMaxWidth()) { Text("录音") }
        OutlinedButton(onClick = { addMenu = false; attachmentMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("添加附件") }
        TextButton(onClick = { addMenu = false }) { Text("关闭") }
    }
    if (attachmentMenu) SoftDialog("添加附件", { attachmentMenu = false }) {
        listOf("file" to "手机文件", "memory" to "某条记忆", "diary" to "某篇日记").forEach { (kind, title) -> OutlinedButton(onClick = {
            attachmentMenu = false
            if (kind == "file") { importKind = "file"; rebinding = null; filePicker.launch(arrayOf("*/*")) } else picker = kind
        }, modifier = Modifier.fillMaxWidth()) { Text(title) } }
        Text("手机文件只建立引用，不复制原文件。", color = Quiet, fontSize = 12.sp)
    }
    picker?.let { kind -> NodePicker("选择${if (kind == "diary") "日记" else "记忆"}", nodes.filter { it.deletedAt == null && it.kind == kind && it.id != note.id }, nodes, { picker = null }) {
        insert(NoteBlock(type = "link", text = it.displayTitle(), target = it.id)); picker = null
    } }
    if (tagDialog) SoftDialog(if (editingTag == null) "添加标签" else "编辑标签", { tagDialog = false }) {
        OutlinedTextField(tagInput, { tagInput = it }, label = { Text("标签") }, prefix = { Text("#") }, placeholder = { Text("输入标签名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        val names = TagRules.names(tagInput)
        Button(onClick = {
            val tags = editingTag?.let { TagRules.replace(note.tags, it, tagInput) } ?: TagRules.add(note.tags, tagInput)
            if (tags != note.tags) change(note.copy(tags = tags))
            tagDialog = false
        }, enabled = names.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(if (editingTag == null) "添加" else "完成") }
        val candidates = nodes.filter { it.deletedAt == null && it.kind in setOf("diary", "memory") }
            .flatMap { TagRules.names(it.tags) }.distinct()
            .filter { it !in TagRules.names(note.tags) && (tagInput.isBlank() || it.contains(tagInput.trim().trimStart('#'), true)) }
        if (candidates.isNotEmpty()) {
            Text("已有标签", fontSize = 12.sp, color = Quiet)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                candidates.forEach { tag -> EditorTag(tag, {
                    val tags = editingTag?.let { TagRules.replace(note.tags, it, tag) } ?: TagRules.add(note.tags, tag)
                    change(note.copy(tags = tags)); tagDialog = false
                }) }
            }
        }
    }
    if (movePicker) MoveDestinationPicker(model, setOf(note.id), { movePicker = false }, onMoved = { saved ->
        if (saved != null) {
            snapshot = jsonObject(saved).toString()
            undo.clear(); redo.clear(); changed = false
            selections.clear(); compositions.clear()
            active = saved.blocks().first().id
        } else error = "记录已不可用，请返回后重试"
    })
    if (preparingMove) SoftDialog("正在保存", {}) { CircularProgressIndicator(); Text("保存后选择移动位置") }
    if (stopBeforeMoving) SoftDialog("录音尚未结束", { stopBeforeMoving = false }) {
        Text("结束录音并保存后移动？")
        Button(onClick = { stopBeforeMoving = false; scope.launch {
            preparingMove = true
            runCatching {
                RecordingService.command(context, "stop")
                withTimeout(30_000) { while (RecordingService.state.value.running) delay(100) }
                prepareMove()
            }.onFailure { error = it.message ?: "录音保存失败，请重试" }
            preparingMove = false
        } }) { Text("结束并保存") }
        TextButton(onClick = { stopBeforeMoving = false }) { Text("继续录制") }
    }
    if (settings) NoteSettingsDialog(note, nodes, { settings = false }) { updated ->
        // Flush editor content first; reconcile on the model and bring the copied blocks back.
        model.save(note)
        model.updateImageSettings(updated) { saved ->
            if (saved.document != note.document) { undo.clear(); redo.clear() }
            change(saved, false)
        }
    }
    if (versions) VersionsDialog(note, model, { versions = false }) { restored ->
        change(restored.copy(id = note.id, kind = note.kind, parentId = note.parentId, day = note.day, deletedAt = null, deleteGroup = null)); versions = false
    }
    if (tableOfContents) SoftDialog("标题目录", { tableOfContents = false }) {
        val headings = shown.filter { it.type.startsWith("heading") }
        if (headings.isEmpty()) Text("将段落设为标题后，会在这里出现。", color = Quiet)
        headings.forEach { block -> TextButton(onClick = { tableOfContents = false; scope.launch { listState.animateScrollToItem(shown.indexOf(block) + 2) } }) { Text(block.text.ifBlank { "未命名标题" }) } }
    }
    if (templates) SoftDialog("追加模板", { templates = false }) {
        listOf("每日复盘" to listOf("今天发生了什么", "值得记住的事", "明天的小目标"), "读书摘录" to listOf("书名与出处", "摘录", "我的想法"), "旅行记录" to listOf("时间与地点", "旅途片段", "下次想做的事")).forEach { (title, headings) ->
            OutlinedButton(onClick = { val next = blocks + headings.flatMap { listOf(NoteBlock(type = "heading1", text = it), NoteBlock()) }; change(note.copy(document = encodeBlocks(next), text = blockPlainText(next))); editing = true; templates = false }, modifier = Modifier.fillMaxWidth()) { Text(title) }
        }
    }
    assetSettings?.let { block ->
        var name by remember(block.id) { mutableStateOf(block.text) }
        var display by remember(block.id) { mutableStateOf(block.display) }
        SoftDialog("素材设置", { assetSettings = null }) {
            OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, modifier = Modifier.fillMaxWidth())
            val enforced = TreeRules.forcedBy(note, nodes)
            if (block.type == "image") ChoiceRow("显示方式", if (enforced != null) "inherit" else display, listOf("inherit" to "跟随记录", "preview" to "正文预览", "card" to "图片卡片")) {
                if (enforced != null) error = "请先前往路径“${TreeRules.path(enforced, nodes)}”解除强制下级设置。" else display = it
            }
            Text(if (block.type == "link") "引用整篇记录，移除卡片不会删除原记录。" else if (block.owned) "素材保存在时笺中" else "仅引用原文件，移动或删除原文件后可能无法打开。", fontSize = 12.sp, color = Quiet)
            Button(onClick = { updateBlock(block.copy(text = name.ifBlank { block.text }, display = display)); assetSettings = null }, modifier = Modifier.fillMaxWidth()) { Text("完成") }
            if (block.type == "image" || block.type == "file") {
                OutlinedButton(onClick = { importKind = block.type; rebinding = block.id; assetSettings = null; filePicker.launch(arrayOf(if (block.type == "image") "image/*" else "*/*")) }) { Text("重新选择原文件") }
                if (block.type == "image" && !block.owned) TextButton(onClick = { scope.launch {
                    runCatching { copyImage(context, block) }.onSuccess { updateBlock(it.copy(text = name, display = display)); assetSettings = null }.onFailure { error = it.message.orEmpty() }
                } }) { Text("保存图片副本到时笺") }
            }
            TextButton(onClick = { val next = blocks.filter { it.id != block.id }.ifEmpty { listOf(NoteBlock()) }; change(note.copy(document = encodeBlocks(next), text = blockPlainText(next))); assetSettings = null }) { Text("移除此素材", color = MaterialTheme.colorScheme.error) }
        }
    }
    viewing?.let { id -> ImageViewer(blocks.filter { it.type == "image" }, id, { viewing = null }) }
    if (stopBeforeLeaving) SoftDialog("录音尚未结束", { stopBeforeLeaving = false }) {
        Text("结束录音并保存后返回？")
        Button(onClick = { stopBeforeLeaving = false; scope.launch { RecordingService.command(context, "stop"); while (RecordingService.state.value.running) delay(100); latestCollect(); leave() } }) { Text("结束并保存") }
        TextButton(onClick = { stopBeforeLeaving = false }) { Text("继续录制") }
    }
    if (error.isNotBlank()) SoftDialog("提示", { error = "" }) { Text(error); TextButton(onClick = { error = "" }) { Text("知道了") } }
}

@Composable
private fun EditorTag(tag: String, onEdit: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF293445)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#$tag", modifier = Modifier.then(if (onEdit != null) Modifier.clickable(onClick = onEdit) else Modifier).padding(start = 10.dp, end = if (onRemove == null) 10.dp else 2.dp, top = 6.dp, bottom = 6.dp), color = Color(0xFFF1F5FA), fontSize = 12.sp)
            if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.size(26.dp)) { Icon(Icons.Default.Close, "从当前记录移除标签 $tag", Modifier.size(13.dp), tint = Color(0xFFD7E0EB)) }
        }
    }
}

fun highlightText(text: String, query: String): AnnotatedString = buildAnnotatedString {
    append(text)
    if (query.isNotBlank()) {
        var index = text.indexOf(query, ignoreCase = true)
        while (index >= 0) { addStyle(SpanStyle(background = Color(0xFFFFE9AD)), index, index + query.length); index = text.indexOf(query, index + query.length, ignoreCase = true) }
    }
}

@Composable fun MediaBlockCard(block: NoteBlock, note: NoteNode, nodes: List<NoteNode>, editing: Boolean, onView: () -> Unit, onOpen: (NoteNode) -> Unit, onOptions: () -> Unit, onError: (String) -> Unit) {
    if (block.type == "audio") {
        SoftCard(color = Mint) { AudioPlayer(block, onError, if (editing) onOptions else null) }
        return
    }
    val context = LocalContext.current
    val display = TreeRules.imageDisplay(block, note, nodes, imageDisplayDefault(context))
    val target = nodes.find { it.id == block.target }
    SoftCard(color = if (block.type == "audio") Mint else Color(0xFFF0F3F9)) {
        if (block.type == "image" && display == "preview") {
            var bitmap by remember(block.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(block.uri) { bitmap = loadImage(context, block.uri) }
            if (bitmap != null) Image(bitmap!!.asImageBitmap(), block.text, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 240.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onView), contentScale = ContentScale.Fit)
            else Text("图片不可访问 · 可在素材设置中重新绑定", color = Quiet, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(when (block.type) { "image" -> Icons.Default.Image; "audio" -> Icons.Default.MicNone; "link" -> Icons.Default.Link; else -> Icons.Default.AttachFile }, null, tint = Sky)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp).clickable {
                when (block.type) {
                    "image" -> onView()
                    "link" -> if (target != null && target.deletedAt == null) onOpen(target) else onError("原记录已删除，可从回收站恢复")
                    "file" -> openFile(context, block)?.let(onError)
                }
            }) {
                Text(if (block.type == "link" && target?.deletedAt == null && target != null) target.displayTitle() else block.text, fontSize = 15.sp)
                Text(when (block.type) {
                    "audio" -> audioTime(block.duration)
                    "link" -> if (target == null || target.deletedAt != null) "原记录已删除" else if (target.kind == "diary") "日记 · ${dateText(target.day ?: target.createdAt)}" else "记忆 · ${TreeRules.path(target, nodes)}"
                    else -> listOf(mediaSize(block.bytes), if (block.owned) "保存在时笺" else "引用原文件").filter { it.isNotBlank() }.joinToString(" · ")
                }, fontSize = 11.sp, color = Quiet)
            }
            if (editing) IconButton(onClick = onOptions, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.MoreHoriz, "素材设置") }
        }
        if (block.type == "audio") AudioPlayer(block, onError)
    }
}
@Composable fun AudioPlayer(block: NoteBlock, onError: (String) -> Unit, onOptions: (() -> Unit)? = null) {
    val context = LocalContext.current
    var playing by remember(block.uri) { mutableStateOf(false) }
    var position by remember(block.uri) { mutableFloatStateOf(0f) }
    var speed by remember { mutableFloatStateOf(1f) }
    val player = remember(block.uri) { MediaPlayer() }
    var ready by remember(block.uri) { mutableStateOf(false) }
    var unavailable by remember(block.uri) { mutableStateOf(false) }
    DisposableEffect(player) {
        runCatching { player.setDataSource(context, Uri.parse(block.uri)); player.setOnPreparedListener { ready = true }; player.setOnErrorListener { _, _, _ -> unavailable = true; ready = false; playing = false; true }; player.setOnCompletionListener { playing = false }; player.prepareAsync() }.onFailure { unavailable = true }
        onDispose { player.release() }
    }
    LaunchedEffect(playing) { while (playing) { position = player.currentPosition.toFloat(); delay(250) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            if (unavailable) onError("录音文件不可播放，原文件可能已丢失或录制曾中断")
            if (ready) runCatching { if (playing) player.pause() else { if (player.currentPosition >= player.duration) player.seekTo(0); player.start() }; playing = !playing }.onFailure { onError("播放失败") }
        }, enabled = ready || unavailable) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "暂停" else "播放") }
        Column(Modifier.weight(1f)) {
            Text(block.text, fontSize = 15.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(if (playing) "${audioTime(position.toLong())} / ${audioTime(block.duration)}" else audioTime(block.duration), fontSize = 11.sp, color = Quiet)
            Slider(value = position.coerceAtMost(block.duration.toFloat().coerceAtLeast(1f)), onValueChange = { position = it; if (ready) player.seekTo(it.toInt()) }, valueRange = 0f..block.duration.toFloat().coerceAtLeast(1f), modifier = Modifier.fillMaxWidth().height(20.dp), enabled = ready)
        }
        TextButton(onClick = { speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }; if (ready) runCatching { player.playbackParams = player.playbackParams.setSpeed(speed); if (!playing) player.pause() } }) { Text("${speed}×") }
        if (onOptions != null) IconButton(onClick = onOptions, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.MoreHoriz, "素材设置") }
    }
    if (unavailable) Text("文件不可播放 · 可移除后重新录制", fontSize = 11.sp, color = Quiet)
}
@Composable fun ImageViewer(images: List<NoteBlock>, selected: String, onClose: () -> Unit) {
    if (images.isEmpty()) return
    val context = LocalContext.current
    var index by remember { mutableIntStateOf(images.indexOfFirst { it.id == selected }.coerceAtLeast(0)) }
    val block = images[index]
    var scale by remember(block.id) { mutableFloatStateOf(1f) }
    var x by remember(block.id) { mutableFloatStateOf(0f) }
    var y by remember(block.id) { mutableFloatStateOf(0f) }
    var bitmap by remember(block.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(block.uri) { bitmap = loadImage(context, block.uri, 4096) }
    Dialog(onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF172030)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭", tint = Color.White) }; Text(block.text, color = Color.White, modifier = Modifier.weight(1f)); Text("${index + 1}/${images.size}", color = Color.White, modifier = Modifier.padding(12.dp)) }
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(0.dp)).pointerInput(block.id) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 6f); x += pan.x; y += pan.y } }, contentAlignment = Alignment.Center) {
                    if (bitmap != null) Image(bitmap!!.asImageBitmap(), block.text, Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = x, translationY = y), contentScale = ContentScale.Fit)
                    else Text("图片不可访问，请重新绑定", color = Color.White)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { TextButton(onClick = { index-- }, enabled = index > 0) { Text("上一张") }; TextButton(onClick = { scale = 1f; x = 0f; y = 0f }) { Text("复位") }; TextButton(onClick = { index++ }, enabled = index < images.lastIndex) { Text("下一张") } }
            }
        }
    }
}
