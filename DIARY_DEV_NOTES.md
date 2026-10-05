# 日记开发版

分支：`diary-dev`，基于本地 `master` 的 `9234297`。本轮功能作为后续界面精简之前的提交基线。

## 本轮实现

- 今日小路与结语、跨日历史与旧纯文本日记入口。
- 四季插画、本机导入背景库、左右交错及全左/全右排布。
- 秒级发生/发送双时间、双排序和邻居位置预览。
- 每日与总收纳箱：草稿、撤回、已删除片段及整条小路。
- 默认永久保留及可选期限；回收站只收整篇日记。
- 单日、多日 ZIP 导出：HTML、Markdown、TXT 与原始媒体。
- 数据库 V12/V13→V14 无损迁移、完整备份和素材引用保护。

详细行为及未讨论事项的选择见 [实现规则 V6](design/diary-road/DIARY_IMPLEMENTATION_V6.md)。需求与设计历史见 [最新需求](design/diary-road/REQUIREMENTS_CURRENT.md)。

## 验证

- `assembleDebug`：成功，生成安装包。
- `testDebugUnitTest`：53项通过，0失败。
- `connectedDebugAndroidTest`：74项通过，0失败、0跳过，Pixel 9 Pro / Android 17 模拟器。
- `lintDebug`：通过，0错误；仍有依赖版本、旧图标和通用Compose风格等警告，本轮没有升级基础依赖。
- V12→V14真实数据库升级覆盖纯文本、富文本、图片/语音/文件/关联记录、标题标签心情收藏、回收记录与原始历史版本；V13→V14覆盖已有小路和旧时间。
- 回归覆盖发送失败重试、撤回重发双时间、富媒体草稿返回与不复活、只读邻居预览往返、整路收纳合并、整篇回收及旧备份同日冲突、保留期限、背景素材保护、三种ZIP格式和缺失媒体。
- 视觉检查：[小路截图](app/build/diary-preview/diary-road-preview-v6.png)、[首页截图](app/build/diary-preview/diary-home-preview-v6.png)。

完整构建检查命令：`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest --console=plain`。

第一轮设备回归发现并修复Android文件路径别名导致的已引用背景误判；最终完整回归通过。

安装包：`app/build/outputs/apk/debug/app-debug.apk`。

## 范围说明

验证使用本机 Android 模拟器及独立测试数据，没有连接或修改用户手机数据。手机升级需要同应用ID和签名；本构建沿用原项目调试构建方式。背景是单幅插画缓慢平移，长小路不会额外生成景物。TXT不会把语音转为文字，仍包含原始音频。
