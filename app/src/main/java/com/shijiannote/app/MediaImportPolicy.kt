package com.shijiannote.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class MediaImportPolicy(val imageStorage: String, val videoStorage: String, val imageQuality: String, val videoQuality: String)
fun mediaImportDefaults(context: Context, imageStorage: String = imageStorageDefault(context)): MediaImportPolicy {
    val prefs = appPreferences(context)
    return MediaImportPolicy(imageStorage, prefs.getString("videoStorage", imageStorage)!!,
        prefs.getString("imageCopyQuality", "original")!!, prefs.getString("videoCopyQuality", "original")!!)
}
fun isMediaBlock(block: NoteBlock) = block.type in setOf("image", "video", "audio", "file", "link")
fun mediaKindLabel(type: String) = when (type) { "image" -> "照片"; "video" -> "视频"; "audio" -> "录音"; "file" -> "文件"; "link" -> "关联"; else -> "文字" }
fun retainMediaAccess(context: Context, uri: Uri) {
    if (uri.authority == context.packageName + ".files") return
    if (uri.scheme == "file") {
        val file = File(requireNotNull(uri.path)).canonicalFile
        require(file.path.startsWith(context.filesDir.canonicalPath + File.separator) && file.canRead()) { "此文件无法长期引用" }
        return
    }
    try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    catch (failure: SecurityException) {
        // Our legacy gallery uses MediaStore under a separately granted read permission.
        val permitted = if (Build.VERSION.SDK_INT < 33) context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        else listOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO,
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED").any { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        val ownsCapture = uri.authority == "media" && context.getSharedPreferences("media_capture", Context.MODE_PRIVATE).getStringSet("owned", emptySet())!!.contains(uri.toString())
        check(uri.authority == "media" && (permitted || ownsCapture)) { "此来源不能长期引用，请保存副本或重新授权" }
    }
}

@Composable fun MediaImportDialog(initial: MediaImportPolicy, images: Boolean, videos: Boolean, title: String = "导入素材", previews: List<NoteBlock> = emptyList(),
    onDismiss: () -> Unit, onConfirm: (MediaImportPolicy) -> Unit) {
    var imageStorage by rememberSaveable { mutableStateOf(initial.imageStorage) }
    var videoStorage by rememberSaveable { mutableStateOf(initial.videoStorage) }
    var imageQuality by rememberSaveable { mutableStateOf(initial.imageQuality) }
    var videoQuality by rememberSaveable { mutableStateOf(initial.videoQuality) }
    SoftDialog(title, onDismiss) {
        previews.forEach { block -> VisualMediaTile(block) { } }
        if (images) {
            ChoiceRow("本次照片保存", imageStorage, listOf("reference" to "引用原图", "copy" to "保存副本")) { imageStorage = it }
            if (imageStorage == "copy") ChoiceRow("照片副本质量", imageQuality,
                listOf("original" to "原文件", "high" to "高清", "small" to "节省空间")) { imageQuality = it }
        }
        if (videos) {
            ChoiceRow("本次视频保存", videoStorage, listOf("reference" to "引用原视频", "copy" to "保存副本")) { videoStorage = it }
            if (videoStorage == "copy") ChoiceRow("视频副本质量", videoQuality,
                listOf("original" to "原视频", "1080" to "1080p", "720" to "720p")) { videoQuality = it }
        }
        Text("仅用于这次导入，默认设置保持不变。原文件副本不重新编码；其他质量档位为压缩副本，原件保留。", color = Quiet)
        Button(onClick = { onConfirm(MediaImportPolicy(imageStorage, videoStorage, imageQuality, videoQuality)) },
            modifier = Modifier.fillMaxWidth()) { Text("加入片段") }
        TextButton(onClick = onDismiss) { Text("不加入") }
    }
}

