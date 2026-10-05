package com.shijiannote.app
import androidx.compose.foundation.relocation.bringIntoViewRequester

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shijiannote.app.data.NoteNode
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun DiaryRoadScreen(initial: NoteNode, model: WorkspaceModel, onBack: () -> Unit,
    onSummary: (NoteNode) -> Unit, onMoment: (NoteNode, Boolean) -> Unit,
    onExport: (Set<String>) -> Unit = {}, onInbox: ((Long?) -> Unit)? = null,
    previewMoment: DiaryMoment? = null, onPreviewBack: (() -> Unit)? = null,
    resumeMoment: NoteNode? = null, onResumeConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = appPreferences(context)
    val nodes by model.nodes.collectAsState()
    val revision by model.diaryRevision.collectAsState()
    val day = initial.day ?: dayMillis()
    val note = remember(nodes, revision, day) { model.currentDiary(day) }
    val order = prefs.getString("diary_time_sort", "occurred") ?: "occurred"
    val moments = sortedDiaryMoments(note.diaryMoments(), order)
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val keyboardBottom = WindowInsets.ime.getBottom(density)
    val keyboardVisible = keyboardBottom > 0
    val initialHeaderHeight = 52.dp + with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    var headerHeight by remember { mutableStateOf(initialHeaderHeight) }
    val snackbar = remember { SnackbarHostState() }
    var settings by rememberSaveable(day) { mutableStateOf(false) }
    var localInbox by rememberSaveable(day) { mutableStateOf(false) }
    var recall by rememberSaveable(day) { mutableStateOf(false) }
    var litCount by rememberSaveable(day) { mutableIntStateOf(0) }
    var pendingScroll by rememberSaveable(day) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var deletion by remember { mutableStateOf<DiaryMoment?>(null) }
    var viewingPosition by rememberSaveable(day) { mutableStateOf(false) }
    var timeOpen by rememberSaveable(day) { mutableStateOf(false) }
    var departure by remember { mutableStateOf(false) }
    var background by remember(note.diaryRoadTheme, note.diaryRoadBackground) { mutableStateOf<Bitmap?>(null) }
    val composer = rememberDiaryMomentComposer(model, day) { error = it }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, composer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (!composer.busy && (composer.hasContent || composer.moment.occurredAt != null ||
                        composer.editingPublished || composer.recordOwner != null)) {
                    runCatching { composer.persist() }.onFailure { error = it.message ?: "草稿保存失败，请重试" }
                }
                scope.launch {
                    runCatching { model.flush(model.currentDiary(day).id) }
                        .onFailure { error = it.message ?: "草稿尚未保存，请重试" }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val isToday = day == dayMillis()
    val preview = previewMoment ?: composer.moment.takeIf { viewingPosition }
    val neighbors = preview?.let { diaryNeighbors(moments, it, order, System.currentTimeMillis()) }
    val highlighted = if (preview != null) setOfNotNull(neighbors?.previous?.id, neighbors?.next?.id) else emptySet()
    fun latest() = model.currentDiary(day)
    fun operation(action: suspend () -> Unit) {
        if (composer.busy || composer.importing) return
        keyboard?.hide()
        composer.busy = true
        scope.launch {
            try { action() } catch (failure: Exception) { error = failure.message ?: "保存失败，片段仍在收纳箱" }
            finally { composer.busy = false }
        }
    }
    fun hasWorkingContent() = composer.editingPublished || composer.hasContent || composer.moment.occurredAt != null ||
        RecordingService.state.value.let { it.running && it.owner == composer.recordOwner }
    fun leave(action: () -> Unit) {
        operation { composer.stage(context, clear = true); action() }
    }
    fun back() {
        when {
            composer.busy || composer.importing -> Unit
            previewMoment != null -> onPreviewBack?.invoke()
            viewingPosition -> viewingPosition = false
            keyboardVisible -> keyboard?.hide()
            recall -> recall = false
            composer.editingPublished && !composer.hasPublishedChanges -> operation {
                if (composer.closeUnchangedEdit()) onBack() else departure = true
            }
            hasWorkingContent() -> departure = true
            else -> { composer.reset(); onBack() }
        }
    }
    LaunchedEffect(resumeMoment?.id) {
        if (resumeMoment != null && previewMoment == null) {
            runCatching {
                if (hasWorkingContent()) composer.stage(context, clear = true)
                composer.open(resumeMoment)
                onResumeConsumed()
            }.onFailure { error = it.message ?: "草稿暂时无法打开" }
        }
    }
    LaunchedEffect(note.diaryRoadTheme, note.diaryRoadBackground) {
        background = DiaryBackgroundLibrary.bitmap(context, note.diaryRoadTheme, note.diaryRoadBackground)
    }
    LaunchedEffect(day) {
        val current = latest()
        if (previewMoment == null && day == dayMillis() && !current.hasDiaryContent() && !current.diaryRoadEnabled &&
            current.diaryRoadBackground.isBlank() && current.diaryRoadTheme == "forest") {
            model.updateDiaryRoadAppearance(current, DiaryBackgroundLibrary.defaultTheme(context), DiaryBackgroundLibrary.defaultUri(context))
            model.updateDiaryRoadLayout(latest(), DiaryBackgroundLibrary.defaultLayout(context))
        }
    }
    LaunchedEffect(revision) { composer.reconcile() }
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); error = null } }
    LaunchedEffect(moments.size) { litCount = litCount.coerceAtMost(moments.size); if (moments.isEmpty()) recall = false }
    LaunchedEffect(moments.map { it.id }, pendingScroll) {
        pendingScroll?.let { id ->
            val index = moments.indexOfFirst { it.id == id }
            if (index >= 0) { list.animateScrollToItem(index + 1); pendingScroll = null }
        }
    }
    LaunchedEffect(preview?.id, preview?.occurredAt, preview?.sentAt, order) {
        if (preview != null) {
            keyboard?.hide()
            val anchor = neighbors?.previous ?: neighbors?.next
            val index = moments.indexOfFirst { it.id == anchor?.id }
            list.animateScrollToItem(if (index >= 0) index + 1 else 0)
        }
    }
    if (localInbox && previewMoment == null) {
        DiaryInboxScreen(model, day, { localInbox = false }, { virtual ->
            localInbox = false
            runCatching { composer.open(virtual) }.onFailure { error = it.message }
        })
        return
    }
    BackHandler(enabled = !settings && !timeOpen && deletion == null && !departure) { back() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Use the page's actual bounds, including when embedded below the app navigation.
    val bottomInset = with(density) { maxOf(keyboardBottom, WindowInsets.navigationBars.getBottom(density)).toDp() }
    val editorHeight = (maxHeight - headerHeight - bottomInset - 48.dp - 8.dp).coerceAtLeast(84.dp)
    Scaffold(containerColor = Color(0xFFF2F1E8), snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Surface(color = Color(0xFFFCFCF8).copy(alpha = .96f), modifier = Modifier.onSizeChanged { headerHeight = with(density) { it.height.toDp() } }) {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::back, enabled = !composer.busy && !composer.importing, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                    Text(if (preview != null) "在小路中查看位置" else if (isToday) "今日小路" else dateText(day),
                        modifier = Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (preview == null) {
                        if (composer.expanded || composer.hasContent || composer.editingPublished) {
                            IconButton(onClick = { composer.undo() }, enabled = composer.undo.isNotEmpty() && !composer.busy && !composer.importing, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Undo, "撤销片段修改", Modifier.size(20.dp))
                            }
                            IconButton(onClick = { composer.redo() }, enabled = composer.redo.isNotEmpty() && !composer.busy && !composer.importing, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Redo, "重做片段修改", Modifier.size(20.dp))
                            }
                            IconButton(onClick = { operation { composer.complete(context) } },
                                enabled = !composer.busy && !composer.importing, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Check, "完成片段编辑", Modifier.size(21.dp))
                            }
                        }
                        DiaryFavoriteButton(note.favorite, { model.toggleDiaryFavorite(initial) }, enabled = !composer.busy && !composer.importing)
                        IconButton(onClick = { leave { onSummary(latest()) } }, enabled = !composer.busy && !composer.importing, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.MenuBook, if (isToday) "今日结语" else "这一天的结语", Modifier.size(21.dp))
                        }
                        IconButton(onClick = { keyboard?.hide(); settings = true }, enabled = !composer.busy && !composer.importing, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Settings, "小路设置", Modifier.size(21.dp))
                        }
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
        Surface(color = Color.White, shadowElevation = 6.dp) {
            Column(Modifier.navigationBarsPadding().imePadding()) {
                when {
                    preview != null -> Button(onClick = { if (previewMoment != null) onPreviewBack?.invoke() else viewingPosition = false },
                        modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text("继续编辑") }
                    recall -> Button(onClick = {
                        if (litCount < moments.size) { litCount++; scope.launch { list.animateScrollToItem(litCount) } } else recall = false
                    }, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text(if (litCount < moments.size) "下一处" else "走完了 · 返回小路") }
                    else -> DiaryMomentComposer(composer, editorHeight, { timeOpen = true },
                        onStage = { operation {
                            composer.stage(context, clear = true)
                            Toast.makeText(context, "当前片段已经存入收纳箱", Toast.LENGTH_SHORT).show()
                        } },
                        onPublish = { operation { pendingScroll = composer.publish(context).id } },
                        onError = { error = it })
                }
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).testTag("diary-road-background")) {
            val info = list.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()
            val progress = if (first == null || info.totalItemsCount <= 1) 0f else
                ((first.index + (-first.offset).coerceAtLeast(0).toFloat() / first.size.coerceAtLeast(1)) / (info.totalItemsCount - 1)).coerceIn(0f, 1f)
            DiaryPainting(background, progress, Modifier.fillMaxSize())
            if (recall) Box(Modifier.fillMaxSize().background(Color(0xFF142B34).copy(alpha = .72f)))
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 36.dp)) {
                item(key = "start") {
                    Box(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
                        Surface(shape = RoundedCornerShape(24.dp), color = Color.White.copy(alpha = .85f)) {
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("从这里出发", fontWeight = FontWeight.Medium)
                                Text(if (moments.isEmpty()) "一句话、一张照片，留住沿途的瞬间" else moments.size.toString() + " 个片段 · 按" +
                                    (if (order == "sent") "发送" else "发生") + "时间排列", fontSize = 11.sp, color = Quiet)
                            }
                        }
                    }
                }
                itemsIndexed(moments, key = { _, moment -> moment.id }) { index, moment ->
                    val right = note.diaryRoadLayout == "right" || note.diaryRoadLayout == "alternate" && index % 2 == 1
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 20.dp), horizontalArrangement = if (right) Arrangement.End else Arrangement.Start) {
                        DiaryMomentCard(moment, note, nodes, order, !recall || index < litCount, recall, moment.id in highlighted,
                            Modifier.fillMaxWidth(.65f), readOnly = preview != null || composer.busy || composer.importing,
                            onEdit = { operation { if (hasWorkingContent()) composer.stage(context, clear = true); composer.open(moment.asNote(latest())) } },
                            onRetract = { runCatching { model.retractDiaryMoment(latest(), moment.id) }.onFailure { error = it.message } },
                            onDelete = { deletion = moment }, onError = { error = it })
                    }
                }
                item(key = "end") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(if (isToday) "小路还在继续" else "也可以在后来，补上这一天", fontSize = 12.sp,
                            color = if (recall) Color.White else Color(0xFF536B53),
                            modifier = Modifier.background(if (recall) Color(0xFF334743) else Color.White.copy(alpha = .8f), RoundedCornerShape(20.dp)).padding(12.dp))
                    }
                }
            }
            if (moments.isNotEmpty() && preview == null && !recall && !composer.expanded) FloatingActionButton(
                onClick = { recall = true; litCount = 0; scope.launch { list.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(42.dp), containerColor = Color(0xFFFFFCED).copy(alpha = .9f)) {
                Icon(Icons.Default.AutoAwesome, "暗背景回溯", Modifier.size(18.dp))
            }
        }
    }
    }
    if (settings) DiaryDaySettings(note, model, { settings = false }, { enabled -> if (!enabled) leave { onSummary(latest()) } }, onExport,
        onInbox = { date -> settings = false; leave { if (onInbox != null) onInbox(date) else localInbox = true } })
    if (timeOpen && previewMoment == null) DiaryMomentTimeDialog(latest(), composer.moment, model,
        onDismiss = { timeOpen = false }, onOccurredAt = { composer.change(composer.moment.copy(occurredAt = it)) },
        onViewPosition = { timeOpen = false; viewingPosition = true })
    if (departure) AlertDialog(onDismissRequest = { departure = false }, title = { Text(if (composer.editingPublished) "片段修改尚未完成" else "片段尚未发送") },
        text = { Text("可以将当前内容留在收纳箱，之后再继续。") },
        confirmButton = { Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { departure = false; operation {
                if (composer.editingPublished) composer.complete(context) else composer.publish(context)
                onBack()
            } }, enabled = composer.editingPublished || composer.hasContent) { Text("直接保存") }
            TextButton(onClick = { departure = false; leave(onBack) }) { Text("存入收纳箱并离开") }
            Row {
                TextButton(onClick = { departure = false; composer.expanded = true; composer.keyboardRequest++ }) { Text("继续编辑") }
                TextButton(onClick = { departure = false; operation { composer.discard(context); onBack() } }) { Text("丢弃") }
            }
        } })
    deletion?.let { moment ->
        AlertDialog(onDismissRequest = { deletion = null }, title = { Text("将这个片段移入收纳箱？") },
            text = { Text("删除后仍可恢复。结语与其他片段会保留。") },
            confirmButton = { TextButton(onClick = { runCatching { model.deleteDiaryMoment(latest(), moment.id); deletion = null }.onFailure { error = it.message } }) { Text("移入收纳箱") } },
            dismissButton = { TextButton(onClick = { deletion = null }) { Text("取消") } })
    }
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
@OptIn(ExperimentalLayoutApi::class)
fun DiaryMomentTagsPreview(tags: String) {
    if (tags.isNotBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TagRules.names(tags).forEachIndexed { index, tag ->
            Text("#" + tag, color = NoteInk, fontSize = 11.sp, modifier = Modifier.background(
                listOf(Mint, Lavender, Peach, Color(0xFFFFF1CF))[index % 4], RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 3.dp))
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
fun DiaryMomentContentPreview(moment: DiaryMoment, parent: NoteNode, nodes: List<NoteNode>, onOpen: (NoteNode) -> Unit,
    onError: (String) -> Unit, maxBlocks: Int = 8, focusBlockId: String? = null) {
    val context = LocalContext.current
    val blocks = remember(moment.document, moment.text) { runCatching { decodeBlocks(moment.document, moment.text) }.getOrElse { listOf(NoteBlock(text = moment.text)) } }
    var video by remember { mutableStateOf<NoteBlock?>(null) }
    var imageId by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        DiaryMomentTagsPreview(moment.tags)
        blocks.filter { it.text.isNotBlank() || it.uri.isNotBlank() || it.target.isNotBlank() }.take(maxBlocks).forEach { block ->
            val bring = remember(block.id) { androidx.compose.foundation.relocation.BringIntoViewRequester() }
            Column(Modifier.then(if (block.id == focusBlockId) Modifier.border(2.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(8.dp)).padding(6.dp) else Modifier)
                .then(Modifier.bringIntoViewRequester(bring))) {
            LaunchedEffect(focusBlockId) { if (block.id == focusBlockId) { kotlinx.coroutines.delay(300); bring.bringIntoView() } }
            when (block.type) {
                "video" -> VisualMediaTile(block) { video = block }
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
                else -> Text(buildAnnotatedString {
                    append(if (block.type == "bullet") "• " else if (block.type == "check") if (block.checked) "☑ " else "☐ " else if (block.type == "quote") "│ " else "")
                    append(block.richText())
                },
                    fontSize = if (block.type.startsWith("heading")) 17.sp else 15.sp, lineHeight = 23.sp, color = NoteInk,
                    fontWeight = if (block.bold || block.type.startsWith("heading")) FontWeight.SemiBold else FontWeight.Normal,
                    fontStyle = if (block.italic) FontStyle.Italic else FontStyle.Normal,
                    modifier = if (block.display == "highlight") Modifier.background(Color(0xFFFFF1A8)) else Modifier,
                    maxLines = if (blocks.size == 1) 6 else 3, overflow = TextOverflow.Ellipsis)
            }
        }
        }
        if (blocks.size > maxBlocks || blocks.any { it.text.length > 180 }) Text("点开阅读全文", color = Sky, fontSize = 11.sp)
    }
    video?.let { VideoViewer(it, onError) { video = null } }
    imageId?.let { id -> ImageViewer(blocks.filter { it.type == "image" }, id) { imageId = null } }
}
