package com.shijiannote.app

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.shijiannote.app.data.NoteNode
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun DiaryRoadScreen(initial: NoteNode, model: WorkspaceModel, onBack: () -> Unit,
    onSummary: (NoteNode) -> Unit, onMoment: (NoteNode, Boolean) -> Unit,
    onExport: (Set<String>) -> Unit = {}, onInbox: ((Long?) -> Unit)? = null,
    previewMoment: DiaryMoment? = null, onPreviewBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val prefs = appPreferences(context)
    val nodes by model.nodes.collectAsState()
    val saveStates by model.saveStates.collectAsState()
    val revision by model.diaryRevision.collectAsState()
    val day = initial.day ?: dayMillis()
    val note = remember(nodes, revision, day) { model.currentDiary(day) }
    val order = prefs.getString("diary_time_sort", "occurred") ?: "occurred"
    val moments = sortedDiaryMoments(note.diaryMoments(), order)
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val snackbar = remember { SnackbarHostState() }
    var settings by rememberSaveable(day) { mutableStateOf(false) }
    var localInbox by rememberSaveable(day) { mutableStateOf(false) }
    var recall by rememberSaveable(day) { mutableStateOf(false) }
    var litCount by rememberSaveable(day) { mutableIntStateOf(0) }
    var pendingScroll by rememberSaveable(day) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var deletion by remember { mutableStateOf<DiaryMoment?>(null) }
    var sending by remember { mutableStateOf(false) }
    var viewingPosition by rememberSaveable(day) { mutableStateOf(false) }
    var timeOpen by rememberSaveable(day) { mutableStateOf(false) }
    val activeKey = "diary_quick_draft_" + day
    var draftId by rememberSaveable(day) { mutableStateOf(run {
        val previousId = prefs.getString(activeKey, null)
        val previous = note.diaryInboxItems().firstOrNull { it.moment?.id == previousId && it.status == "draft" }?.moment
        val id = previousId?.takeIf { previous != null && canResumeDiaryTextDraft(previous) } ?: UUID.randomUUID().toString()
        if (previewMoment == null) prefs.edit().putString(activeKey, id).apply()
        id
    }) }
    val resumed = remember(day, draftId) { note.diaryInboxItems().firstOrNull { it.moment?.id == draftId && it.status == "draft" }?.moment?.takeIf(::canResumeDiaryTextDraft) }
    var startedAt by rememberSaveable(day, draftId) { mutableLongStateOf(resumed?.createdAt ?: System.currentTimeMillis()) }
    var text by rememberSaveable(day, draftId) { mutableStateOf(resumed?.text ?: "") }
    var wasStored by rememberSaveable(day, draftId) { mutableStateOf(resumed != null) }
    var occurredAt by rememberSaveable(day, draftId) { mutableStateOf(resumed?.occurredAt) }
    var background by remember(note.diaryRoadTheme, note.diaryRoadBackground) { mutableStateOf<Bitmap?>(null) }
    val isToday = day == dayMillis()
    val preview = previewMoment ?: if (viewingPosition) DiaryMoment(draftId, startedAt, text = text, occurredAt = occurredAt, sentAt = null) else null
    val neighbors = preview?.let { diaryNeighbors(moments, it, order, System.currentTimeMillis()) }
    val highlighted = if (preview != null) setOfNotNull(neighbors?.previous?.id, neighbors?.next?.id) else emptySet()
    LaunchedEffect(note.diaryRoadTheme, note.diaryRoadBackground) { background = DiaryBackgroundLibrary.bitmap(context, note.diaryRoadTheme, note.diaryRoadBackground) }
    LaunchedEffect(day) {
        val current = model.currentDiary(day)
        if (previewMoment == null && day == dayMillis() && !current.hasDiaryContent() && !current.diaryRoadEnabled && current.diaryRoadBackground.isBlank() && current.diaryRoadTheme == "forest") {
            model.updateDiaryRoadAppearance(current, DiaryBackgroundLibrary.defaultTheme(context), DiaryBackgroundLibrary.defaultUri(context))
            model.updateDiaryRoadLayout(model.currentDiary(day), DiaryBackgroundLibrary.defaultLayout(context))
        }
    }
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); error = null } }
    LaunchedEffect(moments.size) { litCount = litCount.coerceAtMost(moments.size); if (moments.isEmpty()) recall = false }
    LaunchedEffect(moments.map { it.id }, pendingScroll) {
        pendingScroll?.let { id -> val index = moments.indexOfFirst { it.id == id }; if (index >= 0) { list.animateScrollToItem(index + 1); pendingScroll = null } }
    }
    LaunchedEffect(preview?.id, preview?.occurredAt, preview?.sentAt, order) {
        if (preview != null) {
            keyboard?.hide()
            val anchor = neighbors?.previous ?: neighbors?.next
            val index = moments.indexOfFirst { it.id == anchor?.id }
            list.animateScrollToItem(if (index >= 0) index + 1 else 0)
        }
    }
    fun latest() = model.currentDiary(day)
    fun draft(content: String = text, at: Long? = occurredAt) = DiaryMoment(draftId, startedAt, text = content,
        document = encodeBlocks(listOf(NoteBlock(text = content))), occurredAt = at, sentAt = null)
    fun resetCapture() {
        text = ""; occurredAt = null; viewingPosition = false; wasStored = false
        draftId = UUID.randomUUID().toString()
        prefs.edit().putString(activeKey, draftId).apply()
    }
    fun store(content: String = text, at: Long? = occurredAt): Boolean {
        val current = latest()
        val existing = current.diaryInboxItems().firstOrNull { it.moment?.id == draftId }
        if (wasStored && existing == null && text.isNotBlank() || current.diaryMoments().any { it.id == draftId } || existing != null &&
            (existing.status != "draft" || existing.moment?.let { !canResumeDiaryTextDraft(it) } == true)) {
            resetCapture()
            return true // Preserve the richer or explicitly moved item rather than overwriting it on departure.
        }
        return runCatching {
            prefs.edit().putString(activeKey, draftId).apply()
            model.saveDiaryDraft(current, draft(content, at))
            wasStored = content.isNotBlank()
        }.onFailure { error = it.message ?: "草稿暂存失败，请重试" }.isSuccess
    }
    fun leave(action: () -> Unit) {
        if (!sending && store()) { keyboard?.hide(); action() }
    }
    fun openRich() {
        if (sending || !store()) return
        val virtual = draft().asNote(latest())
        resetCapture()
        onMoment(virtual, true)
    }
    fun publish() {
        if (text.isBlank() || sending || !store() || text.isBlank()) return
        val captured = draft()
        sending = true
        scope.launch {
            runCatching { model.publishDiaryMoment(latest(), captured, captured.occurredAt) }
                .onSuccess { pendingScroll = it.id; resetCapture() }
                .onFailure { error = it.message ?: "发送失败，片段仍在收纳箱" }
            sending = false
        }
    }
    // The same draft may have been edited, sent or deleted from the global inbox while this page was away.
    // Never revive a stale composer snapshot over that explicit action.
    LaunchedEffect(revision, draftId, sending) {
        if (previewMoment == null && !sending && text.isNotBlank()) {
            val current = latest()
            val active = current.diaryInboxItems().firstOrNull { it.moment?.id == draftId }
            val changedElsewhere = active?.moment?.let { saved ->
                saved.text != text || saved.occurredAt != occurredAt || !canResumeDiaryTextDraft(saved)
            } ?: false
            if (current.diaryMoments().any { it.id == draftId } || active == null || active.status != "draft" || changedElsewhere) resetCapture()
        }
    }
    if (localInbox && previewMoment == null) {
        DiaryInboxScreen(model, day, { localInbox = false }, { virtual -> localInbox = false; onMoment(virtual, true) })
        return
    }
    BackHandler(enabled = !settings && !timeOpen && deletion == null) {
        when {
            previewMoment != null -> onPreviewBack?.invoke()
            viewingPosition -> viewingPosition = false
            keyboardVisible -> keyboard?.hide()
            recall -> recall = false
            else -> leave(onBack)
        }
    }
    Scaffold(containerColor = Color(0xFFF2F1E8), snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Surface(color = Color(0xFFFCFCF8).copy(alpha = .96f)) {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { when { previewMoment != null -> onPreviewBack?.invoke(); viewingPosition -> viewingPosition = false; else -> leave(onBack) } }, enabled = !sending) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(if (preview != null) "在小路中查看位置" else if (isToday) "今日小路" else "这一天的小路", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(dateText(day), fontSize = 12.sp, color = Quiet)
                        if (preview == null) saveStates[note.id]?.let { state -> Text(state, fontSize = 11.sp, color = if (state.contains("失败")) MaterialTheme.colorScheme.error else Quiet,
                            modifier = Modifier.clickable(enabled = state.contains("失败")) { model.retry() }) }
                    }
                    if (preview == null) {
                        IconButton(onClick = { leave { onSummary(latest()) } }, enabled = !sending) { Icon(Icons.Default.MenuBook, if (isToday) "今日结语" else "这一天的结语") }
                        IconButton(onClick = { if (store()) settings = true }, enabled = !sending) { Icon(Icons.Default.Settings, "小路设置") }
                    }
                }
                if (preview != null && neighbors != null) Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    DiaryNeighborOverview(neighbors, day, order)
                    Text("蓝色边框标出前后邻居，尚未发布你的片段。", fontSize = 11.sp, color = Sky)
                }
                if (recall && preview == null) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("已走过 " + litCount.coerceAtMost(moments.size) + " / " + moments.size + " 处", fontSize = 12.sp, color = Quiet, modifier = Modifier.weight(1f))
                    TextButton(onClick = { recall = false }) { Text("退出回溯") }
                }
            }
        }
    }, bottomBar = {
        Surface(color = Color(0xFFFCFCF8), shadowElevation = 6.dp) {
            Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    preview != null -> Button(onClick = { if (previewMoment != null) onPreviewBack?.invoke() else viewingPosition = false }, modifier = Modifier.fillMaxWidth()) { Text("继续编辑") }
                    recall -> Button(onClick = {
                        if (litCount < moments.size) { litCount++; scope.launch { list.animateScrollToItem(litCount) } } else recall = false
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (litCount < moments.size) "下一处" else "走完了 · 返回小路") }
                    else -> {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconButton(onClick = ::openRich, enabled = !sending) { Icon(Icons.Default.AddCircleOutline, "添加图片、语音、文件或丰富片段") }
                            OutlinedTextField(text, { text = it; store(it) }, modifier = Modifier.weight(1f), enabled = !sending,
                                placeholder = { Text(if (isToday) "留下这一刻…" else "补记这一天…") }, maxLines = 4, shape = RoundedCornerShape(18.dp))
                            IconButton(onClick = ::publish, enabled = text.isNotBlank() && !sending) { if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Filled.Send, "保存片段") }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { if (text.isNotBlank() && store()) resetCapture() }, enabled = text.isNotBlank() && !sending) { Text("暂存下一条") }
                            Text("输入自动留在收纳箱，发送后出现在小路。", fontSize = 10.sp, color = Quiet, modifier = Modifier.weight(1f))
                        }
                        TextButton(onClick = { keyboard?.hide(); timeOpen = true }) { Text(occurredAt?.let { "发生 " + diaryTimestamp(it, day) } ?: "发生时间：跟随发送 · 设置") }
                    }
                }
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val info = list.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()
            val progress = if (first == null || info.totalItemsCount <= 1) 0f else ((first.index + (-first.offset).coerceAtLeast(0).toFloat() / first.size.coerceAtLeast(1)) / (info.totalItemsCount - 1)).coerceIn(0f, 1f)
            DiaryPainting(background, progress, Modifier.fillMaxSize())
            if (recall) Box(Modifier.fillMaxSize().background(Color(0xFF142B34).copy(alpha = .72f)))
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 36.dp)) {
                item(key = "start") {
                    Box(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
                        Surface(shape = RoundedCornerShape(24.dp), color = Color.White.copy(alpha = .85f)) {
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("从这里出发", fontWeight = FontWeight.Medium)
                                Text(if (moments.isEmpty()) "一句话、一张照片，留住沿途的瞬间" else moments.size.toString() + " 个片段 · 按" + (if (order == "sent") "发送" else "发生") + "时间排列", fontSize = 11.sp, color = Quiet)
                            }
                        }
                    }
                }
                itemsIndexed(moments, key = { _, moment -> moment.id }) { index, moment ->
                    val right = note.diaryRoadLayout == "right" || note.diaryRoadLayout == "alternate" && index % 2 == 1
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 20.dp), horizontalArrangement = if (right) Arrangement.End else Arrangement.Start) {
                        DiaryMomentCard(moment, note, nodes, order, !recall || index < litCount, recall, moment.id in highlighted,
                            Modifier.fillMaxWidth(.65f), readOnly = preview != null,
                            onEdit = { leave { onMoment(moment.asNote(latest()), false) } },
                            onRetract = { if (store()) runCatching { model.retractDiaryMoment(latest(), moment.id) }.onFailure { error = it.message } },
                            onDelete = { deletion = moment }, onError = { error = it })
                    }
                }
                item(key = "end") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(if (isToday) "小路还在继续" else "也可以在后来，补上这一天", fontSize = 12.sp, color = if (recall) Color.White else Color(0xFF536B53),
                        modifier = Modifier.background(if (recall) Color(0xFF334743) else Color.White.copy(alpha = .8f), RoundedCornerShape(20.dp)).padding(12.dp))
                } }
            }
            if (moments.isNotEmpty() && preview == null && !recall) FloatingActionButton(onClick = { recall = true; litCount = 0; scope.launch { list.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(42.dp), containerColor = Color(0xFFFFFCED).copy(alpha = .9f)) { Icon(Icons.Default.AutoAwesome, "暗背景回溯", Modifier.size(18.dp)) }
        }
    }
    if (settings) DiaryDaySettings(note, model, { settings = false }, { enabled -> if (!enabled) onSummary(latest()) }, onExport,
        onInbox = { date -> settings = false; resetCapture(); if (onInbox != null) onInbox(date) else localInbox = true })
    if (timeOpen && previewMoment == null) SoftDialog("片段时间与位置", { timeOpen = false }) {
        DiaryMomentTimePanel(latest(), draft(), model, { at -> occurredAt = at; store(at = at) },
            { _, _ -> if (store()) { timeOpen = false; viewingPosition = true } }, initiallyExpanded = true)
        TextButton(onClick = { timeOpen = false }, modifier = Modifier.align(Alignment.End)) { Text("完成") }
    }
    deletion?.let { moment -> AlertDialog(onDismissRequest = { deletion = null }, title = { Text("将这个片段移入收纳箱？") }, text = { Text("删除后仍可恢复。结语与其他片段会保留。") },
        confirmButton = { TextButton(onClick = { runCatching { model.deleteDiaryMoment(latest(), moment.id); deletion = null }.onFailure { error = it.message } }) { Text("移入收纳箱") } },
        dismissButton = { TextButton(onClick = { deletion = null }) { Text("取消") } }) }
}

