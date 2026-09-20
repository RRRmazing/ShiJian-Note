package com.shijiannote.app

import android.app.Application
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.app.TimePickerDialog
import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
import java.text.ParsePosition
import org.json.JSONObject
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
private val LightBlue = Color(0xFFDCE8FF)
private val LightYellow = Color(0xFFFFF1B8)
private val LightRed = Color(0xFFFFDAD6)
private val DeleteInk = Color(0xFF9D2922)
private val Ink = Color(0xFF1C1B20)
private val Muted = Color(0xFF706F78)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ShiJianNoteApp() }
    }
}

private enum class ImportDestination { SCHEDULE, TODO }

private data class ImportedTransaction(
    val sourceLine: Int,
    val title: String,
    val startAt: Long?,
    val endText: String,
    val note: String,
    val reminderDays: Int,
    val reminderHours: Int,
    val reminderMinutes: Int,
    val problem: String? = null
)

private data class ImportParseResult(
    val entries: List<ImportedTransaction>,
    val ignoredLines: Int
)
private data class ImportDraft(
    val sourceLine: Int,
    val title: String,
    val timeText: String,
    val startAt: Long?,
    val endAt: Long?,
    val note: String,
    val reminderDays: Int,
    val reminderHours: Int,
    val reminderMinutes: Int,
    val problem: String?
)
private enum class Tab(val label: String) { SCHEDULE("时间表"), TODO("待办"), DIARY("日记"), MEMORY("记忆"), SETTINGS("设置") }

@Composable
private fun ShiJianNoteApp() {
    val context = LocalContext.current
    val model: AppViewModel = viewModel(factory = AppViewModel.factory(context.applicationContext as Application))
    val schedules by model.schedule.collectAsState(initial = emptyList())
    val archivedSchedules by model.archivedSchedule.collectAsState(initial = emptyList())
    val todoBoards by model.todoBoards.collectAsState(initial = emptyList())
    val archivedTodoBoards by model.archivedTodoBoards.collectAsState(initial = emptyList())
    val diaries by model.diaries.collectAsState(initial = emptyList())
    val memories by model.memoryCategories.collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf(Tab.SCHEDULE) }
    var selectionActive by remember { mutableStateOf(false) }
    var cancelSelectionRequest by remember { mutableIntStateOf(0) }
    var showScheduleHistory by remember { mutableStateOf(false) }
    var showTodoHistory by remember { mutableStateOf(false) }
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

    var advancedFeaturesOpen by remember { mutableStateOf(false) }
    var importDestination by remember { mutableStateOf<ImportDestination?>(null) }
    var pendingTodoImport by remember { mutableStateOf(false) }
    var todoImportTitle by remember { mutableStateOf("自主导入") }
    var todoImportDueDate by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            model.archiveExpiredItems(startOfToday(), System.currentTimeMillis())
            delay(60_000)
        }
    }

    BackHandler(enabled = !selectionActive && tab == Tab.SCHEDULE && showScheduleHistory) { showScheduleHistory = false }
    BackHandler(enabled = !selectionActive && tab == Tab.TODO && showTodoHistory) { showTodoHistory = false }
    BackHandler(enabled = !selectionActive && tab == Tab.MEMORY && memoryCategoryId != null) { memoryCategoryId = null }
    BackHandler(enabled = selectionActive) { cancelSelectionRequest++ }
    BackHandler(enabled = importDestination != null) { importDestination = null }
    BackHandler(enabled = importDestination == null && advancedFeaturesOpen) { advancedFeaturesOpen = false }

    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Blue, secondary = Blue)) {
        Scaffold(
            containerColor = Color(0xFFFCFBFF),
            bottomBar = {
                if (!advancedFeaturesOpen && importDestination == null) {
                NavigationBar(containerColor = Color.White) {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(selected = tab == item, onClick = { tab = item; memoryCategoryId = null; selectionActive = false }, icon = { Icon(tabIcon(item), contentDescription = item.label) }, label = { Text(item.label) })
                    }
                }
                }
            },
            floatingActionButton = {
                when (tab) {
                    Tab.SCHEDULE -> if (!showScheduleHistory && !selectionActive) FloatingActionButton(onClick = { scheduleToEdit = null; showScheduleDialog = true }, containerColor = Blue) { Icon(Icons.Default.Add, "新建时间任务", tint = Color.White) }
                    Tab.TODO -> if (!showTodoHistory && !selectionActive) FloatingActionButton(onClick = { todoToEdit = null; showTodoDialog = true }, containerColor = Blue) { Icon(Icons.Default.Add, "新建待办", tint = Color.White) }
                    Tab.DIARY -> if (!selectionActive) FloatingActionButton(onClick = { val today = startOfToday(); diaryDateToEdit = today; showDiaryDialog = diaries.firstOrNull { it.day == today } }, containerColor = Blue) { Icon(Icons.Default.Add, "写日记", tint = Color.White) }
                    Tab.MEMORY -> if (!selectionActive) FloatingActionButton(onClick = { if (memoryCategoryId == null) { categoryToEdit = null; showCategoryDialog = true } else { memoryEntryToEdit = null; showMemoryEntryDialog = true } }, containerColor = Blue) { Icon(Icons.Default.Add, "新建", tint = Color.White) }
                    Tab.SETTINGS -> { }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    Tab.SCHEDULE -> if (showScheduleHistory) ScheduleHistoryScreen(archivedSchedules, onBack = { showScheduleHistory = false }, onDelete = { events -> events.forEach { ReminderScheduler.cancel(context, it.id) }; model.deleteSchedules(events) }, onSelectionChanged = { selectionActive = it }, cancelSelectionRequest = cancelSelectionRequest) else ScheduleScreen(schedules, onEdit = { scheduleToEdit = it; showScheduleDialog = true }, onArchive = { events -> events.forEach { ReminderScheduler.cancel(context, it.id) }; model.archiveSchedules(events) }, onDelete = { events -> events.forEach { ReminderScheduler.cancel(context, it.id) }; model.deleteSchedules(events) }, onShowHistory = { selectionActive = false; showScheduleHistory = true }, onSelectionChanged = { selectionActive = it }, cancelSelectionRequest = cancelSelectionRequest)
                    Tab.TODO -> if (showTodoHistory) TodoHistoryScreen(archivedTodoBoards, onBack = { showTodoHistory = false }, onDelete = { boards -> model.deleteTodos(boards.map { it.board }) }, onSelectionChanged = { selectionActive = it }, cancelSelectionRequest = cancelSelectionRequest) else TodoScreen(todoBoards, model::toggleTodo, model::toggleTodoBoard, onEdit = { todoToEdit = it; showTodoDialog = true }, onArchive = { boards -> model.archiveTodos(boards.map { it.board }) }, onDelete = { boards -> model.deleteTodos(boards.map { it.board }) }, onShowHistory = { selectionActive = false; showTodoHistory = true }, onSelectionChanged = { selectionActive = it }, cancelSelectionRequest = cancelSelectionRequest)
                    Tab.DIARY -> DiaryScreen(diaries, onOpen = { day, entry -> diaryDateToEdit = day; showDiaryDialog = entry }, onDelete = model::deleteDiary, onSelectionChanged = { selectionActive = it }, cancelSelectionRequest = cancelSelectionRequest)
                    Tab.MEMORY -> MemoryScreen(memories, memoryCategoryId, onBack = { memoryCategoryId = null }, onOpen = { memoryCategoryId = it }, onEditCategory = { categoryToEdit = it; showCategoryDialog = true }, onDeleteCategory = { if (memoryCategoryId == it.id) memoryCategoryId = null; model.deleteMemoryCategory(it) }, onEditEntry = { memoryEntryToEdit = it; showMemoryEntryDialog = true }, onDeleteEntry = model::deleteMemoryEntry, onSelectionChanged = { selectionActive = it }, cancelSelectionRequest = cancelSelectionRequest)
                    Tab.SETTINGS -> when {
                        importDestination != null -> ImportTransactionsScreen(
                            destination = importDestination!!,
                            todoTitle = todoImportTitle,
                            todoDueDate = todoImportDueDate,
                            onBack = { importDestination = null },
                            onImportSchedules = { events -> model.importSchedules(events) { ReminderScheduler.schedule(context, it) } },
                            onImportTodo = { tasks -> model.addTodo(todoImportTitle, todoImportDueDate, tasks) },
                            onFinish = { destination ->
                                importDestination = null
                                advancedFeaturesOpen = false
                                tab = if (destination == ImportDestination.SCHEDULE) Tab.SCHEDULE else Tab.TODO
                            }
                        )
                        advancedFeaturesOpen -> AdvancedFeaturesScreen(
                            onBack = { advancedFeaturesOpen = false },
                            onOpenImport = { destination ->
                                if (destination == ImportDestination.TODO) pendingTodoImport = true
                                else importDestination = ImportDestination.SCHEDULE
                            }
                        )
                        else -> SettingsScreen(
                            onOpenNotificationSettings = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) },
                            onOpenAdvancedFeatures = { advancedFeaturesOpen = true }
                        )
                    }
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
    if (pendingTodoImport) TodoImportSetupDialog(
        initialTitle = todoImportTitle,
        initialDueDate = todoImportDueDate,
        onDismiss = { pendingTodoImport = false },
        onContinue = { title, dueDate ->
            todoImportTitle = title
            todoImportDueDate = dueDate
            pendingTodoImport = false
            importDestination = ImportDestination.TODO
        }
    )
}