suspend fun importVisualMedia(context: Context, uri: Uri, policy: MediaImportPolicy): NoteBlock {
    val info = withContext(Dispatchers.IO) { mediaInfo(context, uri) }
    val video = info.mime.startsWith("video/")
    require(video || info.mime.startsWith("image/")) { "请选择照片或视频" }
    val copy = (if (video) policy.videoStorage else policy.imageStorage) == "copy"
    val quality = if (video) policy.videoQuality else policy.imageQuality
    var block = if (!copy) {
        retainMediaAccess(context, uri)
        NoteBlock(type = if (video) "video" else "image", text = info.name, uri = uri.toString(), mime = info.mime, bytes = info.size)
    } else if (quality == "original") importMedia(context, uri, !video, copy = true).copy(type = if (video) "video" else "image")
    else if (video) compressVideoCopy(context, uri, info, quality) else compressImageCopy(context, uri, info, quality)
    if (video) block = block.copy(duration = withContext(Dispatchers.IO) { runCatching { videoMetadata(context, Uri.parse(block.uri)).duration }.getOrDefault(0) })
    return block
}
data class VideoMetadata(val width: Int, val height: Int, val rotation: Int, val duration: Long)
internal inline fun <T> withMediaMetadata(block: (MediaMetadataRetriever) -> T): T {
    val retriever = MediaMetadataRetriever()
    return try { block(retriever) } finally { retriever.release() }
}
fun videoMetadata(context: Context, uri: Uri): VideoMetadata = withMediaMetadata { retriever ->
    retriever.setDataSource(context, uri)
    fun number(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
    VideoMetadata(number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
        number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0)
}
internal fun scaledMediaDimensions(width: Int, height: Int, maxLongEdge: Int): Pair<Int, Int> {
    val scale = minOf(1.0, maxLongEdge.toDouble() / maxOf(width, height).coerceAtLeast(1))
    return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
}
private suspend fun compressImageCopy(context: Context, uri: Uri, info: MediaInfo, quality: String): NoteBlock = withContext(Dispatchers.IO) {
    require(info.mime !in setOf("image/gif", "image/webp")) { "动图或WebP请选原文件副本，以保留完整内容" }
    val maxEdge = if (quality == "high") 2560 else 1280
    val decoded = loadImage(context, uri.toString(), maxEdge * 2, orient = false) ?: error("图片无法解码，请选择原文件副本")
    var oriented: Bitmap = decoded
    var resized: Bitmap = decoded
    var file: File? = null
    try {
        val orientation = runCatching { context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } }.getOrNull()
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val size = scaledMediaDimensions(oriented.width, oriented.height, maxEdge)
        resized = Bitmap.createScaledBitmap(oriented, size.first, size.second, true)
        val png = info.mime == "image/png" && resized.hasAlpha()
        file = File(File(context.filesDir, "assets").apply { mkdirs() }, UUID.randomUUID().toString() + if (png) ".png" else ".jpg")
        file.outputStream().use { check(resized.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
            if (quality == "high") 90 else 80, it)) { "图片副本保存失败" } }
        NoteBlock(type = "image", text = info.name, uri = Uri.fromFile(file).toString(), owned = true,
            mime = if (png) "image/png" else "image/jpeg", bytes = file.length())
    } catch (failure: Throwable) { file?.delete(); throw failure }
    finally { setOf(decoded, oriented, resized).forEach { it.recycle() } }
}

@androidx.annotation.OptIn(UnstableApi::class)
private suspend fun compressVideoCopy(context: Context, uri: Uri, info: MediaInfo, quality: String): NoteBlock {
    val metadata = withContext(Dispatchers.IO) { videoMetadata(context, uri) }
    require(metadata.width > 0 && metadata.height > 0) { "视频尺寸无法读取，请选择原视频副本" }
    val limit = if (quality == "1080") 1080 else 720
    val targetShort = minOf(metadata.width, metadata.height, limit)
    val rotated = metadata.rotation % 180 != 0
    val outputHeight = if ((if (rotated) metadata.width else metadata.height) <= (if (rotated) metadata.height else metadata.width))
        targetShort else ((if (rotated) metadata.width else metadata.height).toDouble() * targetShort / minOf(metadata.width, metadata.height)).roundToInt()
    val file = File(File(context.filesDir, "assets").apply { mkdirs() }, UUID.randomUUID().toString() + ".mp4")
    try {
        withContext(Dispatchers.Main) {
            withTimeout(30 * 60_000L) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val encoder = DefaultEncoderFactory.Builder(context).setEnableFallback(false)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(if (quality == "1080") 8_000_000 else 4_000_000).build()).build()
                    val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setEncoderFactory(encoder)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: androidx.media3.transformer.ExportResult) { if (continuation.isActive) continuation.resume(Unit) }
                            override fun onError(composition: androidx.media3.transformer.Composition, exportResult: androidx.media3.transformer.ExportResult, exportException: ExportException) {
                                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("此视频无法生成压缩副本，请选原视频。原件保留。", exportException))
                            }
                        }).build()
                    continuation.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { transformer.cancel() } }
                    val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(outputHeight)))).build()
                    runCatching { transformer.start(edited, file.absolutePath) }.onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
                }
            }
        }
        require(file.length() > 0) { "视频副本为空" }
        return NoteBlock(type = "video", text = info.name, uri = Uri.fromFile(file).toString(), owned = true, mime = "video/mp4", bytes = file.length(), duration = metadata.duration)
    } catch (failure: Throwable) { withContext(NonCancellable + Dispatchers.IO) { file.delete() }; throw failure }
}
