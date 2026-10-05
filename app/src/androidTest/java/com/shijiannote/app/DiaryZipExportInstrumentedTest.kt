package com.shijiannote.app

import android.graphics.Bitmap
import android.app.Application
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.shijiannote.app.data.NoteNode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipFile

class DiaryZipExportInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun entry(day: LocalDate = LocalDate.of(2026, 10, 5)) = NoteNode(id = "export-${UUID.randomUUID()}", kind = "diary", day = dayMillis(day), diaryRoadEnabled = true)

    @Test fun htmlIncludesOriginalMixedMediaInBlockOrderAndOmitsInboxAndDeletedDays(): Unit = runBlocking {
        val directory = File(context.cacheDir, "diary-export-test-${UUID.randomUUID()}").apply { mkdirs() }
        var output: File? = null
        try {
            val photo = File(directory, "photo.png").apply { outputStream().use { Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) } }
            val audio = File(directory, "audio.m4a").apply { writeBytes(byteArrayOf(1, 3, 5, 7)) }
            val attachment = File(directory, "attachment.pdf").apply { writeText("original PDF bytes") }
            val mixed = listOf(NoteBlock(text = "文字甲"), NoteBlock(type = "image", text = "../同名素材#100%", uri = Uri.fromFile(photo).toString()),
                NoteBlock(text = "文字乙"), NoteBlock(type = "audio", text = "../同名素材#100%", uri = Uri.fromFile(audio).toString(), duration = 24000),
                NoteBlock(type = "file", text = "../同名素材#100%", uri = Uri.fromFile(attachment).toString()), NoteBlock(text = "文字丙"),
                NoteBlock(type = "link", target = "linked-private"), NoteBlock(text = "加粗斜体", marks = "0:2:b;2:4:i"))
            val first = DiaryMoment(id = "first", title = "早发生晚发送", occurredAt = 1000, sentAt = 9000)
            val later = DiaryMoment(id = "later", title = "混合片段", occurredAt = 2000, sentAt = 3000, document = encodeBlocks(mixed), text = blockPlainText(mixed))
            val note = entry().copy(text = "正文保留", diaryRoadTheme = "spring", diaryRoadLayout = "right",
                diaryRoad = encodeDiaryMoments(listOf(later, first)), diaryInbox = encodeDiaryInbox(listOf(
                    DiaryInboxItem(status = "draft", moment = DiaryMoment(text = "绝不能导出的草稿")),
                    DiaryInboxItem(status = "deleted", moment = DiaryMoment(text = "绝不能导出的删除片段")))))
            val deleted = entry(LocalDate.of(2026, 10, 6)).copy(text = "整篇已删除不可导出", deletedAt = 123)
            val linked = NoteNode(id = "linked-private", title = "关联标题可见", text = "关联正文不可带出")
            val result = DiaryZipExport.export(context, listOf(note, deleted), listOf(note, deleted, linked), DiaryZipOptions())
            output = result.file
            ZipFile(result.file).use { zip ->
                val paths = zip.entries().asSequence().map { it.name }.toList()
                assertEquals(paths.size, paths.distinct().size)
                assertFalse(paths.any { it.contains("../") || it.contains('#') || it.contains('%') })
                assertTrue(paths.contains("index.html"))
                assertTrue(paths.contains("road/2026-10-05/background.webp"))
                assertTrue(paths.contains("diary/2026-10-05/index.html"))
                assertFalse(paths.any { it.contains("2026-10-06") })
                fun text(path: String) = zip.getInputStream(zip.getEntry(path)).bufferedReader().use { it.readText() }
                val html = text("road/2026-10-05/index.html")
                assertTrue(html.indexOf("早发生晚发送") < html.indexOf("混合片段"))
                assertTrue(html.indexOf("文字甲") < html.indexOf("<img "))
                assertTrue(html.indexOf("<img ") < html.indexOf("文字乙"))
                assertTrue(html.indexOf("文字乙") < html.indexOf("<audio "))
                assertTrue(html.indexOf("<audio ") < html.indexOf(".pdf"))
                assertTrue(html.indexOf(".pdf") < html.indexOf("文字丙"))
                assertTrue(html.contains("关联标题可见"))
                assertTrue(html.contains("<strong>加粗</strong><em>斜体</em>"))
                val combined = paths.filter { it.endsWith(".html") || it.endsWith(".json") }.joinToString("\n", transform = ::text)
                assertFalse(combined.contains("绝不能导出"))
                assertFalse(combined.contains("关联正文不可带出"))
                assertFalse(combined.contains("file:///"))
                val manifest = JSONObject(text("manifest.json"))
                assertEquals("occurred", manifest.getString("sort"))
                val road = manifest.getJSONArray("dates").getJSONObject(0).getJSONObject("road")
                assertEquals("right", road.getString("layout"))
                val records = road.getJSONArray("moments")
                assertEquals(1000L, records.getJSONObject(0).getLong("occurredAt"))
                assertEquals(9000L, records.getJSONObject(0).getLong("sentAt"))
                val blocks = records.getJSONObject(1).getJSONArray("blocks")
                assertEquals(listOf("text", "image", "text", "audio", "file", "text", "link", "text"), (0 until blocks.length()).map { blocks.getJSONObject(it).getString("type") })
                for ((index, source) in listOf(1 to photo, 3 to audio, 4 to attachment)) {
                    val path = blocks.getJSONObject(index).getString("mediaPath")
                    assertArrayEquals(source.readBytes(), zip.getInputStream(zip.getEntry(path)).use { it.readBytes() })
                    assertTrue(html.contains(path.removePrefix("road/2026-10-05/")))
                }
                assertTrue(text("index.html").contains("road/2026-10-05/index.html"))
            }
        } finally { output?.delete(); directory.deleteRecursively() }
    }

    @Test fun contentSelectionSkipsEmptySectionsAndBothOtherFormatsKeepTimeAndMedia(): Unit = runBlocking {
        val media = File(context.cacheDir, "zip-original-${UUID.randomUUID()}.bin").apply { writeText("original attachment") }
        val outputs = mutableListOf<File>()
        try {
            val moment = DiaryMoment(id = "moment", text = "随记", occurredAt = 3000, sentAt = 1000,
                document = encodeBlocks(listOf(NoteBlock(text = "随记"), NoteBlock(type = "file", text = "原附件", uri = Uri.fromFile(media).toString()))))
            val roadOnly = entry().copy(diaryRoad = encodeDiaryMoments(listOf(moment)))
            val summaryOnly = entry(LocalDate.of(2026, 10, 6)).copy(text = "只有正文")
            val inboxOnly = entry(LocalDate.of(2026, 10, 7)).copy(diaryInbox = encodeDiaryInbox(listOf(DiaryInboxItem(moment = DiaryMoment(text = "箱中草稿")))))
            val entries = listOf(roadOnly, summaryOnly, inboxOnly)
            assertEquals(DiaryZipCounts(3, 1, 1, 1), DiaryZipExport.counts(entries, DiaryZipOptions(road = true, diary = false)))
            for (format in listOf("md", "txt")) {
                val output = DiaryZipExport.export(context, entries, entries, DiaryZipOptions(road = true, diary = false, format = format, sort = "sent"))
                outputs += output.file
                ZipFile(output.file).use { zip ->
                    val paths = zip.entries().asSequence().map { it.name }.toList()
                    assertFalse(paths.any { it.startsWith("diary/") || it.contains("2026-10-06") || it.contains("2026-10-07") })
                    val content = zip.getInputStream(zip.getEntry("road/2026-10-05/index.$format")).bufferedReader().use { it.readText() }
                    assertTrue(content.contains("发生：")); assertTrue(content.contains("发送：")); assertTrue(content.contains("原附件"))
                    assertTrue(content.contains("media/001-"))
                    val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
                    assertEquals("sent", manifest.getString("sort"))
                }
            }
            val summary = DiaryZipExport.export(context, entries, entries, DiaryZipOptions(road = false, diary = true))
            outputs += summary.file
            ZipFile(summary.file).use { zip -> assertFalse(zip.entries().asSequence().any { it.name.startsWith("road/") }) }
        } finally { outputs.forEach { it.delete() }; media.delete() }
    }

    @Test fun missingMediaFailsWithoutProducingAPartialSuccessfulArchive(): Unit = runBlocking {
        val note = entry().copy(document = encodeBlocks(listOf(NoteBlock(type = "audio", text = "缺失录音", uri = "file:///missing-${UUID.randomUUID()}.m4a"))))
        val directory = File(context.cacheDir, "exports")
        val before = directory.listFiles().orEmpty().map { it.name }.toSet()
        val failure = runCatching { DiaryZipExport.export(context, listOf(note), listOf(note), DiaryZipOptions(road = false, diary = true)) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("素材无法读取"))
        assertEquals(before, directory.listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test fun unusedImportedBackgroundsAreProtectedBackedUpAndRemapped(): Unit = runBlocking {
        lateinit var model: WorkspaceModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model = WorkspaceModel(context.applicationContext as Application) }
        model.spacesReady.first { it }
        val assets = File(context.filesDir, "assets").apply { mkdirs() }
        val imported = File(assets, "unused-background-${UUID.randomUUID()}.png").apply { writeText("library background bytes"); setLastModified(System.currentTimeMillis() - 3 * 86400000L) }
        val default = File(assets, "default-background-${UUID.randomUUID()}.png").apply { writeText("default background bytes"); setLastModified(System.currentTimeMillis() - 3 * 86400000L) }
        val importedUri = Uri.fromFile(imported).toString()
        val defaultUri = Uri.fromFile(default).toString()
        val preferences = appPreferences(context)
        val previousLibrary = preferences.getString("diary_background_library", null)
        val previousDefault = preferences.getString("diary_default_background_uri", null)
        var output: File? = null
        try {
            preferences.edit().putString("diary_background_library", JSONArray().put(JSONObject().put("id", "fixture").put("name", "测试背景").put("uri", importedUri).put("createdAt", 1)).toString())
                .putString("diary_default_background_uri", defaultUri).commit()
            assertFalse(unusedAssets(context, model).any { it.canonicalPath in setOf(imported.canonicalPath, default.canonicalPath) })
            val backup = ExportEngine.backup(context, model, ExportOptions())
            output = backup.file
            ZipFile(backup.file).use { zip ->
                val manifest = JSONObject(zip.getInputStream(zip.getEntry("backup.json")).bufferedReader().use { it.readText() })
                assertEquals(14, manifest.getInt("databaseVersion"))
                val mappings = manifest.getJSONObject("assets")
                assertTrue(mappings.has(importedUri)); assertTrue(mappings.has(defaultUri))
                assertArrayEquals(imported.readBytes(), zip.getInputStream(zip.getEntry(mappings.getString(importedUri))).use { it.readBytes() })
                val savedPrefs = manifest.getJSONObject("preferences")
                ExportEngine.remapBackgroundPreferences(savedPrefs, mapOf(importedUri to "file:///restored/library.png", defaultUri to "file:///restored/default.png"))
                assertEquals("file:///restored/library.png", JSONArray(savedPrefs.getString("diary_background_library")).getJSONObject(0).getString("uri"))
                assertEquals("file:///restored/default.png", savedPrefs.getString("diary_default_background_uri"))
            }
        } finally {
            preferences.edit().apply {
                if (previousLibrary == null) remove("diary_background_library") else putString("diary_background_library", previousLibrary)
                if (previousDefault == null) remove("diary_default_background_uri") else putString("diary_default_background_uri", previousDefault)
            }.commit()
            output?.delete(); imported.delete(); default.delete()
        }
    }
}