@Composable
private fun SettingsScreen(onOpenNotificationSettings: () -> Unit, onOpenAdvancedFeatures: () -> Unit) {
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
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().clickable { onOpenAdvancedFeatures() }) {
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = Blue)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("高级功能", color = Ink, fontSize = 18.sp)
                        Text("导入整理后的事务文本", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = "高级功能", tint = Muted)
                }
            }
        }
    }
}

@Composable
private fun AdvancedFeaturesScreen(onBack: () -> Unit, onOpenImport: (ImportDestination) -> Unit) {
    var chooseDestination by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ChevronLeft, "返回") }; Column { Text("高级功能", fontSize = 28.sp, color = Ink); Text("批量整理和导入事务", color = Muted, fontSize = 13.sp) } } }
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().clickable { chooseDestination = true }) {
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.Notes, null, tint = Blue)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text("文字导入事务", color = Ink, fontSize = 18.sp); Text("将 AI 整理后的文本导入时间表或待办", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp)) }
                    Icon(Icons.Default.ChevronRight, "文字导入事务", tint = Muted)
                }
            }
        }
        item { Text("提示：图片、PDF 等资料请先交由支持相应识别能力的 AI 整理。导入前请检查 AI 是否说明了无法读取或无法确认的内容。", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) }
    }
    if (chooseDestination) AlertDialog(
        onDismissRequest = { chooseDestination = false },
        title = { Text("文字导入事务") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("请选择要创建的事务类型")
            Button(onClick = { chooseDestination = false; onOpenImport(ImportDestination.SCHEDULE) }, modifier = Modifier.fillMaxWidth()) { Text("导入到时间表") }
            OutlinedButton(onClick = { chooseDestination = false; onOpenImport(ImportDestination.TODO) }, modifier = Modifier.fillMaxWidth()) { Text("导入待办") }
        } },
        confirmButton = {},
        dismissButton = { OutlinedButton(onClick = { chooseDestination = false }) { Text("取消") } }
    )
}

