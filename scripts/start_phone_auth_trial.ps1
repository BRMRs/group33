param([string]$SchemeCode = "")

$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskJar = Join-Path $taskRoot 'server\build\libs\server-0.1.0.jar'
if (-not (Test-Path -LiteralPath $taskJar)) {
    throw 'Build the server first: .\gradlew.bat :server:bootJar'
}
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Port 8080 is already in use. Stop the existing service before starting the trial.'
}
if ([string]::IsNullOrWhiteSpace($SchemeCode)) {
    $SchemeCode = Read-Host 'Fusion authentication scheme code'
}
if ([string]::IsNullOrWhiteSpace($SchemeCode)) { throw 'A scheme code is required.' }

function Read-HiddenValue([string]$prompt) {
    $secure = Read-Host -Prompt $prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

$taskStateDir = Join-Path $env:LOCALAPPDATA 'Group33PhoneAuthTrial'
New-Item -ItemType Directory -Path $taskStateDir -Force | Out-Null
function Get-LocalSecret([string]$name) {
    $path = Join-Path $taskStateDir ($name + '.protected')
    if (-not (Test-Path -LiteralPath $path)) {
        $bytes = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        $plain = [Convert]::ToBase64String($bytes)
        $protected = ConvertFrom-SecureString (ConvertTo-SecureString $plain -AsPlainText -Force)
        [IO.File]::WriteAllText($path, $protected)
    }
    $secure = ConvertTo-SecureString ([IO.File]::ReadAllText($path))
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

Write-Host 'Enter the RAM AccessKey in this terminal. It will not be printed or saved by this script.'
$taskAccessId = Read-HiddenValue 'AccessKey ID'
$taskAccessSecret = Read-HiddenValue 'AccessKey Secret'
if ([string]::IsNullOrWhiteSpace($taskAccessId) -or [string]::IsNullOrWhiteSpace($taskAccessSecret)) {
    throw 'Both RAM AccessKey values are required.'
}

$taskEnv = @{
    ALIBABA_CLOUD_ACCESS_KEY_ID = $taskAccessId
    ALIBABA_CLOUD_ACCESS_KEY_SECRET = $taskAccessSecret
    PHONE_AUTH_ENABLED = 'true'
    PHONE_AUTH_SCHEME_CODE = $SchemeCode
    PHONE_AUTH_PACKAGE_SIGN = 'de4612d56e3c0dbe3cee3b76bc3ab294'
    PHONE_AUTH_DATABASE_URL = 'jdbc:h2:file:' + ((Join-Path $taskStateDir 'accounts').Replace('\', '/')) + ';MODE=PostgreSQL'
    PHONE_AUTH_DATABASE_USER = 'sa'
    PHONE_AUTH_DATABASE_PASSWORD = Get-LocalSecret 'database-password'
    PHONE_AUTH_HASH_KEY = Get-LocalSecret 'phone-hash-key'
    JWT_SECRET = Get-LocalSecret 'jwt-secret'
    SERVER_ADDRESS = '127.0.0.1'
    PORT = '8080'
}
$previousEnv = @{}
foreach ($key in $taskEnv.Keys) {
    $previousEnv[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    [Environment]::SetEnvironmentVariable($key, $taskEnv[$key], 'Process')
}
try {
    Write-Host 'Starting the local trial server on 127.0.0.1:8080. Leave this window open.'
    & java -jar $taskJar
} finally {
    foreach ($key in $taskEnv.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previousEnv[$key], 'Process')
    }
    $taskAccessId = $null
    $taskAccessSecret = $null
}
