package com.shijiannote.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shijiannote.app.data.NoteNode
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun DiaryInboxScreen(model: WorkspaceModel, day: Long?, onBack: () -> Unit, onEdit: (NoteNode) -> Unit, focusItemId: String? = null, focusBlockId: String? = null) {
    val nodes by model.nodes.collectAsState()
    val revision by model.diaryRevision.collectAsState()
    val entries = remember(nodes, revision, day) {
        val dates = if (day != null) listOf(day) else nodes.filter { it.kind == "diary" }.mapNotNull { it.day }.distinct()
        dates.flatMap { date -> val parent = model.currentDiary(date); parent.diaryInboxItems().map { parent to it } }.sortedByDescending { it.second.updatedAt }
    }
    var filter by rememberSaveable(day) { mutableStateOf("all") }
    var retention by remember { mutableStateOf(false) }
    var permanent by remember { mutableStateOf<Pair<NoteNode, DiaryInboxItem>?>(null) }
    var preview by remember { mutableStateOf<Pair<NoteNode, DiaryInboxItem>?>(null) }
    LaunchedEffect(focusItemId, entries) { if (focusItemId != null) preview = entries.firstOrNull { it.second.id == focusItemId } }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { model.cleanupDiaryRetention().join() }
    BackHandler(onBack = onBack)
    fun act(action: () -> Unit) { runCatching(action).onFailure { error = it.message ?: "操作失败，请重试" } }
    fun send(parent: NoteNode, item: DiaryInboxItem) {
        val moment = item.moment ?: return
        if (busy != null) return
        busy = item.id
        scope.launch {
            runCatching {
                val current = model.currentDiary(parent.day ?: dayMillis())
                if (item.originalStatus == "editing") model.completeDiaryMomentEdit(current, moment)
                else model.publishDiaryMoment(current, moment, moment.occurredAt)
            }
                .onFailure { error = it.message ?: "发送失败，内容仍在收纳箱" }
            busy = null
        }
    }
    Surface(Modifier.fillMaxSize(), color = Mist) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
            PageTitle(if (day == null) "日记收纳箱" else "这一天的收纳箱", day?.let { dateText(it) } ?: "所有日期的未发布与收纳内容", onBack) {
                IconButton(onClick = { retention = true }) { Icon(Icons.Default.Settings, "收纳箱保留设置") }
            }
            ChoiceRow("查看", filter, listOf("all" to "全部", "draft" to "草稿", "retracted" to "撤回", "deleted" to "已删除", "road" to "整条小路")) { filter = it }
            Spacer(Modifier.height(8.dp))
            Text("草稿和撤回一直保留；普通删除仍可找回。", fontSize = 12.sp, color = Quiet)
            Spacer(Modifier.height(12.dp))
            val shown = entries.filter { filter == "all" || it.second.status == filter }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (shown.isEmpty()) item { SoftCard { Text("这里暂时没有${when (filter) { "draft" -> "草稿"; "retracted" -> "撤回片段"; "deleted" -> "已删除片段"; "road" -> "收纳的小路"; else -> "收纳内容" }}", color = Quiet) } }
                items(shown, key = { "${it.first.id}-${it.second.id}" }) { (parent, item) ->
                    SoftCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(dateText(parent.day ?: parent.createdAt), fontSize = 13.sp, color = Quiet, modifier = Modifier.weight(1f))
                            Text(when (item.status) { "draft" -> if (item.originalStatus == "editing") "修改草稿" else "草稿"; "retracted" -> "撤回"; "deleted" -> "已删除"; else -> "整条小路" }, color = Sky, fontSize = 12.sp)
                        }
                        if (item.status == "road") {
                            val moments = runCatching { parent.copy(diaryRoad = item.road).diaryMoments() }.getOrDefault(emptyList())
                            Text("经历小路 · ${moments.size} 个片段", fontWeight = FontWeight.Medium)
                            moments.firstOrNull()?.let { Text(DiaryLibraryRules.momentPreview(it), maxLines = 3, overflow = TextOverflow.Ellipsis) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { preview = parent to item }) { Text("预览") }
                                TextButton(onClick = { act { model.restoreDiaryInbox(parent, item.id) } }, enabled = busy == null) { Text(if (parent.diaryRoadEnabled || parent.diaryMoments().isNotEmpty()) "合并到现有小路" else "恢复小路") }
                                TextButton(onClick = { permanent = parent to item }, enabled = busy == null) { Text("彻底删除", color = MaterialTheme.colorScheme.error) }
                            }
                        } else item.moment?.let { moment ->
                            Text(DiaryLibraryRules.momentPreview(moment), maxLines = 5, overflow = TextOverflow.Ellipsis)
                            DiaryMomentTagsPreview(moment.tags)
                            val blocks = runCatching { decodeBlocks(moment.document, moment.text) }.getOrDefault(emptyList())
                            val mediaCount = blocks.count { it.type in setOf("image", "video", "audio", "file", "link") }
                            if (mediaCount > 0) Text("包含 $mediaCount 项素材 · 编辑可查看完整顺序", color = Quiet, fontSize = 12.sp)
                            Text("发生：${moment.occurredAt?.let { diaryTimestamp(it) } ?: "跟随发送时间"}", color = Quiet, fontSize = 12.sp)
                            moment.sentAt?.let { Text("原发送：${diaryTimestamp(it)}", color = Quiet, fontSize = 12.sp) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                                if (item.status == "deleted") {
                                    TextButton(onClick = { act { model.restoreDiaryInbox(parent, item.id) } }, enabled = busy == null) { Text(when (item.originalStatus) { "draft" -> "恢复草稿"; "retracted" -> "恢复为撤回"; else -> "恢复到小路" }) }
                                    TextButton(onClick = { act {
                                        model.editDiaryInbox(parent, item.id)?.let(onEdit)
                                    } }, enabled = busy == null) { Text("编辑后发送") }
                                    TextButton(onClick = { permanent = parent to item }, enabled = busy == null) { Text("彻底删除", color = MaterialTheme.colorScheme.error) }
                                } else {
                                    TextButton(onClick = { item.asNote(parent)?.let(onEdit) }, enabled = busy == null) { Text("继续编辑") }
                                    TextButton(onClick = { send(parent, item) }, enabled = busy == null && (item.originalStatus == "editing" || moment.asNote(parent).hasNoteContent())) { Text(if (busy == item.id) "正在保存…" else if (item.originalStatus == "editing") "完成修改" else if (item.status == "retracted") "重新发送" else "发送") }
                                    TextButton(onClick = { act { model.deleteDiaryInbox(parent, item.id) } }, enabled = busy == null) { Text("删除") }
                                }
                            }
                        }
                        Text(item.expiresAt?.let { "保留至 ${diaryTimestamp(it)}" } ?: "永久保留", color = Quiet, fontSize = 11.sp)
                    }
                }
            }
        }
    }
    permanent?.let { (parent, item) ->
        AlertDialog(onDismissRequest = { permanent = null }, title = { Text("彻底删除${if (item.status == "road") "这条小路" else "这个片段"}？") },
            text = { Text("内容将无法恢复。其他片段与日记正文会保留。") },
            confirmButton = { TextButton(onClick = { act { model.permanentlyDeleteDiaryInbox(parent, item.id); permanent = null } }) { Text("彻底删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { permanent = null }) { Text("取消") } })
    }
    preview?.let { (parent, item) ->
        SoftDialog("收纳内容 · ${dateText(parent.day ?: parent.createdAt)}", { preview = null }) {
            val moments = item.moment?.let { listOf(it) } ?: runCatching { parent.copy(diaryRoad = item.road).diaryMoments() }.getOrDefault(emptyList())
            moments.forEach { moment ->
                Text(diaryTimestamp(moment.occurredAt ?: moment.sentAt ?: moment.createdAt), color = Quiet, fontSize = 12.sp)
                DiaryMomentContentPreview(moment, parent, nodes, { }, { error = it }, maxBlocks = Int.MAX_VALUE, focusBlockId = focusBlockId)
                HorizontalDivider()
            }
            Button(onClick = { act { model.restoreDiaryInbox(parent, item.id); preview = null } }, modifier = Modifier.fillMaxWidth()) { Text(if (item.moment != null) "恢复片段" else if (parent.diaryRoadEnabled || parent.diaryMoments().isNotEmpty()) "合并到现有小路" else "恢复小路") }
        }
    }
    if (retention) DiaryRetentionDialog(model) { retention = false }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }) }
}

