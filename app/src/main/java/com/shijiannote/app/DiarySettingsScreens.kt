package com.shijiannote.app

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shijiannote.app.data.NoteNode
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun DiaryMomentTimePanel(parent: NoteNode, moment: DiaryMoment, model: WorkspaceModel,
    onOccurredAt: (Long?) -> Unit, onViewPosition: ((Long?, String) -> Unit)? = null, initiallyExpanded: Boolean = false) {
    val context = LocalContext.current
    val nodes by model.nodes.collectAsState()
    val revision by model.diaryRevision.collectAsState()
    val current = remember(nodes, revision, parent.day) { model.currentDiary(parent.day ?: dayMillis()) }
    val order = appPreferences(context).getString("diary_time_sort", "occurred") ?: "occurred"
    var picker by rememberSaveable(moment.id) { mutableStateOf(false) }
    var expanded by rememberSaveable(moment.id) { mutableStateOf(initiallyExpanded) }
    val now = System.currentTimeMillis()
    val followsSend = moment.occurredAt == null || moment.sentAt != null && moment.occurredAt == moment.sentAt
    val neighbors = diaryNeighbors(current.diaryMoments(), moment, order, now)
    SoftCard(color = Lavender) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (followsSend) "发生时间跟随发送" else "发生 ${diaryTimestamp(moment.occurredAt!!, parent.day)}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("当前按${if (order == "sent") "发送" else "发生"}时间排列", fontSize = 11.sp, color = Quiet)
            }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "时间与位置") }
        }
        if (expanded) {
        Row(Modifier.fillMaxWidth().clickable { onOccurredAt(null) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(followsSend, { onOccurredAt(null) })
            Column { Text("跟随发送时间"); Text(if (moment.sentAt == null) "实际发送时确定日期和时分秒" else diaryTimestamp(moment.sentAt!!), fontSize = 12.sp, color = Quiet) }
        }
        Row(Modifier.fillMaxWidth().clickable { picker = true }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(!followsSend, { picker = true })
            Column { Text("指定发生日期与时间"); Text(moment.occurredAt?.let { diaryTimestamp(it) } ?: "适合迟记或过去日期补记", fontSize = 12.sp, color = Quiet) }
        }
        if (moment.sentAt != null) Text("原发送：${diaryTimestamp(moment.sentAt!!)}", color = Quiet, fontSize = 12.sp)
        HorizontalDivider()
        Text("保存后的位置 · ${if (order == "sent") "按发送时间" else "按发生时间"}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        DiaryNeighborOverview(neighbors, current.day, order)
        if (order == "sent" && moment.sentAt == null) Text("新片段按实际发送时间排列，修改发生时间不会改变发送顺序。", fontSize = 12.sp, color = Quiet)
        onViewPosition?.let { callback -> TextButton(onClick = { callback(moment.occurredAt, moment.id) }) { Text("在小路中查看") } }
        }
    }
    if (picker) DiaryDateTimePicker(moment.occurredAt ?: moment.sentAt ?: now, { picker = false }) { at -> onOccurredAt(at); picker = false }
}

