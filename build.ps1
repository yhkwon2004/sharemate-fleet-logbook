param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [switch]$Install
)
$ErrorActionPreference = 'Stop'
$portable = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\work\toolchain'))
if (-not $JavaHome -and (Test-Path -LiteralPath $portable)) {
    $jdk = Get-ChildItem -LiteralPath $portable -Directory -Filter 'jdk-*' | Select-Object -First 1
    if ($jdk) { $JavaHome = $jdk.FullName }
}
if (-not $AndroidSdk -and (Test-Path -LiteralPath (Join-Path $portable 'android-sdk'))) {
    $AndroidSdk = Join-Path $portable 'android-sdk'
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'Set JAVA_HOME to a JDK 17 installation, or pass -JavaHome.'
}
if (-not $AndroidSdk -or -not (Test-Path -LiteralPath $AndroidSdk)) {
    throw 'Set ANDROID_HOME to your Android SDK, or pass -AndroidSdk.'
}
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH
Push-Location $PSScriptRoot
try {
    & .\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Build or verification failed.' }
    if ($Install) {
        & (Join-Path $AndroidSdk 'platform-tools\adb.exe') install -r 'app\build\outputs\apk\debug\app-debug.apk'
        if ($LASTEXITCODE -ne 0) { throw 'APK installation failed; select a single emulator or set ANDROID_SERIAL.' }
    }
} finally {
    Pop-Location
}