@Composable
private fun TodoImportSetupDialog(initialTitle: String, initialDueDate: Long?, onDismiss: () -> Unit, onContinue: (String, Long?) -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initialTitle) }
    var dueDate by remember { mutableStateOf(initialDueDate) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("待办导入设置") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("所有本次导入的事务会放在同一个待办标题下。", color = Muted, fontSize = 13.sp)
            OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true)
            if (dueDate == null) OutlinedButton(onClick = {
                val calendar = Calendar.getInstance()
                DatePickerDialog(context, { _, y, m, d -> calendar.set(y, m, d, 0, 0, 0); calendar.set(Calendar.MILLISECOND, 0); dueDate = calendar.timeInMillis }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
            }) { Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(6.dp)); Text("设置截止日期（可选）") }
            else Row(verticalAlignment = Alignment.CenterVertically) { AssistChip(onClick = {}, label = { Text("截止：${formatDateOnly(dueDate!!)}") }); IconButton(onClick = { dueDate = null }) { Icon(Icons.Default.Close, "清除截止日期") } }
        } },
        confirmButton = { Button(onClick = { if (title.isNotBlank()) onContinue(title.trim(), dueDate) }) { Text("继续") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ImportTransactionsScreen(
    destination: ImportDestination,
    todoTitle: String,
    todoDueDate: Long?,
    onBack: () -> Unit,
    onImportSchedules: (List<ScheduleEvent>) -> Unit,
    onImportTodo: (List<String>) -> Unit,
    onFinish: (ImportDestination) -> Unit
) {
    val context = LocalContext.current
    var rawText by remember(destination) { mutableStateOf("") }
    var completedCount by remember { mutableIntStateOf(0) }
    val parsed = remember(rawText, destination) { parseImportedTransactions(rawText, destination) }
    val drafts = remember(rawText, destination) {
        mutableStateListOf(*parsed.entries.map { entry ->
            ImportDraft(entry.sourceLine, entry.title, entry.startAt?.let(::formatImportedTime).orEmpty(), entry.startAt, entry.endText.takeIf { it.isNotBlank() }?.let(::parseImportedDate), entry.note, entry.reminderDays, entry.reminderHours, entry.reminderMinutes, entry.problem)
        }.toTypedArray())
    }
    fun usable(draft: ImportDraft): Boolean = draft.problem == null && draft.title.isNotBlank() && (destination == ImportDestination.TODO || parseImportedDate(draft.timeText) != null)
    var selectedLines by remember(rawText, destination) { mutableStateOf(drafts.filter(::usable).map { it.sourceLine }.toSet()) }
    val selected = drafts.filter { it.sourceLine in selectedLines && usable(it) }
    val prompt = importPrompt(destination)
    if (completedCount > 0) { ImportResultScreen(destination, completedCount, onFinish); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ChevronLeft, "返回") }; Column { Text(if (destination == ImportDestination.SCHEDULE) "导入到时间表" else "导入待办", fontSize = 27.sp, color = Ink); Text(if (destination == ImportDestination.SCHEDULE) "展开条目可修改标题、时间和备注" else "标题：$todoTitle${todoDueDate?.let { " · 截止 ${formatDateOnly(it)}" }.orEmpty()}", color = Muted, fontSize = 13.sp) } } }
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(15.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("第一步：复制提示词", fontSize = 17.sp, color = Ink, modifier = Modifier.weight(1f)); IconButton(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("事务导入提示词", prompt)) }) { Icon(Icons.Default.ContentCopy, "复制提示词", tint = Blue) } }
                    Text("把资料和提示词交给 AI。若资料有图片、PDF 或无法读取部分，AI 必须明确指出，不能猜测。", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
                    Text(prompt, color = Ink, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp).height(150.dp).horizontalScroll(rememberScrollState()))
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(15.dp)) {
                    Text("第二步：粘贴 AI 的结果", fontSize = 17.sp, color = Ink)
                    Text("编辑区按一行一条事务设计，可纵向滚动、横向滑动；前后夹带的解释文字会自动忽略。", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
                    OutlinedTextField(rawText, { rawText = it }, modifier = Modifier.fillMaxWidth().height(230.dp).padding(top = 10.dp).horizontalScroll(rememberScrollState()), label = { Text("每行一个 JSON 事务") }, placeholder = { Text("粘贴 AI 回答…") }, textStyle = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 13.sp), minLines = 9, maxLines = 12)
                    Text("发现 ${drafts.size} 条 · 可导入 ${drafts.count(::usable)} 条 · 已忽略 ${parsed.ignoredLines} 行", color = if (drafts.none(::usable) && rawText.isNotBlank()) DeleteInk else Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (drafts.isNotEmpty()) item { Text("导入预览（点击条目编辑）", fontSize = 18.sp, color = Ink, modifier = Modifier.padding(top = 2.dp)) }
        items(drafts, key = { it.sourceLine }) { draft ->
            EditableImportDraftCard(
                draft = draft,
                destination = destination,
                checked = draft.sourceLine in selectedLines,
                usable = usable(draft),
                onCheckedChange = { checked -> selectedLines = if (checked) selectedLines + draft.sourceLine else selectedLines - draft.sourceLine },
                onChange = { changed -> drafts[drafts.indexOfFirst { it.sourceLine == changed.sourceLine }] = changed }
            )
        }
        if (rawText.isNotBlank() && drafts.none(::usable)) item { Text(if (destination == ImportDestination.SCHEDULE) "请补齐每条时间表事务的标题和时间后再勾选导入。" else "请补齐每条待办的标题后再勾选导入。", color = DeleteInk, fontSize = 13.sp) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).height(52.dp)) { Text("取消") }
                Button(onClick = {
                    if (destination == ImportDestination.SCHEDULE) onImportSchedules(selected.map { draft ->
                        ScheduleEvent(title = draft.title.trim(), eventAt = parseImportedDate(draft.timeText)!!, reminderDays = draft.reminderDays, reminderHours = draft.reminderHours, reminderMinutes = draft.reminderMinutes, note = draft.note.trim())
                    }) else onImportTodo(selected.map { draft -> draft.title.trim() + draft.note.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty() })
                    completedCount = selected.size
                }, enabled = selected.isNotEmpty(), modifier = Modifier.weight(1f).height(52.dp)) { Text("确认导入（${selected.size}）") }
            }
        }
    }
}