@Composable
fun DiaryRetentionDialog(model: WorkspaceModel, onDismiss: () -> Unit) {
    var inbox by remember { mutableStateOf(model.diaryInboxRetentionDays().toString()) }
    var trash by remember { mutableStateOf(model.diaryTrashRetentionDays().toString()) }
    val inboxValue = inbox.toIntOrNull()
    val trashValue = trash.toIntOrNull()
    SoftDialog("保留设置", onDismiss) {
        Text("默认永久保留。期限只用于以后删除或移入的项目，已有项目的保留方式不变。", fontSize = 13.sp, color = Quiet)
        Text("收纳箱：已删除片段与整条小路", fontWeight = FontWeight.Medium)
        RetentionField(inbox, { inbox = it })
        Text("草稿与撤回不自动到期。", fontSize = 12.sp, color = Quiet)
        HorizontalDivider()
        Text("回收站：整篇日记", fontWeight = FontWeight.Medium)
        RetentionField(trash, { trash = it })
        Button(onClick = { model.setDiaryRetentionDays(inboxValue!!, trashValue!!); onDismiss() },
            enabled = inboxValue != null && inboxValue in 0..36500 && trashValue != null && trashValue in 0..36500, modifier = Modifier.fillMaxWidth()) { Text("应用到以后收纳的内容") }
    }
}

@Composable
private fun RetentionField(value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(value == "0", { onChange("0") }); Text("永久保留") }
    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(value != "0", { if (value == "0") onChange("30") }); Text("按天保留") }
    if (value != "0") OutlinedTextField(value, { onChange(it.filter(Char::isDigit).take(5)) }, label = { Text("保留天数（1–36500）") }, singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}
