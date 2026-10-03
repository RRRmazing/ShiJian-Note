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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.*
import com.shijiannote.app.data.*
import java.time.*
import kotlinx.coroutines.launch

@Composable fun ScheduleLibrary(model: WorkspaceModel, onEdit: (ScheduleEvent?) -> Unit, onSearch: () -> Unit, onExport: () -> Unit, onSelection: (Boolean) -> Unit) {
    val all by model.schedules.collectAsState()
    var history by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    LaunchedEffect(selected.isNotEmpty()) { onSelection(selected.isNotEmpty()) }
    DisposableEffect(Unit) { onDispose { onSelection(false) } }
    val shown = all.filter { it.deletedAt == null && it.archived == history && (it.title + it.note).contains(query, true) }.sortedBy { it.eventAt }
    androidx.activity.compose.BackHandler(history || selected.isNotEmpty()) { if (selected.isNotEmpty()) selected = emptySet() else history = false }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 10.dp, 18.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            PageTitle(if (history) "时间表历史" else "时间表", dateText(System.currentTimeMillis()), back = if (history) ({ history = false }) else null) {
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "搜索") }
                Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }; DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(if (history) "返回时间表" else "历史与归档") }, onClick = { history = !history; menu = false })
                    DropdownMenuItem(text = { Text("导出 ICS 日历") }, onClick = { onExport(); menu = false })
                } }
            }
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("搜索事务与备注") }, singleLine = true)
            if (selected.isNotEmpty()) Row {
                TextButton(onClick = { selected = shown.map { it.id }.toSet() }) { Text("全选") }
                TextButton(onClick = { shown.filter { it.id in selected }.forEach { model.schedule(it.copy(archived = !history)) }; selected = emptySet() }) { Text(if (history) "恢复" else "归档") }
                TextButton(onClick = { shown.filter { it.id in selected }.forEach { model.schedule(it.copy(deletedAt = System.currentTimeMillis())) }; selected = emptySet() }) { Text("删除") }
                TextButton(onClick = { selected = emptySet() }) { Text("取消") }
            }
        }
        if (shown.isEmpty()) item { SoftCard { Text(if (query.isNotBlank()) "没有匹配的事务" else if (history) "还没有历史事务" else "给接下来的时间留个位置"); Text("点击＋安排事务，也可以设置提醒。", color = Quiet) } }
        shown.groupBy { Instant.ofEpochMilli(it.eventAt).atZone(ZoneId.systemDefault()).toLocalDate() }.forEach { (day, events) ->
            item("day$day") { Text(dateText(dayMillis(day)), color = Sky, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            items(events, key = { it.id }) { event ->
                var open by remember { mutableStateOf(false) }
                @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                SoftCard(Modifier.combinedClickable(onClick = { if (selected.isNotEmpty()) selected = if (event.id in selected) selected - event.id else selected + event.id else open = !open }, onLongClick = { selected = selected + event.id })) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(highlightText(event.title, query), fontSize = 18.sp, modifier = Modifier.weight(1f)); if (selected.isNotEmpty()) Checkbox(event.id in selected, { selected = if (event.id in selected) selected - event.id else selected + event.id }) }
                    Text(dateText(event.eventAt, true) + if (!history && event.eventAt <= System.currentTimeMillis()) " · 已开始" else "", color = Quiet, fontSize = 12.sp)
                    if (event.reminderEnabled) Text(if (event.reminderTriggered) "已提醒" else "提醒：提前 ${event.reminderDays}天 ${event.reminderHours}时 ${event.reminderMinutes}分", color = Sky, fontSize = 12.sp)
                    if (open) {
                        if (event.note.isNotBlank()) Text(highlightText(event.note, query))
                        Row { TextButton(onClick = { onEdit(event) }) { Text("编辑") }; TextButton(onClick = { model.schedule(event.copy(archived = !history)) }) { Text(if (history) "恢复" else "归档") }; TextButton(onClick = { model.schedule(event.copy(deletedAt = System.currentTimeMillis())) }) { Text("删除", color = MaterialTheme.colorScheme.error) } }
                    }
                }
            }
        }
    }
}

