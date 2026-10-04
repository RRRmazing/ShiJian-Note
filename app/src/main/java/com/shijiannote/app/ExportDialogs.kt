package com.shijiannote.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import java.io.File

@Composable fun NoteExportDialog(model: WorkspaceModel, ids: Set<String>, onClose: () -> Unit) {
    val context = LocalContext.current
    val nodes by model.nodes.collectAsState()
    val selected = ExportEngine.scope(ids, nodes)
    val diariesOnly = selected.isNotEmpty() && selected.all { it.kind == "diary" }
    val scope = rememberCoroutineScope()
    var images by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(false) }
    var structureTree by remember { mutableStateOf(false) }
    var pdf by remember { mutableStateOf(false) }
    var expandImages by remember { mutableStateOf(true) }
    var inspection by remember { mutableStateOf<ExportInspection?>(null) }
    var result by remember { mutableStateOf<ExportResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { out -> result!!.file.inputStream().use { it.copyTo(out) } } ?: error("无法保存") }.onSuccess { withContext(Dispatchers.Main) { error = "已保存到所选位置" } }.onFailure { withContext(Dispatchers.Main) { error = "保存失败，请重试" } } }
    }
    LaunchedEffect(ids, nodes) { inspection = ExportEngine.inspect(context, selected) }
    SoftDialog("导出内容", { if (!busy) onClose() }) {
        inspection?.let { Text((if (diariesOnly) "${it.notes} 篇日记" else "${it.folders} 个分类 · ${it.notes} 条记录") + "\n随包保存 ${it.owned} 个素材\n外部引用：${it.images} 张图片、${it.files} 个文件", fontSize = 14.sp); if (it.missing.isNotEmpty()) Text("无法读取：${it.missing.joinToString("、")}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (selected.size == 1 && selected.first().kind != "folder") ChoiceRow("导出格式", if (pdf) "pdf" else "markdown", listOf("markdown" to "Markdown / ZIP", "pdf" to "PDF")) { if (!busy) { pdf = it == "pdf"; result = null } }
        else Text(if (diariesOnly) "多篇日记导出为 ZIP，保留独立文档。" else "分类和多篇记录导出为 ZIP，保留独立文档与目录。", color = Quiet, fontSize = 12.sp)
        if (pdf) {
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(expandImages, { expandImages = it; result = null }, enabled = !busy); Text("在 PDF 中展开图片", Modifier.weight(1f)) }
            Text("PDF 不包含可播放录音和原附件，需要实际素材请选 ZIP。", color = Quiet, fontSize = 12.sp)
        } else {
            if (selected.any { it.kind == "folder" }) {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(structureTree, { structureTree = it; result = null }, enabled = !busy); Text("包含结构树形图", Modifier.weight(1f)) }
                Text("每个所选顶层分类分别生成一份结构树；独立记忆不生成。", color = Quiet, fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(images, { images = it; result = null }, enabled = !busy); Text("将引用图片复制进本次导出包", Modifier.weight(1f)) }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(files, { files = it; result = null }, enabled = !busy); Text("将绑定文件复制进本次导出包", Modifier.weight(1f)) }
            Text("不改变 App 的保存方式。未复制的引用在其他设备上可能无法打开。", color = Quiet, fontSize = 12.sp)
        }
        Button(onClick = { busy = true; error = ""; scope.launch {
            runCatching { model.flushAll(); val latest = model.notes.nodes(); val exportScope = ExportEngine.scope(ids, latest); if (pdf) ExportEngine.pdf(context, exportScope, latest, expandImages) else ExportEngine.notes(context, exportScope, latest, ExportOptions(images, files, structureTree)) }
                .onSuccess { result = it }.onFailure { error = it.message ?: "导出失败" }; busy = false
        } }, enabled = !busy && selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在生成…" else "生成导出文件") }
        result?.let { r ->
            Text("已生成 ${r.file.extension.uppercase()} · ${mediaSize(r.file.length())}", color = Sky)
            if (r.missing.isNotEmpty()) Text("缺失素材：${r.missing.joinToString("、")}。已保留引用说明。", color = MaterialTheme.colorScheme.error)
            if (r.file.extension == "pdf") OutlinedButton(onClick = { error = openFile(context, NoteBlock(uri = Uri.fromFile(r.file).toString(), mime = "application/pdf", text = r.file.name)).orEmpty() }) { Text("预览 PDF") }
            else OutlinedButton(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) {
                if (r.file.extension == "md") r.file.readText().take(16000)
                else java.util.zip.ZipFile(r.file).use { zip -> zip.entries().asSequence().joinToString("\n") { it.name }.take(16000) }
            } }.onSuccess { preview = it }.onFailure { error = "预览失败" } } }) { Text(if (r.file.extension == "md") "预览正文" else "查看包内目录") }
            Button(onClick = { save.launch(r.file.name) }) { Text("保存到…") }
        }
        if (error.isNotBlank()) Text(error, color = Quiet)
    }
    preview?.let { text -> SoftDialog("导出预览", { preview = null }) { Text(text, fontSize = 13.sp); TextButton(onClick = { preview = null }) { Text("关闭") } } }
}

