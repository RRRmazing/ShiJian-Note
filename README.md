# 时笺笔记

青春、简约、淡色的本地 Android 时间管理与记录应用，包含时间表、待办、日记、记忆和设置。无需账号，内容保存于设备本地。

dev 分支改版：分类与记忆可以混排、嵌套和树状浏览；轻量富文本支持可选标题、折叠和历史版本。图片可保存副本或引用原图，并按层级设置预览/卡片；录音支持暂停和后台录制；文件与内部记录以附件卡片引用。提供搜索、标签、置顶、收藏、回收站、导出和完整备份恢复。

- [当前使用说明与导出规则](DEV_GUIDE.md)
- [实施及验收记录](IMPLEMENTATION_CHECKLIST.md)

## 构建

用 Android Studio 打开项目，选择 JDK 17；compileSdk/targetSdk 35，最低 Android 8.0（API 26）。

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

缓存齐全时可添加 `--offline`。APK：`app/build/outputs/apk/debug/app-debug.apk`。

Android 设备测试：`gradlew.bat connectedDebugAndroidTest`。测试包含真实数据库迁移、保存失败重试、回收站恢复、导出与备份素材往返，请使用专用模拟器/测试设备。

## 数据与权限

旧版 Room v9 升级至 v10 时迁移原有内容，原文保持原样。记录标题可以留空。图片默认保存副本；手机文件附件只引用原位置，丢失后可以重新绑定。完整备份可包含外部文件，复制范围由用户明确选择；普通分类导出保留独立文档。

录音使用麦克风权限和前台服务。提醒需要通知权限，精确性还取决于系统闹钟设置与厂商后台限制。恢复完整备份会替换本机数据，操作前自动保留本机恢复备份。

设计参考与实际测试范围记录在使用说明和验收清单中。