@Composable fun TodoLibrary(model: WorkspaceModel, onEditBoard: (TodoBoardWithItems?) -> Unit, onSearch: () -> Unit, onExport: () -> Unit, onSelection: (Boolean) -> Unit) {
    val all by model.todos.collectAsState()
    var view by rememberSaveable { mutableStateOf("lists") }
    var completed by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var taskEdit by remember { mutableStateOf<TodoItem?>(null) }
    var newTaskBoard by remember { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    LaunchedEffect(selected.isNotEmpty()) { onSelection(selected.isNotEmpty()) }
    DisposableEffect(Unit) { onDispose { onSelection(false) } }
    var arranging by remember { mutableStateOf(false) }
    val active = all.filter { it.board.deletedAt == null && it.board.archived == (view == "history") }.sortedWith(compareByDescending<TodoBoardWithItems> { it.board.pinned }.thenBy { it.board.position })
    val today = dayMillis()
    val tomorrow = dayMillis(LocalDate.now().plusDays(1))
    fun matches(item: TodoItem, board: TodoBoard): Boolean {
        if (item.deletedAt != null) return false
        if (!(item.text + board.summary).contains(query, true)) return false
        if (view == "lists" || view == "history") return true
        if (item.completed) return false
        val deadline = item.dueAt ?: board.dueDate
        return when (view) {
            "today" -> item.plannedDay == today || deadline?.let { it in today until tomorrow } == true
            "tomorrow" -> item.plannedDay == tomorrow
            "overdue" -> deadline?.let { it < System.currentTimeMillis() } == true || item.plannedDay?.let { it < today } == true
            "important" -> item.important
            else -> true
        }
    }
    androidx.activity.compose.BackHandler(view != "lists" || selected.isNotEmpty() || arranging) { when { selected.isNotEmpty() -> selected = emptySet(); arranging -> arranging = false; else -> view = "lists" } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 10.dp, 18.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            PageTitle("待办", "一步一步，做好今天") {
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "搜索") }
                Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }; DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("历史与归档") }, onClick = { view = "history"; menu = false })
                    DropdownMenuItem(text = { Text("导出 CSV") }, onClick = { onExport(); menu = false })
                    DropdownMenuItem(text = { Text(if (arranging) "完成排序" else "整理清单顺序") }, onClick = { arranging = !arranging; view = "lists"; menu = false })
                } }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("lists" to "清单", "today" to "今天", "tomorrow" to "明天", "overdue" to "逾期", "important" to "重要").forEach { (key, label) -> FilterChip(view == key, { view = key; selected = emptySet() }, label = { Text(label) }) }
                if (view == "history") FilterChip(true, { view = "lists" }, label = { Text("历史") })
            }
            OutlinedTextField(query, { query = it }, placeholder = { Text("搜索事项、清单") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row { FilterChip(completed, { completed = !completed }, label = { Text("显示已完成") }); if (selected.isNotEmpty()) { TextButton(onClick = { active.filter { it.board.id in selected }.forEach { model.board(it.board.copy(deletedAt = System.currentTimeMillis())) }; selected = emptySet() }) { Text("删除所选") }; TextButton(onClick = { selected = emptySet() }) { Text("取消") } } }
        }
        if (active.isEmpty()) item { SoftCard { Text("先创建一个小清单"); Text("今天、明天和重要事项会从清单中自动汇集。", color = Quiet) } }
        active.forEach { group ->
            val tasks = group.items.filter { matches(it, group.board) }.sortedWith(compareBy<TodoItem> { it.completed }.thenBy { it.position })
            if (view !in setOf("lists", "history") && tasks.isEmpty()) return@forEach
            item("b${group.board.id}") {
                var openMenu by remember { mutableStateOf(false) }
                @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                SoftCard(Modifier.combinedClickable(onClick = { if (selected.isNotEmpty()) selected = if (group.board.id in selected) selected - group.board.id else selected + group.board.id else model.board(group.board.copy(expanded = !group.board.expanded)) }, onLongClick = { selected = selected + group.board.id }), color = if (group.board.id in selected) Lavender else androidx.compose.ui.graphics.Color.White) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val liveItems = group.items.filter { it.deletedAt == null }
                        Column(Modifier.weight(1f)) { Text(highlightText(group.board.summary, query), fontSize = 18.sp); Text("${liveItems.count { it.completed }}/${liveItems.size} 已完成" + (group.board.dueDate?.let { " · 截止 ${dateText(it)}" } ?: ""), color = Quiet, fontSize = 12.sp) }
                        if (group.board.pinned) Icon(Icons.Default.PushPin, "置顶", tint = Sky, modifier = Modifier.size(16.dp))
                        Box { IconButton(onClick = { openMenu = true }) { Icon(Icons.Default.MoreHoriz, "清单操作") }; DropdownMenu(openMenu, { openMenu = false }) {
                            DropdownMenuItem(text = { Text("编辑清单") }, onClick = { onEditBoard(group); openMenu = false })
                            DropdownMenuItem(text = { Text(if (group.board.pinned) "取消置顶" else "置顶") }, onClick = { model.board(group.board.copy(pinned = !group.board.pinned)); openMenu = false })
                            DropdownMenuItem(text = { Text(if (group.board.archived) "恢复" else "归档") }, onClick = { model.board(group.board.copy(archived = !group.board.archived)); openMenu = false })
                            DropdownMenuItem(text = { Text("删除") }, onClick = { model.board(group.board.copy(deletedAt = System.currentTimeMillis())); openMenu = false })
                        } }
                    }
                    val liveItems = group.items.filter { it.deletedAt == null }
                    if (liveItems.isNotEmpty()) LinearProgressIndicator(progress = { liveItems.count { it.completed }.toFloat() / liveItems.size }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Sky, trackColor = Mint)
                    if (arranging) {
                        fun move(direction: Int) { val i = active.indexOf(group); val target = i + direction; if (target in active.indices && active[target].board.pinned == group.board.pinned) { val ordered = active.toMutableList().apply { add(target, removeAt(i)) }; model.reorderBoards(ordered.map { it.board.id }) } }
                        val latestMove by rememberUpdatedState<(Int) -> Unit> { move(it) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DragHandle, "拖动排序", Modifier.size(40.dp).pointerInput(group.board.id) {
                                var distance = 0f
                                detectVerticalDragGestures(onVerticalDrag = { change, amount -> change.consume(); distance += amount; if (kotlin.math.abs(distance) > 55) { latestMove(if (distance > 0) 1 else -1); distance = 0f } }, onDragEnd = { distance = 0f })
                            })
                            TextButton(onClick = { move(-1) }) { Text("上移") }; TextButton(onClick = { move(1) }) { Text("下移") }
                        }
                    }
                    if ((!arranging && group.board.expanded) || view !in setOf("lists", "history")) {
                        tasks.filter { !it.completed || completed || view == "history" }.forEach { task ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(task.completed, { model.complete(task) })
                                Column(Modifier.weight(1f).clickable { taskEdit = task; newTaskBoard = task.boardId }) {
                                    Text(highlightText(task.text, query), textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None)
                                    val details = listOf(task.plannedDay?.let { "计划 ${dateText(it)}" }, task.dueAt?.let { "截止 ${dateText(it, true)}" }, task.reminderAt?.let { "提醒 ${dateText(it, true)}" }, task.repeatDays.takeIf { it > 0 }?.let { "每${it}天重复任务" }).filterNotNull().joinToString(" · ")
                                    if (details.isNotBlank()) Text(details, fontSize = 11.sp, color = Quiet)
                                }
                                IconButton(onClick = { model.task(task.copy(important = !task.important)) }) { Icon(if (task.important) Icons.Default.Star else Icons.Default.StarBorder, "重要", tint = Sky) }
                            }
                        }
                        if (view != "history") TextButton(onClick = { taskEdit = null; newTaskBoard = group.board.id }) { Text("＋ 添加事项") }
                    }
                }
            }
        }
    }
    newTaskBoard?.let { boardId -> TaskDetails(taskEdit, boardId, if (view == "today") today else if (view == "tomorrow") tomorrow else null, model, { newTaskBoard = null; taskEdit = null }) }
}

