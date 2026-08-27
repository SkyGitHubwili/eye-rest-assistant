# 护眼助手

一个简洁的 Windows + Android 护眼定时器。工作计时结束后，会显示全屏休息画面。

## 功能

- 20/30/45/60 分钟定时（另有 1 分钟测试选项）
- 20 秒至 5 分钟休息时长
- 自选 JPG、PNG、BMP 或 GIF 休息图片
- 多显示器全屏覆盖
- 暂停、重置、立即休息、提前结束
- 设置自动保存到 `F:\护眼助手`
- Android 后台计时及“显示在其他应用上层”全屏提醒
- Windows 与 Android 统一护眼主题图标
- Windows 开机自启、Android 开机/亮屏自动工作
- 可设置每日工作时间段，睡眠时段自动暂停
- 提前结束休息每月最多 3 次，用完后自动锁定按钮
- 界面内置 20-20-20 护眼法说明
- Android 支持关闭、仅当天手动护眼、每日自动护眼三种模式
- Android 暂停后保留剩余时间，重新进入应用不会擅自启动

## 开发运行

```powershell
dotnet run
```

## Windows 单文件发布

```powershell
dotnet restore -r win-x64 --configfile .\NuGet.Config
dotnet publish -c Release -r win-x64 --self-contained true --no-restore -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o F:\EyeRestBuild\windows
```

生成的 `护眼助手.exe` 是自带 .NET 运行环境的单文件，可直接双击运行。

## Android 发布

Android 8.0 及以上系统可用。运行以下命令生成已签名 APK：

```powershell
.\mobile\android\build.ps1
```

APK 输出到 `F:\护眼助手发布\护眼助手-Android.apk`。首次使用请在应用中开启“显示在其他应用上层”，否则休息时间到达时只能显示通知。