@Composable
fun DiaryNeighborOverview(neighbors: DiaryNeighbors, day: Long?, order: String) {
    fun caption(moment: DiaryMoment): String {
        val at = if (order == "sent") moment.sentAt else moment.occurredAt ?: moment.sentAt
        return diaryTimestamp(at ?: moment.createdAt, day) + " · " + DiaryLibraryRules.momentPreview(moment)
    }
    if (neighbors.previous == null && neighbors.next == null) Text("将成为第一个片段", fontSize = 12.sp, color = Quiet)
    else {
        Text("前一条：${neighbors.previous?.let(::caption) ?: "起点"}", fontSize = 12.sp, color = Quiet, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("后一条：${neighbors.next?.let(::caption) ?: "小路末尾"}", fontSize = 12.sp, color = Quiet, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun DiaryDateTimePicker(initial: Long, onDismiss: () -> Unit, onSelect: (Long) -> Unit) {
    val context = LocalContext.current
    val local = remember(initial) { Instant.ofEpochMilli(initial).atZone(ZoneId.systemDefault()) }
    var date by rememberSaveable(initial) { mutableStateOf(local.toLocalDate().toString()) }
    var hour by rememberSaveable(initial) { mutableStateOf("%02d".format(local.hour)) }
    var minute by rememberSaveable(initial) { mutableStateOf("%02d".format(local.minute)) }
    var second by rememberSaveable(initial) { mutableStateOf("%02d".format(local.second)) }
    val value = diaryDateTimeMillis(date, hour, minute, second)
    SoftDialog("发生日期与时间", onDismiss) {
        OutlinedTextField(date, { date = it }, label = { Text("日期 YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = {
            val chosen = runCatching { LocalDate.parse(date) }.getOrDefault(local.toLocalDate())
            DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d).toString() }, chosen.year, chosen.monthValue - 1, chosen.dayOfMonth).show()
        }, modifier = Modifier.fillMaxWidth()) { Text("选择日期") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(hour, { hour = it.filter(Char::isDigit).take(2) }, label = { Text("时 0–23") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedTextField(minute, { minute = it.filter(Char::isDigit).take(2) }, label = { Text("分 0–59") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedTextField(second, { second = it.filter(Char::isDigit).take(2) }, label = { Text("秒 0–59") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        }
        Text(value?.let { diaryTimestamp(it) } ?: "请填写有效日期及时间。", fontSize = 13.sp, color = Quiet)
        Button(onClick = { value?.let(onSelect) }, enabled = value != null, modifier = Modifier.fillMaxWidth()) { Text("使用这个时间") }
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("取消") }
    }
}

/** One day's settings are shared by the road and the full text editor. */
@Composable
fun DiaryDaySettings(note: NoteNode, model: WorkspaceModel, onDismiss: () -> Unit,
    onRoadChanged: (Boolean) -> Unit, onExport: (Set<String>) -> Unit = {}, onInbox: ((Long?) -> Unit)? = null) {
    val context = LocalContext.current
    val nodes by model.nodes.collectAsState()
    val revision by model.diaryRevision.collectAsState()
    val current = remember(nodes, revision, note.day) { model.currentDiary(note.day ?: dayMillis()) }
    var page by rememberSaveable(note.id) { mutableStateOf("settings") }
    var disabling by remember { mutableStateOf(false) }
    var order by remember { mutableStateOf(appPreferences(context).getString("diary_time_sort", "occurred") ?: "occurred") }
    val today = current.day == dayMillis()
    fun back() { if (page == "settings") onDismiss() else page = "settings" }
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Mist) {
            when (page) {
                "background" -> DiaryBackgroundScreen(current, model, { page = "settings" })
                "layout" -> DiaryLayoutScreen(current, model, { page = "settings" })
                "inbox" -> DiaryInboxScreen(model, current.day, { page = "settings" }, { virtual ->
                    // Local inbox editing is supplied by the road; external callers use the navigation callback.
                    model.messages.tryEmit("请从日记首页收纳箱继续编辑")
                })
                else -> Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
                    PageTitle(if (today) "今日小路设置" else "这一天的设置", dateText(current.day ?: dayMillis()), ::back)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (!today) item {
                            SoftCard {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(current.diaryRoadEnabled || current.diaryMoments().isNotEmpty(), { enabled ->
                                        if (enabled) { model.enableDiaryRoad(current); onRoadChanged(true); onDismiss() } else disabling = true
                                    })
                                    Text("显示‘经历小路’", modifier = Modifier.weight(1f))
                                }
                                Text("日记正文保留；手动启用后，空小路也会保留这个选择。", fontSize = 12.sp, color = Quiet)
                            }
                        }
                        if (today || current.diaryRoadEnabled || current.diaryMoments().isNotEmpty()) {
                            item { SettingsAction("更换背景", "本机导入与四季", Icons.Default.Wallpaper) { page = "background" } }
                            item { SettingsAction("选择排布方式", when (current.diaryRoadLayout) { "left" -> "全在左侧"; "right" -> "全在右侧"; else -> "左右交错 · 左侧开始" }, Icons.Default.ViewQuilt) { page = "layout" } }
                        }
                        item { SoftCard {
                            ChoiceRow("小路时间排序 · 所有日期共用", order, listOf("occurred" to "发生时间", "sent" to "发送时间")) {
                                order = it; appPreferences(context).edit().putString("diary_time_sort", it).apply()
                            }
                            Text("按完整日期和时分秒从早到晚排列，片段内容不会改变。", fontSize = 12.sp, color = Quiet)
                        } }
                        item { SettingsAction("收纳箱", "草稿、撤回、已删除与整条小路", Icons.Default.Inventory2) {
                            if (onInbox != null) { onDismiss(); onInbox(current.day) } else page = "inbox"
                        } }
                        if (!today) item { SettingsAction("导出这一天", "选择小路、日记与阅读格式", Icons.Default.FileDownload) { onDismiss(); onExport(setOf(current.id)) } }
                    }
                }
            }
        }
        if (disabling) {
            val days = model.diaryInboxRetentionDays()
            AlertDialog(onDismissRequest = { disabling = false }, title = { Text("将整条小路移入收纳箱？") },
                text = { Text("日记正文保留，片段、背景与排布一起收纳。" + if (days == 0) "之后可从收纳箱恢复或彻底删除，默认永久保留。" else "本次移入的小路保留 $days 天，期限内可以恢复。") },
                confirmButton = { TextButton(onClick = { model.archiveDiaryRoad(current); disabling = false; onRoadChanged(false); onDismiss() }) { Text("移入收纳箱") } },
                dismissButton = { TextButton(onClick = { disabling = false }) { Text("保留小路") } })
        }
    }
}

@Composable
private fun SettingsAction(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = Color.White) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Sky); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(detail, fontSize = 12.sp, color = Quiet) }
            Icon(Icons.Default.ChevronRight, null, tint = Quiet)
        }
    }
}