@Composable fun TaskDetails(existing: TodoItem?, boardId: Long, defaultDay: Long?, model: WorkspaceModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(existing?.text.orEmpty()) }
    var planned by remember { mutableStateOf(existing?.plannedDay ?: defaultDay) }
    var due by remember { mutableStateOf(existing?.dueAt) }
    var reminder by remember { mutableStateOf(existing?.reminderAt) }
    var repeat by remember { mutableStateOf(existing?.repeatDays?.takeIf { it > 0 }?.toString().orEmpty()) }
    fun pick(current: Long?, time: Boolean, change: (Long?) -> Unit) {
        val date = Instant.ofEpochMilli(current ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault())
        DatePickerDialog(context, { _, y, m, d ->
            val day = LocalDate.of(y, m + 1, d)
            if (time) TimePickerDialog(context, { _, h, min -> change(day.atTime(h, min).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()) }, date.hour, date.minute, true).show()
            else change(dayMillis(day))
        }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }
    SoftDialog(if (existing == null) "添加事项" else "事项详情", onClose) {
        OutlinedTextField(text, { text = it }, label = { Text("事项内容") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        listOf(Triple("计划日期", planned, 0), Triple("截止时间", due, 1), Triple("提醒时间", reminder, 2)).forEach { (name, value, type) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pick(value, type != 0) { when (type) { 0 -> planned = it; 1 -> due = it; else -> reminder = it } } }, modifier = Modifier.weight(1f)) { Text(value?.let { "$name：${dateText(it, type != 0)}" } ?: "添加$name") }
                if (value != null) IconButton(onClick = { when (type) { 0 -> planned = null; 1 -> due = null; else -> reminder = null } }) { Icon(Icons.Default.Close, "移除$name") }
            }
        }
        OutlinedTextField(repeat, { repeat = it.filter(Char::isDigit).take(4) }, label = { Text("完成后每几天生成下一项（留空不重复）") }, modifier = Modifier.fillMaxWidth())
        Text("计划决定在哪天查看；截止表示最晚完成时间；提醒只负责通知。", color = Quiet, fontSize = 12.sp)
        Button(onClick = { scope.launch {
            val item = (existing ?: TodoItem(boardId = boardId, text = "")).copy(text = text.trim(), plannedDay = planned, dueAt = due, reminderAt = reminder, reminderTriggered = false, repeatDays = repeat.toIntOrNull() ?: 0)
            if (existing == null) { val id = model.dao.insertTodoItems(listOf(item.copy(position = model.dao.nextTodoPosition(boardId)))).single(); ReminderScheduler.scheduleTodo(context, item.copy(id = id)) }
            else { ReminderScheduler.cancelTodo(context, item.id); ReminderScheduler.scheduleTodo(context, item); model.task(item) }
            onClose()
        } }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("完成") }
        if (existing != null) TextButton(onClick = { model.task(existing.copy(deletedAt = System.currentTimeMillis())); ReminderScheduler.cancelTodo(context, existing.id); onClose() }) { Text("移到回收站") }
    }
}
