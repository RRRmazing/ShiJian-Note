@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shijiannote.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.*
import com.shijiannote.app.data.*
import java.time.*
import kotlinx.coroutines.launch

@Composable fun ScheduleLibrary(model: WorkspaceModel, onEdit: (ScheduleEvent?) -> Unit, onSearch: () -> Unit, onExport: () -> Unit, onSelection: (Boolean) -> Unit) {
    val all by model.schedules.collectAsState()
    var history by rememberSaveable { mutableStateOf(false) }
    val query = ""
    var menu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Set<Long>?>(null) }
    LaunchedEffect(selectionMode) { onSelection(selectionMode) }
    DisposableEffect(Unit) { onDispose { onSelection(false) } }
    val shown = all.filter { it.deletedAt == null && it.archived == history && (it.title + it.note).contains(query, true) }.sortedBy { it.eventAt }
    val shownIds = shown.map { it.id }.toSet()
    val selectedIds = selected.intersect(shownIds)
    val allSelected = shownIds.isNotEmpty() && selectedIds == shownIds
    fun exitSelection() { selected = emptySet(); selectionMode = false }
    androidx.activity.compose.BackHandler(history || selectionMode) { if (selectionMode) exitSelection() else history = false }
    deleting?.let { ids ->
        val count = all.count { it.id in ids && it.deletedAt == null }
        ConfirmTrashDialog("将这${count}条事务移到回收站", onCancel = { deleting = null }, enabled = count > 0,
            onDelete = { model.trashSchedules(ids); deleting = null; exitSelection() })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 10.dp, 18.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            PageTitle(if (history) "时间表历史" else "时间表", dateText(System.currentTimeMillis()), back = if (history) ({ history = false; exitSelection() }) else null) {
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "搜索") }
                Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }; DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(if (history) "返回时间表" else "历史与归档") }, onClick = { history = !history; menu = false; exitSelection() })
                    DropdownMenuItem(text = { Text("导出 ICS 日历") }, onClick = { onExport(); menu = false })
                } }
            }
            if (selectionMode) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("已选${selectedIds.size}", color = Quiet, fontSize = 13.sp)
                Button(onClick = { selected = if (allSelected) emptySet() else shownIds }, modifier = Modifier.semantics { this.selected = allSelected }, enabled = shownIds.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer, contentColor = if (allSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer)) { Text(if (allSelected) "取消全选" else "全选") }
                TextButton(onClick = { shown.filter { it.id in selectedIds }.forEach { model.schedule(it.copy(archived = !history)) }; exitSelection() }, enabled = selectedIds.isNotEmpty()) { Text(if (history) "恢复" else "归档") }
                TextButton(onClick = { exitSelection() }) { Text("取消") }
                TextButton(onClick = { deleting = selectedIds }, enabled = selectedIds.isNotEmpty(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
            }
        }
        if (shown.isEmpty()) item { SoftCard { Text(if (query.isNotBlank()) "没有匹配的事务" else if (history) "还没有历史事务" else "给接下来的时间留个位置"); Text("点击＋安排事务，也可以设置提醒。", color = Quiet) } }
        shown.groupBy { Instant.ofEpochMilli(it.eventAt).atZone(ZoneId.systemDefault()).toLocalDate() }.forEach { (day, events) ->
            item("day$day") { Text(dateText(dayMillis(day)), color = Sky, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            items(events, key = { it.id }) { event ->
                var open by remember { mutableStateOf(false) }
                @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                SoftCard(Modifier.combinedClickable(onClick = { if (selectionMode) selected = if (event.id in selected) selected - event.id else selected + event.id else open = !open }, onLongClick = { selectionMode = true; selected = selected + event.id }), color = if (event.important) Lavender else androidx.compose.ui.graphics.Color.White) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(highlightText(event.title, query), fontSize = 18.sp, modifier = Modifier.weight(1f)); if (selectionMode) Checkbox(event.id in selected, { selected = if (event.id in selected) selected - event.id else selected + event.id }) }
                    Text(dateText(event.eventAt, true) + if (!history && event.eventAt <= System.currentTimeMillis()) " · 已开始" else "", color = Quiet, fontSize = 12.sp)
                    if (event.reminderEnabled) Text(if (event.reminderTriggered) "已提醒" else "提醒：提前 ${event.reminderDays}天 ${event.reminderHours}时 ${event.reminderMinutes}分", color = Sky, fontSize = 12.sp)
                    if (open) {
                        if (event.note.isNotBlank()) Text(highlightText(event.note, query))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                                TextButton(onClick = { onEdit(event) }) { Text("编辑") }; TextButton(onClick = { model.schedule(event.copy(archived = !history)) }) { Text(if (history) "恢复" else "归档") }
                            }
                            Spacer(Modifier.width(12.dp))
                            IconButton(onClick = { model.toggleScheduleImportant(event.id) }) { Icon(if (event.important) Icons.Default.Star else Icons.Default.StarBorder, if (event.important) "取消重要" else "标记重要", tint = Sky) }
                        }
                    }
                }
            }
        }
    }
}

