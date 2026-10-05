package com.shijiannote.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.shijiannote.app.data.NoteNode
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Local working copy. Draft saves never mutate a published moment before the checkmark. */
@Stable
class DiaryMomentComposerState(val model: WorkspaceModel, val day: Long, private val onError: (String) -> Unit) {
    var moment by mutableStateOf(freshMoment())
        private set
    var expanded by mutableStateOf(false)
    var editingPublished by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
    var importing by mutableStateOf(false)
    var active by mutableStateOf(moment.blocks().first().id)
    var recordOwner by mutableStateOf<String?>(null)
    var keyboardRequest by mutableIntStateOf(0)
    var typingStyles by mutableStateOf(setOf<String>())
    var lastPersistFailed by mutableStateOf(false)
        private set
    val selections = mutableStateMapOf<String, TextRange>()
    val compositions = mutableStateMapOf<String, TextRange?>()
    val undo = mutableStateListOf<String>()
    val redo = mutableStateListOf<String>()
    val blocks get() = moment.blocks()
    val hasContent get() = moment.hasContent()
    fun parent() = model.currentDiary(day)
    fun restore(json: String, editing: Boolean, open: Boolean, owner: String?, failed: Boolean = false) {
        moment = normalize(decodeDiaryMoment(JSONObject(json))); editingPublished = editing; expanded = open
        lastPersistFailed = failed
        recordOwner = owner; active = blocks.lastOrNull { it.type !in setOf("image", "audio", "file", "link") }?.id ?: blocks.first().id
    }
    fun open(note: NoteNode) {
        val id = note.id.removePrefix("moment-")
        val current = parent()
        val published = current.diaryMoments().firstOrNull { it.id == id }
        if (published != null) {
            model.beginDiaryMomentEdit(current, id)
            moment = parent().diaryInboxItems().firstOrNull { it.originalStatus == "editing" && it.moment?.id == id }?.moment ?: published
            editingPublished = true
        } else {
            moment = (current.diaryInboxItems().firstOrNull { it.moment?.id == id }?.moment ?: note.asDiaryMoment()).copy(sentAt = null)
            editingPublished = false
        }
        moment = normalize(moment); lastPersistFailed = false
        expanded = true; undo.clear(); redo.clear(); selections.clear(); compositions.clear(); typingStyles = emptySet()
        active = blocks.lastOrNull { it.type !in setOf("image", "audio", "file", "link") }?.id ?: blocks.first().id
        recordOwner = model.diaryRecordingOwner(moment.asNote(parent()))
        keyboardRequest++
    }
    fun persist() {
        try {
            if (editingPublished) model.saveDiaryMomentEdit(parent(), moment) else model.saveDiaryDraft(parent(), moment)
            lastPersistFailed = false
        } catch (failure: Exception) { lastPersistFailed = true; throw failure }
    }
    fun reconcile() {
        if (busy || importing || lastPersistFailed) return
        val items = parent().diaryInboxItems()
        if (!editingPublished) {
            if (hasContent || moment.occurredAt != null) {
                val item = items.firstOrNull { it.moment?.id == moment.id }
                if (parent().diaryMoments().any { it.id == moment.id } || item == null || item.status !in setOf("draft", "retracted")) reset()
            }
            return
        }
        if (items.none { it.originalStatus == "editing" && it.status == "draft" && it.moment?.id == moment.id }) {
            val detached = model.getDiaryMomentNote(day, moment.id) ?: return
            val item = parent().diaryInboxItems().firstOrNull { it.moment?.id == detached.id.removePrefix("moment-") }
            if (item != null && item.originalStatus != "editing") {
                moment = normalize(item.moment ?: return)
                editingPublished = false
                recordOwner = model.diaryRecordingOwner(detached)
            } else reset()
        }
    }
    fun change(next: DiaryMoment, track: Boolean = true) {
        if (track && next != moment) { undo.add(jsonObject(moment).toString()); if (undo.size > 80) undo.removeAt(0); redo.clear() }
        moment = normalize(next)
        runCatching { persist() }.onFailure { onError(it.message ?: "草稿保存失败，请重试") }
    }
    fun updateBlock(next: NoteBlock) = setBlocks(blocks.map { if (it.id == next.id) next else it })
    fun setBlocks(next: List<NoteBlock>) {
        val nonEmpty = next.ifEmpty { listOf(NoteBlock()) }
        change(moment.copy(document = encodeBlocks(nonEmpty), text = blockPlainText(nonEmpty)))
    }
    fun insert(block: NoteBlock) {
        val next = blocks.toMutableList()
        val index = next.indexOfFirst { it.id == active }.takeIf { it >= 0 } ?: next.lastIndex
        if (block.type in setOf("image", "audio", "file", "link") && next[index].type == "text" && next[index].text.isBlank()) {
            next[index] = block
        } else next.add(index + 1, block)
        setBlocks(next); active = block.id; expanded = true
    }
    fun prepareTextInput() {
        if (blocks.none { it.id == active && it.type !in setOf("image", "audio", "file", "link") }) insert(NoteBlock())
    }
    fun undo() {
        if (undo.isEmpty() || busy || importing) return
        redo.add(jsonObject(moment).toString())
        change(decodeDiaryMoment(JSONObject(undo.removeAt(undo.lastIndex))), track = false)
    }
    fun redo() {
        if (redo.isEmpty() || busy || importing) return
        undo.add(jsonObject(moment).toString())
        change(decodeDiaryMoment(JSONObject(redo.removeAt(redo.lastIndex))), track = false)
    }
    fun reset() {
        moment = freshMoment(); editingPublished = false; expanded = false; recordOwner = null; lastPersistFailed = false
        active = blocks.first().id; undo.clear(); redo.clear(); selections.clear(); compositions.clear(); typingStyles = emptySet()
    }
    suspend fun collectAudio(context: android.content.Context) {
        val owner = recordOwner ?: model.diaryRecordingOwner(moment.asNote(parent())).also { recordOwner = it }
        val inbox = RecordingService.inbox(context, owner)
        if (inbox.isEmpty()) return
        inbox.filter { audio -> blocks.none { it.id == audio.id } }.forEach { insert(it) }
        persist()
        model.flush(parent().id)
        inbox.forEach { RecordingService.removeInbox(context, Uri.parse(it.uri).path.orEmpty()) }
    }
    suspend fun finishRecording(context: android.content.Context) {
        val current = RecordingService.state.value
        val owner = recordOwner ?: model.diaryRecordingOwner(moment.asNote(parent()))
        if (current.running && current.owner == owner) {
            RecordingService.command(context, "stop")
            withTimeout(30_000) { while (RecordingService.state.value.let { it.running && it.owner == owner }) delay(80) }
        }
        collectAudio(context)
    }
    suspend fun stage(context: android.content.Context, clear: Boolean) {
        finishRecording(context); persist(); model.flush(parent().id)
        if (clear) reset() else expanded = false
    }
    suspend fun complete(context: android.content.Context) {
        finishRecording(context); persist()
        if (editingPublished) model.completeDiaryMomentEdit(parent(), moment)
        else model.flush(parent().id)
        if (editingPublished) reset() else expanded = false
    }
    suspend fun publish(context: android.content.Context): DiaryMoment {
        check(!editingPublished) { "已发布片段请通过完成按钮应用修改" }
        finishRecording(context); persist()
        return model.publishDiaryMoment(parent(), moment, moment.occurredAt).also { reset() }
    }
    suspend fun discard(context: android.content.Context) {
        finishRecording(context); persist(); model.flush(parent().id)
        val item = parent().diaryInboxItems().firstOrNull { it.moment?.id == moment.id }
        if (item != null) { model.deleteDiaryInbox(parent(), item.id); model.flush(parent().id) }
        reset()
    }
    companion object {
        fun freshMoment() = DiaryMoment(document = encodeBlocks(listOf(NoteBlock())))
        fun normalize(moment: DiaryMoment): DiaryMoment = if (moment.document.isBlank()) moment.copy(document = encodeBlocks(moment.blocks())) else moment
    }
}
fun DiaryMoment.blocks(): List<NoteBlock> = decodeBlocks(document, text)

