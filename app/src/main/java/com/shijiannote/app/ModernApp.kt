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
    val spacesReady by model.spacesReady.collectAsState()
    val startupError by model.startupError.collectAsState()
    if (!spacesReady) {
        YouthTheme {
            Surface(Modifier.fillMaxSize(), color = Mist) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (startupError == null) { CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text("正在整理本机记录…") }
                    else { Text(startupError!!); Button(onClick = { model.retry() }) { Text("重试") } }
                }
            }
        }
        return
    }
    val schedules by model.schedules.collectAsState()
    val todos by model.todos.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var parent by rememberSaveable { mutableStateOf<String?>(null) }
    var memoryVisit by rememberSaveable { mutableIntStateOf(0) }
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
    var todoTaskEdit by remember { mutableStateOf<TodoItem?>(null) }
    var todoView by rememberSaveable { mutableStateOf("lists") }
    var todoDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var search by rememberSaveable { mutableStateOf(false) }
    var searchTab by rememberSaveable { mutableIntStateOf(0) }
    var searchParent by rememberSaveable { mutableStateOf<String?>(null) }
    var searchSession by rememberSaveable { mutableIntStateOf(0) }
    var searchFolder by rememberSaveable { mutableStateOf(false) }
    var searchFolderParent by rememberSaveable { mutableStateOf<String?>(null) }
    var structure by rememberSaveable { mutableStateOf(false) }
    var structureRoot by rememberSaveable { mutableStateOf<String?>(null) }
    var structureFolder by rememberSaveable { mutableStateOf(false) }
    var structureEntry by rememberSaveable { mutableStateOf<String?>(null) }
    var structureFolderParent by rememberSaveable { mutableStateOf<String?>(null) }
    var structureSearchSnapshot by rememberSaveable { mutableStateOf<String?>(null) }
    var structureHistory by rememberSaveable { mutableStateOf("[]") }
    var treeReturnChoice by rememberSaveable { mutableStateOf(false) }
    var failurePage by rememberSaveable { mutableStateOf(false) }
    val imageBusy by model.imageBusy.collectAsState()
    val imageFailures by model.imageFailures.collectAsState()
    var general by rememberSaveable { mutableStateOf(false) }
    var trash by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var diaryPast by rememberSaveable { mutableStateOf(false) }
    var handledRecallRequest by rememberSaveable { mutableStateOf<String?>(null) }
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
    fun startSearchAt(originParent: String?) {
        // Keep state while a result is open, but start each new search with fresh filters.
        libraryState.removeState("search-$searchSession")
        searchSession++; searchParent = originParent
        searchTab = tab; searchFolder = false; search = true; addMemory = false
    }
    fun startSearch() { startSearchAt(if (tab == 3) parent else null) }
    fun showStructure(root: String?) {
        val history = if (structure && (structureFolder || structureSearchSnapshot != null)) org.json.JSONArray(structureHistory).put(JSONObject()
            .put("root", structureRoot).put("entry", structureEntry).put("parent", structureFolderParent).put("folder", structureFolder)
            .put("search", search).put("searchFolder", searchFolder).put("searchParent", searchFolderParent)
            .put("searchTab", searchTab).put("searchParentScope", searchParent).put("searchSession", searchSession).put("searchSnapshot", structureSearchSnapshot)) else org.json.JSONArray()
        structureHistory = history.toString()
        structureRoot = root; structureFolder = false; structureEntry = null; structureSearchSnapshot = null; structure = true
    }
    fun closeStructure() {
        val history = org.json.JSONArray(structureHistory)
        if (history.length() == 0) { structure = false; structureEntry = null } else {
            val previous = history.getJSONObject(history.length() - 1)
            history.remove(history.length() - 1); structureHistory = history.toString()
            structureRoot = previous.optString("root").takeIf { it.isNotBlank() }
            structureEntry = previous.optString("entry").takeIf { it.isNotBlank() }
            structureFolderParent = previous.optString("parent").takeIf { it.isNotBlank() }
            structureFolder = previous.optBoolean("folder")
            search = previous.optBoolean("search"); searchFolder = previous.optBoolean("searchFolder")
            searchFolderParent = previous.optString("searchParent").takeIf { it.isNotBlank() }; searchTab = previous.optInt("searchTab")
            searchParent = previous.optString("searchParentScope").takeIf { it.isNotBlank() }; searchSession = previous.optInt("searchSession", searchSession)
            structureSearchSnapshot = previous.optString("searchSnapshot").takeIf { it.isNotBlank() }
        }
    }
    fun searchFromStructure() {
        structureSearchSnapshot = JSONObject().put("search", search).put("folder", searchFolder).put("parent", searchFolderParent).put("tab", searchTab).put("scopeParent", searchParent).put("session", searchSession).toString()
        searchSession++; searchParent = structureFolderParent ?: structureRoot
        searchFolder = false; search = true; addMemory = false; searchTab = 3
    }
    fun closeSearch() {
        val previous = structureSearchSnapshot?.let { JSONObject(it) }
        if (previous != null) {
            search = previous.optBoolean("search"); searchFolder = previous.optBoolean("folder")
            searchFolderParent = previous.optString("parent").takeIf { it.isNotBlank() }; searchTab = previous.optInt("tab")
            searchParent = previous.optString("scopeParent").takeIf { it.isNotBlank() }; searchSession = previous.optInt("session", searchSession)
            structureSearchSnapshot = null
        } else { search = false; searchFolder = false }
    }
    fun detachTree(destination: String?) {
        parent = destination
        editorSnapshot = null; editorStack.clear(); reveal = ""; tab = 3; memoryVisit++
        structure = false; structureFolder = false; structureEntry = null; structureSearchSnapshot = null; structureHistory = "[]"
        search = false; searchFolder = false; treeReturnChoice = false; selecting = false
    }
    fun leaveTreeForParent() {
        val entry = nodes.find { it.id == structureEntry }
        val destination = entry?.parentId?.takeIf { id -> nodes.any { it.id == id && it.deletedAt == null } }
            ?: if (entry != null && !MemorySpaces.isRoot(entry.id)) MemorySpaces.rootId(entry, nodes) else null
        detachTree(destination)
    }
    LaunchedEffect(imageFailures) { failurePage = imageFailures.isNotEmpty() }
    LaunchedEffect(Unit) {
        model.messages.collect { snackbar.showSnackbar(it) }
    }
    val recallRequest = (context as? MainActivity)?.diaryRecallRequest
    LaunchedEffect(recallRequest) {
        if (recallRequest != null && recallRequest != handledRecallRequest) {
            handledRecallRequest = recallRequest
            editorSnapshot = null; editorStack.clear(); reveal = ""
            search = false; searchFolder = false; structure = false; structureFolder = false; structureSearchSnapshot = null
            general = false; trash = false; advanced = false; about = false; failurePage = false
            importDestination = null; selecting = false; addMemory = false
            scheduleDialog = false; todoDialog = false; folderCreate = false; backup = false
            exportTasks = null; exportIds = null; deleting = null; pendingImport = false; treeReturnChoice = false
            tab = 2; diaryPast = true
            DiaryRecallReminder.markRecallRead(context, java.time.LocalDate.now())
        }
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
            if (editor == null && !search && !structure && !(tab == 2 && diaryPast) && !failurePage && !general && !trash && !advanced && !about && importDestination == null) NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                val labels = listOf("时间表", "待办", "日记", "记忆", "设置")
                val icons = listOf(Icons.Default.CalendarMonth, Icons.Default.CheckCircleOutline, Icons.Default.MenuBook, Icons.Default.FolderOpen, Icons.Default.Settings)
                val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 360 || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
                labels.forEachIndexed { index, label -> NavigationBarItem(selected = tab == index, onClick = { if (index == 3 || tab == 3) { parent = null; memoryVisit++ }; tab = index; selecting = false; addMemory = false }, icon = { Icon(icons[index], label) }, label = { Text(if (compact && index == 0) "日程" else label, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }) }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (editor != null) RichNoteEditor(editor, model, editingNew, reveal,
                    onBack = {
                        if (editorStack.isEmpty()) {
                            if (structure && structureSearchSnapshot == null && editor.id == structureEntry) treeReturnChoice = true
                            else { editorSnapshot = null; reveal = "" }
                        }
                        else { val id = editorStack.removeAt(editorStack.lastIndex); scope.launch { model.notes.node(id)?.let { open(it) } ?: run { editorSnapshot = null } } }
                    },
                    onOpen = { target -> scope.launch { runCatching { model.flush(editor.id); editorStack.add(editor.id); open(target) }.onFailure { snackbar.showSnackbar("请先保存当前记录") } } }, onExport = ::export)
                else when {
                    failurePage -> ImageFailuresScreen(model, { failurePage = false }, { open(it, true) })
                    structure && structureSearchSnapshot == null && structureFolder -> libraryState.SaveableStateProvider("structure-folder-$structureRoot-$structureFolderParent") {
                        MemoryLibrary(model, structureFolderParent, { destination ->
                            if (structureEntry != null && destination in TreeRules.descendants(structureEntry!!, nodes)) structureFolderParent = destination
                            else detachTree(destination)
                        }, { editorStack.clear(); open(it) }, ::export,
                            ::searchFromStructure, { selecting = it }, { deleting = it }, { showStructure(structureFolderParent) },
                            if (structureFolderParent == structureEntry || nodes.none { it.id == structureFolderParent && it.deletedAt == null }) ({ treeReturnChoice = true }) else null)
                    }
                    structure && structureSearchSnapshot == null -> libraryState.SaveableStateProvider("structure-$structureRoot") {
                        StructureTreeScreen(nodes, structureRoot, ::closeStructure, highlightId = structureEntry) { node ->
                            structureEntry = node.id
                            if (node.kind == "folder") { structureEntry = node.id; structureFolderParent = node.id; structureFolder = true }
                            else { editorStack.clear(); open(node) }
                        }
                    }
                    search && searchFolder -> libraryState.SaveableStateProvider("search-folder-$searchFolderParent") {
                        MemoryLibrary(model, searchFolderParent, { searchFolderParent = it }, { open(it) }, ::export, { startSearchAt(searchFolderParent); searchTab = 3 }, { selecting = it }, { deleting = it }, { showStructure(searchFolderParent) }, { searchFolder = false })
                    }
                    search -> libraryState.SaveableStateProvider("search-$searchSession") {
                        ModuleSearch(model, searchTab, searchParent, ::closeSearch,
                            { n, query -> editorStack.clear(); open(n, false, query) },
                            { folder -> searchFolderParent = folder.id; searchFolder = true },
                            { event -> scheduleEdit = event; scheduleDialog = true },
                            { board ->
                                // Search groups may contain only matching items; editing must keep the full list.
                                todoDay = null; todoEdit = todos.find { it.board.id == board.board.id } ?: board
                                todoTaskEdit = board.items.firstOrNull().takeIf { board.board.boardType == "DAILY" }
                                todoDialog = todoTaskEdit != null || board.board.boardType != "DAILY"
                            })
                    }
                    general -> GeneralSettings(model, { general = false })
                    trash -> TrashScreen(model, { trash = false })
                    importDestination != null -> ImportTransactionsScreen(importDestination!!, importTitle, null, { importDestination = null },
                        { events -> events.forEach { model.schedule(it) } },
                        { tasks -> model.saveBoard(null, importTitle, tasks, null, null) },
                        { destination -> importDestination = null; advanced = false; tab = if (destination == ImportDestination.SCHEDULE) 0 else 1 })
                    advanced -> AdvancedFeaturesScreen({ advanced = false }) { destination -> if (destination == ImportDestination.TODO) pendingImport = true else importDestination = destination }
                    about -> ModernAbout({ about = false })
                    tab < 4 -> libraryState.SaveableStateProvider("library-$tab") {
                        when (tab) {
                            0 -> ScheduleLibrary(model, { event -> scheduleEdit = event; scheduleDialog = true }, ::startSearch, { exportTasks = "schedule" }, { selecting = it })
                            1 -> TodoLibrary(model, { board -> todoDay = null; todoTaskEdit = null; todoEdit = board; todoDialog = true }, ::startSearch, { exportTasks = "todo" }, { selecting = it }, { todoView = it })
                            2 -> DiaryLibrary(model, { n, isNew -> open(n, isNew) }, ::startSearch, ::export, { deleting = it }, { selecting = it }, pastPage = diaryPast, onPastChange = { diaryPast = it })
                            else -> libraryState.SaveableStateProvider("memory-$memoryVisit-$parent") {
                                if (spacesReady) MemoryLibrary(model, parent, { parent = it }, { open(it) }, ::export, ::startSearch, { selecting = it }, { deleting = it }, { showStructure(parent) })
                                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            }
                        }
                    }
                    else -> SettingsHome({ general = true }, { trash = true }, { backup = true }, { advanced = true }, { about = true }, { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) })
                }
                if (editor == null && record.running) Surface(Modifier.align(Alignment.TopCenter).padding(8.dp), shape = RoundedCornerShape(16.dp), color = Peach) {
                    TextButton(onClick = { scope.launch { model.notes.node(record.owner)?.let { open(it, true) } } }) { Text("录音中 ${audioTime(record.elapsed)} · 返回记录") }
                }
                if (editor == null && tab < 4 && !(tab == 2 && diaryPast) && (tab != 3 || parent != null) && (tab != 1 || todoView != "repeat") && !selecting && !search && !structure && !failurePage && !general && !trash && !advanced && !about && importDestination == null) {
                    if (addMemory && tab == 3) {
                        BackHandler { addMemory = false }
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)).clickable { addMemory = false })
                        SoftCard(Modifier.align(Alignment.BottomCenter).padding(start = 18.dp, end = 18.dp, bottom = 100.dp), color = Color.White) {
                            val location = nodes.find { it.id == parent }?.let { TreeRules.path(it, nodes) } ?: "记忆首页"
                            Text("创建到：$location", fontSize = 12.sp, color = Quiet)
                            Spacer(Modifier.height(4.dp))
                            Button(onClick = { addMemory = false; folderName = ""; folderCreate = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), colors = ButtonDefaults.buttonColors(containerColor = Lavender, contentColor = NoteInk)) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(10.dp)); Text("创建分类") }
                            Button(onClick = { addMemory = false; open(model.newNote("memory", parent), true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = NoteInk)) { Icon(Icons.Default.Description, null); Spacer(Modifier.width(10.dp)); Text("创建记忆") }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    FloatingActionButton(onClick = {
                        when (tab) {
                            0 -> { scheduleEdit = null; scheduleDialog = true }
                            1 -> { todoEdit = null; todoTaskEdit = null; todoDay = when (todoView) { "today" -> dayMillis(); "tomorrow" -> dayMillis(java.time.LocalDate.now().plusDays(1)); else -> null }; todoDialog = true }
                            2 -> scope.launch { val day = dayMillis(); val existing = model.notes.diary(day); open(existing ?: model.newNote("diary", day = day), existing == null) }
                            3 -> addMemory = !addMemory
                        }
                    }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp), containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = Sky) { Icon(if (addMemory) Icons.Default.Close else Icons.Default.Add, "新增") }
                }
            }
        }
        if (treeReturnChoice) SoftDialog("返回到哪里？", { treeReturnChoice = false }) {
            Text("可以回到结构树继续浏览，或进入当前内容的上级分类。", color = Quiet)
            Button(onClick = { editorSnapshot = null; editorStack.clear(); reveal = ""; structureFolder = false; treeReturnChoice = false; selecting = false }, modifier = Modifier.fillMaxWidth()) { Text("返回树形图") }
            OutlinedButton(onClick = ::leaveTreeForParent, modifier = Modifier.fillMaxWidth()) { Text("返回上级") }
        }
        if (folderCreate) SoftDialog("创建分类", { folderCreate = false }) {
            OutlinedTextField(folderName, { folderName = it }, label = { Text("分类名称") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { model.createFolder(folderName, parent); folderCreate = false }, modifier = Modifier.fillMaxWidth(), enabled = folderName.isNotBlank()) { Text("创建") }
        }
        if (scheduleDialog) ScheduleDialog(scheduleEdit, { scheduleDialog = false }) {
            if (it.reminderEnabled && android.os.Build.VERSION.SDK_INT >= 33) (context as? android.app.Activity)?.let { activity -> if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4101) }
            model.schedule(it.copy(archived = scheduleEdit?.archived ?: false, deletedAt = scheduleEdit?.deletedAt, important = scheduleEdit?.important ?: false)); scheduleDialog = false
        }
        if (todoDialog && (todoDay != null || todoTaskEdit != null)) TaskDetails(todoTaskEdit, todoTaskEdit?.boardId ?: 0, todoTaskEdit?.plannedDay ?: todoDay, model, { todoDialog = false; todoEdit = null; todoTaskEdit = null; todoDay = null }, todoEdit?.board)
        if (todoDialog && todoDay == null && todoTaskEdit == null) QuickTodoCreateDialog(todoEdit?.copy(items = todoEdit!!.items.filter { it.deletedAt == null }), null, { todoDialog = false; todoEdit = null; todoTaskEdit = null; todoDay = null }) { title, tasks, due, reminder, timeMode ->
            if (reminder != null && android.os.Build.VERSION.SDK_INT >= 33) (context as? android.app.Activity)?.let { activity -> if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4101) }
            model.saveBoardNow(todoEdit, title, tasks, due, reminder, null, timeMode)
        }
        if (imageBusy) SoftDialog("正在应用图片设置", {}) { CircularProgressIndicator(); Text("正在检查图片引用并保存副本，请稍候。") }
        deleting?.let { ids ->
            val targets = ids.flatMap { TreeRules.descendants(it, nodes) }.toSet()
            val affected = nodes.filter { it.id in targets && it.deletedAt == null }
            ConfirmTrashDialog(trashSummary(affected.count { it.kind == "folder" }, affected.count { it.kind != "folder" }, showFolders = affected.any { it.kind != "diary" }),
                onCancel = { deleting = null }, enabled = affected.isNotEmpty(),
                onDelete = { deleting = null
                    if (affected.all { it.kind == "diary" }) model.trash(ids)
                    else model.trash(ids) { group -> scope.launch { val result = snackbar.showSnackbar("已移到回收站", "撤销", duration = SnackbarDuration.Long); if (result == SnackbarResult.ActionPerformed) model.restore(group) } }
                })
        }
        exportIds?.let { ids -> NoteExportDialog(model, ids, { exportIds = null }) }
        if (backup) BackupDialog(model, { backup = false })
        exportTasks?.let { type -> TaskExportDialog(model, type, { exportTasks = null }) }
        if (pendingImport) SoftDialog("导入到待办清单", { pendingImport = false }) { OutlinedTextField(importTitle, { importTitle = it }, label = { Text("清单名称") }); Button(onClick = { pendingImport = false; importDestination = ImportDestination.TODO }) { Text("继续") } }
    }
}
