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
import androidx.compose.runtime.saveable.listSaver
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
fun RichNoteEditor(initial: NoteNode, model: WorkspaceModel, initialEditing: Boolean, reveal: String = "", onBack: () -> Unit, onOpen: (NoteNode) -> Unit, onExport: (Set<String>) -> Unit, onDiaryRoad: ((NoteNode) -> Unit)? = null,
    onMomentPosition: ((NoteNode) -> Unit)? = null, onDiaryInbox: ((Long?) -> Unit)? = null, focusBlockId: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val nodes by model.nodes.collectAsState()
    var snapshot by rememberSaveable(initial.id) { mutableStateOf(jsonObject(initial).toString()) }
    val note = remember(snapshot) { decodeNode(org.json.JSONObject(snapshot)) }
    val diaryEditor = note.kind in setOf("diary", "diary_moment")
    val blocks = remember(note.document, note.text) { note.blocks() }
    var editing by rememberSaveable(initial.id) { mutableStateOf(initialEditing) }
    var changed by rememberSaveable(initial.id) { mutableStateOf(false) }
    var active by remember { mutableStateOf(blocks.first().id) }
    val selections = remember(initial.id) { mutableStateMapOf<String, TextRange>() }
    val compositions = remember(initial.id) { mutableStateMapOf<String, TextRange?>() }
    val historySaver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<String>, String>(
        save = { it.toList() }, restore = { it.toMutableStateList() })
    val undo = rememberSaveable(initial.id, saver = historySaver) { mutableStateListOf<String>() }
    val redo = rememberSaveable(initial.id, saver = historySaver) { mutableStateListOf<String>() }
    val typingStyles = remember(initial.id) { mutableStateMapOf<String, Set<String>>() }
    var more by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var attachmentMenu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    var daySettings by remember { mutableStateOf(false) }
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
    var leaveToRoad by remember { mutableStateOf(false) }
    var startingRecording by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val recording by RecordingService.state.collectAsState()
    val states by model.saveStates.collectAsState()
    val diaryRevision by model.diaryRevision.collectAsState()
    val parentDiary = if (note.kind in setOf("diary", "diary_moment")) {
        remember(nodes, diaryRevision, note.day) { model.currentDiary(note.day ?: dayMillis()) }
    } else null
    val publishedMoment = parentDiary?.diaryMoments()?.find { "moment-${it.id}" == note.id }
    val editingPublishedMoment = remember(initial.id) { initial.kind == "diary_moment" && publishedMoment != null }
    val inboxMoment = parentDiary?.diaryInboxItems()?.firstOrNull { "moment-${it.moment?.id}" == note.id }
    val isUnsentMoment = note.kind == "diary_moment" && !editingPublishedMoment && publishedMoment == null
    val recordingOwner = if (note.kind == "diary_moment") model.diaryRecordingOwner(note) else note.id
    var pendingRecordingOwner by rememberSaveable(initial.id) { mutableStateOf(recordingOwner) }

    fun persistEditor(value: NoteNode) {
        if (value.kind == "diary_moment" && editingPublishedMoment && parentDiary != null)
            model.saveDiaryMomentEdit(parentDiary, value.asDiaryMoment())
        else model.save(value)
    }

    fun editorMoment(): DiaryMoment = (publishedMoment ?: inboxMoment?.moment ?: DiaryMoment(
        id = note.id.removePrefix("moment-"), createdAt = note.createdAt)).copy(
        title = note.title, text = note.text, document = note.document, tags = note.tags, occurredAt = note.diaryOccurredAt,
        sentAt = publishedMoment?.sentAt)

    fun change(next: NoteNode, track: Boolean = true, restoreDiary: Boolean = false) {
        changed = true
        if (track && snapshot != jsonObject(next).toString()) { undo.add(snapshot); if (undo.size > 100) undo.removeAt(0); redo.clear() }
        val updated = next.copy(updatedAt = System.currentTimeMillis())
        snapshot = jsonObject(updated).toString()
        if (restoreDiary && updated.kind == "diary") model.restoreDiarySnapshot(updated) else persistEditor(updated)
    }
    fun updateBlock(block: NoteBlock) = change(note.copy(document = encodeBlocks(blocks.map { if (it.id == block.id) block else it }), text = blockPlainText(blocks.map { if (it.id == block.id) block else it })))
    fun insert(block: NoteBlock) {
        val next = blocks.toMutableList()
        val index = blocks.indexOfFirst { it.id == active }.takeIf { it >= 0 } ?: blocks.lastIndex
        next.add(index + 1, block)
        if (block.type in setOf("image", "video", "audio", "file", "link")) next.add(index + 2, NoteBlock())
        change(note.copy(document = encodeBlocks(next), text = blockPlainText(next)))
        active = next.getOrNull(index + 2)?.id ?: block.id
    }
    suspend fun collectRecordings() {
        val inbox = RecordingService.inbox(context, recordingOwner)
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
        if (recording.running && recording.owner == recordingOwner) { stopBeforeLeaving = true; return }
        scope.launch {
            leaving = true
            runCatching {
                collectRecordings()
                val latest = decodeNode(org.json.JSONObject(snapshot))
                val hasContent = latest.hasDiaryContent()
                if (latest.kind == "diary_moment") { persistEditor(latest); model.flush(latest.id) }
                else if (hasContent || !initialEditing) { if (changed || initialEditing) model.save(decodeNode(org.json.JSONObject(snapshot))); model.flush(note.id) }
                else if (note.kind != "diary" && nodes.any { it.id == note.id }) {
                    model.flush(note.id)
                    model.notes.remove(listOf(note.id))
                }
                withContext(Dispatchers.Main.immediate) {
                    keyboard?.hide(); focus.clearFocus()
                    if (leaveToRoad && onDiaryRoad != null) onDiaryRoad(latest) else onBack()
                }
            }.onFailure { error = it.message ?: "保存失败，请重试" }
            leaving = false
        }
    }
    suspend fun prepareMove() {
        collectRecordings()
        val latest = decodeNode(org.json.JSONObject(snapshot))
        persistEditor(latest)
        model.flush(latest.id)
        withContext(Dispatchers.Main.immediate) {
            keyboard?.hide(); focus.clearFocus(); movePicker = true
        }
    }
    fun openMovePicker() {
        if (preparingMove) return
        val currentRecording = RecordingService.state.value
        if (currentRecording.running && currentRecording.owner == recordingOwner) { stopBeforeMoving = true; return }
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
    var selectedVisual by rememberSaveable { mutableStateOf(listOf<String>()) }
    var selectedFromCamera by rememberSaveable { mutableStateOf(false) }
    var captureChoice by remember { mutableStateOf(false) }
    var removal by remember { mutableStateOf<NoteBlock?>(null) }
    val visualPicker = rememberVisualMediaPicker({ uris -> selectedVisual = uris.map(Uri::toString); selectedFromCamera = false }, { error = it })
    val camera = rememberSystemCapture({ uri -> selectedVisual = listOf(uri.toString()); selectedFromCamera = true }, { error = it })
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val image = importKind == "image"
            val visual = image || importKind == "video"
            val fallback = TreeRules.imagePreference(latestNote, nodes, true, imageStorageDefault(context))
            runCatching { if (visual) importVisualMedia(context, uri, mediaImportDefaults(context, fallback)) else importMedia(context, uri, false, false).copy(type = if (importKind == "audio") "audio" else "file") }.onSuccess { latestInsert(it) }.onFailure { error = it.message ?: "添加失败" }
        }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) runCatching { RecordingService.command(context, "start", pendingRecordingOwner) }.onFailure { error = "无法开始录音" }
        else error = "录音需要麦克风权限，可在系统应用设置中开启"
    }
    fun startRecording() {
        if (recording.running || startingRecording) { error = "已有录音正在进行"; return }
        startingRecording = true
        scope.launch {
            runCatching {
                // Persist the moment's identity before starting a background recording.
                pendingRecordingOwner = if (note.kind == "diary_moment") model.registerDiaryRecording(note) else { model.save(note); note.id }
                val permissions = listOf(Manifest.permission.RECORD_AUDIO) + if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
                if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
                    RecordingService.command(context, "start", pendingRecordingOwner)
                else microphone.launch(permissions.toTypedArray())
            }.onFailure { error = it.message ?: "无法开始录音，请重试" }
            startingRecording = false
        }
    }
    fun publishMoment() {
        if (leaving || !note.hasNoteContent()) return
        if (recording.running && recording.owner == recordingOwner) {
            error = "请先结束录音，再发送片段"; return
        }
        scope.launch {
            leaving = true
            runCatching {
                collectRecordings()
                val latest = decodeNode(org.json.JSONObject(snapshot))
                val parent = model.currentDiary(latest.day ?: error("片段缺少日期"))
                val moment = editorMoment().copy(title = latest.title, text = latest.text, document = latest.document,
                    occurredAt = latest.diaryOccurredAt)
                model.publishDiaryMoment(parent, moment, latest.diaryOccurredAt)
                model.flush("moment-${moment.id}")
                keyboard?.hide(); focus.clearFocus(); onBack()
            }.onFailure { error = it.message ?: "发送失败，内容仍保留在收纳箱" }
            leaving = false
        }
    }
    fun undoEdit() {
        if (undo.isNotEmpty()) { changed = true; redo.add(snapshot); snapshot = undo.removeAt(undo.lastIndex); persistEditor(decodeNode(org.json.JSONObject(snapshot))) }
    }
    fun redoEdit() {
        if (redo.isNotEmpty()) { changed = true; undo.add(snapshot); snapshot = redo.removeAt(redo.lastIndex); persistEditor(decodeNode(org.json.JSONObject(snapshot))) }
    }
    fun finishEditing() {
        if (!editing) { editing = true; return }
        if (!diaryEditor) { editing = false; focus.clearFocus(); keyboard?.hide(); return }
        if (recording.running && recording.owner == recordingOwner) { error = "请先结束录音，再完成编辑"; return }
        scope.launch {
            leaving = true
            runCatching {
                collectRecordings()
                val latest = decodeNode(org.json.JSONObject(snapshot))
                if (latest.kind == "diary_moment" && editingPublishedMoment && parentDiary != null)
                    model.completeDiaryMomentEdit(parentDiary, latest.asDiaryMoment())
                else { persistEditor(latest); model.flush(latest.id) }
                editing = false; undo.clear(); redo.clear(); keyboard?.hide(); focus.clearFocus()
            }.onFailure { error = it.message ?: "保存失败，请重试" }
            leaving = false
        }
    }
    val latestCollect by rememberUpdatedState<suspend () -> Unit> { collectRecordings() }
    LaunchedEffect(note.id) {
        while (true) { runCatching { latestCollect() }.onFailure { error = it.message.orEmpty() }; delay(1000) }
    }
    LaunchedEffect(recording.error) { if (recording.error.isNotBlank()) error = recording.error }
    val visibleIds = visibleBlockIds(blocks, reveal.isNotBlank() || focusBlockId != null)
    val shown = blocks.filter { it.id in visibleIds }
    val preambleItems = if (note.kind == "diary") 1 else 2
    LaunchedEffect(reveal, focusBlockId) {
        if (reveal.isNotBlank() || focusBlockId != null) {
            val index = shown.indexOfFirst { if (focusBlockId != null) it.id == focusBlockId else it.text.contains(reveal, ignoreCase = true) }
            if (index >= 0) listState.scrollToItem(index + preambleItems)
        }
    }
    Surface(Modifier.fillMaxSize(), color = Mist) {
        Column(Modifier.fillMaxSize().then(if (diaryEditor) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val actionModifier = if (note.kind == "diary") Modifier.size(36.dp) else Modifier
                IconButton(onClick = ::leave, enabled = !leaving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "保存并返回") }
                Column(Modifier.weight(1f)) {
                    Text(when (note.kind) {
                        "diary" -> if (note.day == dayMillis()) "今天" else dateText(note.day ?: note.createdAt)
                        "diary_moment" -> "${dateText(note.day ?: note.createdAt)} · 随记片段"
                        else -> TreeRules.ancestors(note, nodes).lastOrNull()?.displayTitle() ?: "记忆"
                    }, maxLines = 2, fontSize = 14.sp, color = Quiet)
                    val saved = states[if (note.kind == "diary_moment") note.parentId else note.id].orEmpty()
                    if (saved.isNotBlank() && (!diaryEditor || saved.contains("失败"))) Text(saved, fontSize = 11.sp, color = if (saved.contains("失败")) MaterialTheme.colorScheme.error else Quiet, modifier = Modifier.clickable { model.retry() })
                }
                if (diaryEditor && editing) {
                    IconButton(onClick = ::undoEdit, enabled = undo.isNotEmpty() && !leaving, modifier = actionModifier) { Icon(Icons.Default.Undo, "撤销") }
                    IconButton(onClick = ::redoEdit, enabled = redo.isNotEmpty() && !leaving, modifier = actionModifier) { Icon(Icons.Default.Redo, "重做") }
                }
                IconButton(onClick = ::finishEditing, enabled = !leaving, modifier = actionModifier) { Icon(if (editing) Icons.Default.Done else Icons.Default.Edit, if (editing && diaryEditor) "完成编辑" else if (editing) "阅读" else "编辑") }
                if (note.kind == "diary") DiaryFavoriteButton(parentDiary?.favorite == true,
                    { model.toggleDiaryFavorite(note) }, enabled = !leaving)
                if (note.kind == "diary" && onDiaryRoad != null && (parentDiary?.diaryRoadEnabled == true || note.day == dayMillis())) IconButton(onClick = { leaveToRoad = true; leave() }, enabled = !leaving, modifier = actionModifier) {
                    Icon(Icons.Default.Route, "查看小路")
                }
                if (isUnsentMoment) TextButton(onClick = ::publishMoment, enabled = !leaving && note.hasNoteContent()) { Text("发送") }
                Box {
                    IconButton(onClick = { more = true }, modifier = actionModifier) { Icon(Icons.Default.MoreVert, "更多") }
                    DropdownMenu(more, { more = false }) {
                        if (note.kind != "diary_moment") DropdownMenuItem(text = { Text(if (note.kind == "diary") "日记设置" else "记录设置") }, onClick = {
                            more = false
                            if (note.kind == "diary" && note.day != dayMillis()) daySettings = true else settings = true
                        })
                        if (note.kind == "diary" && onDiaryInbox != null) DropdownMenuItem(text = { Text("收纳箱") }, onClick = { more = false; scope.launch {
                            runCatching { model.save(note); model.flush(note.id); keyboard?.hide(); focus.clearFocus(); onDiaryInbox(note.day) }
                                .onFailure { error = it.message ?: "保存失败，请重试" }
                        } })
                        if (isUnsentMoment) DropdownMenuItem(text = { Text("暂存到收纳箱") }, onClick = { more = false; leave() })
                        if (note.kind == "memory") DropdownMenuItem(text = { Text("移动到") }, enabled = !preparingMove, onClick = { more = false; openMovePicker() })
                        if (!diaryEditor) DropdownMenuItem(text = { Text("标题目录") }, onClick = { more = false; tableOfContents = true })
                        if (note.kind != "diary_moment") {
                            if (!diaryEditor) DropdownMenuItem(text = { Text("历史版本") }, onClick = { more = false; versions = true })
                            if (note.kind != "diary" || note.day != dayMillis()) DropdownMenuItem(text = { Text("导出这一篇") }, onClick = { more = false; scope.launch { model.save(note); model.flush(note.id); onExport(setOf(note.id)) } })
                            val favorite = if (note.kind == "diary") parentDiary?.favorite == true else note.favorite
                            DropdownMenuItem(text = { Text(if (favorite) "取消收藏" else "收藏") }, onClick = {
                                more = false
                                if (note.kind == "diary") model.toggleDiaryFavorite(note) else change(note.copy(favorite = !note.favorite))
                            })
                        }
                        DropdownMenuItem(text = { Text("从模板追加") }, onClick = { more = false; templates = true })
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (note.kind == "diary_moment" && parentDiary != null) item("moment_time") {
                DiaryMomentTimePanel(parentDiary, editorMoment(), model,
                    onOccurredAt = { change(note.copy(diaryOccurredAt = it)) },
                    onViewPosition = onMomentPosition?.let { view -> { _, _ ->
                        persistEditor(note); keyboard?.hide(); focus.clearFocus(); view(note)
                    } })
                if (isUnsentMoment && inboxMoment != null && note.diaryOccurredAt == null) TextButton(onClick = {
                    change(note.copy(diaryOccurredAt = inboxMoment.createdAt))
                }) { Text("将暂存时间用作发生时间") }
            }
                if (!diaryEditor) item("title") {
                    if (editing) BasicTextField(note.title, { change(note.copy(title = it)) }, Modifier.fillMaxWidth().onFocusChanged { titleFocused = it.isFocused }, textStyle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = NoteInk), decorationBox = { inner -> Box {
                        if (note.kind == "diary") {
                            if (note.title.isBlank() && !titleFocused) Text("未命名", fontSize = 26.sp, color = Quiet.copy(alpha = .55f))
                        } else if (note.title.isEmpty()) Text("标题（可选）", fontSize = 26.sp, color = Quiet.copy(alpha = .55f))
                        inner()
                    } })
                    else if (note.title.isNotBlank() || note.kind == "diary") Text(note.title.ifBlank { "未命名" }, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = if (note.title.isBlank()) Quiet.copy(alpha = .55f) else NoteInk)
                }
                item("meta") { if (note.kind != "diary_moment") {
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
                }
                items(shown, key = { it.id }) { block ->
                    Column(if (block.id == focusBlockId) Modifier.fillMaxWidth().testTag("media-focused-${block.id}").border(2.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(8.dp)).padding(6.dp) else Modifier.fillMaxWidth()) {
                    if (block.type in setOf("image", "video", "audio", "file", "link")) {
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
                            if (editing) BasicTextField(TextFieldValue(block.richText(), selections[block.id]?.let { TextRange(it.start.coerceIn(0, block.text.length), it.end.coerceIn(0, block.text.length)) } ?: TextRange(block.text.length), compositions[block.id]?.takeIf { it.end <= block.text.length }), { value -> selections[block.id] = value.selection; compositions[block.id] = value.composition; if (value.text != block.text) updateBlock(typingStyles[block.id]?.let { block.editText(value.text, it) } ?: block.editText(value.text)) }, Modifier.weight(1f).onFocusChanged { if (it.isFocused) active = block.id }, textStyle = style,
                                decorationBox = { inner -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { if (block.text.isEmpty()) Text("开始记录…", style = style.copy(color = Quiet.copy(alpha = .5f))); inner() } })
                            else Text(block.richText(reveal), style = style, modifier = Modifier.weight(1f).clickable { editing = true; active = block.id })
                            if (editing && blocks.size > 1) IconButton(onClick = { removal = block }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "移除此段", modifier = Modifier.size(15.dp), tint = Quiet) }
                        }
                    }
                }
                }
                item("append") { if (editing) TextButton(onClick = { insert(NoteBlock()) }) { Text("＋ 新段落") } }
            }
            if (recording.running && recording.owner == recordingOwner) SoftCard(color = Peach) {
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
                    if (!diaryEditor) {
                        IconButton(onClick = ::undoEdit, enabled = undo.isNotEmpty()) { Icon(Icons.Default.Undo, "撤销") }
                        IconButton(onClick = ::redoEdit, enabled = redo.isNotEmpty()) { Icon(Icons.Default.Redo, "重做") }
                    }
                    val current = blocks.find { it.id == active }
                    if (current != null && current.type !in setOf("image", "video", "file", "audio", "link")) {
                        fun formatMark(style: String) {
                            val range = selections[current.id] ?: TextRange.Zero
                            if (diaryEditor && range.collapsed) {
                                val previous = typingStyles[current.id] ?: emptySet()
                                typingStyles[current.id] = if (style in previous) previous - style else previous + style
                            } else updateBlock(current.toggleMark(range.start, range.end, style))
                        }
                        IconButton(onClick = { formatMark("b") }) { Icon(Icons.Default.FormatBold, "加粗选中文字或整段", tint = if (current.bold || "b" in typingStyles[current.id].orEmpty()) Sky else Quiet) }
                        IconButton(onClick = { formatMark("i") }) { Icon(Icons.Default.FormatItalic, "倾斜选中文字或整段", tint = if (current.italic || "i" in typingStyles[current.id].orEmpty()) Sky else Quiet) }
                        if (diaryEditor) IconButton(onClick = { formatMark("h") }) { Icon(Icons.Default.Highlight, "高亮", tint = if ("h" in typingStyles[current.id].orEmpty()) Sky else Quiet) }
                        var format by remember { mutableStateOf(false) }
                        Box { TextButton(onClick = { format = true }) { Text("格式") }; DropdownMenu(format, { format = false }) {
                            listOf("text" to "正文", "heading1" to "大标题", "heading2" to "小标题", "bullet" to "列表", "check" to "勾选项", "quote" to "引用", "code" to "代码").forEach { (key, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { updateBlock(current.copy(type = key)); format = false }) }
                        } }
                    }
                    TextButton(onClick = { insert(NoteBlock(text = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date()))) }) { Text("时间") }
                }
            }
        }
    }
    if (addMenu) SoftDialog("添加内容", { addMenu = false }) {
        Button(onClick = { rebinding = null; addMenu = false; visualPicker() }, modifier = Modifier.fillMaxWidth()) { Text("从相册选择图片或视频") }
        if (diaryEditor && appPreferences(context).getBoolean("diaryAllowCapture", false)) OutlinedButton(onClick = {
            rebinding = null; addMenu = false; captureChoice = true
        }, modifier = Modifier.fillMaxWidth()) { Text("拍摄照片或视频") }
        OutlinedButton(onClick = { addMenu = false; attachmentMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("添加附件") }
        TextButton(onClick = { addMenu = false }) { Text("关闭") }
    }
    if (captureChoice) SoftDialog("使用系统相机", { captureChoice = false }) {
        Text("拍摄完成后确认是否导入。选择不加入，拍摄原件仍在系统相册。", color = Quiet)
        OutlinedButton(onClick = { captureChoice = false; camera(false) }) { Text("拍摄照片") }
        OutlinedButton(onClick = { captureChoice = false; camera(true) }) { Text("拍摄视频") }
    }
    if (selectedVisual.isNotEmpty()) {
        val uris = selectedVisual.map(Uri::parse)
        val types = uris.map { context.contentResolver.getType(it).orEmpty() }
        val fallback = TreeRules.imagePreference(note, nodes, true, imageStorageDefault(context))
        MediaImportDialog(mediaImportDefaults(context, fallback), types.any { it.startsWith("image/") }, types.any { it.startsWith("video/") },
            title = if (selectedFromCamera) "是否导入拍摄素材？" else "导入照片或视频",
            previews = if (selectedFromCamera) uris.mapIndexed { i, uri -> NoteBlock(type = if (types[i].startsWith("video/")) "video" else "image", uri = uri.toString(), text = "拍摄素材") } else emptyList(),
            onDismiss = { selectedVisual = emptyList() }, onConfirm = { policy ->
                selectedVisual = emptyList()
                scope.launch { runCatching { uris.forEach { latestInsert(importVisualMedia(context, it, policy)) } }.onFailure { error = it.message ?: "添加失败，已导入内容保留" } }
            })
    }
    removal?.let { block -> AlertDialog(onDismissRequest = { removal = null }, title = { Text("是否移除此${mediaKindLabel(block.type)}？") },
        text = { Text("从当前记录移除，原文件保留。可使用撤销恢复。") },
        confirmButton = { TextButton(onClick = {
            val next = blocks.filter { it.id != block.id }.ifEmpty { listOf(NoteBlock()) }
            change(note.copy(document = encodeBlocks(next), text = blockPlainText(next))); removal = null
        }) { Text("移除") } }, dismissButton = { TextButton(onClick = { removal = null }) { Text("取消") } }) }
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
    if (daySettings && parentDiary != null) DiaryDaySettings(parentDiary, model,
        onDismiss = { daySettings = false },
        onRoadChanged = { enabled ->
            daySettings = false
            if (enabled && onDiaryRoad != null) { leaveToRoad = true; leave() }
        }, onExport = { ids -> scope.launch {
            runCatching { model.save(note); model.flush(note.id); onExport(ids) }
                .onFailure { error = it.message ?: "保存失败，请重试" }
        } }, onInbox = onDiaryInbox)
    if (versions) VersionsDialog(note, model, { versions = false }) { restored ->
        change(restored.copy(id = note.id, kind = note.kind, parentId = note.parentId, day = note.day, deletedAt = null, deleteGroup = null), restoreDiary = true); versions = false
    }
    if (tableOfContents) SoftDialog("标题目录", { tableOfContents = false }) {
        val headings = shown.filter { it.type.startsWith("heading") }
        if (headings.isEmpty()) Text("将段落设为标题后，会在这里出现。", color = Quiet)
        headings.forEach { block -> TextButton(onClick = { tableOfContents = false; scope.launch { listState.animateScrollToItem(shown.indexOf(block) + preambleItems) } }) { Text(block.text.ifBlank { "未命名标题" }) } }
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
            if (block.type in setOf("image", "video", "audio", "file")) {
                OutlinedButton(onClick = { importKind = block.type; rebinding = block.id; assetSettings = null; filePicker.launch(arrayOf(when (block.type) { "image" -> "image/*"; "video" -> "video/*"; "audio" -> "audio/*"; else -> "*/*" })) }) { Text("重新选择原文件") }
                if (block.type == "image" && !block.owned) TextButton(onClick = { scope.launch {
                    runCatching { copyImage(context, block) }.onSuccess { updateBlock(it.copy(text = name, display = display)); assetSettings = null }.onFailure { error = it.message.orEmpty() }
                } }) { Text("保存图片副本到时笺") }
            }
            TextButton(onClick = { removal = block; assetSettings = null }) { Text("移除此素材", color = MaterialTheme.colorScheme.error) }
        }
    }
    viewing?.let { id -> ImageViewer(blocks.filter { it.type == "image" }, id, { viewing = null }) }
    if (stopBeforeLeaving) SoftDialog("录音尚未结束", { stopBeforeLeaving = false; leaveToRoad = false }) {
        Text("结束录音并保存后返回？")
        Button(onClick = { stopBeforeLeaving = false; scope.launch { RecordingService.command(context, "stop"); while (RecordingService.state.value.running) delay(100); latestCollect(); leave() } }) { Text("结束并保存") }
        TextButton(onClick = { stopBeforeLeaving = false; leaveToRoad = false }) { Text("继续录制") }
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
    if (block.type == "video") {
        var playing by remember { mutableStateOf(false) }
        SoftCard { Row(verticalAlignment = Alignment.CenterVertically) {
            VisualMediaTile(block) { playing = true }
            Column(Modifier.weight(1f).padding(8.dp)) { Text(block.text); Text(if (block.owned) "保存在时笺" else "引用原视频", color = Quiet) }
            if (editing) IconButton(onClick = onOptions) { Icon(Icons.Default.MoreHoriz, "素材设置") }
        } }
        if (playing) VideoViewer(block, onError) { playing = false }
        return
    }
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
