package com.shijiannote.app

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Mist = Color(0xFFF7F9FC)
val Sky = Color(0xFF587BB9)
val Mint = Color(0xFFE8F4ED)
val Peach = Color(0xFFFBEAEC)
val Lavender = Color(0xFFEEECF9)
val NoteInk = Color(0xFF283449)
val Quiet = Color(0xFF718095)

@Composable fun YouthTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Sky, onPrimary = Color.White, primaryContainer = Color(0xFFE8EFFB), onPrimaryContainer = NoteInk,
        secondary = Color(0xFF658D7A), secondaryContainer = Mint, tertiaryContainer = Peach, background = Mist, surface = Color.White,
        surfaceVariant = Color(0xFFF0F3F8), onSurface = NoteInk, onSurfaceVariant = Quiet, outline = Color(0xFFCCD5E1), error = Color(0xFFB66370)),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)), content = content)
}
@Composable fun PageTitle(title: String, subtitle: String = "", back: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        Column(Modifier.weight(1f)) { Text(title, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis); if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp, color = Quiet) }
        actions()
    }
}
@Composable fun SoftCard(modifier: Modifier = Modifier, color: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = color) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}
@Composable fun SoftDialog(title: String, onClose: () -> Unit, dismissOnBackPress: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = dismissOnBackPress)) {
        Surface(Modifier.fillMaxWidth().padding(20.dp).imePadding(), shape = RoundedCornerShape(28.dp), color = Color.White) {
            Column(Modifier.padding(22.dp).heightIn(max = 580.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }
}
@Composable fun ChoiceRow(label: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit) {
    Column { Text(label, color = Quiet, fontSize = 13.sp); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (key, text) -> FilterChip(selected = value == key, onClick = { onChange(key) }, label = { Text(text) }) }
    } }
}
@Composable fun ConfirmTrashDialog(message: String, onCancel: () -> Unit, onDelete: () -> Unit, enabled: Boolean = true) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(20.dp).navigationBarsPadding(), shape = RoundedCornerShape(28.dp), color = Color.White) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(message, fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = onDelete, enabled = enabled, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("删除") }
                }
            }
        }
    }
}
fun trashSummary(folders: Int, records: Int, showFolders: Boolean = true): String =
    if (showFolders) "将这${folders}个分类，${records}条记录移到回收站" else "将这${records}条记录移到回收站"

@Composable fun NoteSelectionActions(count: Int, allSelected: Boolean, canSelectAll: Boolean,
    onAll: () -> Unit, onCancel: () -> Unit, onExport: (() -> Unit)? = null, onMove: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("已选$count", color = Quiet, modifier = Modifier.weight(1f))
            FilterChip(allSelected, onAll, enabled = canSelectAll, label = { Text("全选") })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            onMove?.let { TextButton(onClick = it, enabled = count > 0) { Text("移动到") } }
            onExport?.let { TextButton(onClick = it, enabled = count > 0) { Text("导出") } }
            TextButton(onClick = onCancel) { Text("取消") }
            onDelete?.let { TextButton(onClick = it, enabled = count > 0,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") } }
        }
    }
}
fun dateText(time: Long, withTime: Boolean = false): String {
    val date = java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val currentYear = date.year == java.time.LocalDate.now().year
    return (if (currentYear) "今年" else "") + SimpleDateFormat(if (withTime) { if (currentYear) "M月d日 HH:mm" else "yyyy年M月d日 HH:mm" } else { if (currentYear) "M月d日" else "yyyy年M月d日" }, Locale.CHINA).format(Date(time))
}
fun appPreferences(context: Context) = context.getSharedPreferences("general", Context.MODE_PRIVATE)
fun imageDisplayDefault(context: Context) = appPreferences(context).getString("imageDisplay", "preview")!!
fun imageStorageDefault(context: Context) = appPreferences(context).getString("imageStorage", "copy")!!
