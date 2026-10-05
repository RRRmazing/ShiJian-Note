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
    val diaryRevision = MutableStateFlow(0L)
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
    // Bridge the brief gap between a draft being committed and Room's next flow emission.
    private val diarySnapshots = java.util.concurrent.ConcurrentHashMap<String, NoteNode>()
    private val momentParents = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val drafts = File(app.filesDir, "drafts").apply { mkdirs() }
    private val gate = kotlinx.coroutines.sync.Mutex()
    private val drainGate = kotlinx.coroutines.sync.Mutex()
    private val todoGate = kotlinx.coroutines.sync.Mutex()
    private val diaryPublishGate = kotlinx.coroutines.sync.Mutex()
    private val todoGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private fun verifyTodoGeneration(expected: Long) {
        check(!replacingWorkspace.get() && expected == todoGeneration.get()) { "备份已恢复，请重新打开待办后操作" }
    }
    private fun todoAction(block: suspend () -> Unit): Job {
        val expected = todoGeneration.get()
        return actions.launch { todoGate.withLock { verifyTodoGeneration(expected); block() } }
    }

    init {
        viewModelScope.launch {
            nodes.collect { current -> current.forEach { node ->
                diarySnapshots[node.id]?.takeIf { node.updatedAt >= it.updatedAt }?.let { diarySnapshots.remove(node.id, it) }
            } }
        }
        viewModelScope.launch(Dispatchers.IO) {
            drafts.listFiles().orEmpty().filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }.map { File(it.path.removeSuffix(".bak")) }.distinct().forEach { file ->
                runCatching { decodeNode(JSONObject(android.util.AtomicFile(file).openRead().bufferedReader().use { it.readText() })) }.onSuccess { pending.putIfAbsent(it.id, it) }
            }
            suspend fun prepare() {
                runCatching { drain(); recoverDiaryRecordings(); ensureMemorySpaces(); cleanupExplicitDiaryRetention() }
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
                        if (old != null && (old.document != snapshot.document || old.title != snapshot.title || old.text != snapshot.text || old.diaryRoad != snapshot.diaryRoad || old.diaryRoadTheme != snapshot.diaryRoadTheme || old.diaryRoadBackground != snapshot.diaryRoadBackground || old.diaryInbox != snapshot.diaryInbox || old.diaryRoadEnabled != snapshot.diaryRoadEnabled || old.diaryRoadLayout != snapshot.diaryRoadLayout)) {
                            val last = notes.versions(id).firstOrNull()
                            if (last == null || System.currentTimeMillis() - last.createdAt > 30_000) notes.version(NoteVersion(nodeId = id, snapshot = jsonObject(old).toString()))
                        }
                        val liveDay = if (snapshot.kind == "diary" && (old == null || old.deletedAt != null) && snapshot.day != null) notes.diary(snapshot.day) else null
                        val removedDiary = snapshot.kind == "diary" && (old?.deletedAt != null || diarySnapshots[id]?.deletedAt != null || liveDay != null && liveDay.id != id)
                        if (removedDiary) {
                            // An editor can autosave while trash/restore is awaiting Room. Never consume
                            // that late draft as a successful write to a removed parent.
                            if (old?.deletedAt != null) diarySnapshots[id] = old
                            else if (liveDay != null && liveDay.id != id) diarySnapshots[id] = snapshot.copy(deletedAt = System.currentTimeMillis())
                            if (liveDay != null) diarySnapshots.putIfAbsent(liveDay.id, liveDay)
                            val recoveryOwner = synchronized(this@WorkspaceModel) { currentDiary(snapshot.day ?: error("日记缺少所属日期")) }
                            notes.version(NoteVersion(nodeId = recoveryOwner.id, snapshot = jsonObject(snapshot).toString()))
                            synchronized(this@WorkspaceModel) {
                                val destination = currentDiary(snapshot.day ?: error("日记缺少所属日期"))
                                val saved = destination.diaryInboxItems()
                                val incoming = snapshot.diaryInboxItems().map { item ->
                                    val original = item.moment
                                    val moved = if (item.originalStatus == "editing" && original != null) saved.firstOrNull {
                                        it.originalStatus != "editing" && it.status == "draft" && it.editingOf == original.id
                                    } else null
                                    if (moved != null) item.copy(id = moved.id, originalStatus = "draft", editingOf = original!!.id,
                                        moment = original.copy(id = moved.moment?.id ?: moved.id), recordingOwner = moved.recordingOwner.ifBlank { item.recordingOwner })
                                    else item
                                }
                                val detached = snapshot.copy(diaryInbox = encodeDiaryInbox(incoming)).detachDiaryEdits(
                                    incoming.filter { it.originalStatus == "editing" }.mapNotNull { it.moment?.id }.toSet())
                                val retained = mergeDiaryInboxPreservingConflicts(saved, detached.diaryInboxItems())
                                if (retained != saved) {
                                    enqueueDiary(destination.copy(diaryInbox = encodeDiaryInbox(retained)))
                                    retained.mapNotNull { it.moment }.forEach { moment -> momentParents["moment-${moment.id}"] = destination.id }
                                }
                            }
                        } else if (old?.deletedAt == null) {
                            notes.put(if (snapshot.kind != "diary" && snapshot.parentId == null && !MemorySpaces.isRoot(snapshot.id)) snapshot.copy(parentId = MemorySpaces.WORK_ID) else snapshot)
                        }
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
        if (node.kind == "diary_moment") {
            val parent = findDiary(node.day ?: error("片段缺少所属日期")) ?: newNote("diary", day = node.day).let {
                if (node.parentId != null && (nodes.value + diarySnapshots.values).none { existing -> existing.id == node.parentId && existing.deletedAt != null }) it.copy(id = node.parentId) else it
            }
            momentParents[node.id] = parent.id
            val published = parent.diaryMoments().firstOrNull { it.id == node.id.removePrefix("moment-") }
            if (published != null) {
                val edited = node.asDiaryMoment().copy(createdAt = published.createdAt, sentAt = published.sentAt,
                    occurredAt = node.diaryOccurredAt ?: published.sentAt)
                enqueueDiary(parent.copy(diaryRoad = encodeDiaryMoments(parent.diaryMoments().map { if (it.id == edited.id) edited else it })))
            } else saveDiaryDraft(parent, node.asDiaryMoment())
            return
        }
        val next = if (node.kind == "diary") {
            val current = findDiary(node.day ?: error("日记缺少所属日期"))
            if (current == null) node.copy(id = if ((nodes.value + diarySnapshots.values).any { it.id == node.id && it.deletedAt != null }) UUID.randomUUID().toString() else node.id)
            else node.copy(id = current.id, favorite = current.favorite, diaryRoad = current.diaryRoad, diaryRoadTheme = current.diaryRoadTheme,
                diaryRoadBackground = current.diaryRoadBackground, diaryRoadEnabled = current.diaryRoadEnabled, diaryRoadLayout = current.diaryRoadLayout,
                diaryInbox = current.diaryInbox, diaryTrashExpiresAt = current.diaryTrashExpiresAt, createdAt = current.createdAt)
        } else if (node.kind == "memory") node.flattenDiaryRoad() else node
        if (next.kind == "diary") { enqueueDiary(next); return }
        enqueue(next)
    }
    private fun enqueue(node: NoteNode) {
        val next = node.copy(updatedAt = maxOf(System.currentTimeMillis(), node.updatedAt + 1,
            (pending[node.id]?.updatedAt ?: 0) + 1, (diarySnapshots[node.id]?.updatedAt ?: 0) + 1))
        pending[next.id] = next
        if (next.kind == "diary") { diarySnapshots[next.id] = next; diaryRevision.update { it + 1 } }
        saveStates.update { it + (next.id to "保存中") }
        signal.trySend(Unit)
    }
    private fun enqueueDiary(node: NoteNode) {
        enqueue(node) // Empty date state and inbox are durable; the library hides dates with no published content.
    }
    /** Favorite belongs to the dated diary, independently of summary edits and road drafts. */
    @Synchronized fun toggleDiaryFavorite(parent: NoteNode) {
        check(!replacingWorkspace.get()) { "正在恢复备份，请稍后再收藏" }
        require(parent.kind == "diary")
        val current = diaryParent(parent)
        enqueueDiary(current.copy(favorite = !current.favorite))
    }
    @Synchronized fun setDiaryFavorites(ids: Set<String>, favorite: Boolean) {
        check(!replacingWorkspace.get()) { "正在恢复备份，请稍后再收藏" }
        (nodes.value + diarySnapshots.values + pending.values).filter { it.id in ids }
            .groupBy { it.id }.values.map { versions -> versions.maxBy { it.updatedAt } }
            .filter { it.kind == "diary" && it.deletedAt == null && it.favorite != favorite }
            .forEach { enqueueDiary(it.copy(favorite = favorite)) }
    }
    @Synchronized fun rebindResource(source: NoteNode, location: MediaLocation, expectedUri: String, replacement: NoteBlock) {
        check(!replacingWorkspace.get()) { "正在恢复备份，请稍后重新绑定" }
        val current = if (source.kind == "diary") findDiary(source.day ?: error("日记缺少日期"))
            else pending[source.id] ?: nodes.value.firstOrNull { it.id == source.id }
        val latest = current ?: source
        check(latest.deletedAt == null) { "原记录已经删除" }
        check(mediaReferenceIndex(listOf(latest)).any { reference -> reference.block.uri == expectedUri && reference.locations.any {
            it.blockId == location.blockId && it.momentId == location.momentId && it.inboxId == location.inboxId
        } }) { "原素材已经变化，请重新检查后绑定" }
        enqueue(replaceResourceAtLocation(latest, location, replacement))
    }
    private fun findDiary(day: Long): NoteNode? {
        val normalized = dayMillis(java.time.Instant.ofEpochMilli(day).atZone(ZoneId.systemDefault()).toLocalDate())
        return (nodes.value + diarySnapshots.values + pending.values).filter { node ->
            node.kind == "diary" && node.deletedAt == null && diarySnapshots[node.id]?.deletedAt == null && node.day?.let {
                dayMillis(java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()) == normalized
            } == true
        }.maxByOrNull { it.updatedAt }
    }
    @Synchronized fun currentDiary(day: Long): NoteNode {
        return findDiary(day) ?: newNote("diary", day = dayMillis(java.time.Instant.ofEpochMilli(day).atZone(ZoneId.systemDefault()).toLocalDate()))
    }
    @Synchronized fun appendDiaryMoment(parent: NoteNode, moment: DiaryMoment) {
        stageDiaryPublication(diaryParent(parent), moment, moment.occurredAt)
    }
    private fun diaryParent(parent: NoteNode): NoteNode = findDiary(parent.day ?: error("日记缺少所属日期"))
        ?: if (parent.deletedAt == null) parent else newNote("diary", day = parent.day)
    @Synchronized fun getDiaryMomentNote(day: Long, id: String): NoteNode? {
        val parent = currentDiary(day)
        val key = id.removePrefix("moment-")
        return parent.diaryInboxItems().firstOrNull { it.originalStatus == "editing" && it.status == "draft" && it.moment?.id == key }?.asNote(parent)
            ?: parent.diaryInboxItems().firstOrNull { it.editingOf == key && it.originalStatus != "editing" && it.status == "draft" }?.asNote(parent)
            ?: parent.diaryMoments().firstOrNull { it.id == key }?.asNote(parent)
            ?: parent.diaryInboxItems().firstOrNull { it.id == key || it.moment?.id == key || it.editingOf == key }?.asNote(parent)
    }
    @Synchronized fun beginDiaryMomentEdit(parent: NoteNode, momentId: String): NoteNode? {
        check(!replacingWorkspace.get()) { "正在恢复备份，请稍后再编辑" }
        val current = diaryParent(parent)
        val key = momentId.removePrefix("moment-")
        val existing = current.diaryInboxItems().firstOrNull { it.originalStatus == "editing" && it.status == "draft" && it.moment?.id == key }
        if (existing != null) return existing.asNote(current)
        val original = current.diaryMoments().firstOrNull { it.id == key } ?: return null
        val preferred = "editing-$key"
        val id = if (current.diaryInboxItems().any { it.id == preferred }) "$preferred-${UUID.randomUUID()}" else preferred
        val item = DiaryInboxItem(id = id, originalStatus = "editing", moment = original, editingOf = original.id,
            recordingOwner = "edit-$id", originalMoment = jsonObject(original).toString())
        enqueueDiary(current.copy(diaryInbox = encodeDiaryInbox(current.diaryInboxItems() + item)))
        momentParents["moment-$key"] = current.id
        return original.asNote(current)
    }
    /** Opening an editor creates a durable edit identity; closing unchanged removes only that identity. */
    @Synchronized fun cancelUnchangedDiaryMomentEdit(parent: NoteNode, moment: DiaryMoment): Boolean {
        check(!replacingWorkspace.get()) { "正在恢复备份，请稍后再返回" }
        val current = diaryParent(parent)
        val original = current.diaryMoments().firstOrNull { it.id == moment.id } ?: return false
        val edit = current.diaryInboxItems().firstOrNull {
            it.status == "draft" && it.originalStatus == "editing" && it.moment?.id == moment.id
        }
        val baseline = edit?.originalMoment?.takeIf { it.isNotBlank() }?.let { decodeDiaryMoment(JSONObject(it)) } ?: original
        if (!sameDiaryMomentEditContent(moment, baseline) || !sameDiaryMomentState(original, baseline) ||
            edit?.moment?.let { !sameDiaryMomentEditContent(it, baseline) } == true) return false
        val owner = edit?.recordingOwner?.takeIf { it.isNotBlank() } ?: "moment-${moment.id}"
        val recording = RecordingService.state.value
        if (recording.running && recording.owner == owner || RecordingService.inbox(getApplication(), owner).isNotEmpty()) return false
        if (edit != null) enqueueDiary(current.copy(diaryInbox = encodeDiaryInbox(current.diaryInboxItems().filterNot { it.id == edit.id })))
        return true
    }
    @Synchronized fun saveDiaryMomentEdit(parent: NoteNode, moment: DiaryMoment): DiaryInboxItem {
        check(!replacingWorkspace.get()) { "正在恢复备份，请稍后再保存" }
        var current = diaryParent(parent)
        val original = current.diaryMoments().firstOrNull { it.id == moment.id }
        val activeEdit = current.diaryInboxItems().any { it.status == "draft" && it.originalStatus == "editing" && it.moment?.id == moment.id }
        val orphan = current.diaryInboxItems().firstOrNull { it.status == "draft" && it.editingOf == moment.id && it.originalStatus != "editing" }
        if (orphan != null && !activeEdit) {
            val changed = orphan.copy(moment = moment.copy(id = orphan.moment?.id ?: orphan.id,
                createdAt = orphan.moment?.createdAt ?: moment.createdAt, sentAt = orphan.moment?.sentAt), updatedAt = System.currentTimeMillis())
            enqueueDiary(current.copy(diaryInbox = encodeDiaryInbox(current.diaryInboxItems().map { if (it.id == orphan.id) changed else it })))
            return changed
        }
        if (original == null) {
            if (current.diaryInboxItems().any { it.status == "draft" && it.originalStatus != "editing" && it.moment?.id == moment.id }) {
                return saveDiaryDraft(current, moment)
            }
            current = current.detachDiaryEdits(setOf(moment.id))
            val detached = current.diaryInboxItems().firstOrNull { it.editingOf == moment.id && it.status == "draft" }
            val id = detached?.moment?.id ?: UUID.randomUUID().toString()
            val changed = (detached ?: DiaryInboxItem(id = id, editingOf = moment.id)).copy(moment = moment.copy(id = id), originalStatus = "draft",
                updatedAt = System.currentTimeMillis())
            enqueueDiary(current.copy(diaryInbox = encodeDiaryInbox(current.diaryInboxItems().filterNot { it.id == changed.id } + changed)))
            return changed
        }
        beginDiaryMomentEdit(current, moment.id)
        current = diaryParent(current)
        val previous = current.diaryInboxItems().first { it.originalStatus == "editing" && it.status == "draft" && it.moment?.id == moment.id }
        val changed = previous.copy(moment = moment.copy(createdAt = original.createdAt, sentAt = original.sentAt), updatedAt = System.currentTimeMillis())
        enqueueDiary(current.copy(diaryInbox = encodeDiaryInbox(current.diaryInboxItems().map { if (it.id == previous.id) changed else it })))
        return changed
    }
    suspend fun completeDiaryMomentEdit(parent: NoteNode, moment: DiaryMoment): DiaryMoment = diaryPublishGate.withLock {
        val staged = synchronized(this) {
            val edit = saveDiaryMomentEdit(parent, moment)
            val recording = RecordingService.state.value
            check(!recording.running || recording.owner != edit.recordingOwner.takeIf { it.isNotBlank() }.orEmpty()) { "请先结束录音，再完成编辑" }
            val current = diaryParent(parent)
            val original = current.diaryMoments().firstOrNull { it.id == moment.id }
            val baseline = edit.originalMoment.takeIf { it.isNotBlank() }?.let { decodeDiaryMoment(JSONObject(it)) }
            if (original == null || edit.originalStatus != "editing" || baseline != null && !sameDiaryMomentState(original, baseline)) {
                enqueueDiary(current.detachDiaryEdits(setOf(moment.id)))
                Triple(current, edit, null)
            } else {
                val updated = moment.copy(createdAt = original.createdAt, sentAt = original.sentAt,
                    occurredAt = moment.occurredAt ?: original.sentAt)
                enqueueDiary(current.copy(diaryRoad = encodeDiaryMoments(sortedDiaryMoments(current.diaryMoments().map { if (it.id == updated.id) updated else it })),
                    diaryInbox = encodeDiaryInbox(current.diaryInboxItems().filterNot { it.id == edit.id })))
                Triple(current, edit, updated)
            }
        }
        val updated = staged.third
        if (updated == null) {
            flush(staged.first.id)
            error("原片段已变化或移出小路，修改已保存在收纳箱，可作为新片段发送")
        }
        try {
            gate.withLock { notes.version(NoteVersion(nodeId = staged.first.id, snapshot = jsonObject(staged.first).toString())) }
            flush(staged.first.id)
            updated
        } catch (failure: Exception) {
            synchronized(this) {
                val current = diaryParent(staged.first)
                val live = current.diaryMoments().firstOrNull { it.id == updated.id }
                if (live != null && sameDiaryMomentState(live, updated)) {
                    val old = staged.first.diaryMoments().first { it.id == updated.id }
                    enqueueDiary(current.copy(diaryRoad = encodeDiaryMoments(current.diaryMoments().map { if (it.id == old.id) old else it }),
                        diaryInbox = encodeDiaryInbox(current.diaryInboxItems().filterNot { it.id == staged.second.id } + staged.second)))
                } else {
                    val id = UUID.randomUUID().toString()
                    val retained = staged.second.copy(id = id, originalStatus = "draft", editingOf = updated.id,
                        moment = moment.copy(id = id), updatedAt = System.currentTimeMillis())
                    enqueueDiary(current.copy(diaryInbox = encodeDiaryInbox(current.diaryInboxItems() + retained)))
                }
            }
            throw failure
        }
    }
    @Synchronized fun saveDiaryDraft(parent: NoteNode, moment: DiaryMoment): DiaryInboxItem {
        check(!replacingWorkspace.get()) { "正在恢复备份，请稍后再保存" }
        val current = diaryParent(parent)
        val items = current.diaryInboxItems()
        val previous = items.firstOrNull { it.moment?.id == moment.id }
        val saved = (previous ?: DiaryInboxItem(id = moment.id, moment = moment, createdAt = moment.createdAt))
            .copy(moment = moment.copy(createdAt = previous?.moment?.createdAt ?: moment.createdAt,
                sentAt = previous?.moment?.sentAt), updatedAt = System.currentTimeMillis())
        momentParents["moment-${moment.id}"] = current.id
        val hasInput = moment.hasContent() || moment.occurredAt != null
        val owner = saved.recordingOwner.ifBlank { "moment-${moment.id}" }
        val recorder = RecordingService.state.value
        val hasRecording = !hasInput && (recorder.running && recorder.owner == owner || RecordingService.inbox(getApplication<Application>(), owner).isNotEmpty())
        val next = items.filterNot { it.moment?.id == moment.id } + if (hasInput || hasRecording) listOf(saved) else emptyList()
        enqueueDiary(current.copy(diaryInbox = encodeDiaryInbox(next)))
        return saved
    }
    private fun stageDiaryPublication(parent: NoteNode, moment: DiaryMoment, occurredAt: Long?): DiaryMoment {
        check(!replacingWorkspace.get()) { "正在恢复备份，请稍后再发送" }
        require(moment.hasContent()) { "请先写下片段或添加素材" }
        val previous = parent.diaryMoments().firstOrNull { it.id == moment.id }
        val recordingOwner = parent.diaryInboxItems().firstOrNull { it.moment?.id == moment.id }?.recordingOwner?.takeIf { it.isNotBlank() } ?: "moment-${moment.id}"
        val recording = RecordingService.state.value
        check(!recording.running || recording.owner != recordingOwner) { "请先结束录音，再发送片段" }
        val sent = previous?.sentAt ?: System.currentTimeMillis()
        val published = moment.copy(createdAt = previous?.createdAt ?: moment.createdAt,
            sentAt = sent, occurredAt = occurredAt ?: moment.occurredAt ?: sent)
        momentParents["moment-${moment.id}"] = parent.id
        enqueueDiary(parent.copy(diaryRoadEnabled = true,
            diaryRoad = encodeDiaryMoments(sortedDiaryMoments(parent.diaryMoments().filterNot { it.id == moment.id } + published)),
            diaryInbox = encodeDiaryInbox(parent.diaryInboxItems().filterNot { it.moment?.id == moment.id })))
        return published
    }
    suspend fun publishDiaryMoment(parent: NoteNode, moment: DiaryMoment, occurredAt: Long? = null): DiaryMoment = diaryPublishGate.withLock {
        val before = synchronized(this) { diaryParent(parent) }
        val published = synchronized(this) { stageDiaryPublication(before, moment, occurredAt) }
        try { flush(before.id); published }
        catch (failure: Exception) {
            synchronized(this) {
                val current = diaryParent(before)
                val oldMoment = before.diaryMoments().firstOrNull { it.id == moment.id }
                val oldItem = before.diaryInboxItems().firstOrNull { it.moment?.id == moment.id }
                    ?: DiaryInboxItem(id = moment.id, moment = moment.copy(sentAt = null), createdAt = moment.createdAt)
                val road = current.diaryMoments().filterNot { it.id == moment.id } + listOfNotNull(oldMoment)
                val inbox = current.diaryInboxItems().filterNot { it.moment?.id == moment.id } + if (oldMoment == null) listOf(oldItem) else emptyList()
                enqueueDiary(current.copy(diaryRoad = encodeDiaryMoments(road), diaryInbox = encodeDiaryInbox(inbox),
                    diaryRoadEnabled = before.diaryRoadEnabled || road.any { it.hasContent() }))
            }
            throw failure
        }
    }
    @Synchronized fun enableDiaryRoad(parent: NoteNode) {
        check(!replacingWorkspace.get()) { "正在恢复备份，请稍后再设置" }
        enqueueDiary(diaryParent(parent).copy(diaryRoadEnabled = true))
    }
    fun setDiaryRoadEnabled(parent: NoteNode, enabled: Boolean) { if (enabled) enableDiaryRoad(parent) else archiveDiaryRoad(parent) }
    @Synchronized fun retractDiaryMoment(parent: NoteNode, momentId: String) = moveDiaryMomentToInbox(parent, momentId, "retracted")
    fun withdrawDiaryMoment(parent: NoteNode, momentId: String) = retractDiaryMoment(parent, momentId)
    @Synchronized fun deleteDiaryMoment(parent: NoteNode, momentId: String) {
        moveDiaryMomentToInbox(parent, momentId, "deleted")
    }
    private fun moveDiaryMomentToInbox(parent: NoteNode, momentId: String, status: String) {
        check(!replacingWorkspace.get()) { "正在恢复备份，请稍后再保存" }
        val current = diaryParent(parent).detachDiaryEdits(setOf(momentId))
        val moment = current.diaryMoments().firstOrNull { it.id == momentId } ?: return
        val now = System.currentTimeMillis()
        val item = DiaryInboxItem(id = moment.id, status = status, originalStatus = "published", moment = moment,
            createdAt = now, updatedAt = now, deletedAt = now.takeIf { status == "deleted" }, expiresAt = expiryAt(now).takeIf { status == "deleted" })
        enqueueDiary(current.copy(diaryRoad = encodeDiaryMoments(current.diaryMoments().filterNot { it.id == momentId }),
            diaryInbox = encodeDiaryInbox(current.diaryInboxItems().filterNot { it.moment?.id == momentId } + item)))
    }
    @Synchronized fun archiveDiaryRoad(parent: NoteNode) {
        check(!replacingWorkspace.get()) { "正在恢复备份，请稍后再设置" }
        val before = diaryParent(parent)
        val current = before.detachDiaryEdits(before.diaryMoments().map { it.id }.toSet())
        val now = System.currentTimeMillis()
        val archived = DiaryInboxItem(status = "road", originalStatus = "road", road = current.diaryRoad,
            roadTheme = current.diaryRoadTheme, roadBackground = current.diaryRoadBackground, roadLayout = current.diaryRoadLayout,
            createdAt = now, updatedAt = now, deletedAt = now, expiresAt = expiryAt(now))
        val items = current.diaryInboxItems() + if (current.diaryRoadEnabled || current.diaryRoad.isNotBlank() || current.diaryRoadBackground.isNotBlank()) listOf(archived) else emptyList()
        enqueueDiary(current.copy(diaryRoadEnabled = false, diaryRoad = "", diaryRoadTheme = "forest", diaryRoadBackground = "",
            diaryRoadLayout = "alternate", diaryInbox = encodeDiaryInbox(items)))
    }
    @Synchronized fun restoreDiaryInbox(parent: NoteNode, itemId: String) {
        check(!replacingWorkspace.get()) { "正在恢复备份，请稍后再恢复" }
        val current = diaryParent(parent)
        val item = current.diaryInboxItems().firstOrNull { it.id == itemId } ?: return
        val remainder = current.diaryInboxItems().filterNot { it.id == itemId }
        if (item.status == "road" || item.status == "deleted" && item.originalStatus == "road") {
            val activeRoad = current.diaryRoadEnabled
            val moments = (decodeDiaryMoments(item.road) + current.diaryMoments()).associateBy { it.id }.values.toList()
            enqueueDiary(current.copy(diaryRoadEnabled = true, diaryRoad = encodeDiaryMoments(sortedDiaryMoments(moments)),
                diaryRoadTheme = if (activeRoad) current.diaryRoadTheme else item.roadTheme,
                diaryRoadBackground = if (activeRoad) current.diaryRoadBackground else item.roadBackground,
                diaryRoadLayout = if (activeRoad) current.diaryRoadLayout else item.roadLayout, diaryInbox = encodeDiaryInbox(remainder)))
        } else if (item.status == "deleted" && item.originalStatus == "published") {
            val moment = item.moment ?: return
            enqueueDiary(current.copy(diaryRoadEnabled = true,
                diaryRoad = encodeDiaryMoments(sortedDiaryMoments((current.diaryMoments() + moment).distinctBy { it.id })),
                diaryInbox = encodeDiaryInbox(remainder)))
        } else {
            val restoredStatus = if (item.status != "deleted") item.status else if (item.originalStatus == "editing") "draft" else item.originalStatus
            val restored = item.copy(status = restoredStatus,
                updatedAt = System.currentTimeMillis(), deletedAt = null, expiresAt = null)
            val next = current.copy(diaryInbox = encodeDiaryInbox(remainder + restored))
            enqueueDiary(if (restored.originalStatus == "editing" && current.diaryMoments().none { it.id == restored.moment?.id })
                next.detachDiaryEdits(setOf(restored.moment?.id.orEmpty())) else next)
        }
    }
    fun restoreDiaryInboxItem(parent: NoteNode, itemId: String) = restoreDiaryInbox(parent, itemId)
    @Synchronized fun editDiaryInbox(parent: NoteNode, itemId: String): NoteNode? {
        check(!replacingWorkspace.get()) { "正在恢复备份，请稍后再编辑" }
        val current = diaryParent(parent)
        val item = current.diaryInboxItems().firstOrNull { it.id == itemId && it.moment != null } ?: return null
        val editable = item.copy(status = if (item.status == "deleted") "draft" else item.status,
            originalStatus = if (item.status == "deleted" && item.originalStatus != "editing") "draft" else item.originalStatus,
            deletedAt = null, expiresAt = null, updatedAt = System.currentTimeMillis())
        val edited = current.copy(diaryInbox = encodeDiaryInbox(current.diaryInboxItems().map { if (it.id == itemId) editable else it }))
        val next = if (editable.originalStatus == "editing" && current.diaryMoments().none { it.id == editable.moment?.id })
            edited.detachDiaryEdits(setOf(editable.moment?.id.orEmpty())) else edited
        enqueueDiary(next)
        return next.diaryInboxItems().firstOrNull { it.id == itemId || it.editingOf == editable.moment?.id && it.status == "draft" }?.asNote(next)
    }
    @Synchronized fun deleteDiaryInbox(parent: NoteNode, itemId: String) {
        check(!replacingWorkspace.get()) { "正在恢复备份，请稍后再删除" }
        val current = diaryParent(parent)
        val now = System.currentTimeMillis()
        enqueueDiary(current.copy(diaryInbox = encodeDiaryInbox(current.diaryInboxItems().map { item ->
            if (item.id != itemId || item.status == "deleted") item else item.copy(status = "deleted", originalStatus = if (item.originalStatus == "editing") "editing" else item.status,
                updatedAt = now, deletedAt = now, expiresAt = expiryAt(now))
        })))
    }
    fun deleteDiaryInboxItem(parent: NoteNode, itemId: String) = deleteDiaryInbox(parent, itemId)
    @Synchronized fun permanentlyDeleteDiaryInbox(parent: NoteNode, itemId: String) {
        check(!replacingWorkspace.get()) { "正在恢复备份，请稍后再删除" }
        val current = diaryParent(parent)
        enqueueDiary(current.copy(diaryInbox = encodeDiaryInbox(current.diaryInboxItems().filterNot { it.id == itemId })))
    }
    fun permanentlyDeleteDiaryInboxItem(parent: NoteNode, itemId: String) = permanentlyDeleteDiaryInbox(parent, itemId)
    @Synchronized fun updateDiaryRoadLayout(parent: NoteNode, layout: String) {
        require(layout in setOf("alternate", "left", "right")) { "未知排布方式" }
        check(!replacingWorkspace.get()) { "正在恢复备份，请稍后再设置" }
        enqueueDiary(diaryParent(parent).copy(diaryRoadLayout = layout))
    }
    fun diaryInboxRetentionDays(): Int = appPreferences(getApplication<Application>()).getInt("diary_inbox_retention_days", 0).coerceAtLeast(0)
    fun diaryTrashRetentionDays(): Int = appPreferences(getApplication<Application>()).getInt("diary_trash_retention_days", 0).coerceAtLeast(0)
    fun setDiaryRetentionDays(inboxDays: Int, trashDays: Int) {
        require(inboxDays >= 0 && trashDays >= 0) { "保留天数不能小于0" }
        appPreferences(getApplication<Application>()).edit().putInt("diary_inbox_retention_days", inboxDays).putInt("diary_trash_retention_days", trashDays).apply()
    }
    private fun expiryAt(now: Long): Long? = diaryInboxRetentionDays().takeIf { it > 0 }?.let { now + it.toLong() * 86_400_000L }
    fun cleanupDiaryRetention() = actions.launch { cleanupExplicitDiaryRetention() }
    private suspend fun cleanupExplicitDiaryRetention(now: Long = System.currentTimeMillis()) {
        val workspace = todoGeneration.get()
        if (replacingWorkspace.get()) return
        flushAll()
        val all = notes.nodes()
        val expired = all.filter { it.kind == "diary" && it.deletedAt != null && it.diaryTrashExpiresAt?.let { at -> at <= now } == true }.map { it.id }
        if (expired.isNotEmpty()) drainGate.withLock { gate.withLock { db.withTransaction {
            if (replacingWorkspace.get() || todoGeneration.get() != workspace) return@withTransaction
            val stillExpired = expired.filter { id -> notes.node(id)?.let { it.deletedAt != null && it.diaryTrashExpiresAt?.let { at -> at <= now } == true } == true }
            notes.removeVersions(stillExpired); notes.remove(stillExpired)
            synchronized(this) { stillExpired.forEach { id -> pending.remove(id); diarySnapshots.remove(id); android.util.AtomicFile(File(drafts, "$id.json")).delete() } }
        } } }
        all.filter { it.kind == "diary" && it.id !in expired }.forEach { parent ->
            synchronized(this) {
                if (replacingWorkspace.get() || todoGeneration.get() != workspace) return@synchronized
                // Apply expiry to the latest editor state; enqueue it through the normal atomic draft drain.
                val current = pending[parent.id] ?: diarySnapshots[parent.id]?.takeIf { it.updatedAt > parent.updatedAt } ?: parent
                val items = current.diaryInboxItems()
                val kept = items.filterNot { it.expiresAt?.let { at -> at <= now } == true && it.status in setOf("deleted", "road") }
                if (items != kept && current.deletedAt == null) enqueue(current.copy(diaryInbox = encodeDiaryInbox(kept)))
            }
        }
        flushAll()
        diaryRevision.update { it + 1 }
    }
    @Synchronized fun updateDiaryRoadAppearance(parent: NoteNode, theme: String, background: String) {
        check(!replacingWorkspace.get()) { "正在恢复备份，请稍后再保存" }
        val current = currentDiary(parent.day ?: error("日记缺少所属日期"))
        enqueueDiary(current.copy(diaryRoadTheme = theme, diaryRoadBackground = background))
    }
    fun updateDiaryRoadSettings(parent: NoteNode, theme: String, background: String) = updateDiaryRoadAppearance(parent, theme, background)
    /** Restore content while keeping the current favorite and retained inbox items. */
    @Synchronized fun restoreDiarySnapshot(snapshot: NoteNode) {
        check(!replacingWorkspace.get()) { "正在恢复备份，请稍后再保存" }
        val current = currentDiary(snapshot.day ?: error("日记缺少所属日期"))
        val restored = snapshot.copy(id = current.id, createdAt = current.createdAt, favorite = current.favorite,
            diaryInbox = encodeDiaryInbox(mergeDiaryInboxPreservingConflicts(current.diaryInboxItems(), snapshot.diaryInboxItems())))
        val conflicts = restored.diaryInboxItems().filter { item -> item.originalStatus == "editing" && item.moment != null &&
            restored.diaryMoments().firstOrNull { it.id == item.moment.id }?.let { original ->
                item.originalMoment.isNotBlank() && !sameDiaryMomentState(original, decodeDiaryMoment(JSONObject(item.originalMoment)))
            } != false }.mapNotNull { it.moment?.id }.toSet()
        enqueueDiary(restored.detachDiaryEdits(conflicts))
    }
    /** Persist an inbox identity before the recorder can outlive its editor or process. */
    @Synchronized fun diaryRecordingOwner(note: NoteNode): String {
        if (note.kind != "diary_moment" || note.day == null) return note.id
        val parent = currentDiary(note.day)
        val key = note.id.removePrefix("moment-")
        val item = parent.diaryInboxItems().firstOrNull { it.status == "draft" && it.originalStatus == "editing" && it.moment?.id == key }
            ?: parent.diaryInboxItems().firstOrNull { it.moment?.id == key || it.editingOf == key && it.status == "draft" }
        return item?.recordingOwner?.takeIf { it.isNotBlank() } ?: note.id
    }
    suspend fun registerDiaryRecording(note: NoteNode): String {
        if (note.kind != "diary_moment") return note.id
        val registered = synchronized(this) {
            check(!replacingWorkspace.get()) { "正在恢复备份，请稍后再录音" }
            val parent = findDiary(note.day ?: error("片段缺少所属日期")) ?: newNote("diary", day = note.day).let { fresh ->
                if (note.parentId != null && (nodes.value + diarySnapshots.values).none { it.id == note.parentId && it.deletedAt != null }) fresh.copy(id = note.parentId) else fresh
            }
            val moment = note.asDiaryMoment()
            val editing = parent.diaryInboxItems().firstOrNull { it.originalStatus == "editing" && it.status == "draft" && it.moment?.id == moment.id }
            val item = editing ?: parent.diaryInboxItems().firstOrNull { it.moment?.id == moment.id || it.editingOf == moment.id && it.status == "draft" }
            val exists = parent.diaryMoments().any { it.id == moment.id } || item != null
            val owner = item?.recordingOwner?.takeIf { it.isNotBlank() } ?: note.id
            val next = if (exists) parent else parent.copy(diaryInbox = encodeDiaryInbox(parent.diaryInboxItems() +
                DiaryInboxItem(id = moment.id, moment = moment, createdAt = moment.createdAt, recordingOwner = owner)))
            momentParents[note.id] = parent.id
            enqueue(next)
            parent.id to owner
        }
        flush(registered.first)
        return registered.second
    }
    suspend fun recoverDiaryRecordings() {
        val context = getApplication<Application>()
        val sources = notes.nodes().filter { it.kind == "diary" }
        for (source in sources) {
            diarySnapshots.putIfAbsent(source.id, source)
            val moments = (source.diaryMoments() + source.diaryInboxItems().flatMap { item ->
                listOfNotNull(item.moment).filter { item.originalStatus != "editing" && (item.recordingOwner.isBlank() || item.recordingOwner == "moment-${it.id}") } + decodeDiaryMoments(item.road)
            }).distinctBy { it.id }
            for (moment in moments) {
                val owner = "moment-${moment.id}"
                val audio = RecordingService.inbox(context, owner)
                val recorder = RecordingService.state.value
                val active = recorder.running && recorder.owner == owner
                val originalDraft = source.diaryInboxItems().firstOrNull { it.moment?.id == moment.id && it.originalStatus != "editing" }
                val emptyPlaceholder = !moment.hasContent() && moment.occurredAt == null && !active && audio.isEmpty() && originalDraft?.status == "draft" &&
                    originalDraft.editingOf.isBlank() && originalDraft.originalMoment.isBlank()
                if (audio.isEmpty() && !emptyPlaceholder) continue
                val updated = synchronized(this) {
                    val current = pending[source.id] ?: diarySnapshots[source.id] ?: source
                    val changed = if (emptyPlaceholder) current.copy(
                        diaryRoad = encodeDiaryMoments(current.diaryMoments().filterNot { it.id == moment.id }),
                        diaryInbox = encodeDiaryInbox(current.diaryInboxItems().filterNot { it.status == "draft" && it.moment?.id == moment.id }))
                    else current.withDiaryRecording(moment.id, audio)
                    if (source.deletedAt == null) enqueue(changed)
                    changed
                }
                if (source.deletedAt != null) {
                    gate.withLock { db.withTransaction { notes.put(updated.copy(updatedAt = maxOf(System.currentTimeMillis(), updated.updatedAt + 1))) } }
                    diarySnapshots[source.id] = updated
                } else {
                    flush(source.id)
                }
                audio.forEach { RecordingService.removeInbox(context, android.net.Uri.parse(it.uri).path.orEmpty()) }
            }
            for (item in source.diaryInboxItems().filter { it.moment != null && it.recordingOwner.isNotBlank() && it.recordingOwner != "moment-${it.moment.id}" }) {
                val audio = RecordingService.inbox(context, item.recordingOwner)
                if (audio.isEmpty()) continue
                // Withdrawals and whole-diary deletion can move this edit to a new item or date carrier.
                // Resolve its persistent recording owner again before consuming the service inbox.
                val updated = synchronized(this) {
                    val candidates = (nodes.value + sources + diarySnapshots.values + pending.values).associateBy { it.id }.values
                    val current = candidates.firstOrNull { parent -> parent.kind == "diary" && parent.diaryInboxItems().any { it.recordingOwner == item.recordingOwner } }
                    val actualItem = current?.diaryInboxItems()?.firstOrNull { it.recordingOwner == item.recordingOwner && it.moment != null }
                    if (current == null || actualItem == null) null else {
                        val next = current.withDiaryInboxRecording(actualItem.id, audio)
                        if (current.deletedAt == null) enqueue(next)
                        next
                    }
                } ?: continue
                if (updated.deletedAt != null) {
                    val saved = gate.withLock { db.withTransaction {
                        val latest = notes.node(updated.id)
                        val actualItem = latest?.diaryInboxItems()?.firstOrNull { it.recordingOwner == item.recordingOwner && it.moment != null }
                        if (latest == null || actualItem == null) false else {
                            val next = latest.withDiaryInboxRecording(actualItem.id, audio).copy(updatedAt = maxOf(System.currentTimeMillis(), latest.updatedAt + 1))
                            notes.put(next); diarySnapshots[next.id] = next; true
                        }
                    } }
                    if (!saved) continue
                } else flush(updated.id)
                audio.forEach { RecordingService.removeInbox(context, android.net.Uri.parse(it.uri).path.orEmpty()) }
            }
        }
    }
    suspend fun flush(id: String) {
        val owner = momentParents[id] ?: if (id.startsWith("moment-"))
            (pending.values + diarySnapshots.values + nodes.value).firstOrNull { parent ->
                (parent.diaryMoments() + parent.diaryInboxItems().flatMap { listOfNotNull(it.moment) + decodeDiaryMoments(it.road) }).any { moment -> "moment-${moment.id}" == id }
            }?.id ?: id else id
        repeat(4) { if (pending[owner] != null) drain() }
        check(pending[owner] == null) { "尚未保存，请重试" }
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
                        diarySnapshots.clear()
                        momentParents.clear()
                        diaryRevision.update { it + 1 }
                    } finally { gate.unlock() }
                } finally { drainGate.unlock() }
            } finally { todoGate.unlock() }
        } finally { replacingWorkspace.set(false) }
    }
    fun reorderBoards(ids: List<Long>) = todoAction { db.withTransaction { ids.forEachIndexed { position, id -> dao.setTodoBoardPosition(id, position) } } }
    fun newNote(kind: String, parent: String? = null, day: Long? = null): NoteNode {
        val destination = if (kind == "diary") null else parent ?: MemorySpaces.WORK_ID
        val proposed = if (kind == "diary" && day != null) "day-$day" else UUID.randomUUID().toString()
        val id = if ((nodes.value + diarySnapshots.values).any { it.id == proposed && it.deletedAt != null }) UUID.randomUUID().toString() else proposed
        val node = NoteNode(id = id, kind = kind, parentId = destination, day = day, position = (nodes.value.filter { it.parentId == destination }.minOfOrNull { it.position } ?: 0) - 1)
        return if (kind == "diary") node.copy(diaryRoadTheme = DiaryBackgroundLibrary.defaultTheme(getApplication<Application>()),
            diaryRoadBackground = DiaryBackgroundLibrary.defaultUri(getApplication<Application>()),
            diaryRoadLayout = DiaryBackgroundLibrary.defaultLayout(getApplication<Application>())) else node
    }
    fun createFolder(title: String, parent: String?) { save(newNote("folder", parent).copy(title = title.trim())) }
    fun trash(ids: Set<String>, after: (String) -> Unit = {}) = actions.launch {
        flushAll()
        val current = notes.nodes()
        val targets = ids.filterNot(MemorySpaces::isRoot).flatMap { TreeRules.descendants(it, current) }.filterNot(MemorySpaces::isRoot).toSet()
        val group = UUID.randomUUID().toString()
        val time = System.currentTimeMillis()
        db.withTransaction { current.filter { it.id in targets && it.deletedAt == null }.forEach {
            if (it.kind == "diary" && it.diaryInbox.isNotBlank()) {
                notes.version(NoteVersion(nodeId = it.id, snapshot = jsonObject(it).toString()))
                val detached = it.detachDiaryEdits(it.diaryInboxItems().filter { item -> item.originalStatus == "editing" }.mapNotNull { item -> item.moment?.id }.toSet())
                val carrier = NoteNode(id = UUID.randomUUID().toString(), kind = "diary", day = it.day,
                    diaryInbox = detached.diaryInbox, diaryRoadTheme = it.diaryRoadTheme, diaryRoadLayout = it.diaryRoadLayout,
                    updatedAt = maxOf(time, it.updatedAt + 1))
                notes.put(carrier)
                diarySnapshots[carrier.id] = carrier
                carrier.diaryInboxItems().flatMap { listOfNotNull(it.moment) + decodeDiaryMoments(it.road) }.forEach { moment ->
                    momentParents["moment-${moment.id}"] = carrier.id
                }
            }
            val deleted = it.copy(deletedAt = time, deleteGroup = group, updatedAt = maxOf(time, it.updatedAt + 1),
                diaryInbox = if (it.kind == "diary") "" else it.diaryInbox,
                diaryTrashExpiresAt = if (it.kind == "diary") diaryTrashRetentionDays().takeIf { days -> days > 0 }?.let { days -> time + days.toLong() * 86_400_000L } else it.diaryTrashExpiresAt)
            notes.put(deleted)
            if (it.kind == "diary") diarySnapshots[it.id] = deleted
        } }
        diaryRevision.update { it + 1 }
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
            val liveDay = if (node.kind == "diary" && node.day != null) notes.diary(node.day)?.takeIf { it.id != node.id }
                else all.firstOrNull { it.kind == "diary" && it.deletedAt == null && it.day == node.day && it.id != node.id }
            val placeholder = liveDay?.takeIf { !it.hasDiaryContent() && !it.diaryRoadEnabled }
            val conflict = node.kind == "diary" && liveDay != null && placeholder == null
            val destination = if (conflict) MemorySpaces.WORK_ID else if (node.kind == "diary") null else
                node.parentId.takeIf { parent != null && (parent.deletedAt == null || parent.id in returningIds) } ?: MemorySpaces.rootId(node, all)
            if (conflict) notes.version(NoteVersion(nodeId = node.id, snapshot = jsonObject(node).toString()))
            if (conflict && node.diaryInbox.isNotBlank() && liveDay != null) {
                val active = liveDay.copy(diaryInbox = encodeDiaryInbox(mergeDiaryInboxPreservingConflicts(liveDay.diaryInboxItems(), node.diaryInboxItems())),
                    updatedAt = maxOf(System.currentTimeMillis(), liveDay.updatedAt + 1))
                notes.put(active)
                diarySnapshots[active.id] = active
                active.diaryInboxItems().flatMap { listOfNotNull(it.moment) + decodeDiaryMoments(it.road) }.forEach { moment -> momentParents["moment-${moment.id}"] = active.id }
            }
            val restoredInbox = when {
                conflict -> ""
                placeholder != null && node.kind == "diary" -> encodeDiaryInbox(
                    mergeDiaryInboxPreservingConflicts(placeholder.diaryInboxItems(), node.diaryInboxItems()))
                else -> node.diaryInbox
            }
            val restored = node.copy(kind = if (conflict) "memory" else node.kind, deletedAt = null, deleteGroup = null, parentId = destination,
                diaryTrashExpiresAt = null, updatedAt = maxOf(System.currentTimeMillis(), node.updatedAt + 1, (placeholder?.updatedAt ?: 0) + 1),
                diaryInbox = restoredInbox)
            if (placeholder != null && node.kind == "diary") {
                // Inbox edits made while the diary was in trash keep their recovery history and media references.
                notes.versions(placeholder.id).forEach { version -> notes.version(version.copy(nodeId = restored.id)) }
                notes.removeVersions(listOf(placeholder.id)); notes.remove(listOf(placeholder.id))
                // Keep a tombstone until the old Room emission disappears, and redirect any delayed autosave.
                diarySnapshots[placeholder.id] = placeholder.copy(deletedAt = System.currentTimeMillis(), updatedAt = restored.updatedAt)
                restored.diaryInboxItems().flatMap { listOfNotNull(it.moment) + decodeDiaryMoments(it.road) }.forEach { moment -> momentParents["moment-${moment.id}"] = restored.id }
            }
            notes.put(if (conflict) restored.flattenDiaryRoad() else restored)
            if (restored.kind == "diary") diarySnapshots[restored.id] = restored else diarySnapshots.remove(restored.id)
            if (conflict) messages.tryEmit("当天已有日记，恢复的记录已放到记忆 / 工作")
        } }
        diaryRevision.update { it + 1 }
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
                TreeRules.imagePreference(node, all, true, imageStorageDefault(context)) != "copy") {
                return@map node
            }
            suspend fun reconcile(blocks: List<NoteBlock>): List<NoteBlock> = blocks.map { block ->
                if (block.type != "image" || block.owned) block
                else runCatching {
                    val copied = copies[block.uri] ?: copyImage(context, block).also { copies[block.uri] = it }
                    block.copy(uri = copied.uri, owned = true, bytes = copied.bytes)
                }.getOrElse {
                    failures += ImageFailure(node.id, block.id, TreeRules.path(node, all), block.text, block.uri, it.message ?: "无法读取原图片")
                    block
                }
            }
            val blocks = reconcile(node.blocks())
            val moments = node.diaryMoments().map { moment ->
                val next = reconcile(decodeBlocks(moment.document, moment.text))
                moment.copy(document = encodeBlocks(next), text = blockPlainText(next))
            }
            suspend fun reconcileMoment(moment: DiaryMoment): DiaryMoment {
                val next = reconcile(decodeBlocks(moment.document, moment.text))
                return moment.copy(document = encodeBlocks(next), text = blockPlainText(next))
            }
            val inbox = node.diaryInboxItems().map { item -> item.copy(moment = item.moment?.let { reconcileMoment(it) },
                road = encodeDiaryMoments(decodeDiaryMoments(item.road).map { reconcileMoment(it) }),
                originalMoment = if (item.originalMoment.isBlank()) "" else jsonObject(reconcileMoment(decodeDiaryMoment(JSONObject(item.originalMoment)))).toString()) }
            if (blocks == node.blocks() && moments == node.diaryMoments() && inbox == node.diaryInboxItems()) node else
                node.copy(document = encodeBlocks(blocks), text = blockPlainText(blocks), diaryRoad = encodeDiaryMoments(moments), diaryInbox = encodeDiaryInbox(inbox), updatedAt = System.currentTimeMillis())
        }
        return ImageReconciliation(result, failures)
    }

    private suspend fun persistImageChanges(old: List<NoteNode>, next: List<NoteNode>) {
        val previous = old.associateBy { it.id }
        db.withTransaction {
            next.filter { previous[it.id] != it }.forEach { node ->
                previous[node.id]?.takeIf { it.document != node.document || it.diaryRoad != node.diaryRoad || it.diaryInbox != node.diaryInbox }?.let { notes.version(NoteVersion(nodeId = node.id, snapshot = jsonObject(it).toString())) }
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
