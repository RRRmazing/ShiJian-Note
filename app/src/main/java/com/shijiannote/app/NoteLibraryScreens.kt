@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.shijiannote.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.shijiannote.app.data.*
import kotlinx.coroutines.launch
import java.time.*

@Composable fun NodePicker(title: String, candidates: List<NoteNode>, all: List<NoteNode>, onClose: () -> Unit, onPick: (NoteNode) -> Unit) {
    var query by remember { mutableStateOf("") }
    SoftDialog(title, onClose) {
        OutlinedTextField(query, { query = it }, placeholder = { Text("搜索名称或内容") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        val shown = candidates.filter { (it.title + it.text + TreeRules.path(it, all)).contains(query, true) }
        if (shown.isEmpty()) Text("没有符合条件的内容", color = Quiet)
        shown.take(200).forEach { n ->
            Column(Modifier.fillMaxWidth().clickable { onPick(n) }.padding(vertical = 10.dp)) {
                Text(n.displayTitle(), fontWeight = FontWeight.Medium)
                Text(if (n.kind == "diary") dateText(n.day ?: n.createdAt) else TreeRules.path(n, all), fontSize = 12.sp, color = Quiet)
            }
        }
        if (shown.size > 200) Text("结果较多，请输入关键词缩小范围。", color = Quiet)
    }
}
@Composable fun NoteSettingsDialog(node: NoteNode, nodes: List<NoteNode>, onClose: () -> Unit, onSave: (NoteNode) -> Unit) {
    val context = LocalContext.current
    var title by remember(node.id) { mutableStateOf(node.title) }
    var tags by remember(node.id) { mutableStateOf(node.tags) }
    var mood by remember(node.id) { mutableStateOf(node.mood) }
    var display by remember(node.id) { mutableStateOf(node.imageDisplay) }
    var storage by remember(node.id) { mutableStateOf(node.imageStorage) }
    SoftDialog(if (node.kind == "folder") "分类设置" else "记录设置", onClose) {
        if (node.kind == "folder") OutlinedTextField(title, { title = it }, label = { Text("分类名称") }, modifier = Modifier.fillMaxWidth())
        if (node.kind != "folder") OutlinedTextField(tags, { tags = it }, label = { Text("标签（用空格分隔）") }, modifier = Modifier.fillMaxWidth())
        if (node.kind == "diary") ChoiceRow("可选心情", mood, listOf("" to "不记录", "开心" to "开心", "平静" to "平静", "充实" to "充实", "疲惫" to "疲惫", "低落" to "低落")) { mood = it }
        ChoiceRow("图片显示", display, listOf("inherit" to "跟随上级", "preview" to "正文预览", "card" to "图片卡片")) { display = it }
        val effective = TreeRules.imagePreference(node.copy(imageDisplay = display), nodes, false, imageDisplayDefault(context))
        Text("当前生效：${if (effective == "preview") "正文预览" else "图片卡片"}", color = Quiet, fontSize = 12.sp)
        ChoiceRow("新插入图片的保存方式", storage, listOf("inherit" to "跟随上级", "copy" to "保存副本", "reference" to "引用原图")) { storage = it }
        val saving = TreeRules.imagePreference(node.copy(imageStorage = storage), nodes, true, imageStorageDefault(context))
        Text("当前生效：${if (saving == "copy") "保存副本" else "引用原图"}。此设置只影响之后添加的图片。", color = Quiet, fontSize = 12.sp)
        Button(onClick = { if (node.kind != "folder" || title.isNotBlank()) { onSave(node.copy(title = title.trim(), tags = tags.trim(), mood = mood, imageDisplay = display, imageStorage = storage)); onClose() } }, modifier = Modifier.fillMaxWidth(), enabled = node.kind != "folder" || title.isNotBlank()) { Text("完成") }
    }
}
@Composable fun VersionsDialog(note: NoteNode, model: WorkspaceModel, onClose: () -> Unit, onRestore: (NoteNode) -> Unit) {
    var versions by remember { mutableStateOf<List<NoteVersion>>(emptyList()) }
    var chosen by remember { mutableStateOf<NoteNode?>(null) }
    LaunchedEffect(note.id) { versions = model.notes.versions(note.id) }
    SoftDialog("历史版本", onClose) {
        Text("恢复前会保留当前版本。", color = Quiet, fontSize = 12.sp)
        if (versions.isEmpty()) Text("还没有历史版本。", color = Quiet)
        versions.forEach { v -> TextButton(onClick = { chosen = decodeNode(org.json.JSONObject(v.snapshot)) }) { Text(dateText(v.createdAt, true)) } }
        chosen?.let { old ->
            HorizontalDivider(); Text(old.displayTitle()); Text(old.text.take(1200), fontSize = 14.sp)
            val scope = rememberCoroutineScope()
            Button(onClick = { scope.launch { model.notes.version(NoteVersion(nodeId = note.id, snapshot = jsonObject(note).toString())); onRestore(old.copy(id = note.id, parentId = note.parentId, kind = note.kind, day = note.day, deletedAt = null, deleteGroup = null)) } }) { Text("恢复此版本") }
        }
    }
}

data class TreeRow(val node: NoteNode, val depth: Int)
fun treeRows(nodes: List<NoteNode>, root: String?, expanded: Set<String>, tree: Boolean, sort: String): List<TreeRow> {
    fun ordered(children: List<NoteNode>) = when (sort) {
        "updated" -> children.sortedWith(compareByDescending<NoteNode> { it.pinned }.thenByDescending { it.updatedAt })
        "created" -> children.sortedWith(compareByDescending<NoteNode> { it.pinned }.thenByDescending { it.createdAt })
        else -> children.sortedWith(compareByDescending<NoteNode> { it.pinned }.thenBy { it.position }.thenByDescending { it.createdAt })
    }
    val grouped = nodes.groupBy { it.parentId }.mapValues { ordered(it.value) }
    val result = mutableListOf<TreeRow>()
    val stack = java.util.ArrayDeque<TreeRow>()
    grouped[root].orEmpty().asReversed().forEach { stack.addFirst(TreeRow(it, 0)) }
    val seen = mutableSetOf<String>()
    while (stack.isNotEmpty()) {
        val row = stack.removeFirst()
        if (!seen.add(row.node.id)) continue
        result += row
        if (tree && row.node.kind == "folder" && row.node.id in expanded) grouped[row.node.id].orEmpty().asReversed().forEach { stack.addFirst(TreeRow(it, row.depth + 1)) }
    }
    return result
}

@Composable fun MemoryLibrary(model: WorkspaceModel, parent: String?, onParent: (String?) -> Unit, onOpen: (NoteNode) -> Unit, onExport: (Set<String>) -> Unit, onSearch: () -> Unit, onSelection: (Boolean) -> Unit, onTrash: (Set<String>) -> Unit) {
    val nodes by model.nodes.collectAsState()
    val active = nodes.filter { it.deletedAt == null && it.kind != "diary" }
    val current = active.find { it.id == parent }
    val context = LocalContext.current
    val prefs = appPreferences(context)
    var tree by rememberSaveable { mutableStateOf(prefs.getBoolean("tree", false)) }
    var expandedValue by rememberSaveable { mutableStateOf(prefs.getString("expanded", "")!!) }
    val expanded = expandedValue.split('|').filter { it.isNotBlank() }.toSet()
    var sort by rememberSaveable { mutableStateOf("manual") }
    var arranging by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var menu by remember { mutableStateOf<NoteNode?>(null) }
    var moving by remember { mutableStateOf<NoteNode?>(null) }
    var setting by remember { mutableStateOf<NoteNode?>(null) }
    var pathMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf("") }
    val path = if (current == null) emptyList() else TreeRules.ancestors(current, nodes) + current
    val scopeIds = parent?.let { TreeRules.descendants(it, active) }
    val searched = query.isNotBlank() || tag.isNotBlank()
    val rows = if (searched) active.filter { (scopeIds == null || it.id in scopeIds) && (it.title + it.text + it.tags).contains(query, true) && (tag.isBlank() || tag in it.tags.split(' ')) }.map { TreeRow(it, 0) }
        else treeRows(active, parent, expanded, tree, sort)
    val listState = rememberLazyListState()
    LaunchedEffect(parent) { selected = emptySet(); query = ""; tag = "" }
    LaunchedEffect(selected.isNotEmpty()) { onSelection(selected.isNotEmpty()) }
    DisposableEffect(Unit) { onDispose { onSelection(false) } }
    BackHandler(enabled = selected.isNotEmpty() || arranging || parent != null) {
        when { selected.isNotEmpty() -> selected = emptySet(); arranging -> arranging = false; else -> onParent(current?.parentId) }
    }
    fun shift(n: NoteNode, direction: Int) {
        val siblings = active.filter { it.parentId == n.parentId }.sortedWith(compareByDescending<NoteNode> { it.pinned }.thenBy { it.position }.thenByDescending { it.createdAt })
        val index = siblings.indexOfFirst { it.id == n.id }
        val target = index + direction
        if (target !in siblings.indices || siblings[target].pinned != n.pinned) return
        val changed = siblings.toMutableList().apply { add(target, removeAt(index)) }
        changed.forEachIndexed { i, node -> model.save(node.copy(position = i)) }
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(18.dp, 10.dp, 18.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            PageTitle(current?.displayTitle() ?: "记忆", "随手记录，慢慢珍藏", back = if (parent != null) ({ onParent(current?.parentId) }) else null) {
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "搜索全部内容") }
                IconButton(onClick = { tree = !tree; prefs.edit().putBoolean("tree", tree).apply() }) { Icon(if (tree) Icons.Default.ViewList else Icons.Default.AccountTree, if (tree) "列表视图" else "树状视图") }
                Box { IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.MoreVert, "更多") }; DropdownMenu(sortMenu, { sortMenu = false }) {
                    listOf("manual" to "手动顺序", "updated" to "最近修改", "created" to "最近创建").forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { sort = key; arranging = false; sortMenu = false }) }
                    DropdownMenuItem(text = { Text(if (arranging) "完成排序" else "整理顺序") }, onClick = { arranging = !arranging; sort = "manual"; sortMenu = false })
                    if (current != null) DropdownMenuItem(text = { Text("分类设置") }, onClick = { setting = current; sortMenu = false })
                    DropdownMenuItem(text = { Text(if (current == null) "导出记忆模块" else "导出此分类") }, onClick = { onExport(if (current == null) active.map { it.id }.toSet() else TreeRules.descendants(current.id, active)); sortMenu = false })
                } }
            }
            if (path.isNotEmpty()) {
                Box {
                    TextButton(onClick = { pathMenu = true }) { Text((if (path.size > 2) "… / " else "记忆 / ") + path.takeLast(2).joinToString(" / ") { it.displayTitle() }, maxLines = 2) }
                    DropdownMenu(pathMenu, { pathMenu = false }) {
                        DropdownMenuItem(text = { Text("记忆首页") }, onClick = { onParent(null); pathMenu = false })
                        path.forEach { n -> DropdownMenuItem(text = { Text(n.displayTitle()) }, onClick = { onParent(n.id); pathMenu = false }) }
                    }
                }
            }
            OutlinedTextField(query, { query = it }, placeholder = { Text(if (parent == null) "搜索记忆与分类" else "搜索此分类与下级", fontSize = 14.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, null) })
            val tags = active.filter { scopeIds == null || it.id in scopeIds }.flatMap { it.tags.split(' ') }.filter { it.isNotBlank() }.distinct()
            if (tags.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { FilterChip(tag.isBlank(), { tag = "" }, label = { Text("全部") }); tags.forEach { t -> FilterChip(tag == t, { tag = t }, label = { Text(t) }) } }
            if (selected.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { selected = rows.map { it.node.id }.toSet() }) { Text("全选 ${selected.size}") }
                TextButton(onClick = { onExport(selected); selected = emptySet() }) { Text("导出") }
                TextButton(onClick = { onTrash(selected); selected = emptySet() }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { selected = emptySet() }) { Text("取消") }
            }
            if (arranging) Text("拖动把手调整同层顺序，也可点击上下箭头。", color = Quiet, fontSize = 12.sp)
        }
        if (rows.isEmpty()) item { SoftCard { Text(if (searched) "没有找到匹配内容" else "这里还没有内容", fontSize = 18.sp); Text("点击右下角＋，创建分类或记录一条记忆。", color = Quiet) } }
        items(rows, key = { it.node.id }) { row ->
            val n = row.node
            val indentation = (row.depth.coerceAtMost(3) * 12).dp
            SoftCard(modifier = Modifier.padding(start = indentation).combinedClickable(onClick = {
                if (selected.isNotEmpty()) selected = if (n.id in selected) selected - n.id else selected + n.id
                else if (n.kind == "folder") onParent(n.id) else onOpen(n)
            }, onLongClick = { if (!arranging) selected = selected + n.id }), color = if (n.id in selected) Lavender else androidx.compose.ui.graphics.Color.White) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (tree && n.kind == "folder" && !searched) IconButton(onClick = { val next = if (n.id in expanded) expanded - n.id else expanded + n.id; expandedValue = next.joinToString("|"); prefs.edit().putString("expanded", expandedValue).apply() }, modifier = Modifier.size(30.dp)) { Icon(if (n.id in expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, "展开或收起分类") }
                    Icon(if (n.kind == "folder") Icons.Default.Folder else Icons.Default.Description, null, tint = if (n.kind == "folder") Sky else Quiet, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(highlightText(n.displayTitle(), query), fontSize = 17.sp, fontWeight = if (n.kind == "folder") FontWeight.Medium else FontWeight.Normal)
                        if (row.depth > 3) Text("第 ${row.depth + 1} 层 · 点击分类聚焦", fontSize = 10.sp, color = Quiet)
                        if (searched) Text(TreeRules.path(n, nodes), fontSize = 11.sp, color = Quiet)
                    }
                    if (n.pinned) Icon(Icons.Default.PushPin, "已置顶", tint = Sky, modifier = Modifier.size(16.dp))
                    if (selected.isNotEmpty()) Checkbox(n.id in selected, { selected = if (n.id in selected) selected - n.id else selected + n.id })
                    else IconButton(onClick = { menu = n }, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.MoreHoriz, "条目操作") }
                }
                if (n.kind == "folder") Text("${active.count { it.parentId == n.id && it.kind == "folder" }} 个分类 · ${active.count { it.parentId == n.id && it.kind == "memory" }} 条记忆", color = Quiet, fontSize = 12.sp)
                else if (!tree || searched) { Text(highlightText(n.text.take(180), query), color = Quiet, maxLines = 3, overflow = TextOverflow.Ellipsis); Text(dateText(n.updatedAt), color = Quiet, fontSize = 11.sp) }
                if (arranging && !searched) {
                    val latestShift by rememberUpdatedState<(Int) -> Unit> { shift(n, it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DragHandle, "拖动排序", modifier = Modifier.size(40.dp).pointerInput(n.id) { var distance = 0f; detectVerticalDragGestures(onVerticalDrag = { change, amount -> change.consume(); distance += amount; if (kotlin.math.abs(distance) > 55f) { latestShift(if (distance > 0) 1 else -1); distance = 0f } }, onDragEnd = { distance = 0f }) })
                        TextButton(onClick = { shift(n, -1) }) { Text("上移") }; TextButton(onClick = { shift(n, 1) }) { Text("下移") }
                    }
                }
            }
        }
    }
    menu?.let { n -> SoftDialog(n.displayTitle(), { menu = null }) {
        TextButton(onClick = { model.save(n.copy(pinned = !n.pinned)); menu = null }) { Text(if (n.pinned) "取消置顶" else "置顶") }
        TextButton(onClick = { moving = n; menu = null }) { Text("移动到分类") }
        TextButton(onClick = { setting = n; menu = null }) { Text(if (n.kind == "folder") "分类设置" else "记录设置") }
        TextButton(onClick = { onExport(TreeRules.descendants(n.id, active)); menu = null }) { Text("导出") }
        TextButton(onClick = { onTrash(setOf(n.id)); menu = null }) { Text("删除", color = MaterialTheme.colorScheme.error) }
    } }
    setting?.let { n -> NoteSettingsDialog(n, nodes, { setting = null }) { model.save(it) } }
    moving?.let { n -> SoftDialog("移动到", { moving = null }) {
        TextButton(onClick = { model.move(n, null); moving = null }) { Text("记忆首页") }
        val destinations = active.filter { it.kind == "folder" && TreeRules.canMove(n, it, nodes) }
        var search by remember { mutableStateOf("") }
        OutlinedTextField(search, { search = it }, placeholder = { Text("搜索分类") }, modifier = Modifier.fillMaxWidth())
        destinations.filter { TreeRules.path(it, nodes).contains(search, true) }.forEach { target -> TextButton(onClick = { model.move(n, target); moving = null }) { Text(TreeRules.path(target, nodes)) } }
    } }
}

