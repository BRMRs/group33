param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [int]$AdbPort = 5037,
    [string]$SigningMd5 = 'de4612d56e3c0dbe3cee3b76bc3ab294',
    [string]$AdbPath = ''
)

$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskApk = Join-Path $taskRoot 'android-app\build\outputs\apk\debug\android-app-debug.apk'

if ([string]::IsNullOrWhiteSpace($AdbPath)) {
    $taskSdk = Get-Content (Join-Path $taskRoot 'local.properties') |
        Where-Object { $_ -match '^sdk\.dir=' } |
        Select-Object -First 1
    if (-not $taskSdk) { throw 'Set -AdbPath or sdk.dir in local.properties.' }
    $taskSdk = $taskSdk.Substring('sdk.dir='.Length).Replace('\:', ':').Replace('\\', '\')
    $AdbPath = Join-Path $taskSdk 'platform-tools\adb.exe'
}
if (-not (Test-Path -LiteralPath $AdbPath)) { throw "ADB not found: $AdbPath" }

try {
    $taskHealth = Invoke-WebRequest 'http://127.0.0.1:8080/health' -TimeoutSec 5 -UseBasicParsing
    if ($taskHealth.StatusCode -ne 200) { throw 'Local phone auth server is not ready.' }
} catch { throw 'Start the local phone auth server before installing the tablet build.' }

Push-Location $taskRoot
try {
    & .\gradlew.bat :android-app:assemblePhoneTest --offline `
        '-PphoneAuthBaseUrl=http://127.0.0.1:8080' '-PphoneAuthLocalTrial=true' "-PphoneAuthSigningMd5=$SigningMd5"
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
    & $AdbPath -P $AdbPort -s $Serial install -r $taskApk
    if ($LASTEXITCODE -ne 0) { throw 'Tablet installation failed.' }
    & $AdbPath -P $AdbPort -s $Serial reverse tcp:8080 tcp:8080
    if ($LASTEXITCODE -ne 0) { throw 'ADB reverse failed.' }

    $taskPackagePath = (& $AdbPath -P $AdbPort -s $Serial shell pm path com.group33.medicationreminder |
        Select-Object -First 1).Trim().Replace('package:', '')
    if (-not $taskPackagePath) { throw 'Installed package not found.' }
    $taskPulledApk = Join-Path $taskRoot 'android-app\build\phone-auth-tablet-installed.apk'
    & $AdbPath -P $AdbPort -s $Serial pull $taskPackagePath $taskPulledApk | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not verify installed APK.' }
    $taskLocalHash = (Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash
    $taskInstalledHash = (Get-FileHash -LiteralPath $taskPulledApk -Algorithm SHA256).Hash
    if ($taskLocalHash -ne $taskInstalledHash) { throw 'Tablet APK differs from the latest build.' }

    & $AdbPath -P $AdbPort -s $Serial shell am force-stop com.group33.medicationreminder
    & $AdbPath -P $AdbPort -s $Serial shell am start -n com.group33.medicationreminder/.SharedLoginActivity
    Write-Host "Installed and verified tablet APK: $taskLocalHash"
} finally {
    Pop-Location
}
