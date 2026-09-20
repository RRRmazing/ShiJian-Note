package com.shijiannote.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM schedule_events ORDER BY eventAt ASC")
    fun observeSchedule(): Flow<List<ScheduleEvent>>

    @Insert suspend fun insertSchedule(event: ScheduleEvent): Long
    @Update suspend fun updateSchedule(event: ScheduleEvent)

    @Transaction
    @Query("SELECT * FROM todo_boards ORDER BY CASE WHEN dueDate IS NULL THEN 1 ELSE 0 END, dueDate ASC, createdAt DESC")
    fun observeTodoBoards(): Flow<List<TodoBoardWithItems>>

    @Insert suspend fun insertTodoBoard(board: TodoBoard): Long
    @Insert suspend fun insertTodoItems(items: List<TodoItem>)
    @Update suspend fun updateTodoBoard(board: TodoBoard)
    @Update suspend fun updateTodoItem(item: TodoItem)

    @Query("SELECT * FROM diary_entries ORDER BY day DESC")
    fun observeDiaries(): Flow<List<DiaryEntry>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDiary(entry: DiaryEntry)

    @Transaction
    @Query("SELECT * FROM memory_categories ORDER BY createdAt DESC")
    fun observeMemoryCategories(): Flow<List<MemoryCategoryWithEntries>>

    @Insert suspend fun insertMemoryCategory(category: MemoryCategory): Long
    @Insert suspend fun insertMemoryEntry(entry: MemoryEntry): Long
}