@Composable
private fun EditableImportDraftCard(draft: ImportDraft, destination: ImportDestination, checked: Boolean, usable: Boolean, onCheckedChange: (Boolean) -> Unit, onChange: (ImportDraft) -> Unit) {
    var expanded by remember(draft.sourceLine) { mutableStateOf(false) }
    var showRecognizedTimes by remember(draft.sourceLine) { mutableStateOf(false) }
    val timeValid = destination == ImportDestination.TODO || parseImportedDate(draft.timeText) != null
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().background(SoftBlue).clickable { expanded = !expanded }.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, enabled = usable, onCheckedChange = onCheckedChange)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(draft.title.ifBlank { "未命名事务" }, color = Ink, fontSize = 16.sp)
                    val detail = when {
                        draft.problem != null -> draft.problem
                        draft.title.isBlank() -> "缺少事务名称，展开后补齐"
                        !timeValid -> "缺少或无法识别时间，展开后补齐"
                        destination == ImportDestination.SCHEDULE -> "时间：${draft.timeText}"
                        else -> draft.note.ifBlank { "点击展开，补充备注" }
                    }
                    Text(detail, color = if (usable) Muted else DeleteInk, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "收起" else "展开", tint = Blue)
            }
            if (expanded) Column(Modifier.fillMaxWidth().background(Color.White).padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(draft.title, { onChange(draft.copy(title = it)) }, modifier = Modifier.fillMaxWidth(), label = { Text("标题") }, singleLine = true)
                if (destination == ImportDestination.SCHEDULE) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(draft.timeText, { onChange(draft.copy(timeText = it)) }, modifier = Modifier.weight(1f), label = { Text("时间") }, placeholder = { Text("2026年9月21日 09:30") }, singleLine = true, isError = !timeValid)
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { showRecognizedTimes = !showRecognizedTimes }) { Text("选择") }
                    }
                    if (showRecognizedTimes) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (draft.startAt != null) OutlinedButton(onClick = { onChange(draft.copy(timeText = formatImportedTime(draft.startAt))) }, modifier = Modifier.fillMaxWidth()) { Text("开始时间：${formatImportedTime(draft.startAt)}") }
                        if (draft.endAt != null) OutlinedButton(onClick = { onChange(draft.copy(timeText = formatImportedTime(draft.endAt))) }, modifier = Modifier.fillMaxWidth()) { Text("结束时间：${formatImportedTime(draft.endAt)}") }
                        if (draft.startAt == null && draft.endAt == null) Text("没有可用的 start 或 end 时间；请直接填写上方时间。", color = Muted, fontSize = 13.sp)
                    }
                }
                OutlinedTextField(draft.note, { onChange(draft.copy(note = it)) }, modifier = Modifier.fillMaxWidth(), label = { Text("备注（可选）") }, minLines = 2)
            }
        }
    }
}
@Composable
private fun ImportResultScreen(destination: ImportDestination, count: Int, onFinish: (ImportDestination) -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.CheckCircle, null, tint = Blue, modifier = Modifier.size(56.dp))
        Text("已创建 $count 条${if (destination == ImportDestination.SCHEDULE) "时间表实例" else "待办事项"}", fontSize = 21.sp, color = Ink, modifier = Modifier.padding(top = 14.dp))
        Text("已跳过未勾选、缺失或无法识别的信息。", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Button(onClick = { onFinish(destination) }, modifier = Modifier.padding(top = 22.dp)) { Text(if (destination == ImportDestination.SCHEDULE) "查看时间表" else "查看待办") }
    }
}
@Composable
private fun ScheduleScreen(events: List<ScheduleEvent>, onEdit: (ScheduleEvent) -> Unit, onArchive: (List<ScheduleEvent>) -> Unit, onDelete: (List<ScheduleEvent>) -> Unit, onShowHistory: () -> Unit, onSelectionChanged: (Boolean) -> Unit, cancelSelectionRequest: Int) {
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val selecting = selectedIds.isNotEmpty()
    LaunchedEffect(selecting) { onSelectionChanged(selecting) }
    LaunchedEffect(cancelSelectionRequest) { if (cancelSelectionRequest > 0) selectedIds = emptySet() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Column { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("时间表", fontSize = 30.sp, color = Ink, modifier = Modifier.weight(1f)); IconButton(onClick = onShowHistory) { Icon(Icons.Default.MenuBook, "打开历史", tint = Ink) } }; Text("今天：${formatToday()}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) } }
            if (events.isEmpty()) item { EmptyHint("还没有时间任务", "点击右下角＋，添加需要准时提醒的事情") }
            items(events, key = { it.id }) { event ->
                val toggle = { selectedIds = selectedIds.toggle(event.id) }
                val card: @Composable () -> Unit = { ScheduleCard(event, showReminder = true, selected = event.id in selectedIds, selecting = selecting, onToggle = toggle, onLongSelect = { selectedIds = setOf(event.id) }) }
                if (selecting) card() else ThreeActionSwipeRow(onEdit = { onEdit(event) }, onArchive = { onArchive(listOf(event)) }, onDelete = { onDelete(listOf(event)) }) { card() }
            }
        }
        if (selecting) SelectionActionBar(onCancel = { selectedIds = emptySet() }, onArchive = { onArchive(events.filter { it.id in selectedIds }); selectedIds = emptySet() }, onDelete = { onDelete(events.filter { it.id in selectedIds }); selectedIds = emptySet() })
    }
}

