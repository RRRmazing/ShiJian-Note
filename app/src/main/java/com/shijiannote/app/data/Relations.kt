package com.shijiannote.app.data

import androidx.room.Embedded
import androidx.room.Relation

data class TodoBoardWithItems(
    @Embedded val board: TodoBoard,
    @Relation(parentColumn = "id", entityColumn = "boardId") val items: List<TodoItem>
)

data class MemoryCategoryWithEntries(
    @Embedded val category: MemoryCategory,
    @Relation(parentColumn = "id", entityColumn = "categoryId") val entries: List<MemoryEntry>
)

