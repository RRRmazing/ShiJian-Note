package com.shijiannote.app

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.shijiannote.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

class DiaryV12MigrationInstrumentedTest {
    @Test fun realVersionTwelveUpgradePreservesEveryDiaryFieldBlockMediaVersionAndTrash() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val name = "diary-v12-complete-$token.db"
        val assets = File(context.filesDir, "assets").apply { mkdirs() }
        val photo = File(assets, "$token.png").apply { writeBytes(byteArrayOf(1, 4, 8, 15)) }
        val audio = File(assets, "$token.m4a").apply { writeText("untouched voice payload") }
        val file = File(assets, "$token.pdf").apply { writeText("untouched file payload") }
        val v14Fields = listOf("diaryRoad", "diaryRoadTheme", "diaryRoadBackground", "diaryRoadEnabled", "diaryRoadLayout", "diaryInbox", "diaryOccurredAt", "diaryTrashExpiresAt")
        val target = NoteNode(id = "link-$token", kind = "memory", title = "被关联的旧记忆", text = "目标原文", createdAt = 10, updatedAt = 20)
        val blocks = listOf(
            NoteBlock(id = "heading", type = "heading1", text = "标题：旧日记 🌿", bold = true),
            NoteBlock(id = "body", text = "第一行保持原样。\n第二行  空格与标点；\n末尾换行\n", italic = true),
            NoteBlock(id = "check", type = "check", text = "已经完成", checked = true),
            NoteBlock(id = "photo", type = "image", text = "自己的照片.png", uri = Uri.fromFile(photo).toString(), owned = true, mime = "image/png", bytes = photo.length(), display = "preview"),
            NoteBlock(id = "reference", type = "image", text = "外部引用图片", uri = "content://legacy/provider/image", owned = false, mime = "image/jpeg"),
            NoteBlock(id = "voice", type = "audio", text = "自己的录音", uri = Uri.fromFile(audio).toString(), owned = true, mime = "audio/mp4", bytes = audio.length(), duration = 12345),
            NoteBlock(id = "file", type = "file", text = "自己的文件.pdf", uri = Uri.fromFile(file).toString(), owned = true, mime = "application/pdf", bytes = file.length()),
            NoteBlock(id = "link", type = "link", text = "关联标题", target = target.id)
        )
        val plain = NoteNode(id = "plain-$token", kind = "diary", day = dayMillis(LocalDate.of(2024, 1, 1)),
            title = "纯文本标题", text = "纯文本第一行\n第二行，emoji 😊\n", tags = "生活,原标签", mood = "平静", favorite = true,
            createdAt = 100, updatedAt = 200)
        val rich = NoteNode(id = "rich-$token", kind = "diary", day = dayMillis(LocalDate.of(2024, 1, 2)),
            title = "完整富文本标题", text = blockPlainText(blocks), document = encodeBlocks(blocks), tags = "工作,旅途", mood = "开心",
            favorite = true, pinned = true, imageDisplay = "preview", imageStorage = "copy", createdAt = 300, updatedAt = 400)
        val trash = rich.copy(id = "trash-$token", day = dayMillis(LocalDate.of(2024, 1, 3)), title = "回收站原日记",
            deletedAt = 500, deleteGroup = "trash-group-$token", updatedAt = 600)
        fun legacySnapshot(node: NoteNode): String = jsonObject(node).apply { v14Fields.forEach(::remove) }.toString()
        val oldRichSnapshot = legacySnapshot(rich.copy(title = "更早的标题", text = "更早的原文", updatedAt = 250))
        val trashSnapshot = legacySnapshot(trash)
        val initial = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        initial.noteDao().putAll(listOf(plain, rich, trash, target))
        initial.noteDao().version(NoteVersion(nodeId = rich.id, snapshot = oldRichSnapshot, createdAt = 123))
        initial.noteDao().version(NoteVersion(nodeId = trash.id, snapshot = trashSnapshot, createdAt = 456))
        initial.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
            v14Fields.forEach { sqlite.execSQL("ALTER TABLE note_nodes DROP COLUMN $it") }
            sqlite.execSQL("DROP TABLE room_master_table")
            sqlite.version = 12
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.migration12To13, AppDatabase.migration13To14).build()
        try {
            val notes = migrated.noteDao()
            assertEquals(plain, notes.node(plain.id))
            assertEquals(rich, notes.node(rich.id))
            assertEquals(trash, notes.node(trash.id))
            assertEquals(target, notes.node(target.id))
            listOf(plain.id, rich.id, trash.id).forEach { id ->
                val diary = notes.node(id)!!
                assertFalse(diary.diaryRoadEnabled)
                assertTrue(diary.diaryRoad.isEmpty())
                assertTrue(diary.diaryInbox.isEmpty())
            }
            assertEquals(rich.document, notes.node(rich.id)!!.document)
            assertEquals(blocks, notes.node(rich.id)!!.blocks())
            assertEquals(oldRichSnapshot, notes.versions(rich.id).single().snapshot)
            assertEquals(trashSnapshot, notes.versions(trash.id).single().snapshot)
            assertEquals(123L, notes.versions(rich.id).single().createdAt)
            assertEquals(456L, notes.versions(trash.id).single().createdAt)
            assertEquals("更早的标题", decodeNode(JSONObject(notes.versions(rich.id).single().snapshot)).title)
            assertArrayEquals(byteArrayOf(1, 4, 8, 15), photo.readBytes())
            assertEquals("untouched voice payload", audio.readText())
            assertEquals("untouched file payload", file.readText())
        } finally { migrated.close(); context.deleteDatabase(name); photo.delete(); audio.delete(); file.delete() }
    }
}
