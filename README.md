# 护眼睡眠助手

Windows + Android 本地护眼工具；Android 8.0+ 还包含睡眠和健康使用模块。健康使用数据来自 Android `UsageStatsManager` / `UsageEvents`，不会展示虚构统计。

## 护眼功能

- Windows/Android 护眼计时、暂停、重置、立即休息和全屏休息画面
- 自定义休息图片、每日工作时段和后台恢复
- 提前结束休息每月最多 3 次
- Android 支持关闭、仅当天手动护眼、每日自动护眼三种模式

## Android 3.2.7

- 使用概览采用 ScreenLedger 事件统计核心，按应用前后台、熄屏及锁屏事件计时，归档保存在本机 SQLite。
- 日使用圆环、应用列表直接显示时长、启动次数和时长占比；点击应用可看每小时使用柱形图。
- 自然周周报支持切换日期，包含周使用占比、每日使用柱形图、日均、与上周比较，以及 TOP 6 使用时长和启动排行。点击柱形在图内显示当天时长；排行行不跳转。
- 占比分母为当天或全周的全部应用，启动排行按全部应用启动次数计算。历史缺失不视为零，不完整记录不生成误导性的上周比较。
- 圆环为完整图标预留边界空间；自适应图标、普通图标及已卸载应用的默认图标按可见内容统一尺寸。
- 保留应用使用限制、护眼及睡眠功能；应用限时读取同一计时核心。

安装包在仓库的 [Releases](https://github.com/SkyGitHubwili/eye-rest-assistant/releases) 下载。已有用户使用相同签名的安装包覆盖安装，以保留数据和限时设置。

计时模块源于用户提供的 ScreenLedger 1.0.0 源码，移植代码位于 `mobile/android/src/com/eyerest/app/ledger/`。系统可读取的事件历史有限，无法恢复所有安装前的历史记录，也不读取其他应用的私有数据库。

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
.\tools\test-screenledger.ps1
```

后者覆盖计时核心、自然周聚合及图标边界：12 组计时场景、1,000 组随机时长不变量、周报日期/平均值/排行测试，以及 34,560 组图标边界检查。

构建脚本使用 Android SDK 35、Build Tools 35.0.0 和 JDK 17；请按本机安装目录修改脚本中的 SDK/JDK 路径。签名密钥不纳入版本库；重新生成的开发密钥无法覆盖原签名的安装包。

## Android 3.3.0 固定限制

- 固定小红书 60 分钟、抖音 15 分钟、哔哩哔哩 105 分钟、全部游戏 60 分钟；四类娱乐当天合计最多 180 分钟。
- 小红书和哔哩哔哩连续使用 15 分钟强制休息 2 分钟；每天 6 点后第一次解锁起，四类应用保护 45 分钟。
- 规则页只读，限制不能在应用内修改、删除或临时解锁；游戏按系统游戏分类和已知包名识别，新安装游戏会扫描登记。
- 测试命令：.\\tools\\test-strict-limits.ps1。
