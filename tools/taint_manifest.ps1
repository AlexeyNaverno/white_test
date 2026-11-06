@echo off
REM -Level 1: UI only (drop services/receiver/VPN-fgs permissions)
REM -Level 2: + DPI  (Tor service dropped)
REM -Level 3: + VPN (Tor service dropped)
REM -Level 4: full   (no filtering; file still rewrites versionName nothing)
param(
    [int]$Level,
    [string]$In,
    [string]$Out
)
$ErrorActionPreference = 'Stop'
$src = Get-Content -Raw -Encoding UTF8 $In

if ($Level -eq 1) {
    $src = [regex]::Replace($src, '(?s)\s*<service\b[^>]*?(?:/>|>.*?</service>)', '')
    $src = [regex]::Replace($src, '(?s)\s*<receiver\b[^>]*?(?:/>|>.*?</receiver>)', '')
    foreach ($p in @(
        'android.permission.FOREGROUND_SERVICE',
        'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
        'android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED',
        'android.permission.RECEIVE_BOOT_COMPLETED',
        'android.permission.WAKE_LOCK',
        'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS'
    )) {
        $src = [regex]::Replace($src, '(?s)\s*<uses-permission[^>]*android:name="' + [regex]::Escape($p) + '"[^>]*/>', '')
    }
} elseif ($Level -eq 2 -or $Level -eq 3) {
    $src = [regex]::Replace($src, '(?s)\s*<service\b[^>]*?TorService[^>]*?(?:/>|>.*?</service>)', '')
} elseif ($Level -ne 4) {
    throw "TAINT level must be 1..4"
}

if ($Level -ne 4) {
    $src = $src -replace 'android:versionName="1.0"', "android:versionName=`"1.0-test$Level`""
}

Set-Content -Encoding UTF8 -NoNewline -Path $Out -Value $src
Write-Host "[taint] level $Level manifest written to $Out"
