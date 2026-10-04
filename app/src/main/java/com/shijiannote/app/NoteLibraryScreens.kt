@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shijiannote.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
    var mood by remember(node.id) { mutableStateOf(node.mood) }
    var display by remember(node.id) { mutableStateOf(node.imageDisplay) }
    var storage by remember(node.id) { mutableStateOf(node.imageStorage) }
    var forceChildren by remember(node.id) { mutableStateOf(node.forceChildren) }
    var lockedHint by remember { mutableStateOf(false) }
    val enforced = TreeRules.forcedBy(node, nodes)
    SoftDialog(if (node.kind == "folder") "分类设置" else "记录设置", onClose) {
        if (node.kind == "folder" && !MemorySpaces.isRoot(node.id)) OutlinedTextField(title, { title = it }, label = { Text("分类名称") }, modifier = Modifier.fillMaxWidth())
        if (node.kind == "diary") ChoiceRow("可选心情", mood, listOf("" to "不记录", "开心" to "开心", "平静" to "平静", "充实" to "充实", "疲惫" to "疲惫", "低落" to "低落")) { mood = it }
        ChoiceRow("图片显示", if (enforced != null) "inherit" else display, listOf("inherit" to "跟随上级", "preview" to "正文预览", "card" to "图片卡片")) { if (enforced != null) lockedHint = true else display = it }
        val effective = TreeRules.imagePreference(node.copy(imageDisplay = display), nodes, false, imageDisplayDefault(context))
        Text("当前生效：${if (effective == "preview") "正文预览" else "图片卡片"}", color = Quiet, fontSize = 12.sp)
        ChoiceRow("图片保存方式", if (enforced != null) "inherit" else storage, listOf("inherit" to "跟随上级", "copy" to "保存副本", "reference" to "引用原图")) { if (enforced != null) lockedHint = true else storage = it }
        val saving = TreeRules.imagePreference(node.copy(imageStorage = storage), nodes, true, imageStorageDefault(context))
        Text("当前生效：${if (saving == "copy") "保存副本" else "引用原图"}。保存副本会补存已有的引用图片；已有副本会保留。", color = Quiet, fontSize = 12.sp)
        if (node.kind == "folder") {
            Row(Modifier.fillMaxWidth().toggleable(value = enforced != null || forceChildren, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = { if (enforced != null) lockedHint = true else forceChildren = it }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(enforced != null || forceChildren, null)
                Text("强制下级沿用本分类设置", Modifier.weight(1f))
            }
            Text("开启后统一所有下级分类和记忆的图片显示与保存方式。解除后恢复各自原来的配置。", color = Quiet, fontSize = 12.sp)
        }
        if (enforced != null) Text("已锁定 · 来源：${TreeRules.path(enforced, nodes)}", color = Sky, fontSize = 12.sp)
        Button(onClick = { if (node.kind != "folder" || title.isNotBlank()) { onSave(node.copy(title = title.trim(), mood = mood, imageDisplay = display, imageStorage = storage, forceChildren = forceChildren)); onClose() } }, modifier = Modifier.fillMaxWidth(), enabled = node.kind != "folder" || title.isNotBlank()) { Text("完成") }
    }
    if (lockedHint && enforced != null) SoftDialog("设置已被上级锁定", { lockedHint = false }) {
        Text("请先前往路径“${TreeRules.path(enforced, nodes)}”解除强制下级设置。")
        TextButton(onClick = { lockedHint = false }) { Text("知道了") }
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

@Composable fun MemoryLibrary(model: WorkspaceModel, parent: String?, onParent: (String?) -> Unit, onOpen: (NoteNode) -> Unit, onExport: (Set<String>) -> Unit, onSearch: () -> Unit, onSelection: (Boolean) -> Unit, onTrash: (Set<String>) -> Unit, onStructure: () -> Unit, onBackOverride: (() -> Unit)? = null) {
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
    var selected by rememberSaveable(stateSaver = Saver<Set<String>, List<String>>(save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(setOf<String>()) }
    var selectionMode by rememberSaveable { mutableStateOf("") }
    val selecting = selectionMode.isNotEmpty()
    var menu by remember { mutableStateOf<NoteNode?>(null) }
    var moving by remember { mutableStateOf<Set<String>?>(null) }
    var setting by remember { mutableStateOf<NoteNode?>(null) }
    var pathMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var orderMenu by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf("") }
    val path = if (current == null) emptyList() else TreeRules.ancestors(current, nodes) + current
    val scopeIds = parent?.let { TreeRules.descendants(it, active) }
    val searched = query.isNotBlank() || tag.isNotBlank()
    val rows = if (searched && !selecting) active.filter { !MemorySpaces.isRoot(it.id) && (scopeIds == null || it.id in scopeIds) && (it.title + it.text + it.tags).contains(query, true) && (tag.isBlank() || tag in it.tags.split(' ')) }.map { TreeRow(it, 0) }
        else treeRows(active, if (selectionMode == "export") null else parent,
            if (selectionMode == "export") expanded + setOf(MemorySpaces.WORK_ID, MemorySpaces.LIFE_ID) else expanded,
            tree || selecting, sort).filterNot { MemorySpaces.isRoot(it.node.id) }
    val listState = rememberLazyListState()
    val selectableIds = if (selectionMode == "export") active.filterNot { MemorySpaces.isRoot(it.id) }.map { it.id }.toSet()
        else rows.flatMap { TreeRules.descendants(it.node.id, active) }.filterNot { MemorySpaces.isRoot(it) }.toSet()
    LaunchedEffect(active.map { it.id }) {
        val remaining = selected.intersect(active.filterNot { MemorySpaces.isRoot(it.id) }.map { it.id }.toSet())
        if (selected.isNotEmpty() && remaining.isEmpty()) selectionMode = ""
        selected = remaining
    }
    LaunchedEffect(selecting) { onSelection(selecting) }
    DisposableEffect(Unit) { onDispose { onSelection(false) } }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    fun back() {
        when { imeVisible -> { keyboard?.hide(); focus.clearFocus() }; selecting -> { selected = emptySet(); selectionMode = "" }; arranging -> arranging = false; searched -> { query = ""; tag = ""; focus.clearFocus() }; onBackOverride != null -> onBackOverride(); else -> onParent(current?.parentId) }
    }
    BackHandler(enabled = selecting || arranging || searched || parent != null || onBackOverride != null) { back() }
    fun toggle(n: NoteNode) { selected = TreeRules.toggleSelection(selected, n, active) }
    fun shift(n: NoteNode, direction: Int) {
        val siblings = active.filter { it.parentId == n.parentId }.sortedWith(compareByDescending<NoteNode> { it.pinned }.thenBy { it.position }.thenByDescending { it.createdAt })
        val index = siblings.indexOfFirst { it.id == n.id }
        val target = index + direction
        if (target !in siblings.indices || siblings[target].pinned != n.pinned) return
        val changed = siblings.toMutableList().apply { add(target, removeAt(index)) }
        changed.forEachIndexed { i, node -> model.save(node.copy(position = i)) }
    }
    if (parent == null && selectionMode != "export") {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 10.dp, 18.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                PageTitle("记忆", "工作与生活，分别收纳", back = if (onBackOverride != null) ({ back() }) else null) {
                    IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "搜索全部内容") }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            DropdownMenuItem(text = { Text("结构树") }, onClick = { sortMenu = false; onStructure() })
                            DropdownMenuItem(text = { Text("导出") }, onClick = { selected = emptySet(); selectionMode = "export"; sortMenu = false })
                        }
                    }
                }
            }
            items(listOf(MemorySpaces.WORK_ID, MemorySpaces.LIFE_ID), key = { it }) { id ->
                val name = if (id == MemorySpaces.WORK_ID) "工作" else "生活"
                SoftCard(Modifier.fillMaxWidth().heightIn(min = 176.dp).clickable { onParent(id) }, color = if (id == MemorySpaces.WORK_ID) Lavender else Mint) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (id == MemorySpaces.WORK_ID) Icons.Default.WorkOutline else Icons.Default.Home, name, tint = Sky, modifier = Modifier.size(38.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                            Text(name, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            Text(if (id == MemorySpaces.WORK_ID) "收纳工作中的分类与记忆" else "收纳生活中的分类与记忆", color = Quiet)
                        }
                        Icon(Icons.Default.ChevronRight, "进入$name", tint = Sky)
                    }
                }
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(18.dp, 10.dp, 18.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            PageTitle(if (selectionMode == "export") "选择导出内容" else current?.displayTitle() ?: "记忆", if (selectionMode == "export") "勾选分类会包含其全部下级内容" else "随手记录，慢慢珍藏", back = if (parent != null || searched || selecting || onBackOverride != null) ({ back() }) else null) {
                if (!selecting) {
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "搜索全部内容") }
                IconButton(onClick = { tree = !tree; prefs.edit().putBoolean("tree", tree).apply() }) { Icon(if (tree) Icons.Default.ViewList else Icons.Default.AccountTree, if (tree) "列表视图" else "树状视图") }
                Box { IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.MoreVert, "更多") }; DropdownMenu(sortMenu, { sortMenu = false; orderMenu = false }, modifier = Modifier.width(144.dp)) {
                    DropdownMenuItem(text = { Text("‹　排序") }, onClick = { orderMenu = true })
                    DropdownMenuItem(text = { Text("结构树") }, onClick = { sortMenu = false; onStructure() })
                    if (current != null) DropdownMenuItem(text = { Text("分类设置") }, onClick = { setting = current; sortMenu = false })
                    DropdownMenuItem(text = { Text("导出") }, onClick = { selected = emptySet(); selectionMode = "export"; sortMenu = false })
                }
                    DropdownMenu(orderMenu && sortMenu, { orderMenu = false }, offset = DpOffset(-240.dp, 0.dp), modifier = Modifier.width(144.dp)) {
                        listOf("manual" to "手动顺序", "updated" to "最近修改", "created" to "最近创建", "arrange" to "整理顺序").forEach { (key, label) ->
                            DropdownMenuItem(text = { Text((if (if (key == "arrange") arranging else !arranging && sort == key) "✓ " else "") + label) }, onClick = { arranging = key == "arrange"; sort = if (arranging) "manual" else key; orderMenu = false; sortMenu = false })
                        }
                    }
                }
                }
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
            if (parent != null && !selecting) OutlinedTextField(query, { query = it }, placeholder = { Text("搜索此分类与下级的标题、正文、标签", fontSize = 14.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "清除搜索") } })
            val tags = active.filter { scopeIds == null || it.id in scopeIds }.flatMap { it.tags.split(' ') }.filter { it.isNotBlank() }.distinct()
            if (tags.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { FilterChip(tag.isBlank(), { tag = "" }, label = { Text("全部") }); tags.forEach { t -> FilterChip(tag == t, { tag = t }, label = { Text(t) }) } }
            if (selecting) NoteSelectionActions(selected.size, selectableIds.isNotEmpty() && selected.containsAll(selectableIds), selectableIds.isNotEmpty(),
                onAll = { selected = if (selected.containsAll(selectableIds)) emptySet() else selectableIds },
                onCancel = { selected = emptySet(); selectionMode = "" },
                onExport = if (selectionMode == "export") ({ onExport(selected); selected = emptySet(); selectionMode = "" }) else null,
                onMove = if (selectionMode == "manage") ({ moving = selected }) else null,
                onDelete = if (selectionMode == "manage") ({ onTrash(selected) }) else null)
            if (arranging) Text("拖动把手调整同层顺序，也可点击上下箭头。", color = Quiet, fontSize = 12.sp)
        }
        if (rows.isEmpty()) item { SoftCard { Text(if (searched) "没有找到匹配内容" else "这里还没有内容", fontSize = 18.sp); Text("点击右下角＋，创建分类或记录一条记忆。", color = Quiet) } }
        itemsIndexed(rows, key = { _, row -> row.node.id }) { index, row ->
            val n = row.node
            if (selectionMode == "export" && (index == 0 || MemorySpaces.rootId(rows[index - 1].node, active) != MemorySpaces.rootId(n, active))) {
                Text(if (MemorySpaces.rootId(n, active) == MemorySpaces.WORK_ID) "工作" else "生活", Modifier.padding(vertical = 8.dp), color = Sky, fontWeight = FontWeight.Medium)
            }
            val indentation = (row.depth.coerceAtMost(3) * 12).dp
            SoftCard(modifier = Modifier.padding(start = indentation).combinedClickable(onClick = {
                if (selecting) toggle(n)
                else if (n.kind == "folder") onParent(n.id) else onOpen(n)
            }, onLongClick = { if (!arranging) { if (selecting) toggle(n) else { selectionMode = "manage"; selected = TreeRules.descendants(n.id, active) } } }), color = if (n.id in selected) Lavender else androidx.compose.ui.graphics.Color.White) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if ((tree || selecting) && n.kind == "folder" && (!searched || selecting)) IconButton(onClick = { val next = if (n.id in expanded) expanded - n.id else expanded + n.id; expandedValue = next.joinToString("|"); prefs.edit().putString("expanded", expandedValue).apply() }, modifier = Modifier.size(30.dp)) { Icon(if (n.id in expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, "展开或收起分类") }
                    Icon(if (n.kind == "folder") Icons.Default.Folder else Icons.Default.Description, null, tint = if (n.kind == "folder") Sky else Quiet, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(highlightText(n.displayTitle(), query), fontSize = 17.sp, fontWeight = if (n.kind == "folder") FontWeight.Medium else FontWeight.Normal)
                        if (row.depth > 3) Text("第 ${row.depth + 1} 层 · 点击分类聚焦", fontSize = 10.sp, color = Quiet)
                        if (searched) Text(TreeRules.path(n, nodes), fontSize = 11.sp, color = Quiet)
                    }
                    if (n.pinned) Icon(Icons.Default.PushPin, "已置顶", tint = Sky, modifier = Modifier.size(16.dp))
                    if (selecting) Checkbox(n.id in selected, { toggle(n) })
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
        if (n.kind != "folder" && !MemorySpaces.isRoot(n.id)) TextButton(onClick = { moving = setOf(n.id); menu = null }) { Text("移动到") }
        TextButton(onClick = { setting = n; menu = null }) { Text(if (n.kind == "folder") "分类设置" else "记录设置") }
        TextButton(onClick = { onTrash(setOf(n.id)); menu = null }) { Text("删除", color = MaterialTheme.colorScheme.error) }
    } }
    setting?.let { n -> NoteSettingsDialog(n, nodes, { setting = null }) { model.updateImageSettings(it) } }
    moving?.let { ids -> MoveDestinationPicker(model, ids, onClose = { moving = null; selected = emptySet(); selectionMode = "" }) }
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
    val query = ""
    var selected by rememberSaveable(stateSaver = Saver<Set<String>, List<String>>(save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(setOf<String>()) }
    var selectionMode by rememberSaveable { mutableStateOf("") }
    val selecting = selectionMode.isNotEmpty()
    var menu by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val shown = if (selectionMode == "export") diaries else diaries.filter { (!favorites || it.favorite) && (!past || it.day?.let { d -> Instant.ofEpochMilli(d).atZone(ZoneId.systemDefault()).toLocalDate().let { it.month == today.month && it.dayOfMonth == today.dayOfMonth && it.year < today.year } } == true) && (it.title + it.text + it.tags).contains(query, true) }
    val selectableIds = shown.map { it.id }.toSet()
    LaunchedEffect(diaries.map { it.id }) {
        val remaining = selected.intersect(diaries.map { it.id }.toSet())
        if (selected.isNotEmpty() && remaining.isEmpty()) selectionMode = ""
        selected = remaining
    }
    LaunchedEffect(selecting) { onSelection(selecting) }
    DisposableEffect(Unit) { onDispose { onSelection(false) } }
    BackHandler(enabled = selecting) { selected = emptySet(); selectionMode = "" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 10.dp, 18.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            PageTitle(if (selectionMode == "export") "选择导出日记" else "日记", "记录今天，也回望过去", back = if (selecting) ({ selected = emptySet(); selectionMode = "" }) else null) {
                if (!selecting) {
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "搜索") }
                IconButton(onClick = { calendar = !calendar; prefs.edit().putBoolean("diaryCalendar", calendar).apply() }) { Icon(if (calendar) Icons.Default.ViewList else Icons.Default.CalendarMonth, "月历或时间线") }
                Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }; DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text("导出日记") }, onClick = { selected = emptySet(); selectionMode = "export"; menu = false }) } }
                }
            }
            if (!selecting) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(favorites, { favorites = !favorites }, label = { Text("收藏") }); FilterChip(past, { past = !past }, label = { Text("往年今日") }) }
            if (selecting) NoteSelectionActions(selected.size, selectableIds.isNotEmpty() && selected.containsAll(selectableIds), selectableIds.isNotEmpty(),
                onAll = { selected = if (selected.containsAll(selectableIds)) emptySet() else selectableIds },
                onCancel = { selected = emptySet(); selectionMode = "" },
                onExport = if (selectionMode == "export") ({ onExport(selected); selected = emptySet(); selectionMode = "" }) else null,
                onDelete = if (selectionMode == "manage") ({ onTrash(selected) }) else null)
        }
        if (calendar && !selecting) item { DiaryCalendar(month, diaries.mapNotNull { it.day }.toSet(), { monthValue = month.minusYears(1).toString() }, { monthValue = month.minusMonths(1).toString() }, { monthValue = month.plusMonths(1).toString() }, { monthValue = month.plusYears(1).toString() }) { day ->
            val existing = diaries.find { it.day == day }
            onOpen(existing ?: model.newNote("diary", day = day), existing == null)
        } }
        if (shown.isEmpty()) item { SoftCard { Text(if (past) "往年这一天还没有记录" else "从今天的一件小事开始"); Text("点击＋写今天的日记，也可以从月历选择日期。", color = Quiet) } }
        items(shown, key = { it.id }) { n -> SoftCard(modifier = Modifier.combinedClickable(onClick = { if (selecting) selected = if (n.id in selected) selected - n.id else selected + n.id else onOpen(n, false) }, onLongClick = { if (!selecting) selectionMode = "manage"; selected = if (n.id in selected) selected - n.id else selected + n.id }), color = if (n.id in selected) Lavender else androidx.compose.ui.graphics.Color.White) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(dateText(n.day ?: n.createdAt), fontSize = 13.sp, color = Sky, modifier = Modifier.weight(1f)); if (n.favorite) Icon(Icons.Default.Star, "收藏", tint = Sky); if (selecting) Checkbox(n.id in selected, { selected = if (n.id in selected) selected - n.id else selected + n.id }) }
            Text(highlightText(n.displayTitle(), query), fontSize = 19.sp, fontWeight = FontWeight.Medium)
            Text(highlightText(n.text.take(240), query), color = Quiet, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (n.mood.isNotBlank() || n.tags.isNotBlank()) Text(listOf(n.mood, n.tags).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = Quiet)
        } }
    }
}