private data class RepeatTodoEntry(val group: TodoBoardWithItems, val task: TodoItem?, val nextAt: Long, val skipped: Boolean) {
    val board: TodoBoard get() = group.board
    val title: String get() = task?.text ?: board.summary
    val rule: String? get() = task?.reminderRule ?: board.reminderRule
    val customDays: Int get() = task?.reminderCustomDays ?: board.reminderCustomDays
}

@Composable fun TodoLibrary(model: WorkspaceModel, onEditBoard: (TodoBoardWithItems?) -> Unit, onSearch: () -> Unit, onExport: () -> Unit, onSelection: (Boolean) -> Unit, onViewChange: (String) -> Unit) {
    val all by model.todos.collectAsState()
    var view by rememberSaveable { mutableStateOf("lists") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(15_000) } }
    LaunchedEffect(view) { onViewChange(view) }
    var completed by rememberSaveable { mutableStateOf(false) }
    var taskEdit by remember { mutableStateOf<TodoItem?>(null) }
    var newTaskBoard by remember { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var deletingBoards by remember { mutableStateOf<Set<Long>?>(null) }
    var repeatAction by remember { mutableStateOf<RepeatTodoEntry?>(null) }
    var repeatDelete by remember { mutableStateOf<RepeatTodoEntry?>(null) }
    LaunchedEffect(selected.isNotEmpty()) { onSelection(selected.isNotEmpty()) }
    DisposableEffect(Unit) { onDispose { onSelection(false) } }
    var arranging by remember { mutableStateOf(false) }
    val active = all.filter { it.board.deletedAt == null && it.board.archived == (view == "history") }
        .sortedWith(compareByDescending<TodoBoardWithItems> { it.board.pinned }.thenBy { it.board.position })
    val todayDate = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = dayMillis(todayDate)
    val tomorrow = dayMillis(todayDate.plusDays(1))
    val dailyView = view == "today" || view == "tomorrow"
    val selectedDay = if (view == "tomorrow") tomorrow else today
    fun matches(item: TodoItem, board: TodoBoard) =
        (view == "history" || completed || !item.completed) && taskInView(item, board, view, now, includeCompleted = completed)
    fun edit(item: TodoItem) { taskEdit = item; newTaskBoard = item.boardId }
    val repeatEntries = active.flatMap { group ->
        val board = group.board
        val live = group.items.filter { it.deletedAt == null && !it.completed }
        if (isUnifiedBoard(board)) {
            val next = nextBoardReminderAt(board.copy(reminderSkipAt = null), now)
            if (board.reminderRule != null && live.isNotEmpty() && next != null)
                listOf(RepeatTodoEntry(group, null, next, board.reminderSkipAt?.let { it > now } == true)) else emptyList()
        } else live.mapNotNull { task ->
            val next = nextTaskReminderAt(task.copy(reminderSkipAt = null), board, now)
            if (task.reminderRule != null && next != null) RepeatTodoEntry(group, task, next, task.reminderSkipAt?.let { it > now } == true) else null
        }
    }.sortedBy { it.nextAt }
    val dailyTasks = if (dailyView) active.flatMap { group -> group.items.filter { matches(it, group.board) }.map { group.board to it } }
        .sortedWith(compareBy<Pair<TodoBoard, TodoItem>> { taskReminderOnDay(it.second, it.first, selectedDay) ?: Long.MAX_VALUE }.thenBy { it.second.completed }.thenBy { it.second.position }) else emptyList()
    androidx.activity.compose.BackHandler(view != "lists" || selected.isNotEmpty() || arranging) {
        when { selected.isNotEmpty() -> selected = emptySet(); arranging -> arranging = false; else -> view = "lists" }
    }
    deletingBoards?.let { ids ->
        ConfirmTrashDialog("将所选待办框及其中的事项移到回收站", { deletingBoards = null }, {
            active.filter { it.board.id in ids }.forEach { model.board(it.board.copy(deletedAt = System.currentTimeMillis())) }
            deletingBoards = null; selected = emptySet()
        })
    }
    repeatAction?.let { entry ->
        SoftDialog(entry.title, { repeatAction = null }) {
            TextButton(onClick = {
                if (entry.task == null) {
                    if (entry.skipped) model.restoreBoardReminder(entry.board) else model.skipBoardReminder(entry.board)
                } else {
                    if (entry.skipped) model.restoreTaskReminder(entry.task) else model.skipTaskReminder(entry.task)
                }
                repeatAction = null
            }, modifier = Modifier.fillMaxWidth()) { Text(if (entry.skipped) "恢复下一次提醒" else "跳过下一次提醒") }
            TextButton(onClick = {
                if (entry.task == null) model.closeBoardRepeat(entry.board) else model.closeTaskRepeat(entry.task)
                repeatAction = null
            }, modifier = Modifier.fillMaxWidth()) { Text("关闭重复") }
            TextButton(onClick = { repeatDelete = entry; repeatAction = null }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
            TextButton(onClick = { repeatAction = null }, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
    }
    repeatDelete?.let { entry ->
        ConfirmTrashDialog(if (entry.task == null) "将整个待办框“${entry.board.summary}”及其中所有事项移到回收站" else "将事项“${entry.title}”移到回收站", { repeatDelete = null }, {
            if (entry.task == null) model.board(entry.board.copy(deletedAt = System.currentTimeMillis()))
            else model.task(entry.task.copy(deletedAt = System.currentTimeMillis()))
            repeatDelete = null
        })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 10.dp, 18.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            PageTitle("待办", "一步一步，做好今天") {
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "搜索") }
                Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }; DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("历史与归档") }, onClick = { view = "history"; selected = emptySet(); menu = false })
                    DropdownMenuItem(text = { Text("导出 CSV") }, onClick = { onExport(); menu = false })
                    DropdownMenuItem(text = { Text(if (arranging) "完成排序" else "整理清单顺序") }, onClick = { arranging = !arranging; view = "lists"; menu = false })
                } }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("lists" to "清单", "repeat" to "重复", "today" to "今天", "tomorrow" to "明天", "overdue" to "逾期", "important" to "重要").forEach { (key, label) ->
                    FilterChip(view == key, { view = key; selected = emptySet(); arranging = false }, label = { Text(label) })
                }
                if (view == "history") FilterChip(true, { view = "lists" }, label = { Text("历史") })
            }
            if (view != "repeat") FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(completed, { completed = !completed }, label = { Text("显示已完成") })
                if (!dailyView) OutlinedButton(onClick = { model.collapseBoards(active.map { it.board.id }) }) { Icon(Icons.Default.UnfoldLess, null); Text("一键收起") }
                if (selected.isNotEmpty()) {
                    TextButton(onClick = { deletingBoards = selected }) { Text("删除所选") }
                    TextButton(onClick = { selected = emptySet() }) { Text("取消") }
                }
            }
        }
        when {
            view == "repeat" -> {
                if (repeatEntries.isEmpty()) item { SoftCard { Text("还没有重复提醒"); Text("在统一时间待办框或独立事项中设置提醒频次。", color = Quiet) } }
                items(repeatEntries, key = { if (it.task == null) "repeat-board-${it.board.id}" else "repeat-task-${it.task.id}" }) { entry ->
                    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color = androidx.compose.ui.graphics.Color.White) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { if (entry.task == null) onEditBoard(entry.group) else edit(entry.task) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.title, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text((if (entry.task != null && entry.board.boardType != "DAILY") "${entry.board.summary} · " else "") + reminderRuleLabel(entry.rule, entry.customDays) + " · 下次 ${dateText(entry.nextAt, true)}", color = Quiet, fontSize = 11.sp)
                                if (entry.skipped) Text("下次提醒已跳过", color = Sky, fontSize = 11.sp)
                            }
                            Switch(checked = !entry.skipped, onCheckedChange = { repeatAction = entry })
                        }
                    }
                }
            }
            dailyView -> {
                if (dailyTasks.isEmpty()) item { SoftCard { Text(if (view == "today") "今天还没有事项" else "明天还没有事项"); Text("点击＋直接添加事项，清单中的提醒也会显示在这里。", color = Quiet) } }
                items(dailyTasks, key = { "daily-${it.second.id}" }) { (board, task) ->
                    val dark = task.important
                    val textColor = if (dark) androidx.compose.ui.graphics.Color.White else NoteInk
                    val secondary = if (dark) androidx.compose.ui.graphics.Color.White.copy(alpha = .8f) else Quiet
                    val remindAt = taskReminderOnDay(task, board, selectedDay)
                    val skipAt = if (isUnifiedBoard(board)) board.reminderSkipAt else task.reminderSkipAt
                    SoftCard(Modifier.clickable { edit(task) }, color = if (dark) Sky else androidx.compose.ui.graphics.Color.White) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(remindAt?.let { java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "--", color = textColor, fontSize = 20.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { model.task(task.copy(important = !task.important)) }) { Icon(if (task.important) Icons.Default.Star else Icons.Default.StarBorder, if (task.important) "取消重要" else "标记重要", tint = if (dark) androidx.compose.ui.graphics.Color.White else Sky) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(task.completed, { model.complete(task) }, colors = CheckboxDefaults.colors(checkedColor = if (dark) androidx.compose.ui.graphics.Color.White else Sky, checkmarkColor = if (dark) Sky else androidx.compose.ui.graphics.Color.White, uncheckedColor = secondary))
                            Text(task.text, color = textColor, textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None, modifier = Modifier.weight(1f))
                        }
                        if (board.boardType != "DAILY") Text("来自：${board.summary}", color = secondary, fontSize = 12.sp)
                        if (remindAt != null && skipAt == remindAt) Text("本次提醒已跳过", color = secondary, fontSize = 12.sp)
                    }
                }
            }
            else -> {
                val groups = active.filter { view != "lists" || it.board.boardType != "DAILY" }
                var shownCount = 0
                groups.forEach { group ->
                    val tasks = group.items.filter { matches(it, group.board) }.sortedWith(compareBy<TodoItem> { it.completed }.thenBy { it.position })
                    if (view !in setOf("lists", "history") && tasks.isEmpty()) return@forEach
                    val daily = group.board.boardType == "DAILY"
                    val chunks = if (daily) tasks.groupBy { it.plannedDay }.entries.sortedBy { it.key }.map { it.key to it.value } else listOf(null to tasks)
                    chunks.forEach chunkLoop@ { (day, chunk) ->
                        if (daily && chunk.isEmpty()) return@chunkLoop
                        shownCount++
                        item("b${group.board.id}-${day ?: "list"}") {
                            var openMenu by remember { mutableStateOf(false) }
                            val title = if (daily) when (day) { today -> "今天"; tomorrow -> "明天"; null -> "独立事项"; else -> dateText(day) } else group.board.summary
                            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                            SoftCard(Modifier.combinedClickable(onClick = {
                                if (selected.isNotEmpty() && !daily) selected = if (group.board.id in selected) selected - group.board.id else selected + group.board.id
                                else model.board(group.board.copy(expanded = !group.board.expanded))
                            }, onLongClick = { if (!daily) selected = selected + group.board.id }), color = if (group.board.id in selected) Lavender else androidx.compose.ui.graphics.Color.White) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val liveItems = if (daily) chunk else group.items.filter { it.deletedAt == null }
                                    Column(Modifier.weight(1f)) {
                                        Text(title, fontSize = 18.sp)
                                        Text("${liveItems.count { it.completed }}/${liveItems.size} 已完成" + if (!daily) " · ${if (isUnifiedBoard(group.board)) "统一时间" else "独立时间"}" else "", color = Quiet, fontSize = 12.sp)
                                        if (!daily && isUnifiedBoard(group.board)) group.board.dueDate?.let { Text("截止 ${dateText(it, true)}", color = Quiet, fontSize = 12.sp) }
                                    }
                                    if (group.board.pinned) Icon(Icons.Default.PushPin, "置顶", tint = Sky, modifier = Modifier.size(16.dp))
                                    if (!daily) Box { IconButton(onClick = { openMenu = true }) { Icon(Icons.Default.MoreHoriz, "清单操作") }; DropdownMenu(openMenu, { openMenu = false }) {
                                        DropdownMenuItem(text = { Text("编辑清单") }, onClick = { onEditBoard(group); openMenu = false })
                                        DropdownMenuItem(text = { Text(if (group.board.pinned) "取消置顶" else "置顶") }, onClick = { model.board(group.board.copy(pinned = !group.board.pinned)); openMenu = false })
                                        DropdownMenuItem(text = { Text(if (group.board.archived) "恢复" else "归档") }, onClick = { model.board(group.board.copy(archived = !group.board.archived)); openMenu = false })
                                        DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = { deletingBoards = setOf(group.board.id); openMenu = false })
                                    } }
                                }
                                val liveItems = if (daily) chunk else group.items.filter { it.deletedAt == null }
                                if (liveItems.isNotEmpty()) LinearProgressIndicator(progress = { liveItems.count { it.completed }.toFloat() / liveItems.size }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Sky, trackColor = Mint)
                                if (arranging && !daily) {
                                    fun move(direction: Int) {
                                        val i = groups.indexOf(group); val target = i + direction
                                        if (target in groups.indices && groups[target].board.pinned == group.board.pinned) { val ordered = groups.toMutableList().apply { add(target, removeAt(i)) }; model.reorderBoards(ordered.map { it.board.id }) }
                                    }
                                    val latestMove by rememberUpdatedState<(Int) -> Unit> { move(it) }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.DragHandle, "拖动排序", Modifier.size(40.dp).pointerInput(group.board.id) {
                                            var distance = 0f
                                            detectVerticalDragGestures(onVerticalDrag = { change, amount -> change.consume(); distance += amount; if (kotlin.math.abs(distance) > 55) { latestMove(if (distance > 0) 1 else -1); distance = 0f } }, onDragEnd = { distance = 0f })
                                        })
                                        TextButton(onClick = { move(-1) }) { Text("上移") }; TextButton(onClick = { move(1) }) { Text("下移") }
                                    }
                                }
                                if (!arranging && group.board.expanded) {
                                    chunk.forEach { task ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(task.completed, { model.complete(task) })
                                            Column(Modifier.weight(1f).clickable { edit(task) }) {
                                                Text(task.text, textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None)
                                                val details = listOf(effectiveTaskDeadline(task, group.board)?.let { "截止 ${dateText(it, true)}" }, if (!isUnifiedBoard(group.board)) (task.reminderBaseAt ?: task.reminderAt)?.let { "${reminderRuleLabel(task.reminderRule, task.reminderCustomDays)} ${dateText(it, true)}" } else null).filterNotNull().joinToString(" · ")
                                                if (details.isNotBlank()) Text(details, fontSize = 11.sp, color = Quiet)
                                            }
                                            IconButton(onClick = { model.task(task.copy(important = !task.important)) }) { Icon(if (task.important) Icons.Default.Star else Icons.Default.StarBorder, "重要", tint = Sky) }
                                        }
                                    }
                                    if (view != "history" && !daily) TextButton(onClick = { taskEdit = null; newTaskBoard = group.board.id }) { Text("＋ 添加事项") }
                                }
                            }
                        }
                    }
                }
                if (shownCount == 0) item { SoftCard { Text(if (view == "lists") "先创建一个小清单" else "这里还没有事项"); Text("今天、明天和重要事项会自动汇集。", color = Quiet) } }
            }
        }
    }
    newTaskBoard?.let { boardId ->
        val board = all.firstOrNull { it.board.id == boardId }?.board
        val editorDay = if (board?.boardType == "DAILY") taskEdit?.plannedDay ?: if (view == "tomorrow") tomorrow else today else null
        TaskDetails(taskEdit, boardId, editorDay, model, { newTaskBoard = null; taskEdit = null }, board = board)
    }
}