@Composable
private fun ScheduleHistoryScreen(events: List<ScheduleEvent>, onBack: () -> Unit, onDelete: (List<ScheduleEvent>) -> Unit, onSelectionChanged: (Boolean) -> Unit, cancelSelectionRequest: Int) {
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val selecting = selectedIds.isNotEmpty()
    LaunchedEffect(selecting) { onSelectionChanged(selecting) }; LaunchedEffect(cancelSelectionRequest) { if (cancelSelectionRequest > 0) selectedIds = emptySet() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ChevronLeft, "返回") }; Text("时间表历史", fontSize = 28.sp, color = Ink) } }
            if (events.isEmpty()) item { EmptyHint("暂无历史事务", "归档或过期的时间事务会出现在这里") }
            items(events, key = { it.id }) { event ->
                val card: @Composable () -> Unit = { ScheduleCard(event, showReminder = false, selected = event.id in selectedIds, selecting = selecting, onToggle = { selectedIds = selectedIds.toggle(event.id) }, onLongSelect = { selectedIds = setOf(event.id) }) }
                if (selecting) card() else SwipeDeleteRow(onDelete = { onDelete(listOf(event)) }) { card() }
            }
        }
        if (selecting) SelectionActionBar(onCancel = { selectedIds = emptySet() }, onDelete = { onDelete(events.filter { it.id in selectedIds }); selectedIds = emptySet() })
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ScheduleCard(event: ScheduleEvent, showReminder: Boolean, selected: Boolean, selecting: Boolean, onToggle: () -> Unit, onLongSelect: () -> Unit) {
    var expanded by remember(event.id) { mutableStateOf(false) }
    val reminder = if (event.reminderTriggered) "已提醒" else formatReminder(event)
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { if (selecting) onToggle() else expanded = !expanded }, onLongClick = onLongSelect)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(formatScheduleDate(event), color = Blue, fontSize = 13.sp); Text(event.title, color = Ink, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (selecting) { SelectionMarker(selected); Spacer(Modifier.width(8.dp)) }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Blue, modifier = Modifier.size(20.dp))
            }
            if (showReminder && reminder != null) { Spacer(Modifier.height(4.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.NotificationsNone, null, Modifier.size(15.dp), tint = Muted); Spacer(Modifier.width(5.dp)); Text(reminder, color = Muted, fontSize = 12.sp) } }
            if (expanded && event.note.isNotBlank()) { HorizontalDivider(Modifier.padding(vertical = 8.dp)); Text("事务详情", fontSize = 12.sp, color = Muted); Text(event.note, color = Ink, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp)) }
        }
    }
}

@Composable
private fun TodoScreen(boards: List<TodoBoardWithItems>, toggleItem: (TodoItem) -> Unit, toggleBoard: (TodoBoard) -> Unit, onEdit: (TodoBoardWithItems) -> Unit, onArchive: (List<TodoBoardWithItems>) -> Unit, onDelete: (List<TodoBoardWithItems>) -> Unit, onShowHistory: () -> Unit, onSelectionChanged: (Boolean) -> Unit, cancelSelectionRequest: Int) {
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val selecting = selectedIds.isNotEmpty()
    LaunchedEffect(selecting) { onSelectionChanged(selecting) }
    LaunchedEffect(cancelSelectionRequest) { if (cancelSelectionRequest > 0) selectedIds = emptySet() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("待办", fontSize = 30.sp, color = Ink); Text("记录事情和大概时间；准时提醒请使用时间表", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }; IconButton(onClick = onShowHistory) { Icon(Icons.Default.MenuBook, "打开历史", tint = Ink) } } }
            if (boards.isEmpty()) item { EmptyHint("还没有待办", "点击＋创建一个待办框，再在里面连续添加小事项") }
            items(boards, key = { it.board.id }) { board ->
                val card: @Composable () -> Unit = { TodoBoardCard(board, history = false, selected = board.board.id in selectedIds, selecting = selecting, toggleItem = toggleItem, toggleBoard = toggleBoard, onToggle = { selectedIds = selectedIds.toggle(board.board.id) }, onLongSelect = { selectedIds = setOf(board.board.id) }) }
                if (selecting) card() else ThreeActionSwipeRow(onEdit = { onEdit(board) }, onArchive = { onArchive(listOf(board)) }, onDelete = { onDelete(listOf(board)) }) { card() }
            }
        }
        if (selecting) SelectionActionBar(onCancel = { selectedIds = emptySet() }, onArchive = { onArchive(boards.filter { it.board.id in selectedIds }); selectedIds = emptySet() }, onDelete = { onDelete(boards.filter { it.board.id in selectedIds }); selectedIds = emptySet() })
    }
}

