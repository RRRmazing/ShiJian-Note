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
@Composable fun SoftDialog(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
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
fun dateText(time: Long, withTime: Boolean = false): String = SimpleDateFormat(if (withTime) "yyyy年M月d日 HH:mm" else "yyyy年M月d日", Locale.CHINA).format(Date(time))
fun appPreferences(context: Context) = context.getSharedPreferences("general", Context.MODE_PRIVATE)
fun imageDisplayDefault(context: Context) = appPreferences(context).getString("imageDisplay", "preview")!!
fun imageStorageDefault(context: Context) = appPreferences(context).getString("imageStorage", "copy")!!
