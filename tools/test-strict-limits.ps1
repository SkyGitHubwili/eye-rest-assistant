$ErrorActionPreference='Stop'
$Project=Split-Path $PSScriptRoot -Parent
$Java='D:\Software\Java\jdk-17.0.20+8\bin'
$Output=Join-Path $PSScriptRoot 'strict-test-output'
New-Item -ItemType Directory -Path $Output -Force | Out-Null
& "$Java\javac.exe" -encoding UTF-8 -d $Output (Join-Path $Project 'mobile/android/src/com/eyerest/app/ledger/UsageEngine.java') (Join-Path $Project 'mobile/android/src/com/eyerest/app/StrictLimitPolicy.java') (Join-Path $PSScriptRoot 'StrictLimitPolicyTest.java')
if($LASTEXITCODE -ne 0){throw 'Strict limit compilation failed'}
& "$Java\java.exe" -cp $Output com.eyerest.app.StrictLimitPolicyTest
if($LASTEXITCODE -ne 0){throw 'Strict limit checks failed'}
