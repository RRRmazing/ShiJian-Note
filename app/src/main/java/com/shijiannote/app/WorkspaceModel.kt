package com.shijiannote.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.shijiannote.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
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
    private val errors = CoroutineExceptionHandler { _, e -> messages.tryEmit(e.message ?: "操作未完成，请重试") }
    private val actions get() = CoroutineScope(viewModelScope.coroutineContext + errors)
    private val pending = java.util.concurrent.ConcurrentHashMap<String, NoteNode>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val drafts = File(app.filesDir, "drafts").apply { mkdirs() }
    private val gate = kotlinx.coroutines.sync.Mutex()
    private val drainGate = kotlinx.coroutines.sync.Mutex()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            drafts.listFiles().orEmpty().filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }.map { File(it.path.removeSuffix(".bak")) }.distinct().forEach { file ->
                runCatching { decodeNode(JSONObject(android.util.AtomicFile(file).openRead().bufferedReader().use { it.readText() })) }.onSuccess { pending[it.id] = it }
            }
            drain()
            for (ignored in signal) drain()
        }
        viewModelScope.launch { androidx.work.WorkManager.getInstance(app).cancelUniqueWork("daily_todo_rollover") }
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
                        if (old?.deletedAt == null) notes.put(snapshot)
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
    fun save(node: NoteNode) {
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
    fun reorderBoards(ids: List<Long>) = actions.launch { db.withTransaction { ids.forEachIndexed { position, id -> dao.setTodoBoardPosition(id, position) } } }
    fun newNote(kind: String, parent: String? = null, day: Long? = null): NoteNode {
        return NoteNode(id = if (kind == "diary" && day != null) "day-$day" else UUID.randomUUID().toString(), kind = kind, parentId = parent, day = day, position = (nodes.value.filter { it.parentId == parent }.minOfOrNull { it.position } ?: 0) - 1)
    }
    fun createFolder(title: String, parent: String?) { save(newNote("folder", parent).copy(title = title.trim())) }
    fun trash(ids: Set<String>, after: (String) -> Unit = {}) = actions.launch {
        flushAll()
        val current = notes.nodes()
        val targets = ids.flatMap { TreeRules.descendants(it, current) }.toSet()
        val group = UUID.randomUUID().toString()
        val time = System.currentTimeMillis()
        db.withTransaction { current.filter { it.id in targets && it.deletedAt == null }.forEach { notes.put(it.copy(deletedAt = time, deleteGroup = group)) } }
        after(group)
    }
    fun restore(group: String) = actions.launch {
        val all = notes.nodes()
        val returning = all.filter { it.deleteGroup == group }
        val ids = returning.map { it.id }.toSet()
        db.withTransaction { returning.forEach { node ->
            val parent = node.parentId?.let { id -> all.find { it.id == id } }
            val conflict = node.kind == "diary" && all.any { it.kind == "diary" && it.deletedAt == null && it.day == node.day && it.id != node.id }
            notes.put(node.copy(kind = if (conflict) "memory" else node.kind, deletedAt = null, deleteGroup = null, parentId = node.parentId.takeIf { parent != null && (parent.deletedAt == null || parent.id in ids) }))
            if (conflict) messages.tryEmit("当天已有日记，恢复的记录已放到记忆首页")
        } }
    }
    fun permanentlyDelete(group: String) = actions.launch {
        val all = notes.nodes()
        val targets = all.filter { it.deleteGroup == group }.map { it.id }
        db.withTransaction {
            all.filter { it.id !in targets && it.parentId in targets }.forEach { notes.put(it.copy(parentId = null)) }
            notes.remove(targets); notes.removeVersions(targets)
        }
        // Owned media may still be used by another document, a draft, or an older version.
        // Keep it until explicit storage maintenance/backup rather than deleting eagerly.
    }
    fun move(node: NoteNode, destination: NoteNode?) {
        if (!TreeRules.canMove(node, destination, nodes.value)) { messages.tryEmit("不能移动到自身或子分类"); return }
        save(node.copy(parentId = destination?.id))
    }
    fun task(item: TodoItem) = actions.launch {
        dao.updateTodoItem(item)
        if (item.completed || item.deletedAt != null) ReminderScheduler.cancelTodo(getApplication(), item.id) else ReminderScheduler.scheduleTodo(getApplication(), item)
    }
    fun complete(item: TodoItem) = actions.launch {
        var spawned: TodoItem? = null
        db.withTransaction {
        val current = dao.allTodos().flatMap { it.items }.find { it.id == item.id } ?: return@withTransaction
        if (current.completed != item.completed || current.deletedAt != null) return@withTransaction
        val shouldRepeat = !current.completed && current.repeatDays > 0 && !current.repeatSpawned
        dao.updateTodoItem(current.copy(completed = !current.completed, repeatSpawned = current.repeatSpawned || shouldRepeat))
        if (shouldRepeat) {
            val shifted = nextRepeatedTask(current)
            val id = dao.insertTodoItems(listOf(shifted)).single()
            spawned = shifted.copy(id = id)
        }
        }
        if (!item.completed) {
            ReminderScheduler.cancelTodo(getApplication(), item.id)
            spawned?.let { ReminderScheduler.scheduleTodo(getApplication(), it) }
        } else ReminderScheduler.scheduleTodo(getApplication(), item.copy(completed = false))
    }
    fun board(board: TodoBoard) = actions.launch {
        dao.updateTodoBoard(board)
        if (board.archived || board.deletedAt != null) {
            ReminderScheduler.cancelTodoBoard(getApplication(), board.id)
            dao.getTodoItems(board.id).forEach { ReminderScheduler.cancelTodo(getApplication(), it.id) }
        } else {
            ReminderScheduler.scheduleTodoBoard(getApplication(), board)
            dao.getTodoItems(board.id).filter { !it.completed && it.deletedAt == null }.forEach { ReminderScheduler.scheduleTodo(getApplication(), it) }
        }
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
    internal fun saveBoard(existing: TodoBoardWithItems?, title: String, text: List<String>, due: Long?, reminder: BoardReminderDraft?) = actions.launch {
        db.withTransaction {
            val old = existing?.board ?: TodoBoard(summary = "", position = (todos.value.minOfOrNull { it.board.position } ?: 0) - 1)
            val changed = old.copy(summary = title.trim().ifBlank { "未命名清单" }, dueDate = due,
                reminderAt = reminder?.let { if (it.repeatRule == null) due else due ?: it.baseAt },
                reminderDays = reminder?.advanceDays ?: 0, reminderHours = reminder?.advanceHours ?: 0, reminderMinutes = reminder?.advanceMinutes ?: 0,
                reminderRule = reminder?.repeatRule, reminderBaseAt = reminder?.baseAt, reminderCustomDays = reminder?.customDays ?: 0, reminderTriggered = false)
            val id = if (old.id == 0L) dao.insertTodoBoard(changed) else old.id.also { dao.updateTodoBoard(changed) }
            // Preserve identities, completion and reminders when text in a list is edited.
            val currentItems = if (existing != null) dao.getTodoItems(id).filter { it.deletedAt == null } else emptyList()
            val values = text.filter { it.isNotBlank() }
            val matches = matchTaskEdits(currentItems, values)
            values.forEachIndexed { index, value ->
                val matched = matches[index]
                if (matched != null) { dao.updateTodoItem(matched.copy(text = value, position = index)) }
                else dao.insertTodoItems(listOf(TodoItem(boardId = id, text = value, position = index)))
            }
            currentItems.filter { oldItem -> matches.none { it?.id == oldItem.id } }.forEach { dao.updateTodoItem(it.copy(deletedAt = System.currentTimeMillis())); ReminderScheduler.cancelTodo(getApplication(), it.id) }
            ReminderScheduler.cancelTodoBoard(getApplication(), id)
            ReminderScheduler.scheduleTodoBoard(getApplication(), changed.copy(id = id))
        }
    }
}
