$ErrorActionPreference = 'Stop'
$Project = Split-Path $PSScriptRoot -Parent
$Java = 'D:\Software\Java\jdk-17.0.20+8\bin'
$Output = Join-Path $PSScriptRoot 'screenledger-test-output'
New-Item -ItemType Directory -Path $Output -Force | Out-Null
& "$Java\javac.exe" -encoding UTF-8 -d $Output (Join-Path $Project 'mobile\android\src\com\eyerest\app\ledger\UsageEngine.java') (Join-Path $Project 'mobile\android\src\com\eyerest\app\ledger\WeeklyReport.java') (Join-Path $PSScriptRoot 'ScreenLedgerEngineTest.java') (Join-Path $PSScriptRoot 'WeeklyReportTest.java')
if($LASTEXITCODE -ne 0){throw 'ScreenLedger test compilation failed'}
& "$Java\java.exe" -cp $Output com.eyerest.app.ledger.ScreenLedgerEngineTest
if($LASTEXITCODE -ne 0){throw 'ScreenLedger engine test failed'}
& "$Java\java.exe" -cp $Output com.eyerest.app.ledger.WeeklyReportTest
if($LASTEXITCODE -ne 0){throw 'Weekly report test failed'}
& "$Java\javac.exe" -encoding UTF-8 -d $Output (Join-Path $Project 'mobile\android\src\com\eyerest\app\ledger\RingIconLayout.java') (Join-Path $PSScriptRoot 'RingIconLayoutTest.java')
if($LASTEXITCODE -ne 0){throw 'Ring layout test compilation failed'}
& "$Java\java.exe" -cp $Output com.eyerest.app.ledger.RingIconLayoutTest
if($LASTEXITCODE -ne 0){throw 'Ring icon bounds test failed'}