@Composable
private fun TodoHistoryScreen(boards: List<TodoBoardWithItems>, onBack: () -> Unit, onDelete: (List<TodoBoardWithItems>) -> Unit, onSelectionChanged: (Boolean) -> Unit, cancelSelectionRequest: Int) {
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val selecting = selectedIds.isNotEmpty()
    LaunchedEffect(selecting) { onSelectionChanged(selecting) }; LaunchedEffect(cancelSelectionRequest) { if (cancelSelectionRequest > 0) selectedIds = emptySet() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ChevronLeft, "返回") }; Text("待办历史", fontSize = 28.sp, color = Ink) } }
            if (boards.isEmpty()) item { EmptyHint("暂无历史待办", "归档或过期的待办会出现在这里") }
            items(boards, key = { it.board.id }) { board ->
                val card: @Composable () -> Unit = { TodoBoardCard(board, history = true, selected = board.board.id in selectedIds, selecting = selecting, toggleItem = {}, toggleBoard = {}, onToggle = { selectedIds = selectedIds.toggle(board.board.id) }, onLongSelect = { selectedIds = setOf(board.board.id) }) }
                if (selecting) card() else SwipeDeleteRow(onDelete = { onDelete(listOf(board)) }) { card() }
            }
        }
        if (selecting) SelectionActionBar(onCancel = { selectedIds = emptySet() }, onDelete = { onDelete(boards.filter { it.board.id in selectedIds }); selectedIds = emptySet() })
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TodoBoardCard(board: TodoBoardWithItems, history: Boolean, selected: Boolean, selecting: Boolean, toggleItem: (TodoItem) -> Unit, toggleBoard: (TodoBoard) -> Unit, onToggle: () -> Unit, onLongSelect: () -> Unit) {
    val done = board.items.count { it.completed }
    val overdue = !history && board.board.dueDate?.let { it < startOfToday() && done < board.items.size } == true
    val cardInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().combinedClickable(interactionSource = cardInteraction, indication = null, onClick = { if (selecting) onToggle() else toggleBoard(board.board) }, onLongClick = onLongSelect)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Icon(if (board.board.expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Blue); Spacer(Modifier.width(6.dp)); Text(board.board.summary, Modifier.weight(1f), color = Ink, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("$done / ${board.items.size}", color = Muted); if (selecting) SelectionMarker(selected) }
            if (board.board.dueDate != null || !history) { Spacer(Modifier.height(6.dp)); Text(when { history && board.board.dueDate != null -> "截止时间：${formatDateOnly(board.board.dueDate)}"; overdue -> "已逾期 · 截止：${formatDateOnly(board.board.dueDate!!)}"; board.board.dueDate != null -> "截止：${formatDateOnly(board.board.dueDate)}"; else -> "未设置截止日期" }, color = if (overdue) Color(0xFFB04A35) else Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 28.dp)) }
            if (board.board.expanded) { HorizontalDivider(Modifier.padding(vertical = 10.dp)); board.items.sortedBy { it.position }.forEach { item -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = item.completed, onCheckedChange = if (history || selecting) null else { _: Boolean -> toggleItem(item) }); val parts = item.text.split("\n", limit = 2); Column { Text(parts.first(), color = if (item.completed) Muted else Ink, textDecoration = if (item.completed) TextDecoration.LineThrough else null); if (parts.size > 1) Text(parts[1], color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp)) } } } }
        }
    }
}
@Composable
private fun DiaryScreen(entries: List<DiaryEntry>, onOpen: (Long, DiaryEntry?) -> Unit, onDelete: (DiaryEntry) -> Unit, onSelectionChanged: (Boolean) -> Unit, cancelSelectionRequest: Int) {
    var showCalendar by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val selecting = selectedIds.isNotEmpty()
    LaunchedEffect(selecting) { onSelectionChanged(selecting) }
    LaunchedEffect(cancelSelectionRequest) { if (cancelSelectionRequest > 0) selectedIds = emptySet() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("日记", fontSize = 30.sp, color = Ink); Text("每天一篇，标题固定为日期", color = Muted, fontSize = 13.sp) }; IconButton(onClick = { showCalendar = !showCalendar }) { Icon(Icons.Default.CalendarMonth, "打开日历", tint = Blue) } } }
            if (showCalendar) item { DiaryCalendar(month, entries.map { it.day }.toSet(), onPrev = { month = month.minusMonths(1) }, onNext = { month = month.plusMonths(1) }, onSelect = { day -> onOpen(day, entries.firstOrNull { it.day == day }) }) }
            if (entries.isEmpty()) item { EmptyHint("今天想记下什么？", "点击＋写下今天的日记") }
            items(entries, key = { it.id }) { entry ->
                val card: @Composable () -> Unit = { DiaryCard(entry, selected = entry.id in selectedIds, selecting = selecting, onToggle = { selectedIds = selectedIds.toggle(entry.id) }, onLongSelect = { selectedIds = setOf(entry.id) }, onOpen = { onOpen(entry.day, entry) }) }
                if (selecting) card() else SwipeActionRow(onEdit = { onOpen(entry.day, entry) }, onDelete = { onDelete(entry) }) { card() }
            }
        }
        if (selecting) SelectionActionBar(onCancel = { selectedIds = emptySet() }, onDelete = { entries.filter { it.id in selectedIds }.forEach(onDelete); selectedIds = emptySet() })
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DiaryCard(entry: DiaryEntry, selected: Boolean, selecting: Boolean, onToggle: () -> Unit, onLongSelect: () -> Unit, onOpen: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { if (selecting) onToggle() else onOpen() }, onLongClick = onLongSelect)) { Column(Modifier.padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(formatDateOnly(entry.day), color = Blue, fontSize = 14.sp); Text(entry.summary, color = Ink, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }; if (selecting) SelectionMarker(selected) }; if (entry.content.isNotBlank()) Text(entry.content, color = Muted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp)) } }
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
private fun MemoryScreen(categories: List<MemoryCategoryWithEntries>, selectedId: Long?, onBack: () -> Unit, onOpen: (Long) -> Unit, onEditCategory: (MemoryCategory) -> Unit, onDeleteCategory: (MemoryCategory) -> Unit, onEditEntry: (MemoryEntry) -> Unit, onDeleteEntry: (MemoryEntry) -> Unit, onSelectionChanged: (Boolean) -> Unit, cancelSelectionRequest: Int) {
    val selected = categories.firstOrNull { it.category.id == selectedId }
    var selectedIds by remember(selectedId) { mutableStateOf(setOf<Long>()) }
    val selecting = selectedIds.isNotEmpty()
    LaunchedEffect(selecting) { onSelectionChanged(selecting) }
    LaunchedEffect(cancelSelectionRequest) { if (cancelSelectionRequest > 0) selectedIds = emptySet() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { if (selected == null) { Text("记忆", fontSize = 30.sp, color = Ink); Text("把值得留下的事情收进分类", color = Muted, fontSize = 13.sp) } else Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ChevronLeft, "返回") }; Text(selected.category.name, fontSize = 28.sp, color = Ink) } }
            if (selected == null) {
                if (categories.isEmpty()) item { EmptyHint("还没有记忆分类", "点击＋，创建如「旅行」「学习」这样的分类") }
                items(categories, key = { it.category.id }) { category ->
                    val card: @Composable () -> Unit = { MemoryCategoryCard(category, selected = category.category.id in selectedIds, selecting = selecting, onToggle = { selectedIds = selectedIds.toggle(category.category.id) }, onLongSelect = { selectedIds = setOf(category.category.id) }, onOpen = { onOpen(category.category.id) }) }
                    if (selecting) card() else SwipeActionRow(onEdit = { onEditCategory(category.category) }, onDelete = { onDeleteCategory(category.category) }) { card() }
                }
            } else {
                if (selected.entries.isEmpty()) item { EmptyHint("这个分类还没有内容", "点击＋，记录一件具体的记忆") }
                items(selected.entries, key = { it.id }) { entry ->
                    val card: @Composable () -> Unit = { MemoryEntryCard(entry, selected = entry.id in selectedIds, selecting = selecting, onToggle = { selectedIds = selectedIds.toggle(entry.id) }, onLongSelect = { selectedIds = setOf(entry.id) }, onOpen = { onEditEntry(entry) }) }
                    if (selecting) card() else SwipeActionRow(onEdit = { onEditEntry(entry) }, onDelete = { onDeleteEntry(entry) }) { card() }
                }
            }
        }
        if (selecting) SelectionActionBar(onCancel = { selectedIds = emptySet() }, onDelete = { if (selected == null) categories.filter { it.category.id in selectedIds }.forEach { onDeleteCategory(it.category) } else selected.entries.filter { it.id in selectedIds }.forEach(onDeleteEntry); selectedIds = emptySet() })
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MemoryCategoryCard(category: MemoryCategoryWithEntries, selected: Boolean, selecting: Boolean, onToggle: () -> Unit, onLongSelect: () -> Unit, onOpen: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { if (selecting) onToggle() else onOpen() }, onLongClick = onLongSelect)) { Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Folder, null, tint = Blue); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(category.category.name, color = Ink, fontSize = 18.sp); Text("${category.entries.size} 条记忆", color = Muted, fontSize = 13.sp) }; if (selecting) SelectionMarker(selected) else Icon(Icons.Default.ChevronRight, null, tint = Muted) } }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MemoryEntryCard(entry: MemoryEntry, selected: Boolean, selecting: Boolean, onToggle: () -> Unit, onLongSelect: () -> Unit, onOpen: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { if (selecting) onToggle() else onOpen() }, onLongClick = onLongSelect)) { Column(Modifier.padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(entry.title, color = Ink, fontSize = 18.sp, modifier = Modifier.weight(1f)); if (selecting) SelectionMarker(selected) }; Text(entry.content, color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp)); Text(formatDateOnly(entry.createdAt), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) } }
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

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

