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
        item { PageTitle("时笺", "本地记录，轻轻整理", onBack) }
        item { SoftCard { Text("版本 ${BuildConfig.VERSION_NAME}"); TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RRRmazing/ShiJian-Note"))) }) { Text("项目地址") } } }
        listOf("记忆与分类树" to "先进入工作或生活，再点击＋创建分类或记忆。旧版本已有记忆与回收内容统一归入工作，原分类层级保留。分类可嵌套且与记忆混排；箭头展开，名称进入分支。结构树以连续枝线连接节点，可上下左右滚动，分类用《》区分；点击节点进入内容，在跳转入口返回时选择返回树形图或返回上级。返回树形图保留原位置，返回上级后离开树的浏览路径。长按多选移动，或从记忆正文更多菜单移动，支持跨工作生活和临时新建分类。排序向左展开四项选择。",
            "书写与保存" to "标题可选，日记未填写标题显示未命名。标题与正文之间可添加深色标签，输入时固定带#，可复用已有标签，点击×只移除当前篇的标签。点格式设置标题、列表、引用或代码；标题可以折叠。顶部状态反映真实保存结果，失败时点击重试。已有记录先阅读，点正文或编辑按钮书写。",
            "图片、语音和附件" to "图片可预览或显示为卡片，可保存副本或引用原图。默认值在常规的图片显示与保存页面设置。分类可强制所有下级沿用图片设置，补存已有图片；失效引用集中显示，可以打开记录修复并重试。解除强制恢复原配置，已有副本保留。录音支持暂停和后台通知；文件只绑定原位置，内部附件指向整篇记录。",
            "搜索与整理" to "顶部搜索只查当前模块，查看结果后返回保留条件和位置。日记可筛选收藏，时间表可筛选重要，并可按时间范围或历年某月某日查找；完整日期支持不输入关键词搜索，起止当天都包含。待办按清单、今天、明天等范围查找。记忆默认查当前分类及下级，取消仅在当前分类下可查工作与生活；包含历史与归档会查找记录的历史版本和归档事务。",
            "日记与回顾" to "首页空心爱心切换收藏筛选，红色实心表示仅显示收藏，再点恢复全部；搜索中的仅收藏独立选择。日记摘要最多两行，标签带#。日历内的往年今日查看历年同月同日的记录，也包含今年。往年有记录时每天显示红色1，日历与往年今日入口分别点击后清除各自提醒。日记设置可关闭提醒往年今日，开启通知权限后每天最多发送一条回顾通知。",
            "待办与时间表" to "待办框创建时选择统一时间或独立时间，创建后固定类型。统一时间由框设置截止、提醒与频次；独立时间由每条事项设置。今天和明天直接新建事项，只输入时分，默认当天结束截止；明天跨日进入今天，未完成且超过截止时间进入逾期。重复页同步显示正在重复的框或事项，可以跳过一次通知、恢复、关闭重复或删除。完成后停止提醒，不复制事项。今天和明天点星标突出重要事项。时间表展开卡片右侧的星标可突出重要事务；长按多选删除，确认后移到回收站。",
            "删除与恢复" to "日记、记忆和时间表长按进入多选，显示已选数量；全选按钮变色，再次点击取消全选。删除前确认数量，取消删除保留勾选。分类删除会把整个分支放入回收站。回收站先分时间表、待办、日记、记忆，记忆再分工作生活。恢复时保持结构；上级已删除时回到原收纳。日记删除后不显示撤销提示，可到回收站恢复。永久删除需明确确认。历史版本可以找回误改文字与素材。",
            "导出与备份" to "记忆更多菜单的导出、日记更多菜单的导出日记进入专用勾选页面，选择内容或全选，再选择导出内容。分类导出ZIP，保留子树和独立文档，并可附结构树；日记不显示结构树选项，单篇可导出Markdown或PDF。图片副本与录音随包保存，外部引用是否复制由你选择。完整备份恢复前自动保留本机备份。",
            "通知与权限" to "录音需要麦克风权限。提醒需要通知权限，精确提醒还取决于系统闹钟设置。可以从设置中的系统应用设置调整。").forEach { (title, body) -> item { SoftCard { Text(title, fontSize = 18.sp); Text(body, color = Quiet, fontSize = 14.sp) } } }
    }
}
