package com.shijiannote.app

import android.net.Uri
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*

@Composable fun MediaProblemsScreen(model: WorkspaceModel, onBack: () -> Unit, onOpen: (MediaLocation) -> Unit) {
    val context = LocalContext.current
    val issues by MediaResourceHealth.issues.collectAsState()
    val scanning by MediaResourceHealth.scanning.collectAsState()
    var days by remember { mutableStateOf(MediaResourceHealth.interval(context).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var rebinding by rememberSaveable { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val selected = rebinding; rebinding = emptyList()
        if (uri != null && selected.size == 6) scope.launch {
            busy = true
            runCatching { rebindMedia(context, model, MediaLocation(selected[0], selected[1].ifBlank { null }, selected[2].ifBlank { null }, selected[3], selected[4].toInt(), selected[5]), uri) }
                .onFailure { error = it.message ?: "重新绑定失败" }
            busy = false
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageTitle("问题日志", "${issues.size}项待处理素材", onBack) }
        item {
            ChoiceRow("自动检查频率", days, listOf("1" to "每天", "3" to "每3天", "7" to "每7天", "0" to "仅手动")) {
                days = it; MediaResourceHealth.setInterval(context, it.toInt())
            }
            Text("检查图片、视频、录音和附件是否仍可访问。后台检查时间由系统安排，打开应用时补查到期项目。相同素材的多处引用算一项问题。", color = Quiet, fontSize = 12.sp)
            OutlinedButton(onClick = { scope.launch { runCatching { MediaResourceHealth.scan(context, model, true) }.onFailure { error = it.message ?: "检查失败，原日志保留" } } }, enabled = !scanning && !busy) {
                Text(if (scanning) "正在检查…" else "立即重新检查")
            }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }) { Text("通知与访问权限") }
            if (android.os.Build.VERSION.SDK_INT >= 33) TextButton(onClick = { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) { Text("开启素材问题通知") }
            Text("如果近期删除过这些原件，请检查系统相册或文件管理器的回收站，尝试恢复。恢复或重新授权后重新检查，问题会自动消除。", color = Quiet, fontSize = 13.sp)
        }
        if (issues.isEmpty()) item { SoftCard { Text(if (scanning) "检查中…" else "目前没有待处理的素材问题") } }
        items(issues, key = { it.uri }) { issue -> SoftCard {
            Text("${mediaKindLabel(issue.type)} · ${issue.name}")
            Text(issue.reason, color = MaterialTheme.colorScheme.error)
            Text("首次发现 ${diaryTimestamp(issue.firstFound)}\n最近检查 ${diaryTimestamp(issue.lastChecked)}", color = Quiet, fontSize = 12.sp)
            issue.locations.forEach { location ->
                TextButton(onClick = { onOpen(location) }) { Text(location.path) }
                OutlinedButton(onClick = {
                    rebinding = listOf(location.nodeId, location.momentId.orEmpty(), location.inboxId.orEmpty(), location.blockId, location.ordinal.toString(), location.path)
                    picker.launch(arrayOf(when (issue.type) { "image" -> "image/*"; "video" -> "video/*"; "audio" -> "audio/*"; else -> "*/*" }))
                }, enabled = !busy && !scanning) { Text("为此处重新绑定") }
            }
        } }
    }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }) }
}

@Composable fun ReferencedMediaScreen(model: WorkspaceModel, onBack: () -> Unit, onOpen: (MediaLocation) -> Unit) {
    val nodes by model.nodes.collectAsState()
    val references = remember(nodes) { mediaReferenceIndex(nodes).filter { !it.block.owned && it.block.type in setOf("image", "video") } }
    var viewing by remember { mutableStateOf<NoteBlock?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageTitle("已引用照片与视频", "${references.size}项原文件引用", onBack) }
        item { Text("这里列出照片和视频在时笺中的使用位置。", color = Quiet) }
        if (references.isEmpty()) item { Text("还没有引用的照片或视频", color = Quiet) }
        items(references, key = { it.block.uri }) { reference -> SoftCard {
            VisualMediaTile(reference.block) { viewing = reference.block }
            Text(reference.block.text)
            reference.locations.forEach { location -> TextButton(onClick = { onOpen(location) }) { Text(location.path) } }
        } }
    }
    viewing?.let { block -> if (block.type == "video") VideoViewer(block, { error = it }) { viewing = null }
        else ImageViewer(listOf(block), block.id) { viewing = null } }
    error?.let { AlertDialog(onDismissRequest = { error = null }, text = { Text(it) }, confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }) }
}
