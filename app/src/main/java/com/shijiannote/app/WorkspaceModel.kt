package com.shijiannote.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.shijiannote.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

fun dayMillis(date: LocalDate = LocalDate.now()): Long = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

class WorkspaceModel(app: Application) : AndroidViewModel(app) {
    val db = AppDatabase.get(app)
    val dao = db.appDao()
    val notes = db.noteDao()
    val nodes = notes.observeNodes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val todos = dao.observeAllTodos().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val schedules = combine(dao.observeSchedule(), dao.observeArchivedSchedule()) { a, b -> a + b }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val saveStates = MutableStateFlow<Map<String, String>>(emptyMap())
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 20)
    val imageBusy = MutableStateFlow(false)
    val imageFailures = MutableStateFlow<List<ImageFailure>>(emptyList())
    val spacesReady = MutableStateFlow(false)
    val startupError = MutableStateFlow<String?>(null)
    private val replacingWorkspace = java.util.concurrent.atomic.AtomicBoolean(false)
    private val imageGate = kotlinx.coroutines.sync.Mutex()
    private val errors = CoroutineExceptionHandler { _, e -> messages.tryEmit(e.message ?: "操作未完成，请重试") }
    private val actions get() = CoroutineScope(viewModelScope.coroutineContext + errors)
    private val pending = java.util.concurrent.ConcurrentHashMap<String, NoteNode>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val drafts = File(app.filesDir, "drafts").apply { mkdirs() }
    private val gate = kotlinx.coroutines.sync.Mutex()
    private val drainGate = kotlinx.coroutines.sync.Mutex()
    private val todoGate = kotlinx.coroutines.sync.Mutex()
    private val todoGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private fun verifyTodoGeneration(expected: Long) {
        check(!replacingWorkspace.get() && expected == todoGeneration.get()) { "备份已恢复，请重新打开待办后操作" }
    }
    private fun todoAction(block: suspend () -> Unit): Job {
        val expected = todoGeneration.get()
        return actions.launch { todoGate.withLock { verifyTodoGeneration(expected); block() } }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            drafts.listFiles().orEmpty().filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }.map { File(it.path.removeSuffix(".bak")) }.distinct().forEach { file ->
                runCatching { decodeNode(JSONObject(android.util.AtomicFile(file).openRead().bufferedReader().use { it.readText() })) }.onSuccess { pending.putIfAbsent(it.id, it) }
            }
            suspend fun prepare() {
                runCatching { drain(); ensureMemorySpaces() }
                    .onSuccess { startupError.value = null; spacesReady.value = true }
                    .onFailure { startupError.value = it.message ?: "数据整理未完成，请重试" }
            }
            prepare()
            for (ignored in signal) { if (spacesReady.value) drain() else prepare() }
        }
        todoAction {
            androidx.work.WorkManager.getInstance(app).cancelUniqueWork("daily_todo_rollover")
            val now = System.currentTimeMillis()
            dao.allTodos().forEach { entry ->
                if (entry.board.reminderSkipAt?.let { it <= now } == true) dao.updateTodoBoard(entry.board.copy(reminderSkipAt = null))
                entry.items.filter { it.reminderSkipAt?.let { at -> at <= now } == true }.forEach { dao.updateTodoItem(it.copy(reminderSkipAt = null)) }
                ReminderScheduler.rescheduleTodoBoard(app, entry.board.id)
            }
        }
    }
    private suspend fun drain() = withContext(Dispatchers.IO) {
        drainGate.lock()
        try {
        for ((id, snapshot) in pending.toMap()) {
            runCatching {
                val atomic = android.util.AtomicFile(File(drafts, "$id.json"))
                val stream = atomic.startWrite()
                try { stream.write(jsonObject(snapshot).toString().toByteArray()); atomic.finishWrite(stream) }
                catch (e: Exception) { atomic.failWrite(stream); throw e }
                gate.lock()
                try {
                    db.withTransaction {
                        val old = notes.node(id)
                        if (old != null && (old.document != snapshot.document || old.title != snapshot.title || old.text != snapshot.text)) {
                            val last = notes.versions(id).firstOrNull()
                            if (last == null || System.currentTimeMillis() - last.createdAt > 30_000) notes.version(NoteVersion(nodeId = id, snapshot = jsonObject(old).toString()))
                        }
                        if (old?.deletedAt == null) notes.put(if (snapshot.kind != "diary" && snapshot.parentId == null && !MemorySpaces.isRoot(snapshot.id)) snapshot.copy(parentId = MemorySpaces.WORK_ID) else snapshot)
                    }
                } finally { gate.unlock() }
                if (pending.remove(id, snapshot)) {
                    atomic.delete()
                    saveStates.update { it + (id to "已保存") }
                }
            }.onFailure {
                saveStates.update { states -> states + (id to "保存失败 · 点击重试") }
            }
        }
        } finally { drainGate.unlock() }
    }
    @Synchronized fun save(node: NoteNode) {
        check(!replacingWorkspace.get()) { "正在恢复备份，请稍后再保存" }
        pending[node.id] = node.copy(updatedAt = System.currentTimeMillis())
        saveStates.update { it + (node.id to "保存中") }
        signal.trySend(Unit)
    }
    suspend fun flush(id: String) {
        repeat(4) { if (pending[id] != null) drain() }
        check(pending[id] == null) { "尚未保存，请重试" }
    }
    suspend fun flushAll() {
        repeat(4) { if (pending.isNotEmpty()) drain() }
        check(pending.isEmpty()) { "仍有内容未保存，请重试" }
    }
    fun retry() { signal.trySend(Unit) }
    suspend fun ensureMemorySpaces() {
        flushAll()
        gate.lock()
        try { db.withTransaction {
            val current = notes.nodes()
            val previous = current.associateBy { it.id }
            notes.putAll(MemorySpaces.normalize(current).filter { previous[it.id] != it })
        } } finally { gate.unlock() }
    }
    /** A restore cannot race the draft drain and resurrect the replaced workspace. */
    suspend fun replaceWorkspace(replace: suspend () -> Unit) = withContext(Dispatchers.IO) {
        synchronized(this@WorkspaceModel) { check(replacingWorkspace.compareAndSet(false, true)) { "正在恢复另一份备份" } }
        todoGeneration.incrementAndGet()
        try {
            todoGate.lock()
            try {
                flushAll()
                drainGate.lock()
                try {
                    gate.lock()
                    try {
                        check(pending.isEmpty()) { "还有内容正在保存，请重试恢复" }
                        db.withTransaction { replace() }
                    } finally { gate.unlock() }
                } finally { drainGate.unlock() }
            } finally { todoGate.unlock() }
        } finally { replacingWorkspace.set(false) }
    }
    fun reorderBoards(ids: List<Long>) = todoAction { db.withTransaction { ids.forEachIndexed { position, id -> dao.setTodoBoardPosition(id, position) } } }
    fun newNote(kind: String, parent: String? = null, day: Long? = null): NoteNode {
        val destination = if (kind == "diary") null else parent ?: MemorySpaces.WORK_ID
        return NoteNode(id = if (kind == "diary" && day != null) "day-$day" else UUID.randomUUID().toString(), kind = kind, parentId = destination, day = day, position = (nodes.value.filter { it.parentId == destination }.minOfOrNull { it.position } ?: 0) - 1)
    }
    fun createFolder(title: String, parent: String?) { save(newNote("folder", parent).copy(title = title.trim())) }
    fun trash(ids: Set<String>, after: (String) -> Unit = {}) = actions.launch {
        flushAll()
        val current = notes.nodes()
        val targets = ids.filterNot(MemorySpaces::isRoot).flatMap { TreeRules.descendants(it, current) }.filterNot(MemorySpaces::isRoot).toSet()
        val group = UUID.randomUUID().toString()
        val time = System.currentTimeMillis()
        db.withTransaction { current.filter { it.id in targets && it.deletedAt == null }.forEach { notes.put(it.copy(deletedAt = time, deleteGroup = group)) } }
        after(group)
    }
    fun restore(group: String?, ids: Set<String>? = null) = actions.launch {
        flushAll()
        ensureMemorySpaces()
        val all = notes.nodes()
        val returning = all.filter { it.deletedAt != null && (if (ids == null) it.deleteGroup == group else it.id in ids) && !MemorySpaces.isRoot(it.id) }
        val returningIds = returning.map { it.id }.toSet()
        db.withTransaction { returning.forEach { node ->
            val parent = node.parentId?.let { id -> all.find { it.id == id } }
            val conflict = node.kind == "diary" && all.any { it.kind == "diary" && it.deletedAt == null && it.day == node.day && it.id != node.id }
            val destination = if (conflict) MemorySpaces.WORK_ID else if (node.kind == "diary") null else
                node.parentId.takeIf { parent != null && (parent.deletedAt == null || parent.id in returningIds) } ?: MemorySpaces.rootId(node, all)
            notes.put(node.copy(kind = if (conflict) "memory" else node.kind, deletedAt = null, deleteGroup = null, parentId = destination))
            if (conflict) messages.tryEmit("当天已有日记，恢复的记录已放到记忆 / 工作")
        } }
    }
    fun permanentlyDelete(group: String?, ids: Set<String>? = null) = actions.launch {
        flushAll()
        val all = notes.nodes()
        val targets = all.filter { it.deletedAt != null && (if (ids == null) it.deleteGroup == group else it.id in ids) && !MemorySpaces.isRoot(it.id) }.map { it.id }
        db.withTransaction {
            all.filter { it.id !in targets && it.parentId in targets }.forEach { notes.put(it.copy(parentId = if (it.kind == "diary") null else MemorySpaces.rootId(it, all))) }
            notes.remove(targets); notes.removeVersions(targets)
        }
        // Owned media may still be used by another document, a draft, or an older version.
        // Keep it until explicit storage maintenance/backup rather than deleting eagerly.
    }
    fun move(node: NoteNode, destination: NoteNode?) = moveMany(setOf(node.id), destination?.id)

    private fun imageOperation(action: suspend () -> Unit) = actions.launch {
        imageGate.lock()
        imageBusy.value = true
        try { withContext(Dispatchers.IO) { action() } }
        finally { imageBusy.value = false; imageGate.unlock() }
    }

    fun updateImageSettings(node: NoteNode, after: (NoteNode) -> Unit = {}) = imageOperation {
        flushAll()
        gate.lock()
        try {
            val all = notes.nodes()
            val old = all.find { it.id == node.id } ?: node
            val source = TreeRules.forcedBy(old, all)
            val updated = old.copy(title = node.title, tags = node.tags, mood = node.mood,
                imageDisplay = if (source == null) node.imageDisplay else old.imageDisplay,
                imageStorage = if (source == null) node.imageStorage else old.imageStorage,
                forceChildren = if (source == null) node.forceChildren else old.forceChildren,
                updatedAt = System.currentTimeMillis())
            val next = all.filter { it.id != updated.id } + updated
            val targets = TreeRules.descendants(updated.id, next)
            val result = reconcileImages(next, targets)
            persistImageChanges(all, result.nodes)
            imageFailures.value = result.failures
            withContext(Dispatchers.Main) { after(result.nodes.first { it.id == updated.id }) }
        } finally { gate.unlock() }
    }

    fun moveMany(ids: Set<String>, destinationId: String?, after: (List<NoteNode>) -> Unit = {}) = imageOperation {
        flushAll()
        gate.lock()
        try {
            val all = notes.nodes()
            val destination = destinationId?.let { id -> all.find { it.id == id } }
            require(destinationId != null && destination != null) { "请选择工作、生活或其中的分类" }
            val roots = TreeRules.topLevel(ids, all)
            require(roots.isNotEmpty() && roots.all { !MemorySpaces.isRoot(it.id) && it.kind != "diary" && TreeRules.canMove(it, destination, all) }) { "不能移动收纳入口、日记或移动到自身及子分类" }
            val rootIds = roots.map { it.id }.toSet()
            val targets = roots.flatMap { TreeRules.descendants(it.id, all) }.toSet()
            val start = (all.filter { it.parentId == destinationId }.minOfOrNull { it.position } ?: 0) - roots.size
            val positions = roots.mapIndexed { index, root -> root.id to start + index }.toMap()
            val next = all.map { if (it.id in rootIds) it.copy(parentId = destinationId, position = positions.getValue(it.id), updatedAt = System.currentTimeMillis()) else it }
            val result = reconcileImages(next, targets)
            persistImageChanges(all, result.nodes)
            imageFailures.value = result.failures
            withContext(Dispatchers.Main) { after(result.nodes.filter { it.id in rootIds }) }
        } finally { gate.unlock() }
    }

    fun retryImages() = imageOperation {
        flushAll()
        gate.lock()
        try {
            val all = notes.nodes()
            val result = reconcileImages(all, imageFailures.value.map { it.nodeId }.toSet())
            persistImageChanges(all, result.nodes)
            imageFailures.value = result.failures
        } finally { gate.unlock() }
    }

    private data class ImageReconciliation(val nodes: List<NoteNode>, val failures: List<ImageFailure>)
    private suspend fun reconcileImages(all: List<NoteNode>, targets: Set<String>): ImageReconciliation {
        val context = getApplication<Application>()
        val failures = mutableListOf<ImageFailure>()
        val copies = mutableMapOf<String, NoteBlock>()
        val result = all.map { node ->
            if (node.id !in targets || node.deletedAt != null || node.kind == "folder" ||
                TreeRules.imagePreference(node, all, true, imageStorageDefault(context)) != "copy") return@map node
            val blocks = node.blocks().map { block ->
                if (block.type != "image" || block.owned) block
                else runCatching {
                    val copied = copies[block.uri] ?: copyImage(context, block).also { copies[block.uri] = it }
                    block.copy(uri = copied.uri, owned = true, bytes = copied.bytes)
                }.getOrElse {
                    failures += ImageFailure(node.id, block.id, TreeRules.path(node, all), block.text, block.uri, it.message ?: "无法读取原图片")
                    block
                }
            }
            if (blocks == node.blocks()) node else node.copy(document = encodeBlocks(blocks), text = blockPlainText(blocks), updatedAt = System.currentTimeMillis())
        }
        return ImageReconciliation(result, failures)
    }

    private suspend fun persistImageChanges(old: List<NoteNode>, next: List<NoteNode>) {
        val previous = old.associateBy { it.id }
        db.withTransaction {
            next.filter { previous[it.id] != it }.forEach { node ->
                previous[node.id]?.takeIf { it.document != node.document }?.let { notes.version(NoteVersion(nodeId = node.id, snapshot = jsonObject(it).toString())) }
                notes.put(node)
            }
        }
    }

    fun collapseBoards(ids: List<Long>) = todoAction {
        dao.setTodoBoardsExpanded(ids, false)
    }
    fun task(item: TodoItem): Job {
        val expected = todoGeneration.get()
        return actions.launch { saveTaskNow(item, expected) }
    }
    fun createDailyTask(item: TodoItem) = task(item)

    suspend fun saveTaskNow(item: TodoItem): TodoItem = saveTaskNow(item, todoGeneration.get())
    private suspend fun saveTaskNow(item: TodoItem, expected: Long): TodoItem = todoGate.withLock {
        verifyTodoGeneration(expected)
        require(item.text.isNotBlank()) { "请输入事项内容" }
        var saved = item
        var needsScheduling = true
        db.withTransaction {
            val board = if (item.boardId == 0L) {
                require(item.plannedDay != null) { "请选择事项所属日期" }
                dao.findDailyBoard("DAILY")?.takeIf { it.deletedAt == null && it.timeMode == "INDEPENDENT" }
                    ?: TodoBoard(summary = "当天事项", boardType = "DAILY", timeMode = "INDEPENDENT").let { it.copy(id = dao.insertTodoBoard(it)) }
            } else requireNotNull(dao.getTodoBoard(item.boardId)) { "待办框已不存在" }
            val current = if (item.id == 0L) null else requireNotNull(dao.getTodoItem(item.id)) { "事项已不存在" }
            val independent = !isUnifiedBoard(board)
            val previouslyActive = if (!independent) dao.getTodoItems(board.id).any { !it.completed && it.deletedAt == null } else false
            val reminderChanged = current == null || current.reminderAt != item.reminderAt || current.reminderBaseAt != item.reminderBaseAt || current.reminderRule != item.reminderRule || current.reminderCustomDays != item.reminderCustomDays
            saved = item.copy(boardId = board.id, text = item.text.trim(),
                position = if (item.id == 0L) dao.nextTodoPosition(board.id) else item.position,
                dueAt = if (!independent) null else item.dueAt ?: item.plannedDay?.let { planDeadline(it) },
                reminderAt = if (independent) item.reminderAt else null,
                reminderRule = if (independent) item.reminderRule else null,
                reminderBaseAt = if (independent) item.reminderBaseAt else null,
                reminderCustomDays = if (independent) item.reminderCustomDays else 0,
                reminderSkipAt = if (!independent || reminderChanged) null else item.reminderSkipAt,
                reminderTriggered = if (reminderChanged) false else item.reminderTriggered,
                repeatDays = 0, repeatSpawned = false)
            needsScheduling = current == null || current.completed != saved.completed || current.deletedAt != saved.deletedAt ||
                current.dueAt != saved.dueAt || current.plannedDay != saved.plannedDay || current.reminderAt != saved.reminderAt ||
                current.reminderRule != saved.reminderRule || current.reminderBaseAt != saved.reminderBaseAt ||
                current.reminderCustomDays != saved.reminderCustomDays || current.reminderSkipAt != saved.reminderSkipAt ||
                current.reminderHours != saved.reminderHours || current.reminderMinutes != saved.reminderMinutes
            if (saved.id == 0L) saved = saved.copy(id = dao.insertTodoItems(listOf(saved)).single())
            else dao.updateTodoItem(saved)
            if (!independent) needsScheduling = previouslyActive != dao.getTodoItems(board.id).any { !it.completed && it.deletedAt == null }
        }
        if (needsScheduling) ReminderScheduler.rescheduleTodo(getApplication(), saved.id)
        saved
    }

    fun complete(item: TodoItem) = todoAction {
        var needsScheduling = false
        db.withTransaction {
            val current = dao.getTodoItem(item.id) ?: return@withTransaction
            if (current.completed != item.completed || current.deletedAt != null) return@withTransaction
            val board = dao.getTodoBoard(current.boardId) ?: return@withTransaction
            val previouslyActive = dao.getTodoItems(board.id).any { !it.completed && it.deletedAt == null }
            dao.updateTodoItem(current.copy(completed = !current.completed, repeatDays = 0, repeatSpawned = false))
            needsScheduling = !isUnifiedBoard(board) || previouslyActive != dao.getTodoItems(board.id).any { !it.completed && it.deletedAt == null }
        }
        if (needsScheduling) ReminderScheduler.rescheduleTodo(getApplication(), item.id)
    }

    fun board(board: TodoBoard) = todoAction {
        val current = dao.getTodoBoard(board.id) ?: return@todoAction
        require(board.timeMode == current.timeMode) { "待办框创建后不能切换时间类型" }
        dao.updateTodoBoard(board)
        if (current.archived != board.archived || current.deletedAt != board.deletedAt || current.dueDate != board.dueDate ||
            current.reminderAt != board.reminderAt || current.reminderRule != board.reminderRule || current.reminderBaseAt != board.reminderBaseAt ||
            current.reminderCustomDays != board.reminderCustomDays || current.reminderSkipAt != board.reminderSkipAt ||
            current.reminderDays != board.reminderDays || current.reminderHours != board.reminderHours || current.reminderMinutes != board.reminderMinutes)
            ReminderScheduler.rescheduleTodoBoard(getApplication(), board.id)
    }

    fun skipBoardReminder(board: TodoBoard) = changeBoardReminder(board.id) { current ->
        if (current.reminderRule == null) current else current.copy(reminderSkipAt = nextBoardReminderAt(current.copy(reminderSkipAt = null)))
    }
    fun restoreBoardReminder(board: TodoBoard) = changeBoardReminder(board.id) { it.copy(reminderSkipAt = null) }
    fun closeBoardRepeat(board: TodoBoard) = changeBoardReminder(board.id) {
        it.copy(reminderAt = it.reminderBaseAt ?: it.reminderAt, reminderRule = null, reminderBaseAt = null, reminderCustomDays = 0,
            reminderSkipAt = null, reminderDays = 0, reminderHours = 0, reminderMinutes = 0, reminderTriggered = false)
    }
    private fun changeBoardReminder(id: Long, change: (TodoBoard) -> TodoBoard) = todoAction {
        val current = dao.getTodoBoard(id) ?: return@todoAction
        dao.updateTodoBoard(change(current))
        ReminderScheduler.rescheduleTodoBoard(getApplication(), id)
    }
    fun skipTaskReminder(item: TodoItem) = changeTaskReminder(item.id) { current, board ->
        if (current.reminderRule == null) current else current.copy(reminderSkipAt = nextTaskReminderAt(current.copy(reminderSkipAt = null), board))
    }
    fun restoreTaskReminder(item: TodoItem) = changeTaskReminder(item.id) { current, _ -> current.copy(reminderSkipAt = null) }
    fun closeTaskRepeat(item: TodoItem) = changeTaskReminder(item.id) { current, _ ->
        current.copy(reminderAt = current.reminderBaseAt ?: current.reminderAt, reminderRule = null, reminderBaseAt = null, reminderCustomDays = 0,
            reminderSkipAt = null, reminderHours = 0, reminderMinutes = 0, reminderTriggered = false, repeatDays = 0, repeatSpawned = false)
    }
    private fun changeTaskReminder(id: Long, change: (TodoItem, TodoBoard) -> TodoItem) = todoAction {
        val current = dao.getTodoItem(id) ?: return@todoAction
        val board = dao.getTodoBoard(current.boardId) ?: return@todoAction
        dao.updateTodoItem(change(current, board))
        ReminderScheduler.rescheduleTodo(getApplication(), current.id)
    }
    fun schedule(event: ScheduleEvent) = actions.launch {
        if (event.id == 0L) {
            val id = dao.insertSchedule(event)
            ReminderScheduler.schedule(getApplication(), event.copy(id = id))
        } else {
            dao.updateSchedule(event)
            ReminderScheduler.cancel(getApplication(), event.id)
            if (!event.archived && event.deletedAt == null) ReminderScheduler.schedule(getApplication(), event)
        }
    }
    fun toggleScheduleImportant(id: Long) = actions.launch { dao.toggleScheduleImportant(id) }
    fun trashSchedules(ids: Set<Long>) = actions.launch {
        dao.trashSchedules(ids.toList(), System.currentTimeMillis())
        ids.forEach { ReminderScheduler.cancel(getApplication(), it) }
    }
    internal fun saveBoard(existing: TodoBoardWithItems?, title: String, text: List<String>, due: Long?, reminder: BoardReminderDraft?, plannedDay: Long? = null, timeMode: String = "UNIFIED"): Job {
        val expected = todoGeneration.get()
        return actions.launch { saveBoardNow(existing, title, text, due, reminder, plannedDay, timeMode, expected) }
    }
    internal suspend fun saveBoardNow(existing: TodoBoardWithItems?, title: String, text: List<String>, due: Long?, reminder: BoardReminderDraft?, plannedDay: Long? = null, timeMode: String = "UNIFIED", expected: Long = todoGeneration.get()) = todoGate.withLock {
        verifyTodoGeneration(expected)
        require(timeMode == "UNIFIED" || timeMode == "INDEPENDENT") { "请选择时间类型" }
        var boardId = 0L
        var needsBoardScheduling = false
        val deletedTaskIds = mutableListOf<Long>()
        db.withTransaction {
            val old = existing?.board?.let { requireNotNull(dao.getTodoBoard(it.id)) { "待办框已不存在" } }
                ?: TodoBoard(summary = "", position = dao.nextTodoBoardPosition(), timeMode = timeMode)
            require(old.timeMode == timeMode) { "待办框创建后不能切换时间类型" }
            val unified = timeMode == "UNIFIED"
            val draft = reminder.takeIf { unified }
            val reminderAt = draft?.let { if (it.repeatRule == null) it.singleAt ?: it.baseAt ?: due else it.baseAt }
            val reminderChanged = old.reminderAt != reminderAt || old.reminderRule != draft?.repeatRule || old.reminderBaseAt != draft?.baseAt || old.reminderCustomDays != (draft?.customDays ?: 0) ||
                old.reminderDays != (draft?.advanceDays ?: 0) || old.reminderHours != (draft?.advanceHours ?: 0) || old.reminderMinutes != (draft?.advanceMinutes ?: 0)
            val changed = old.copy(summary = title.trim().ifBlank { "未命名清单" }, dueDate = due.takeIf { unified }, timeMode = timeMode,
                reminderAt = reminderAt, reminderDays = draft?.advanceDays ?: 0, reminderHours = draft?.advanceHours ?: 0, reminderMinutes = draft?.advanceMinutes ?: 0,
                reminderRule = draft?.repeatRule, reminderBaseAt = draft?.baseAt, reminderCustomDays = draft?.customDays ?: 0,
                reminderSkipAt = if (reminderChanged) null else old.reminderSkipAt, reminderTriggered = if (reminderChanged) false else old.reminderTriggered)
            boardId = if (old.id == 0L) dao.insertTodoBoard(changed) else old.id.also { dao.updateTodoBoard(changed) }
            val currentItems = if (existing != null) dao.getTodoItems(boardId).filter { it.deletedAt == null } else emptyList()
            val values = text.filter { it.isNotBlank() }
            val matches = matchTaskEdits(currentItems, values)
            values.forEachIndexed { index, value ->
                val matched = matches[index]
                if (matched != null) dao.updateTodoItem(matched.copy(text = value, position = index))
                else dao.insertTodoItems(listOf(TodoItem(boardId = boardId, text = value, position = index, plannedDay = plannedDay,
                    dueAt = if (!unified && plannedDay != null) due ?: planDeadline(plannedDay) else null)))
            }
            currentItems.filter { oldItem -> matches.none { it?.id == oldItem.id } }.forEach {
                dao.updateTodoItem(it.copy(deletedAt = System.currentTimeMillis()))
                deletedTaskIds += it.id
            }
            val previouslyActive = currentItems.any { !it.completed }
            val currentlyActive = dao.getTodoItems(boardId).any { !it.completed && it.deletedAt == null }
            needsBoardScheduling = old.id == 0L || reminderChanged || old.dueDate != changed.dueDate || (unified && previouslyActive != currentlyActive)
        }
        deletedTaskIds.forEach { ReminderScheduler.cancelTodo(getApplication(), it) }
        if (needsBoardScheduling) ReminderScheduler.rescheduleTodoBoard(getApplication(), boardId)
    }
}
