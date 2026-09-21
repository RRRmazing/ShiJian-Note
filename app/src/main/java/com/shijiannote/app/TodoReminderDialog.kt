package com.shijiannote.app

import android.app.TimePickerDialog
import android.icu.util.ChineseCalendar
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shijiannote.app.data.TodoBoardWithItems
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val TodoDialogInk = Color(0xFF1C1B20)
private val TodoDialogMuted = Color(0xFF706F78)
private val TodoDialogBlue = Color(0xFF356AE6)

@Composable
internal fun TodoReminderDialog(
    board: TodoBoardWithItems?,
    onDismiss: () -> Unit,
    onSave: (String, Long?, Long?, Int, Int, Int, List<String>) -> Unit
) {
    var summary by remember(board?.board?.id) { mutableStateOf(board?.board?.summary.orEmpty()) }
    var dueDate by remember(board?.board?.id) { mutableStateOf(board?.board?.dueDate) }
    var showDatePicker by remember { mutableStateOf(false) }
    var reminderEnabled by remember(board?.board?.id) { mutableStateOf(board?.board?.reminderAt != null) }
    var reminderAt by remember(board?.board?.id) { mutableStateOf(board?.board?.reminderAt ?: board?.board?.dueDate ?: System.currentTimeMillis()) }
    var days by remember(board?.board?.id) { mutableStateOf(board?.board?.reminderDays?.takeIf { it > 0 }?.toString().orEmpty()) }
    var hours by remember(board?.board?.id) { mutableStateOf(board?.board?.reminderHours?.takeIf { it > 0 }?.toString().orEmpty()) }
    var minutes by remember(board?.board?.id) { mutableStateOf(board?.board?.reminderMinutes?.takeIf { it > 0 }?.toString().orEmpty()) }
    val tasks = remember(board?.board?.id) { mutableStateListOf(*(board?.items?.sortedBy { it.position }?.map { it.text }?.toTypedArray() ?: arrayOf(""))) }
    val context = androidx.compose.ui.platform.LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (board == null) "新建待办框" else "更改待办框") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(summary, { summary = it }, label = { Text("摘要") }, placeholder = { Text("例如：本周复习计划") }, singleLine = true)
                Text("具体任务", color = TodoDialogMuted, fontSize = 13.sp)
                tasks.forEachIndexed { index, task ->
                    OutlinedTextField(task, { tasks[index] = it }, label = { Text(if (index == 0) "输入第一项任务" else "继续添加任务") }, leadingIcon = { Checkbox(false, {}) }, singleLine = true)
                }
                OutlinedButton(onClick = { tasks.add("") }) { Text("＋ 添加一项") }
                if (dueDate == null) {
                    OutlinedButton(onClick = { showDatePicker = true }) { Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(6.dp)); Text("设置截止日期（可选）") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(onClick = { showDatePicker = true }, label = { Text("截止：${formatDeadlineDate(dueDate!!)}") })
                        IconButton(onClick = { dueDate = null; reminderEnabled = false; days = ""; hours = ""; minutes = "" }) { Icon(Icons.Default.Close, "清除截止日期") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = reminderEnabled, onCheckedChange = { reminderEnabled = it })
                        Text("设置截止提醒")
                    }
                    if (reminderEnabled) {
                        OutlinedButton(onClick = {
                            val calendar = Calendar.getInstance().apply { timeInMillis = reminderAt }
                            TimePickerDialog(context, { _, hour, minute ->
                                val due = Calendar.getInstance().apply { timeInMillis = dueDate!! }
                                due.set(Calendar.HOUR_OF_DAY, hour)
                                due.set(Calendar.MINUTE, minute)
                                due.set(Calendar.SECOND, 0)
                                due.set(Calendar.MILLISECOND, 0)
                                reminderAt = due.timeInMillis
                            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
                        }) { Text("提醒时间：${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(reminderAt))}") }
                        Text("提前提醒", color = TodoDialogMuted, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TodoReminderNumber("天", days, 999) { days = it }
                            TodoReminderNumber("时", hours, 23) { hours = it }
                            TodoReminderNumber("分", minutes, 59) { minutes = it }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (summary.isNotBlank()) onSave(summary.trim(), dueDate, reminderAt.takeIf { dueDate != null && reminderEnabled }, days.toIntOrNull() ?: 0, hours.toIntOrNull() ?: 0, minutes.toIntOrNull() ?: 0, tasks.toList())
            }) { Text(if (board == null) "创建" else "保存") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } }
    )
    if (showDatePicker) TodoDeadlinePicker(
        initialDate = dueDate ?: System.currentTimeMillis(),
        onDismiss = { showDatePicker = false },
        onConfirm = { selected ->
            val oldTime = Calendar.getInstance().apply { timeInMillis = reminderAt }
            val newTime = Calendar.getInstance().apply { timeInMillis = selected }
            newTime.set(Calendar.HOUR_OF_DAY, oldTime.get(Calendar.HOUR_OF_DAY))
            newTime.set(Calendar.MINUTE, oldTime.get(Calendar.MINUTE))
            reminderAt = newTime.timeInMillis
            dueDate = selected
            showDatePicker = false
        }
    )
}

