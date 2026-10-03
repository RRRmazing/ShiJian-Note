package com.shijiannote.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** One stable identity for a folder, an independent memory, or a dated journal. */
@Entity(tableName = "note_nodes", indices = [Index("parentId"), Index("day")])
data class NoteNode(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val kind: String = "memory",
    val parentId: String? = null,
    val title: String = "",
    val text: String = "",
    val document: String = "",
    val day: Long? = null,
    val position: Int = 0,
    val pinned: Boolean = false,
    val favorite: Boolean = false,
    val tags: String = "",
    val mood: String = "",
    val imageDisplay: String = "inherit",
    val imageStorage: String = "inherit",
    val deletedAt: Long? = null,
    val deleteGroup: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "note_versions", indices = [Index("nodeId")])
data class NoteVersion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nodeId: String,
    val snapshot: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM note_nodes ORDER BY pinned DESC, position ASC, createdAt DESC")
    fun observeNodes(): Flow<List<NoteNode>>
    @Query("SELECT * FROM note_nodes") suspend fun nodes(): List<NoteNode>
    @Query("SELECT * FROM note_nodes WHERE id = :id") suspend fun node(id: String): NoteNode?
    @Query("SELECT * FROM note_nodes WHERE kind = 'diary' AND day = :day AND deletedAt IS NULL LIMIT 1")
    suspend fun diary(day: Long): NoteNode?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(node: NoteNode)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAll(nodes: List<NoteNode>)
    @Query("DELETE FROM note_nodes WHERE id IN (:ids)") suspend fun remove(ids: List<String>)
    @Query("SELECT * FROM note_versions WHERE nodeId = :id ORDER BY createdAt DESC, id DESC")
    suspend fun versions(id: String): List<NoteVersion>
    @Query("SELECT * FROM note_versions") suspend fun allVersions(): List<NoteVersion>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun version(version: NoteVersion)
    @Query("DELETE FROM note_versions WHERE nodeId IN (:ids)") suspend fun removeVersions(ids: List<String>)
}