@Composable fun DiaryLibrary(model: WorkspaceModel, onOpen: (NoteNode, Boolean) -> Unit, onSearch: () -> Unit, onExport: (Set<String>) -> Unit, onTrash: (Set<String>) -> Unit, onSelection: (Boolean) -> Unit) {
    val all by model.nodes.collectAsState()
    val diaries = all.filter { it.kind == "diary" && it.deletedAt == null }.sortedByDescending { it.day }
    val context = LocalContext.current
    val prefs = appPreferences(context)
    var calendar by rememberSaveable { mutableStateOf(prefs.getBoolean("diaryCalendar", false)) }
    var monthValue by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val month = YearMonth.parse(monthValue)
    var favorites by rememberSaveable { mutableStateOf(false) }
    var past by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var menu by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val shown = diaries.filter { (!favorites || it.favorite) && (!past || it.day?.let { d -> Instant.ofEpochMilli(d).atZone(ZoneId.systemDefault()).toLocalDate().let { it.month == today.month && it.dayOfMonth == today.dayOfMonth && it.year < today.year } } == true) && (it.title + it.text + it.tags).contains(query, true) }
    LaunchedEffect(selected.isNotEmpty()) { onSelection(selected.isNotEmpty()) }
    DisposableEffect(Unit) { onDispose { onSelection(false) } }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 10.dp, 18.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            PageTitle("日记", "记录今天，也回望过去") {
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "搜索") }
                IconButton(onClick = { calendar = !calendar; prefs.edit().putBoolean("diaryCalendar", calendar).apply() }) { Icon(if (calendar) Icons.Default.ViewList else Icons.Default.CalendarMonth, "月历或时间线") }
                Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }; DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text("导出日记模块") }, onClick = { onExport(diaries.map { it.id }.toSet()); menu = false }) } }
            }
            OutlinedTextField(query, { query = it }, placeholder = { Text("搜索日记、标签") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(favorites, { favorites = !favorites }, label = { Text("收藏") }); FilterChip(past, { past = !past }, label = { Text("往年今日") }) }
            if (selected.isNotEmpty()) Row { TextButton(onClick = { selected = shown.map { it.id }.toSet() }) { Text("全选") }; TextButton(onClick = { onExport(selected); selected = emptySet() }) { Text("导出") }; TextButton(onClick = { onTrash(selected); selected = emptySet() }) { Text("删除") }; TextButton(onClick = { selected = emptySet() }) { Text("取消") } }
        }
        if (calendar) item { DiaryCalendar(month, diaries.mapNotNull { it.day }.toSet(), { monthValue = month.minusYears(1).toString() }, { monthValue = month.minusMonths(1).toString() }, { monthValue = month.plusMonths(1).toString() }, { monthValue = month.plusYears(1).toString() }) { day ->
            val existing = diaries.find { it.day == day }
            onOpen(existing ?: model.newNote("diary", day = day), existing == null)
        } }
        if (shown.isEmpty()) item { SoftCard { Text(if (past) "往年这一天还没有记录" else "从今天的一件小事开始"); Text("点击＋写今天的日记，也可以从月历选择日期。", color = Quiet) } }
        items(shown, key = { it.id }) { n -> SoftCard(modifier = Modifier.combinedClickable(onClick = { if (selected.isNotEmpty()) selected = if (n.id in selected) selected - n.id else selected + n.id else onOpen(n, false) }, onLongClick = { selected = selected + n.id })) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(dateText(n.day ?: n.createdAt), fontSize = 13.sp, color = Sky, modifier = Modifier.weight(1f)); if (n.favorite) Icon(Icons.Default.Star, "收藏", tint = Sky); if (selected.isNotEmpty()) Checkbox(n.id in selected, { selected = if (n.id in selected) selected - n.id else selected + n.id }) }
            Text(highlightText(n.displayTitle(), query), fontSize = 19.sp, fontWeight = FontWeight.Medium)
            Text(highlightText(n.text.take(240), query), color = Quiet, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (n.mood.isNotBlank() || n.tags.isNotBlank()) Text(listOf(n.mood, n.tags).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = Quiet)
        } }
    }
}