@Composable
private fun TodoReminderNumber(label: String, value: String, maximum: Int, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { raw ->
        val digits = raw.filter(Char::isDigit)
        val number = digits.toIntOrNull()
        onChange(when { digits.isEmpty() -> ""; number == null -> ""; number > maximum -> maximum.toString(); else -> digits })
    }, label = { Text(label) }, placeholder = { Text("0") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(82.dp), singleLine = true)
}

@Composable
internal fun TodoDeadlinePicker(initialDate: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.from(java.time.Instant.ofEpochMilli(initialDate).atZone(ZoneId.systemDefault()))) }
    var selected by remember { mutableStateOf(java.time.Instant.ofEpochMilli(initialDate).atZone(ZoneId.systemDefault()).toLocalDate()) }
    val cells = mutableListOf<LocalDate?>().apply {
        repeat(month.atDay(1).dayOfWeek.value % 7) { add(null) }
        (1..month.lengthOfMonth()).forEach { add(month.atDay(it)) }
        while (size % 7 != 0) add(null)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(30.dp), color = Color.White) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("选择基准日期", color = TodoDialogInk, fontSize = 23.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("《", color = TodoDialogBlue, fontSize = 20.sp, modifier = Modifier.clickable { month = month.minusYears(1) }.padding(4.dp))
                        Spacer(Modifier.width(6.dp))
                        IconButton(onClick = { month = month.minusMonths(1) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ChevronLeft, "上个月") }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text("${month.year}年${month.monthValue}月", color = TodoDialogInk, fontSize = 19.sp) }
                        IconButton(onClick = { month = month.plusMonths(1) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ChevronRight, "下个月") }
                        Spacer(Modifier.width(6.dp))
                        Text("》", color = TodoDialogBlue, fontSize = 20.sp, modifier = Modifier.clickable { month = month.plusYears(1) }.padding(4.dp))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        listOf("日", "一", "二", "三", "四", "五", "六").forEach { label ->
                            Box(Modifier.weight(1f).height(28.dp), contentAlignment = Alignment.Center) {
                                Text(label, color = TodoDialogMuted, fontSize = 13.sp)
                            }
                        }
                    }
                    cells.chunked(7).forEach { week ->
                        Row(Modifier.fillMaxWidth()) {
                            week.forEach { day ->
                                if (day == null) {
                                    Spacer(Modifier.weight(1f).height(52.dp))
                                } else {
                                    Box(
                                        Modifier.weight(1f).height(52.dp).padding(2.dp)
                                            .background(if (day == selected) TodoDialogBlue else Color.Transparent, CircleShape)
                                            .clickable { selected = day },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(day.dayOfMonth.toString(), color = if (day == selected) Color.White else TodoDialogInk, fontSize = 14.sp)
                                            Text(calendarAnnotation(day), color = if (day == selected) Color.White else TodoDialogMuted, fontSize = 9.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                        Spacer(Modifier.width(10.dp))
                        Button(onClick = { onConfirm(selected.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()) }, modifier = Modifier.weight(1f)) { Text("确定") }
                    }
                }
            }
        }
    }
}

internal fun calendarAnnotation(day: LocalDate): String {
    val holiday = when ("${day.monthValue}-${day.dayOfMonth}") {
        "1-1" -> "元旦"
        "5-1" -> "劳动节"
        "10-1" -> "国庆节"
        else -> null
    }
    if (holiday != null) return holiday
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return "农历"
    val lunar = ChineseCalendar().apply { timeInMillis = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val monthNames = arrayOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")
    val dayNames = arrayOf("初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十", "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十", "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十")
    val lunarMonth = lunar.get(ChineseCalendar.MONTH)
    val lunarDay = lunar.get(ChineseCalendar.DAY_OF_MONTH)
    return if (lunarDay == 1) "${monthNames.getOrElse(lunarMonth) { "?" }}月" else dayNames.getOrElse(lunarDay - 1) { "" }
}

internal fun formatDeadlineDateTime(time: Long): String = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date(time))

internal fun formatDeadlineDate(time: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = time }
    return if (calendar.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)) SimpleDateFormat("M月d日", Locale.CHINA).format(Date(time)) else SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(time))
}