@Composable fun BackupDialog(model: WorkspaceModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recording by RecordingService.state.collectAsState()
    var images by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ExportResult?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) scope.launch(Dispatchers.IO) {
        runCatching { context.contentResolver.openOutputStream(uri)?.use { out -> result!!.file.inputStream().use { it.copyTo(out) } } ?: error("无法保存") }.onSuccess { withContext(Dispatchers.Main) { status = "备份已保存" } }.onFailure { withContext(Dispatchers.Main) { status = "保存失败" } }
    } }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreUri = it }
    SoftDialog("备份与恢复", { if (!busy) onClose() }) {
        Text("备份包括记录、分类树、格式、历史版本、回收站、待办、时间表、设置，以及时笺保存的图片和录音。", fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(images, { images = it; result = null }, enabled = !busy); Text("额外打包引用图片", Modifier.weight(1f)) }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(files, { files = it; result = null }, enabled = !busy); Text("额外打包绑定文件", Modifier.weight(1f)) }
        Text("没有打包的外部引用，在换设备后可能需要重新绑定。", color = Quiet, fontSize = 12.sp)
        Button(onClick = { busy = true; scope.launch { runCatching { ExportEngine.backup(context, model, ExportOptions(images, files)) }.onSuccess { result = it; status = if (it.missing.isEmpty()) "备份已生成" else "备份已生成，缺失：${it.missing.joinToString("、")}" }.onFailure { status = it.message ?: "备份失败" }; busy = false } }, enabled = !busy && !recording.running, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "处理中…" else "生成完整备份") }
        result?.let { Button(onClick = { save.launch(it.file.name) }) { Text("保存备份到…") } }
        OutlinedButton(onClick = { open.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !busy && !recording.running, modifier = Modifier.fillMaxWidth()) { Text("选择备份恢复") }
        val recovery = remember(result, status) { File(context.filesDir, "recovery").listFiles().orEmpty().sortedByDescending { it.lastModified() } }
        recovery.take(3).forEach { f -> TextButton(onClick = { result = ExportResult(f, emptyList(), 0) }) { Text("导出恢复前备份 · ${dateText(f.lastModified(), true)}") } }
        if (recording.running) Text("请先结束录音，再备份或恢复。", color = Quiet)
        if (status.isNotBlank()) Text(status, color = Quiet)
    }
    restoreUri?.let { uri -> SoftDialog("恢复备份", { if (!busy) restoreUri = null }) {
        Text("将替换本机现有数据。恢复前会自动生成本机恢复备份；你也可以先导出现有备份。")
        Button(onClick = { busy = true; scope.launch { runCatching { ExportEngine.restore(context, model, uri) }.onSuccess { status = it }.onFailure { status = "恢复失败：${it.message}" }; busy = false; restoreUri = null } }, enabled = !busy) { Text(if (busy) "恢复中…" else "恢复并替换") }
        TextButton(onClick = { restoreUri = null }, enabled = !busy) { Text("取消") }
    } }
}
@Composable fun TaskExportDialog(model: WorkspaceModel, type: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(if (type == "schedule") "text/calendar" else "text/csv")) { uri -> if (uri != null) scope.launch(Dispatchers.IO) {
        runCatching { context.contentResolver.openOutputStream(uri)?.use { out -> file!!.inputStream().use { it.copyTo(out) } } ?: error("无法保存") }.onSuccess { withContext(Dispatchers.Main) { status = "已保存" } }.onFailure { withContext(Dispatchers.Main) { status = "保存失败" } }
    } }
    LaunchedEffect(type) { runCatching { ExportEngine.taskExport(context, model, type) }.onSuccess { file = it }.onFailure { status = it.message.orEmpty() } }
    SoftDialog(if (type == "schedule") "导出时间表" else "导出待办", onClose) { Text(if (type == "schedule") "ICS 包含正常与历史事务，可导入日历软件。" else "CSV 包含清单、事项、完成状态、计划日期、截止与提醒时间。"); Button(onClick = { file?.let { save.launch(it.name) } }, enabled = file != null) { Text("保存到…") }; if (status.isNotBlank()) Text(status) }
}
