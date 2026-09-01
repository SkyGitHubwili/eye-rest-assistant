# 护眼睡眠助手

Windows + Android 本地护眼工具；Android 8.0+ 还包含睡眠和健康使用模块。健康使用数据来自 Android `UsageStatsManager` / `UsageEvents`，不会展示虚构统计。

## 护眼功能

- Windows/Android 护眼计时、暂停、重置、立即休息和全屏休息画面
- 自定义休息图片、每日工作时段和后台恢复
- 提前结束休息每月最多 3 次
- Android 支持关闭、仅当天手动护眼、每日自动护眼三种模式

## 健康使用 V1

- 固定底部三页面导航，重建后保留当前页面
- 今日/昨日总使用时间、每日目标与剩余时间
- Top 5 App 排行、真实图标、名称、时长和横向柱形图
- 单 App 今日/昨日、打开次数、平均每次和最近 7 天趋势
- 全部数据页、7 天日均/最多/最少及与上周比较
- 最长/当前连续使用估算，30/45/60/90 分钟低频通知提醒
- 本地透明健康指数，不包含医疗诊断
- 为 V2 单 App 限时预留 `AppLimit` 数据模型，不在 V1 强制锁 App

首次进入「健康使用」需按提示开启系统的“使用情况访问权限”。权限关闭或系统无数据时，页面只显示权限/空状态。

## 睡眠功能

- 关闭、今日、每天三种模式
- 精确到分钟的睡眠/起床时间，支持跨午夜
- 睡前 3 分钟红色真实时间倒计时
- 到点使用全屏 `TYPE_APPLICATION_OVERLAY` 阻挡普通应用操作
- 状态栏保留，可查看通知；不修改媒体、通知、闹钟和通话音量
- 来电立即移除睡眠层，并跳过当前这一晚
- 睡眠期间重启后跳过当前这一晚；每日模式下一晚自动恢复
- 熄屏移除 Overlay，解锁后若仍在睡眠时段则恢复
- 前台服务、定时恢复、时间及时区变化重新计算

## 使用

首次启动请允许：

1. 显示在其他应用上层（强制提醒必需）
2. 通知权限（前台服务状态）
3. 电话状态权限（仅用于检测响铃并解除当晚睡眠锁）
4. 厂商系统中的自启动与后台运行权限
5. 使用情况访问权限（仅健康使用统计需要）

最低 Android 8.0。Android 不允许普通应用获得不可退出的系统级设备锁；用户强制停止应用或撤销悬浮窗权限后，系统会终止锁定，这是避免永久锁死的安全边界。

## Windows 开发与发布

```powershell
dotnet run
dotnet restore -r win-x64 --configfile .\NuGet.Config
dotnet publish -c Release -r win-x64 --self-contained true --no-restore -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o F:\EyeRestBuild\windows
```

## Android 构建

```powershell
.\mobile\android\build.ps1
```

输出：`F:\SleepAssistantRelease\SleepAssistant-Android.apk`

逻辑测试：

```powershell
.\tools\run-tests.ps1
```
