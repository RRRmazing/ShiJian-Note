package com.shijiannote.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shijiannote.app.data.TodoBoard
import com.shijiannote.app.data.TodoBoardWithItems
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private val QuickInk = Color(0xFF1C1B20)
private val QuickMuted = Color(0xFF8B8991)
private val QuickBlue = Color(0xFF356AE6)

internal data class BoardReminderDraft(
    val repeatRule: String? = null,
    val advanceDays: Int = 0,
    val advanceHours: Int = 0,
    val advanceMinutes: Int = 0,
    val baseAt: Long = System.currentTimeMillis(),
    val customDays: Int = 1
)

private fun TodoBoard.toReminderDraft(): BoardReminderDraft? = reminderAt?.let {
    BoardReminderDraft(
        repeatRule = reminderRule,
        advanceDays = reminderDays,
        advanceHours = reminderHours,
        advanceMinutes = reminderMinutes,
        baseAt = reminderBaseAt ?: System.currentTimeMillis(),
        customDays = reminderCustomDays.coerceAtLeast(1)
    )
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun QuickTodoCreateDialog(
    board: TodoBoardWithItems? = null,
    onDismiss: () -> Unit,
    onSave: (String, List<String>, Long?, BoardReminderDraft?) -> Unit
) {
    val boardId = board?.board?.id
    var title by remember(boardId) { mutableStateOf(board?.board?.summary.orEmpty()) }
    val tasks = remember(boardId) {
        mutableStateListOf<String>().apply {
            addAll(board?.items?.sortedBy { it.position }?.map { it.text }.orEmpty())
            if (isEmpty()) add("")
        }
    }
    val taskFocusRequesters = remember(boardId) {
        mutableStateListOf<FocusRequester>().apply { repeat(tasks.size) { add(FocusRequester()) } }
    }
    val taskBringIntoViewRequesters = remember(boardId) {
        mutableStateListOf<BringIntoViewRequester>().apply { repeat(tasks.size) { add(BringIntoViewRequester()) } }
    }
    var focusTaskIndex by remember(boardId) { mutableIntStateOf(-1) }
    var showDeadlineSetup by remember(boardId) { mutableStateOf(false) }
    var showReminderSetup by remember(boardId) { mutableStateOf(false) }
    var dueAt by remember(boardId) { mutableStateOf(board?.board?.dueDate) }
    var reminderDraft by remember(boardId) { mutableStateOf(board?.board?.toReminderDraft()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val taskScroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    fun saveIfNeeded() {
        val enteredTasks = tasks.filter { it.isNotBlank() }
        if (title.isNotBlank() || enteredTasks.isNotEmpty()) onSave(title, enteredTasks, dueAt, reminderDraft)
    }
    fun leave() { saveIfNeeded(); keyboard?.hide(); focusManager.clearFocus(); onDismiss() }

    val imeVisible = WindowInsets.isImeVisible
    BackHandler {
        if (imeVisible) {
            keyboard?.hide()
            focusManager.clearFocus()
        } else leave()
    }
    LaunchedEffect(focusTaskIndex) {
        if (focusTaskIndex >= 0) {
            taskFocusRequesters.getOrNull(focusTaskIndex)?.requestFocus()
            taskBringIntoViewRequesters.getOrNull(focusTaskIndex)?.bringIntoView()
        }
    }

    Dialog(onDismissRequest = ::leave, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier.fillMaxSize().imePadding().padding(start = 20.dp, end = 20.dp, top = 36.dp, bottom = 4.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                color = Color.White
            ) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = if (imeVisible) 320.dp else 460.dp).verticalScroll(taskScroll),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        BasicTextField(
                            value = title,
                            onValueChange = { title = it },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            textStyle = TextStyle(color = QuickInk, fontSize = 21.sp),
                            singleLine = true,
                            cursorBrush = SolidColor(QuickBlue),
                            decorationBox = { innerTextField ->
                                Box(Modifier.fillMaxWidth()) {
                                    if (title.isBlank()) Text("未命名清单", color = QuickMuted, fontSize = 21.sp)
                                    innerTextField()
                                }
                            }
                        )
                        tasks.forEachIndexed { index, task ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = false, onCheckedChange = null, enabled = false)
                                BasicTextField(
                                    value = task,
                                    onValueChange = { value ->
                                        tasks[index] = value
                                        if (focusTaskIndex == index) scope.launch { taskBringIntoViewRequesters.getOrNull(index)?.bringIntoView() }
                                    },
                                    modifier = Modifier.weight(1f).padding(start = 8.dp, top = 8.dp, bottom = 8.dp).focusRequester(taskFocusRequesters[index]).bringIntoViewRequester(taskBringIntoViewRequesters[index]).onFocusChanged { state -> if (state.isFocused) focusTaskIndex = index },
                                    textStyle = TextStyle(color = QuickInk, fontSize = 18.sp),
                                    cursorBrush = SolidColor(QuickBlue),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                        onNext = {
                                            if (tasks[index].isNotBlank() && index == tasks.lastIndex) {
                                                tasks.add("")
                                                taskFocusRequesters.add(FocusRequester())
                                                taskBringIntoViewRequesters.add(BringIntoViewRequester())
                                                focusTaskIndex = tasks.lastIndex
                                            }
                                        }
                                    ),
                                    maxLines = Int.MAX_VALUE,
                                    decorationBox = { innerTextField ->
                                        Box(Modifier.fillMaxWidth()) {
                                            if (task.isBlank()) Text(if (index == 0) "输入事务，回车继续添加" else "输入事务", color = QuickMuted, fontSize = 18.sp)
                                            innerTextField()
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { showDeadlineSetup = true }) { Text("截止时间") }
                        Spacer(Modifier.width(12.dp))
                        if (dueAt == null) Text("未设置", color = QuickMuted, modifier = Modifier.weight(1f))
                        else RemovableSettingBox(
                            text = formatDeadlineDateTime(dueAt!!),
                            onClick = { showDeadlineSetup = true },
                            onClear = { dueAt = null },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { showReminderSetup = true }) { Text("提醒我") }
                        Spacer(Modifier.width(12.dp))
                        if (reminderDraft == null) Text("未设置", color = QuickMuted, modifier = Modifier.weight(1f))
                        else RemovableSettingBox(
                            text = describeReminder(reminderDraft!!),
                            onClick = { showReminderSetup = true },
                            onClear = { reminderDraft = null },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
    if (showDeadlineSetup) DeadlineSetupDialog(
        initialDeadline = dueAt,
        onDismiss = { showDeadlineSetup = false },
        onConfirm = { deadline ->
            dueAt = deadline
            showDeadlineSetup = false
        }
    )
    if (showReminderSetup) BoardReminderDialog(
        initial = reminderDraft,
        hasDeadline = dueAt != null,
        onDismiss = { showReminderSetup = false },
        onConfirm = { draft ->
            reminderDraft = draft
            showReminderSetup = false
        }
    )
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeadlineSetupDialog(
    initialDeadline: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val start = initialDeadline ?: System.currentTimeMillis()
    var selectedDate by remember { mutableStateOf(start.toLocalDate()) }
    var month by remember { mutableStateOf(YearMonth.from(selectedDate)) }
    var hour by remember { mutableStateOf(SimpleDateFormat("H", Locale.CHINA).format(Date(start))) }
    var minute by remember { mutableStateOf(SimpleDateFormat("m", Locale.CHINA).format(Date(start))) }
    val scroll = rememberScrollState()

    fun deadlineTime(): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            set(Calendar.HOUR_OF_DAY, hour.toIntOrNull()?.coerceIn(0, 23) ?: 0)
            set(Calendar.MINUTE, minute.toIntOrNull()?.coerceIn(0, 59) ?: 0)
        }
        return calendar.timeInMillis
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 32.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(30.dp), color = Color.White) {
                Column(Modifier.fillMaxWidth().padding(24.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("设置截止时间", color = QuickInk, fontSize = 25.sp)
                    Column {
                        Text("截止时间", color = QuickInk, fontSize = 18.sp)
                        Text("${formatFullDate(selectedDate)} ${hour.padStart(2, '0')}:${minute.padStart(2, '0')}", color = QuickBlue, fontSize = 17.sp)
                    }
                    QuickDeadlineCalendar(month = month, selected = selectedDate, onMonthChange = { month = it }, onSelect = { selectedDate = it })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("时间", color = QuickInk, fontSize = 17.sp)
                        QuickNumber("时", hour, 23) { hour = it }
                        QuickNumber("分", minute, 59) { minute = it }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                        Spacer(Modifier.width(10.dp))
                        Button(onClick = { onConfirm(deadlineTime()) }, modifier = Modifier.weight(1f)) { Text("确定") }
                    }
                }
            }
        }
    }
}
@Composable
private fun QuickDeadlineCalendar(
    month: YearMonth,
    selected: LocalDate,
    onMonthChange: (YearMonth) -> Unit,
    onSelect: (LocalDate) -> Unit
) {
    val cells = mutableListOf<LocalDate?>().apply {
        repeat(month.atDay(1).dayOfWeek.value % 7) { add(null) }
        (1..month.lengthOfMonth()).forEach { add(month.atDay(it)) }
        while (size % 7 != 0) add(null)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("《", color = QuickBlue, fontSize = 20.sp, modifier = Modifier.clickable { onMonthChange(month.minusYears(1)) }.padding(4.dp))
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = { onMonthChange(month.minusMonths(1)) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ChevronLeft, "上个月") }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text("${month.year}年${month.monthValue}月", color = QuickInk, fontSize = 19.sp) }
            IconButton(onClick = { onMonthChange(month.plusMonths(1)) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ChevronRight, "下个月") }
            Spacer(Modifier.width(6.dp))
            Text("》", color = QuickBlue, fontSize = 20.sp, modifier = Modifier.clickable { onMonthChange(month.plusYears(1)) }.padding(4.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEach { label ->
                Box(Modifier.weight(1f).height(28.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = QuickMuted, fontSize = 13.sp)
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
                                .background(if (day == selected) QuickBlue else Color.Transparent, CircleShape)
                                .clickable { onSelect(day) },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(day.dayOfMonth.toString(), color = if (day == selected) Color.White else QuickInk, fontSize = 14.sp)
                                Text(calendarAnnotation(day), color = if (day == selected) Color.White else QuickMuted, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardReminderDialog(initial: BoardReminderDraft?, hasDeadline: Boolean, onDismiss: () -> Unit, onConfirm: (BoardReminderDraft) -> Unit) {
    var repeating by remember { mutableStateOf(initial?.repeatRule != null || !hasDeadline) }
    var singleDays by remember { mutableStateOf(initial?.advanceDays?.takeIf { !repeating }?.toString().orEmpty()) }
    var singleHours by remember { mutableStateOf(initial?.advanceHours?.takeIf { !repeating }?.toString().orEmpty()) }
    var singleMinutes by remember { mutableStateOf(initial?.advanceMinutes?.takeIf { !repeating }?.toString().orEmpty()) }
    var rule by remember { mutableStateOf(initial?.repeatRule ?: ReminderScheduler.RULE_WEEKDAYS) }
    var baseAt by remember { mutableStateOf(initial?.baseAt ?: System.currentTimeMillis()) }
    var repeatHour by remember { mutableStateOf(SimpleDateFormat("H", Locale.CHINA).format(Date(baseAt))) }
    var repeatMinute by remember { mutableStateOf(SimpleDateFormat("m", Locale.CHINA).format(Date(baseAt))) }
    var customDays by remember { mutableStateOf(initial?.customDays?.toString() ?: "1") }
    var showBaseDatePicker by remember { mutableStateOf(false) }
    var showServiceIntroduction by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    fun updateBaseTime() {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = baseAt
            set(Calendar.HOUR_OF_DAY, repeatHour.toIntOrNull()?.coerceIn(0, 23) ?: 0)
            set(Calendar.MINUTE, repeatMinute.toIntOrNull()?.coerceIn(0, 59) ?: 0)
        }
        baseAt = calendar.timeInMillis
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp, vertical = 48.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(30.dp), color = Color.White) {
                Column(Modifier.fillMaxWidth().padding(24.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Text("提醒我", color = QuickInk, fontSize = 25.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!repeating) Button(onClick = { repeating = false }, enabled = hasDeadline) { Text("单次提醒") }
                        else OutlinedButton(onClick = { repeating = false }, enabled = hasDeadline) { Text("单次提醒") }
                        if (repeating) Button(onClick = { repeating = true }) { Text("重复提醒") }
                        else OutlinedButton(onClick = { repeating = true; singleDays = ""; singleHours = ""; singleMinutes = "" }) { Text("重复提醒") }
                    }
                    if (!hasDeadline) Text("未设置截止时间时，可使用重复提醒；服务会持续到清除。", color = QuickMuted, fontSize = 12.sp)
                    if (!repeating) {
                        Text("提前提醒（默认为准时提醒）", color = QuickInk, fontSize = 17.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QuickNumber("天", singleDays, 999) { singleDays = it }
                            QuickNumber("时", singleHours, 23) { singleHours = it }
                            QuickNumber("分", singleMinutes, 59) { singleMinutes = it }
                        }
                    } else {
                        Text("基准时间", color = QuickInk, fontSize = 17.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showBaseDatePicker = true }) { Text(formatFullDate(baseAt.toLocalDate())) }
                            QuickNumber("时", repeatHour, 23) { repeatHour = it; updateBaseTime() }
                            QuickNumber("分", repeatMinute, 59) { repeatMinute = it; updateBaseTime() }
                        }
                        Text("重复方式", color = QuickInk, fontSize = 17.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RepeatRuleButton("周一至周五", rule == ReminderScheduler.RULE_WEEKDAYS, Modifier.weight(1f)) { rule = ReminderScheduler.RULE_WEEKDAYS }
                            RepeatRuleButton("周末", rule == ReminderScheduler.RULE_WEEKENDS, Modifier.weight(1f)) { rule = ReminderScheduler.RULE_WEEKENDS }
                        }
                        FlowRow(maxItemsInEachRow = 3, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "每隔1天" to ReminderScheduler.RULE_DAILY,
                                "每隔3天" to ReminderScheduler.RULE_EVERY_3_DAYS,
                                "每隔1周" to ReminderScheduler.RULE_WEEKLY,
                                "每隔半月" to ReminderScheduler.RULE_SEMI_MONTHLY,
                                "每隔一月" to ReminderScheduler.RULE_MONTHLY,
                                "每隔一年" to ReminderScheduler.RULE_YEARLY,
                                "自定义" to ReminderScheduler.RULE_CUSTOM_DAYS
                            ).forEach { (label, value) -> RepeatRuleButton(label, rule == value) { rule = value } }
                        }
                        if (rule == ReminderScheduler.RULE_CUSTOM_DAYS) QuickNumber("每隔天数", customDays, 999) { customDays = it }
                        Text("* 点击查看服务介绍", color = QuickBlue, fontSize = 13.sp, modifier = Modifier.clickable { showServiceIntroduction = true })
                    }
                    Row(Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                        Spacer(Modifier.width(10.dp))
                        Button(onClick = {
                            val draft = if (repeating) {
                                updateBaseTime()
                                BoardReminderDraft(repeatRule = rule, baseAt = baseAt, customDays = customDays.toIntOrNull()?.coerceIn(1, 999) ?: 1)
                            } else {
                                BoardReminderDraft(
                                    advanceDays = singleDays.toIntOrNull()?.coerceIn(0, 999) ?: 0,
                                    advanceHours = singleHours.toIntOrNull()?.coerceIn(0, 23) ?: 0,
                                    advanceMinutes = singleMinutes.toIntOrNull()?.coerceIn(0, 59) ?: 0
                                )
                            }
                            onConfirm(draft)
                        }, modifier = Modifier.weight(1f)) { Text("确定") }
                    }
                }
            }
        }
    }
    if (showServiceIntroduction) ReminderServiceIntroductionDialog(onDismiss = { showServiceIntroduction = false })
    if (showBaseDatePicker) TodoDeadlinePicker(
        initialDate = baseAt,
        onDismiss = { showBaseDatePicker = false },
        onConfirm = { selected ->
            val old = Calendar.getInstance().apply { timeInMillis = baseAt }
            val updated = Calendar.getInstance().apply {
                timeInMillis = selected
                set(Calendar.HOUR_OF_DAY, old.get(Calendar.HOUR_OF_DAY))
                set(Calendar.MINUTE, old.get(Calendar.MINUTE))
            }
            baseAt = updated.timeInMillis
            showBaseDatePicker = false
        }
    )
}

@Composable
private fun ReminderServiceIntroductionDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), color = Color.White) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("重复提醒服务介绍", modifier = Modifier.weight(1f), color = QuickInk, fontSize = 22.sp)
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭", tint = QuickMuted) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "重复提醒是以基准时间为开始，按照一定频率进行提醒。\n\n例如，“周一至周五”意味着从基准时间开始到截止时间之前，每一个周一至周五当天都会在基准时间设置的时分进行提醒；“每隔1天”意味着从基准时间开始到截止时间之前，每天在基准时间设置的时分进行提醒。\n\n如果没有设置截止时间，重复提醒服务会一直持续，直到被清除。",
                        color = QuickInk,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}