@Composable
fun rememberDiaryMomentComposer(model: WorkspaceModel, day: Long, onError: (String) -> Unit): DiaryMomentComposerState {
    val errors by rememberUpdatedState(onError)
    val saver = remember(model, day) {
        listSaver<DiaryMomentComposerState, String>(save = { state -> listOf(jsonObject(state.moment).toString(), state.editingPublished.toString(), state.expanded.toString(), state.recordOwner.orEmpty(),
            JSONArray(state.undo.toList()).toString(), JSONArray(state.redo.toList()).toString(), state.active,
            JSONArray(state.typingStyles.toList()).toString(), JSONObject().apply { state.selections.forEach { (id, range) -> put(id, JSONArray(listOf(range.start, range.end))) } }.toString(), state.lastPersistFailed.toString()) },
            restore = { saved -> DiaryMomentComposerState(model, day) { errors(it) }.apply {
                restore(saved[0], saved[1].toBoolean(), saved[2].toBoolean(), saved[3].ifBlank { null }, saved.getOrNull(9)?.toBoolean() ?: false)
                if (saved.size > 8) {
                    JSONArray(saved[4]).let { a -> (0 until a.length()).forEach { undo.add(a.getString(it)) } }
                    JSONArray(saved[5]).let { a -> (0 until a.length()).forEach { redo.add(a.getString(it)) } }
                    active = saved[6].takeIf { id -> blocks.any { it.id == id } } ?: active
                    typingStyles = JSONArray(saved[7]).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    JSONObject(saved[8]).let { j -> j.keys().forEach { id -> j.getJSONArray(id).let { a -> selections[id] = TextRange(a.getInt(0), a.getInt(1)) } } }
                }
            } })
    }
    return rememberSaveable(day, saver = saver) { DiaryMomentComposerState(model, day) { errors(it) } }
}