/** Pan one painting calmly: no stretched pixels, repeated inverted scenery or tile seams. */
@Composable
private fun DiaryPainting(bitmap: Bitmap?, progress: Float, modifier: Modifier) {
    val p by animateFloatAsState(progress, animationSpec = tween(140), label = "paintingPan")
    Canvas(modifier) {
        bitmap?.let { image ->
            val scale = max(size.width / image.width, size.height / image.height)
            val width = (image.width * scale).roundToInt()
            val height = (image.height * scale).roundToInt()
            drawImage(image.asImageBitmap(), dstOffset = IntOffset(((size.width - width) / 2f).roundToInt(), (-(height - size.height) * p).roundToInt()), dstSize = IntSize(width, height))
        }
    }
}

@Composable
private fun DiaryMomentCard(moment: DiaryMoment, parent: NoteNode, nodes: List<NoteNode>, order: String,
    lit: Boolean, recall: Boolean, highlighted: Boolean, modifier: Modifier, readOnly: Boolean,
    onEdit: () -> Unit, onRetract: () -> Unit, onDelete: () -> Unit, onError: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val at = if (order == "sent") moment.sentAt else moment.occurredAt ?: moment.sentAt
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = if (recall && !lit) Color(0xFFE4E8E6) else Color(0xFFFFFEF9).copy(alpha = .96f),
        border = BorderStroke(if (highlighted || recall && lit) 2.dp else 1.dp, if (highlighted) Sky else if (recall && lit) Color(0xFFE2BD6F) else Color(0xFFC9CBBF)), shadowElevation = 2.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (order == "sent") "发送 " else "发生 ") + diaryTimestamp(at ?: moment.createdAt, parent.day), fontSize = 11.sp, color = Quiet, modifier = Modifier.weight(1f))
                if (!readOnly) Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.MoreHoriz, "片段操作", Modifier.size(18.dp)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("查看与编辑") }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("撤回到收纳箱") }, onClick = { menu = false; onRetract() })
                        DropdownMenuItem(text = { Text("删除片段") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            if (moment.title.isNotBlank()) Text(moment.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.clickable(enabled = !readOnly, onClick = onEdit), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Column(Modifier.fillMaxWidth().clickable(enabled = !readOnly, onClick = onEdit)) { DiaryMomentContentPreview(moment, parent, nodes, { if (!readOnly) onEdit() }, onError) }
        }
    }
}

