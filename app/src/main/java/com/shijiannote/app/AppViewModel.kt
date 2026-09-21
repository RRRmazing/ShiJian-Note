package com.shijiannote.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shijiannote.app.data.AppDatabase
import com.shijiannote.app.data.DiaryEntry
import java.time.LocalDate
import java.time.ZoneId
import com.shijiannote.app.data.MemoryCategory
import com.shijiannote.app.data.MemoryEntry
import com.shijiannote.app.data.ScheduleEvent
import com.shijiannote.app.data.TodoBoard
import com.shijiannote.app.data.TodoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.get(application).appDao()

    val schedule = dao.observeSchedule()
    val archivedSchedule = dao.observeArchivedSchedule()
    val todoBoards = dao.observeTodoBoards()
    val archivedTodoBoards = dao.observeArchivedTodoBoards()
    val dailyTodoBoards = dao.observeDailyTodoBoards()
    val dailyTodoPreferences = dao.observeDailyTodoPreferences()
    val diaries = dao.observeDiaries()
    val memoryCategories = dao.observeMemoryCategories()

    init {
        viewModelScope.launch { synchronizeDailyTodos() }
    }

    /** Safe on every app start, resume, and worker run: each missed day is handled exactly once. */
    suspend fun synchronizeDailyTodos() {
        val today = LocalDate.now()
        val todayMillis = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val preferences = dao.getDailyTodoPreferences()
        if (preferences == null || preferences.lastRolloverDay == 0L) {
            dao.saveDailyTodoPreferences((preferences ?: com.shijiannote.app.data.DailyTodoPreferences()).copy(lastRolloverDay = todayMillis))
            dao.prepareDailyPages(todayMillis)
            return
        }
        var day = preferences.lastRolloverDay
        while (day < todayMillis) {
            val date = java.time.Instant.ofEpochMilli(day).atZone(ZoneId.systemDefault()).toLocalDate()
            dao.rollDailyPages("今天 · ${date.year}.${date.monthValue}.${date.dayOfMonth}")
            day = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            dao.saveDailyTodoPreferences(preferences.copy(lastRolloverDay = day))
        }
        dao.prepareDailyPages(todayMillis)
    }

    fun synchronizeDailyTodosAsync() = viewModelScope.launch { synchronizeDailyTodos() }
    fun setDailyPageVisible(type: String, visible: Boolean) = viewModelScope.launch { dao.setDailyPageVisible(type, visible) }

    fun addDailyTodo(boardId: Long, text: String, reminderAt: Long?, reminderHours: Int, reminderMinutes: Int, afterInsert: (TodoItem) -> Unit) = viewModelScope.launch {
        val item = TodoItem(boardId = boardId, text = text.trim(), position = dao.nextTodoPosition(boardId), reminderAt = reminderAt, reminderHours = reminderHours, reminderMinutes = reminderMinutes)
        val id = dao.insertTodoItems(listOf(item))
        afterInsert(item.copy(id = id.firstOrNull() ?: 0L))
    }

    fun updateDailyTodo(item: TodoItem, afterUpdate: (TodoItem) -> Unit) = viewModelScope.launch { dao.updateTodoItem(item); afterUpdate(item) }
    fun deleteDailyTodo(item: TodoItem) = viewModelScope.launch { dao.deleteTodoItem(item) }
    fun toggleTodoImportant(item: TodoItem) = viewModelScope.launch { dao.toggleTodoImportant(item.id) }
    fun reorderTodoItems(items: List<TodoItem>) = viewModelScope.launch { items.forEachIndexed { index, item -> dao.setTodoPosition(item.id, index) } }

    fun addSchedule(event: ScheduleEvent, afterInsert: (ScheduleEvent) -> Unit) = viewModelScope.launch {
        val id = dao.insertSchedule(event)
        afterInsert(event.copy(id = id))
    }

    fun importSchedules(events: List<ScheduleEvent>, afterInsert: (ScheduleEvent) -> Unit) = viewModelScope.launch {
        events.forEach { event ->
            val id = dao.insertSchedule(event)
            afterInsert(event.copy(id = id))
        }
    }

    fun updateSchedule(event: ScheduleEvent, afterUpdate: (ScheduleEvent) -> Unit) = viewModelScope.launch {
        dao.updateSchedule(event)
        afterUpdate(event)
    }

    fun deleteSchedule(event: ScheduleEvent) = viewModelScope.launch { dao.deleteSchedule(event) }
    fun archiveSchedules(events: List<ScheduleEvent>) = viewModelScope.launch { dao.archiveSchedules(events.map { it.id }) }
    fun deleteSchedules(events: List<ScheduleEvent>) = viewModelScope.launch { dao.deleteSchedules(events.map { it.id }) }
    fun archiveExpiredItems(today: Long, now: Long) = viewModelScope.launch {
        dao.archiveExpiredSchedules(now)
        dao.archiveExpiredTodoBoards(today)
    }

    fun addTodo(summary: String, dueDate: Long?, tasks: List<String>) = viewModelScope.launch {
        val boardId = dao.insertTodoBoard(TodoBoard(summary = summary.trim(), dueDate = dueDate, position = dao.nextTodoBoardPosition()))
        dao.insertTodoItems(tasks.filter { it.isNotBlank() }.mapIndexed { index, text ->
            TodoItem(boardId = boardId, text = text.trim(), position = index)
        })
    }

    fun toggleTodo(item: TodoItem) = viewModelScope.launch {
        dao.updateTodoItem(item.copy(completed = !item.completed))
    }

    fun toggleTodoBoard(board: TodoBoard) = viewModelScope.launch {
        dao.updateTodoBoard(board.copy(expanded = !board.expanded))
    }
    fun updateTodo(board: TodoBoard, tasks: List<String>) = viewModelScope.launch {
        dao.updateTodoBoard(board)
        dao.deleteTodoItemsForBoard(board.id)
        dao.insertTodoItems(tasks.filter { it.isNotBlank() }.mapIndexed { index, text ->
            TodoItem(boardId = board.id, text = text.trim(), position = index)
        })
    }

    fun reorderTodoBoards(boards: List<TodoBoard>) = viewModelScope.launch { boards.forEachIndexed { index, board -> dao.setTodoBoardPosition(board.id, index) } }
    fun setTodoBoardsExpanded(boards: List<TodoBoard>, expanded: Boolean) = viewModelScope.launch { dao.setTodoBoardsExpanded(boards.map { it.id }, expanded) }
    fun deleteTodo(board: TodoBoard) = viewModelScope.launch { dao.deleteTodoBoard(board) }
    fun archiveTodos(boards: List<TodoBoard>) = viewModelScope.launch { dao.archiveTodoBoards(boards.map { it.id }) }
    fun deleteTodos(boards: List<TodoBoard>) = viewModelScope.launch { dao.deleteTodoBoards(boards.map { it.id }) }


    fun saveDiary(entry: DiaryEntry) = viewModelScope.launch { dao.saveDiary(entry) }
    fun deleteDiary(entry: DiaryEntry) = viewModelScope.launch { dao.deleteDiary(entry) }
    fun deleteDiaries(entries: List<DiaryEntry>) = viewModelScope.launch { dao.deleteDiaries(entries.map { it.id }) }
    fun addMemoryCategory(name: String) = viewModelScope.launch { dao.insertMemoryCategory(MemoryCategory(name = name.trim(), position = dao.nextMemoryCategoryPosition())) }
    fun updateMemoryCategory(category: MemoryCategory) = viewModelScope.launch { dao.updateMemoryCategory(category) }
    fun deleteMemoryCategory(category: MemoryCategory) = viewModelScope.launch { dao.deleteMemoryCategory(category) }
    fun addMemoryEntry(categoryId: Long, title: String, content: String, afterInsert: ((MemoryEntry) -> Unit)? = null) = viewModelScope.launch {
        val entry = MemoryEntry(categoryId = categoryId, title = title.trim(), content = content.trim(), position = dao.nextMemoryEntryPosition(categoryId))
        val id = dao.insertMemoryEntry(entry)
        afterInsert?.invoke(entry.copy(id = id))
    }
    fun updateMemoryEntry(entry: MemoryEntry) = viewModelScope.launch { dao.updateMemoryEntry(entry) }
    fun deleteMemoryEntry(entry: MemoryEntry) = viewModelScope.launch { dao.deleteMemoryEntry(entry) }
    fun reorderMemoryCategories(categories: List<MemoryCategory>) = viewModelScope.launch { categories.forEachIndexed { index, category -> dao.setMemoryCategoryPosition(category.id, index) } }
    fun reorderMemoryEntries(entries: List<MemoryEntry>) = viewModelScope.launch { entries.forEachIndexed { index, entry -> dao.setMemoryEntryPosition(entry.id, index) } }
    fun deleteMemoryCategories(categories: List<MemoryCategory>) = viewModelScope.launch { dao.deleteMemoryCategories(categories.map { it.id }) }
    fun deleteMemoryEntries(entries: List<MemoryEntry>) = viewModelScope.launch { dao.deleteMemoryEntries(entries.map { it.id }) }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { AppViewModel(application) }
        }
    }
}