@Composable
private fun RemovableSettingBox(
    text: String,
    onClick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp)
        ) {
            Text(text, fontSize = 12.sp, maxLines = 1)
        }
        IconButton(
            onClick = onClear,
            modifier = Modifier.align(Alignment.TopEnd).size(22.dp).background(Color.White, CircleShape)
        ) {
            Icon(Icons.Default.Close, contentDescription = "清除", tint = QuickMuted, modifier = Modifier.size(15.dp))
        }
    }
}

private fun describeReminder(draft: BoardReminderDraft): String {
    if (draft.repeatRule == null) {
        return if (draft.advanceDays == 0 && draft.advanceHours == 0 && draft.advanceMinutes == 0) "准时提醒" else "提前${draft.advanceDays}天${draft.advanceHours}时${draft.advanceMinutes}分提醒"
    }
    val label = when (draft.repeatRule) {
        ReminderScheduler.RULE_WEEKDAYS -> "周一至周五"
        ReminderScheduler.RULE_WEEKENDS -> "周末"
        ReminderScheduler.RULE_DAILY -> "每隔1天"
        ReminderScheduler.RULE_EVERY_3_DAYS -> "每隔3天"
        ReminderScheduler.RULE_WEEKLY -> "每隔1周"
        ReminderScheduler.RULE_SEMI_MONTHLY -> "每隔半月"
        ReminderScheduler.RULE_MONTHLY -> "每隔一月"
        ReminderScheduler.RULE_YEARLY -> "每隔一年"
        ReminderScheduler.RULE_CUSTOM_DAYS -> "每隔${draft.customDays}天"
        else -> "重复提醒"
    }
    val time = SimpleDateFormat("HH时mm分", Locale.CHINA).format(Date(draft.baseAt))
    return "以${formatFullDate(draft.baseAt.toLocalDate())}${time}为基准  $label"
}
@Composable
private fun RepeatRuleButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = modifier) { Text(label, fontSize = 13.sp) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label, fontSize = 13.sp) }
}

@Composable
private fun QuickNumber(label: String, value: String, maximum: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit)
            val number = digits.toIntOrNull()
            onChange(
                when {
                    digits.isEmpty() -> ""
                    number == null -> ""
                    number > maximum -> maximum.toString()
                    else -> digits
                }
            )
        },
        label = { Text(label) },
        modifier = Modifier.width(if (label == "每隔天数") 118.dp else 78.dp),
        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        singleLine = true
    )
}

private fun Long.toLocalDate(): LocalDate = java.time.Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
private fun formatFullDate(date: LocalDate): String = "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
