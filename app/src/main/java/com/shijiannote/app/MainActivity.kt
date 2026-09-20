package com.shijiannote.app

import android.app.Application
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shijiannote.app.data.MemoryCategory
import com.shijiannote.app.data.MemoryEntry
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shijiannote.app.data.DiaryEntry
import com.shijiannote.app.data.TodoBoard
import com.shijiannote.app.data.MemoryCategoryWithEntries
import com.shijiannote.app.data.ScheduleEvent
import com.shijiannote.app.data.TodoBoardWithItems
import com.shijiannote.app.data.TodoItem
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Blue = Color(0xFF356AE6)
private val SoftBlue = Color(0xFFEAF0FF)
private val Ink = Color(0xFF1C1B20)
private val Muted = Color(0xFF706F78)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ShiJianNoteApp() }
    }
}

private enum class Tab(val label: String) { SCHEDULE("时间表"), TODO("待办"), DIARY("日记"), MEMORY("记忆"), SETTINGS("设置") }

@Composable
private fun ShiJianNoteApp() {
    val context = LocalContext.current
    val model: AppViewModel = viewModel(factory = AppViewModel.factory(context.applicationContext as Application))
    val schedules by model.schedule.collectAsState(initial = emptyList())
    val todoBoards by model.todoBoards.collectAsState(initial = emptyList())
    val diaries by model.diaries.collectAsState(initial = emptyList())
    val memories by model.memoryCategories.collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf(Tab.SCHEDULE) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    var scheduleToEdit by remember { mutableStateOf<ScheduleEvent?>(null) }
    var showTodoDialog by remember { mutableStateOf(false) }
    var todoToEdit by remember { mutableStateOf<TodoBoardWithItems?>(null) }
    var showDiaryDialog by remember { mutableStateOf<DiaryEntry?>(null) }
    var diaryDateToEdit by remember { mutableStateOf<Long?>(null) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var categoryToEdit by remember { mutableStateOf<MemoryCategory?>(null) }
    var memoryCategoryId by remember { mutableStateOf<Long?>(null) }
    var showMemoryEntryDialog by remember { mutableStateOf(false) }
    var memoryEntryToEdit by remember { mutableStateOf<MemoryEntry?>(null) }

    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Blue, secondary = Blue)) {
        Scaffold(
            containerColor = Color(0xFFFCFBFF),
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(selected = tab == item, onClick = { tab = item; memoryCategoryId = null }, icon = { Icon(tabIcon(item), contentDescription = item.label) }, label = { Text(item.label) })
                    }
                }
            },
            floatingActionButton = {
                when (tab) {
                    Tab.SCHEDULE -> FloatingActionButton(onClick = { scheduleToEdit = null; showScheduleDialog = true }, containerColor = Blue) { Icon(Icons.Default.Add, "新建时间任务", tint = Color.White) }
                    Tab.TODO -> FloatingActionButton(onClick = { todoToEdit = null; showTodoDialog = true }, containerColor = Blue) { Icon(Icons.Default.Add, "新建待办", tint = Color.White) }
                    Tab.DIARY -> FloatingActionButton(onClick = { val today = startOfToday(); diaryDateToEdit = today; showDiaryDialog = diaries.firstOrNull { it.day == today } }, containerColor = Blue) { Icon(Icons.Default.Add, "写日记", tint = Color.White) }
                    Tab.MEMORY -> FloatingActionButton(onClick = { if (memoryCategoryId == null) { categoryToEdit = null; showCategoryDialog = true } else { memoryEntryToEdit = null; showMemoryEntryDialog = true } }, containerColor = Blue) { Icon(Icons.Default.Add, "新建", tint = Color.White) }
                    Tab.SETTINGS -> { }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    Tab.SCHEDULE -> ScheduleScreen(schedules, onEdit = { scheduleToEdit = it; showScheduleDialog = true }, onDelete = { ReminderScheduler.cancel(context, it.id); model.deleteSchedule(it) })
                    Tab.TODO -> TodoScreen(todoBoards, model::toggleTodo, model::toggleTodoBoard, onEdit = { todoToEdit = it; showTodoDialog = true }, onDelete = model::deleteTodo)
                    Tab.DIARY -> DiaryScreen(diaries, onOpen = { day, entry -> diaryDateToEdit = day; showDiaryDialog = entry }, onDelete = model::deleteDiary)
                    Tab.MEMORY -> MemoryScreen(memories, memoryCategoryId, onBack = { memoryCategoryId = null }, onOpen = { memoryCategoryId = it }, onEditCategory = { categoryToEdit = it; showCategoryDialog = true }, onDeleteCategory = { if (memoryCategoryId == it.id) memoryCategoryId = null; model.deleteMemoryCategory(it) }, onEditEntry = { memoryEntryToEdit = it; showMemoryEntryDialog = true }, onDeleteEntry = model::deleteMemoryEntry)
                    Tab.SETTINGS -> SettingsScreen(onOpenNotificationSettings = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) })
                }
            }
        }
    }

    if (showScheduleDialog) ScheduleDialog(event = scheduleToEdit, onDismiss = { showScheduleDialog = false }, onSave = { event ->
        if (event.id == 0L) model.addSchedule(event) { ReminderScheduler.schedule(context, it) }
        else { ReminderScheduler.cancel(context, event.id); model.updateSchedule(event) { ReminderScheduler.schedule(context, it) } }
        showScheduleDialog = false
    })
    if (showTodoDialog) TodoDialog(board = todoToEdit, onDismiss = { showTodoDialog = false }, onSave = { summary, due, tasks ->
        val existing = todoToEdit
        if (existing == null) model.addTodo(summary, due, tasks) else model.updateTodo(existing.board.copy(summary = summary.trim(), dueDate = due), tasks)
        showTodoDialog = false
    })
    if (diaryDateToEdit != null) DiaryDialog(day = diaryDateToEdit!!, entry = showDiaryDialog, onDismiss = { diaryDateToEdit = null }, onSave = { summary, content -> model.saveDiary(DiaryEntry(id = showDiaryDialog?.id ?: 0, day = diaryDateToEdit!!, summary = summary, content = content)); diaryDateToEdit = null })
    if (showCategoryDialog) TextInputDialog(if (categoryToEdit == null) "新建记忆分类" else "更改记忆分类", "分类名称，例如：旅行", categoryToEdit?.name.orEmpty(), { showCategoryDialog = false }) { name ->
        val existing = categoryToEdit
        if (existing == null) model.addMemoryCategory(name) else model.updateMemoryCategory(existing.copy(name = name.trim()))
        showCategoryDialog = false
    }
    if (showMemoryEntryDialog && memoryCategoryId != null) MemoryEntryDialog(memoryEntryToEdit, { showMemoryEntryDialog = false }) { title, content ->
        val existing = memoryEntryToEdit
        if (existing == null) model.addMemoryEntry(memoryCategoryId!!, title, content) else model.updateMemoryEntry(existing.copy(title = title.trim(), content = content.trim()))
        showMemoryEntryDialog = false
    }
}

