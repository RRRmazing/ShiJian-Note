package com.shijiannote.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shijiannote.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val SearchInputSaver = listSaver<SearchInput, String>(save = { value ->
    listOf(value.query, value.markedOnly.toString(), value.history.toString(), value.currentCategory.toString(), value.todoScope,
        value.range.toString(), value.start.year, value.start.month, value.start.day, value.end.year, value.end.month, value.end.day,
        value.certainDay.toString(), value.date.month, value.date.day)
}, restore = { v -> SearchInput(v[0], v[1].toBoolean(), v[2].toBoolean(), v[3].toBoolean(), v[4], v[5].toBoolean(),
    SearchDateInput(v[6], v[7], v[8]), SearchDateInput(v[9], v[10], v[11]), v[12].toBoolean(), SearchDateInput(month = v[13], day = v[14])) })

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ModuleSearch(
    model: WorkspaceModel, initialTab: Int, memoryParent: String? = null, onClose: () -> Unit,
    onNote: (NoteNode, String) -> Unit, onFolder: (NoteNode) -> Unit,
    onSchedule: (ScheduleEvent) -> Unit, onTodo: (TodoBoardWithItems) -> Unit
) {
    val nodes by model.nodes.collectAsState()
    val events by model.schedules.collectAsState()
    val boards by model.todos.collectAsState()
    var draft by rememberSaveable(stateSaver = SearchInputSaver) { mutableStateOf(SearchInput()) }
    var applied by rememberSaveable(stateSaver = SearchInputSaver) { mutableStateOf(SearchInput()) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var submission by rememberSaveable { mutableIntStateOf(0) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var rangeExpanded by rememberSaveable { mutableStateOf(false) }
    var dayExpanded by rememberSaveable { mutableStateOf(false) }
    var expandedHistory by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    // Historical rows load asynchronously after a result/editor round trip.
    // Retain their saved position before the temporarily empty list can clamp it.
    val initialHistoryIndex = remember { listState.firstVisibleItemIndex }
    val initialHistoryOffset = remember { listState.firstVisibleItemScrollOffset }
    var restoreHistoryPosition by remember { mutableStateOf(applied.history && submitted) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    val dated = initialTab == 0 || initialTab == 2
    val moduleName = when (initialTab) { 0 -> "时间表"; 1 -> "待办"; 2 -> "日记"; else -> "记忆" }
    var snapshots by remember { mutableStateOf<List<SearchNoteSnapshot>>(emptyList()) }
    var historyLoading by remember { mutableStateOf(false) }
    var historyError by remember { mutableStateOf(false) }
    LaunchedEffect(applied.history, nodes, initialTab, submission) {
        snapshots = emptyList(); historyError = false
        if (applied.history && initialTab in 2..3) {
            historyLoading = true
            val loaded = withContext(Dispatchers.IO) { runCatching { model.notes.allVersions().mapNotNull { version ->
                runCatching { SearchNoteSnapshot(version.nodeId, decodeNode(JSONObject(version.snapshot)), version.createdAt) }.getOrNull()
            } } }
            snapshots = loaded.getOrDefault(emptyList()); historyError = loaded.isFailure; historyLoading = false
            if (restoreHistoryPosition) {
                restoreHistoryPosition = false
                listState.scrollToItem(initialHistoryIndex, initialHistoryOffset)
            }
        } else historyLoading = false
    }
    fun hideKeyboard() { keyboard?.hide(); focus.clearFocus() }
    fun close() { hideKeyboard(); onClose() }
    fun submit() {
        val result = SearchRules.normalize(draft)
        draft = result.input
        error = result.error ?: if (!SearchRules.canSearch(result.input)) if (dated) "请输入关键词或完整日期" else "请输入关键词" else null
        if (!draft.range) rangeExpanded = false
        if (!draft.certainDay) dayExpanded = false
        if (error == null) { restoreHistoryPosition = false; applied = result.input; submitted = true; submission++; expandedHistory = null; hideKeyboard(); scope.launch { listState.scrollToItem(0) } }
    }
    BackHandler { if (imeVisible) hideKeyboard() else close() }
    val noteHits = remember(nodes, snapshots, initialTab, memoryParent, applied, submitted) {
        if (submitted && initialTab in 2..3) SearchRules.notes(nodes, snapshots, initialTab, memoryParent, applied) else emptyList()
    }
    val scheduleHits = remember(events, applied, submitted) { if (submitted && initialTab == 0) SearchRules.schedules(events, applied) else emptyList() }
    val todoHits = remember(boards, applied, submitted) { if (submitted && initialTab == 1) SearchRules.todos(boards, applied, System.currentTimeMillis()) else emptyList() }
    fun snippet(text: String): String {
        val index = text.indexOf(applied.query, ignoreCase = true).coerceAtLeast(0)
        return text.substring((index - 25).coerceAtLeast(0), (index + applied.query.length + 120).coerceAtMost(text.length))
    }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 18.dp).padding(top = 6.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("搜索", fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "search-controls") { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(draft.query, { draft = draft.copy(query = it) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "搜索关键词" },
                    placeholder = { Text(if (initialTab == 1) "搜索待办清单或事项" else "搜索${moduleName}标题、正文、标签或附件名称", fontSize = 13.sp) },
                    singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { TextButton(onClick = ::submit) { Text("搜索") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
                if (initialTab == 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("all" to "全部", "lists" to "清单", "today" to "今天", "tomorrow" to "明天", "overdue" to "逾期", "important" to "重要", "repeat" to "重复").forEach { (key, label) ->
                        FilterChip(draft.todoScope == key, { draft = draft.copy(todoScope = key) }, label = { Text(label) })
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (dated) SearchCheck(if (initialTab == 2) "仅收藏" else "仅重要", draft.markedOnly) { draft = draft.copy(markedOnly = it) }
                    SearchCheck("包含历史与归档", draft.history) { draft = draft.copy(history = it) }
                    if (initialTab == 3 && memoryParent != null) SearchCheck("仅在当前分类下", draft.currentCategory) { draft = draft.copy(currentCategory = it) }
                }
                if (dated) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        SearchCheck("时间范围", draft.range) { draft = draft.copy(range = it); rangeExpanded = it }
                        IconButton(onClick = { rangeExpanded = !rangeExpanded }) { Icon(if (rangeExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (rangeExpanded) "收起时间范围" else "展开时间范围") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        SearchCheck("某一天", draft.certainDay) { draft = draft.copy(certainDay = it); dayExpanded = it }
                        IconButton(onClick = { dayExpanded = !dayExpanded }) { Icon(if (dayExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (dayExpanded) "收起某一天" else "展开某一天") }
                        }
                    }
                    if (rangeExpanded) {
                        SearchDateRow("始：", draft.start, onSearch = ::submit) { draft = draft.copy(start = it) }
                        SearchDateRow("终：", draft.end, onSearch = ::submit) { draft = draft.copy(end = it) }
                    }
                    if (dayExpanded) SearchDateRow("日期：", draft.date, false, ::submit) { draft = draft.copy(date = it) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                if (submitted) {
                    Text(if (historyLoading) "正在查找历史版本…" else "找到 ${noteHits.size + scheduleHits.size + todoHits.size} 条结果", color = Quiet, fontSize = 12.sp)
                    if (historyError) Text("历史版本读取失败，请重新搜索", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            } }
            if (submitted) {
            items(noteHits, key = { "note-${it.owner.id}" }) { hit ->
                val n = hit.owner
                SoftCard(Modifier.clickable { hideKeyboard(); if (n.kind == "folder") onFolder(n) else onNote(n, if (hit.versionAt == null) applied.query else "") }) {
                    Text(highlightText(hit.matched.displayTitle(), applied.query))
                    Text(highlightText(snippet(SearchRules.noteText(hit.matched)), applied.query), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = Quiet)
                    Text(if (n.kind == "diary") "日记 · ${dateText(n.day ?: n.createdAt)}" else "${if (n.kind == "folder") "分类" else "记忆"} · ${TreeRules.path(n, nodes)}", color = Sky, fontSize = 11.sp)
                    hit.versionAt?.let { at ->
                        TextButton(onClick = { expandedHistory = if (expandedHistory == n.id) null else n.id }) { Text("历史版本 · ${dateText(at)} · ${if (expandedHistory == n.id) "收起" else "查看匹配内容"}", fontSize = 12.sp) }
                        if (expandedHistory == n.id) Text(highlightText(SearchRules.noteText(hit.matched), applied.query), fontSize = 13.sp)
                    }
                }
            }
            items(scheduleHits, key = { "schedule-${it.id}" }) { event -> SoftCard(Modifier.clickable { hideKeyboard(); onSchedule(event) }) {
                Text(highlightText(event.title, applied.query))
                if (event.note.isNotBlank()) Text(highlightText(snippet(event.note), applied.query), maxLines = 2, overflow = TextOverflow.Ellipsis, color = Quiet, fontSize = 13.sp)
                Text("时间表 · ${dateText(event.eventAt)}${if (event.archived) " · 已归档" else ""}", color = Sky, fontSize = 11.sp)
            } }
            items(todoHits, key = { if (it.board.boardType == "DAILY") "task-${it.items.single().id}" else "board-${it.board.id}" }) { group -> SoftCard(Modifier.clickable { hideKeyboard(); onTodo(group) }) {
                val daily = group.board.boardType == "DAILY"
                Text(highlightText(if (daily) group.items.single().text else group.board.summary, applied.query))
                if (!daily) Text(highlightText(snippet(group.items.joinToString("\n") { it.text }), applied.query), maxLines = 2, overflow = TextOverflow.Ellipsis, color = Quiet, fontSize = 13.sp)
                Text((if (daily) "独立事项 · ${group.items.single().plannedDay?.let { dateText(it) }.orEmpty()}" else "待办清单") + if (group.board.archived) " · 已归档" else "", color = Sky, fontSize = 11.sp)
            } }
            }
        }
    }
}

@Composable private fun SearchCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, modifier = Modifier.size(32.dp))
        Text(label, fontSize = 13.sp)
    }
}

@Composable private fun SearchDateRow(label: String, input: SearchDateInput, withYear: Boolean = true, onSearch: () -> Unit, onChange: (SearchDateInput) -> Unit) {
    val prefix = when (label) { "始：" -> "开始"; "终：" -> "结束"; else -> "日期" }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(if (withYear) 30.dp else 42.dp))
        if (withYear) OutlinedTextField(input.year, { onChange(input.copy(year = it.filter(Char::isDigit).take(4))) }, modifier = Modifier.weight(1.3f).semantics { contentDescription = "${prefix}年" }, placeholder = { Text("年", fontSize = 13.sp) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }))
        OutlinedTextField(input.month, { onChange(input.copy(month = it.filter(Char::isDigit).take(2))) }, modifier = Modifier.weight(1f).semantics { contentDescription = "${prefix}月" }, placeholder = { Text("月", fontSize = 13.sp) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }))
        OutlinedTextField(input.day, { onChange(input.copy(day = it.filter(Char::isDigit).take(2))) }, modifier = Modifier.weight(1f).semantics { contentDescription = "${prefix}日" }, placeholder = { Text("日", fontSize = 13.sp) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }))
    }
}
