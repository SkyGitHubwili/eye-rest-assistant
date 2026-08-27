$ErrorActionPreference = 'Stop'

$SourceRoot = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$ProjectRoot = 'F:\EyeRestBuild\android'
$SdkRoot = 'D:\Software\AndroidSDK'
$JavaRoot = 'D:\Software\Java\jdk-17.0.20+8'
$BuildTools = Join-Path $SdkRoot 'build-tools\35.0.0'
$AndroidJar = Join-Path $SdkRoot 'platforms\android-35\android.jar'
$BuildDir = Join-Path $ProjectRoot 'build'
$OutputDir = 'F:\护眼助手发布'

if (-not $ProjectRoot.StartsWith('F:\EyeRestBuild\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Staging directory escaped the expected F:\EyeRestBuild folder.'
}
if (Test-Path -LiteralPath $ProjectRoot) { Remove-Item -LiteralPath $ProjectRoot -Recurse -Force }
New-Item -ItemType Directory -Path $ProjectRoot -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $SourceRoot 'AndroidManifest.xml') -Destination $ProjectRoot
Copy-Item -LiteralPath (Join-Path $SourceRoot 'res') -Destination $ProjectRoot -Recurse
Copy-Item -LiteralPath (Join-Path $SourceRoot 'src') -Destination $ProjectRoot -Recurse
New-Item -ItemType Directory -Path $BuildDir, (Join-Path $BuildDir 'compiled'), (Join-Path $BuildDir 'gen'), (Join-Path $BuildDir 'classes'), (Join-Path $BuildDir 'dex'), $OutputDir -Force | Out-Null

$env:JAVA_HOME = $JavaRoot
$env:Path = "$JavaRoot\bin;$BuildTools;$env:Path"

& "$BuildTools\aapt2.exe" compile --dir (Join-Path $ProjectRoot 'res') -o (Join-Path $BuildDir 'compiled\resources.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

$Unsigned = Join-Path $BuildDir 'unsigned.apk'
& "$BuildTools\aapt2.exe" link -o $Unsigned -I $AndroidJar --manifest (Join-Path $ProjectRoot 'AndroidManifest.xml') --java (Join-Path $BuildDir 'gen') --min-sdk-version 26 --target-sdk-version 35 (Join-Path $BuildDir 'compiled\resources.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

$JavaFiles = @(Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'src') -Filter '*.java' -Recurse | ForEach-Object FullName)
& "$JavaRoot\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath $AndroidJar -d (Join-Path $BuildDir 'classes') @JavaFiles (Join-Path $BuildDir 'gen\com\eyerest\app\R.java')
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

$ClassFiles = @(Get-ChildItem -LiteralPath (Join-Path $BuildDir 'classes') -Filter '*.class' -Recurse | ForEach-Object FullName)
& "$BuildTools\d8.bat" --lib $AndroidJar --min-api 26 --output (Join-Path $BuildDir 'dex') @ClassFiles
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Copy-Item -LiteralPath (Join-Path $BuildDir 'dex\classes.dex') -Destination (Join-Path $BuildDir 'classes.dex')
Push-Location $BuildDir
try { & "$BuildTools\aapt.exe" add $Unsigned 'classes.dex' } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'Adding dex failed' }

$Aligned = Join-Path $BuildDir 'aligned.apk'
& "$BuildTools\zipalign.exe" -f 4 $Unsigned $Aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

$KeyStore = Join-Path $SourceRoot 'eyerest-debug.keystore'
if (-not (Test-Path -LiteralPath $KeyStore)) {
    & "$JavaRoot\bin\keytool.exe" -genkeypair -keystore $KeyStore -storepass android -keypass android -alias eyerest -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=EyeRest, O=Personal, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

$FinalApk = Join-Path $OutputDir '护眼助手-Android.apk'
& "$BuildTools\apksigner.bat" sign --ks $KeyStore --ks-key-alias eyerest --ks-pass pass:android --key-pass pass:android --out $FinalApk $Aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
& "$BuildTools\apksigner.bat" verify --verbose $FinalApk
if ($LASTEXITCODE -ne 0) { throw 'APK verification failed' }

Get-Item -LiteralPath $FinalApk | Select-Object FullName, Length, LastWriteTime