/** Keep mixed text/media blocks in their original order. */
@Composable
fun DiaryMomentContentPreview(moment: DiaryMoment, parent: NoteNode, nodes: List<NoteNode>, onOpen: (NoteNode) -> Unit,
    onError: (String) -> Unit, maxBlocks: Int = 8) {
    val context = LocalContext.current
    val blocks = remember(moment.document, moment.text) { runCatching { decodeBlocks(moment.document, moment.text) }.getOrElse { listOf(NoteBlock(text = moment.text)) } }
    var imageId by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        blocks.filter { it.text.isNotBlank() || it.uri.isNotBlank() || it.target.isNotBlank() }.take(maxBlocks).forEach { block ->
            when (block.type) {
                "image" -> {
                    var bitmap by remember(block.uri) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(block.uri) { bitmap = loadImage(context, block.uri, 1000) }
                    bitmap?.let { Image(it.asImageBitmap(), block.text, Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 220.dp).clickable { imageId = block.id }, contentScale = ContentScale.Fit) }
                        ?: Text(block.text.ifBlank { "图片" }, fontSize = 12.sp, color = Quiet)
                }
                "audio" -> AudioPlayer(block, onError)
                "file" -> TextButton(onClick = { openFile(context, block)?.let(onError) }, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Default.AttachFile, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(block.text.ifBlank { "文件" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                "link" -> {
                    val target = nodes.find { it.id == block.target && it.deletedAt == null }
                    TextButton(onClick = { if (target != null) onOpen(target) else onError("关联记录已删除") }, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Default.Link, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(target?.displayTitle() ?: block.text.ifBlank { "关联记录" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                else -> Text((if (block.type == "bullet") "• " else if (block.type == "check") if (block.checked) "☑ " else "☐ " else "") + block.text,
                    fontSize = if (block.type.startsWith("heading")) 17.sp else 15.sp, lineHeight = 23.sp, color = NoteInk,
                    fontWeight = if (block.bold || block.type.startsWith("heading")) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = if (blocks.size == 1) 6 else 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (blocks.size > maxBlocks || blocks.any { it.text.length > 180 }) Text("点开阅读全文", color = Sky, fontSize = 11.sp)
    }
    imageId?.let { id -> ImageViewer(blocks.filter { it.type == "image" }, id) { imageId = null } }
}