@Composable
private fun SelectionMarker(selected: Boolean) {
    Box(Modifier.size(22.dp).clip(CircleShape).background(if (selected) Blue else Color(0xFFE2E2E8)), contentAlignment = Alignment.Center) {
        if (selected) Icon(Icons.Default.CheckCircle, null, tint = Color.White, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun SelectionActionBar(onCancel: () -> Unit, onArchive: (() -> Unit)? = null, onDelete: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(Modifier.fillMaxWidth().padding(16.dp).height(54.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFE9E9EE))) {
            Box(Modifier.weight(1f).fillMaxHeight().background(Color.White).clickable(onClick = onCancel), contentAlignment = Alignment.Center) { Text("取消", color = Ink, fontSize = 16.sp) }
            if (onArchive != null) Box(Modifier.weight(1f).fillMaxHeight().background(LightYellow).clickable(onClick = onArchive), contentAlignment = Alignment.Center) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Archive, null, tint = Ink, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("归档", color = Ink, fontSize = 16.sp) } }
            Box(Modifier.weight(1f).fillMaxHeight().background(LightRed).clickable(onClick = onDelete), contentAlignment = Alignment.Center) { Icon(Icons.Default.Delete, "删除", tint = DeleteInk) }
        }
    }
}

@Composable
private fun ThreeActionSwipeRow(onEdit: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val actionWidth = 264.dp
    val actionWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { actionWidth.toPx() }
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
        Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.width(88.dp).fillMaxHeight().background(LightBlue).clickable { scope.launch { offset.animateTo(0f) }; onEdit() }, contentAlignment = Alignment.Center) { Text("更改", color = Ink, fontSize = 15.sp) }
            Box(Modifier.width(88.dp).fillMaxHeight().background(LightYellow).clickable { scope.launch { offset.animateTo(0f) }; onArchive() }, contentAlignment = Alignment.Center) { Text("归档", color = Ink, fontSize = 15.sp) }
            Box(Modifier.width(88.dp).fillMaxHeight().background(LightRed).clickable { onDelete() }, contentAlignment = Alignment.Center) { Text("删除", color = DeleteInk, fontSize = 15.sp) }
        }
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }.pointerInput(actionWidthPx) { detectHorizontalDragGestures(onHorizontalDrag = { change, dragAmount -> change.consume(); scope.launch { offset.snapTo((offset.value + dragAmount).coerceIn(-actionWidthPx, 0f)) } }, onDragEnd = { scope.launch { offset.animateTo(if (offset.value < -actionWidthPx / 2) -actionWidthPx else 0f) } }, onDragCancel = { scope.launch { offset.animateTo(0f) } }) }) { content() }
    }
}

