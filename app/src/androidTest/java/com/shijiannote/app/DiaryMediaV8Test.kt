package com.shijiannote.app

import android.app.Application
import android.app.NotificationManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.shijiannote.app.data.NoteNode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class DiaryMediaV8Test {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun model(): WorkspaceModel {
        lateinit var m: WorkspaceModel
        rule.runOnUiThread { m = WorkspaceModel(rule.activity.application as Application) }
        runBlocking { m.spacesReady.first { it } }; return m
    }
    private fun file(ext: String) = File(File(rule.activity.filesDir, "assets").apply { mkdirs() }, "v8-test-${UUID.randomUUID()}.$ext")
    private fun uri(f: File) = FileProvider.getUriForFile(rule.activity, rule.activity.packageName + ".files", f)
    private fun note(blocks: List<NoteBlock>) = NoteNode(id = "v8-${UUID.randomUUID()}", title = "媒体检查测试", document = encodeBlocks(blocks))
    private fun seed(m: WorkspaceModel, n: NoteNode) = runBlocking { m.notes.put(n); m.nodes.first { all -> all.any { it.id == n.id } } }
    private fun cleanup(m: WorkspaceModel, n: NoteNode) = runBlocking {
        rule.runOnUiThread { rule.activity.setContent { YouthTheme {} } }
        m.flushAll(); m.notes.remove(listOf(n.id)); m.notes.removeVersions(listOf(n.id))
        MediaResourceHealth.scan(rule.activity, m, true)
    }
    @Test fun indexIncludesSummaryMomentsDraftsAndRecoveredRoadsButNotTrash() {
        val shared = NoteBlock(type = "video", text = "测试视频", uri = "file:///test/shared.mp4")
        val audio = NoteBlock(type = "audio", uri = "file:///test/audio.m4a")
        val m = DiaryMoment(document = encodeBlocks(listOf(shared, audio)))
        val n = note(listOf(shared)).copy(kind = "diary", day = dayMillis(), diaryRoad = encodeDiaryMoments(listOf(m)),
            diaryInbox = encodeDiaryInbox(listOf(DiaryInboxItem(moment = m.copy(id = UUID.randomUUID().toString())),
                DiaryInboxItem(status = "road", road = encodeDiaryMoments(listOf(m))))))
        val refs = mediaReferenceIndex(listOf(n, n.copy(id = "trashed", deletedAt = 1)))
        assertEquals(2, refs.size)
        assertEquals(4, refs.first { it.block.uri == shared.uri }.locations.size)
        assertEquals(3, refs.first { it.block.uri == audio.uri }.locations.size)
        assertTrue(refs.flatMap { it.locations }.all { it.nodeId == n.id })
    }
    @Test fun originalsCopyByteForByteAndReferencesDoNotCreateAssets() = runBlocking {
        val image = file("png"); val video = file("mp4"); val created = mutableListOf<File>()
        try {
            Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).also { b -> image.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
            InstrumentationRegistry.getInstrumentation().context.assets.open("media-test.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
            val original = MediaImportPolicy("copy", "copy", "original", "original")
            val imageCopy = importVisualMedia(rule.activity, uri(image), original); created.add(File(Uri.parse(imageCopy.uri).path!!))
            val videoCopy = importVisualMedia(rule.activity, uri(video), original); created.add(File(Uri.parse(videoCopy.uri).path!!))
            assertArrayEquals(image.readBytes(), created[0].readBytes()); assertArrayEquals(video.readBytes(), created[1].readBytes())
            assertEquals("video", videoCopy.type); assertTrue(videoCopy.duration > 0)
            val before = File(rule.activity.filesDir, "assets").listFiles()!!.map { it.name }.toSet()
            val reference = importVisualMedia(rule.activity, uri(video), original.copy(videoStorage = "reference"))
            assertFalse(reference.owned); assertEquals(uri(video).toString(), reference.uri)
            assertEquals(before, File(rule.activity.filesDir, "assets").listFiles()!!.map { it.name }.toSet())
        } finally { (created + image + video).forEach { it.delete() } }
    }
    @Test fun imageCompressionRespectsSizeTransparencyAndOriginal() = runBlocking {
        val source = file("png"); var output: File? = null
        try {
            val bitmap = Bitmap.createBitmap(3000, 1800, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0x80449977.toInt()); source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val original = source.readBytes()
            val b = importVisualMedia(rule.activity, uri(source), MediaImportPolicy("copy", "reference", "high", "original"))
            output = File(Uri.parse(b.uri).path!!)
            val decoded = android.graphics.BitmapFactory.decodeFile(output.absolutePath)
            assertEquals(2560, decoded.width); assertEquals(1536, decoded.height); assertTrue(decoded.hasAlpha()); decoded.recycle()
            assertEquals("image/png", b.mime); assertArrayEquals(original, source.readBytes())
        } finally { output?.delete(); source.delete() }
    }
    @Test fun videoCompressionKeepsAudioAndOriginalAndCapsResolution() = runBlocking {
        val source = file("mp4"); var output: File? = null
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("media-test.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
            val original = source.readBytes()
            val b = importVisualMedia(rule.activity, uri(source), MediaImportPolicy("reference", "copy", "original", "720"))
            output = File(Uri.parse(b.uri).path!!)
            val meta = videoMetadata(rule.activity, Uri.parse(b.uri))
            assertEquals(1280, meta.width); assertEquals(720, meta.height); assertTrue(meta.duration > 0)
            withMediaMetadata { r -> r.setDataSource(output.absolutePath); assertEquals("yes", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)) }
            assertArrayEquals(original, source.readBytes())
        } finally { output?.delete(); source.delete() }
    }
    @Test fun missingResourceIsDeduplicatedAndRecoveryClearsWithoutChangingBlocks() = runBlocking {
        val m = model(); val missing = file("bin")
        val b = NoteBlock(type = "file", uri = Uri.fromFile(missing).toString(), text = "失联附件")
        val n = note(listOf(b, b.copy(id = UUID.randomUUID().toString())))
        seed(m, n)
        try {
            MediaResourceHealth.scan(rule.activity, m, true)
            val issue = MediaResourceHealth.issues.value.single { it.uri == b.uri }
            assertEquals(2, issue.locations.size); assertEquals("原文件无法访问", issue.reason)
            val first = issue.firstFound
            MediaResourceHealth.scan(rule.activity, m, true)
            assertEquals(first, MediaResourceHealth.issues.value.single { it.uri == b.uri }.firstFound)
            assertEquals(n.document, m.notes.node(n.id)!!.document)
            missing.writeText("recovered")
            MediaResourceHealth.scan(rule.activity, m, true)
            assertTrue(MediaResourceHealth.issues.value.none { it.uri == b.uri })
            assertEquals(n.document, m.notes.node(n.id)!!.document)
        } finally { cleanup(m, n); missing.delete() }
    }
    @Test fun transientProviderFailureDoesNotImmediatelyReportDeletion() = runBlocking {
        val m = model(); val b = NoteBlock(type = "image", uri = "content://cloud-test-${UUID.randomUUID()}/unavailable", text = "云端照片")
        val n = note(listOf(b)); seed(m, n)
        try {
            MediaResourceHealth.scan(rule.activity, m, true)
            assertTrue(MediaResourceHealth.issues.value.none { it.uri == b.uri })
        } finally { cleanup(m, n) }
    }
    @Test fun rebindUsesStableBlockInRecoverableInboxAndClearsIssue() = runBlocking {
        val m = model(); val missing = file("mp4"); val source = file("mp4")
        val b = NoteBlock(type = "video", uri = Uri.fromFile(missing).toString(), text = "原视频名称")
        val moment = DiaryMoment(document = encodeBlocks(listOf(b)))
        val inbox = DiaryInboxItem(status = "deleted", moment = moment)
        val n = note(emptyList()).copy(kind = "diary", day = dayMillis(java.time.LocalDate.of(1968, 1, 8)), diaryInbox = encodeDiaryInbox(listOf(inbox)))
        seed(m, n)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("media-test.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
            MediaResourceHealth.scan(rule.activity, m, true)
            val location = MediaResourceHealth.issues.value.single { it.uri == b.uri }.locations.single()
            rebindMedia(rule.activity, m, location, uri(source))
            val rebound = m.notes.node(n.id)!!.diaryInboxItems().single()
            assertEquals("deleted", rebound.status); assertEquals(inbox.id, rebound.id); assertEquals(moment.id, rebound.moment!!.id)
            assertEquals(b.id, rebound.moment.blocks().single().id); assertEquals(b.text, rebound.moment.blocks().single().text)
            assertEquals(uri(source).toString(), rebound.moment.blocks().single().uri)
            assertTrue(MediaResourceHealth.issues.value.none { it.uri == b.uri })
        } finally { cleanup(m, n); source.delete() }
    }
    @Test fun cancelledCameraKeepsNonemptyOriginalAndRemovesOnlyEmptyPlaceholder() {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val resolver = rule.activity.contentResolver
        val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "v8-camera-test.jpg"); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); put(MediaStore.Images.Media.IS_PENDING, 1) }
        val full = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        val empty = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        try {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            resolver.openOutputStream(full)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }; bitmap.recycle()
            finishCancelledCapture(rule.activity, full); finishCancelledCapture(rule.activity, empty)
            assertTrue(resolver.openAssetFileDescriptor(full, "r")!!.use { it.length } > 0)
            resolver.query(full, arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            resolver.query(empty, arrayOf("_id"), null, null, null)!!.use { assertEquals(0, it.count) }
        } finally { resolver.delete(full, null, null); runCatching { resolver.delete(empty, null, null) } }
    }
    @Test fun removalNeedsConfirmationAndUndoRestoresVideo() {
        val m = model(); val n = note(emptyList()).copy(kind = "diary", day = dayMillis(java.time.LocalDate.of(1959, 1, 9)))
        seed(m, n)
        lateinit var state: DiaryMomentComposerState
        try {
            rule.runOnUiThread { state = DiaryMomentComposerState(m, n.day!!) { throw AssertionError(it) }; state.insert(NoteBlock(type = "video", text = "测试视频", uri = "content://video-ui/unavailable")) }
            rule.activity.setContent { YouthTheme { DiaryMomentComposer(state, androidx.compose.ui.unit.Dp(500f), {}, {}, {}, { }) } }
            rule.onNodeWithText("留下这一刻…").assertDoesNotExist()
            rule.onNodeWithContentDescription("移除此素材").performClick()
            rule.onNodeWithText("是否移除此视频？").assertExists()
            rule.onNodeWithText("取消").performClick()
            rule.runOnUiThread { assertEquals("video", state.blocks.single().type) }
            rule.onNodeWithContentDescription("移除此素材").performClick(); rule.onNodeWithText("移除", useUnmergedTree = true).performClick()
            rule.runOnUiThread { assertEquals("text", state.blocks.single().type); state.undo(); assertEquals("video", state.blocks.single().type) }
        } finally { cleanup(m, n) }
    }
    @Test fun defaultCaptureHiddenMenuDoesNotDuplicateRecordingAndTagsStayInFormatRow() {
        val m = model(); val n = note(emptyList()).copy(kind = "diary", day = dayMillis(java.time.LocalDate.of(1958, 1, 9)))
        val prefs = appPreferences(rule.activity); val before = prefs.getBoolean("diaryAllowCapture", false)
        seed(m, n)
        try {
            prefs.edit().putBoolean("diaryAllowCapture", false).commit()
            val state = DiaryMomentComposerState(m, n.day!!) { }
            rule.activity.setContent { YouthTheme { DiaryMomentComposer(state, androidx.compose.ui.unit.Dp(500f), {}, {}, {}, {}) } }
            rule.onNodeWithContentDescription("添加素材").performClick()
            rule.onNodeWithText("从相册选择图片或视频").assertExists()
            rule.onNodeWithText("录音").assertDoesNotExist(); rule.onNodeWithText("拍摄照片或视频").assertDoesNotExist()
            androidx.test.espresso.Espresso.pressBack()
            rule.onNodeWithContentDescription("新增标签").assertExists()
            val keyboard = rule.onNodeWithContentDescription("键盘").fetchSemanticsNode().boundsInRoot.center.x
            val time = rule.onNodeWithContentDescription("发生时间").fetchSemanticsNode().boundsInRoot.center.x
            val stage = rule.onNodeWithContentDescription("暂存").fetchSemanticsNode().boundsInRoot.center.x
            assertTrue(time < keyboard && keyboard < stage)
            prefs.edit().putBoolean("diaryAllowCapture", true).commit()
            rule.onNodeWithContentDescription("添加素材").performClick(); rule.onNodeWithText("拍摄照片或视频").assertExists()
        } finally { prefs.edit().putBoolean("diaryAllowCapture", before).commit(); cleanup(m, n) }
    }
    @Test fun thumbnailDimensionsAndSeparateRemoveButton() {
        val m = model(); val n = note(emptyList()).copy(kind = "diary", day = dayMillis(java.time.LocalDate.of(1957, 1, 9)))
        val photo = file("png"); val video = file("mp4")
        seed(m, n)
        try {
            val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xFF659876.toInt())
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            InstrumentationRegistry.getInstrumentation().context.assets.open("media-test.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
            val imageBlock = NoteBlock(type = "image", text = "照片", uri = Uri.fromFile(photo).toString())
            val videoBlock = NoteBlock(type = "video", text = "视频", uri = Uri.fromFile(video).toString(), duration = 1200)
            val state = DiaryMomentComposerState(m, n.day!!) { }
            rule.runOnUiThread { state.insert(imageBlock); state.insert(videoBlock); state.insert(NoteBlock(text = "今天的小片段")) }
            rule.activity.setContent { YouthTheme { Column(Modifier.statusBarsPadding()) { DiaryMomentComposer(state, androidx.compose.ui.unit.Dp(600f), {}, {}, {}, {}) } } }
            rule.waitUntil(10_000) {
                rule.onAllNodesWithTag("visual-bitmap-${imageBlock.id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    rule.onAllNodesWithTag("visual-bitmap-${videoBlock.id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val imageRect = rule.onNodeWithTag("visual-media-${imageBlock.id}").fetchSemanticsNode().boundsInRoot
            val videoRect = rule.onNodeWithTag("visual-media-${videoBlock.id}").fetchSemanticsNode().boundsInRoot
            assertEquals(imageRect.width, imageRect.height, 1f)
            assertEquals(imageRect.width, videoRect.width, 1f)
            assertEquals(9f / 16f, videoRect.height / videoRect.width, .01f)
            val close = rule.onAllNodesWithContentDescription("移除此素材")[0].fetchSemanticsNode().boundsInRoot
            assertTrue(close.left >= imageRect.right - 1)
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.also { shot ->
                File(rule.activity.filesDir, "media-v8-editor.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }; shot.recycle()
            }
        } finally { cleanup(m, n); photo.delete(); video.delete() }
    }
    @Test fun notificationOpensLogAndExactBlockAndViewingKeepsIssueNumber(): Unit = runBlocking {
        val m = model(); val missing = file("mp4")
        val b = NoteBlock(type = "video", text = "通知跳转视频", uri = Uri.fromFile(missing).toString())
        val n = note((0 until 20).map { NoteBlock(text = "前面的文字 $it") } + b)
        seed(m, n)
        val ctx = rule.activity
        if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        try {
            MediaResourceHealth.scan(ctx, m, true)
            val manager = ctx.getSystemService(NotificationManager::class.java)
            val notification = manager.activeNotifications.first { it.id == 5401 }
            val before = notification.postTime
            MediaResourceHealth.scan(ctx, m, true)
            assertEquals(before, manager.activeNotifications.first { it.id == 5401 }.postTime)
            rule.runOnUiThread {
                // Retain ActivityScenario's launch tracker when exercising the notification's destination action.
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(ctx,
                    android.content.Intent(ctx.intent).setAction(MediaResourceHealth.ACTION_OPEN))
            }
            rule.waitUntil(15_000) { rule.onAllNodesWithText("问题日志").fetchSemanticsNodes().isNotEmpty() }
            val issue = MediaResourceHealth.issues.value.single { it.uri == b.uri }
            rule.onNodeWithText(issue.locations.single().path).performScrollTo().performClick()
            rule.onNodeWithTag("media-focused-${b.id}").assertIsDisplayed()
            assertTrue(MediaResourceHealth.issues.value.any { it.uri == b.uri })
            rule.onNodeWithContentDescription("保存并返回").performClick()
            rule.onNodeWithText("问题日志").assertExists()
        } finally { cleanup(m, n); managerCancel(ctx) }
    }
    private fun managerCancel(context: android.content.Context) = context.getSystemService(NotificationManager::class.java).cancel(5401)
    @Test fun videoExportAndMaterialRemappingPreserveOriginalAndMetadata() = runBlocking {
        val source = file("mp4"); var output: File? = null
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("media-test.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
            val b = NoteBlock(type = "video", text = "视频原件", uri = Uri.fromFile(source).toString(), owned = true, mime = "video/mp4", duration = 1200)
            val moment = DiaryMoment(document = encodeBlocks(listOf(b)), sentAt = 100)
            val n = note(emptyList()).copy(kind = "diary", day = dayMillis(java.time.LocalDate.of(2001, 1, 9)), diaryRoad = encodeDiaryMoments(listOf(moment)))
            val result = DiaryZipExport.export(rule.activity, listOf(n), listOf(n), DiaryZipOptions())
            output = result.file
            java.util.zip.ZipFile(output).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val video = entries.single { it.name.endsWith(".mp4") }
                assertArrayEquals(source.readBytes(), zip.getInputStream(video).use { it.readBytes() })
                val html = zip.getInputStream(entries.first { it.name.startsWith("road/") && it.name.endsWith("index.html") }).bufferedReader().use { it.readText() }
                assertTrue(html.contains("<video controls"))
            }
            val rebound = n.remapMaterials(mapOf(b.uri to "file:///restored/video.mp4")).diaryMoments().single().blocks().single()
            assertEquals("video", rebound.type); assertEquals(b.id, rebound.id); assertEquals(b.duration, rebound.duration)
            assertEquals("file:///restored/video.mp4", rebound.uri)
        } finally { output?.delete(); source.delete() }
    }
    @Test fun portraitPhotoPreviewAndCompressedCopyKeepExifOrientation() = runBlocking {
        val source = file("jpg"); var output: File? = null
        try {
            val bitmap = Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }; bitmap.recycle()
            androidx.exifinterface.media.ExifInterface(source.absolutePath).apply {
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, "6"); saveAttributes()
            }
            val original = source.readBytes()
            val preview = loadImage(rule.activity, uri(source).toString())!!
            assertEquals(500, preview.width); assertEquals(1000, preview.height); preview.recycle()
            val b = importVisualMedia(rule.activity, uri(source), MediaImportPolicy("copy", "reference", "small", "original"))
            output = File(Uri.parse(b.uri).path!!)
            val copy = android.graphics.BitmapFactory.decodeFile(output.absolutePath)
            assertEquals(500, copy.width); assertEquals(1000, copy.height); copy.recycle()
            assertArrayEquals(original, source.readBytes())
        } finally { output?.delete(); source.delete() }
    }
    @Test fun portraitVideoCompressionKeepsAspectRatioAndAudio() = runBlocking {
        val source = file("mp4"); val rotated = file("mp4"); var output: File? = null
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("media-test.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
            val extractor = android.media.MediaExtractor(); val muxer = android.media.MediaMuxer(rotated.path, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                extractor.setDataSource(source.path)
                val tracks = (0 until extractor.trackCount).map { i -> extractor.selectTrack(i); muxer.addTrack(extractor.getTrackFormat(i)) }
                muxer.setOrientationHint(90); muxer.start()
                val buffer = java.nio.ByteBuffer.allocate(1024 * 1024); val info = android.media.MediaCodec.BufferInfo()
                while (true) {
                    buffer.clear(); val size = extractor.readSampleData(buffer, 0); if (size < 0) break
                    info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                    muxer.writeSampleData(tracks[extractor.sampleTrackIndex], buffer, info); extractor.advance()
                }; muxer.stop()
            } finally { extractor.release(); muxer.release() }
            assertEquals(90, videoMetadata(rule.activity, uri(rotated)).rotation)
            val b = importVisualMedia(rule.activity, uri(rotated), MediaImportPolicy("reference", "copy", "original", "720"))
            output = File(Uri.parse(b.uri).path!!)
            val meta = videoMetadata(rule.activity, Uri.parse(b.uri))
            val width = if (meta.rotation % 180 == 0) meta.width else meta.height
            val height = if (meta.rotation % 180 == 0) meta.height else meta.width
            assertEquals(720, width); assertEquals(1280, height)
            withMediaMetadata { r -> r.setDataSource(output.path); assertEquals("yes", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)) }
        } finally { output?.delete(); source.delete(); rotated.delete() }
    }
}
