package com.shijiannote.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shijiannote.app.data.DiaryEntry
import com.shijiannote.app.data.MemoryEntry

private val EditorInk = Color(0xFF1C1B20)
private val EditorMuted = Color(0xFF706F78)
/**
 * Full-page note editor. The text field owns the remaining visible height, so Compose keeps the
 * active cursor in view and allows scrolling without dismissing the IME.
 */
@Composable
internal fun DiaryEditorScreen(
    day: Long,
    entry: DiaryEntry?,
    onBack: () -> Unit,
    onSave: (String, String) -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var summary by remember(entry?.id) { mutableStateOf(entry?.summary.orEmpty()) }
    var content by remember(entry?.id) { mutableStateOf(entry?.content.orEmpty()) }
    fun leaveEditor() {
        onSave(summary, content)
        keyboard?.hide()
        focusManager.clearFocus()
        onBack()
    }
    BackHandler { leaveEditor() }
    Surface(modifier = Modifier.fillMaxSize().imePadding(), color = Color(0xFFFCFBFF)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::leaveEditor) { Icon(Icons.Default.ChevronLeft, "返回", tint = EditorInk) }
                Text("日记", fontSize = 22.sp, color = EditorInk)
            }
            Text("正在自动保存到本机", color = EditorMuted, fontSize = 13.sp)
            OutlinedTextField(
                value = summary,
                onValueChange = { summary = it; onSave(summary, content) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("摘要") },
                singleLine = true
            )
            OutlinedTextField(
                value = content,
                onValueChange = { content = it; onSave(summary, content) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = { Text("正文") },
                placeholder = { Text("点击这里开始书写…") },
                keyboardOptions = KeyboardOptions.Default,
                minLines = 12
            )
        }
    }
}

@Composable
internal fun MemoryEditorScreen(
    entry: MemoryEntry?,
    onBack: () -> Unit,
    onCreate: (String, String, (MemoryEntry) -> Unit) -> Unit,
    onSave: (MemoryEntry, String, String) -> Unit,
    onDelete: (MemoryEntry) -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var title by remember(entry?.id) { mutableStateOf(entry?.title.orEmpty()) }
    var content by remember(entry?.id) { mutableStateOf(entry?.content.orEmpty()) }
    var savedEntry by remember(entry?.id) { mutableStateOf(entry) }
    var creating by remember(entry?.id) { mutableStateOf(false) }
    fun persist(newTitle: String = title, newContent: String = content) {
        val current = savedEntry
        if (current != null && (newTitle.isNotBlank() || newContent.isNotBlank())) {
            onSave(current, newTitle, newContent)
        } else if (newTitle.isNotBlank() && !creating) {
            creating = true
            onCreate(newTitle, newContent) { created ->
                savedEntry = created
                creating = false
                if (title.isBlank() && content.isBlank()) onDelete(created) else onSave(created, title, content)
            }
        }
    }
    fun leaveEditor() {
        if (title.isBlank() && content.isBlank()) savedEntry?.let(onDelete) else persist()
        keyboard?.hide()
        focusManager.clearFocus()
        onBack()
    }
    BackHandler { leaveEditor() }
    Surface(modifier = Modifier.fillMaxSize().imePadding(), color = Color(0xFFFCFBFF)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::leaveEditor) { Icon(Icons.Default.ChevronLeft, "返回", tint = EditorInk) }
                Text(if (entry == null) "新建记忆" else "编辑记忆", fontSize = 22.sp, color = EditorInk)
            }
            Text("正在自动保存到本机", color = EditorMuted, fontSize = 13.sp)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; persist() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("标题") },
                singleLine = true
            )
            OutlinedTextField(
                value = content,
                onValueChange = { content = it; persist() },
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = { Text("内容") },
                placeholder = { Text("点击这里开始记录…") },
                keyboardOptions = KeyboardOptions.Default,
                minLines = 12
            )
        }
    }
}