@Composable
private fun SettingsScreen(onOpenNotificationSettings: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("设置", fontSize = 30.sp, color = Ink)
            Text("管理应用偏好与提醒方式", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth().clickable { onOpenNotificationSettings() }
            ) {
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NotificationsNone, contentDescription = null, tint = Blue)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("通知", color = Ink, fontSize = 18.sp)
                        Text("前往系统设置，管理提醒权限和通知方式", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = "通知设置", tint = Muted)
                }
            }
        }
    }
}

@Composable
private fun ScheduleScreen(events: List<ScheduleEvent>, onEdit: (ScheduleEvent) -> Unit, onDelete: (ScheduleEvent) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("时间表", fontSize = 30.sp, color = Ink, modifier = Modifier.weight(1f)); Text("今天：${formatToday()}", color = Muted, fontSize = 12.sp) } }
        if (events.isEmpty()) item { EmptyHint("还没有时间任务", "点击右下角＋，添加需要准时提醒的事情") }
        items(events, key = { it.id }) { event -> SwipeActionRow(onEdit = { onEdit(event) }, onDelete = { onDelete(event) }) { ScheduleCard(event) } }
    }
}

@Composable
private fun ScheduleCard(event: ScheduleEvent) {
    var expanded by remember(event.id) { mutableStateOf(false) }
    val reminder = formatReminder(event)
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Blue, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) { Text(formatScheduleDate(event), color = Blue, fontSize = 13.sp); Text(event.title, color = Ink, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (reminder != null) { Spacer(Modifier.height(4.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.NotificationsNone, null, Modifier.size(15.dp), tint = Muted); Spacer(Modifier.width(5.dp)); Text(reminder, color = Muted, fontSize = 12.sp) } }
            if (expanded && event.note.isNotBlank()) { HorizontalDivider(Modifier.padding(vertical = 8.dp)); Text("事务详情", fontSize = 12.sp, color = Muted); Text(event.note, color = Ink, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp)) }
        }
    }
}
@Composable
private fun TodoScreen(boards: List<TodoBoardWithItems>, toggleItem: (TodoItem) -> Unit, toggleBoard: (TodoBoard) -> Unit, onEdit: (TodoBoardWithItems) -> Unit, onDelete: (TodoBoard) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("待办", fontSize = 30.sp, color = Ink); Text("记录事情和大概时间；准时提醒请使用时间表", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
        if (boards.isEmpty()) item { EmptyHint("还没有待办", "点击＋创建一个待办框，再在里面连续添加小事项") }
        items(boards, key = { it.board.id }) { board ->
            val done = board.items.count { it.completed }
            val overdue = board.board.dueDate?.let { it < startOfToday() && done < board.items.size } == true
            SwipeActionRow(onEdit = { onEdit(board) }, onDelete = { onDelete(board.board) }) {
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { toggleBoard(board.board) }, verticalAlignment = Alignment.CenterVertically) { Icon(if (board.board.expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Blue); Spacer(Modifier.width(6.dp)); Text(board.board.summary, Modifier.weight(1f), color = Ink, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("$done / ${board.items.size}", color = Muted) }
                        Spacer(Modifier.height(6.dp))
                        Text(when { overdue -> "已逾期 · 截止：${formatDateOnly(board.board.dueDate!!)}"; board.board.dueDate != null -> "截止：${formatDateOnly(board.board.dueDate)}"; else -> "未设置截止日期" }, color = if (overdue) Color(0xFFB04A35) else Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 28.dp))
                        if (board.board.expanded) { HorizontalDivider(Modifier.padding(vertical = 10.dp)); board.items.sortedBy { it.position }.forEach { item -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = item.completed, onCheckedChange = { toggleItem(item) }); Text(item.text, color = if (item.completed) Muted else Ink, textDecoration = if (item.completed) TextDecoration.LineThrough else null) } } }
                    }
                }
            }
        }
    }
}
@Composable
private fun DiaryScreen(entries: List<DiaryEntry>, onOpen: (Long, DiaryEntry?) -> Unit, onDelete: (DiaryEntry) -> Unit) {
    var showCalendar by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("日记", fontSize = 30.sp, color = Ink); Text("每天一篇，标题固定为日期", color = Muted, fontSize = 13.sp) }; IconButton(onClick = { showCalendar = !showCalendar }) { Icon(Icons.Default.CalendarMonth, "打开日历", tint = Blue) } } }
        if (showCalendar) item { DiaryCalendar(month, entries.map { it.day }.toSet(), onPrev = { month = month.minusMonths(1) }, onNext = { month = month.plusMonths(1) }, onSelect = { day -> onOpen(day, entries.firstOrNull { it.day == day }) }) }
        if (entries.isEmpty()) item { EmptyHint("今天想记下什么？", "点击＋写下今天的日记") }
        items(entries, key = { it.id }) { entry -> SwipeActionRow(onEdit = { onOpen(entry.day, entry) }, onDelete = { onDelete(entry) }) { Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().clickable { onOpen(entry.day, entry) }) { Column(Modifier.padding(16.dp)) { Text(formatDateOnly(entry.day), color = Blue, fontSize = 14.sp); Text(entry.summary, color = Ink, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); if (entry.content.isNotBlank()) Text(entry.content, color = Muted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp)) } } } }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiaryCalendar(month: YearMonth, markedDays: Set<Long>, onPrev: () -> Unit, onNext: () -> Unit, onSelect: (Long) -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev) { Icon(Icons.Default.ChevronLeft, "上个月") }
                Text("${month.year}年${month.monthValue}月", color = Ink, fontSize = 17.sp)
                IconButton(onClick = onNext) { Icon(Icons.Default.ChevronRight, "下个月") }
            }
            FlowRow(maxItemsInEachRow = 7, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                listOf("日", "一", "二", "三", "四", "五", "六").forEach { Text(it, color = Muted, modifier = Modifier.width(38.dp).padding(bottom = 6.dp)) }
                val offset = month.atDay(1).dayOfWeek.value % 7
                repeat(offset) { Spacer(Modifier.width(38.dp).height(38.dp)) }
                (1..month.lengthOfMonth()).forEach { number ->
                    val day = month.atDay(number)
                    val hasDiary = localDateToMillis(day) in markedDays
                    Box(Modifier.size(38.dp).padding(2.dp).clip(CircleShape).background(if (hasDiary) Blue else Color.Transparent).clickable { onSelect(localDateToMillis(day)) }, contentAlignment = Alignment.Center) {
                        Text(number.toString(), color = if (hasDiary) Color.White else Ink, fontSize = 13.sp)
                    }
                }
            }
            Text("蓝色日期表示已有日记", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun MemoryScreen(categories: List<MemoryCategoryWithEntries>, selectedId: Long?, onBack: () -> Unit, onOpen: (Long) -> Unit, onEditCategory: (MemoryCategory) -> Unit, onDeleteCategory: (MemoryCategory) -> Unit, onEditEntry: (MemoryEntry) -> Unit, onDeleteEntry: (MemoryEntry) -> Unit) {
    val selected = categories.firstOrNull { it.category.id == selectedId }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { if (selected == null) { Text("记忆", fontSize = 30.sp, color = Ink); Text("把值得留下的事情收进分类", color = Muted, fontSize = 13.sp) } else Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ChevronLeft, "返回") }; Text(selected.category.name, fontSize = 28.sp, color = Ink) } }
        if (selected == null) {
            if (categories.isEmpty()) item { EmptyHint("还没有记忆分类", "点击＋，创建如「旅行」「学习」这样的分类") }
            items(categories, key = { it.category.id }) { category -> SwipeActionRow(onEdit = { onEditCategory(category.category) }, onDelete = { onDeleteCategory(category.category) }) { Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().clickable { onOpen(category.category.id) }) { Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Folder, null, tint = Blue); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(category.category.name, color = Ink, fontSize = 18.sp); Text("${category.entries.size} 条记忆", color = Muted, fontSize = 13.sp) }; Icon(Icons.Default.ChevronRight, null, tint = Muted) } } } }
        } else {
            if (selected.entries.isEmpty()) item { EmptyHint("这个分类还没有内容", "点击＋，记录一件具体的记忆") }
            items(selected.entries, key = { it.id }) { entry -> SwipeActionRow(onEdit = { onEditEntry(entry) }, onDelete = { onDeleteEntry(entry) }) { Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(entry.title, color = Ink, fontSize = 18.sp); Text(entry.content, color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp)); Text(formatDateOnly(entry.createdAt), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) } } } }
        }
    }
}
@Composable
private fun ScheduleDialog(event: ScheduleEvent?, onDismiss: () -> Unit, onSave: (ScheduleEvent) -> Unit) {
    val context = LocalContext.current
    val calendar = remember(event?.id) { Calendar.getInstance().apply { timeInMillis = event?.eventAt ?: Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis } }
    var title by remember(event?.id) { mutableStateOf(event?.title.orEmpty()) }
    var note by remember(event?.id) { mutableStateOf(event?.note.orEmpty()) }
    var dateTime by remember(event?.id) { mutableStateOf(calendar.timeInMillis) }
    var hasExactTime by remember(event?.id) { mutableStateOf(event?.let { Calendar.getInstance().apply { timeInMillis = it.eventAt }.let { c -> c.get(Calendar.HOUR_OF_DAY) != 0 || c.get(Calendar.MINUTE) != 0 } } ?: false) }
    var days by remember(event?.id) { mutableStateOf(event?.reminderDays?.takeIf { it > 0 }?.toString().orEmpty()) }
    var hours by remember(event?.id) { mutableStateOf(event?.reminderHours?.takeIf { it > 0 }?.toString().orEmpty()) }
    var minutes by remember(event?.id) { mutableStateOf(event?.reminderMinutes?.takeIf { it > 0 }?.toString().orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (event == null) "新建时间任务" else "更改时间任务") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(title, { title = it }, label = { Text("事务名称") }, singleLine = true)
        OutlinedButton(onClick = { val c = Calendar.getInstance().apply { timeInMillis = dateTime }; DatePickerDialog(context, { _, y, m, d -> c.set(y, m, d); dateTime = c.timeInMillis }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show() }) { Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(6.dp)); Text(formatDateOnly(dateTime)) }
        OutlinedButton(onClick = { val c = Calendar.getInstance().apply { timeInMillis = dateTime }; TimePickerDialog(context, { _, h, m -> c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, m); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); dateTime = c.timeInMillis; hasExactTime = true }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show() }) { Text(if (hasExactTime) "精确时间：${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(dateTime))}" else "精确时间（可选）：00:00") }
        Text("提前提醒（全部为 0 表示不提醒）", color = Muted, fontSize = 13.sp); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { NumberField("天", days, 999) { days = it }; NumberField("时", hours, 23) { hours = it }; NumberField("分", minutes, 59) { minutes = it } }; OutlinedTextField(note, { note = it }, label = { Text("事务详情（可选）") }, minLines = 2)
    } }, confirmButton = { Button(onClick = { if (title.isNotBlank()) onSave(ScheduleEvent(id = event?.id ?: 0, title = title.trim(), eventAt = dateTime, reminderDays = days.toIntOrNull() ?: 0, reminderHours = hours.toIntOrNull() ?: 0, reminderMinutes = minutes.toIntOrNull() ?: 0, note = note.trim())) }) { Text(if (event == null) "创建" else "保存") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } })
}
@Composable
private fun NumberField(unit: String, value: String, maximum: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit)
            val number = digits.toIntOrNull()
            onChange(when {
                digits.isEmpty() -> ""
                number == null -> ""
                number > maximum -> maximum.toString()
                else -> digits
            })
        },
        label = { Text(unit) },
        placeholder = { Text("0") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(82.dp),
        singleLine = true
    )
}
@Composable
private fun TodoDialog(board: TodoBoardWithItems?, onDismiss: () -> Unit, onSave: (String, Long?, List<String>) -> Unit) {
    val context = LocalContext.current
    var summary by remember(board?.board?.id) { mutableStateOf(board?.board?.summary.orEmpty()) }
    var dueDate by remember(board?.board?.id) { mutableStateOf(board?.board?.dueDate) }
    val tasks = remember(board?.board?.id) { mutableStateListOf(*(board?.items?.sortedBy { it.position }?.map { it.text }?.toTypedArray() ?: arrayOf(""))) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (board == null) "新建待办" else "更改待办") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(summary, { summary = it }, label = { Text("摘要") }, placeholder = { Text("例如：本周复习计划") }, singleLine = true); Text("具体任务", color = Muted, fontSize = 13.sp)
        tasks.forEachIndexed { index, task -> OutlinedTextField(task, { tasks[index] = it }, label = { Text(if (index == 0) "输入第一项任务" else "继续添加任务") }, leadingIcon = { Checkbox(false, {}) }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Next), keyboardActions = KeyboardActions(onNext = { if (tasks.lastOrNull()?.isNotBlank() == true) tasks.add("") })) }
        OutlinedButton(onClick = { tasks.add("") }) { Icon(Icons.Default.Add, null); Text(" 添加一项") }
        if (dueDate == null) OutlinedButton(onClick = { val c = Calendar.getInstance(); DatePickerDialog(context, { _, y, m, d -> c.set(y, m, d, 0, 0, 0); c.set(Calendar.MILLISECOND, 0); dueDate = c.timeInMillis }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show() }) { Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(6.dp)); Text("设置截止日期（可选）") } else Row(verticalAlignment = Alignment.CenterVertically) { AssistChip(onClick = {}, label = { Text("截止：${formatDateOnly(dueDate!!)}") }); IconButton(onClick = { dueDate = null }) { Icon(Icons.Default.Close, "清除截止日期") } }
    } }, confirmButton = { Button(onClick = { if (summary.isNotBlank() && tasks.any { it.isNotBlank() }) onSave(summary, dueDate, tasks.toList()) }) { Text(if (board == null) "完成" else "保存") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } })
}
@Composable
private fun DiaryDialog(day: Long, entry: DiaryEntry?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var summary by remember(entry?.id) { mutableStateOf(entry?.summary ?: "") }
    var content by remember(entry?.id) { mutableStateOf(entry?.content ?: "") }
    AlertDialog(onDismissRequest = onDismiss, title = { Column { Text(formatDateOnly(day)); Text("日记", fontSize = 14.sp, color = Muted) } }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(summary, { summary = it }, label = { Text("摘要") }, singleLine = true); OutlinedTextField(content, { content = it }, label = { Text("正文") }, minLines = 6) } }, confirmButton = { Button(onClick = { if (summary.isNotBlank()) onSave(summary.trim(), content.trim()) }) { Text("保存") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun TextInputDialog(title: String, hint: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(text, { text = it }, label = { Text(hint) }, singleLine = true) }, confirmButton = { Button(onClick = { if (text.isNotBlank()) onSave(text) }) { Text("保存") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } })
}
@Composable
private fun MemoryEntryDialog(entry: MemoryEntry?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember(entry?.id) { mutableStateOf(entry?.title.orEmpty()) }; var content by remember(entry?.id) { mutableStateOf(entry?.content.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (entry == null) "记录一段记忆" else "更改记忆") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true); OutlinedTextField(content, { content = it }, label = { Text("内容") }, minLines = 5) } }, confirmButton = { Button(onClick = { if (title.isNotBlank()) onSave(title, content) }) { Text("保存") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun SwipeActionRow(onEdit: () -> Unit, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val actionWidth = 176.dp
    val actionWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { actionWidth.toPx() }
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
        Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.width(88.dp).fillMaxHeight().background(Blue).clickable { scope.launch { offset.animateTo(0f) }; onEdit() }, contentAlignment = Alignment.Center) { Text("更改", color = Color.White, fontSize = 15.sp) }
            Box(Modifier.width(88.dp).fillMaxHeight().background(Color(0xFFE14B4B)).clickable { onDelete() }, contentAlignment = Alignment.Center) { Text("删除", color = Color.White, fontSize = 15.sp) }
        }
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }.pointerInput(actionWidthPx) { detectHorizontalDragGestures(onHorizontalDrag = { change, dragAmount -> change.consume(); scope.launch { offset.snapTo((offset.value + dragAmount).coerceIn(-actionWidthPx, 0f)) } }, onDragEnd = { scope.launch { offset.animateTo(if (offset.value < -actionWidthPx / 2) -actionWidthPx else 0f) } }, onDragCancel = { scope.launch { offset.animateTo(0f) } }) }) { content() }
    }
}
@Composable
private fun EmptyHint(title: String, text: String) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.AutoMirrored.Filled.Notes, null, tint = Blue); Text(title, color = Ink, fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp)); Text(text, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) } }
}

