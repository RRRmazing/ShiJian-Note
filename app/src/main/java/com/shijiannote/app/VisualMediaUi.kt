package com.shijiannote.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*

@Composable fun VisualMediaTile(block: NoteBlock, width: Dp = 110.dp, onOpen: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(block.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(block.uri) {
        bitmap = withContext(Dispatchers.IO) {
            if (block.type == "video") runCatching { withMediaMetadata { r ->
                r.setDataSource(context, Uri.parse(block.uri))
                if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 240) else r.getFrameAtTime(0)
            } }.getOrNull() else loadImage(context, block.uri, 360)
        }
    }
    Box(Modifier.width(width).height(if (block.type == "video") width * 9 / 16 else width)
        .clip(RoundedCornerShape(8.dp)).background(Mist).testTag("visual-media-${block.id}").clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), block.text, Modifier.fillMaxSize().testTag("visual-bitmap-${block.id}"), contentScale = ContentScale.Crop) }
            ?: Icon(if (block.type == "video") Icons.Default.Videocam else Icons.Default.BrokenImage, "素材预览", tint = Quiet)
        if (block.type == "video") {
            Icon(Icons.Default.PlayCircle, "播放视频", tint = Color.White, modifier = Modifier.size(32.dp))
            Text(audioTime(block.duration), color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = .5f)).padding(3.dp))
        }
    }
}

@Composable fun VideoViewer(block: NoteBlock, onError: (String) -> Unit, onClose: () -> Unit) {
    var view by remember { mutableStateOf<VideoView?>(null) }
    DisposableEffect(block.uri) { onDispose { view?.stopPlayback() } }
    Dialog(onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭视频", tint = Color.White) }
                    Text(block.text, color = Color.White, modifier = Modifier.weight(1f))
                }
                AndroidView(factory = { context -> VideoView(context).apply {
                    view = this
                    setMediaController(MediaController(context).also { it.setAnchorView(this) })
                    setOnErrorListener { _, _, _ -> onError("视频无法播放，请检查原文件或在问题日志重新绑定"); onClose(); true }
                    setVideoURI(Uri.parse(block.uri)); setOnPreparedListener { start() }
                } }, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

/** Never fall back to the document picker for photos: older devices get a MediaStore grid. */
@Composable fun rememberVisualMediaPicker(onSelected: (List<Uri>) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onSelected)
    var gallery by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { callback(it) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) gallery = true else onError("需要相册读取权限才能选择照片或视频")
    }
    if (gallery) LegacyMediaGallery({ gallery = false }, { gallery = false; callback(it) }, onError)
    return {
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        else permission.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
    }
}
private data class GalleryMedia(val block: NoteBlock, val album: String)
@Composable private fun LegacyMediaGallery(onClose: () -> Unit, onSelected: (List<Uri>) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    var media by remember { mutableStateOf<List<GalleryMedia>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var album by rememberSaveable { mutableStateOf("全部") }
    var albumsOpen by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) {
            val base = MediaStore.Files.getContentUri("external")
            val projection = arrayOf("_id", "media_type", "_display_name", "bucket_display_name", "duration")
            val rows = mutableListOf<GalleryMedia>()
            context.contentResolver.query(base, projection, "media_type IN (1,3)", null, "date_added DESC")?.use { c ->
                while (c.moveToNext()) {
                    val video = c.getInt(1) == 3
                    val uri = ContentUris.withAppendedId(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    rows.add(GalleryMedia(NoteBlock(id = uri.toString(), type = if (video) "video" else "image", text = c.getString(2).orEmpty(), uri = uri.toString(), duration = c.getLong(4)), c.getString(3).orEmpty().ifBlank { "其他" }))
                }
            }; rows
        } }.onSuccess { media = it }.onFailure { onError(it.message ?: "相册无法读取") }
        loading = false
    }
    Dialog(onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Mist) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭相册") }
                    TextButton(onClick = { albumsOpen = true }) { Text("图片和视频 · $album ▾") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                    items(media.filter { album == "全部" || it.album == album }, key = { it.block.uri }) { item ->
                        Box(Modifier.padding(2.dp)) {
                            VisualMediaTile(item.block, with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp - 20.dp) / 3 }) {
                                selected = if (item.block.uri in selected) selected - item.block.uri else selected + item.block.uri
                            }
                            Checkbox(item.block.uri in selected, { selected = if (it) (selected + item.block.uri).distinct() else selected - item.block.uri }, Modifier.align(Alignment.TopEnd))
                        }
                    }
                }
                Button(onClick = { onSelected(selected.map(Uri::parse)) }, enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("选择（${selected.size}）") }
            }
        }
    }
    if (albumsOpen) SoftDialog("选择相册", { albumsOpen = false }) {
        (listOf("全部") + media.map { it.album }.distinct()).forEach { name -> TextButton(onClick = { album = name; albumsOpen = false }) { Text(name) } }
    }
}

@Composable fun rememberSystemCapture(onCaptured: (Uri) -> Unit, onError: (String) -> Unit): (Boolean) -> Unit {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onCaptured)
    var output by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedVideo by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = output?.let(Uri::parse)
        if (uri != null) {
            if (result.resultCode == Activity.RESULT_OK) {
                runCatching {
                    require((context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0) != 0L) { "系统相机没有输出有效素材，可从相册重新选择" }
                    if (Build.VERSION.SDK_INT >= 29) context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    val prefs = context.getSharedPreferences("media_capture", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putStringSet("owned", prefs.getStringSet("owned", emptySet())!!.toSet() + uri.toString()).apply()
                    callback(uri)
                }.onFailure { runCatching { finishCancelledCapture(context, uri) }; onError("拍摄素材未能导入：${it.message}") }
            } else runCatching { finishCancelledCapture(context, uri) }
        }
        output = null
    }
    fun capture(video: Boolean) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "ShiJian-${System.currentTimeMillis()}" + if (video) ".mp4" else ".jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera"); put(MediaStore.MediaColumns.IS_PENDING, 1) }
            }
            val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = context.contentResolver.insert(collection, values) ?: error("无法建立相册输出")
            output = uri.toString()
            launcher.launch(Intent(if (video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                if (video) putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                clipData = ClipData.newRawUri("拍摄输出", uri)
            })
        }.onFailure {
            output?.let { value -> runCatching { finishCancelledCapture(context, Uri.parse(value)) } }; output = null
            onError("无法打开系统相机：${it.message}")
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) capture(requestedVideo) else onError("此系统版本需要存储权限才能将拍摄原件保留在相册")
    }
    return { video ->
        requestedVideo = video
        if (Build.VERSION.SDK_INT <= 28 && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else capture(video)
    }
}

internal fun finishCancelledCapture(context: android.content.Context, uri: Uri) {
    // A native camera may return cancellation after writing a valid photograph. Preserve any nonempty output.
    val size = try { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0 }
        catch (_: java.io.FileNotFoundException) { 0L }
    if (size == 0L) context.contentResolver.delete(uri, null, null)
    else if (Build.VERSION.SDK_INT >= 29) context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
}