@Composable
fun DiaryBackgroundScreen(note: NoteNode, model: WorkspaceModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imported by remember { mutableStateOf(DiaryBackgroundLibrary.imported(context)) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<DiaryBackground?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentTheme by remember { mutableStateOf(note.diaryRoadTheme) }
    var currentUri by remember { mutableStateOf(note.diaryRoadBackground) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching {
                val block = importMedia(context, uri, image = true, copy = true)
                require(loadImage(context, block.uri) != null) { "这张图片无法读取，请换一张" }
                DiaryBackgroundLibrary.add(context, block)
            }.onSuccess { imported = DiaryBackgroundLibrary.imported(context); selected = it }.onFailure { error = it.message ?: "导入失败" }
            busy = false
        }
    }
    fun back() { if (category != null) category = null else onBack() }
    BackHandler { if (selected != null) selected = null else back() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
        PageTitle(category ?: "更换背景", "先预览，再选择使用", ::back)
        if (category == null) LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { BackgroundCategory("本机导入", imported, currentTheme, currentUri, { category = "本机导入" }, { selected = it },
                importAction = { pick.launch(arrayOf("image/*")) }, busy = busy) }
            item { BackgroundCategory("四季", DiaryBackgroundLibrary.seasons, currentTheme, currentUri, { category = "四季" }, { selected = it }) }
        } else {
            if (category == "本机导入") OutlinedButton(onClick = { pick.launch(arrayOf("image/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在导入…" else "导入背景图片") }
            LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                items(if (category == "四季") DiaryBackgroundLibrary.seasons else imported, key = { it.id }) { background ->
                    BackgroundThumbnail(background, currentTheme == background.theme && currentUri == background.uri, Modifier.fillMaxWidth()) { selected = background }
                }
            }
        }
    }
    selected?.let { background ->
        SoftDialog(background.name, { selected = null }) {
            var bitmap by remember(background.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(background.id) { bitmap = DiaryBackgroundLibrary.bitmap(context, background.theme, background.uri) }
            bitmap?.let { Image(it.asImageBitmap(), background.name, Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit) }
            Button(onClick = {
                val current = model.currentDiary(note.day ?: dayMillis())
                model.updateDiaryRoadAppearance(current, background.theme, background.uri)
                if (current.day == dayMillis()) DiaryBackgroundLibrary.setDefaults(context, background = background)
                currentTheme = background.theme; currentUri = background.uri; selected = null; onBack()
            }, modifier = Modifier.fillMaxWidth(), enabled = bitmap != null) { Text("使用背景") }
        }
    }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }) }
}

@Composable
private fun BackgroundCategory(title: String, pictures: List<DiaryBackground>, theme: String, uri: String, onAll: () -> Unit,
    onPreview: (DiaryBackground) -> Unit, importAction: (() -> Unit)? = null, busy: Boolean = false) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)); IconButton(onClick = onAll) { Icon(Icons.Default.ChevronRight, "查看全部$title") } }
        importAction?.let { action -> OutlinedButton(onClick = action, enabled = !busy) { Icon(Icons.Default.AddPhotoAlternate, null); Spacer(Modifier.width(6.dp)); Text(if (busy) "正在导入…" else "导入背景图片") } }
        if (pictures.isEmpty()) Text("选择自己的照片，保存后可供不同日期使用。", fontSize = 13.sp, color = Quiet)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(pictures.take(8), key = { it.id }) { background -> BackgroundThumbnail(background, background.theme == theme && background.uri == uri, Modifier.width(128.dp)) { onPreview(background) } }
        }
    }
}

@Composable
private fun BackgroundThumbnail(background: DiaryBackground, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(background.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(background.id) { bitmap = DiaryBackgroundLibrary.bitmap(context, background.theme, background.uri) }
    Surface(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.small, color = Color(0xFFF1F4ED),
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Sky else ContentOutline)) {
        Column {
            bitmap?.let { Image(it.asImageBitmap(), background.name, Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Crop) }
                ?: Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { Text("正在读取…", fontSize = 12.sp) }
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Text(background.name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)); if (selected) Icon(Icons.Default.CheckCircle, "已选择", tint = Sky, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
fun DiaryLayoutScreen(note: NoteNode, model: WorkspaceModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var selected by rememberSaveable(note.id) { mutableStateOf(note.diaryRoadLayout) }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("选择排布方式", "时间顺序保持不变", onBack)
        listOf("alternate" to "左右交错 · 左侧开始", "left" to "全在左侧", "right" to "全在右侧").forEach { (key, label) ->
            SoftCard {
                Row(Modifier.fillMaxWidth().clickable { selected = key }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected == key, { selected = key }); Text(label) }
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { index ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (key == "right" || key == "alternate" && index % 2 == 1) Arrangement.End else Arrangement.Start) {
                            Surface(color = if (selected == key) Color(0xFFE4ECF8) else Color(0xFFEEF1F2), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth(.63f).height(20.dp)) { }
                        }
                    }
                }
            }
        }
        Button(onClick = { model.updateDiaryRoadLayout(model.currentDiary(note.day ?: dayMillis()), selected); if (note.day == dayMillis()) DiaryBackgroundLibrary.setDefaults(context, layout = selected); onBack() }, modifier = Modifier.fillMaxWidth()) { Text("完成") }
    }
}