private fun tabIcon(tab: Tab) = when (tab) { Tab.SCHEDULE -> Icons.AutoMirrored.Filled.EventNote; Tab.TODO -> Icons.Default.CheckCircle; Tab.DIARY -> Icons.AutoMirrored.Filled.Article; Tab.MEMORY -> Icons.Default.Folder; Tab.SETTINGS -> Icons.Default.Settings }
private fun startOfToday(): Long = localDateToMillis(LocalDate.now())
private fun localDateToMillis(date: LocalDate): Long = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
private fun formatToday(): String = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).format(Date())
private fun formatDateOnly(time: Long): String = SimpleDateFormat(if (Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)) "M月d日 EEEE" else "yyyy年M月d日 EEEE", Locale.CHINA).format(Date(time))
private fun formatScheduleDate(event: ScheduleEvent): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = event.eventAt }
    val hasExactTime = calendar.get(Calendar.HOUR_OF_DAY) != 0 || calendar.get(Calendar.MINUTE) != 0
    return if (hasExactTime) "${formatDateOnly(event.eventAt)}  ${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(event.eventAt))}" else formatDateOnly(event.eventAt)
}
private fun formatReminder(event: ScheduleEvent): String? {
    val parts = listOf(event.reminderDays.takeIf { it > 0 }?.let { "${it}天" }, event.reminderHours.takeIf { it > 0 }?.let { "${it}小时" }, event.reminderMinutes.takeIf { it > 0 }?.let { "${it}分钟" }).filterNotNull()
    return parts.takeIf { it.isNotEmpty() }?.let { "提前 ${it.joinToString(" ")}提醒" }
}
