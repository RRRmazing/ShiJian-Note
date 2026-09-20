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
    val todoBoards = dao.observeTodoBoards()
    val diaries = dao.observeDiaries()
    val memoryCategories = dao.observeMemoryCategories()

    fun addSchedule(event: ScheduleEvent, afterInsert: (ScheduleEvent) -> Unit) = viewModelScope.launch {
        val id = dao.insertSchedule(event)
        afterInsert(event.copy(id = id))
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

    fun saveDiary(entry: DiaryEntry) = viewModelScope.launch { dao.saveDiary(entry) }
    fun addMemoryCategory(name: String) = viewModelScope.launch { dao.insertMemoryCategory(MemoryCategory(name = name.trim())) }
    fun addMemoryEntry(categoryId: Long, title: String, content: String) = viewModelScope.launch {
        dao.insertMemoryEntry(MemoryEntry(categoryId = categoryId, title = title.trim(), content = content.trim()))
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { AppViewModel(application) }
        }
    }
}
