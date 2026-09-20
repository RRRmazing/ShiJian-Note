package com.shijiannote.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shijiannote.app.data.AppDatabase
import com.shijiannote.app.data.DiaryEntry
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
    val diaries = dao.observeDiaries()
    val memoryCategories = dao.observeMemoryCategories()

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
        val boardId = dao.insertTodoBoard(TodoBoard(summary = summary.trim(), dueDate = dueDate))
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

    fun deleteTodo(board: TodoBoard) = viewModelScope.launch { dao.deleteTodoBoard(board) }
    fun archiveTodos(boards: List<TodoBoard>) = viewModelScope.launch { dao.archiveTodoBoards(boards.map { it.id }) }
    fun deleteTodos(boards: List<TodoBoard>) = viewModelScope.launch { dao.deleteTodoBoards(boards.map { it.id }) }


    fun saveDiary(entry: DiaryEntry) = viewModelScope.launch { dao.saveDiary(entry) }
    fun deleteDiary(entry: DiaryEntry) = viewModelScope.launch { dao.deleteDiary(entry) }
    fun deleteDiaries(entries: List<DiaryEntry>) = viewModelScope.launch { dao.deleteDiaries(entries.map { it.id }) }
    fun addMemoryCategory(name: String) = viewModelScope.launch { dao.insertMemoryCategory(MemoryCategory(name = name.trim())) }
    fun updateMemoryCategory(category: MemoryCategory) = viewModelScope.launch { dao.updateMemoryCategory(category) }
    fun deleteMemoryCategory(category: MemoryCategory) = viewModelScope.launch { dao.deleteMemoryCategory(category) }
    fun addMemoryEntry(categoryId: Long, title: String, content: String) = viewModelScope.launch {
        dao.insertMemoryEntry(MemoryEntry(categoryId = categoryId, title = title.trim(), content = content.trim()))
    }
    fun updateMemoryEntry(entry: MemoryEntry) = viewModelScope.launch { dao.updateMemoryEntry(entry) }
    fun deleteMemoryEntry(entry: MemoryEntry) = viewModelScope.launch { dao.deleteMemoryEntry(entry) }
    fun deleteMemoryCategories(categories: List<MemoryCategory>) = viewModelScope.launch { dao.deleteMemoryCategories(categories.map { it.id }) }
    fun deleteMemoryEntries(entries: List<MemoryEntry>) = viewModelScope.launch { dao.deleteMemoryEntries(entries.map { it.id }) }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { AppViewModel(application) }
        }
    }
}