/** A single mixed-block editor that remains on the road, with a compact seven-action bottom row. */
@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
fun DiaryMomentComposer(state: DiaryMomentComposerState, maxEditorHeight: Dp, onClock: () -> Unit,
    onStage: () -> Unit, onPublish: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val recording by RecordingService.state.collectAsState()
    val moment = state.moment
    val blocks = state.blocks
    val selections = state.selections
    val compositions = state.compositions
    val typingStyles = state.typingStyles
    val editable = !state.busy && !state.importing
    val displayedGroups = remember(blocks) {
        val result = mutableListOf<List<NoteBlock>>()
        var index = 0
        while (index < blocks.size) {
            val current = blocks[index++]
            if (current.type != "image") result.add(listOf(current)) else {
                val images = mutableListOf(current)
                while (index < blocks.size) {
                    if (blocks[index].type == "image") images.add(blocks[index++])
                    else if (blocks[index].type == "text" && blocks[index].text.isBlank() && index + 1 < blocks.size && blocks[index + 1].type == "image") index++
                    else break
                }
                result.add(images)
            }
        }
        result
    }
    var addMenu by remember { mutableStateOf(false) }
    var viewingImage by remember { mutableStateOf<String?>(null) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraMoment by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraBlock by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingImports by rememberSaveable { mutableStateOf(listOf<String>()) }
    var importTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var importImages by rememberSaveable { mutableStateOf(true) }
    var importGeneration by rememberSaveable { mutableIntStateOf(0) }
    var tagFocus by remember { mutableStateOf<String?>(null) }
    val tagSlots = remember(moment.id) { mutableStateListOf<Pair<String, String>>().apply { TagRules.names(moment.tags).forEach { add(UUID.randomUUID().toString() to it) } } }
    var tagsWritten by remember(moment.id) { mutableStateOf(moment.tags) }
    LaunchedEffect(moment.tags) { if (moment.tags != tagsWritten) {
        tagSlots.clear(); TagRules.names(moment.tags).forEach { tagSlots.add(UUID.randomUUID().toString() to it) }; tagsWritten = moment.tags
    } }
    fun saveTags() { tagsWritten = tagSlots.map { it.second }.filter { it.isNotBlank() }.distinct().joinToString(" "); state.change(state.moment.copy(tags = tagsWritten)) }
    fun expandKeyboard() { state.prepareTextInput(); state.expanded = true; state.keyboardRequest++ }
    fun queueImports(uris: List<Uri>, images: Boolean) {
        if (uris.isEmpty()) return
        uris.forEach { uri -> runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        importTarget = state.moment.id; importImages = images
        pendingImports = uris.map { uri -> JSONObject().put("id", UUID.randomUUID().toString()).put("uri", uri.toString()).toString() }
        state.importing = true; importGeneration++
    }
    val multipleImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> queueImports(uris, true) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> queueImports(uris, false) }
    LaunchedEffect(importGeneration, importTarget) {
        if (pendingImports.isNotEmpty()) {
            state.importing = true
            try {
                while (pendingImports.isNotEmpty()) {
                    val entry = JSONObject(pendingImports.first())
                    val id = entry.getString("id")
                    val target = importTarget ?: state.moment.id
                    val original = if (state.moment.id == target) state.moment else state.model.getDiaryMomentNote(state.day, target)?.asDiaryMoment()
                        ?: error("原草稿不可用，请从收纳箱重试")
                    if (original.blocks().none { it.id == id }) {
                        val block = importMedia(context, Uri.parse(entry.getString("uri")), importImages, copy = true).copy(id = id)
                        if (state.moment.id == target) state.insert(block)
                        else {
                            val existing = original.blocks()
                            val next = (if (existing.size == 1 && existing.single().type == "text" && existing.single().text.isBlank()) emptyList() else existing) + block
                            val updated = original.copy(document = encodeBlocks(next), text = blockPlainText(next))
                            if (state.model.currentDiary(state.day).diaryInboxItems().any { it.originalStatus == "editing" && it.moment?.id == target }) state.model.saveDiaryMomentEdit(state.parent(), updated)
                            else state.model.saveDiaryDraft(state.parent(), updated)
                        }
                    }
                    state.model.flush(state.parent().id)
                    pendingImports = pendingImports.drop(1)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { onError(failure.message ?: "素材添加失败，已添加内容仍保留"); pendingImports = emptyList() }
            finally { state.importing = false }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path = cameraPath; val id = cameraMoment; val blockId = cameraBlock
        if (path != null && id != null && blockId != null) scope.launch {
            runCatching {
                val current = state.model.getDiaryMomentNote(state.day, id) ?: return@runCatching
                val saved = current.asDiaryMoment()
                val next = saved.blocks().mapNotNull { block -> if (block.id != blockId) block else if (success && File(path).length() > 0) block.copy(bytes = File(path).length()) else null }
                val updated = saved.copy(document = encodeBlocks(next.ifEmpty { listOf(NoteBlock()) }), text = blockPlainText(next))
                if (state.moment.id == id) state.change(updated.copy(sentAt = state.moment.sentAt))
                else if (state.model.currentDiary(state.day).diaryInboxItems().any { it.originalStatus == "editing" && it.moment?.id == id }) state.model.saveDiaryMomentEdit(state.parent(), updated)
                else state.model.saveDiaryDraft(state.parent(), updated)
                state.model.flush(state.parent().id)
            }.onFailure { onError(it.message ?: "照片保存失败，可在收纳箱重试") }
        }
        cameraPath = null; cameraMoment = null; cameraBlock = null
    }
    var requestedOwner by rememberSaveable { mutableStateOf<String?>(null) }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val owner = requestedOwner
        if (owner != null && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            runCatching { RecordingService.command(context, "start", owner) }.onFailure { onError("无法开始录音") }
        else onError("录音需要麦克风权限")
        requestedOwner = null
    }
    fun startRecording() {
        if (recording.running || state.busy || state.importing) { onError("已有录音或素材操作正在进行"); return }
        state.expanded = true
        scope.launch {
            state.importing = true
            runCatching {
                state.persist()
                val owner = state.model.registerDiaryRecording(state.moment.asNote(state.parent()))
                state.recordOwner = owner
                val permissions = listOf(Manifest.permission.RECORD_AUDIO) + if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
                if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) RecordingService.command(context, "start", owner)
                else { requestedOwner = owner; microphone.launch(permissions.toTypedArray()) }
            }.onFailure { onError(it.message ?: "录音无法开始，请重试") }
            state.importing = false
        }
    }
    val latestCollect by rememberUpdatedState<suspend () -> Unit> { state.collectAudio(context) }
    LaunchedEffect(moment.id) {
        while (true) { runCatching { latestCollect() }.onFailure { onError(it.message ?: "录音尚未保存，请重试") }; delay(700) }
    }
    fun format(style: String) {
        if (!editable) return
        val block = state.blocks.firstOrNull { it.id == state.active } ?: return
        val range = selections[block.id] ?: TextRange(block.text.length)
        if (range.collapsed) state.typingStyles = if (style in typingStyles) typingStyles - style else typingStyles + style
        else state.updateBlock(block.toggleMark(range.start, range.end, style))
    }
    Column(Modifier.fillMaxWidth().background(Color.White)) {
        if (state.expanded) {
            Column(Modifier.fillMaxWidth().heightIn(max = maxEditorHeight).testTag("diary-composer-editor")) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 44.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (tagSlots.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    tagSlots.toList().forEachIndexed { index, (id, label) ->
                        val requester = remember(id) { FocusRequester() }
                        val color = listOf(Mint, Lavender, Peach, Color(0xFFFFF1CF))[index % 4]
                        Surface(shape = RoundedCornerShape(10.dp), color = color) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("#", color = Sky, modifier = Modifier.padding(start = 7.dp))
                                BasicTextField(label, { value ->
                                    val clean = value.filterNot(Char::isWhitespace).trimStart('#').take(80)
                                    val at = tagSlots.indexOfFirst { it.first == id }; if (at >= 0) { tagSlots[at] = id to clean; saveTags() }
                                }, Modifier.widthIn(min = 36.dp, max = 120.dp).padding(vertical = 8.dp, horizontal = 4.dp).focusRequester(requester),
                                    enabled = editable, singleLine = true, textStyle = TextStyle(color = NoteInk, fontSize = 13.sp), decorationBox = { inner -> if (label.isEmpty()) Box { Text("标签", color = Quiet, fontSize = 13.sp); inner() } else inner() })
                                IconButton(onClick = { tagSlots.removeAll { it.first == id }; saveTags() }, enabled = editable, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "移除标签", Modifier.size(14.dp)) }
                            }
                        }
                        LaunchedEffect(tagFocus, id) { if (tagFocus == id) { requester.requestFocus(); keyboard?.show(); tagFocus = null } }
                    }
                }
                displayedGroups.forEach { group ->
                    val block = group.first()
                    if (block.type == "image") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            group.forEach { image ->
                                Box(Modifier.size(44.dp)) {
                                    var bitmap by remember(image.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
                                    LaunchedEffect(image.uri) { bitmap = loadImage(context, image.uri, 160) }
                                    bitmap?.let { Image(it.asImageBitmap(), image.text, Modifier.size(38.dp).align(Alignment.BottomStart).clip(RoundedCornerShape(6.dp)).clickable { viewingImage = image.id }, contentScale = ContentScale.Crop) }
                                        ?: Icon(Icons.Default.Image, image.text, Modifier.size(38.dp).align(Alignment.BottomStart), tint = Quiet)
                                    IconButton(onClick = { state.setBlocks(state.blocks.filterNot { it.id == image.id }) }, enabled = editable,
                                        modifier = Modifier.size(20.dp).align(Alignment.TopEnd).background(Color.White, RoundedCornerShape(10.dp))) { Icon(Icons.Default.Close, "移除此素材", Modifier.size(12.dp)) }
                                }
                            }
                        }
                    } else if (block.type in setOf("audio", "file", "link")) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            when (block.type) {
                                "image" -> {
                                    var bitmap by remember(block.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
                                    LaunchedEffect(block.uri) { bitmap = loadImage(context, block.uri, 200) }
                                    bitmap?.let { Image(it.asImageBitmap(), block.text, Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)).clickable { viewingImage = block.id }, contentScale = ContentScale.Crop) }
                                        ?: Icon(Icons.Default.Image, null, Modifier.size(38.dp), tint = Quiet)
                                    Text(block.text, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 8.dp))
                                }
                                "audio" -> Box(Modifier.weight(1f)) { AudioPlayer(block, onError) }
                                else -> TextButton(onClick = { openFile(context, block)?.let(onError) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.AttachFile, null, Modifier.size(16.dp)); Text(block.text, fontSize = 12.sp, maxLines = 1) }
                            }
                            IconButton(onClick = { state.setBlocks(state.blocks.filterNot { it.id == block.id }) }, enabled = editable, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.Close, "移除此素材", Modifier.size(15.dp)) }
                        }
                    } else {
                        val requester = remember(block.id) { FocusRequester() }
                        val bring = remember(block.id) { BringIntoViewRequester() }
                        var layout by remember(block.id) { mutableStateOf<TextLayoutResult?>(null) }
                        var focused by remember(block.id) { mutableStateOf(false) }
                        val selection = selections[block.id]?.let { TextRange(it.start.coerceIn(0, block.text.length), it.end.coerceIn(0, block.text.length)) } ?: TextRange(block.text.length)
                        val style = TextStyle(color = NoteInk, fontSize = 16.sp, lineHeight = 25.sp,
                            fontWeight = if (block.bold || block.type.startsWith("heading")) FontWeight.SemiBold else FontWeight.Normal,
                            fontStyle = if (block.italic) FontStyle.Italic else FontStyle.Normal)
                        fun keepCaret() {
                            if (focused) scope.launch { layout?.let { result -> bring.bringIntoView(result.getCursorRect((selections[block.id]?.end ?: block.text.length).coerceIn(0, result.layoutInput.text.length))) } }
                        }
                        Row(verticalAlignment = Alignment.Top) {
                            if (block.type == "bullet") Text("• ", style = style)
                            if (block.type == "quote") Text("│ ", color = Sky, style = style)
                            BasicTextField(TextFieldValue(block.richText(), selection, compositions[block.id]?.takeIf { it.end <= block.text.length }), { value ->
                                selections[block.id] = value.selection
                                compositions[block.id] = value.composition
                                if (value.text != block.text) state.updateBlock(block.editText(value.text, typingStyles))
                                keepCaret()
                            }, modifier = Modifier.weight(1f).focusRequester(requester).bringIntoViewRequester(bring).onFocusChanged {
                                focused = it.isFocused; if (it.isFocused) { state.active = block.id; keepCaret() }
                            }.then(if (block.display == "highlight") Modifier.background(Color(0xFFFFF1A8)) else Modifier),
                                readOnly = !editable, textStyle = style, onTextLayout = { layout = it; keepCaret() }, decorationBox = { inner ->
                                    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { if (block.text.isEmpty()) Text("留下这一刻…", style = style.copy(color = Quiet)); inner() }
                                })
                            if (blocks.size > 1) IconButton(onClick = { state.setBlocks(state.blocks.filterNot { it.id == block.id }) }, enabled = editable, modifier = Modifier.size(26.dp)) { Icon(Icons.Default.Close, "移除此段", Modifier.size(13.dp)) }
                        }
                        LaunchedEffect(state.keyboardRequest, block.id, state.busy, state.importing) {
                            if (state.expanded && editable && block.id == state.active && state.keyboardRequest > 0) { requester.requestFocus(); keyboard?.show() }
                        }
                    }
                }
            }
            if (recording.running && recording.owner == state.recordOwner) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Text((if (recording.paused) "已暂停 " else "录音中 ") + audioTime(recording.elapsed), fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { RecordingService.command(context, "pause") }, enabled = editable) { Text(if (recording.paused) "继续" else "暂停") }
                TextButton(onClick = { scope.launch { runCatching { state.finishRecording(context) }.onFailure { onError(it.message ?: "录音保存失败") } } }, enabled = editable) { Text("结束") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                FormatAction(Icons.Default.FormatBold, "加粗", "b" in typingStyles, editable) { format("b") }
                FormatAction(Icons.Default.FormatItalic, "斜体", "i" in typingStyles, editable) { format("i") }
                FormatAction(Icons.Default.FormatColorFill, "高亮", "h" in typingStyles, editable) { format("h") }
                FormatAction(Icons.Default.FormatListBulleted, "列表", false, editable) { state.prepareTextInput(); state.blocks.firstOrNull { it.id == state.active }?.let { state.updateBlock(it.copy(type = if (it.type == "bullet") "text" else "bullet")) } }
                FormatAction(Icons.Default.FormatQuote, "引用", false, editable) { state.prepareTextInput(); state.blocks.firstOrNull { it.id == state.active }?.let { state.updateBlock(it.copy(type = if (it.type == "quote") "text" else "quote")) } }
                FormatAction(Icons.Default.Add, "新段落", false, editable) { state.insert(NoteBlock()); state.keyboardRequest++ }
            }
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            ComposerAction(Icons.Default.Keyboard, "键盘", !state.busy && !state.importing) { expandKeyboard() }
            ComposerAction(Icons.Default.MicNone, "语音", !state.busy && !state.importing) { startRecording() }
            ComposerAction(Icons.Default.Schedule, "发生时间", !state.busy && !state.importing) { keyboard?.hide(); onClock() }
            ComposerAction(Icons.Default.Tag, "新增标签", !state.busy && !state.importing) {
                state.expanded = true; val id = UUID.randomUUID().toString(); tagSlots.add(id to ""); tagFocus = id
            }
            ComposerAction(Icons.Default.Inventory2, "暂存", (state.hasContent || moment.occurredAt != null || recording.running && recording.owner == state.recordOwner) && editable) { keyboard?.hide(); focus.clearFocus(); onStage() }
            ComposerAction(Icons.Default.AddCircleOutline, "添加素材", !state.busy && !state.importing) { state.expanded = true; keyboard?.hide(); addMenu = true }
            IconButton(onClick = { keyboard?.hide(); onPublish() }, enabled = state.hasContent && !state.editingPublished && !state.busy && !state.importing, modifier = Modifier.size(44.dp)) {
                if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Filled.Send, "发送片段", Modifier.size(22.dp))
            }
        }
    }
    if (addMenu) SoftDialog("添加素材", { addMenu = false }) {
        OutlinedButton(onClick = { addMenu = false; multipleImages.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) { Text("选择多张图片") }
        OutlinedButton(onClick = { addMenu = false; scope.launch {
            state.importing = true
            runCatching {
                val file = File(File(context.filesDir, "assets").apply { mkdirs() }, "camera-" + UUID.randomUUID() + ".jpg")
                file.createNewFile()
                val block = NoteBlock(type = "image", text = "拍摄照片", uri = Uri.fromFile(file).toString(), owned = true, mime = "image/jpeg")
                cameraPath = file.absolutePath; cameraMoment = state.moment.id; cameraBlock = block.id
                state.insert(block); state.model.flush(state.parent().id)
                camera.launch(FileProvider.getUriForFile(context, context.packageName + ".files", file))
            }.onFailure { failure ->
                val placeholder = cameraBlock
                val file = cameraPath?.let(::File)
                runCatching {
                    if (placeholder != null) { state.setBlocks(state.blocks.filterNot { it.id == placeholder }); state.model.flush(state.parent().id) }
                    if (file?.length() == 0L) file.delete()
                    cameraPath = null; cameraMoment = null; cameraBlock = null
                }.onFailure { cleanup -> onError(cleanup.message ?: "相机占位尚未移除，请在收纳箱重试") }
                onError(failure.message ?: "无法打开相机")
            }
            state.importing = false
        } }, modifier = Modifier.fillMaxWidth()) { Text("拍摄照片") }
        OutlinedButton(onClick = { addMenu = false; filePicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("添加文件") }
        OutlinedButton(onClick = { addMenu = false; startRecording() }, modifier = Modifier.fillMaxWidth()) { Text("录音") }
    }
    viewingImage?.let { id -> ImageViewer(state.blocks.filter { it.type == "image" }, id) { viewingImage = null } }
}

@Composable
private fun ComposerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) { Icon(icon, description, Modifier.size(22.dp)) }
}
@Composable
private fun FormatAction(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) { Icon(icon, description, Modifier.size(20.dp), tint = if (selected) Sky else Quiet) }
}
