package com.shijiannote.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*

@Composable fun ModernAbout(onBack: () -> Unit) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageTitle("时笺笔记", "本地记录，轻轻整理", onBack) }
        item { SoftCard { Text("版本 ${BuildConfig.VERSION_NAME}"); TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RRRmazing/ShiJian-Note"))) }) { Text("项目地址") } } }
        listOf("记忆与分类树" to "点击＋选择分类或记忆。分类可嵌套且与记忆混排；箭头展开，名称进入分支。深层目录限制缩进，路径菜单可返回上级。长按多选，在更多中进入排序模式。",
            "书写与保存" to "标题可选，直接写正文即可保存。点格式设置标题、列表、引用或代码；标题可以折叠。顶部状态反映真实保存结果，失败时点击重试。已有记录先阅读，点正文或编辑按钮书写。",
            "图片、语音和附件" to "图片可预览或显示为卡片，可保存副本或引用原图。默认值在常规中设置，分类和记录可以覆盖。录音点按开始，支持暂停和后台通知。文件只绑定原位置；内部附件指向整篇记忆或日记。",
            "搜索与整理" to "搜索覆盖标题、正文、标签和附件名称；结果显示来源。置顶保留常用内容，日记收藏用于回顾，往年今日显示往年同一天的记录。",
            "待办与时间表" to "今天、明天、逾期和重要是清单事项的智能视图。事项的计划、截止、提醒分别设置。重复任务在完成后生成下一项，重复提醒只发送通知。当天开始的事务仍保留在时间表。",
            "删除与恢复" to "分类删除会把整个分支放入回收站。恢复时保持结构；上级已删除时恢复到首页。永久删除需明确确认。历史版本可以找回误改文字与素材。",
            "导出与备份" to "单篇导出Markdown或PDF，有素材时使用ZIP。分类导出ZIP，保留子树和独立文档。图片副本与录音随包保存，外部引用是否复制由你选择。完整备份支持替换恢复，恢复前自动保留本机备份。",
            "通知与权限" to "录音需要麦克风权限。提醒需要通知权限，精确提醒还取决于系统闹钟设置。可以从设置中的系统应用设置调整。").forEach { (title, body) -> item { SoftCard { Text(title, fontSize = 18.sp); Text(body, color = Quiet, fontSize = 14.sp) } } }
    }
}