@Composable fun TaskDetails(existing: TodoItem?, boardId: Long, defaultDay: Long?, model: WorkspaceModel, onClose: () -> Unit, board: TodoBoard? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unified = board?.let(::isUnifiedBoard) == true
    val daily = defaultDay != null && (boardId == 0L || board?.boardType == "DAILY")
    val initialDue = existing?.dueAt ?: if (daily) defaultDay?.let { planDeadline(it) } else null
    val initialReminder = existing?.reminderBaseAt ?: existing?.reminderAt
    var text by remember { mutableStateOf(existing?.text.orEmpty()) }
    var due by remember { mutableStateOf(initialDue) }
    var reminder by remember { mutableStateOf(initialReminder) }
    var reminderEnabled by remember { mutableStateOf(initialReminder != null) }
    var rule by remember { mutableStateOf(existing?.reminderRule) }
    var customDays by remember { mutableStateOf((existing?.reminderCustomDays ?: 1).coerceAtLeast(1).toString()) }
    var important by remember { mutableStateOf(existing?.important ?: false) }
    var completed by remember { mutableStateOf(existing?.completed ?: false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun close() {
        if (saving) return
        keyboard?.hide(); focus.clearFocus()
        val dirty = text != existing?.text.orEmpty() || (!unified && (due != initialDue || reminder != initialReminder || reminderEnabled != (initialReminder != null) || rule != existing?.reminderRule || customDays != (existing?.reminderCustomDays ?: 1).coerceAtLeast(1).toString())) || important != (existing?.important ?: false) || completed != (existing?.completed ?: false)
        if (dirty) confirmDiscard = true else onClose()
    }
    fun pick(current: Long?, change: (Long?) -> Unit) {
        val date = Instant.ofEpochMilli(current ?: defaultDay ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault())
        fun pickTime(day: LocalDate) = TimePickerDialog(context, { _, h, min -> change(day.atTime(h, min).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()) }, date.hour, date.minute, true).show()
        if (daily) pickTime(Instant.ofEpochMilli(defaultDay!!).atZone(ZoneId.systemDefault()).toLocalDate())
        else DatePickerDialog(context, { _, y, m, d -> pickTime(LocalDate.of(y, m + 1, d)) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }
    SoftDialog(if (existing == null) "添加事项" else "事项详情", ::close, dismissOnBackPress = false) {
        val imeVisible = WindowInsets.isImeVisible
        androidx.activity.compose.BackHandler { if (imeVisible) { keyboard?.hide(); focus.clearFocus() } else close() }
        OutlinedTextField(text, { text = it }, label = { Text("事项内容") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(important, { important = it }); Text("重要事项") }
        if (existing != null) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(completed, { completed = it }); Text("已完成") }
        if (!unified) {
            if (daily) {
                InlineDayTime("截止时间", defaultDay!!, due) { due = it }
                Text("当天事项默认在当天结束时截止。", color = Quiet, fontSize = 12.sp)
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pick(due) { due = it } }, modifier = Modifier.weight(1f)) { Text(due?.let { "截止 ${dateText(it, true)}" } ?: "设置截止时间") }
                if (due != null) IconButton(onClick = { due = null }) { Icon(Icons.Default.Close, "移除截止时间") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(reminderEnabled, {
                    reminderEnabled = it
                    if (it && reminder == null) reminder = Instant.ofEpochMilli(defaultDay ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault()).toLocalDate().atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    if (!it) reminder = null
                }); Text("提醒我")
            }
            if (reminderEnabled) {
                if (daily) InlineDayTime("提醒时间", defaultDay!!, reminder) { reminder = it }
                else OutlinedButton(onClick = { pick(reminder) { reminder = it } }, modifier = Modifier.fillMaxWidth()) { Text(reminder?.let { "提醒 ${dateText(it, true)}" } ?: "设置提醒时间") }
                val rules = listOf("" to "单次", ReminderScheduler.RULE_WEEKDAYS to "周一至周五", ReminderScheduler.RULE_WEEKENDS to "周末", ReminderScheduler.RULE_DAILY to "每隔1天", ReminderScheduler.RULE_EVERY_3_DAYS to "每隔3天", ReminderScheduler.RULE_WEEKLY to "每隔1周", ReminderScheduler.RULE_SEMI_MONTHLY to "每隔半月", ReminderScheduler.RULE_MONTHLY to "每隔1月", ReminderScheduler.RULE_YEARLY to "每隔1年", ReminderScheduler.RULE_CUSTOM_DAYS to "自定义")
                ChoiceRow("提醒频次", rule.orEmpty(), rules) { rule = it.takeIf(String::isNotEmpty) }
                if (rule == ReminderScheduler.RULE_CUSTOM_DAYS) OutlinedTextField(customDays, { customDays = it.filter(Char::isDigit).take(3) }, label = { Text("每隔几天") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (rule != null) Text("重复提醒沿用基准时间的节奏，完成或超过截止时间后停止。", color = Quiet, fontSize = 12.sp)
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = { scope.launch {
            if (!unified && daily && due == null) { error = "请填写完整的截止时分"; return@launch }
            if (!unified && reminderEnabled && reminder == null) { error = "请填写完整的提醒时间"; return@launch }
            if (!unified && reminderEnabled && reminder != null && due != null && reminder!! > due!!) { error = "提醒时间不能晚于截止时间"; return@launch }
            if (reminderEnabled && rule == ReminderScheduler.RULE_CUSTOM_DAYS && (customDays.toIntOrNull() ?: 0) < 1) { error = "重复间隔至少为1天"; return@launch }
            saving = true
            runCatching {
                val at = if (!unified && reminderEnabled) reminder else null
                val nextRule = if (at != null) rule else null
                val nextCustomDays = if (nextRule == ReminderScheduler.RULE_CUSTOM_DAYS) customDays.toIntOrNull() ?: 1 else 0
                val reminderChanged = at != initialReminder || nextRule != existing?.reminderRule || nextCustomDays != (existing?.reminderCustomDays ?: 0)
                val item = (existing ?: TodoItem(boardId = boardId, text = "")).copy(text = text.trim(), important = important, completed = completed,
                    plannedDay = if (daily) existing?.plannedDay ?: defaultDay else existing?.plannedDay,
                    dueAt = if (unified) null else due, reminderAt = at, reminderBaseAt = at,
                    reminderRule = nextRule, reminderCustomDays = nextCustomDays,
                    reminderTriggered = if (reminderChanged) false else existing?.reminderTriggered ?: false,
                    reminderSkipAt = if (reminderChanged) null else existing?.reminderSkipAt, repeatDays = 0, repeatSpawned = false)
                model.saveTaskNow(item)
                if (at != null && android.os.Build.VERSION.SDK_INT >= 33) (context as? android.app.Activity)?.let { activity ->
                    if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4101)
                }
                onClose()
            }.onFailure { error = it.message ?: "保存失败，内容已保留，请重试" }
            saving = false
        } }, enabled = text.isNotBlank() && !saving, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "保存中…" else "保存") }
        OutlinedButton(onClick = ::close, enabled = !saving) { Text("取消") }
        if (existing != null) TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("移到回收站") }
    }
    if (confirmDelete && existing != null) ConfirmTrashDialog("将这条事项移到回收站", { confirmDelete = false }, { model.task(existing.copy(deletedAt = System.currentTimeMillis())); onClose() })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("放弃本次填写？") }, text = { Text("已填写的内容尚未保存。") }, confirmButton = { Button(onClick = { confirmDiscard = false; onClose() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("放弃") } }, dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } })
}
