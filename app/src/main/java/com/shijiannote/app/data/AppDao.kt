package com.shijiannote.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM schedule_events WHERE archived = 0 ORDER BY eventAt ASC")
    fun observeSchedule(): Flow<List<ScheduleEvent>>

    @Query("SELECT * FROM schedule_events WHERE archived = 1 ORDER BY eventAt DESC")
    fun observeArchivedSchedule(): Flow<List<ScheduleEvent>>

    @Insert suspend fun insertSchedule(event: ScheduleEvent): Long
    @Update suspend fun updateSchedule(event: ScheduleEvent)
    @Delete suspend fun deleteSchedule(event: ScheduleEvent)
    @Query("UPDATE schedule_events SET archived = 1 WHERE id IN (:ids)") suspend fun archiveSchedules(ids: List<Long>)
    @Query("DELETE FROM schedule_events WHERE id IN (:ids)") suspend fun deleteSchedules(ids: List<Long>)
    @Query("UPDATE schedule_events SET reminderTriggered = 1 WHERE id = :id") suspend fun markScheduleReminded(id: Long)
    @Query("UPDATE schedule_events SET archived = 1 WHERE archived = 0 AND eventAt < :now") suspend fun archiveExpiredSchedules(now: Long)

    @Transaction
    @Query("SELECT * FROM todo_boards WHERE archived = 0 ORDER BY CASE WHEN dueDate IS NULL THEN 1 ELSE 0 END, dueDate ASC, createdAt DESC")
    fun observeTodoBoards(): Flow<List<TodoBoardWithItems>>

    @Transaction
    @Query("SELECT * FROM todo_boards WHERE archived = 1 ORDER BY dueDate DESC, createdAt DESC")
    fun observeArchivedTodoBoards(): Flow<List<TodoBoardWithItems>>

    @Insert suspend fun insertTodoBoard(board: TodoBoard): Long
    @Insert suspend fun insertTodoItems(items: List<TodoItem>)
    @Update suspend fun updateTodoBoard(board: TodoBoard)
    @Update suspend fun updateTodoItem(item: TodoItem)
    @Query("DELETE FROM todo_items WHERE boardId = :boardId") suspend fun deleteTodoItemsForBoard(boardId: Long)
    @Delete suspend fun deleteTodoBoard(board: TodoBoard)
    @Query("UPDATE todo_boards SET archived = 1 WHERE id IN (:ids)") suspend fun archiveTodoBoards(ids: List<Long>)
    @Query("DELETE FROM todo_boards WHERE id IN (:ids)") suspend fun deleteTodoBoards(ids: List<Long>)
    @Query("UPDATE todo_boards SET archived = 1 WHERE archived = 0 AND dueDate IS NOT NULL AND dueDate < :today") suspend fun archiveExpiredTodoBoards(today: Long)

    @Query("SELECT * FROM diary_entries ORDER BY day DESC")
    fun observeDiaries(): Flow<List<DiaryEntry>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDiary(entry: DiaryEntry)
    @Delete suspend fun deleteDiary(entry: DiaryEntry)
    @Query("DELETE FROM diary_entries WHERE id IN (:ids)") suspend fun deleteDiaries(ids: List<Long>)

    @Transaction
    @Query("SELECT * FROM memory_categories ORDER BY createdAt DESC")
    fun observeMemoryCategories(): Flow<List<MemoryCategoryWithEntries>>

    @Insert suspend fun insertMemoryCategory(category: MemoryCategory): Long
    @Insert suspend fun insertMemoryEntry(entry: MemoryEntry): Long
    @Update suspend fun updateMemoryCategory(category: MemoryCategory)
    @Update suspend fun updateMemoryEntry(entry: MemoryEntry)
    @Delete suspend fun deleteMemoryCategory(category: MemoryCategory)
    @Delete suspend fun deleteMemoryEntry(entry: MemoryEntry)
    @Query("DELETE FROM memory_categories WHERE id IN (:ids)") suspend fun deleteMemoryCategories(ids: List<Long>)
    @Query("DELETE FROM memory_entries WHERE id IN (:ids)") suspend fun deleteMemoryEntries(ids: List<Long>)

}
