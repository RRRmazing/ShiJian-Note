package com.shijiannote.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shijiannote.app.data.*
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable fun ModernShiJianApp() {
    val context = LocalContext.current
    val model: WorkspaceModel = viewModel(factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application))
    val nodes by model.nodes.collectAsState()
    val schedules by model.schedules.collectAsState()
    val todos by model.todos.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var parent by rememberSaveable { mutableStateOf<String?>(null) }
    var editorSnapshot by rememberSaveable { mutableStateOf<String?>(null) }
    var editingNew by rememberSaveable { mutableStateOf(false) }
    var reveal by remember { mutableStateOf("") }
    val editorStack = remember { mutableStateListOf<String>() }
    val libraryState = rememberSaveableStateHolder()
    var selecting by remember { mutableStateOf(false) }
    var addMemory by remember { mutableStateOf(false) }
    var folderCreate by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var scheduleEdit by remember { mutableStateOf<ScheduleEvent?>(null) }
    var scheduleDialog by remember { mutableStateOf(false) }
    var todoDialog by remember { mutableStateOf(false) }
    var todoEdit by remember { mutableStateOf<TodoBoardWithItems?>(null) }
    var search by remember { mutableStateOf(false) }
    var general by rememberSaveable { mutableStateOf(false) }
    var trash by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var importDestination by remember { mutableStateOf<ImportDestination?>(null) }
    var importTitle by remember { mutableStateOf("自主导入") }
    var pendingImport by remember { mutableStateOf(false) }
    var exportIds by remember { mutableStateOf<Set<String>?>(null) }
    var backup by remember { mutableStateOf(false) }
    var exportTasks by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Set<String>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val record by RecordingService.state.collectAsState()
    val editor = editorSnapshot?.let { decodeNode(JSONObject(it)) }

    fun open(note: NoteNode, new: Boolean = false, query: String = "") {
        editorSnapshot = jsonObject(note).toString(); editingNew = new; reveal = query; selecting = false
    }
    fun export(ids: Set<String>) { if (ids.isNotEmpty()) exportIds = ids else scope.launch { snackbar.showSnackbar("没有可导出的内容") } }
    LaunchedEffect(Unit) {
        model.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        val action = (context as? android.app.Activity)?.intent?.action
        (context as? android.app.Activity)?.intent?.action = Intent.ACTION_MAIN
        if (action == "com.shijiannote.app.NEW_MEMORY") { tab = 3; open(model.newNote("memory"), true) }
        if (action == "com.shijiannote.app.NEW_DIARY") { tab = 2; val day = dayMillis(); val existing = model.notes.diary(day); open(existing ?: model.newNote("diary", day = day), existing == null) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            // Started events remain on today's page. Archive only before local midnight.
            model.dao.archiveExpiredSchedules(dayMillis())
            delay(60_000)
        }
    }
    BackHandler(enabled = editor == null && (addMemory || general || trash || advanced || about || importDestination != null)) {
        when { addMemory -> addMemory = false; importDestination != null -> importDestination = null; general -> general = false; trash -> trash = false; about -> about = false; else -> advanced = false }
    }
    YouthTheme {
        Scaffold(containerColor = Mist, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            if (editor == null && !general && !trash && !advanced && !about && importDestination == null) NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                val labels = listOf("时间表", "待办", "日记", "记忆", "设置")
                val icons = listOf(Icons.Default.CalendarMonth, Icons.Default.CheckCircleOutline, Icons.Default.MenuBook, Icons.Default.FolderOpen, Icons.Default.Settings)
                val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 360 || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
                labels.forEachIndexed { index, label -> NavigationBarItem(selected = tab == index, onClick = { tab = index; selecting = false; addMemory = false }, icon = { Icon(icons[index], label) }, label = { Text(if (compact && index == 0) "日程" else label, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }) }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (editor != null) RichNoteEditor(editor, model, editingNew, reveal,
                    onBack = {
                        if (editorStack.isEmpty()) { editorSnapshot = null; reveal = "" }
                        else { val id = editorStack.removeAt(editorStack.lastIndex); scope.launch { model.notes.node(id)?.let { open(it) } ?: run { editorSnapshot = null } } }
                    },
                    onOpen = { target -> scope.launch { runCatching { model.flush(editor.id); editorStack.add(editor.id); open(target) }.onFailure { snackbar.showSnackbar("请先保存当前记录") } } }, onExport = ::export)
                else when {
                    general -> GeneralSettings({ general = false })
                    trash -> TrashScreen(model, { trash = false })
                    importDestination != null -> ImportTransactionsScreen(importDestination!!, importTitle, null, { importDestination = null },
                        { events -> events.forEach { model.schedule(it) } },
                        { tasks -> model.saveBoard(null, importTitle, tasks, null, null) },
                        { destination -> importDestination = null; advanced = false; tab = if (destination == ImportDestination.SCHEDULE) 0 else 1 })
                    advanced -> AdvancedFeaturesScreen({ advanced = false }) { destination -> if (destination == ImportDestination.TODO) pendingImport = true else importDestination = destination }
                    about -> ModernAbout({ about = false })
                    tab < 4 -> libraryState.SaveableStateProvider("library-$tab") {
                        when (tab) {
                            0 -> ScheduleLibrary(model, { event -> scheduleEdit = event; scheduleDialog = true }, { search = true }, { exportTasks = "schedule" }, { selecting = it })
                            1 -> TodoLibrary(model, { board -> todoEdit = board; todoDialog = true }, { search = true }, { exportTasks = "todo" }, { selecting = it })
                            2 -> DiaryLibrary(model, { n, isNew -> open(n, isNew) }, { search = true }, ::export, { deleting = it }, { selecting = it })
                            else -> MemoryLibrary(model, parent, { parent = it }, { open(it) }, ::export, { search = true }, { selecting = it }, { deleting = it })
                        }
                    }
                    else -> SettingsHome({ general = true }, { trash = true }, { backup = true }, { advanced = true }, { about = true }, { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) })
                }
                if (editor == null && record.running) Surface(Modifier.align(Alignment.TopCenter).padding(8.dp), shape = RoundedCornerShape(16.dp), color = Peach) {
                    TextButton(onClick = { scope.launch { model.notes.node(record.owner)?.let { open(it, true) } } }) { Text("录音中 ${audioTime(record.elapsed)} · 返回记录") }
                }
                if (editor == null && tab < 4 && !selecting && !general && !trash && !advanced && !about && importDestination == null) {
                    if (addMemory && tab == 3) {
                        Box(Modifier.fillMaxSize().clickable { addMemory = false })
                        SoftCard(Modifier.align(Alignment.BottomCenter).padding(start = 18.dp, end = 18.dp, bottom = 90.dp), color = Color.White) {
                            val location = nodes.find { it.id == parent }?.let { TreeRules.path(it, nodes) } ?: "记忆首页"
                            Text("创建到：$location", fontSize = 12.sp, color = Quiet)
                            Button(onClick = { addMemory = false; folderName = ""; folderCreate = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), colors = ButtonDefaults.buttonColors(containerColor = Lavender, contentColor = NoteInk)) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(10.dp)); Text("创建分类") }
                            Button(onClick = { addMemory = false; open(model.newNote("memory", parent), true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = NoteInk)) { Icon(Icons.Default.Description, null); Spacer(Modifier.width(10.dp)); Text("创建记忆") }
                        }
                    }
                    FloatingActionButton(onClick = {
                        when (tab) {
                            0 -> { scheduleEdit = null; scheduleDialog = true }
                            1 -> { todoEdit = null; todoDialog = true }
                            2 -> scope.launch { val day = dayMillis(); val existing = model.notes.diary(day); open(existing ?: model.newNote("diary", day = day), existing == null) }
                            3 -> addMemory = !addMemory
                        }
                    }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp), containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = Sky) { Icon(if (addMemory) Icons.Default.Close else Icons.Default.Add, "新增") }
                }
            }
        }
        if (folderCreate) SoftDialog("创建分类", { folderCreate = false }) {
            OutlinedTextField(folderName, { folderName = it }, label = { Text("分类名称") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { model.createFolder(folderName, parent); folderCreate = false }, modifier = Modifier.fillMaxWidth(), enabled = folderName.isNotBlank()) { Text("创建") }
        }
        if (scheduleDialog) ScheduleDialog(scheduleEdit, { scheduleDialog = false }) {
            if (it.reminderEnabled && android.os.Build.VERSION.SDK_INT >= 33) (context as? android.app.Activity)?.let { activity -> if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4101) }
            model.schedule(it.copy(archived = scheduleEdit?.archived ?: false, deletedAt = scheduleEdit?.deletedAt)); scheduleDialog = false
        }
        if (todoDialog) QuickTodoCreateDialog(todoEdit?.copy(items = todoEdit!!.items.filter { it.deletedAt == null }), { todoDialog = false; todoEdit = null }) { title, tasks, due, reminder ->
            if (reminder != null && android.os.Build.VERSION.SDK_INT >= 33) (context as? android.app.Activity)?.let { activity -> if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4101) }
            model.saveBoard(todoEdit, title, tasks, due, reminder); todoDialog = false; todoEdit = null
        }
        if (search) GlobalSearch(model, tab, { search = false }, { n, query -> search = false; tab = if (n.kind == "diary") 2 else 3; if (n.kind == "memory") parent = n.parentId; editorStack.clear(); open(n, false, query) }, { event -> search = false; tab = 0; scheduleEdit = event; scheduleDialog = true }, { board -> search = false; tab = 1; todoEdit = board; todoDialog = true })
        deleting?.let { ids ->
            val targets = ids.flatMap { TreeRules.descendants(it, nodes) }.toSet()
            val affected = nodes.filter { it.id in targets && it.deletedAt == null }
            SoftDialog("移到回收站", { deleting = null }) {
                Text("包含 ${affected.count { it.kind == "folder" }} 个分类、${affected.count { it.kind != "folder" }} 条记录。可以从回收站恢复。")
                Button(onClick = { deleting = null; model.trash(ids) { group -> scope.launch { val result = snackbar.showSnackbar("已移到回收站", "撤销", duration = SnackbarDuration.Long); if (result == SnackbarResult.ActionPerformed) model.restore(group) } } }) { Text("删除") }
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        }
        exportIds?.let { ids -> NoteExportDialog(model, ids, { exportIds = null }) }
        if (backup) BackupDialog(model, { backup = false })
        exportTasks?.let { type -> TaskExportDialog(model, type, { exportTasks = null }) }
        if (pendingImport) SoftDialog("导入到待办清单", { pendingImport = false }) { OutlinedTextField(importTitle, { importTitle = it }, label = { Text("清单名称") }); Button(onClick = { pendingImport = false; importDestination = ImportDestination.TODO }) { Text("继续") } }
    }
}
