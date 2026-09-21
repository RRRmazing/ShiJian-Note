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
    @Query("SELECT * FROM todo_boards WHERE archived = 0 ORDER BY CASE WHEN boardType = 'LIST' THEN 0 ELSE 1 END, position ASC, createdAt DESC")
    fun observeTodoBoards(): Flow<List<TodoBoardWithItems>>

    @Transaction
    @Query("SELECT * FROM todo_boards WHERE archived = 1 ORDER BY dueDate DESC, createdAt DESC")
    fun observeArchivedTodoBoards(): Flow<List<TodoBoardWithItems>>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM todo_boards WHERE archived = 0 AND boardType = 'LIST'") suspend fun nextTodoBoardPosition(): Int
    @Query("UPDATE todo_boards SET position = :position WHERE id = :boardId") suspend fun setTodoBoardPosition(boardId: Long, position: Int)
    @Query("UPDATE todo_boards SET expanded = :expanded WHERE id IN (:ids)") suspend fun setTodoBoardsExpanded(ids: List<Long>, expanded: Boolean)

    @Insert suspend fun insertTodoBoard(board: TodoBoard): Long
    @Insert suspend fun insertTodoItems(items: List<TodoItem>): List<Long>
    @Update suspend fun updateTodoBoard(board: TodoBoard)
    @Update suspend fun updateTodoItem(item: TodoItem)
    @Delete suspend fun deleteTodoItem(item: TodoItem)
    @Query("DELETE FROM todo_items WHERE boardId = :boardId") suspend fun deleteTodoItemsForBoard(boardId: Long)
    @Delete suspend fun deleteTodoBoard(board: TodoBoard)
    @Query("UPDATE todo_boards SET archived = 1 WHERE id IN (:ids)") suspend fun archiveTodoBoards(ids: List<Long>)
    @Query("DELETE FROM todo_boards WHERE id IN (:ids)") suspend fun deleteTodoBoards(ids: List<Long>)
    @Query("UPDATE todo_boards SET archived = 1 WHERE archived = 0 AND dueDate IS NOT NULL AND dueDate < :today") suspend fun archiveExpiredTodoBoards(today: Long)

    @Transaction
    @Query("SELECT * FROM todo_boards WHERE archived = 0 AND boardType IN ('TODAY', 'TOMORROW') ORDER BY CASE boardType WHEN 'TODAY' THEN 0 ELSE 1 END")
    fun observeDailyTodoBoards(): Flow<List<TodoBoardWithItems>>

    @Query("SELECT * FROM daily_todo_preferences WHERE id = 1")
    fun observeDailyTodoPreferences(): Flow<DailyTodoPreferences?>

    @Query("SELECT * FROM daily_todo_preferences WHERE id = 1")
    suspend fun getDailyTodoPreferences(): DailyTodoPreferences?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDailyTodoPreferences(preferences: DailyTodoPreferences)

    @Query("SELECT * FROM todo_boards WHERE archived = 0 AND boardType = :type LIMIT 1")
    suspend fun findDailyBoard(type: String): TodoBoard?

    @Query("SELECT COUNT(*) FROM todo_items WHERE boardId = :boardId")
    suspend fun countTodoItems(boardId: Long): Int

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM todo_items WHERE boardId = :boardId")
    suspend fun nextTodoPosition(boardId: Long): Int

    @Query("SELECT COALESCE(MIN(position), 0) - 1 FROM todo_items WHERE boardId = :boardId")
    suspend fun topTodoPosition(boardId: Long): Int

    @Query("UPDATE todo_items SET completed = :completed, position = :position WHERE id = :itemId")
    suspend fun setTodoCompletion(itemId: Long, completed: Boolean, position: Int)

    @Query("UPDATE todo_items SET important = NOT important WHERE id = :itemId")
    suspend fun toggleTodoImportant(itemId: Long)

    @Query("UPDATE todo_items SET reminderTriggered = 1 WHERE id = :itemId")
    suspend fun markTodoReminded(itemId: Long)

    @Query("UPDATE todo_boards SET reminderTriggered = 1 WHERE id = :boardId")
    suspend fun markTodoBoardReminded(boardId: Long)

    @Query("SELECT * FROM todo_boards WHERE id = :boardId LIMIT 1")
    suspend fun getTodoBoard(boardId: Long): TodoBoard?

    @Query("UPDATE todo_items SET position = :position WHERE id = :itemId")
    suspend fun setTodoPosition(itemId: Long, position: Int)

    @Transaction
    suspend fun prepareDailyPages(today: Long) {
        val preferences = getDailyTodoPreferences() ?: DailyTodoPreferences(lastRolloverDay = today).also { saveDailyTodoPreferences(it) }
        if (preferences.showToday && findDailyBoard("TODAY") == null) insertTodoBoard(TodoBoard(summary = "今天", boardType = "TODAY"))
        if (preferences.showTomorrow && findDailyBoard("TOMORROW") == null) insertTodoBoard(TodoBoard(summary = "明天", boardType = "TOMORROW"))
    }

    /** One idempotent daily step. Its caller persists the checkpoint only after this returns. */
    @Transaction
    suspend fun rollDailyPages(historyTitle: String) {
        val preferences = getDailyTodoPreferences() ?: return
        val todayBoard = findDailyBoard("TODAY")
        if (todayBoard != null) {
            if (countTodoItems(todayBoard.id) > 0) updateTodoBoard(todayBoard.copy(summary = historyTitle, boardType = "HISTORY", archived = true))
            else deleteTodoBoard(todayBoard)
        }
        val tomorrowBoard = findDailyBoard("TOMORROW")
        if (preferences.showToday && tomorrowBoard != null) updateTodoBoard(tomorrowBoard.copy(summary = "今天", boardType = "TODAY"))
        if (preferences.showTomorrow) {
            if (findDailyBoard("TOMORROW") == null) insertTodoBoard(TodoBoard(summary = "明天", boardType = "TOMORROW"))
        }
    }

    @Transaction
    suspend fun setDailyPageVisible(type: String, visible: Boolean) {
        val preferences = getDailyTodoPreferences() ?: DailyTodoPreferences().also { saveDailyTodoPreferences(it) }
        val updated = if (type == "TODAY") preferences.copy(showToday = visible, showTomorrow = if (visible) preferences.showTomorrow else false) else preferences.copy(showTomorrow = visible, showToday = if (visible) true else preferences.showToday)
        saveDailyTodoPreferences(updated)
        if (!visible) findDailyBoard(type)?.let { deleteTodoBoard(it) }
        if (visible && findDailyBoard(type) == null) insertTodoBoard(TodoBoard(summary = if (type == "TODAY") "今天" else "明天", boardType = type))
        if (type == "TOMORROW" && visible && findDailyBoard("TODAY") == null) insertTodoBoard(TodoBoard(summary = "今天", boardType = "TODAY"))
    }

    @Query("SELECT * FROM diary_entries ORDER BY day DESC")
    fun observeDiaries(): Flow<List<DiaryEntry>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDiary(entry: DiaryEntry)
    @Delete suspend fun deleteDiary(entry: DiaryEntry)
    @Query("DELETE FROM diary_entries WHERE id IN (:ids)") suspend fun deleteDiaries(ids: List<Long>)

    @Transaction
    @Query("SELECT * FROM memory_categories ORDER BY position ASC, createdAt DESC")
    fun observeMemoryCategories(): Flow<List<MemoryCategoryWithEntries>>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM memory_categories") suspend fun nextMemoryCategoryPosition(): Int
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM memory_entries WHERE categoryId = :categoryId") suspend fun nextMemoryEntryPosition(categoryId: Long): Int
    @Query("UPDATE memory_categories SET position = :position WHERE id = :id") suspend fun setMemoryCategoryPosition(id: Long, position: Int)
    @Query("UPDATE memory_entries SET position = :position WHERE id = :id") suspend fun setMemoryEntryPosition(id: Long, position: Int)
    @Insert suspend fun insertMemoryCategory(category: MemoryCategory): Long
    @Insert suspend fun insertMemoryEntry(entry: MemoryEntry): Long
    @Update suspend fun updateMemoryCategory(category: MemoryCategory)
    @Update suspend fun updateMemoryEntry(entry: MemoryEntry)
    @Delete suspend fun deleteMemoryCategory(category: MemoryCategory)
    @Delete suspend fun deleteMemoryEntry(entry: MemoryEntry)
    @Query("DELETE FROM memory_categories WHERE id IN (:ids)") suspend fun deleteMemoryCategories(ids: List<Long>)
    @Query("DELETE FROM memory_entries WHERE id IN (:ids)") suspend fun deleteMemoryEntries(ids: List<Long>)

}
