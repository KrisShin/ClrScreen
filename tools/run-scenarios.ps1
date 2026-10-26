# 靶场自动化回归脚本
#
# 逐个启动 demo 的每个场景，采集 ClrScreen 的实际行为，输出对比表。
# 用法：pwsh -File tools/run-scenarios.ps1 [-Label baseline]
param(
    [string]$Label = "run",
    [int]$WaitSec = 6,
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"

# 场景清单：id|期望行为（SKIP=应被点掉, IGNORE=不应被点, ANY=观察项）
$scenarios = @(
    "text_plain|SKIP",
    "text_corner|SKIP",
    "desc_only|SKIP",
    "view_id_skip|SKIP",
    "child_text|SKIP",
    "touch_only|SKIP",
    "english|SKIP",
    "delayed_3s|SKIP",
    "timer_countdown|SKIP",
    "transparent_overlay|SKIP",
    "deep_nested|SKIP",
    "close_ad_text|SKIP",
    "countdown_prefix|SKIP",
    "skip_in_webview|SKIP",
    "fullscreen_clickable|SKIP",
    "guide_skip_step|IGNORE",
    "guide_skip_tutorial|IGNORE",
    "long_sentence|IGNORE",
    "video_seek|IGNORE",
    "canvas_drawn|ANY",
    "image_button|ANY",
    "late_8s|ANY"
)

function Set-Service([bool]$on) {
    if ($on) {
        & $Adb shell settings put secure enabled_accessibility_services `
            com.clrscreen.skipad/com.clrscreen.skipad.SkipAdAccessibilityService 2>&1 | Out-Null
        & $Adb shell settings put secure accessibility_enabled 1 2>&1 | Out-Null
    } else {
        & $Adb shell settings put secure accessibility_enabled 0 2>&1 | Out-Null
        & $Adb shell settings delete secure enabled_accessibility_services 2>&1 | Out-Null
    }
    Start-Sleep -Milliseconds 1200
}

$rows = @()

foreach ($entry in $scenarios) {
    $parts = $entry -split '\|'
    $id = $parts[0]
    $expect = $parts[1]

    Set-Service $true
    & $Adb logcat -c 2>&1 | Out-Null
    & $Adb shell am force-stop com.clrscreen.skipad.demo 2>&1 | Out-Null
    & $Adb shell am start -n com.clrscreen.skipad.demo/.AdScenarioActivity `
        --es scenario $id 2>&1 | Out-Null

    Start-Sleep -Seconds $WaitSec

    $log = (& $Adb logcat -d -s ClrScreenDemo:I 2>&1) -join "`n"
    $clicked = $log -match "SKIPPED id=$id reason=CLICKED"
    $fp = $log -match "FALSE_POSITIVE id=$id"
    $shown = $log -match "SHOWN id=$id"

    $actual = if ($fp) { "FALSE_POSITIVE" } elseif ($clicked) { "CLICKED" } else { "NO_CLICK" }

    $verdict = switch ($expect) {
        "SKIP"   { if ($clicked -and -not $fp) { "PASS" } else { "FAIL" } }
        "IGNORE" { if (-not $clicked -and -not $fp) { "PASS" } else { "FAIL" } }
        default  { "-" }
    }

    $rows += [pscustomobject]@{
        Scenario = $id
        Expect   = $expect
        Actual   = $actual
        Shown    = $shown
        Verdict  = $verdict
    }
    Write-Host ("{0,-22} expect={1,-7} actual={2,-15} {3}" -f $id, $expect, $actual, $verdict)
}

$pass = ($rows | Where-Object { $_.Verdict -eq "PASS" }).Count
$fail = ($rows | Where-Object { $_.Verdict -eq "FAIL" }).Count
Write-Host ""
Write-Host "===== $Label : PASS=$pass FAIL=$fail ====="

$rows | Export-Csv -NoTypeInformation -Encoding UTF8 "tools/result-$Label.csv"
Write-Host "已保存 tools/result-$Label.csv"
