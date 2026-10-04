package com.shijiannote.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class MediaInfo(val name: String, val size: Long, val mime: String)
data class ImageFailure(val nodeId: String, val blockId: String, val path: String, val name: String, val uri: String, val reason: String)

/** Retrofitting a copy preserves block identity, formatting and display overrides. */
suspend fun copyImage(context: Context, block: NoteBlock): NoteBlock = withContext(Dispatchers.IO) {
    val ext = block.text.substringAfterLast('.', "img").take(10).filter(Char::isLetterOrDigit).ifBlank { "img" }
    val file = File(File(context.filesDir, "assets").apply { mkdirs() }, "${UUID.randomUUID()}.$ext")
    try {
        context.contentResolver.openInputStream(Uri.parse(block.uri))?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: error("原图片不存在或访问权限已失效")
        require(file.length() > 0) { "原图片为空，无法保存副本" }
        block.copy(uri = Uri.fromFile(file).toString(), owned = true, bytes = file.length())
    } catch (e: Exception) { file.delete(); throw e }
}
fun mediaInfo(context: Context, uri: Uri): MediaInfo {
    var name = "附件"
    var size = 0L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val s = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (n >= 0) name = cursor.getString(n) ?: name
            if (s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s)
        }
    }
    return MediaInfo(name, size, context.contentResolver.getType(uri) ?: "application/octet-stream")
}
suspend fun importMedia(context: Context, uri: Uri, image: Boolean, copy: Boolean): NoteBlock = withContext(Dispatchers.IO) {
    val info = mediaInfo(context, uri)
    val stored = if (copy) {
        val ext = info.name.substringAfterLast('.', "bin").take(10).filter { it.isLetterOrDigit() }.ifBlank { "bin" }
        val file = File(File(context.filesDir, "assets").apply { mkdirs() }, "${UUID.randomUUID()}.$ext")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: error("无法读取所选文件")
            Uri.fromFile(file)
        } catch (e: Exception) { file.delete(); throw e }
    } else {
        try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (e: SecurityException) { error("此来源不能长期引用，请选择本地文件或保存图片副本") }
        uri
    }
    NoteBlock(type = if (image) "image" else "file", text = info.name, uri = stored.toString(), owned = copy, mime = info.mime, bytes = info.size)
}
suspend fun loadImage(context: Context, uri: String, maxPixels: Int = 1600): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val parsed = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxPixels || bounds.outHeight / sample > maxPixels) sample *= 2
        context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }.getOrNull()
}
fun openFile(context: Context, block: NoteBlock): String? = try {
    val original = Uri.parse(block.uri)
    // Open once to detect missing/revoked files rather than launching a broken target.
    context.contentResolver.openInputStream(original)?.use { } ?: error("原文件不可访问")
    val uri = if (original.scheme == "file") FileProvider.getUriForFile(context, "${context.packageName}.files", File(original.path!!)) else original
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, block.mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "打开 ${block.text}"))
    null
} catch (_: android.content.ActivityNotFoundException) { "手机上没有支持此文件的应用" }
catch (_: Exception) { "原文件不可访问，可以重新绑定" }
fun mediaSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / (1024.0 * 1024))
}
fun audioTime(ms: Long): String = "%02d:%02d".format(ms / 60_000, ms / 1000 % 60)
