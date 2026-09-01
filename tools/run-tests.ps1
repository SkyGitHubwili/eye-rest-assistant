$ErrorActionPreference = 'Stop'

$ProjectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$JavaRoot = 'D:\Software\Java\jdk-17.0.20+8'
$AndroidJar = 'D:\Software\AndroidSDK\platforms\android-35\android.jar'
$TestRoot = 'F:\SleepAssistantBuild\logic-tests'

if (-not $TestRoot.StartsWith('F:\SleepAssistantBuild\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Test output escaped F:\SleepAssistantBuild.'
}
if (Test-Path -LiteralPath $TestRoot) { Remove-Item -LiteralPath $TestRoot -Recurse -Force }
New-Item -ItemType Directory -Path (Join-Path $TestRoot 'health'), (Join-Path $TestRoot 'sleep') -Force | Out-Null

$HealthSources = @(
    'mobile\android\src\com\eyerest\app\HealthModels.java',
    'mobile\android\src\com\eyerest\app\UsageStatsCalculator.java',
    'mobile\android\src\com\eyerest\app\HealthScoreCalculator.java',
    'mobile\android\src\com\eyerest\app\AppLimit.java',
    'tools\HealthUsageTest.java'
) | ForEach-Object { Join-Path $ProjectRoot $_ }
& "$JavaRoot\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -d (Join-Path $TestRoot 'health') @HealthSources
if ($LASTEXITCODE -ne 0) { throw 'Health logic compilation failed' }
& "$JavaRoot\bin\java.exe" -cp (Join-Path $TestRoot 'health') HealthUsageTest
if ($LASTEXITCODE -ne 0) { throw 'HealthUsageTest failed' }

$SleepSources = @(
    (Join-Path $ProjectRoot 'mobile\android\src\com\eyerest\app\SleepSettings.java'),
    (Join-Path $ProjectRoot 'tools\SleepSettingsTest.java')
)
& "$JavaRoot\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath $AndroidJar -d (Join-Path $TestRoot 'sleep') @SleepSources
if ($LASTEXITCODE -ne 0) { throw 'Sleep logic compilation failed' }
& "$JavaRoot\bin\java.exe" -cp "$(Join-Path $TestRoot 'sleep');$AndroidJar" SleepSettingsTest
if ($LASTEXITCODE -ne 0) { throw 'SleepSettingsTest failed' }