@Composable
private fun SwipeDeleteRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val actionWidth = 88.dp
    val actionWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { actionWidth.toPx() }
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
        Box(Modifier.matchParentSize().background(LightRed), contentAlignment = Alignment.CenterEnd) { Icon(Icons.Default.Delete, "删除", tint = DeleteInk, modifier = Modifier.padding(end = 30.dp)) }
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }.pointerInput(actionWidthPx) { detectHorizontalDragGestures(onHorizontalDrag = { change, dragAmount -> change.consume(); scope.launch { offset.snapTo((offset.value + dragAmount).coerceIn(-actionWidthPx, 0f)) } }, onDragEnd = { if (offset.value <= -actionWidthPx / 2) onDelete() else scope.launch { offset.animateTo(0f) } }, onDragCancel = { scope.launch { offset.animateTo(0f) } }) }) { content() }
    }
}

@Composable
private fun SwipeActionRow(onEdit: () -> Unit, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val actionWidth = 176.dp
    val actionWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { actionWidth.toPx() }
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
        Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.width(88.dp).fillMaxHeight().background(LightBlue).clickable { scope.launch { offset.animateTo(0f) }; onEdit() }, contentAlignment = Alignment.Center) { Text("更改", color = Ink, fontSize = 15.sp) }
            Box(Modifier.width(88.dp).fillMaxHeight().background(LightRed).clickable { onDelete() }, contentAlignment = Alignment.Center) { Text("删除", color = DeleteInk, fontSize = 15.sp) }
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

private fun importPrompt(destination: ImportDestination): String = if (destination == ImportDestination.TODO) """
你是待办事务整理助手。请先检查用户给出的文字、图片、PDF 或其他文件是否确实可读取；若有图片模糊、PDF 页面无法读取、附件缺失或不能确认的内容，必须输出一行 issue 说明，不能猜测或编造。

仅输出逐行 JSON，不要 Markdown 表格。每一条待办独占一行：
{"title":"待办名称","note":"可选备注"}
无法确认时：{"issue":"具体无法识别的内容和原因"}

title 不能缺失。不要输出开始时间、结束时间或提醒字段；没有备注时 note 填空字符串。
""".trimIndent() else """
你是事务整理助手。请先检查用户给出的文字、图片、PDF 或其他文件是否确实可读取；若存在图片模糊、扫描件 OCR 失败、PDF 页面无法读取、附件缺失或其他不能确认的内容，必须输出一行 issue 说明，不能猜测或编造。

仅输出逐行 JSON，不要 Markdown 表格。每一条事务独占一行：
{"title":"事务名称","start":"yyyy-MM-dd HH:mm 或 yyyy-MM-dd","end":"可留空","note":"可留空","reminder_days":0,"reminder_hours":0,"reminder_minutes":0}
无法确认时：{"issue":"具体无法识别的内容和原因"}

title 不能缺失。不确定的开始/结束时间请留空。本次会使用 start 作为时间表实例的时间；没有 start 的事务仍输出，但不要猜测。没有明确提醒信息时三个 reminder 字段都填 0。
""".trimIndent()

private fun parseImportedTransactions(text: String, destination: ImportDestination): ImportParseResult {
    if (text.isBlank()) return ImportParseResult(emptyList(), 0)
    var ignored = 0
    val unique = linkedSetOf<String>()
    val entries = mutableListOf<ImportedTransaction>()
    text.lineSequence().forEachIndexed { index, source ->
        val line = source.trim().removePrefix("```json").removePrefix("```").trim()
        if (!line.startsWith("{") || !line.endsWith("}")) { if (line.isNotBlank() && !line.startsWith("```")) ignored++; return@forEachIndexed }
        try {
            val json = JSONObject(line)
            if (json.has("issue")) {
                entries += ImportedTransaction(index + 1, "无法识别的信息", null, "", "", 0, 0, 0, json.optString("issue", "AI 未说明原因"))
                return@forEachIndexed
            }
            val title = json.optString("title", json.optString("name", "")).trim()
            val startText = json.optString("start", "").trim()
            val startAt = startText.takeIf { it.isNotBlank() }?.let(::parseImportedDate)
            val issue: String? = null
            val entry = ImportedTransaction(
                sourceLine = index + 1,
                title = title,
                startAt = startAt,
                endText = json.optString("end", "").trim(),
                note = json.optString("note", "").trim(),
                reminderDays = json.optInt("reminder_days", 0).coerceIn(0, 999),
                reminderHours = json.optInt("reminder_hours", 0).coerceIn(0, 23),
                reminderMinutes = json.optInt("reminder_minutes", 0).coerceIn(0, 59),
                problem = issue
            )
            val key = "${entry.title}|${entry.startAt}|${entry.endText}|${entry.note}|${entry.reminderDays}|${entry.reminderHours}|${entry.reminderMinutes}"
            if (entry.problem == null && !unique.add(key)) ignored++ else entries += entry
        } catch (_: Exception) { ignored++ }
    }
    return ImportParseResult(entries, ignored)
}

private fun parseImportedDate(value: String): Long? {
    val patterns = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm", "yyyy/M/d H:m", "yyyy-MM-dd", "yyyy/M/d", "yyyy年M月d日 HH:mm", "yyyy年M月d日")
    return patterns.firstNotNullOfOrNull { pattern ->
        val parser = SimpleDateFormat(pattern, Locale.CHINA).apply { isLenient = false }
        val position = ParsePosition(0)
        val date = parser.parse(value, position)
        date?.takeIf { position.index == value.length }?.time
    }
}

private fun formatImportedTime(time: Long): String = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date(time))